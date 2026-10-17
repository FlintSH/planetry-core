package dev.flint.planetry.core.hotbar;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Keeps Nexo optional; never reconstructs a valid Nexo stack from just its material. */
final class HotbarItems {
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final Set<String> warnings = new HashSet<>();
    private final Map<String, ItemStack> templates = new HashMap<>();

    HotbarItems(JavaPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "hotbar-item");
    }

    void refresh(HotbarSettings settings) {
        for (var item : settings.items()) templates.put(item.id(), build(item));
    }

    ItemStack template(HotbarSettings.Item item) { return templates.get(item.id()); }

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
        ItemStack custom = settings.nexoId().isEmpty() ? null : nexo(settings.nexoId());
        ItemStack result = custom == null ? ItemStack.of(settings.material()) : custom.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("hotbar." + settings.id() + ".material must be an item with metadata");
        meta.displayName(TEXT.deserialize(settings.displayName()).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, settings.id());
        if (!result.setItemMeta(meta)) throw new IllegalArgumentException("Could not set hotbar item metadata: " + settings.id());
        return result;
    }

    private ItemStack nexo(String id) {
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
                if (built instanceof ItemStack stack && !empty(stack) && stack.getItemMeta() != null) return stack;
            }
            warn(id, "the Nexo item is not loaded or its ID is unknown");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(id, "the Nexo item API could not build the item");
        }
        return null;
    }

    private void warn(String id, String reason) {
        if (warnings.add(id)) plugin.getLogger().warning("Hotbar Nexo item '" + id + "': " + reason
                + "; using the configured fallback material and retrying during restoration.");
    }
}
