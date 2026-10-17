package dev.flint.planetry.core.integration;

import java.util.Locale;
import java.util.function.BiFunction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional MMOItems API. No ordinary-material substitute can award the wrong custom item. */
public final class MmoItemsHook implements BiFunction<String, String, ItemStack> {
    private final JavaPlugin plugin;
    public MmoItemsHook(JavaPlugin plugin) { this.plugin = plugin; }

    @Override public ItemStack apply(String type, String id) {
        return build(plugin.getServer().getPluginManager().getPlugin("MMOItems"), type, id);
    }

    static ItemStack build(Plugin installed, String type, String id) {
        if (installed == null || !installed.isEnabled() || type == null || id == null) return null;
        if (!type.matches("[A-Za-z0-9_-]{1,64}") || !id.matches("[A-Za-z0-9_-]{1,128}")) return null;
        try {
            // https://docs.phoenixdevt.fr/mmoitems/api/main.html
            Class<?> api = Class.forName("net.Indyuce.mmoitems.MMOItems", true, installed.getClass().getClassLoader());
            Object instance = api.getField("plugin").get(null);
            if (instance != installed) return null;
            Object types = api.getMethod("getTypes").invoke(instance);
            Object itemType = types.getClass().getMethod("get", String.class).invoke(types, type.toUpperCase(Locale.ROOT));
            if (itemType == null) return null;
            Class<?> typeApi = Class.forName("net.Indyuce.mmoitems.api.Type", true, installed.getClass().getClassLoader());
            Object result = api.getMethod("getItem", typeApi, String.class).invoke(instance, itemType, id.toUpperCase(Locale.ROOT));
            if (!(result instanceof ItemStack item) || item.getType().isAir() || item.getAmount() < 1) return null;
            return item.clone();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return null; }
    }
}
