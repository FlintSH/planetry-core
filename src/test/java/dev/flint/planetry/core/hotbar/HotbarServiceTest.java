package dev.flint.planetry.core.hotbar;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class HotbarServiceTest {
    @Test void fixtureConstructorFailureAlwaysReleasesStaticFactories() {
        assertThrows(IllegalStateException.class, () -> new HotbarFixture(true));
        try (var fixture = new HotbarFixture()) {
            assertNotNull(HotbarSettings.read(fixture.config));
        }
    }

    @Test void constructionNeedsNoBukkitGlobalAndLifecycleRejectsCallsAwayFromTheServerThread() {
        try (var f = new HotbarFixture()) {
            f.service = new HotbarService(f.plugin, HotbarSettings.read(f.config), ignored -> true);
            verifyNoInteractions(f.inventory);
            verifyNoInteractions(f.scheduler);
            when(f.server.isPrimaryThread()).thenReturn(false);
            assertThrows(IllegalStateException.class, f.service::start);
            assertThrows(IllegalStateException.class, f.service::close);
            when(f.server.isPrimaryThread()).thenReturn(true);
        }
    }

    @Test void readyPlayersReceiveTwoMarkedItemsAndPollingRepairsMissingItems() {
        try (var f = new HotbarFixture()) {
            f.start();
            assertEquals("quest-book", f.id(7));
            assertEquals("novalink", f.id(8));
            assertEquals(Material.BOOK, f.storage[7].getType());
            assertEquals(Material.COMPASS, f.storage[8].getType());
            ItemStack existing = f.storage[8];
            clearInvocations(f.inventory);
            f.service.restore(f.player);
            verify(f.inventory, never()).setItem(anyInt(), any());
            f.storage[7] = null;
            f.polling.run();
            assertEquals("quest-book", f.id(7));
            assertSame(existing, f.storage[8]);
        }
    }

    @Test void missingNexoLeavesPossessionsAndEmptySlotsAvailableAndRetriesWhenItReturns() {
        try (var f = new HotbarFixture()) {
            f.nexoAvailable.set(false);
            ItemStack ordinary = f.stack(Material.DIAMOND, 17);
            f.storage[7] = ordinary;
            f.start();
            verify(f.inventory, never()).setItem(anyInt(), any());
            assertSame(ordinary, f.storage[7]);
            assertNull(f.storage[8]);
            var emptyClick = click(f, 8, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.service.click(emptyClick);
            assertFalse(emptyClick.isCancelled());
            ItemStack stone = f.stack(Material.STONE, 1);
            var drag = new InventoryDragEvent(f.view, stone, stone, false, java.util.Map.of(HotbarFixture.raw(8), stone));
            f.service.drag(drag);
            assertFalse(drag.isCancelled());
            f.nexoAvailable.set(true);
            f.polling.run();
            assertSame(ordinary, f.storage[0]);
            assertEquals("quest-book", f.id(7));
            assertEquals("novalink", f.id(8));
        }
    }

    @Test void unknownNexoIdsDoNotCreateFallbacksOrDisplaceOrdinaryItems() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.nexo-id", "unknown_item");
            ItemStack ordinary = f.stack(Material.DIAMOND, 17);
            f.storage[7] = ordinary;
            f.start();
            assertSame(ordinary, f.storage[7]);
            assertNull(f.storage[0]);
            assertEquals("novalink", f.id(8));
            f.storage[7] = null;
            var click = click(f, 7, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.service.click(click);
            assertFalse(click.isCancelled(), "An unavailable shortcut must not reserve its empty slot");
            f.nexoItems.put("unknown_item", f.nexoItems.get("quest_book"));
            f.polling.run();
            assertEquals("quest-book", f.id(7));
        }
    }

    @Test void onlyNexoMetadataIsUsedAndBuilderStacksAreNotMutated() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.material", "PAPER");
            f.config.set("hotbar.quest-book.display-name", "&cMust not replace Nexo name");
            ItemStack source = f.nexoItems.get("quest_book");
            source.setAmount(4);
            f.start();
            assertEquals(Material.BOOK, f.storage[7].getType());
            assertEquals(Component.text("Nexo Quest Book"), f.storage[7].getItemMeta().displayName());
            assertEquals("quest_book", f.stacks.get(f.storage[7]).data.get(new org.bukkit.NamespacedKey("nexo", "id")));
            assertEquals(1, f.storage[7].getAmount());
            assertEquals(4, source.getAmount());
            assertFalse(f.stacks.get(source).data.containsKey(f.marker));
        }
    }

    @Test void staleGenericOwnedItemsCannotActivateAndUnavailableNexoRemovesThemAfterReadiness() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.start();
            ItemStack stale = f.owned("quest-book");
            f.storage[7] = stale;
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, stale));
            verify(f.commands, never()).dispatch(any(), anyString());
            f.nexoAvailable.set(false);
            f.ready.set(false);
            f.polling.run();
            assertSame(stale, f.storage[7]);
            f.ready.set(true);
            f.polling.run();
            assertNull(f.storage[7]);
            assertNull(f.storage[8]);
            f.nexoAvailable.set(true);
            f.polling.run();
            ItemStack genuine = f.storage[7];
            f.nexoAvailable.set(false); // Before the next polling refresh, activation must already fail.
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, genuine));
            verify(f.commands, never()).dispatch(any(), anyString());
            ItemStack ordinary = f.stack(Material.DIAMOND, 17);
            f.storage[8] = ordinary;
            f.service.restore(f.player); // A queued event callback must refresh before displacing possessions.
            assertNull(f.storage[7]);
            assertSame(ordinary, f.storage[8]);
            assertNull(f.storage[0]);
        }
    }

    @Test void emptyOrUnusableNexoBuilderResultsFailClosedWithoutFallbacks() {
        try (var f = new HotbarFixture()) {
            // The fixture owns Material's registry boundary; use its configured
            // air material rather than mixing a real enum with that mocked registry.
            f.nexoItems.put("quest_book", f.stack(f.itemMaterials.get("AIR"), 1));
            ItemStack badMetadata = f.stack(Material.COMPASS, 1);
            when(badMetadata.clone()).thenReturn(badMetadata);
            when(badMetadata.getItemMeta()).thenReturn(null);
            f.nexoItems.put("novalink", badMetadata);
            f.start();
            assertNull(f.storage[7]);
            assertNull(f.storage[8]);
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    @Test void randomBuildMetadataDoesNotChurnIssuedItemsOrPreventTheirCommands() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.randomNexo.set(true);
            f.start();
            ItemStack issuedQuest = f.storage[7];
            ItemStack issuedNova = f.storage[8];
            clearInvocations(f.inventory);
            f.polling.run();
            f.service.restore(f.player);
            assertSame(issuedQuest, f.storage[7]);
            assertSame(issuedNova, f.storage[8]);
            verify(f.inventory, never()).setItem(anyInt(), any());
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, issuedQuest));
            verify(f.commands).dispatch(f.player, "quests");
        }
    }

    @Test void replacingTheRegisteredBuilderRefreshesItsMetadataAndRejectsThePriorTemplate() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.randomNexo.set(true);
            f.start();
            ItemStack old = f.storage[7];
            ItemStack replacement = f.nexoItems.get("quest_book").clone();
            f.stacks.get(replacement).name = Component.text("Reloaded Nexo Quest");
            f.nexoItems.put("quest_book", replacement);
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, old));
            verify(f.commands, never()).dispatch(any(), anyString());
            f.polling.run();
            assertNotSame(old, f.storage[7]);
            assertEquals(Component.text("Reloaded Nexo Quest"), f.storage[7].getItemMeta().displayName());
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]));
            verify(f.commands).dispatch(f.player, "quests");
        }
    }

    @Test void readinessGatesInitialApplicationAndLaterOwnershipTransitions() {
        try (var f = new HotbarFixture()) {
            f.ready.set(false);
            f.start();
            verify(f.inventory, never()).setItem(anyInt(), any());
            f.ready.set(true);
            f.polling.run();
            assertEquals("quest-book", f.id(7));
            ItemStack ordinary = f.stack(Material.DIAMOND, 12);
            f.storage[7] = ordinary;
            f.ready.set(false);
            clearInvocations(f.inventory);
            f.polling.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            assertSame(ordinary, f.storage[7]);
            f.ready.set(true);
            f.polling.run();
            assertSame(ordinary, f.storage[0]);
            assertEquals("quest-book", f.id(7));
        }
    }

    @Test void readinessExceptionsFailClosedAndDisabledServicesDoNotScheduleOrTouchStorage() {
        try (var f = new HotbarFixture()) {
            f.service = new HotbarService(f.plugin, HotbarSettings.read(f.config), player -> { throw new LinkageError("incompatible sync"); });
            f.service.start();
            f.flush();
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.enabled", false);
            f.start();
            assertNull(f.polling);
            assertTrue(f.pending.isEmpty());
            verifyNoInteractions(f.inventory);
        }
    }

    @Test void displacedItemsAndLookalikesAreRelocatedIntactToNonreservedStorageOnly() {
        try (var f = new HotbarFixture()) {
            ItemStack questLookalike = f.stack(Material.BOOK, 3);
            f.stacks.get(questLookalike).name = Component.text("Quest Book");
            ItemStack diamonds = f.stack(Material.DIAMOND, 64);
            f.storage[0] = f.stack(Material.STONE, 64);
            f.storage[7] = questLookalike;
            f.storage[8] = diamonds;
            f.start();
            assertSame(questLookalike, f.storage[1]);
            assertSame(diamonds, f.storage[2]);
            assertEquals(3, questLookalike.getAmount());
            assertEquals(64, diamonds.getAmount());
            assertEquals("quest-book", f.id(7));
            assertEquals("novalink", f.id(8));
            verify(f.player, never()).setItemOnCursor(any());
        }
    }

    @Test void fullInventoriesPreserveOrdinaryReservedItemsUntilSpaceBecomesAvailable() {
        try (var f = new HotbarFixture()) {
            Arrays.fill(f.storage, f.stack(Material.STONE, 64));
            ItemStack questSlot = f.stack(Material.DIAMOND, 7);
            ItemStack novaSlot = f.stack(Material.PAPER, 8);
            f.storage[7] = questSlot;
            f.storage[8] = novaSlot;
            f.start();
            verify(f.inventory, never()).setItem(anyInt(), any());
            assertSame(questSlot, f.storage[7]);
            assertSame(novaSlot, f.storage[8]);
            f.storage[35] = null;
            f.polling.run();
            assertSame(questSlot, f.storage[35]);
            assertEquals("quest-book", f.id(7));
            assertSame(novaSlot, f.storage[8]);
            f.storage[34] = null;
            f.polling.run();
            assertSame(novaSlot, f.storage[34]);
            assertEquals("novalink", f.id(8));
            assertNotNull(f.storage[36], "Armor slots must not be used to displace possessions");
            assertNotNull(f.storage[40], "Offhand must not be used to displace possessions");
        }
    }

    @Test void staleDuplicateArmorOffhandAndCursorCopiesAreRemovedWithoutTouchingOrdinaryItems() {
        try (var f = new HotbarFixture()) {
            f.storage[7] = f.owned("retired-item");
            f.storage[8] = f.owned("quest-book");
            f.storage[1] = f.owned("novalink");
            f.storage[36] = f.owned("retired-item");
            f.storage[40] = f.owned("quest-book");
            f.cursor.set(f.owned("quest-book"));
            ItemStack ordinary = f.stack(Material.BOOK, 1);
            f.storage[2] = ordinary;
            f.start();
            assertEquals("quest-book", f.id(7));
            assertEquals("novalink", f.id(8));
            assertNull(f.storage[1]);
            assertNull(f.storage[36]);
            assertNull(f.storage[40]);
            assertNull(f.cursor.get());
            assertSame(ordinary, f.storage[2]);
        }
    }

    @Test void malformedMarkerPrimitivesCannotActivateCommandsAndAreReplacedByRestoration() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.start();
            ItemStack malformed = f.owned("quest-book");
            f.stacks.get(malformed).data.put(f.marker, 42);
            f.storage[7] = malformed;
            var click = interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, malformed);
            assertDoesNotThrow(() -> f.service.interact(click));
            assertEquals(Event.Result.DENY, click.useItemInHand());
            verify(f.commands, never()).dispatch(any(), anyString());
            f.service.restore(f.player);
            assertEquals("quest-book", f.id(7));
            assertNotSame(malformed, f.storage[7]);
        }
    }

    @Test void rightClicksAcrossBlockEntityAndHandEventsShareOneCooldownAndLeftClicksDoNotActivate() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests {player} {uuid}");
            f.start();
            f.service.interact(interact(f, Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]));
            verify(f.commands, never()).dispatch(any(), anyString());
            var click = interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]);
            click.setCancelled(true); // Vanilla prediction pre-cancels an air interaction.
            f.service.interact(click);
            f.service.interact(interact(f, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, f.storage[7]));
            f.storage[40] = f.owned("quest-book");
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, f.storage[40]));
            f.service.entityInteract(new PlayerInteractEntityEvent(f.player, mock(Entity.class), EquipmentSlot.HAND));
            f.service.entityInteract(new PlayerInteractAtEntityEvent(f.player, mock(Entity.class), new Vector(), EquipmentSlot.HAND));
            String command = "quests Alice " + f.player.getUniqueId();
            verify(f.commands, times(1)).dispatch(f.player, command);
            assertEquals(Event.Result.DENY, click.useItemInHand());
            assertEquals(Event.Result.DENY, click.useInteractedBlock());
            f.time.addAndGet(300_000_000L);
            f.ticks.addAndGet(6);
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]));
            verify(f.commands, times(2)).dispatch(f.player, command);
        }
    }

    @Test void consoleCommandsUseSafeSubstitutionsAndOnlyTheConfiguredSelectedSlotCanActivate() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.novalink.command", "menu open {player} {uuid}");
            f.config.set("hotbar.novalink.executor", "console");
            f.start();
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[8]));
            verify(f.commands, never()).dispatch(any(), anyString());
            f.held = 8;
            when(f.player.getName()).thenReturn("Alice\nop Bob");
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[8]));
            verify(f.commands, never()).dispatch(any(), anyString());
            when(f.player.getName()).thenReturn("Alice");
            f.ready.set(false);
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[8]));
            verify(f.commands, never()).dispatch(any(), anyString());
            f.ready.set(true);
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[8]));
            verify(f.commands).dispatch(f.console, "menu open Alice " + f.player.getUniqueId());
        }
    }

    @Test void failedCommandDispatchIsNotRetriedAndEmptyCommandsDoNothing() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.start();
            when(f.commands.dispatch(any(), anyString())).thenThrow(new IllegalStateException("partial external effect"));
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]));
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[7]));
            verify(f.commands, times(1)).dispatch(f.player, "quests");
            f.held = 8;
            f.time.addAndGet(1_000_000_000L);
            f.service.interact(interact(f, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, f.storage[8]));
            verify(f.commands, times(1)).dispatch(any(), anyString());
        }
    }

    @Test void inventoryClicksCreativeCloningAndNumberKeysCannotMoveManagedOrEmptyReservedSlots() {
        try (var f = new HotbarFixture()) {
            f.start();
            for (ClickType type : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.DOUBLE_CLICK,
                    ClickType.DROP, ClickType.CONTROL_DROP, ClickType.MIDDLE, ClickType.SWAP_OFFHAND)) {
                var event = click(f, 7, type, InventoryAction.PICKUP_ALL);
                f.service.click(event);
                assertTrue(event.isCancelled(), type.toString());
            }
            var creative = new InventoryCreativeEvent(f.view, InventoryType.SlotType.QUICKBAR, HotbarFixture.raw(7), f.stack(Material.DIAMOND, 64));
            f.service.click(creative);
            assertTrue(creative.isCancelled());
            var number = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 8);
            f.service.click(number);
            assertTrue(number.isCancelled());
            f.storage[7] = null;
            var empty = click(f, 7, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.service.click(empty);
            assertTrue(empty.isCancelled());
            var emptyNumber = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 7);
            f.service.click(emptyNumber);
            assertTrue(emptyNumber.isCancelled());
            var shift = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            f.service.click(shift);
            assertTrue(shift.isCancelled());
        }
    }

    @Test void unrelatedContainerAndInventoryActionsRemainAvailableWhileMarkedCursorsAreBlocked() {
        try (var f = new HotbarFixture()) {
            f.start();
            var ordinary = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            f.service.click(ordinary);
            assertFalse(ordinary.isCancelled());
            var shift = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            f.service.click(shift);
            assertFalse(shift.isCancelled());
            f.cursor.set(f.stack(Material.BOOK, 1));
            var collect = click(f, 0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
            f.service.click(collect);
            assertFalse(collect.isCancelled(), "An unmarked cursor cannot collect stacks with our PDC");
            f.cursor.set(f.owned("quest-book"));
            var marked = new InventoryClickEvent(f.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.service.click(marked);
            assertTrue(marked.isCancelled());
        }
    }

    @Test void dragsProtectReservedSlotsAndPermitOtherContainerSlots() {
        try (var f = new HotbarFixture()) {
            f.start();
            ItemStack stone = f.stack(Material.STONE, 8);
            var ordinary = new InventoryDragEvent(f.view, stone, stone, false, java.util.Map.of(0, stone));
            f.service.drag(ordinary);
            assertFalse(ordinary.isCancelled());
            f.storage[7] = null;
            var reserved = new InventoryDragEvent(f.view, stone, stone, false, java.util.Map.of(HotbarFixture.raw(7), stone));
            f.service.drag(reserved);
            assertTrue(reserved.isCancelled());
            ItemStack owned = f.owned("quest-book");
            var marked = new InventoryDragEvent(f.view, owned, owned, false, java.util.Map.of(0, owned));
            f.service.drag(marked);
            assertTrue(marked.isCancelled());
        }
    }

    @Test void dropSwapPlaceConsumeDamageAndPickupProtectOnlyManagedItems() {
        try (var f = new HotbarFixture()) {
            f.start();
            Item marked = mock(Item.class);
            when(marked.getItemStack()).thenReturn(f.storage[7]);
            var drop = new PlayerDropItemEvent(f.player, marked);
            f.service.drop(drop);
            assertTrue(drop.isCancelled());
            var swap = new PlayerSwapHandItemsEvent(f.player, f.stack(Material.STONE, 1), f.storage[7]);
            f.service.swap(swap);
            assertTrue(swap.isCancelled());
            var place = new BlockPlaceEvent(mock(Block.class), mock(BlockState.class), mock(Block.class), f.storage[7], f.player, true, EquipmentSlot.HAND);
            f.service.place(place);
            assertTrue(place.isCancelled());
            var consume = new PlayerItemConsumeEvent(f.player, f.storage[7], EquipmentSlot.HAND);
            f.service.consume(consume);
            assertTrue(consume.isCancelled());
            var damage = new PlayerItemDamageEvent(f.player, f.storage[7], 1);
            f.service.damage(damage);
            assertTrue(damage.isCancelled());
            var pickup = new EntityPickupItemEvent(f.player, marked, 0);
            f.service.pickup(pickup);
            assertTrue(pickup.isCancelled());
            Item ordinaryItem = mock(Item.class);
            ItemStack ordinaryStack = f.stack(Material.DIAMOND, 5);
            when(ordinaryItem.getItemStack()).thenReturn(ordinaryStack);
            var normalPickup = new EntityPickupItemEvent(f.player, ordinaryItem, 0);
            f.service.pickup(normalPickup);
            assertFalse(normalPickup.isCancelled());
        }
    }

    @Test void fullInventoryOrdinaryReservedPossessionsCanBeUsedMovedAndDroppedNormally() {
        try (var f = new HotbarFixture()) {
            Arrays.fill(f.storage, f.stack(Material.STONE, 64));
            f.start();
            var click = click(f, 7, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            f.service.click(click);
            assertFalse(click.isCancelled());
            var use = interact(f, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, f.storage[7]);
            f.service.interact(use);
            assertNotEquals(Event.Result.DENY, use.useItemInHand());
            Item ordinary = mock(Item.class);
            when(ordinary.getItemStack()).thenReturn(f.storage[7]);
            var drop = new PlayerDropItemEvent(f.player, ordinary);
            f.service.drop(drop);
            assertFalse(drop.isCancelled());
            var swap = new PlayerSwapHandItemsEvent(f.player, f.stack(Material.DIAMOND, 1), f.storage[7]);
            f.service.swap(swap);
            assertFalse(swap.isCancelled());
        }
    }

    @Test void deathRemovesOnlyOwnedDropsAndRestoresAfterRespawnForBothKeepInventoryModes() {
        for (boolean keep : new boolean[]{false, true}) try (var f = new HotbarFixture()) {
            f.start();
            ItemStack ordinary = f.stack(Material.DIAMOND, 64);
            var drops = new ArrayList<>(List.of(f.storage[7], f.storage[8], f.owned("retired"), ordinary));
            var event = new PlayerDeathEvent(f.player, mock(DamageSource.class), drops, 42, Component.empty(), true);
            event.setKeepInventory(keep);
            event.getItemsToKeep().add(f.storage[7]);
            ItemStack retained = f.storage[7];
            f.service.death(event);
            f.service.deathCompleted(event);
            assertEquals(List.of(ordinary), drops);
            assertEquals(keep, event.getKeepInventory());
            assertEquals(42, event.getDroppedExp());
            if (!keep) {
                assertTrue(event.getItemsToKeep().isEmpty());
                f.storage[7] = null;
                f.storage[8] = null;
            }
            clearInvocations(f.inventory);
            f.polling.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            var respawn = mock(PlayerRespawnEvent.class);
            when(respawn.getPlayer()).thenReturn(f.player);
            f.service.respawn(respawn);
            f.flush();
            assertEquals("quest-book", f.id(7));
            assertEquals("novalink", f.id(8));
            if (keep) assertSame(retained, f.storage[7]);
        }
    }

    @Test void worldRestorationCallbacksRecheckReadinessAndOldSessionsCannotMutateRejoinedPlayers() {
        try (var f = new HotbarFixture()) {
            f.start();
            f.storage[7] = null;
            f.service.world(new PlayerChangedWorldEvent(f.player, mock(World.class)));
            f.ready.set(false);
            f.flush();
            assertNull(f.storage[7]);
            f.ready.set(true);
            f.service.world(new PlayerChangedWorldEvent(f.player, mock(World.class)));
            Runnable stale = f.pending.getFirst().action();
            f.service.quit(new PlayerQuitEvent(f.player, Component.empty()));
            Player next = mock(Player.class);
            var playerId = f.player.getUniqueId();
            when(next.getUniqueId()).thenReturn(playerId);
            when(next.isOnline()).thenReturn(true);
            when(next.getInventory()).thenReturn(f.inventory);
            f.service.join(new PlayerJoinEvent(next, Component.empty()));
            clearInvocations(f.inventory);
            stale.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            f.flush();
            assertEquals("quest-book", f.id(7));
            clearInvocations(f.inventory);
            f.service.restore(f.player);
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    @Test void closeCancelsOwnedTasksAndEvenManuallyRunLateCallbacksCannotMutate() {
        try (var f = new HotbarFixture()) {
            f.start();
            f.storage[7] = null;
            f.service.world(new PlayerChangedWorldEvent(f.player, mock(World.class)));
            var pending = f.pending.getFirst();
            f.service.close();
            verify(f.pollTask).cancel();
            verify(pending.task()).cancel();
            clearInvocations(f.inventory);
            pending.action().run();
            f.polling.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            assertNull(f.storage[7]);
        }
    }

    @Test void markedInputsRemainLockedWhileUnreadyWithoutReservingEmptySlotsOrWritingInventory() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.start();
            ItemStack marked = f.storage[7];
            f.ready.set(false);
            clearInvocations(f.inventory, f.player);
            var click = click(f, 7, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            f.service.click(click);
            assertTrue(click.isCancelled());
            Item entity = mock(Item.class);
            when(entity.getItemStack()).thenReturn(marked);
            var drop = new PlayerDropItemEvent(f.player, entity);
            f.service.drop(drop);
            assertTrue(drop.isCancelled());
            var use = interact(f, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, marked);
            f.service.interact(use);
            assertEquals(Event.Result.DENY, use.useItemInHand());
            assertEquals(Event.Result.DENY, use.useInteractedBlock());
            var entityUse = new PlayerInteractEntityEvent(f.player, mock(Entity.class), EquipmentSlot.HAND);
            f.service.entityInteract(entityUse);
            assertTrue(entityUse.isCancelled());
            var swap = new PlayerSwapHandItemsEvent(f.player, f.stack(Material.STONE, 1), marked);
            f.service.swap(swap);
            assertTrue(swap.isCancelled());
            f.storage[7] = null;
            var emptyClick = click(f, 7, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.service.click(emptyClick);
            assertFalse(emptyClick.isCancelled(), "Inventory authority can populate empty slots while unready");
            ItemStack ordinary = f.stack(Material.STONE, 3);
            var emptyDrag = new InventoryDragEvent(f.view, ordinary, ordinary, false, java.util.Map.of(HotbarFixture.raw(7), ordinary));
            f.service.drag(emptyDrag);
            assertFalse(emptyDrag.isCancelled());
            f.polling.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            verify(f.player, never()).setItemOnCursor(any());
            verify(f.commands, never()).dispatch(any(), anyString());
            assertNull(f.storage[7]);
        }
    }

    @Test void pickItemProtectsManagedSourcesDestinationsAndReadyEmptyReservedSlotsOnly() {
        try (var f = new HotbarFixture()) {
            f.start();
            var target = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 7, 12);
            f.service.pick(target);
            assertTrue(target.isCancelled());
            var source = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 0, 8);
            f.service.pick(source);
            assertTrue(source.isCancelled());
            f.storage[12] = f.owned("retired-item");
            var outside = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 0, 12);
            f.service.pick(outside);
            assertTrue(outside.isCancelled());
            var unrelated = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 0, 13);
            f.service.pick(unrelated);
            assertFalse(unrelated.isCancelled());
            f.storage[7] = null;
            var empty = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 7, -1);
            f.service.pick(empty);
            assertTrue(empty.isCancelled());
            f.ready.set(false);
            var unreadyEmpty = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 7, -1);
            f.service.pick(unreadyEmpty);
            assertFalse(unreadyEmpty.isCancelled());
            var unreadyManaged = new PlayerPickBlockEvent(f.player, mock(Block.class), false, 0, 8);
            f.service.pick(unreadyManaged);
            assertTrue(unreadyManaged.isCancelled());
        }
    }

    @Test void slowSynchronousCommandsCannotReopenPairedEntityActivationInTheSameServerTick() {
        try (var f = new HotbarFixture()) {
            f.config.set("hotbar.quest-book.command", "quests");
            f.start();
            when(f.commands.dispatch(f.player, "quests")).thenAnswer(call -> {
                f.time.addAndGet(400_000_000L);
                return true;
            });
            f.service.entityInteract(new PlayerInteractAtEntityEvent(f.player, mock(Entity.class), new Vector(), EquipmentSlot.HAND));
            f.service.entityInteract(new PlayerInteractEntityEvent(f.player, mock(Entity.class), EquipmentSlot.HAND));
            verify(f.commands, times(1)).dispatch(f.player, "quests");
            f.ticks.addAndGet(6);
            f.service.entityInteract(new PlayerInteractEntityEvent(f.player, mock(Entity.class), EquipmentSlot.HAND));
            verify(f.commands, times(2)).dispatch(f.player, "quests");
        }
    }

    @Test void aDeathCancelledAfterTheHighestHandlerDoesNotSuspendTheLivingSession() {
        try (var f = new HotbarFixture()) {
            f.start();
            var death = new PlayerDeathEvent(f.player, mock(DamageSource.class), new ArrayList<>(List.of(f.storage[7])), 0, Component.empty(), true);
            f.service.death(death);
            death.setCancelled(true); // A later HIGHEST listener cancels death.
            f.service.deathCompleted(death);
            f.storage[7] = null;
            f.polling.run();
            assertEquals("quest-book", f.id(7));
        }
    }

    @Test void pendingCallbacksCannotResumeAnUncancelledDeathBeforeRespawn() {
        try (var f = new HotbarFixture()) {
            f.start();
            f.service.world(new PlayerChangedWorldEvent(f.player, mock(World.class)));
            var death = new PlayerDeathEvent(f.player, mock(DamageSource.class), new ArrayList<>(List.of(f.storage[7])), 0, Component.empty(), true);
            f.service.death(death);
            f.service.deathCompleted(death);
            f.storage[7] = null;
            clearInvocations(f.inventory);
            f.flush();
            f.polling.run();
            verify(f.inventory, never()).setItem(anyInt(), any());
            var respawn = mock(PlayerRespawnEvent.class);
            when(respawn.getPlayer()).thenReturn(f.player);
            f.service.respawn(respawn);
            f.flush();
            assertEquals("quest-book", f.id(7));
        }
    }

    @Test void unreadyDeathFiltersOnlyManagedDropsAndKeepListWithoutWritingPlayerInventory() {
        try (var f = new HotbarFixture()) {
            f.start();
            ItemStack ordinary = f.stack(Material.DIAMOND, 12);
            var drops = new ArrayList<>(List.of(ordinary, f.storage[7], f.storage[8]));
            var death = new PlayerDeathEvent(f.player, mock(DamageSource.class), drops, 0, Component.empty(), true);
            death.getItemsToKeep().add(f.storage[7]);
            death.getItemsToKeep().add(ordinary);
            f.ready.set(false);
            clearInvocations(f.inventory, f.player);
            f.service.death(death);
            assertEquals(List.of(ordinary), drops);
            assertEquals(List.of(ordinary), death.getItemsToKeep());
            verify(f.inventory, never()).setItem(anyInt(), any());
            verify(f.player, never()).setItemOnCursor(any());
            verify(f.commands, never()).dispatch(any(), anyString());
        }
    }

    @Test void fullInventoryPartialOrdinaryStacksInReservedSlotsCanStillReceiveDrags() {
        try (var f = new HotbarFixture()) {
            Arrays.fill(f.storage, f.stack(Material.STONE, 64));
            f.storage[7] = f.stack(Material.STONE, 3);
            f.start();
            ItemStack stone = f.stack(Material.STONE, 8);
            var drag = new InventoryDragEvent(f.view, stone, stone, false, java.util.Map.of(HotbarFixture.raw(7), stone));
            f.service.drag(drag);
            assertFalse(drag.isCancelled());
            assertEquals(3, f.storage[7].getAmount());
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    private static InventoryClickEvent click(HotbarFixture f, int slot, ClickType click, InventoryAction action) {
        return new InventoryClickEvent(f.view, InventoryType.SlotType.QUICKBAR, HotbarFixture.raw(slot), click, action);
    }

    private static PlayerInteractEvent interact(HotbarFixture f, Action action, EquipmentSlot hand, ItemStack item) {
        return new PlayerInteractEvent(f.player, action, item, action == Action.RIGHT_CLICK_BLOCK ? mock(Block.class) : null, BlockFace.UP, hand);
    }
}
