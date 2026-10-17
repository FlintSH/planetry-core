package dev.flint.planetry.core.harvest;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Ownership lives on the entity, so it survives chunk unloading and a backend restart. */
public final class OwnedDrops implements Listener {
    private final JavaPlugin plugin;
    private final Predicate<Player> ready;
    private final NamespacedKey ownerKey;

    public OwnedDrops(JavaPlugin plugin, Predicate<Player> ready) {
        this.plugin = plugin; this.ready = ready;
        ownerKey = new NamespacedKey(plugin, "harvest-owner");
    }

    public void drop(Player player, Location location, ItemStack stack) {
        if (!ready(player)) throw new IllegalStateException("Inventory authority changed before drop");
        Item item = location.getWorld().dropItem(location, stack, spawned -> {
            spawned.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            spawned.setVisibleByDefault(false); // Consumer runs before world insertion/broadcast.
            spawned.setOwner(player.getUniqueId());
            spawned.setCanMobPickup(false);
            spawned.setGlowing(true);
            spawned.setPickupDelay(10);
        });
        player.showEntity(plugin, item);
    }

    public boolean owned(Item item) { return item.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING); }
    private boolean ready(Player player) {
        try { return player.isOnline() && !player.isDead() && ready.test(player); }
        catch (RuntimeException | LinkageError error) { return false; }
    }
    UUID owner(Item item) {
        String text = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try { return text == null ? null : UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    public void restore(Item item) {
        if (!owned(item)) return;
        item.setVisibleByDefault(false);
        item.setCanMobPickup(false);
        item.setGlowing(true);
        UUID owner = owner(item);
        if (owner == null) { item.setCanPlayerPickup(false); return; }
        item.setOwner(owner);
        Player player = plugin.getServer().getPlayer(owner);
        if (player != null && player.isOnline()) player.showEntity(plugin, item);
    }
    public void start() {
        for (var world : plugin.getServer().getWorlds()) for (Item item : world.getEntitiesByClass(Item.class)) restore(item);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void pickup(EntityPickupItemEvent event) {
        if (!owned(event.getItem())) return;
        if (!(event.getEntity() instanceof Player player) || !player.getUniqueId().equals(owner(event.getItem())) || !ready(player)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void hopper(InventoryPickupItemEvent event) {
        if (owned(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void merge(ItemMergeEvent event) {
        if (owned(event.getEntity()) || owned(event.getTarget())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void track(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Item item && owned(item) && !event.getPlayer().getUniqueId().equals(owner(item))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void load(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) if (entity instanceof Item item) restore(item);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (var world : plugin.getServer().getWorlds()) for (Item item : world.getEntitiesByClass(Item.class))
            if (owned(item) && player.getUniqueId().equals(owner(item))) player.showEntity(plugin, item);
    }
}
