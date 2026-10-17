# PlanetryCore

Game levels for [Planetry](https://github.com/FlintSH/planetry), with persistent
progress, level rewards, placeholders and a Minecraft XP-bar display.

Targets **Paper/Purpur 26.1.2 and Java 25**. Runs on backends, not Velocity.
Folia and Spigot are not supported. Releases are tested development prereleases.

## Install

1. Download `planetry-core-*.jar` from [Releases](https://github.com/FlintSH/planetry-core/releases).
   `SHA256SUMS` verifies the download. The MariaDB source JAR is for license
   compliance; do not put that source JAR in the server's plugins folder.
2. On a staging backend, put the plugin JAR in `plugins/` and start the server
   once to create `plugins/PlanetryCore/config.yml`.
3. For money rewards, install Vault and a compatible economy provider on that
   backend. The default reward is **150 Credits per new level**. The economy
   provider controls the actual currency and its network synchronization;
   `currency-label` only changes the message. To use command-only rewards set
   `rewards.money: 0` and configure your economy's console command.
4. Install PlaceholderAPI for placeholders. The `planetry` expansion is built
   in; no expansion download is needed. Restart after configuration changes.
5. For multiple backends, configure shared database storage **before players
   start earning XP**. See below. Give each backend a unique `server-id`.

Installation on production remains the network operator's decision. Back up data
before changing storage or upgrading. Hot reload/plugin managers are unsupported.

## Commands and behavior

| Command | Permission | Behavior |
| --- | --- | --- |
| `/level`, `/levels` | None | Show your level and current game XP, plus a configurable hint |
| `/gamexp give <player> <amount>` | `planetry.adam.gamexp` | Add game XP, carrying the remainder across levels |
| `/gamexp take <player> <amount>` | `planetry.adam.gamexp` | Remove total game XP, reducing levels if needed, with a floor of zero |
| `/gamexp view <player>` | `planetry.adam.gamexp` | Show current saved level/progress, including offline players |
| `/gamexp setlevel <player> <level>` | `planetry.adam.gamexp` | Set the level and reset progress to zero, without rewards |
| `/gamexp rewardstatus <player>` | `planetry.adam.gamexp` | List claimed/uncertain rewards requiring review |
| `/gamexp ackreward <player> <claim-uuid>` | Console only | Acknowledge a reviewed reward without repeating its effects |

Staff permission defaults to operators. Targets accept an online or cached known
player name, or a UUID for an offline player. Unknown names do not trigger remote
name lookups. `/gamexp view` has no amount argument. All numeric values are
nonnegative whole numbers; a single `give` accepts at most 10,000 XP (100 level
rewards), and the maximum level is 1,000,000. Excess XP at the cap is rejected.

Players start at **level 0, 0/100 game XP**. Giving 250 XP produces level 2 with
50/100 XP and earns the rewards for levels 1 and 2. Taking 75 XP then produces
level 1 with 75/100 XP. Only newly reached highest levels earn rewards. Taking XP
or lowering/restoring a level cannot pay an old reward twice. `setlevel` is an
administrative adjustment: it also advances the reward high-water mark when
raising a level, with no retroactive rewards. Existing queued rewards remain.

Quest and skill integrations award XP using console commands, for example
`gamexp give PlayerName 25`. This plugin does not invent quest, journal or skill
completion events; configure those plugins to run this command when appropriate.

## Placeholders and the XP bar

| Placeholder | Value |
| --- | --- |
| `%planetry_level%` | Current game level |
| `%planetry_gamexp%` | Current progress from 0 to 99, not lifetime XP |

TAB and quest plugins must support PlaceholderAPI on the backend. Proxy displays
can use Network Chat's existing optional PAPIProxyBridge setup. Placeholder reads
never block the server thread: data is loaded asynchronously, refreshed every
five seconds by default, and updated immediately after local commands. Missing,
loading or failed data returns an empty string. Quest conditions should require
a valid numeric value before granting access. There is no synchronous placeholder
API for strict cross-server transactional quest gating.

Game XP replaces normal progression by default. The XP bar shows only game level
and progress. XP pickups and vanilla advancement XP rewards are suppressed, death drops no XP and preserves the
underlying vanilla level, and mending is disabled. Survival enchanting and anvil
actions that cost XP are blocked with a configurable message, so they cannot spend
game levels or become free. Creative actions that already cost no XP are allowed.
Quests and staff `gamexp` commands control game level changes.

The display uses packets. A targeted correction removes advancement XP that
bypasses the pickup event, only within the same ready player session. It does
not replace Inventory Sync's saved player state. Raw administrative XP commands
and other plugins calling XP setters directly are outside the gameplay event
lock; these still do not change game XP or the game-level bar.
To opt back into ordinary vanilla XP gameplay alongside the game-level display,
set `vanilla-experience.lock: false`. Another plugin or administrative XP command
can briefly replace the display until the next refresh. Disable
`display.xp-bar` if another plugin owns this display. While
Inventory Sync is loading, the overlay waits for `isReady(UUID)`; an installed
disabled/incompatible Inventory Sync also blocks the overlay. Disabling Core
restores the current underlying vanilla XP display. The vanilla lock and display
settings are independent.

## Storage

Default `storage.type: file` stores each UUID in an atomic, forced-to-disk file.
Use reliable local storage supporting atomic replacement and directory syncing.
One process holds an exclusive directory lock. Do not copy or share this directory
between running servers; file mode is for **one server**.

For network progress choose `mariadb` (10.11+) or `postgresql` (14+) and configure
all backends with the same database, `table-prefix` and reward settings. Use port 3306 for MariaDB
or 5432 for PostgreSQL. Both drivers ship in the plugin. The database user needs
CREATE/SELECT/INSERT/UPDATE/DELETE for the configured tables, including CREATE
REFERENCES where the database requires it. Use a dedicated database/schema.
All players must use the same authenticated forwarded UUIDs on every backend.

```yaml
server-id: survival-1
storage:
  type: mariadb
  host: db.example.net
  port: 3306
  database: planetry
  username: planetry
  password: ''
  password-env: PLANETRY_DB_PASSWORD
  table-prefix: planetry_core_
  ssl-mode: require
```

`password-env` uses an environment variable on the server, falling back to
`password` if set. Never post credentials in GitHub. `ssl-mode: require` validates
both the database certificate and hostname against the JVM trust store. Configure
trusted certificates before connecting. `disable` is only for a trusted local
connection/test setup. Database operations use bounded connect/socket timeouts
and a bounded background queue. Shared updates and reward claims lock the UUID's
row in a transaction so concurrent backends cannot overwrite XP or claim the same
reward twice. Progress is committed before a success response or reward payout.

Changing `storage.type`, database or table prefix does **not** migrate data.
Stop participating servers, retain backups and plan a migration before changing
these settings. Core data is independent of Inventory Sync data.

## Rewards and recovery

`rewards.money` controls the per-level Vault payment. `rewards.commands` accepts
console commands without a leading slash, with `{player}`, `{uuid}`, `{level}`
and `{money}` substitutions. Commands execute once per newly earned level in
ascending order. Messages and the currency label are configurable with `&` colors.

```yaml
rewards:
  money: 150.0
  currency-label: Credits
  commands:
    - 'lp user {player} permission set planetry.level.{level} true'
```

Rewards wait until the player is online, alive, ready in Inventory Sync if installed, and the money provider and configured
command names are available. Offline XP changes are saved immediately; rewards
wait for a later join. Configuration is read when delivery occurs, so queued
rewards use the current reward settings after a restart.

Each reward is durably marked claimed **before** calling Vault or console commands.
Money and commands are external operations and cannot be in the same transaction
as Core's data. If the server crashes, a provider fails, a command returns failure,
or a player leaves during delivery, effects can be partial. Core retains the claim
for review and does not automatically repeat it. Other later rewards may continue.
This is duplicate-resistant delivery, not exactly-once payment across crashes.

Use `/gamexp rewardstatus <player>` to inspect claims. The operator should check
economy/command records, compensate only any missing effects using those plugins,
then run `gamexp ackreward <player> <claim-uuid>` **from console**. Acknowledgement
only removes the claim and never pays or runs commands again. Core cannot detect
whether an external command silently failed after returning success. Backups or
manual database edits can invalidate reward history; do not roll it back casually.

## Development and validation

See [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) for reproducible tests and staging
coverage. Every main commit runs unit/file and both database suites, then probes
the same packaged JAR on real Paper using all three storage modes. Passing CI
publishes that exact JAR, checksums and the MariaDB driver source. No automatic
production installation occurs. See [THIRD_PARTY.md](THIRD_PARTY.md).

Open requests in [Issues](https://github.com/FlintSH/planetry-core/issues).
