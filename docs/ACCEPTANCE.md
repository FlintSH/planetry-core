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
