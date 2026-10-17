# PlanetryCore development

PlanetryCore belongs to [Planetry](https://github.com/FlintSH/planetry). Before
every issue turn, read the live central registry, all registered plugin contexts,
and current `docs/SYSTEM.md` and `docs/ISSUE_AGENT.md` there. Do not rely on a
frozen system map. Use Codex `gpt-6.1-sol`, ultra reasoning and priority/Fast
service for all work, including delegated agents and newly registered projects.

Use an isolated task checkout. Implement relevant human issue requests, test,
commit and push normally to `main`, and verify the exact commit's CI and released
bytes. Do not open a PR unless the owner asks. Do not create synthetic GitHub
issues. Keep issue communications in GitHub through the central durable reply
helper. Publishing does not authorize installation on the live Minecraft network
or access to production data.

Build with Java 25: `mvn -B -ntp clean verify dependency:build-classpath
-Dmdep.outputFile=target/test-classpath.txt`. Run the documented real PostgreSQL
and MariaDB tests and `scripts/smoke.py` for all three storage modes before
delivery. CI builds once, tests that JAR in Paper, and publishes those same bytes
with `SHA256SUMS` and the bundled MariaDB driver's source JAR.

Game XP owns the displayed level/progress. Vanilla XP gameplay is locked by
default: no gains, death loss, mending or survival XP-cost enchanting/anvil actions.
Use display packets and preserve Inventory Sync's authority. Advancement XP
needs a targeted same-session correction because its reward bypasses the pickup
event; never restore vanilla XP across reconnects or readiness transitions.
Keep durability-before-success, transactional shared
writes, reward high-water marks, and durable claims before external effects.
Never automatically retry an uncertain money payment or console command.

Keep `planetry-plugin.json` and its central plugin context current when changing
platform versions, compatibility, integrations or validation commands.
