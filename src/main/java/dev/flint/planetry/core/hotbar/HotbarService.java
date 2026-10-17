package dev.flint.planetry.core.hotbar;

import io.papermc.paper.event.player.PlayerPickItemEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread hotbar ownership. Inventory Sync's readiness always takes precedence. */
public final class HotbarService implements Listener, AutoCloseable {
    private static final class Session {
        final Player player;
        BukkitTask pending;
        boolean suspended;
        boolean resumeAfterRespawn;
        boolean activated;
        long lastActivation;
        int lastActivationTick;
        Session(Player player) { this.player = player; }
    }

    private final JavaPlugin plugin;
    private final HotbarSettings settings;
    private final Predicate<Player> readiness;
    private final LongSupplier nanoTime;
    private final HotbarItems items;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask poll;
    private boolean started;
    private boolean closed;
    private boolean readinessWarning;

    public HotbarService(JavaPlugin plugin, HotbarSettings settings, Predicate<Player> readiness) {
        this(plugin, settings, readiness, System::nanoTime);
    }

    HotbarService(JavaPlugin plugin, HotbarSettings settings, Predicate<Player> readiness, LongSupplier nanoTime) {
        this(plugin, settings, readiness, nanoTime, new HotbarItems(plugin));
    }

    HotbarService(JavaPlugin plugin, HotbarSettings settings, Predicate<Player> readiness,
                  LongSupplier nanoTime, HotbarItems items) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.items = Objects.requireNonNull(items, "items");
    }

    /** Register this Listener separately, then start it once during plugin enable. */
    public void start() {
        mainThread();
        if (closed) throw new IllegalStateException("The hotbar service is closed");
        if (started) return;
        started = true;
        if (!settings.enabled()) return;
        items.refresh(settings);
        plugin.getServer().getOnlinePlayers().forEach(this::track);
        poll = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1, settings.restoreIntervalTicks());
    }

    @Override public void close() {
        mainThread();
        if (closed) return;
        closed = true;
        if (poll != null) poll.cancel();
        for (Session session : sessions.values()) if (session.pending != null) session.pending.cancel();
        sessions.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { track(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            sessions.remove(event.getPlayer().getUniqueId());
            if (session.pending != null) session.pending.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void world(PlayerChangedWorldEvent event) { later(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) {
        Session session = session(event.getPlayer());
        if (session == null) return;
        session.suspended = true;
        session.resumeAfterRespawn = true;
        later(event.getPlayer());
    }

    private void track(Player player) {
        if (!running()) return;
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null && previous.player == player) return;
        if (previous != null && previous.pending != null) previous.pending.cancel();
        sessions.put(player.getUniqueId(), new Session(player));
        later(player);
    }

    private void poll() {
        if (!running()) return;
        items.refresh(settings);
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.player.isOnline()) {
                if (sessions.remove(session.player.getUniqueId(), session) && session.pending != null) session.pending.cancel();
            } else restore(session.player, false);
        }
    }

    private void later(Player player) {
        Session session = session(player);
        if (session == null || session.pending != null) return;
        session.pending = plugin.getServer().getScheduler().runTask(plugin, () -> {
            // The captured session, not UUID alone, fences reconnects and stale callbacks.
            if (session(player) != session) return;
            session.pending = null;
            if (session.resumeAfterRespawn) {
                session.suspended = false;
                session.resumeAfterRespawn = false;
            }
            restore(player);
        });
    }

    void restore(Player player) {
        restore(player, true);
    }

    private void restore(Player player, boolean refreshItems) {
        mainThread();
        if (!usable(player)) return;
        // Event callbacks can run between polls while Nexo disables or reloads.
        // Polls already refreshed once for their whole batch of player sessions.
        if (refreshItems) items.refresh(settings);
        PlayerInventory inventory = player.getInventory();
        // Only our PDC marker grants ownership. Ordinary lookalikes are player possessions.
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!reserved(slot) && items.owned(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
        if (items.owned(player.getItemOnCursor())) player.setItemOnCursor(null);
        for (var definition : settings.items()) {
            ItemStack current = inventory.getItem(definition.slot());
            ItemStack template = items.template(definition);
            if (template == null) {
                if (items.owned(current)) inventory.setItem(definition.slot(), null);
                continue; // Missing Nexo must never move possessions or create a generic shortcut.
            }
            if (!HotbarItems.empty(current) && !items.owned(current)) {
                int free = freeSlot(inventory);
                if (free < 0) continue; // Never delete or drop possessions to reserve a slot.
                inventory.setItem(free, current);
            }
            if (HotbarItems.empty(current) || !items.owned(current) || current.getAmount() != 1 || !template.isSimilar(current))
                inventory.setItem(definition.slot(), template.clone());
        }
    }

    private int freeSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < 36; slot++)
            if (!reserved(slot) && HotbarItems.empty(inventory.getItem(slot))) return slot;
        return -1;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !protecting(player)) return;
        boolean reserveEmpty = usable(player);
        PlayerInventory inventory = player.getInventory();
        boolean bottom = same(event.getClickedInventory(), inventory);
        boolean deny = items.owned(event.getCurrentItem()) || items.owned(event.getCursor())
                || bottom && protectedSlot(inventory, event.getSlot(), reserveEmpty);
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            int button = event.getHotbarButton();
            deny |= items.owned(inventory.getItem(button)) || protectedSlot(inventory, button, reserveEmpty);
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) deny |= items.owned(inventory.getItemInOffHand());
        // A shift transfer can select an empty reserved slot without clicking it.
        if (reserveEmpty && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && emptyReserved(inventory)) {
            boolean selfCrafting = event.getView().getType() == InventoryType.CRAFTING;
            deny |= event.getClickedInventory() != null && (!bottom || selfCrafting);
        }
        if (deny) event.setCancelled(true);
        if (reserveEmpty && (deny || bottom && reserved(event.getSlot()))) later(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void drag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !protecting(player)) return;
        boolean reserveEmpty = usable(player);
        boolean deny = items.owned(event.getOldCursor()) || items.owned(event.getCursor());
        for (int raw : event.getRawSlots())
            if (same(event.getView().getInventory(raw), player.getInventory())
                    && protectedSlot(player.getInventory(), event.getView().convertSlot(raw), reserveEmpty)) deny = true;
        if (deny) {
            event.setCancelled(true);
            if (reserveEmpty) later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void inventoryClosed(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) later(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void drop(PlayerDropItemEvent event) {
        if (!protecting(event.getPlayer()) || !items.owned(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        if (usable(event.getPlayer())) later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!protecting(player)) return;
        boolean reserveEmpty = usable(player);
        if (items.owned(event.getMainHandItem()) || items.owned(event.getOffHandItem())
                || protectedSlot(player.getInventory(), player.getInventory().getHeldItemSlot(), reserveEmpty)) {
            event.setCancelled(true);
            if (reserveEmpty) later(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void pick(PlayerPickItemEvent event) {
        Player player = event.getPlayer();
        if (!protecting(player)) return;
        PlayerInventory inventory = player.getInventory();
        boolean reserveEmpty = usable(player);
        int source = event.getSourceSlot();
        int target = event.getTargetSlot();
        // Paper swaps these storage slots after this event, including creative block/entity picks.
        boolean deny = protectedSlot(inventory, target, reserveEmpty) || items.owned(inventory.getItem(target));
        if (source >= 0 && source < 36)
            deny |= protectedSlot(inventory, source, reserveEmpty) || items.owned(inventory.getItem(source));
        if (deny) {
            event.setCancelled(true);
            if (reserveEmpty) later(player);
        }
    }

    // Air interactions are often pre-cancelled by vanilla prediction, so this must receive cancelled events.
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!protecting(player) || !items.owned(event.getItem())) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
            activate(player, event.getHand(), event.getItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void entityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!protecting(player)) return;
        ItemStack held = player.getInventory().getItem(event.getHand());
        if (!items.owned(held)) return;
        event.setCancelled(true);
        activate(player, event.getHand(), held);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void armorStand(PlayerArmorStandManipulateEvent event) {
        if (protecting(event.getPlayer()) && (items.owned(event.getPlayerItem()) || items.owned(event.getArmorStandItem()))) event.setCancelled(true);
    }

    private void activate(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand != EquipmentSlot.HAND || !usable(player)) return;
        HotbarSettings.Item definition = null;
        String id = items.id(item);
        for (var candidate : settings.items())
            if (candidate.slot() == player.getInventory().getHeldItemSlot() && candidate.id().equals(id)) definition = candidate;
        if (definition == null || !definition.id().equals(items.id(player.getInventory().getItem(definition.slot())))
                || definition.command().isEmpty()) return;
        // Check live Nexo availability, then compare with the issued template from
        // that builder. A fresh build can contain random per-item metadata.
        ItemStack template = items.liveTemplate(definition);
        ItemStack held = player.getInventory().getItem(definition.slot());
        if (template == null || item.getAmount() != 1 || held.getAmount() != 1
                || !template.isSimilar(item) || !template.isSimilar(held)) return;
        String name = player.getName();
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,32}")) return;
        Session session = session(player);
        long now = nanoTime.getAsLong();
        int tick = plugin.getServer().getCurrentTick();
        long cooldown = settings.activationCooldownTicks() * 50_000_000L;
        if (session.activated && (now - session.lastActivation < cooldown
                || Integer.toUnsignedLong(tick - session.lastActivationTick) < settings.activationCooldownTicks())) return;
        // Set before dispatch: command handlers may trigger another event synchronously.
        session.activated = true;
        session.lastActivation = now;
        session.lastActivationTick = tick;
        String command = definition.command().replace("{player}", name).replace("{uuid}", player.getUniqueId().toString());
        if (!usable(player)) return;
        try {
            var sender = definition.executor() == HotbarSettings.Executor.CONSOLE ? plugin.getServer().getConsoleSender() : player;
            if (!plugin.getServer().getCommandMap().dispatch(sender, command))
                plugin.getLogger().warning("Hotbar command is unavailable for " + definition.id() + "; check its configuration.");
        } catch (RuntimeException | LinkageError error) {
            // Commands can have partial external effects; never retry a failed dispatch automatically.
            plugin.getLogger().log(Level.WARNING, "Hotbar command failed for " + definition.id() + "; it was not retried.", error);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void place(BlockPlaceEvent event) {
        if (protecting(event.getPlayer()) && items.owned(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void consume(PlayerItemConsumeEvent event) {
        if (protecting(event.getPlayer()) && items.owned(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void damage(PlayerItemDamageEvent event) {
        if (protecting(event.getPlayer()) && items.owned(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !protecting(player)) return;
        boolean reserveEmpty = usable(player);
        if (items.owned(event.getItem().getItemStack()) || reserveEmpty && emptyReserved(player.getInventory())) {
            event.setCancelled(true);
            if (reserveEmpty) later(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void death(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!protecting(player)) return;
        // Do not alter keepInventory or ordinary drops. Respawn restores new copies after vanilla clears storage.
        event.getDrops().removeIf(items::owned);
        if (!event.getKeepInventory()) event.getItemsToKeep().removeIf(items::owned);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void deathCompleted(PlayerDeathEvent event) {
        // Another HIGHEST listener may cancel death after our drop filter. Observe final state only.
        if (event.isCancelled()) return;
        Session session = session(event.getEntity());
        if (session != null) {
            session.suspended = true;
            session.resumeAfterRespawn = false;
        }
    }

    private boolean emptyReserved(PlayerInventory inventory) {
        return settings.items().stream().anyMatch(item -> items.template(item) != null
                && HotbarItems.empty(inventory.getItem(item.slot())));
    }

    private boolean protectedSlot(PlayerInventory inventory, int slot, boolean reserveEmpty) {
        return reserved(slot) && (items.owned(inventory.getItem(slot))
                || reserveEmpty && items.available(slot, settings) && HotbarItems.empty(inventory.getItem(slot)));
    }

    private static boolean reserved(int slot) { return slot == 7 || slot == 8; }
    private static boolean same(Inventory first, Inventory second) { return first != null && first.equals(second); }
    private boolean running() { return started && !closed && settings.enabled(); }

    private Session session(Player player) {
        if (!running() || !plugin.getServer().isPrimaryThread() || !player.isOnline()) return null;
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.player == player ? session : null;
    }

    private boolean usable(Player player) {
        Session session = session(player);
        return session != null && !session.suspended && !player.isDead() && ready(player);
    }

    private boolean protecting(Player player) { return session(player) != null; }

    private boolean ready(Player player) {
        try { return readiness.test(player); }
        catch (RuntimeException | LinkageError error) {
            if (!readinessWarning) {
                readinessWarning = true;
                plugin.getLogger().warning("Hotbar inventory readiness is unavailable; refusing inventory changes and commands.");
            }
            return false;
        }
    }

    private void mainThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Hotbar lifecycle and inventory access require the server thread");
    }
}
