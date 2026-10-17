package dev.flint.planetry.core;

import java.util.function.Consumer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.view.AnvilView;

/** Game XP replaces vanilla progression; never spends game levels or grants free XP-cost actions. */
final class VanillaExperienceGuard implements Listener {
    private final boolean enabled;
    private final Consumer<Player> explain;
    VanillaExperienceGuard(boolean enabled, Consumer<Player> explain) { this.enabled = enabled; this.explain = explain; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void gain(PlayerExpChangeEvent event) { if (enabled) event.setAmount(0); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mend(PlayerItemMendEvent event) { if (enabled) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if (!enabled) return;
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void enchant(EnchantItemEvent event) {
        if (!enabled || event.getEnchanter().getGameMode() == GameMode.CREATIVE) return;
        event.setCancelled(true);
        explain.accept(event.getEnchanter());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void anvil(InventoryClickEvent event) {
        if (!enabled || event.getRawSlot() != 2 || !(event.getView() instanceof AnvilView view)
                || view.getRepairCost() <= 0 || !(event.getWhoClicked() instanceof Player player)
                || player.getGameMode() == GameMode.CREATIVE) return;
        event.setCancelled(true);
        explain.accept(player);
    }
}
