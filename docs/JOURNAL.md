# Journal

`/journal` and `/collections` open the player's collection categories without a
permission. `/journal farming` opens a category directly. The seven categories
are `farming`, `foraging`, `fighting`, `mining`, `fishing`, `rockets` and
`achievements`. Categories remain visible when empty. Collections use pages of
45 entries with previous/next buttons and a button returning to categories.
The inventory is a display: clicking, dragging, shifting, swapping and creative
inventory actions cannot take or insert its items.

`/journaladmin <known-name|uuid> <add|remove> <entryId>` requires
`planetry.admin.journal`. Names must already be known to this backend; use a
UUID for an offline player unknown here. UUIDs must be canonical full UUIDs.
Adding an already discovered entry and removing an absent entry do nothing.
An added entry announces only after its database/file write succeeds. Offline
awards persist and appear on the next login; they do not send a delayed unlock
announcement.

`/journal open <online-player|uuid> <category>` opens that player's own journal
for them. It runs from console, or from a player with
`planetry.admin.journal`. It does not let staff inspect another player's journal
in their own inventory. The public category link opens only the clicking
player's own validated category. Core executes its fixed `journal open` command
as console for this link; player input cannot select arbitrary console commands.

## Definitions

Edit `plugins/PlanetryCore/journal.yml`, then restart the backend. Existing
discovery records are preserved when definitions change. IDs are global across
all categories and must contain 1 to 64 lowercase letters, digits, underscores
or hyphens, beginning with a letter or digit. IDs are permanent identifiers:
renaming an entry creates a different discovery. Removing a definition hides
it without deleting its saved discovery.

```yaml
categories:
  mining:
    name: Mining
    material: IRON_PICKAXE
    entries:
      moon_crystal:
        mmoitems-type: MATERIAL
        mmoitems-id: MOON_CRYSTAL
        harvest-nodes: [moon_crystal_node]
        mythic-mobs: [MoonGuardian]
```

Keep the other six category sections from the generated file. `name` is a text
label; `material` is a Bukkit item material for the category icon. Entries show
their MMOItems icon, display name and lore after discovery; locked entries use
an unknown icon. Missing or disabled MMOItems definitions show a clear barrier
icon for discovered entries and do not erase discoveries or prevent commands.
Install compatible MMOItems/MythicLib definitions on every participating backend.

`harvest-nodes` links to harvest definition IDs from `harvest.yml`. It unlocks
only when harvesting an assigned, successfully harvested node, not every
vanilla block of that material. `mythic-mobs` links exact MythicMobs internal
IDs and unlocks for the reported player killer. These lists are case sensitive.
Multiple entries may share a trigger. Omit either list or use `[]` to disable
that trigger. An entry with neither list is command only, suitable for fishing,
rockets and achievements granted by quest or other server plugins. Console
can award it using `journaladmin <player> add <entryId>`.

## Unlock messages and placeholders

All journal messages are in `journal.yml` under `messages`. They support
MiniMessage and legacy color codes independently of Core's selected language.
`messages.unlock` is a list of up to 32 lines. The default has blank lines around
the announcement. Supported values are `{id}`, `{player}`, `{category}`,
`{unlocked}`, `{max}`, `{item}` and `{open}`. The item component uses the actual
MMOItems display name and a full item hover with its lore. `{open}` uses the
configurable `messages.open` text and `messages.open-hover` hover text. Its click
action opens that item's category. Names and other runtime values are inserted
as components and never interpreted as MiniMessage commands or formatting.

PlaceholderAPI, when installed, provides:

| Placeholder | Output |
| --- | --- |
| `%planetry_journal_moon_crystal_found%` | `true` or `false` |
| `%planetry_journal_mining_unlocked%` | Number of currently defined mining entries discovered |
| `%planetry_journal_mining_max%` | Number of currently defined mining entries |

Entry IDs containing underscores work without ambiguity. Unknown IDs/categories
are unresolved. Known placeholders return an empty value while a player is
offline, loading, or storage has not confirmed their current cache. Reads never
block PlaceholderAPI on a database operation. Online caches refresh every five
seconds, so changes made on another backend appear after that refresh.

## Persistence and operations

Journal data uses Core's configured `storage.type` and the same database/table
prefix as progression and mail. File mode is single backend only. MariaDB and
PostgreSQL share discoveries across backends with the same database and prefix,
matching definitions, and authenticated forwarded UUIDs. Both database engines
enforce one discovery per UUID/entry, so simultaneous unlocks announce only for
the writer that created the discovery. Storage work uses a bounded serial worker;
an unavailable/full worker reports failure without claiming a successful award.
Accepted writes finish on shutdown before storage closes. Login sessions fence
callbacks so an old session cannot display messages or inventories to a later
connection with the same UUID. A disconnect can still complete an accepted
durable award, which is visible on the next login.

Game clients, actual MMOItems/MythicMobs definitions and cross-backend transfers
should be checked on staging before installation on the live network.
