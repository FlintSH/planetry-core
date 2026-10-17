package dev.flint.planetry.core.harvest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarvestServiceTest {
    @TempDir Path folder;
    @Test void acceptedNodeBreakHasNoVanillaEffectsAndStartsDurableCooldownBeforeRewards() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            var event = new BlockBreakEvent(f.ore, f.player); event.setExpToDrop(8);
            f.service.breakBlock(event);
            assertTrue(event.isCancelled()); assertFalse(event.isDropItems()); assertEquals(0, event.getExpToDrop());
            assertEquals(Material.BEDROCK, f.ore.getType()); assertTrue(f.node().cooling()); assertEquals("", f.node().previous());
            assertEquals(2, f.granted.get()); assertEquals(List.of("ore"), f.journal);
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player));
            assertEquals(2, f.granted.get());
            f.time.addAndGet(8000); f.service.poll();
            assertEquals(Material.OBSIDIAN, f.ore.getType()); assertFalse(f.node().cooling());
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertEquals(4, f.granted.get());
        }
    }
    @Test void canceledProtectionEventsUnboundBlocksAndUnreadyInventoriesNeverGrantRewards() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            var denied = new BlockBreakEvent(f.ore, f.player); denied.setCancelled(true); f.service.breakBlock(denied);
            assertEquals(0, f.granted.get()); assertFalse(f.node().cooling());
            var ordinary = new BlockBreakEvent(f.block(2, 64, 0), f.player); f.service.breakBlock(ordinary); assertFalse(ordinary.isCancelled());
            f.ready.set(false); var unready = new BlockBreakEvent(f.ore, f.player); f.service.breakBlock(unready);
            assertTrue(unready.isCancelled()); assertFalse(f.node().cooling()); assertEquals(Material.OBSIDIAN, f.ore.getType());
            f.ready.set(true); f.itemsAvailable = false; f.service.breakBlock(new BlockBreakEvent(f.ore, f.player));
            assertFalse(f.node().cooling()); assertEquals(0, f.granted.get());
        }
    }
    @Test void cropsVisitIntermediateAgesAndNaturalGrowthCannotBypassCooldown() throws Exception {
        try (var f = new HarvestFixture(folder, true)) {
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertEquals(0, ((Ageable)f.ore.getBlockData()).getAge());
            var grow = new BlockGrowEvent(f.ore, f.ore.getState()); f.service.grow(grow); assertTrue(grow.isCancelled());
            for (int age = 1; age <= 7; age++) {
                f.time.addAndGet(1000); f.service.poll(); assertEquals(Math.min(age, 6), ((Ageable)f.ore.getBlockData()).getAge());
                f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertEquals(2, f.granted.get());
            }
            f.time.addAndGet(1000); f.service.poll(); assertEquals(7, ((Ageable)f.ore.getBlockData()).getAge());
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertEquals(4, f.granted.get());
        }
    }
    @Test void persistedPendingWorldTransitionRecoversWithoutRepeatingRewards() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            var intent = f.node().intent("minecraft:bedrock", f.time.get(), f.time.get() + 8000);
            f.store.replace(Map.of(intent.key(), intent)); f.service.poll();
            assertEquals(Material.BEDROCK, f.ore.getType()); assertEquals("", f.node().previous()); assertEquals(0, f.granted.get());
            f.time.addAndGet(8000); f.service.poll(); assertEquals(Material.OBSIDIAN, f.ore.getType()); assertEquals(0, f.granted.get());
        }
    }
    @Test void externalReplacementSuspendsNodeWithoutOverwritingOrGrantingRewards() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); f.data.get(f.ore).set(f.make(Material.DIAMOND_BLOCK, -1));
            f.time.addAndGet(8000); f.service.poll(); assertEquals(Material.DIAMOND_BLOCK, f.ore.getType()); assertTrue(f.node().suspended());
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertEquals(2, f.granted.get());
        }
    }
    @Test void protectedNodesAndCropSupportsResistEnvironmentalChanges() throws Exception {
        try (var f = new HarvestFixture(folder, true)) {
            var physics = new BlockPhysicsEvent(f.ore, f.ore.getBlockData()); f.service.physics(physics); assertTrue(physics.isCancelled());
            var fluid = new BlockFromToEvent(f.block(1, 64, 0), f.ore); f.service.flow(fluid); assertTrue(fluid.isCancelled());
            var supportBreak = new BlockBreakEvent(f.block(0, 63, 0), f.player); f.service.breakBlock(supportBreak); assertTrue(supportBreak.isCancelled());
            var explosion = mock(EntityExplodeEvent.class); var blocks = new ArrayList<>(List.of(f.ore, f.block(2, 64, 0)));
            when(explosion.blockList()).thenReturn(blocks); f.service.explode(explosion); assertEquals(List.of(f.block(2, 64, 0)), blocks);
        }
    }
    @Test void uncertainPartialInventoryInsertionIsNotRetriedAndUnloadedChunksAreNotForced() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            when(f.inventory.addItem(any(org.bukkit.inventory.ItemStack.class))).thenThrow(new IllegalStateException("uncertain insertion"));
            f.service.breakBlock(new BlockBreakEvent(f.ore, f.player)); assertTrue(f.node().cooling()); assertTrue(f.journal.isEmpty());
            clearInvocations(f.inventory); f.service.poll(); verifyNoInteractions(f.inventory);
            when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); f.time.addAndGet(8000);
            f.service.poll(); assertEquals(Material.BEDROCK, f.ore.getType()); assertTrue(f.node().cooling());
            verify(f.world, never()).loadChunk(anyInt(), anyInt());
        }
    }
    @Test void offhandInteractionsAndPortalCreationCannotChangeBoundNodes() throws Exception {
        try (var f = new HarvestFixture(folder, true)) {
            var offhand = new org.bukkit.event.player.PlayerInteractEvent(f.player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    null, f.ore, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            f.service.interact(offhand);
            assertEquals(org.bukkit.event.Event.Result.DENY, offhand.useInteractedBlock());
            assertEquals(org.bukkit.event.Event.Result.DENY, offhand.useItemInHand());
            var state = mock(org.bukkit.block.BlockState.class); when(state.getBlock()).thenReturn(f.ore);
            var portal = new org.bukkit.event.world.PortalCreateEvent(List.of(state), f.world, null,
                    org.bukkit.event.world.PortalCreateEvent.CreateReason.FIRE);
            f.service.portal(portal); assertTrue(portal.isCancelled());
        }
    }
    @Test void cocoaProtectsItsDirectionalLogSupportInsteadOfUnrelatedSoilBelow() throws Exception {
        try (var f = new HarvestFixture(folder, false)) {
            String source = "minecraft:cocoa[age=2,facing=east]";
            var cocoa = mock(org.bukkit.block.data.type.Cocoa.class);
            when(cocoa.getFacing()).thenReturn(org.bukkit.block.BlockFace.EAST); when(cocoa.getMaterial()).thenReturn(Material.COCOA);
            when(cocoa.getAsString()).thenReturn(source); when(cocoa.clone()).thenReturn(cocoa);
            when(f.server.createBlockData(source)).thenReturn(cocoa);
            var previous = f.node(); f.store.replace(Map.of(previous.key(), new NodeStore.Node(previous.key(), "ore", source, source, "", 0, 0, false)));
            f.data.get(f.ore).set(cocoa);
            var support = new BlockBreakEvent(f.block(1, 64, 0), f.player); f.service.breakBlock(support); assertTrue(support.isCancelled());
            var below = new BlockBreakEvent(f.block(0, 63, 0), f.player); f.service.breakBlock(below); assertFalse(below.isCancelled());
            var unrelated = new BlockBreakEvent(f.block(-1, 64, 0), f.player); f.service.breakBlock(unrelated); assertFalse(unrelated.isCancelled());
        }
    }
}
