package dev.flint.planetry.core.harvest;

import dev.flint.planetry.core.PlanetryCore;
import dev.flint.planetry.core.harvest.NodeStore.Key;
import dev.flint.planetry.core.harvest.NodeStore.Node;
import dev.flint.planetry.core.integration.AuraSkillsHook;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Location-bound harvesting. All Bukkit calls and all state transitions run on the server thread. */
public final class HarvestService implements Listener, CommandExecutor, TabCompleter, AutoCloseable {
    public static final String PERMISSION = "planetry.admin.harvest";
    private final PlanetryCore plugin;
    private final Predicate<Player> readiness;
    private final BiFunction<String, String, ItemStack> mmoItems;
    private final BiConsumer<Player, String> journalHarvest;
    private final LongSupplier clock;
    private final NamespacedKey wandKey;
    private final OwnedDrops ownedDrops;
    private HarvestSettings settings;
    private NodeStore store;
    private BukkitTask timer;
    private boolean started, closed, failed;
    private long nextSlowWarning;

    public HarvestService(PlanetryCore plugin, Predicate<Player> ready,
                          BiFunction<String, String, ItemStack> mmoItems, BiConsumer<Player, String> journalHarvest) {
        this(plugin, ready, mmoItems, journalHarvest, System::currentTimeMillis);
    }
    HarvestService(PlanetryCore plugin, Predicate<Player> ready, BiFunction<String, String, ItemStack> mmoItems,
                   BiConsumer<Player, String> journalHarvest, LongSupplier clock) {
        this.plugin = Objects.requireNonNull(plugin); this.readiness = Objects.requireNonNull(ready);
        this.mmoItems = Objects.requireNonNull(mmoItems); this.journalHarvest = Objects.requireNonNull(journalHarvest);
        this.clock = Objects.requireNonNull(clock);
        wandKey = new NamespacedKey(plugin, "harvest-wand"); ownedDrops = new OwnedDrops(plugin, ready);
    }
    public void start() throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        mainThread(); if (closed) throw new IllegalStateException("Harvest service closed"); if (started) return;
        File config = new File(plugin.getDataFolder(), "harvest.yml");
        if (!config.exists()) plugin.saveResource("harvest.yml", false);
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(config);
        start(HarvestSettings.read(yaml), new NodeStore(plugin.getDataFolder().toPath()));
    }
    void start(HarvestSettings settings, NodeStore store) {
        mainThread(); if (closed || started) throw new IllegalStateException("Harvest service already started or closed");
        this.settings = settings; this.store = store;
        try {
            for (var profile : settings.profiles().values()) {
                BlockData source = plugin.getServer().createBlockData(profile.source());
                BlockData cooldown = plugin.getServer().createBlockData(profile.cooldown());
                if (profile.growth() && !(source instanceof Ageable)) throw new IllegalArgumentException("Harvest growth-stages requires an Ageable source: " + profile.id());
                if (source instanceof Bisected || cooldown instanceof Bisected || source instanceof org.bukkit.block.data.type.Bed
                        || cooldown instanceof org.bukkit.block.data.type.Bed || source.createBlockState() instanceof TileState
                        || cooldown.createBlockState() instanceof TileState) throw new IllegalArgumentException("Harvest nodes require simple blocks without block entities or multi-block parts");
            }
            for (Node node : store.nodes().values()) {
                plugin.getServer().createBlockData(node.source());
                plugin.getServer().createBlockData(node.expected());
                if (!node.previous().isEmpty()) plugin.getServer().createBlockData(node.previous());
            }
            started = true;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getServer().getPluginManager().registerEvents(ownedDrops, plugin);
            var command = Objects.requireNonNull(plugin.getCommand("harvest")); command.setExecutor(this); command.setTabCompleter(this);
            ownedDrops.start();
            timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1, settings.pollTicks());
        } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    public boolean enabled() { return started && !closed && !failed && settings.enabled(); }
    public int nodeCount() { return store == null ? 0 : store.nodes().size(); }
    public Map<String, HarvestSettings.Profile> profiles() { return settings == null ? Map.of() : settings.profiles(); }

    // Run after ordinary protection checks and before MONITOR reward hooks such as AuraSkills.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void breakBlock(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Node node = node(event.getBlock());
        if (node == null) { if (support(event.getBlock())) event.setCancelled(true); return; }
        event.setCancelled(true); event.setDropItems(false); event.setExpToDrop(0);
        if (!enabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (!ready(player)) { message(player, "not-ready", Map.of()); return; }
        if (node.cooling() || node.suspended() || !node.previous().isEmpty()) return;
        Block block = event.getBlock();
        if (!same(block, node.expected())) { suspend(node); return; }
        var profile = settings.profiles().get(node.profile());
        if (profile == null || block.getType() != profile.source()) return;
        BlockData original = block.getBlockData();
        if (original instanceof Ageable age && age.getAge() != age.getMaximumAge()) { message(player, "immature", Map.of()); return; }
        List<ItemStack> drops = templates(profile);
        if (drops == null || profile.skillXp() > 0 && !AuraSkillsHook.available(player, profile.skill())) {
            message(player, "integration-unavailable", Map.of()); return;
        }
        long now = clock.getAsLong();
        BlockData next = profile.growth() ? original.clone() : plugin.getServer().createBlockData(profile.cooldown());
        if (next instanceof Ageable age && profile.growth()) age.setAge(0);
        Node intent = node.intent(next.getAsString(), now, now + profile.cooldownMillis());
        if (!put(intent)) return; // Forced intent is the reward's non-retrying claim.
        try {
            block.setBlockData(next, false);
            if (!put(intent.confirmed())) return;
            // No external effect is retried after this claim, including partial insertion.
            for (ItemStack drop : drops) {
                if (!ready(player)) throw new IllegalStateException("Inventory authority changed after harvest claim");
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(drop);
                for (ItemStack leftover : leftovers.values()) ownedDrops.drop(player, block.getLocation().add(.5, .5, .5), leftover);
            }
            if (profile.skillXp() > 0 && (!ready(player) || !AuraSkillsHook.award(player, profile.skill(), profile.skillXp())))
                throw new IllegalStateException("AuraSkills XP outcome is uncertain");
            journalHarvest.accept(player, profile.id());
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "Harvest effects require review for " + player.getUniqueId() + " at " + node.key()
                    + ". Effects may be partial and will never be retried automatically.", error);
        }
    }
    private List<ItemStack> templates(HarvestSettings.Profile profile) {
        List<ItemStack> result = new ArrayList<>();
        try {
            for (var drop : profile.drops()) {
                ItemStack source = mmoItems.apply(drop.type(), drop.id());
                if (source == null || source.getType().isAir() || source.getMaxStackSize() < 1) return null;
                int remaining = drop.amount();
                while (remaining > 0) { ItemStack stack = source.clone(); int amount = Math.min(remaining, source.getMaxStackSize()); stack.setAmount(amount); result.add(stack); remaining -= amount; }
            }
            return result;
        } catch (RuntimeException | LinkageError error) { return null; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer(); ItemStack item = event.getItem();
        String profile = event.getHand() == EquipmentSlot.HAND && item != null && item.hasItemMeta()
                ? item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING) : null;
        if (profile == null) {
            // Stop bonemeal, right-click berry/cocoa harvesting, stripping and transformations.
            if (node(event.getClickedBlock()) != null || support(event.getClickedBlock())) { event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY); }
            return;
        }
        if (!player.hasPermission(PERMISSION)) return;
        event.setCancelled(true);
        if (!enabled()) { message(player, "disabled", Map.of()); return; }
        if (!ready(player)) { message(player, "not-ready", Map.of()); return; }
        if (player.isSneaking()) remove(player, event.getClickedBlock()); else bind(player, event.getClickedBlock(), profile);
    }
    private void bind(Player player, Block block, String id) {
        var profile = settings.profiles().get(id);
        if (profile == null) { message(player, "unknown-profile", Map.of("profile", id)); return; }
        if (block.getType() != profile.source() || block.getState() instanceof TileState || block.getBlockData() instanceof Bisected
                || profile.growth() && !(block.getBlockData() instanceof Ageable)) { message(player, "mismatch", Map.of("profile", id)); return; }
        if (block.getBlockData() instanceof Ageable age && age.getAge() != age.getMaximumAge()) { message(player, "immature", Map.of()); return; }
        if (node(block) != null) { message(player, "mismatch", Map.of("profile", id)); return; }
        if (nodeCount() >= settings.maxNodes()) { message(player, "limit", Map.of("count", Integer.toString(settings.maxNodes()))); return; }
        String original = block.getBlockData().getAsString();
        if (put(new Node(key(block), id, original, original, "", 0, 0, false))) message(player, "bound", Map.of("profile", id));
    }
    private void remove(Player player, Block block) {
        Node node = node(block); if (node == null) { message(player, "no-node", Map.of()); return; }
        if (failed) { message(player, "unavailable", Map.of()); return; }
        if (!node.suspended() && same(block, node.expected()) && !same(block, node.source())) {
            Node intent = node.intent(node.source(), 0, 0);
            if (!put(intent)) return;
            try { block.setBlockData(plugin.getServer().createBlockData(node.source()), false); }
            catch (RuntimeException | LinkageError error) { fail(error); return; }
        }
        Map<Key, Node> nodes = new HashMap<>(store.nodes()); nodes.remove(node.key());
        if (commit(nodes)) message(player, "removed", Map.of("profile", node.profile()));
    }

    void poll() {
        mainThread(); if (!started || closed || failed) return;
        long now = clock.getAsLong();
        Map<Key, Node> updated = new LinkedHashMap<>(store.nodes());
        Map<Block, Node> changes = new LinkedHashMap<>(); boolean dirty = false;
        for (Node node : store.nodes().values()) {
            if (node.suspended()) continue;
            World world = plugin.getServer().getWorld(node.key().world());
            if (world == null || !world.isChunkLoaded(node.key().x() >> 4, node.key().z() >> 4)) continue;
            Block block = world.getBlockAt(node.key().x(), node.key().y(), node.key().z());
            if (!same(block, node.expected())) {
                if (!node.previous().isEmpty() && same(block, node.previous())) { changes.put(block, node); continue; }
                updated.put(node.key(), node.suspend()); dirty = true; continue;
            }
            if (!node.previous().isEmpty()) { updated.put(node.key(), node.confirmed()); dirty = true; continue; }
            if (!node.cooling()) continue;
            BlockData source = plugin.getServer().createBlockData(node.source());
            BlockData next = null;
            long start = node.started(), deadline = node.deadline();
            if (now >= deadline) { next = source; start = 0; deadline = 0; }
            else if (source instanceof Ageable age) {
                // Persisted source and current crop identity, not a changed profile, own regrowth.
                BlockData current = block.getBlockData();
                if (current instanceof Ageable && current.getMaterial() == source.getMaterial()) {
                    int target = (int)Math.min(age.getMaximumAge() - 1, Math.max(0, (now - start) * (age.getMaximumAge() + 1L) / (deadline - start)));
                    age.setAge(target); if (!same(block, age.getAsString())) next = age;
                }
            }
            if (next != null) { Node intent = node.intent(next.getAsString(), start, deadline); updated.put(node.key(), intent); changes.put(block, intent); dirty = true; }
        }
        if (dirty && !commit(updated)) return;
        if (changes.isEmpty()) return;
        try {
            Map<Key, Node> confirmed = new HashMap<>(store.nodes());
            for (var entry : changes.entrySet()) {
                entry.getKey().setBlockData(plugin.getServer().createBlockData(entry.getValue().expected()), false);
                confirmed.put(entry.getValue().key(), entry.getValue().confirmed());
            }
            commit(confirmed);
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    private boolean put(Node node) { Map<Key, Node> nodes = new HashMap<>(store.nodes()); nodes.put(node.key(), node); return commit(nodes); }
    private boolean commit(Map<Key, Node> nodes) {
        if (failed) return false;
        long start = System.nanoTime();
        try { store.replace(nodes); return true; }
        catch (IOException | RuntimeException error) { fail(error); return false; }
        finally {
            if (System.nanoTime() - start > 100_000_000L && clock.getAsLong() >= nextSlowWarning) {
                nextSlowWarning = clock.getAsLong() + 60_000;
                plugin.getLogger().warning("Harvest durability writes exceeded 100ms. Use local fast storage and reduce max-nodes if needed.");
            }
        }
    }
    private void suspend(Node node) { if (put(node.suspend())) plugin.getLogger().warning("Harvest node suspended after an external block replacement: " + node.key()); }
    private void fail(Throwable error) { failed = true; plugin.getLogger().log(Level.SEVERE, "Harvest storage/world operation failed. Bound nodes are protected and harvesting is disabled until restart and operator review.", error); }
    private Node node(Block block) { return store == null ? null : store.nodes().get(key(block)); }
    private boolean support(Block block) {
        Node above = node(block.getRelative(BlockFace.UP));
        if (above != null && !above.source().startsWith("minecraft:cocoa[")
                && (above.source().contains("[age=") || above.source().contains(",age="))) return true;
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            Node adjacent = node(block.getRelative(face));
            if (adjacent == null || !adjacent.source().startsWith("minecraft:cocoa[")) continue;
            BlockData original = plugin.getServer().createBlockData(adjacent.source());
            // CocoaBlock.canSurvive checks its supporting jungle log at position.relative(FACING).
            if (original instanceof Directional cocoa) {
                BlockFace attached = cocoa.getFacing(); Key position = adjacent.key();
                if (position.world().equals(block.getWorld().getUID()) && position.x() + attached.getModX() == block.getX()
                        && position.y() + attached.getModY() == block.getY() && position.z() + attached.getModZ() == block.getZ()) return true;
            }
        }
        return false;
    }
    private boolean protectedBlock(Block block) { return node(block) != null || support(block); }
    private static Key key(Block block) { return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    private static boolean same(Block block, String data) { return block.getBlockData().getAsString().equals(data); }
    private boolean ready(Player player) {
        try { return player.isOnline() && !player.isDead() && readiness.test(player); }
        catch (RuntimeException | LinkageError error) { return false; }
    }
    private void mainThread() { if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Harvest requires the server thread"); }
    private void message(CommandSender sender, String key, Map<String, String> values) { sender.sendMessage(plugin.language().text(sender, "harvest." + key, values)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) { message(sender, "no-permission", Map.of()); return true; }
        if (!(sender instanceof Player player)) { message(sender, "players-only", Map.of()); return true; }
        if (!enabled()) { message(sender, "disabled", Map.of()); return true; }
        if (!ready(player)) { message(sender, "not-ready", Map.of()); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("wand")) {
            if (!settings.profiles().containsKey(args[1])) { message(sender, "unknown-profile", Map.of("profile", args[1])); return true; }
            ItemStack wand = ItemStack.of(Material.BLAZE_ROD); var meta = wand.getItemMeta();
            meta.displayName(Component.text("Planetry harvest: " + args[1]));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, args[1]); wand.setItemMeta(meta);
            // A staff tool never spills onto the world when inventory space is unavailable.
            if (player.getInventory().firstEmpty() < 0) { message(sender, "not-ready", Map.of()); return true; }
            player.getInventory().addItem(wand); message(sender, "wand-given", Map.of("profile", args[1])); return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("inspect"))) {
            Block block = player.getTargetBlockExact(6);
            if (block == null || node(block) == null) { message(sender, "no-node", Map.of()); return true; }
            if (args[0].equalsIgnoreCase("remove")) remove(player, block);
            else message(sender, "bound", Map.of("profile", node(block).profile()));
            return true;
        }
        message(sender, "usage", Map.of()); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        List<String> options = args.length == 1 ? List.of("wand", "remove", "inspect")
                : args.length == 2 && args[0].equalsIgnoreCase("wand") ? new ArrayList<>(profiles().keySet()) : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void place(BlockPlaceEvent event) {
        if (protectedBlock(event.getBlock()) || event instanceof BlockMultiPlaceEvent multi && multi.getReplacedBlockStates().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void harvest(PlayerHarvestBlockEvent event) { if (protectedBlock(event.getHarvestedBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explode(EntityExplodeEvent event) { event.blockList().removeIf(this::protectedBlock); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explode(BlockExplodeEvent event) { event.blockList().removeIf(this::protectedBlock); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void extend(BlockPistonExtendEvent event) {
        if (protectedBlock(event.getBlock().getRelative(event.getDirection())) || event.getBlocks().stream().anyMatch(block -> protectedBlock(block) || protectedBlock(block.getRelative(event.getDirection())))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void retract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBlock(block) || protectedBlock(block.getRelative(event.getDirection())) || protectedBlock(block.getRelative(event.getDirection().getOppositeFace())))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void flow(BlockFromToEvent event) { if (protectedBlock(event.getToBlock()) || protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void physics(BlockPhysicsEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void grow(BlockGrowEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void decay(LeavesDecayEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void burn(BlockBurnEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void ignite(BlockIgniteEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void fade(BlockFadeEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void form(BlockFormEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void spread(BlockSpreadEvent event) { if (protectedBlock(event.getBlock()) || protectedBlock(event.getSource())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void change(EntityChangeBlockEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void fertilize(BlockFertilizeEvent event) { if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void structure(StructureGrowEvent event) { if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void portal(PortalCreateEvent event) { if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void sponge(SpongeAbsorbEvent event) { if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true); }

    @Override public void close() {
        mainThread(); if (closed) return; closed = true;
        if (timer != null) timer.cancel(); HandlerList.unregisterAll(this); HandlerList.unregisterAll(ownedDrops);
        // Keep cooldown blocks and their durable deadlines. Shutdown does not make nodes harvestable.
        if (store != null) try { store.close(); } catch (IOException error) { plugin.getLogger().log(Level.SEVERE, "Harvest storage close failed", error); }
    }
}
