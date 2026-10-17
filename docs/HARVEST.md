# Regenerating harvest nodes

Harvesting is opt in on each Paper/Purpur backend. Edit `harvest.yml`, replace
the example MMOItems type/id pairs, set `enabled: true`, and restart. MMOItems
must be enabled and each configured item must resolve. A positive `skill-xp`
also requires an enabled AuraSkills 2.x, a valid skill and a loaded player.
Use `skill-xp: 0` when no AuraSkills XP is needed. `skill` accepts `mining`,
`auraskills/mining` or `auraskills:mining`.

Staff with `planetry.admin.harvest` can use:

- `/harvest wand <profile>`: receive a marked binding wand. Right-click a
  matching placed block to bind only that world UUID and block location.
- Sneak and right-click a bound node with the wand, or look at it and run
  `/harvest remove`, to unbind. An unchanged cooldown block is restored first.
  An externally replaced block is preserved.
- `/harvest inspect`: show the bound profile of the block within six blocks.

Ordinary blocks of the same material keep their usual behavior. Survival and
Adventure players harvest bound nodes by breaking them. Creative breaks are
blocked; remove the binding first. Protection plugins can cancel the normal
block-break event before Core's HIGHEST handler. Core cancels accepted node
breaks before MONITOR reward hooks, suppressing vanilla drops and XP.
Validate the actual protection/plugin set on staging; plugins that mutate blocks
directly, award native skill XP despite cancellation, or perform protection
checks after Core's HIGHEST handler need matching integration configuration.

Each profile defines its `source` material, `cooldown-block`, integer
`cooldown-seconds` (1 through 604800), `growth-stages`, MMOItems `drops` list,
`skill` and `skill-xp`. Source and cooldown materials must be simple blocks,
without block entities or multi-block parts. Cooldown blocks can use AIR,
BEDROCK or another safe block material. Drops preserve MMOItems metadata and
allow 1 through 2304 items per entry, with up to 32 entries per profile.
Existing bound blocks retain their original complete block data, including log
orientation. Changing a profile's source does not convert existing nodes.

Set `growth-stages: true` for an Ageable crop such as WHEAT. Bind it fully grown.
Harvest resets it to age zero and advances the visible stages over the configured
cooldown. Only maturity enables another harvest. Natural growth, bonemeal and
right-click vanilla harvesting cannot shorten the cooldown. The crop's support
block is protected against breaking, trampling and other normal block changes.
Bound nodes also resist piston movement, explosions, fluids, burning, decay,
physics and placement. AIR placeholders remain reserved locations.

Drops enter the harvesting player's inventory immediately. Overflow is a glowing
item entity, hidden from other players and collectible only by that player.
Persistent ownership survives entity saves, chunk loading and restart; later
joins and entity tracking retain the same visibility and pickup rules. Hoppers,
mobs and merging cannot take or transfer owned drops. Normal item despawn and
damage still apply. Inventory Sync readiness is checked before inventory
insertion, spawning overflow and owner pickup. No reward is granted while the
installed Inventory Sync integration is unavailable or not ready.

`harvest-nodes.dat` and its lock file belong to this backend's world data. Keep
them with the matching world backup and never share them between backends.
They are independent of Core's player-progress SQL/file mode. Locations use world
UUIDs, so changing world names does not redirect bindings. Unloaded chunks are
not loaded just to advance cooldowns; the next loaded poll catches up.

Bindings, deadlines and every intended block transition are atomically replaced
and forced to local disk before the world is changed. World-change confirmations
are saved before rewards. Pending intents resume after restart only when the
current block exactly matches the saved previous or intended block data. A
different block suspends the node and is never overwritten; inspect the server
log, remove the stale binding and bind the intended block again. Shutdown keeps
saved deadlines and cooldown placeholders. Setting `enabled: false` stops new
harvests while completing existing cooldowns and maintaining node/drop guards.

Item insertion, item entities, AuraSkills and Journal updates cannot participate
in a transaction with world saves or Inventory Sync. A crash or integration
failure can lose some or all rewards. Claimed harvest effects are never retried
automatically, even when their outcome is uncertain; logs identify the player
and node for operator review. A stale world save can suspend a node rather than
guess and overwrite it. This provides safe recovery, not cross-plugin exactly
once delivery. Do not restore a player inventory, node file or world backup in
isolation to repeat rewards.

Durability writes run on the server thread to fence world and inventory changes.
Use fast local storage. `max-nodes` defaults to 2000 and is capped at 10000;
`poll-ticks` defaults to 10. Regeneration changes are persisted in batches, and
slow durability writes produce a rate-limited warning. Lower the node limit or
increase the polling interval if staging measurements show server tick delays.
Malformed or corrupt state fails plugin startup closed. A runtime persistence
error disables harvest effects and retains protection until restart and review.
