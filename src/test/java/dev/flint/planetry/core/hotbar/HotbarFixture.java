package dev.flint.planetry.core.hotbar;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.mockito.MockedStatic;

/**
 * Narrow Bukkit server fixture: metadata, storage and scheduler have real mutable state.
 * Paper's Material item registry and ItemStack.of need a running server; those two factory
 * boundaries are mocked here, rather than trying to instantiate server-backed metadata.
 * Packaged Paper probes exercise the real registry, ItemFactory and event manager.
 */
final class HotbarFixture implements AutoCloseable {
    final JavaPlugin plugin = mock(JavaPlugin.class);
    final Server server = mock(Server.class);
    final PluginManager plugins = mock(PluginManager.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    final CommandMap commands = mock(CommandMap.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final Inventory top = mock(Inventory.class);
    final InventoryView view = mock(InventoryView.class);
    final ItemStack[] storage = new ItemStack[41];
    final Map<Integer, ItemStack> container = new HashMap<>();
    final AtomicReference<ItemStack> cursor = new AtomicReference<>();
    final AtomicBoolean ready = new AtomicBoolean(true);
    final AtomicBoolean nexoAvailable = new AtomicBoolean(true);
    final AtomicBoolean randomNexo = new AtomicBoolean();
    final Map<String, ItemStack> nexoItems = new HashMap<>();
    final AtomicLong time = new AtomicLong(1_000_000_000L);
    final AtomicInteger ticks = new AtomicInteger(100);
    final NamespacedKey marker = new NamespacedKey("planetrycore", "hotbar-item");
    final List<Pending> pending = new ArrayList<>();
    final Map<ItemStack, StackData> stacks = new IdentityHashMap<>();
    final Map<String, Material> itemMaterials = new HashMap<>();
    final YamlConfiguration config = new YamlConfiguration();
    private MockedStatic<Material> materialFactory;
    private MockedStatic<ItemStack> stackFactory;
    private static boolean registriesBootstrapped;
    int held = 7;
    Runnable polling;
    BukkitTask pollTask;
    HotbarService service;

    record Pending(Runnable action, BukkitTask task, AtomicBoolean cancelled) { }
    static final class StackData {
        final Material material;
        int amount;
        Component name;
        final Map<NamespacedKey, Object> data = new HashMap<>();
        StackData(Material material, int amount) { this.material = material; this.amount = amount; }
    }

    HotbarFixture() { this(false); }

    HotbarFixture(boolean failAfterFactories) {
        try {
        bootstrapMenus();
        materialFactory = mockStatic(Material.class, CALLS_REAL_METHODS);
        stackFactory = mockStatic(ItemStack.class);
        if (failAfterFactories) throw new IllegalStateException("Intentional fixture initialization failure");
        for (String name : List.of("BOOK", "COMPASS", "STONE", "PAPER", "DIAMOND")) material(name, true);
        material("WATER", false);
        material("LAVA", false);
        Material air = material("AIR", false);
        when(air.isAir()).thenReturn(true);
        materialFactory.when(() -> Material.matchMaterial(anyString())).thenAnswer(call -> itemMaterials.get(call.getArgument(0)));
        stackFactory.when(() -> ItemStack.of(any(Material.class))).thenAnswer(call -> {
            Material type = call.getArgument(0);
            // Keep ordinary item enum identities; air requires this fixture's
            // explicit isAir stub because Paper delegates it to BlockType.
            return stack(Material.valueOf(type.name()), 1);
        });
        when(plugin.namespace()).thenReturn("planetrycore");
        when(plugin.getName()).thenReturn("PlanetryCore");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("hotbar-unit-fixture"));
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getConsoleSender()).thenReturn(console);
        when(server.getCommandMap()).thenReturn(commands);
        when(server.getCurrentTick()).thenAnswer(call -> ticks.get());
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(commands.dispatch(any(), anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.fromString("b420d6e7-8f67-4e37-a589-3db46ac64c50"));
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player).setItemOnCursor(any());
        when(inventory.getSize()).thenReturn(storage.length);
        when(inventory.getHolder()).thenReturn(player);
        when(inventory.getItem(anyInt())).thenAnswer(call -> storage[(int) call.getArgument(0)]);
        doAnswer(call -> { storage[(int) call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
        when(inventory.getHeldItemSlot()).thenAnswer(call -> held);
        when(inventory.getItemInMainHand()).thenAnswer(call -> storage[held]);
        when(inventory.getItemInOffHand()).thenAnswer(call -> storage[40]);
        when(inventory.getItem(any(EquipmentSlot.class))).thenAnswer(call -> call.getArgument(0) == EquipmentSlot.HAND ? storage[held] : storage[40]);
        when(top.getType()).thenReturn(InventoryType.CHEST);
        when(top.getSize()).thenReturn(27);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(inventory);
        when(view.getType()).thenReturn(InventoryType.CHEST);
        when(view.getInventory(anyInt())).thenAnswer(call -> (int) call.getArgument(0) < 0 ? null : (int) call.getArgument(0) < 27 ? top : inventory);
        when(view.convertSlot(anyInt())).thenAnswer(call -> convert(call.getArgument(0)));
        when(view.getItem(anyInt())).thenAnswer(call -> {
            int raw = call.getArgument(0);
            return raw < 0 ? null : raw < 27 ? container.get(raw) : storage[convert(raw)];
        });
        when(view.getCursor()).thenAnswer(call -> cursor.get());
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            BukkitTask task = mock(BukkitTask.class);
            AtomicBoolean cancelled = new AtomicBoolean();
            doAnswer(ignored -> { cancelled.set(true); return null; }).when(task).cancel();
            pending.add(new Pending(call.getArgument(1), task, cancelled));
            return task;
        });
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            polling = call.getArgument(1);
            pollTask = mock(BukkitTask.class);
            return pollTask;
        });
        nexoItems.put("quest_book", nexo("quest_book", Material.BOOK, "Nexo Quest Book"));
        nexoItems.put("novalink", nexo("novalink", Material.COMPASS, "Nexo NovaLink"));
        } catch (RuntimeException | Error failure) {
            closeFactories();
            throw failure;
        }
    }

    /** InventoryType now initializes Paper's MenuType registry, even for event-only tests. */
    private static synchronized void bootstrapMenus() {
        if (registriesBootstrapped) return;
        RegistryAccess access = new RegistryAccess() {
            @Override public <T extends Keyed> Registry<T> getRegistry(Class<T> type) { return new MenuRegistry<>(false); }
            @Override public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) { return new MenuRegistry<>(key.equals(RegistryKey.MENU)); }
        };
        try (var registryAccess = mockStatic(RegistryAccess.class)) {
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            InventoryType.CHEST.name();
        }
        registriesBootstrapped = true;
    }

    private static final class MenuRegistry<T extends Keyed> implements Registry<T> {
        private final boolean menus;
        MenuRegistry(boolean menus) { this.menus = menus; }

        @Override @SuppressWarnings("unchecked") public T get(NamespacedKey key) {
            if (!menus) return null;
            return (T) Proxy.newProxyInstance(HotbarFixture.class.getClassLoader(), new Class<?>[]{MenuType.Typed.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getKey", "key" -> key;
                case "typed" -> proxy;
                case "getInventoryViewClass" -> InventoryView.class;
                case "toString" -> "FixtureMenu[" + key + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            });
        }
        @Override public NamespacedKey getKey(T value) { return value.getKey(); }
        @Override public boolean hasTag(TagKey<T> key) { return false; }
        @Override public Tag<T> getTag(TagKey<T> key) { throw new UnsupportedOperationException(); }
        @Override public Collection<Tag<T>> getTags() { return List.of(); }
        @Override public Stream<T> stream() { return Stream.empty(); }
        @Override public Stream<NamespacedKey> keyStream() { return Stream.empty(); }
        @Override public int size() { return 0; }
        @Override public Iterator<T> iterator() { return List.<T>of().iterator(); }
    }

    Material material(String name, boolean isItem) {
        Material result = mock(Material.class);
        when(result.name()).thenReturn(name);
        when(result.isItem()).thenReturn(isItem);
        itemMaterials.put(name, result);
        return result;
    }

    static int raw(int slot) { return slot < 9 ? 54 + slot : 27 + slot - 9; }
    static int convert(int raw) { return raw < 27 ? raw : raw < 54 ? raw - 27 + 9 : raw - 54; }

    HotbarService start() {
        service = new HotbarService(plugin, HotbarSettings.read(config), ignored -> ready.get(), time::get,
                new HotbarItems(plugin, this::nexoSource));
        service.start();
        flush();
        return service;
    }

    void flush() {
        List<Pending> batch = List.copyOf(pending);
        pending.clear();
        for (Pending task : batch) if (!task.cancelled().get()) task.action().run();
    }

    ItemStack stack(Material material, int amount) {
        return stack(new StackData(material, amount));
    }

    ItemStack owned(String id) {
        ItemStack result = stack(Material.BOOK, 1);
        stacks.get(result).data.put(marker, id);
        return result;
    }

    private ItemStack nexo(String id, Material type, String name) {
        ItemStack item = stack(type, 1);
        stacks.get(item).name = Component.text(name);
        stacks.get(item).data.put(new NamespacedKey("nexo", "id"), id);
        return item;
    }

    private HotbarItems.Source nexoSource(String id) {
        ItemStack definition = nexoItems.get(id);
        if (!nexoAvailable.get() || definition == null) return null;
        ItemStack built = definition;
        if (randomNexo.get()) {
            built = definition.clone();
            stacks.get(built).data.put(new NamespacedKey("nexo", "unique"), UUID.randomUUID().toString());
        }
        return new HotbarItems.Source(definition, built);
    }

    private ItemStack stack(StackData state) {
        ItemStack result = mock(ItemStack.class);
        stacks.put(result, state);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(result.getType()).thenReturn(state.material);
        when(result.getAmount()).thenAnswer(call -> state.amount);
        doAnswer(call -> { state.amount = call.getArgument(0); return null; }).when(result).setAmount(anyInt());
        when(result.getItemMeta()).thenReturn(meta);
        when(result.hasItemMeta()).thenAnswer(call -> state.name != null || !state.data.isEmpty());
        when(result.setItemMeta(meta)).thenReturn(true);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(meta.displayName()).thenAnswer(call -> state.name);
        doAnswer(call -> { state.name = call.getArgument(0); return null; }).when(meta).displayName(any(Component.class));
        when(data.has(any(NamespacedKey.class))).thenAnswer(call -> state.data.containsKey(call.getArgument(0)));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> {
            Object value = state.data.get(call.getArgument(0));
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("wrong PDC primitive type");
            return value;
        });
        doAnswer(call -> { state.data.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        when(result.clone()).thenAnswer(call -> {
            StackData copy = new StackData(state.material, state.amount);
            copy.name = state.name;
            copy.data.putAll(state.data);
            return stack(copy);
        });
        when(result.isSimilar(any())).thenAnswer(call -> {
            StackData other = stacks.get(call.getArgument(0));
            return other != null && state.material == other.material && java.util.Objects.equals(state.name, other.name) && state.data.equals(other.data);
        });
        return result;
    }

    String id(int slot) { return (String) stacks.get(storage[slot]).data.get(marker); }

    @Override public void close() {
        try { if (service != null) service.close(); }
        finally { closeFactories(); }
    }

    private void closeFactories() {
        try { if (stackFactory != null) stackFactory.close(); }
        finally { if (materialFactory != null) materialFactory.close(); }
    }
}
