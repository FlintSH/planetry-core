package dev.flint.planetry.core.hotbar;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Nexo is optional, but every available shortcut must come from its item builder. */
final class HotbarItems {
    /** The registry's builder identifies a definition; its built stacks may contain random data. */
    record Source(Object definition, ItemStack item) { }

    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final Function<String, Source> lookup;
    private final Set<String> warnings = new HashSet<>();
    private final Map<String, ItemStack> templates = new HashMap<>();
    private final Map<String, Object> definitions = new HashMap<>();

    HotbarItems(JavaPlugin plugin) {
        this(plugin, null);
    }

    HotbarItems(JavaPlugin plugin, Function<String, Source> lookup) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "hotbar-item");
        this.lookup = lookup == null ? this::nexo : lookup;
    }

    void refresh(HotbarSettings settings) {
        for (var item : settings.items()) liveTemplate(item);
    }

    ItemStack template(HotbarSettings.Item item) { return templates.get(item.id()); }

    ItemStack liveTemplate(HotbarSettings.Item item) {
        ItemStack current = build(item);
        if (current == null) {
            templates.remove(item.id());
            definitions.remove(item.id());
        } else templates.put(item.id(), current);
        return current;
    }

    boolean available(int slot, HotbarSettings settings) {
        return settings.items().stream().anyMatch(item -> item.slot() == slot && template(item) != null);
    }

    boolean owned(ItemStack item) {
        if (empty(item) || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(marker);
    }

    String id(ItemStack item) {
        if (!owned(item)) return null;
        try { return item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING); }
        catch (IllegalArgumentException malformedMarker) {
            // A stale/corrupt marker still grants ownership for cleanup, never command activation.
            return null;
        }
    }

    static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private ItemStack build(HotbarSettings.Item settings) {
        try {
            Source source = lookup.apply(settings.nexoId());
            if (source == null || source.definition() == null || empty(source.item())) return null;
            ItemMeta meta = source.item().getItemMeta();
            if (meta != null) {
                // One registered definition can build unique UUIDs or other random metadata.
                // Keep the exact issued template while that definition remains available.
                ItemStack cached = templates.get(settings.id());
                if (cached != null && definitions.get(settings.id()) == source.definition()) return cached;
                ItemStack result = source.item().clone();
                result.setAmount(1);
                meta = result.getItemMeta();
                if (meta == null) return null;
                // The Nexo name, lore, model data and other properties remain authoritative.
                meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, settings.id());
                if (result.setItemMeta(meta)) {
                    definitions.put(settings.id(), source.definition());
                    return result;
                }
            }
            warn(settings.nexoId(), "the Nexo item has unusable metadata");
        } catch (RuntimeException | LinkageError error) {
            warn(settings.nexoId(), "the Nexo item could not be prepared");
        }
        return null;
    }

    private Source nexo(String id) {
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) {
            warn(id, "Nexo is unavailable");
            return null;
        }
        try {
            // Official API: NexoItems.itemFromId(String) -> ItemBuilder.build().
            // https://github.com/Nexo-MC/Nexo-Documentation/blob/master/community-guides/api.md
            // Nexo loads items asynchronously. Polling retries missing IDs after startup/reloads.
            Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems", true, nexo.getClass().getClassLoader());
            Method lookup = api.getMethod("itemFromId", String.class);
            Object builder = lookup.invoke(null, id);
            if (builder != null) {
                Object built = builder.getClass().getMethod("build").invoke(builder);
                if (built instanceof ItemStack stack && !empty(stack) && stack.getItemMeta() != null)
                    return new Source(builder, stack);
            }
            warn(id, "the Nexo item is not loaded or its ID is unknown");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(id, "the Nexo item API could not build the item");
        }
        return null;
    }

    private void warn(String id, String reason) {
        if (warnings.add(id)) plugin.getLogger().warning("Hotbar Nexo item '" + id + "': " + reason
                + "; the shortcut is unavailable and will be retried during restoration.");
    }
}
