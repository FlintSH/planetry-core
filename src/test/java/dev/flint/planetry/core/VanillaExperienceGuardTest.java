package dev.flint.planetry.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;

class VanillaExperienceGuardTest {
    private final Player player = mock(Player.class);
    private final List<Player> explained = new ArrayList<>();
    private final VanillaExperienceGuard guard = new VanillaExperienceGuard(true, explained::add);

    @Test void positiveAndNegativeNaturalExperienceChangesAreSuppressedWithoutRewritingTheLedger() {
        for (int amount : new int[]{15, -8, 0}) {
            var event = new PlayerExpChangeEvent(player, amount);
            guard.gain(event);
            assertEquals(0, event.getAmount());
        }
        verifyNoInteractions(player);
    }

    @Test void deathKeepsExistingExperienceAndSuppressesExperienceDropsWithoutKeepingItems() {
        var event = death();
        guard.death(event);

        assertTrue(event.getKeepLevel());
        assertEquals(0, event.getDroppedExp());
        assertFalse(event.shouldDropExperience());
        assertFalse(event.getKeepInventory(), "Locking XP must not change item death rules");
        verify(player, never()).setLevel(anyInt());
        verify(player, never()).setExp(anyFloat());
        verify(player, never()).setTotalExperience(anyInt());
    }

    @Test void mendingCannotConsumeExperienceOrRepairAnItemWhileLocked() {
        var event = mend();
        guard.mend(event);
        assertTrue(event.isCancelled());
        assertEquals(8, event.getRepairAmount());
        assertEquals(4, event.getConsumedExperience());
    }

    @Test void survivalAndAdventureEnchantingAreDeniedWithAnExplanation() {
        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.ADVENTURE)) {
            when(player.getGameMode()).thenReturn(mode);
            var event = enchant();
            guard.enchant(event);
            assertTrue(event.isCancelled());
        }
        assertEquals(List.of(player, player), explained);
    }

    @Test void creativeEnchantingIsAllowedWithoutChargingExperience() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        var event = enchant();
        guard.enchant(event);
        assertFalse(event.isCancelled());
        assertTrue(explained.isEmpty());
    }

    @Test void chargedAnvilOutputIsDeniedForNormalShiftHotbarAndDropClicks() {
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        for (ClickType click : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY,
                ClickType.SWAP_OFFHAND, ClickType.DROP)) {
            var event = anvil(2, 4, click);
            guard.anvil(event);
            assertTrue(event.isCancelled(), "Charged anvil output escaped via " + click);
        }
        assertEquals(5, explained.size());
    }

    @Test void freeAnvilOutputInputsAndCreativeActionsRemainAvailable() {
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var free = anvil(2, 0, ClickType.LEFT);
        var input = anvil(1, 4, ClickType.LEFT);
        guard.anvil(free);
        guard.anvil(input);
        assertFalse(free.isCancelled());
        assertFalse(input.isCancelled());

        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        var creative = anvil(2, 40, ClickType.SHIFT_LEFT);
        guard.anvil(creative);
        assertFalse(creative.isCancelled());
        assertTrue(explained.isEmpty());
    }

    @Test void resultSlotsInOtherInventoriesAreNotBlocked() {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        var event = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        guard.anvil(event);
        assertFalse(event.isCancelled());
        assertTrue(explained.isEmpty());
    }

    @Test void disabledLockLeavesVanillaGainsDeathMendingAndCostsUnchanged() {
        var disabled = new VanillaExperienceGuard(false, explained::add);
        var gain = new PlayerExpChangeEvent(player, 15);
        var death = death();
        var mend = mend();
        var enchant = enchant();
        var anvil = anvil(2, 4, ClickType.LEFT);

        disabled.gain(gain);
        disabled.death(death);
        disabled.mend(mend);
        disabled.enchant(enchant);
        disabled.anvil(anvil);

        assertEquals(15, gain.getAmount());
        assertFalse(death.getKeepLevel());
        assertEquals(42, death.getDroppedExp());
        assertTrue(death.shouldDropExperience());
        assertFalse(mend.isCancelled());
        assertFalse(enchant.isCancelled());
        assertFalse(anvil.isCancelled());
        assertTrue(explained.isEmpty());
    }

    @Test void vanillaExperienceIsLockedByDefaultButCanBeExplicitlyDisabled() {
        var config = new YamlConfiguration();
        config.set("server-id", "test-server");
        config.createSection("messages");
        assertTrue(Settings.read(config).lockVanillaExperience());
        config.set("vanilla-experience.lock", false);
        assertFalse(Settings.read(config).lockVanillaExperience());
    }

    private PlayerDeathEvent death() {
        return new PlayerDeathEvent(player, mock(DamageSource.class), new ArrayList<>(), 42,
                Component.empty(), true);
    }

    private PlayerItemMendEvent mend() {
        return new PlayerItemMendEvent(player, mock(ItemStack.class), EquipmentSlot.HAND,
                mock(ExperienceOrb.class), 8, 4);
    }

    private EnchantItemEvent enchant() {
        // The unused enchantment hint may be null in this event-only unit fixture.
        return new EnchantItemEvent(player, mock(InventoryView.class), null, mock(ItemStack.class),
                30, Map.of(), null, 1, 0);
    }

    private InventoryClickEvent anvil(int slot, int cost, ClickType click) {
        AnvilView view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getRepairCost()).thenReturn(cost);
        return new InventoryClickEvent(view, slot == 2 ? InventoryType.SlotType.RESULT : InventoryType.SlotType.CRAFTING,
                slot, click, InventoryAction.PICKUP_ALL);
    }
}
