package dev.flint.planetrycore.probe;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/** Controlled players and Nexo API fixture in real Paper; not real-client/Nexo acceptance. */
public final class HotbarProbe extends JavaPlugin implements CommandExecutor {
    private final List<Activation> activations = new ArrayList<>();
    private JavaPlugin core;
    private Plugin inventorySync;
    private NamespacedKey marker;
    private Fake player;
    private ItemStack quest;
    private ItemStack nova;
    private ItemStack diamonds;
    private ItemStack emeralds;
    private ItemStack lookalike;
    private boolean hooks;
    private boolean finished;
    private boolean slowActivation;
    private String stage = "startup";
    private Throwable asynchronousFailure;

    @Override public void onEnable() {
        getCommand("hotbarprobe").setExecutor(this);
        after(1, () -> {
            Plugin loaded = Bukkit.getPluginManager().getPlugin("PlanetryCore");
            check(loaded instanceof JavaPlugin && loaded.isEnabled(), "PlanetryCore did not enable");
            core = (JavaPlugin) loaded;
            marker = new NamespacedKey(core, "hotbar-item");
            inventorySync = Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
            hooks = inventorySync != null;
            check(!hooks || inventorySync.isEnabled(), "Inventory Sync fixture did not enable");
            check(Bukkit.getPluginManager().isPluginEnabled("Nexo") == hooks, "Unexpected Nexo fixture presence");
            check(core.getConfig().getBoolean("hotbar.enabled"), "Hotbar smoke configuration is disabled");
            check(core.getConfig().getInt("hotbar.restore-interval-ticks") == 2, "Expected two-tick smoke polling");
            check(core.getConfig().getInt("hotbar.activation-cooldown-ticks") == 6, "Expected six-tick smoke cooldown");
            player = new Fake(UUID.randomUUID());
            diamonds = named(Material.DIAMOND, 17, "Player diamonds");
            emeralds = named(Material.EMERALD, 11, "Player emeralds");
            lookalike = named(Material.BOOK, 1, "Quest Book");
            player.put(7, diamonds.clone());
            player.put(8, emeralds.clone());
            player.put(10, lookalike.clone());
            readiness(false);
            join(player);
            if (hooks) {
                stage = "initial readiness gate";
                ItemStack[] before = player.snapshot();
                int writes = player.writes;
                after(10, () -> {
                    unchanged(player, before, writes, "Join/poll changed inventory before readiness");
                    readiness(true);
                    awaitInitial();
                });
            } else absentNexo();
        });
        // An exception inside another plugin's event handler can otherwise strand a probe.
        after(1200, () -> { throw new IllegalStateException("Global probe timeout at " + stage); });
    }

    private void awaitInitial() {
        stage = "initial hotbar and displaced possessions";
        waitFor(this::restored, () -> {
            assertItems();
            quest = player.items[7].clone();
            nova = player.items[8].clone();
            check(ownedCount() == 2, "Initial managed item count differs");
            possessions();
            check(player.items[10].equals(lookalike), "Unmarked lookalike was modified");
            // Also match every real Nexo/name/lore property, removing only our ownership marker.
            lookalike = quest.clone();
            var meta = lookalike.getItemMeta();
            meta.getPersistentDataContainer().remove(marker);
            lookalike.setItemMeta(meta);
            player.put(10, lookalike.clone());
            movement();
            activation();
        });
    }

    private void assertItems() {
        checkItem(player.items[7], "quest-book", Material.ENCHANTED_BOOK, "Nexo Quest Book");
        checkItem(player.items[8], "novalink", Material.COMPASS, "Nexo NovaLink");
        for (int slot : new int[]{7, 8}) {
            String nexoId = slot == 7 ? "quest_book" : "novalink";
            String unique = player.items[slot].getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey("nexo", "unique"), PersistentDataType.STRING);
            check(unique != null, "Nexo's per-build random metadata was lost");
            ItemStack expected = originalNexo(unique);
            check(expected.getAmount() == 3 && !expected.getItemMeta().getPersistentDataContainer().has(marker),
                    "Core mutated the original Nexo builder stack");
            expected.setAmount(1);
            ItemStack actual = player.items[slot].clone();
            var meta = actual.getItemMeta();
            meta.getPersistentDataContainer().remove(marker);
            actual.setItemMeta(meta);
            check(expected.equals(actual), "Core changed Nexo item metadata for " + nexoId + ": " + actual);
        }
    }

    private void checkItem(ItemStack item, String id, Material material, String name) {
        check(!empty(item) && item.getAmount() == 1 && item.getType() == material,
                id + " has wrong material/amount: " + item);
        check(id.equals(item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING)),
                id + " lacks its real plugin PDC identity");
        check(name.equals(plain(item.getItemMeta().displayName())), id + " lost the Nexo display name");
    }

    private void absentNexo() {
        stage = "missing Nexo has no shortcuts or inventory displacement";
        ItemStack[] before = player.snapshot();
        int writes = player.writes;
        after(10, () -> {
            unchanged(player, before, writes, "Missing Nexo changed ordinary possessions");
            check(ownedCount() == 0 && activations.isEmpty(), "Missing Nexo created or activated shortcuts");
            allowed(click(player.raw(7), ClickType.LEFT, InventoryAction.PICKUP_ALL), "ordinary item without Nexo");
            // Preserve emeralds while exercising the empty unavailable slot and legacy cleanup.
            player.put(0, player.items[8]);
            player.put(8, null);
            allowed(click(player.raw(8), ClickType.LEFT, InventoryAction.PLACE_ALL), "empty slot without Nexo");
            allowed(drag(new ItemStack(Material.APPLE), player.raw(8)), "drag into unavailable shortcut slot");
            allowed(number(0, 8), "number-key swap into unavailable shortcut slot");
            allowed(new PlayerPickBlockEvent(player.player, Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(),
                    false, 8, 10), "pick into unavailable shortcut slot");
            ItemStack stale = legacy("novalink");
            player.put(8, stale);
            player.put(4, legacy("quest-book"));
            player.cursor = stale.clone();
            player.held = 8;
            interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, stale);
            check(activations.isEmpty(), "A legacy generic shortcut activated without Nexo");
            after(10, () -> {
                check(ownedCount() == 0 && empty(player.cursor) && empty(player.items[8]), "Legacy shortcuts survived without Nexo");
                possessions();
                check(player.items[7].equals(diamonds), "Missing Nexo displaced an ordinary reserved item");
                succeed();
            });
        });
    }

    private ItemStack legacy(String id) {
        ItemStack item = named(Material.BOOK, 1, "Legacy generic shortcut");
        item.editMeta(meta -> meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, id));
        return item;
    }

    private ItemStack originalNexo(String unique) {
        try {
            Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
            Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems", true, nexo.getClass().getClassLoader());
            return (ItemStack) api.getMethod("builtItem", String.class).invoke(null, unique);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private void nexoState(String name, boolean value) {
        try { Bukkit.getPluginManager().getPlugin("Nexo").getClass().getField(name).setBoolean(
                Bukkit.getPluginManager().getPlugin("Nexo"), value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private void movement() {
        stage = "reserved and ordinary inventory interactions";
        ItemStack[] before = player.snapshot();
        int commands = activations.size();
        for (int slot : new int[]{7, 8}) {
            denied(click(player.raw(slot), ClickType.LEFT, InventoryAction.PICKUP_ALL), "reserved pickup " + slot);
            denied(click(player.raw(slot), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "reserved shift " + slot);
            denied(click(player.raw(slot), ClickType.DROP, InventoryAction.DROP_ONE_SLOT), "reserved inventory drop " + slot);
            player.cursor = new ItemStack(Material.APPLE);
            denied(click(player.raw(slot), ClickType.RIGHT, InventoryAction.PLACE_ONE), "reserved placement " + slot);
            player.cursor = null;
            denied(number(0, slot), "chest number-key swap " + slot);
            denied(number(player.raw(2), slot), "player number-key swap " + slot);
            denied(drag(new ItemStack(Material.APPLE, 2), player.raw(slot)), "reserved drag " + slot);
            denied(creative(player.raw(slot), new ItemStack(Material.APPLE)), "reserved creative replacement " + slot);
        }
        denied(new PlayerPickBlockEvent(player.player, Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), false, 7, 10), "middle-click into reserved target");
        denied(new PlayerPickBlockEvent(player.player, Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), false, 2, 7), "middle-click from managed source");
        allowed(new PlayerPickBlockEvent(player.player, Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), false, 2, 10), "ordinary middle-click pick");
        player.cursor = quest.clone();
        denied(click(0, ClickType.LEFT, InventoryAction.PLACE_ALL), "managed cursor into chest");
        player.cursor = null;
        denied(drag(quest.clone(), 0), "managed cursor drag into chest");
        denied(creative(player.raw(2), quest.clone()), "creative managed item copy");
        player.held = 7;
        denied(new PlayerDropItemEvent(player.player, dropped(quest)), "managed world drop");
        denied(new PlayerSwapHandItemsEvent(player.player, new ItemStack(Material.APPLE), quest.clone()), "main/offhand swap");
        player.put(40, nova.clone());
        denied(click(player.raw(2), ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP), "inventory offhand swap");
        player.put(40, null);
        denied(new EntityPickupItemEvent(player.player, dropped(nova), 0), "managed ground pickup");

        // Unmarked player possessions, including lookalikes, retain ordinary behavior.
        player.held = 2;
        allowed(click(player.raw(10), ClickType.LEFT, InventoryAction.PICKUP_ALL), "lookalike pickup");
        allowed(click(player.raw(10), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "ordinary shift out");
        player.top.setItem(0, new ItemStack(Material.APPLE));
        allowed(click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "ordinary shift in");
        allowed(number(0, 2), "ordinary number-key swap");
        allowed(drag(new ItemStack(Material.APPLE, 2), player.raw(2)), "ordinary drag");
        allowed(creative(player.raw(2), new ItemStack(Material.APPLE)), "ordinary creative action");
        allowed(new PlayerDropItemEvent(player.player, dropped(lookalike)), "ordinary world drop");
        allowed(new PlayerSwapHandItemsEvent(player.player, new ItemStack(Material.APPLE), lookalike.clone()), "ordinary hand swap");
        allowed(new EntityPickupItemEvent(player.player, dropped(lookalike), 0), "ordinary ground pickup");
        PlayerInteractEvent ordinary = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, lookalike);
        check(ordinary.useItemInHand() != Event.Result.DENY, "Ordinary lookalike use was blocked");
        check(Arrays.equals(before, player.snapshot()), "Movement events mutated player possessions");
        check(player.drops == 0 && activations.size() == commands, "Inventory movement dropped items or ran commands");
    }

    private void activation() {
        stage = "activation identity, hands and shared cooldown";
        player.held = 7;
        interact(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, quest);
        interact(Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND, quest);
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, quest);
        check(activations.isEmpty(), "Left/offhand click activated a command");
        PlayerInteractEvent right = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, quest);
        check(right.useItemInHand() == Event.Result.DENY, "Managed air use was not prevented");
        checkActivation(0, "quest", false);
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, quest);
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, quest);
        denied(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND), "managed entity use");
        denied(new PlayerInteractAtEntityEvent(player.player, entity(), new Vector(), EquipmentSlot.HAND), "managed entity-at use");
        player.held = 8;
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, nova);
        check(activations.size() == 1, "Dual hands/events/items bypassed the shared cooldown");
        after(10, () -> {
            PlayerInteractEvent block = interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, nova);
            check(block.useItemInHand() == Event.Result.DENY && block.useInteractedBlock() == Event.Result.DENY,
                    "Managed block interaction did not deny vanilla item/block use");
            checkActivation(1, "menu", true);
            interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, nova);
            check(activations.size() == 2, "Same-tick repeat ran the menu twice");
            after(10, () -> {
                player.held = 7;
                slowActivation = true;
                denied(new PlayerInteractAtEntityEvent(player.player, entity(), new Vector(), EquipmentSlot.HAND), "managed entity-at activation");
                slowActivation = false;
                denied(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND), "duplicate entity activation");
                checkActivation(2, "quest", false);
                check(activations.size() == 3, "Entity and entity-at produced duplicate commands");
                readinessTransition();
            });
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            check(Bukkit.isPrimaryThread(), "Hotbar command ran off main thread");
            check(args.length == 3, "Wrong command argument count: " + Arrays.toString(args));
            check(player.name.equals(args[1]) && player.id.toString().equals(args[2]),
                    "Player/UUID placeholders were not substituted exactly: " + Arrays.toString(args));
            boolean console = sender instanceof ConsoleCommandSender;
            check(console || sender == player.player, "Player command used another sender identity");
            activations.add(new Activation(args[0], console));
            if (slowActivation) Thread.sleep(400); // Longer than the cooldown, still the same server tick/event.
        } catch (Throwable error) { asynchronousFailure = error; }
        return true;
    }

    private void checkActivation(int index, String token, boolean console) {
        healthy();
        check(activations.size() == index + 1, "Expected activation " + (index + 1) + ", got " + activations);
        check(activations.get(index).equals(new Activation(token, console)), "Wrong configured command/executor: " + activations);
    }

    private void readinessTransition() {
        if (!hooks) { lifecycle(); return; }
        stage = "readiness transitions fence delayed repairs and commands";
        // Queue a delayed callback while ready, then change authority before it executes.
        fire(new PlayerChangedWorldEvent(player.player, Bukkit.getWorlds().getFirst()));
        readiness(false);
        player.put(8, null);
        player.put(4, quest.clone());
        player.cursor = nova.clone();
        ItemStack[] before = player.snapshot();
        ItemStack cursorBefore = player.cursor.clone();
        int writes = player.writes;
        int count = activations.size();
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, quest);
        fire(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND));
        fire(respawn());
        after(10, () -> {
            unchanged(player, before, writes, "Readiness false allowed inventory mutation");
            check(cursorBefore.equals(player.cursor), "Readiness false cleared a managed cursor");
            // The prior activation's cooldown has now expired: it cannot mask a missing gate.
            interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, quest);
            fire(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND));
            check(activations.size() == count, "Readiness false dispatched a command");
            readiness(true);
            waitFor(() -> restored() && empty(player.items[4]) && empty(player.cursor), this::lifecycle);
        });
    }

    private void lifecycle() {
        stage = "world, respawn, close and periodic restoration";
        player.cursor = null;
        player.put(7, null);
        fire(new PlayerChangedWorldEvent(player.player, Bukkit.getWorlds().getFirst()));
        waitFor(this::restored, () -> {
            player.put(8, null);
            fire(respawn());
            waitFor(this::restored, () -> {
                player.put(7, null);
                player.put(8, null);
                waitFor(this::restored, () -> {
                    player.put(7, null);
                    // Empty reserved slots must not receive ordinary shift transfers/pickups.
                    denied(click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "shift into missing reserved item");
                    denied(new EntityPickupItemEvent(player.player, dropped(lookalike), 0), "pickup with missing reserved item");
                    fire(new InventoryCloseEvent(player.view));
                    waitFor(this::restored, this::duplicates);
                });
            });
        });
    }

    private void duplicates() {
        stage = "stale managed identities and duplicate cleanup";
        ItemStack stale = quest.clone();
        stale.setType(Material.PAPER);
        var meta = stale.getItemMeta();
        meta.displayName(Component.text("Old Quest Book"));
        stale.setItemMeta(meta);
        stale.setAmount(5);
        player.put(7, stale);
        player.put(4, quest.clone());
        player.put(5, nova.clone());
        player.put(40, quest.clone());
        player.cursor = nova.clone();
        waitFor(() -> restored() && ownedCount() == 2 && empty(player.cursor)
                && player.items[7].isSimilar(quest) && player.items[7].getAmount() == 1, () -> {
            possessions();
            check(player.items[10].equals(lookalike), "Deduplication deleted an unmarked lookalike");
            cancelledDeath();
        });
    }

    private void cancelledDeath() {
        stage = "later death cancellation leaves active protection";
        Listener cancellation = new Listener() { };
        Bukkit.getPluginManager().registerEvent(PlayerDeathEvent.class, cancellation, EventPriority.HIGHEST,
                (listener, event) -> ((PlayerDeathEvent) event).setCancelled(true), this);
        DamageSource source = proxy(DamageSource.class, (object, method, args) -> defaultValue(method.getReturnType()));
        PlayerDeathEvent event = new PlayerDeathEvent(player.player, source, new ArrayList<>(), 0, Component.empty(), true);
        try { fire(event); } finally { HandlerList.unregisterAll(cancellation); }
        check(event.isCancelled(), "Later HIGHEST fixture did not cancel death");
        player.put(7, null);
        waitFor(this::restored, () -> {
            denied(click(player.raw(7), ClickType.LEFT, InventoryAction.PICKUP_ALL), "protection after cancelled death");
            death();
        });
    }

    private void death() {
        stage = "managed death drops preserve ordinary possessions";
        List<ItemStack> drops = new ArrayList<>(List.of(quest.clone(), nova.clone(), diamonds.clone(), lookalike.clone()));
        DamageSource source = proxy(DamageSource.class, (object, method, args) -> defaultValue(method.getReturnType()));
        PlayerDeathEvent death = new PlayerDeathEvent(player.player, source, drops, 0, Component.empty(), true);
        death.getItemsToKeep().add(quest.clone());
        death.getItemsToKeep().add(emeralds.clone());
        player.dead = true;
        fire(death);
        check(death.getDrops().size() == 2 && death.getDrops().contains(diamonds) && death.getDrops().contains(lookalike),
                "Death removed ordinary possessions or retained managed drops: " + death.getDrops());
        check(death.getDrops().stream().noneMatch(this::owned), "Managed item remained in death drops");
        check(!death.getKeepInventory(), "Hotbar changed ordinary keepInventory death rules");
        check(death.getItemsToKeep().equals(List.of(emeralds)), "Managed death keep list changed ordinary possessions");
        player.dead = false;
        fire(respawn());
        after(3, this::fullInventoryRejoin);
    }

    private void fullInventoryRejoin() {
        stage = "full inventory, session fencing and rejoin";
        Fake previous = player;
        previous.put(7, null);
        fire(new PlayerChangedWorldEvent(previous.player, Bukkit.getWorlds().getFirst()));
        quit(previous);
        ItemStack[] oldContents = previous.snapshot();
        int oldWrites = previous.writes;
        player = new Fake(previous.id);
        ItemStack filler = named(Material.STONE, 64, "Full inventory fixture");
        for (int slot = 0; slot < 36; slot++) player.put(slot, filler.clone());
        player.put(7, diamonds.clone());
        player.put(8, emeralds.clone());
        ItemStack[] full = player.snapshot();
        join(player);
        after(10, () -> {
            unchanged(previous, oldContents, oldWrites, "An old-session callback mutated disconnected inventory");
            check(Arrays.equals(full, player.snapshot()), "Full inventory changed or lost ordinary items");
            check(ownedCount() == 0 && player.drops == 0, "Full inventory replaced/dropped possessions");
            allowed(click(player.raw(7), ClickType.LEFT, InventoryAction.PICKUP_ALL), "ordinary item occupying reserved slot");
            allowed(click(player.raw(8), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "ordinary reserved item removal");
            player.held = 7;
            int count = activations.size();
            interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, diamonds);
            check(activations.size() == count, "An ordinary occupied reserved slot activated a command");
            // Free one slot at a time: the second possession must remain until space exists.
            player.put(0, null);
            waitFor(() -> owned(player.items[7]), () -> {
                check(player.items[0].equals(diamonds) && player.items[8].equals(emeralds),
                        "One free slot did not preserve both displaced possessions");
                check(count(filler) == 33L * 64, "First restoration changed ordinary filler count");
                player.put(1, null);
                waitFor(this::restored, () -> {
                    possessions();
                    check(player.items[1].equals(emeralds), "Second reserved possession was not preserved");
                    check(count(filler) == 32L * 64 && player.drops == 0, "Retry lost/dropped ordinary possessions");
                    assertItems();
                    nexoOutages();
                });
            });
        });
    }

    private void nexoOutages() {
        stage = "Nexo disable, stale generic items, unknown IDs and recovery";
        Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
        int commands = activations.size();
        player.held = 7;
        Bukkit.getPluginManager().disablePlugin(nexo);
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, quest);
        check(activations.size() == commands, "Disabled Nexo activated a cached shortcut");
        waitFor(() -> ownedCount() == 0, () -> {
            check(empty(player.items[7]) && empty(player.items[8]), "Disabled Nexo left generic replacements");
            // A prior release's owned generic items are cleaned up, never activated.
            player.put(7, legacy("quest-book"));
            player.cursor = legacy("novalink");
            interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, player.items[7]);
            after(5, () -> {
                check(empty(player.items[7]) && empty(player.cursor), "Disabled Nexo retained stale generic items");
                check(activations.size() == commands, "A legacy generic item activated a command");
                player.put(8, player.items[1]);
                player.put(1, null);
                nexoState("novaLinkAvailable", false);
                Bukkit.getPluginManager().enablePlugin(nexo);
                waitFor(() -> owned(player.items[7]), () -> {
                    check(player.items[8].equals(emeralds) && empty(player.items[1]), "An unknown Nexo ID displaced ordinary items");
                    nexoState("invalidQuest", true);
                    waitFor(() -> empty(player.items[7]), () -> {
                        allowed(click(player.raw(7), ClickType.LEFT, InventoryAction.PLACE_ALL), "empty slot for invalid Nexo result");
                        check(player.items[8].equals(emeralds), "Invalid Nexo results displaced possessions");
                        nexoState("invalidQuest", false);
                        nexoState("novaLinkAvailable", true);
                        waitFor(this::restored, () -> {
                            possessions();
                            assertItems();
                            check(player.items[1].equals(emeralds), "Nexo recovery lost the displaced ordinary item");
                            disabledSync();
                        });
                    });
                });
            });
        });
    }

    private void disabledSync() {
        if (!hooks) { succeed(); return; }
        stage = "installed disabled Inventory Sync fails closed";
        player.held = 7;
        Bukkit.getPluginManager().disablePlugin(inventorySync);
        check(!inventorySync.isEnabled(), "Could not disable the readiness fixture");
        player.put(8, null);
        ItemStack[] before = player.snapshot();
        int writes = player.writes;
        int count = activations.size();
        PlayerInteractEvent blocked = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, quest);
        check(blocked.useItemInHand() == Event.Result.DENY, "Disabled Sync unlocked managed item use");
        denied(new PlayerDropItemEvent(player.player, dropped(quest)), "managed drop with disabled Sync");
        denied(click(player.raw(7), ClickType.LEFT, InventoryAction.PICKUP_ALL), "managed move with disabled Sync");
        fire(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND));
        fire(new PlayerChangedWorldEvent(player.player, Bukkit.getWorlds().getFirst()));
        fire(respawn());
        DamageSource source = proxy(DamageSource.class, (object, method, args) -> defaultValue(method.getReturnType()));
        List<ItemStack> deathDrops = new ArrayList<>(List.of(quest.clone(), diamonds.clone()));
        fire(new PlayerDeathEvent(player.player, source, deathDrops, 0, Component.empty(), true));
        check(deathDrops.equals(List.of(diamonds)), "Disabled Sync let managed items escape in death drops");
        after(10, () -> {
            unchanged(player, before, writes, "Disabled installed Inventory Sync allowed mutations");
            check(activations.size() == count, "Disabled installed Inventory Sync dispatched a command");
            succeed();
        });
    }

    private void succeed() {
        healthy();
        check(player.drops == 0, "The hotbar dropped a player possession");
        finished = true;
        getLogger().info("CORE_HOTBAR_PROBE_OK: controlled players in Paper; "
                + (hooks ? "controlled Nexo API fixture only; metadata, missing/invalid items, recovery, readiness, "
                         + "disabled integrations, movement, commands, conservation, lifecycle, duplicates and death verified"
                         : "optional hooks absent; no shortcuts, no commands, legacy cleanup and possession conservation verified"));
        Bukkit.shutdown();
    }

    private void possessions() {
        check(count(diamonds) == 17 && count(emeralds) == 11, "Displaced ordinary item identities/amounts changed");
    }
    private long count(ItemStack identity) {
        return Arrays.stream(player.items).filter(item -> !empty(item) && item.isSimilar(identity))
                .mapToLong(ItemStack::getAmount).sum();
    }
    private int ownedCount() { return (int) Arrays.stream(player.items).filter(this::owned).count(); }
    private boolean owned(ItemStack item) {
        return !empty(item) && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(marker);
    }
    private boolean restored() {
        return owned(player.items[7]) && owned(player.items[8])
                && "quest-book".equals(player.items[7].getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING))
                && "novalink".equals(player.items[8].getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }
    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    private static String plain(Component text) { return text == null ? "" : PlainTextComponentSerializer.plainText().serialize(text); }
    private static ItemStack named(Material type, int amount, String name) {
        ItemStack item = new ItemStack(type, amount);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }
    private void unchanged(Fake target, ItemStack[] contents, int writes, String message) {
        check(Arrays.equals(contents, target.snapshot()) && target.writes == writes, message);
    }
    private void readiness(boolean ready) {
        if (!hooks) return;
        try { inventorySync.getClass().getField("ready").setBoolean(inventorySync, ready); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private boolean ready() {
        if (!hooks) return true;
        try { return inventorySync.isEnabled() && inventorySync.getClass().getField("ready").getBoolean(inventorySync); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void join(Fake target) { target.online = true; fire(new PlayerJoinEvent(target.player, Component.empty())); }
    private void quit(Fake target) {
        target.online = false;
        fire(new PlayerQuitEvent(target.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }
    private PlayerRespawnEvent respawn() {
        return new PlayerRespawnEvent(player.player, Bukkit.getWorlds().getFirst().getSpawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
    }
    private InventoryClickEvent click(int raw, ClickType type, InventoryAction action) {
        return new InventoryClickEvent(player.view, player.slotType(raw), raw, type, action);
    }
    private InventoryClickEvent number(int raw, int hotbar) {
        return new InventoryClickEvent(player.view, player.slotType(raw), raw, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, hotbar);
    }
    private InventoryCreativeEvent creative(int raw, ItemStack cursor) {
        return new InventoryCreativeEvent(player.view, player.slotType(raw), raw, cursor);
    }
    private InventoryDragEvent drag(ItemStack cursor, int raw) {
        return new InventoryDragEvent(player.view, null, cursor, false, Map.of(raw, cursor.clone()));
    }
    private PlayerInteractEvent interact(Action action, EquipmentSlot hand, ItemStack item) {
        var block = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK
                ? Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock() : null;
        var event = new PlayerInteractEvent(player.player, action, item, block, BlockFace.UP, hand);
        fire(event);
        return event;
    }
    private void denied(Event event, String action) {
        fire(event);
        check(event instanceof Cancellable cancelled && cancelled.isCancelled(), "Allowed " + action);
    }
    private void allowed(Event event, String action) {
        fire(event);
        check(event instanceof Cancellable cancelled && !cancelled.isCancelled(), "Blocked " + action);
    }
    private void fire(Event event) { Bukkit.getPluginManager().callEvent(event); healthy(); }
    private Entity entity() {
        return proxy(Entity.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> player.id;
            case "getWorld" -> Bukkit.getWorlds().getFirst();
            case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
            case "isValid" -> true;
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object == args[0];
            default -> defaultValue(method.getReturnType());
        });
    }
    private Item dropped(ItemStack stack) {
        ItemStack[] value = {stack.clone()};
        return proxy(Item.class, (object, method, args) -> switch (method.getName()) {
            case "getItemStack" -> value[0];
            case "setItemStack" -> { value[0] = ((ItemStack) args[0]).clone(); yield null; }
            case "getWorld" -> Bukkit.getWorlds().getFirst();
            case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
            case "isValid" -> true;
            case "remove" -> { throw new IllegalStateException("Hotbar removed a world item directly"); }
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object == args[0];
            default -> defaultValue(method.getReturnType());
        });
    }
    private void after(long ticks, Runnable next) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { if (!finished) { healthy(); next.run(); } } catch (Throwable error) { fail(error); }
        }, ticks);
    }
    private void waitFor(BooleanSupplier condition, Runnable next) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                try {
                    if (finished) { cancel(); return; }
                    healthy();
                    if (condition.getAsBoolean()) { cancel(); next.run(); }
                    else if (++ticks > 300) { cancel(); throw new IllegalStateException("Timed out: " + stage
                            + "; inventory=" + Arrays.toString(player.items)); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }
    private void healthy() {
        if (asynchronousFailure != null) throw new IllegalStateException("Fixture detected an asynchronous failure", asynchronousFailure);
    }
    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("CORE_HOTBAR_PROBE_FAILED: " + stage + ": " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private record Activation(String command, boolean console) { }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private final class Fake {
        final UUID id;
        final String name = "HotbarProbeUser";
        final ItemStack[] items = new ItemStack[43];
        final List<String> messages = new ArrayList<>();
        final Player player;
        PlayerInventory inventory;
        final Inventory top;
        InventoryView view;
        ItemStack cursor;
        int held;
        int writes;
        int drops;
        int maxStack = 64;
        boolean online = true;
        boolean dead;

        Fake(UUID id) {
            this.id = id;
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "getDisplayName" -> name;
                case "displayName", "name" -> Component.text(name);
                case "getServer" -> Bukkit.getServer();
                case "getPlayer" -> online ? object : null;
                case "isOnline" -> online;
                case "isDead" -> dead;
                case "isValid", "hasPlayedBefore", "hasPermission", "isPermissionSet" -> true;
                case "isOp", "isSneaking" -> false;
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getInventory" -> inventory;
                case "getOpenInventory" -> view;
                case "getItemOnCursor" -> cursor == null ? new ItemStack(Material.AIR) : cursor;
                case "setItemOnCursor" -> { mutation(); cursor = (ItemStack) args[0]; yield null; }
                case "getItemInHand" -> hand(held);
                case "setItemInHand" -> { mutation(); items[held] = (ItemStack) args[0]; yield null; }
                case "getWorld" -> Bukkit.getWorlds().getFirst();
                case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                case "getLevel", "getTotalExperience" -> 0;
                case "getExp" -> 0f;
                case "sendExperienceChange", "updateInventory", "closeInventory", "recalculatePermissions" -> null;
                case "getEffectivePermissions" -> java.util.Set.of();
                case "performCommand" -> Bukkit.dispatchCommand((Player) object, (String) args[0]);
                case "sendMessage" -> { append(args); yield null; }
                case "dropItem", "dropItemNaturally" -> { drops++; throw new IllegalStateException("Hotbar dropped an item"); }
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == args[0];
                case "toString" -> "HotbarProbe[" + id + "]";
                default -> defaultValue(method.getReturnType());
            });
            inventory = proxy(PlayerInventory.class, (object, method, args) -> {
                check(Bukkit.isPrimaryThread(), "Player inventory accessed off main thread: " + method.getName());
                return switch (method.getName()) {
                    case "getSize" -> items.length;
                    case "getType" -> InventoryType.PLAYER;
                    case "getHolder" -> player;
                    case "getViewers" -> List.of(player);
                    case "getMaxStackSize" -> maxStack;
                    case "setMaxStackSize" -> { mutation(); maxStack = (int) args[0]; yield null; }
                    case "getItem" -> items[index(args[0])];
                    case "setItem" -> { mutation(); items[index(args[0])] = (ItemStack) args[1]; yield null; }
                    case "getItemInMainHand", "getItemInHand" -> hand(held);
                    case "getItemInOffHand" -> hand(40);
                    case "setItemInMainHand", "setItemInHand" -> { mutation(); items[held] = (ItemStack) args[0]; yield null; }
                    case "setItemInOffHand" -> { mutation(); items[40] = (ItemStack) args[0]; yield null; }
                    case "getHeldItemSlot" -> held;
                    case "setHeldItemSlot" -> { mutation(); held = (int) args[0]; yield null; }
                    case "getContents" -> Arrays.copyOf(items, items.length);
                    case "getStorageContents" -> Arrays.copyOf(items, 36);
                    case "getArmorContents" -> Arrays.copyOfRange(items, 36, 40);
                    case "getExtraContents" -> Arrays.copyOfRange(items, 40, 43);
                    case "setContents" -> { replace(0, 43, (ItemStack[]) args[0]); yield null; }
                    case "setStorageContents" -> { replace(0, 36, (ItemStack[]) args[0]); yield null; }
                    case "setArmorContents" -> { replace(36, 40, (ItemStack[]) args[0]); yield null; }
                    case "setExtraContents" -> { replace(40, 43, (ItemStack[]) args[0]); yield null; }
                    case "getBoots" -> hand(36);
                    case "getLeggings" -> hand(37);
                    case "getChestplate" -> hand(38);
                    case "getHelmet" -> hand(39);
                    case "setBoots" -> { mutation(); items[36] = (ItemStack) args[0]; yield null; }
                    case "setLeggings" -> { mutation(); items[37] = (ItemStack) args[0]; yield null; }
                    case "setChestplate" -> { mutation(); items[38] = (ItemStack) args[0]; yield null; }
                    case "setHelmet" -> { mutation(); items[39] = (ItemStack) args[0]; yield null; }
                    case "firstEmpty" -> firstEmpty();
                    case "isEmpty" -> Arrays.stream(items).allMatch(HotbarProbe::empty);
                    case "addItem" -> add((ItemStack[]) args[0]);
                    case "removeItem", "removeItemAnySlot" -> remove((ItemStack[]) args[0]);
                    case "clear" -> {
                        mutation();
                        if (args == null || args.length == 0) Arrays.fill(items, null);
                        else items[(int) args[0]] = null;
                        yield null;
                    }
                    case "iterator" -> Arrays.asList(Arrays.copyOf(items, items.length)).listIterator(args == null || args.length == 0 ? 0 : (int) args[0]);
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "hashCode" -> System.identityHashCode(object);
                    case "equals" -> object == args[0];
                    case "toString" -> "HotbarProbeInventory[" + id + "]";
                    default -> throw new UnsupportedOperationException("Unimplemented inventory API: " + method);
                };
            });
            top = Bukkit.createInventory(null, 27, Component.text("Hotbar probe chest"));
            view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
                case "getPlayer" -> player;
                case "getTopInventory" -> top;
                case "getBottomInventory" -> inventory;
                case "getType" -> InventoryType.CHEST;
                case "countSlots" -> 63;
                case "convertSlot" -> convert((int) args[0]);
                case "getInventory" -> at((int) args[0]);
                case "getSlotType" -> slotType((int) args[0]);
                case "getItem" -> { int raw = (int) args[0]; Inventory source = at(raw); yield source == null ? null : source.getItem(convert(raw)); }
                case "setItem" -> { int raw = (int) args[0]; Inventory target = at(raw); if (target != null) target.setItem(convert(raw), (ItemStack) args[1]); yield null; }
                case "getCursor" -> cursor == null ? new ItemStack(Material.AIR) : cursor;
                case "setCursor" -> { mutation(); cursor = (ItemStack) args[0]; yield null; }
                case "getTitle", "getOriginalTitle" -> "Hotbar probe chest";
                case "title" -> Component.text("Hotbar probe chest");
                case "close", "open", "setTitle" -> null;
                case "setProperty" -> false;
                case "getMenuType" -> null;
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == args[0];
                case "toString" -> "HotbarProbeView[" + id + "]";
                default -> throw new UnsupportedOperationException("Unimplemented view API: " + method);
            });
        }

        void put(int slot, ItemStack item) { items[slot] = item; }
        ItemStack[] snapshot() { return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new); }
        ItemStack hand(int slot) { return items[slot] == null ? new ItemStack(Material.AIR) : items[slot]; }
        int raw(int slot) { check(slot >= 0 && slot < 36, "Invalid view storage slot"); return 27 + (slot < 9 ? 27 + slot : slot - 9); }
        int convert(int raw) { if (raw < 27) return raw; int lower = raw - 27; return lower < 27 ? lower + 9 : lower - 27; }
        Inventory at(int raw) { return raw < 0 || raw >= 63 ? null : raw < 27 ? top : inventory; }
        InventoryType.SlotType slotType(int raw) {
            return raw < 0 ? InventoryType.SlotType.OUTSIDE : raw >= 54 ? InventoryType.SlotType.QUICKBAR : InventoryType.SlotType.CONTAINER;
        }
        int index(Object argument) {
            if (argument instanceof Integer number) return number;
            return switch ((EquipmentSlot) argument) {
                case HAND -> held;
                case OFF_HAND -> 40;
                case FEET -> 36;
                case LEGS -> 37;
                case CHEST -> 38;
                case HEAD -> 39;
                case BODY -> 41;
                case SADDLE -> 42;
            };
        }
        void mutation() {
            check(Bukkit.isPrimaryThread(), "Inventory mutation ran off main thread");
            if (!ready()) {
                asynchronousFailure = new IllegalStateException("Inventory mutation without Inventory Sync authority at " + stage);
                throw new IllegalStateException("Inventory mutation without Inventory Sync authority");
            }
            writes++;
        }
        void replace(int from, int to, ItemStack[] contents) {
            mutation();
            check(contents != null && contents.length <= to - from, "Invalid inventory contents length");
            Arrays.fill(items, from, to, null);
            System.arraycopy(contents, 0, items, from, contents.length);
        }
        int firstEmpty() { for (int slot = 0; slot < 36; slot++) if (empty(items[slot])) return slot; return -1; }
        Map<Integer, ItemStack> add(ItemStack[] stacks) {
            mutation();
            Map<Integer, ItemStack> remaining = new HashMap<>();
            for (int input = 0; input < stacks.length; input++) {
                ItemStack item = stacks[input].clone();
                for (int slot = 0; slot < 36 && item.getAmount() > 0; slot++) {
                    if (empty(items[slot]) || !items[slot].isSimilar(item)) continue;
                    int amount = Math.min(item.getAmount(), Math.min(maxStack, item.getMaxStackSize()) - items[slot].getAmount());
                    if (amount > 0) { items[slot].setAmount(items[slot].getAmount() + amount); item.setAmount(item.getAmount() - amount); }
                }
                while (item.getAmount() > 0 && firstEmpty() >= 0) {
                    int slot = firstEmpty();
                    int amount = Math.min(item.getAmount(), Math.min(maxStack, item.getMaxStackSize()));
                    items[slot] = item.clone(); items[slot].setAmount(amount); item.setAmount(item.getAmount() - amount);
                }
                if (item.getAmount() > 0) remaining.put(input, item);
            }
            return remaining;
        }
        Map<Integer, ItemStack> remove(ItemStack[] stacks) {
            mutation();
            Map<Integer, ItemStack> remaining = new HashMap<>();
            for (int input = 0; input < stacks.length; input++) {
                ItemStack wanted = stacks[input].clone();
                for (int slot = 0; slot < 36 && wanted.getAmount() > 0; slot++) {
                    if (empty(items[slot]) || !items[slot].isSimilar(wanted)) continue;
                    int amount = Math.min(wanted.getAmount(), items[slot].getAmount());
                    wanted.setAmount(wanted.getAmount() - amount);
                    items[slot].setAmount(items[slot].getAmount() - amount);
                    if (empty(items[slot])) items[slot] = null;
                }
                if (wanted.getAmount() > 0) remaining.put(input, wanted);
            }
            return remaining;
        }
        void append(Object[] args) {
            if (args == null) return;
            for (Object arg : args) {
                if (arg instanceof String[] strings) messages.addAll(List.of(strings));
                else if (arg instanceof Component component) messages.add(plain(component));
                else if (arg instanceof String string) messages.add(string);
            }
        }
    }
}
