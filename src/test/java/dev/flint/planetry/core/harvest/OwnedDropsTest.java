package dev.flint.planetry.core.harvest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class OwnedDropsTest {
    private static final class Fixture {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final Player owner = mock(Player.class), stranger = mock(Player.class);
        final Item item = mock(Item.class);
        final Map<NamespacedKey, String> tags = new HashMap<>();
        final AtomicBoolean ready = new AtomicBoolean(true);
        final OwnedDrops drops;
        final NamespacedKey key = new NamespacedKey("planetrycore", "harvest-owner");
        Fixture() {
            when(plugin.getName()).thenReturn("PlanetryCore"); when(plugin.namespace()).thenReturn("planetrycore"); when(plugin.getServer()).thenReturn(server);
            when(owner.getUniqueId()).thenReturn(UUID.randomUUID()); when(owner.isOnline()).thenReturn(true);
            when(stranger.getUniqueId()).thenReturn(UUID.randomUUID()); when(server.getPlayer(owner.getUniqueId())).thenReturn(owner);
            when(server.getWorlds()).thenReturn(List.of(world)); when(world.getEntitiesByClass(Item.class)).thenReturn(List.of(item));
            var pdc = mock(PersistentDataContainer.class); when(item.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> tags.containsKey(call.getArgument(0)));
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> tags.get(call.getArgument(0)));
            doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
            drops = new OwnedDrops(plugin, ignored -> ready.get());
        }
        void savedOwner() { tags.put(key, owner.getUniqueId().toString()); }
    }
    @Test void overflowGetsOwnershipAndIsHiddenBeforeSpawnBroadcast() {
        var f = new Fixture(); var spawned = new AtomicBoolean();
        when(f.world.dropItem(any(Location.class), any(ItemStack.class), any())).thenAnswer(call -> {
            Consumer<Item> initialize = call.getArgument(2); initialize.accept(f.item);
            assertEquals(f.owner.getUniqueId().toString(), f.tags.get(f.key));
            verify(f.item).setVisibleByDefault(false); verify(f.item).setCanMobPickup(false); verify(f.item).setGlowing(true);
            spawned.set(true); return f.item;
        });
        f.drops.drop(f.owner, new Location(f.world, 0, 64, 0), mock(ItemStack.class));
        assertTrue(spawned.get()); verify(f.owner).showEntity(f.plugin, f.item);
    }
    @Test void onlyTheReadyOwnerCanPickUpPersistedDropsAndNoHopperOrMobCanTakeThem() {
        var f = new Fixture(); f.savedOwner();
        var ownerPickup = new EntityPickupItemEvent(f.owner, f.item, 0); f.drops.pickup(ownerPickup); assertFalse(ownerPickup.isCancelled());
        var strangerPickup = new EntityPickupItemEvent(f.stranger, f.item, 0); f.drops.pickup(strangerPickup); assertTrue(strangerPickup.isCancelled());
        var mob = new EntityPickupItemEvent(mock(Zombie.class), f.item, 0); f.drops.pickup(mob); assertTrue(mob.isCancelled());
        var hopper = new InventoryPickupItemEvent(mock(Inventory.class), f.item); f.drops.hopper(hopper); assertTrue(hopper.isCancelled());
        f.ready.set(false); var unready = new EntityPickupItemEvent(f.owner, f.item, 0); f.drops.pickup(unready); assertTrue(unready.isCancelled());
    }
    @Test void laterJoinsAndChunkLoadsShowOnlyTheSavedOwner() {
        var f = new Fixture(); f.savedOwner(); f.drops.start(); verify(f.owner).showEntity(f.plugin, f.item);
        clearInvocations(f.owner); f.drops.join(new PlayerJoinEvent(f.owner, net.kyori.adventure.text.Component.empty())); verify(f.owner).showEntity(f.plugin, f.item);
        var strangerTrack = new PlayerTrackEntityEvent(f.stranger, f.item); f.drops.track(strangerTrack); assertTrue(strangerTrack.isCancelled());
        var ownerTrack = new PlayerTrackEntityEvent(f.owner, f.item); f.drops.track(ownerTrack); assertFalse(ownerTrack.isCancelled());
        clearInvocations(f.item); f.drops.load(new EntitiesLoadEvent(mock(org.bukkit.Chunk.class), List.of(f.item)));
        verify(f.item).setOwner(f.owner.getUniqueId()); verify(f.item).setVisibleByDefault(false);
    }
    @Test void malformedOwnershipFailsClosedAndOwnedDropsNeverMerge() {
        var f = new Fixture(); f.tags.put(f.key, "malformed"); f.drops.restore(f.item); verify(f.item).setCanPlayerPickup(false);
        var pickup = new EntityPickupItemEvent(f.owner, f.item, 0); f.drops.pickup(pickup); assertTrue(pickup.isCancelled());
        var ordinary = mock(Item.class); var pdc = mock(PersistentDataContainer.class); when(ordinary.getPersistentDataContainer()).thenReturn(pdc);
        var merge = new ItemMergeEvent(ordinary, f.item); f.drops.merge(merge); assertTrue(merge.isCancelled());
        f.ready.set(false); assertThrows(IllegalStateException.class, () -> f.drops.drop(f.owner, new Location(f.world, 0, 64, 0), mock(ItemStack.class)));
        verify(f.world, never()).dropItem(any(), any(), any());
    }
    @Test void unavailableReadinessAndOfflineOwnersFailClosedBeforePickupOrDrop() {
        var f = new Fixture(); f.savedOwner();
        var broken = new OwnedDrops(f.plugin, ignored -> { throw new IllegalStateException("integration unavailable"); });
        var pickup = new EntityPickupItemEvent(f.owner, f.item, 0); broken.pickup(pickup); assertTrue(pickup.isCancelled());
        assertThrows(IllegalStateException.class, () -> broken.drop(f.owner, new Location(f.world, 0, 64, 0), mock(ItemStack.class)));
        when(f.owner.isOnline()).thenReturn(false);
        var offline = new EntityPickupItemEvent(f.owner, f.item, 0); f.drops.pickup(offline); assertTrue(offline.isCancelled());
        assertThrows(IllegalStateException.class, () -> f.drops.drop(f.owner, new Location(f.world, 0, 64, 0), mock(ItemStack.class)));
        verify(f.world, never()).dropItem(any(), any(), any());
    }
}
