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
   once to create `plugins/PlanetryCore/config.yml` and `lang/en.yml` / `lang/de.yml`.
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

Core suppresses the vanilla backend "joined the server" announcement on every
join, including transfers between backends. Network join announcements belong
to Velocity. This is automatic after upgrading Core and restarting each backend;
no configuration change is needed.

| Command | Permission | Behavior |
| --- | --- | --- |
| `/planetrycore reload` | `planetry.admin.reload` | Reload language messages and the language selection; other settings require restart |
| `/level`, `/levels` | None | Show your level and current game XP, plus a configurable hint |
| `/gamexp give <player> <amount>` | `planetry.admin.gamexp` | Add game XP, carrying the remainder across levels |
| `/gamexp take <player> <amount>` | `planetry.admin.gamexp` | Remove total game XP, reducing levels if needed, with a floor of zero |
| `/gamexp view <player>` | `planetry.admin.gamexp` | Show current saved level/progress, including offline players |
| `/gamexp setlevel <player> <level>` | `planetry.admin.gamexp` | Set the level and reset progress to zero, without rewards |
| `/gamexp rewardstatus <player>` | `planetry.admin.gamexp` | List claimed/uncertain rewards requiring review |
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
`gamexp give PlayerName 25`. Quest plugins can run this command when appropriate. Harvest nodes and Journal
discoveries use the separate configuration described below.

## Locked hotbar items

Quest Book occupies **slot 8** and NovaLink/menu **slot 9** for every player.
They cannot be moved, dropped, swapped into the offhand, consumed or placed.
Right click in the main hand runs the configured command; left click does not.
A short shared cooldown prevents duplicate activations from interaction events.
These are menu shortcuts, not a quest or menu implementation: configure the
commands supplied by your quest/menu plugins before using them.

```yaml
hotbar:
  enabled: true
  restore-interval-ticks: 20
  activation-cooldown-ticks: 6
  quest-book:
    nexo-id: quest_book
    command: 'yourquestcommand'
    executor: player
  novalink:
    nexo-id: novalink
    command: 'yourmenucommand'
    executor: player
```

The shortcuts use **only Nexo items**, defaulting to `quest_book` and `novalink`.
Install Nexo and define those IDs on each backend. Missing Nexo, an unknown ID
or an unavailable item builder leaves that shortcut absent and logs a warning;
lookup retries after Nexo finishes loading. No ordinary book or compass is
created, and unavailable shortcuts do not displace ordinary player items. Old
Core-managed fallback items are removed when inventory readiness permits.
Nexo's display name, lore, model and other metadata are preserved; Core adds
only its private marker and sets the amount to one. This uses Nexo's
[documented item builder API](https://github.com/Nexo-MC/Nexo-Documentation/blob/master/community-guides/api.md).
The actual Nexo version and resource pack require staging validation.

Commands default to empty until configured, omit the leading slash, and accept
`{player}` and `{uuid}`. `executor: player` uses the player's permissions;
`executor: console` runs with console authority, so configure trusted commands.
Names come from Nexo. The slots are fixed; `enabled: false` stops the feature.
Restart after configuration changes. Existing configurations can add this section;
missing or blank Nexo IDs use `quest_book` / `novalink`. Old `material` and
`display-name` options are ignored. Explicit nonblank IDs remain configurable.
Install matching configuration and Nexo item
definitions on every participating Paper/Purpur backend, not Velocity.

Items return after joining, changing worlds, respawning or another plugin removing
them, normally within one second. A server change is a join on the destination.
Restoration and activation wait for NetworkInventorySync's current-session
readiness if installed; disabled or incompatible Sync blocks both. No inventory
state is written while it is loading. Ordinary items already in these slots
move to empty nonreserved storage slots. If the inventory is full, Core keeps
them intact and retries when there is room. Their ordinary use/removal remains
available. Core removes its own items from death drops and restores them after
respawn; it leaves ordinary possessions and keep-inventory rules alone.

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

## Network mail

Players use **`/mail`** without arguments or a permission to claim saved gifts.
It has no tab suggestions, including for staff. Each mail item
stores a console command and the sender's identity. A colored chat notice includes
a clickable **CLICK TO CLAIM** prompt. Receiving mail does not execute anything;
unclaimed mail survives restarts and players see the notice again on their next
join. Remote sends appear within about five seconds. One `/mail` claims up to 50
items; repeat it for a larger inbox. Repeated claims advance past gifts whose
commands are unavailable locally, and revisit skipped gifts on the next pass.

Staff need **`planetry.admin.sendmail`** (default: operators):

```text
/sendmail player <username|uuid> <command>
/sendmail all <command>
/sendmail player Alex give {player} minecraft:diamond 3
/sendmail all gamexp give {player} 100
```

Successful sends show the number of saved mail items and the exact stored
command. The confirmation appears only after storage confirms the send.
Commands have no leading slash. `{player}` and `{uuid}` resolve to the recipient
at claim time. Mail commands run as console on the **backend where the recipient
claims**, so install the command's plugin on that backend. Commands are limited
to one line and 2048 characters. This permission grants the ability to queue
console commands; give it only to trusted staff. Items, money, game XP and other
gifts depend on the command you choose. For an `all` gift, use a recipient token
rather than a fixed player name.

`player` accepts names known to any participating Core backend, including offline
players, or an explicit UUID. Core remembers names from joins after this upgrade;
the local server's cached names also work. Unknown names do not cause a blocking
remote lookup. Use the UUID for someone who has never joined. `all` snapshots
players currently online across the shared database, including other backends.
It does not send to every historical player or future join. Presence refreshes
every five seconds and expires after 30 seconds if a backend crashes; a recent
disconnect can remain in that snapshot until its cleanup/expiry completes.

Network mail requires Core on **every participating Paper/Purpur backend**, the
same MariaDB/PostgreSQL database and table prefix, unique `server-id` values and
consistent authenticated forwarded UUIDs. No Velocity plugin update is needed.
File storage provides mail on one server only. New mail tables are added without
changing existing game-level data. Restart backends after upgrading or changing
settings. Storage engine changes still require a separate offline migration.

Notices preserve the staff member's rank and name as they were when sending.
`mail.rank-placeholder` defaults to `%luckperms_prefix%` and needs PlaceholderAPI
plus its matching expansion. Core otherwise tries Vault's chat prefix/primary
group, then `mail.fallback-rank` (default `Staff`). Configure these to match your
rank provider. Console and other non-player senders appear as **PLANETRY**.
Trailing spaces in a rank prefix are removed before the single separator and
player name. Messages are configured in the selected language file below.

Claims require an online, living player and Inventory Sync readiness when that
plugin is installed. Missing command names leave mail pending so the player can
claim on the appropriate backend. A claim is durably reserved before running its
command. Concurrent claims across backends cannot execute the same mail twice.
Console effects cannot be committed atomically with the mail database: a crash,
disconnect or command failure can leave an uncertain/partial delivery. Core never
automatically repeats that command. A command returning success can still fail
inside its own plugin; operators must inspect its records.

Staff inspect uncertain items with `/sendmail status <username|uuid>`. This shows
up to 100 retained claims, including the mail UUID, sender, command and backend.
After checking command/economy records and compensating only missing effects, use
`sendmail ack <username|uuid> <mail-uuid>` **from console**. Acknowledgement removes
the review record and never repeats the command. Pending mail cannot be
acknowledged this way. Keep backups and do not restore old mail data casually.

## Language and formatting

`language: en` selects `plugins/PlanetryCore/lang/en.yml`; `language: de` selects
German. Both files are created on first start. Copy one to another locale name
and select that name to add a translation. Run `/planetrycore reload` after
changing the selection or text. The permission `planetry.admin.reload` defaults
to operators; console can also reload. Invalid YAML, non-text entries or missing
selected files keep the previous message set
active and report an error. The command reads only the `language` selection
from `config.yml`; storage, rewards, hotbar and other settings require restart. The setting applies to the backend, rather than each player's client.

Language files contain `messages.*` for game levels and command feedback and
`mail.*` for mail notices, buttons, hover text, sender formats and staff feedback.
Use [MiniMessage](https://docs.advntr.dev/minimessage/format.html), for example
`<light_purple>Level <white>{level}`. Existing `{player}`, `{level}`, `{gamexp}`,
`{count}` and other documented tokens remain supported. Values such as a saved
command are inserted literally, so their angle brackets and braces cannot become
formatting or introduce another substitution. The sender/rank token preserves
its formatted component. Legacy `&` / `§` colors remain accepted for compatibility.

With PlaceholderAPI enabled, language templates can contain PAPI placeholders,
for example `<gray>Hello %player_name%, your level is %planetry_level%`.
Install the matching expansion for placeholders supplied by other plugins.
They resolve for the **player receiving the message**, including mail notices,
buttons and hover text. Sender identity templates resolve for the staff member
when the mail is sent. Console messages leave player placeholders unchanged.
Missing PlaceholderAPI or unresolved placeholders leave their original text.
PAPI results support legacy colors; their MiniMessage and glyph tags stay
literal and cannot add click commands. For Nexo glyphs with custom fonts, use
direct `<glyph:id>` tags in the language template. Only placeholders written in the language template
are expanded, once. Stored commands, names and other dynamic values remain literal.

With Nexo enabled, use its native `<glyph:my_glyph>` and `<shift:2>` tags in
language templates. Core uses Nexo's own MiniMessage resolvers, including
player-aware glyph handling when that API is available. Nexo defines the glyph
IDs and formatting options. Missing or incompatible Nexo leaves these tags
visible. See [Nexo glyphs](https://docs.nexomc.com/configuration/glyphs).
The actual Nexo version, glyph permissions and resource pack must be checked
with real clients on staging. Installing or updating optional plugins requires
a backend restart; the language reload command does not reload plugins.

On the first upgrade, customized `messages.*` and `mail.messages.*` values
are imported into the selected language file as MiniMessage. Unchanged old
English defaults use the selected language's defaults instead. Existing
translations are preserved; the old config remains unchanged. A private marker
inside `lang/` prevents importing the same legacy entries on every restart.
After upgrading, edit the language files for text changes. Missing translation
keys fall back to the bundled locale, then English. The sent-command line is
separate from the send-success message so existing customized success text still
shows the saved command.

## Harvest nodes and Journal

Configure location-bound regenerating blocks in `harvest.yml`, then use
`/harvest wand <profile>` with `planetry.admin.harvest` to bind individual blocks.
Profiles select MMOItems drops, AuraSkills XP, cooldown blocks and crop growth.
Only bound locations change; ordinary blocks retain their normal behavior.
Harvesting starts disabled until configured. See [harvest setup](docs/HARVEST.md).

`/journal` (alias `/collections`) opens the seven collection categories without a
permission. Define MMOItems entries and harvest/MythicMob triggers in `journal.yml`.
`/journaladmin <player> <add|remove> <id>` requires `planetry.admin.journal`;
console can also use `journal open <player> <category>`. Discoveries share Core's
configured storage. See [Journal setup and placeholders](docs/JOURNAL.md).

The game XP permission is now **`planetry.admin.gamexp`**. Update permission grants
that still use the retired `planetry.adam.gamexp` spelling. Restart after editing
harvest or Journal definitions; `/planetrycore reload` reloads language files only.

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
ascending order. Messages use MiniMessage in the selected language file.
The currency label remains configurable under `rewards.currency-label` and
accepts MiniMessage or legacy colors.

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
