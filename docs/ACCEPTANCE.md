# Validation

Use Java 25. Never point these tests at production databases. Database tests
create randomly prefixed tables and remove only their own test tables. Paper
probes create random tables in their disposable test database and remove their
temporary server directories. Destroy the test database afterwards.

```sh
export CORE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/planetry_core_test
export CORE_TEST_POSTGRES_USER=planetry
export CORE_TEST_POSTGRES_PASSWORD=core-test-only
export CORE_TEST_MARIADB_URL=jdbc:mariadb://127.0.0.1:3306/planetry_core_test
export CORE_TEST_MARIADB_USER=planetry
export CORE_TEST_MARIADB_PASSWORD=core-test-only
mvn -B -ntp clean verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
python3 scripts/smoke.py target/planetry-core-1.0.0.jar
python3 scripts/smoke.py target/planetry-core-1.0.0.jar --without-hooks
CORE_TEST_TYPE=postgresql CORE_TEST_PORT=5432 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar
CORE_TEST_TYPE=mariadb CORE_TEST_PORT=3306 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar
```

The database suites are skipped unless their URL variables are present. CI
provides both real engines. Local file/domain tests need no external database.
For database probes, `CORE_TEST_HOST` defaults to 127.0.0.1 and
`CORE_TEST_DATABASE` defaults to planetry_core_test. Set `JAVA_HOME` for the probes.

Automated checks cover arithmetic boundaries, negatives/overflow, multi-level
carry, XP floor/cap, administrative changes, reward high-water behavior, durable
restart, corruption preservation, wrong/stale claim rejection, independent
backend concurrency, transactional rollback and ambiguous commit outcomes.

The runtime probe downloads checksum-pinned Paper 26.1.2 build 74 and actual
PlaceholderAPI 2.12.3. It starts a temporary flat server on loopback with an
ephemeral port and 768 MiB heap. Test-only economy and Inventory Sync readiness
fixtures use controlled players. It verifies commands/permissions, reward money
and console substitutions, PAPI expansion, display packets, rejoin persistence,
readiness gating for both rewards and display, unavailable/declined economy
providers, review acknowledgement, and restoration of unchanged vanilla XP. The
`--without-hooks` probe omits all optional plugin JARs and verifies basic
commands, persistence and display still work. Fixtures never ship
inside the release plugin. Running the probe accepts Mojang's server EULA for
the disposable server.

## Operator staging checklist

- Test a real player using `/level` and `/levels` without staff permissions.
  Confirm give/take/setlevel permissions with the network permission provider.
- Check exactly 99→100 XP and a multi-level grant. Confirm the game XP display,
  chat colors and messages with a real client, including death and respawn.
- Confirm vanilla XP pickup does not advance the bar, death does not lower it,
  and mending and survival enchanting/anvil XP costs are blocked by default.
  Verify game XP still advances through quest/staff commands. Test the optional
  `vanilla-experience.lock: false` mode separately if the operator enables it.
  Test display refresh and disable it if another plugin should own the bar.
- With the actual economy and quest/skill plugins, confirm 150 Credits per new
  level (or configured reward), command execution and configured XP earning.
- With shared storage, transfer across backends with Inventory Sync and the
  teleport bridge. Confirm the same game progress, vanilla XP, no duplicate
  rewards and the expected cache refresh delay for remote staff changes.
- Check TAB/quest placeholders and fail-closed behavior for loading/unavailable
  data. Confirm the actual Purpur build and complete production plugin set on
  staging; a Paper fixture probe does not prove these combinations.
- During a controlled test-only outage, verify progress is not confirmed until
  saved, then inspect and reconcile any uncertain reward claim. Keep backups.

## Mail regression coverage

The common mail store contract runs against durable file storage and both real
SQL engines. It covers offline and all-online sends, persistent names, restart
recovery, exclusive file ownership, fenced sessions, concurrent claims, retained
uncertain claims and console acknowledgement. SQL tests also cover rollback,
ambiguous commits and network presence expiry using the database clock.

Run the packaged mail probe in addition to the progression probes above:

```sh
python3 scripts/smoke.py target/planetry-core-1.0.0.jar --mail
python3 scripts/smoke.py target/planetry-core-1.0.0.jar --mail --without-hooks
CORE_TEST_TYPE=postgresql CORE_TEST_PORT=5432 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar --mail
CORE_TEST_TYPE=mariadb CORE_TEST_PORT=3306 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar --mail
```

MailProbe uses controlled player doubles in real Paper, rather than connected
Minecraft clients. It checks mail permission enforcement, offline names and join
reminders, clickable claim components, console and staff identity, recipient
substitution, all-online targeting, repeated claim prevention, command failure
review/acknowledgement and optional-hook absence. With the readiness fixture it
also checks that mail cannot be claimed before inventory authority is ready.
CI runs these checks on the same packaged JAR that it releases.

On staging, use two actual clients and backends sharing the database. Send to a
player on the other backend, send to a disconnected player, send `all`, and
transfer before claiming. Confirm the rank provider, green clickable prompt and
command results with the real economy/item plugins. Test the production Purpur
build and full plugin set there. Check inventory capacity and the chosen reward
command's behavior when a player's inventory is full. Core does not determine
whether another plugin silently dropped or rejected a gift. Do not run these
checks on production or infer a real-client result from the automated fixtures.

## Hotbar validation

The hotbar configuration and event regression tests run with the regular suite.
The following independent probes run the **same packaged JAR** in the pinned
Paper server, using real ItemStacks and controlled Player/Inventory/View doubles:

```sh
python3 scripts/smoke.py target/planetry-core-1.0.0.jar --hotbar
python3 scripts/smoke.py target/planetry-core-1.0.0.jar --hotbar --without-hooks
CORE_TEST_TYPE=postgresql CORE_TEST_PORT=5432 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar --hotbar
CORE_TEST_TYPE=mariadb CORE_TEST_PORT=3306 CORE_TEST_USER=planetry CORE_TEST_PASSWORD=core-test-only python3 scripts/smoke.py target/planetry-core-1.0.0.jar --hotbar
```

Hooks mode uses a controlled **Nexo API-shape fixture**, not the actual Nexo
plugin. It checks custom metadata preservation and unknown-ID refusal and later recovery; the
without-hooks mode checks missing-Nexo refusal without ordinary fallback items. Fixtures never ship in the
plugin JAR. Existing XP/mail probes disable hotbar because their player doubles
do not model inventory interactions. CI retains those eight probes and adds
four hotbar modes.

Before installation, staging still needs real Minecraft clients with the actual
Nexo version/resource pack, Purpur and complete plugin set. Check right clicks on
air/blocks/entities, quest/menu opening and permissions, creative/normal inventory
screens, drag/shift/number-key/offhand/drop paths, death with both keep-inventory
settings, full inventory, and actual server transfers while Inventory Sync loads.
Confirm ordinary items are preserved and both shortcuts appear in slots 8/9 on
every participating backend. These controlled probes do not prove client rendering
or network-transfer compatibility. No production installation is automated.

## MiniMessage, languages and mail presentation

Unit coverage verifies locale selection, per-key fallback, preservation of custom
translations and one-time legacy configuration migration, MiniMessage/legacy
formatting, literal dynamic values, argument-free `/mail` completion and rendered
rank spacing across formatting boundaries. The mail Paper probe verifies the
exact stored command in both individual and broadcast send confirmations,
including a command token containing MiniMessage-looking text and braces. It
also exercises a rank prefix ending in spaces and a reset, while preserving the
existing durable claims, readiness and recovery checks.

On staging, inspect both languages with a real client, edit a translation and
restart, and confirm LuckPerms prefixes and Nexo item names/models with the real
plugins. Automated Paper players are controlled doubles, not connected clients.
