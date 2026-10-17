package dev.flint.planetry.core.harvest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.flint.planetry.core.Language;
import dev.flint.planetry.core.PlanetryCore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

final class HarvestFixture implements AutoCloseable {
    final PlanetryCore plugin = mock(PlanetryCore.class);
    final Server server = mock(Server.class);
    final World world = mock(World.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final AtomicBoolean ready = new AtomicBoolean(true);
    final AtomicLong time = new AtomicLong(1000);
    final AtomicInteger granted = new AtomicInteger();
    final List<String> journal = new ArrayList<>();
    final Map<String, Block> blocks = new HashMap<>();
    final Map<Block, AtomicReference<BlockData>> data = new HashMap<>();
    final NodeStore store;
    final HarvestService service;
    final Block ore;
    final HarvestSettings settings;
    boolean itemsAvailable = true;

    HarvestFixture(Path folder, boolean crop) throws Exception {
        when(plugin.getServer()).thenReturn(server); when(plugin.getName()).thenReturn("PlanetryCore"); when(plugin.namespace()).thenReturn("planetrycore");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("harvest-unit-test"));
        var language = mock(Language.class); when(plugin.language()).thenReturn(language);
        when(language.text(any(), anyString(), anyMap())).thenReturn(Component.empty());
        when(server.isPrimaryThread()).thenReturn(true); when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        var scheduler = mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        when(plugin.getCommand("harvest")).thenReturn(mock(PluginCommand.class));
        UUID worldId = UUID.randomUUID(); when(world.getUID()).thenReturn(worldId); when(server.getWorld(worldId)).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getEntitiesByClass(org.bukkit.entity.Item.class)).thenReturn(List.of());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(server.createBlockData(any(Material.class))).thenAnswer(call -> make(call.getArgument(0), crop && call.getArgument(0) == Material.WHEAT ? 7 : -1));
        when(server.createBlockData(anyString())).thenAnswer(call -> parse(call.getArgument(0)));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL); when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission(HarvestService.PERMISSION)).thenReturn(true);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> { granted.addAndGet(((ItemStack)call.getArgument(0)).getAmount()); return new HashMap<Integer,ItemStack>(); });
        ore = block(0, 64, 0); data.get(ore).set(make(crop ? Material.WHEAT : Material.OBSIDIAN, crop ? 7 : -1));
        var profile = new HarvestSettings.Profile("ore", crop ? Material.WHEAT : Material.OBSIDIAN, Material.BEDROCK, 8000,
                crop, List.of(new HarvestSettings.Drop("MATERIAL", "ORE", 2)), "", 0);
        settings = new HarvestSettings(true, 10, 1, Map.of("ore", profile));
        store = new NodeStore(folder);
        String source = ore.getBlockData().getAsString();
        var key = new NodeStore.Key(worldId, 0, 64, 0);
        store.replace(Map.of(key, new NodeStore.Node(key, "ore", source, source, "", 0, 0, false)));
        service = new HarvestService(plugin, ignored -> ready.get(), (type, id) -> itemsAvailable ? item(1) : null, (p, id) -> journal.add(id), time::get);
        service.start(settings, store);
    }
    ItemStack item(int amount) {
        ItemStack stack = mock(ItemStack.class); Material material = mock(Material.class); when(material.isAir()).thenReturn(false);
        when(stack.getType()).thenReturn(material); AtomicInteger count = new AtomicInteger(amount);
        when(stack.getAmount()).thenAnswer(call -> count.get()); doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.getMaxStackSize()).thenReturn(64); when(stack.clone()).thenAnswer(call -> item(count.get())); return stack;
    }
    Block block(int x, int y, int z) {
        String key = x + "/" + y + "/" + z;
        Block current = blocks.get(key); if (current != null) return current;
        Block block = mock(Block.class); blocks.put(key, block); var ref = new AtomicReference<>(make(Material.STONE, -1)); data.put(block, ref);
        when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z); when(block.getWorld()).thenReturn(world);
        when(block.getBlockData()).thenAnswer(call -> ref.get().clone()); when(block.getType()).thenAnswer(call -> ref.get().getMaterial());
        when(block.getState()).thenReturn(mock(BlockState.class));
        when(block.getLocation()).thenReturn(new org.bukkit.Location(world, x, y, z));
        doAnswer(call -> { ref.set(call.getArgument(0)); return null; }).when(block).setBlockData(any(BlockData.class), eq(false));
        when(block.getRelative(any(BlockFace.class))).thenAnswer(call -> {
            BlockFace face = call.getArgument(0); return block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        });
        return block;
    }
    BlockData parse(String value) {
        if (value.startsWith("minecraft:wheat[age=")) return make(Material.WHEAT, Integer.parseInt(value.substring(20, value.length() - 1)));
        String material = value.substring("minecraft:".length()).split("\\[", 2)[0];
        return make(Material.valueOf(material.toUpperCase(java.util.Locale.ROOT)), -1);
    }
    BlockData make(Material material, int age) {
        BlockData data;
        if (age >= 0) {
            Ageable crop = mock(Ageable.class); var currentAge = new AtomicInteger(age);
            when(crop.getAge()).thenAnswer(call -> currentAge.get()); when(crop.getMaximumAge()).thenReturn(7);
            doAnswer(call -> { currentAge.set(call.getArgument(0)); return null; }).when(crop).setAge(anyInt());
            when(crop.getAsString()).thenAnswer(call -> "minecraft:wheat[age=" + currentAge.get() + "]");
            when(crop.clone()).thenAnswer(call -> make(material, currentAge.get())); data = crop;
        } else {
            data = mock(BlockData.class); when(data.getAsString()).thenReturn("minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT));
            when(data.clone()).thenAnswer(call -> make(material, -1));
        }
        when(data.getMaterial()).thenReturn(material); when(data.createBlockState()).thenReturn(mock(BlockState.class)); return data;
    }
    NodeStore.Node node() { return store.nodes().values().iterator().next(); }
    @Override public void close() { service.close(); }
}
