#!/usr/bin/env python3
"""Exercise PlanetryCore inside a disposable Paper 26.1.2 server.

Usage: python3 scripts/smoke.py target/planetry-core-VERSION.jar [--mail|--hotbar|--language|--adventure] [--without-hooks]
Requires JAVA_HOME (JDK 25) and target/test-classpath.txt from Maven.
CORE_TEST_TYPE defaults to file; postgresql/mariadb use CORE_TEST_* variables
pointing to a throwaway database. Never reads production configuration.
Uses controlled player doubles rather than network-connected Minecraft clients.
The temporary test server accepts Mojang's server EULA.
"""

import hashlib
import argparse
import json
import os
import pathlib
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile


ROOT = pathlib.Path(__file__).resolve().parent.parent
PAPER_SHA = "1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_SHA}/paper-26.1.2-74.jar"
PAPI_SHA = "fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437"
PAPI_URL = "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar"


def fetch(url, expected):
    request = urllib.request.Request(
        url, headers={"User-Agent": "PlanetryCore-smoke/1 (https://github.com/FlintSH/planetry-core)"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch for {url}")
    return data


def build_fixture(java, classpath, work, name, source, dependencies=(), vault_api=None):
    classes = work / (source + "-classes")
    classes.mkdir()
    subprocess.run(
        [str(java.with_name("javac")), "-cp", classpath, "-d", str(classes),
         str(ROOT / "scripts/probe" / (source + ".java")),
         *([str(path) for path in sorted((ROOT / "scripts/probe/nexo").glob("*.java"))]
           if source == "NexoFixture" else [])], check=True
    )
    if vault_api is not None:
        # Test-only Vault plugin owns the unmodified provided Economy API classes.
        # These never enter the PlanetryCore release JAR.
        with zipfile.ZipFile(vault_api) as archive:
            for entry in archive.infolist():
                if entry.filename.startswith("net/milkbowl/vault/economy/") and entry.filename.endswith(".class"):
                    target = classes / entry.filename
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(entry))
    descriptor = (
        f"name: {name}\nversion: '1'\nmain: dev.flint.planetrycore.probe.{source}\n"
        "api-version: '26.1.2'\n"
    )
    if dependencies:
        descriptor += "depend: [" + ", ".join(dependencies) + "]\n"
    if source == "CoreProbe":
        descriptor += "commands:\n  coreprobeaward:\n    description: Test-only console reward recorder\n"
    if source == "HotbarProbe":
        descriptor += "commands:\n  hotbarprobe:\n    description: Test-only hotbar action recorder\n"
    if source == "MailProbe":
        descriptor += "commands:\n  mailprobeaward:\n    description: Test-only console mail recorder\n"
    (classes / "plugin.yml").write_text(descriptor, encoding="utf-8")
    subprocess.run(
        [str(java.with_name("jar")), "--create", "--file", str(work / "plugins" / (source + ".jar")),
         "-C", str(classes), "."], check=True
    )


def build_rpg_fixtures(java, classpath, work, install=True):
    """Controlled MMOItems/AuraSkills/MythicMobs public API shapes, excluded from release."""
    classes = work / "rpg-classes"
    classes.mkdir()
    subprocess.run([str(java.with_name("javac")), "-cp", classpath, "-d", str(classes),
                    *map(str, sorted((ROOT / "scripts/probe/rpg").rglob("*.java")))], check=True)
    definitions = [
        ("MMOItems", "net.Indyuce.mmoitems.MMOItems", ["net"]),
        ("AuraSkills", "dev.flint.planetrycore.probe.AuraFixture", ["dev/aurelium", "dev/flint/planetrycore/probe/AuraFixture"]),
        ("MythicMobs", "dev.flint.planetrycore.probe.MythicFixture", ["io", "dev/flint/planetrycore/probe/MythicFixture"]),
    ]
    for name, main, prefixes in (definitions if install else []):
        jar = work / "plugins" / (name + ".jar")
        with zipfile.ZipFile(jar, "w") as archive:
            for path in classes.rglob("*.class"):
                relative = path.relative_to(classes).as_posix()
                if any(relative.startswith(prefix) for prefix in prefixes):
                    archive.write(path, relative)
            archive.writestr("plugin.yml", f"name: {name}\nversion: 'fixture-1'\nmain: {main}\napi-version: '26.1.2'\n")
    return os.pathsep.join([classpath, str(classes)])


def adventure_configs(work):
    (work / "plugins/PlanetryCore/harvest.yml").write_text("""enabled: true
max-nodes: 100
poll-ticks: 1
profiles:
  ore:
    source: OBSIDIAN
    cooldown-block: BEDROCK
    cooldown-seconds: 2
    growth-stages: false
    skill: mining
    skill-xp: 3.5
    drops: [{type: MATERIAL, id: ORE, amount: 2}]
  crop:
    source: WHEAT
    cooldown-block: AIR
    cooldown-seconds: 2
    growth-stages: true
    skill: farming
    skill-xp: 2.0
    drops: [{type: MATERIAL, id: CROP, amount: 1}]
""", encoding="utf-8")
    (work / "plugins/PlanetryCore/journal.yml").write_text("""categories:
  farming:
    entries:
      first_crop:
        mmoitems-type: MATERIAL
        mmoitems-id: CROP
        harvest-nodes: [crop]
  foraging: {}
  fighting:
    entries:
      first_mob:
        mmoitems-type: MATERIAL
        mmoitems-id: MOB
        mythic-mobs: [ProbeMob]
  mining:
    entries:
      first_ore:
        mmoitems-type: MATERIAL
        mmoitems-id: ORE
        harvest-nodes: [ore]
  fishing: {}
  rockets: {}
  achievements:
    entries:
      command_entry:
        mmoitems-type: MATERIAL
        mmoitems-id: ORE
messages:
  unlock: ['<green>DISCOVERY {item} {open}', '<gray>{category} {unlocked}/{max}']
""", encoding="utf-8")


def test_config(plugin, without_hooks=False, mail=False, hotbar=False, language=False, adventure=False):
    """Return isolated test configuration; all credentials are test-only inputs."""
    engine = os.environ.get("CORE_TEST_TYPE", "file").lower()
    if engine not in {"file", "postgresql", "mariadb"}:
        raise ValueError("CORE_TEST_TYPE must be file, postgresql or mariadb")
    port = os.environ.get("CORE_TEST_PORT", "5432" if engine == "postgresql" else "3306")
    prefix = "smoke_" + os.urandom(5).hex() + "_"
    quote = json.dumps
    commands = "[]" if without_hooks or mail or hotbar or language or adventure else "['coreprobeaward {player} {level} {money}']"
    config = f"""server-id: {quote('smoke-' + os.urandom(5).hex())}
storage:
  type: {engine}
  host: {quote(os.environ.get('CORE_TEST_HOST', '127.0.0.1'))}
  port: {int(port)}
  database: {quote(os.environ.get('CORE_TEST_DATABASE', 'planetry_core_test'))}
  username: {quote(os.environ.get('CORE_TEST_USER', 'planetry'))}
  password: ''
  password-env: CORE_TEST_PASSWORD
  table-prefix: {prefix}
  ssl-mode: disable
display:
  xp-bar: true
  refresh-ticks: 20
  data-refresh-ticks: 100
rewards:
  money: {0.0 if mail or hotbar or language or adventure else 150.0}
  currency-label: Credits
  commands: {commands}
"""
    if hotbar:
        config += """hotbar:
  enabled: true
  restore-interval-ticks: 2
  activation-cooldown-ticks: 6
  quest-book:
    nexo-id: quest_book
    command: 'hotbarprobe quest {player} {uuid}'
    executor: player
  novalink:
    nexo-id: novalink
    command: 'hotbarprobe menu {player} {uuid}'
    executor: console
"""
    else:
        # Legacy player doubles deliberately do not implement inventory gameplay.
        config += "hotbar:\n  enabled: false\n"
    if mail:
        # A registered test expansion exercises actual PAPI without LuckPerms.
        config += "mail:\n  rank-placeholder: '%mailprobe_rank%'\n  fallback-rank: '&cStaff'\n"
    with zipfile.ZipFile(plugin) as archive:
        for language in ("en", "de"):
            if f"lang/{language}.yml" not in archive.namelist():
                raise RuntimeError(f"Packaged language file is missing: {language}")
    config += "language: en\n"
    return engine, config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plugin", type=pathlib.Path)
    parser.add_argument("--hotbar", action="store_true", help="run independent locked-hotbar coverage; hooks mode uses a controlled Nexo API fixture")
    parser.add_argument("--mail", action="store_true", help="run independent mail acceptance coverage instead of the XP probe")
    parser.add_argument("--language", action="store_true", help="run language/reload coverage; hooks mode uses actual PlaceholderAPI and controlled native Nexo tag resolvers")
    parser.add_argument("--adventure", action="store_true", help="run harvest and Journal acceptance using controlled MMOItems/AuraSkills/MythicMobs API fixtures")
    parser.add_argument("--without-hooks", action="store_true", help="run without Vault, PlaceholderAPI, Inventory Sync or Nexo")
    arguments = parser.parse_args()
    if sum((arguments.mail, arguments.hotbar, arguments.language, arguments.adventure)) > 1:
        parser.error("--mail, --hotbar, --language and --adventure select separate probes")
    java = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java"
    plugin = arguments.plugin.resolve()
    entries = (ROOT / "target/test-classpath.txt").read_text(encoding="utf-8").strip().split(os.pathsep)
    vault_api = next((pathlib.Path(entry) for entry in entries if pathlib.Path(entry).name.lower().startswith("vaultapi-")), None)
    if vault_api is None and not arguments.without_hooks:
        raise RuntimeError("Maven test classpath must contain the provided VaultAPI dependency")
    classpath = os.pathsep.join([str(plugin), *entries])
    paper = fetch(PAPER_URL, PAPER_SHA)
    papi = None if arguments.without_hooks else fetch(PAPI_URL, PAPI_SHA)
    os.environ.setdefault("CORE_TEST_PASSWORD", "")
    engine, config = test_config(plugin, arguments.without_hooks, arguments.mail, arguments.hotbar, arguments.language, arguments.adventure)
    with tempfile.TemporaryDirectory(prefix="planetry-core-paper-") as temporary:
        work = pathlib.Path(temporary)
        (work / "plugins/PlanetryCore").mkdir(parents=True)
        shutil.copyfile(plugin, work / "plugins/core.jar")
        (work / "plugins/PlanetryCore/config.yml").write_text(config, encoding="utf-8")
        if papi is not None:
            (work / "plugins/PlaceholderAPI").mkdir()
            (work / "plugins/PlaceholderAPI.jar").write_bytes(papi)
            (work / "plugins/PlaceholderAPI/config.yml").write_text("check_updates: false\n", encoding="utf-8")
        (work / "paper.jar").write_bytes(paper)
        (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (work / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n"
            "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
            "enable-status=false\npause-when-empty-seconds=-1\n"
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n',
            encoding="utf-8"
        )
        if arguments.adventure:
            adventure_configs(work)
        if arguments.without_hooks and not arguments.mail and not arguments.hotbar and not arguments.language and not arguments.adventure:
            build_fixture(java, classpath, work, "NoHooksProbe", "NoHooksProbe", ("PlanetryCore",))
        elif not arguments.without_hooks:
            build_fixture(java, classpath, work, "Vault", "VaultFixture", vault_api=vault_api)
            build_fixture(java, classpath, work, "NetworkInventorySync", "InventoryFixture")
        if arguments.adventure:
            dependencies = ("PlanetryCore",) if arguments.without_hooks else ("PlanetryCore", "PlaceholderAPI", "NetworkInventorySync", "MMOItems", "AuraSkills", "MythicMobs")
            classpath = build_rpg_fixtures(java, classpath, work, install=not arguments.without_hooks)
            build_fixture(java, classpath, work, "AdventureProbe", "AdventureProbe", dependencies)
        elif arguments.language:
            dependencies = ("PlanetryCore",) if arguments.without_hooks else (
                "PlanetryCore", "PlaceholderAPI", "Vault", "NetworkInventorySync", "Nexo")
            if not arguments.without_hooks:
                build_fixture(java, classpath, work, "Nexo", "NexoFixture")
            build_fixture(java, classpath, work, "LanguageProbe", "LanguageProbe", dependencies)
        elif arguments.hotbar:
            dependencies = ("PlanetryCore",) if arguments.without_hooks else ("PlanetryCore", "NetworkInventorySync", "Nexo")
            if not arguments.without_hooks:
                build_fixture(java, classpath, work, "Nexo", "NexoFixture")
            build_fixture(java, classpath, work, "HotbarProbe", "HotbarProbe", dependencies)
        elif arguments.mail:
            dependencies = ("PlanetryCore",) if arguments.without_hooks else (
                "PlanetryCore", "PlaceholderAPI", "Vault", "NetworkInventorySync")
            build_fixture(java, classpath, work, "MailProbe", "MailProbe", dependencies)
        elif not arguments.without_hooks:
            build_fixture(java, classpath, work, "CoreProbe", "CoreProbe",
                          ("PlanetryCore", "PlaceholderAPI", "Vault", "NetworkInventorySync"))
        process = subprocess.Popen(
            [str(java), "-Xms256m", "-Xmx768m", "-jar", "paper.jar", "--nogui"], cwd=work,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace"
        )
        output = queue.Queue()
        lines = []

        def read_output():
            for line in process.stdout:
                output.put(line)

        threading.Thread(target=read_output, daemon=True).start()
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                try:
                    lines.append(output.get(timeout=1))
                except queue.Empty:
                    if process.poll() is not None:
                        break
            else:
                raise TimeoutError("PlanetryCore Paper probe timed out")
            while not output.empty():
                lines.append(output.get_nowait())
            log = "".join(lines)
            success = "CORE_ADVENTURE_PROBE_OK" if arguments.adventure else "CORE_LANGUAGE_PROBE_OK" if arguments.language else "CORE_HOTBAR_PROBE_OK" if arguments.hotbar else "CORE_MAIL_PROBE_OK" if arguments.mail else (
                "CORE_NO_HOOKS_PROBE_OK" if arguments.without_hooks else "CORE_PROBE_OK")
            if success not in log or any(marker in log for marker in (
                    "CORE_PROBE_FAILED", "CORE_MAIL_PROBE_FAILED", "CORE_HOTBAR_PROBE_FAILED", "CORE_LANGUAGE_PROBE_FAILED", "CORE_ADVENTURE_PROBE_FAILED")):
                raise AssertionError("PlanetryCore probe failed")
            if process.returncode != 0:
                raise AssertionError(f"Paper exited {process.returncode}")
            if arguments.adventure:
                checks = "harvest and Journal: controlled RPG API fixtures, binding, growth, drops, readiness, permissions, menus, placeholders and triggers" if not arguments.without_hooks else "harvest integration refusal and Journal commands without optional hooks"
            elif arguments.language:
                checks = "language-only reload, permissions/arguments/completion, locale switching, failure retention, recipient-aware Core/mail formatting and literal runtime values"
                checks += "; actual PlaceholderAPI and controlled native Nexo tag API fixture" if not arguments.without_hooks else "; no optional hooks"
            elif arguments.hotbar:
                checks = "locked hotbar, item preservation, commands, lifecycle and readiness; controlled Nexo API fixture" if not arguments.without_hooks else "Nexo-only hotbar safely absent without optional hooks"
            elif arguments.mail:
                checks = "mail commands, reminders, explicit claims, permission/session/readiness gates, broadcast snapshots, bounded batches and failure review/ack"
                if arguments.without_hooks:
                    checks += " without Vault/PlaceholderAPI/Inventory Sync"
                else:
                    checks += " with actual PlaceholderAPI and optional-hook fixtures"
            else:
                checks = "startup without Vault/PlaceholderAPI/Inventory Sync, commands and XP display" if arguments.without_hooks else "startup, commands, reward failure/recovery, PlaceholderAPI and XP display"
            print(f"PASS: real Paper 26.1.2 PlanetryCore {checks}, {engine} storage")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            while not output.empty():
                lines.append(output.get_nowait())
            print("".join(lines))


if __name__ == "__main__":
    main()
