package com.nexomc.nexo.api;

import dev.flint.planetrycore.probe.NexoFixture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only shape of the documented Nexo item builder API; never released. */
public final class NexoItems {
    private static final Map<String, Builder> BUILDERS = Map.of(
            "quest_book", new Builder("quest_book"), "novalink", new Builder("novalink"));
    private static final Map<String, ItemStack> BUILT = new HashMap<>();

    public static Builder itemFromId(String id) {
        NexoFixture fixture = JavaPlugin.getPlugin(NexoFixture.class);
        if ("novalink".equals(id) && !fixture.novaLinkAvailable) return null;
        return BUILDERS.get(id);
    }

    public static ItemStack builtItem(String unique) { return BUILT.get(unique).clone(); }

    public static final class Builder {
        private final String id;
        Builder(String id) { this.id = id; }
        public ItemStack build() {
            if ("quest_book".equals(id) && JavaPlugin.getPlugin(NexoFixture.class).invalidQuest)
                return new ItemStack(Material.AIR);
            ItemStack item = new ItemStack("quest_book".equals(id) ? Material.ENCHANTED_BOOK : Material.COMPASS, 3);
            String unique = UUID.randomUUID().toString();
            item.editMeta(meta -> {
                meta.displayName(Component.text("quest_book".equals(id) ? "Nexo Quest Book" : "Nexo NovaLink")
                        .decorate(TextDecoration.ITALIC));
                meta.lore(List.of(Component.text("Nexo fixture metadata")));
                meta.setCustomModelData("quest_book".equals(id) ? 81 : 82);
                meta.getPersistentDataContainer().set(new NamespacedKey("nexo", "id"), PersistentDataType.STRING, id);
                meta.getPersistentDataContainer().set(new NamespacedKey("nexo", "unique"), PersistentDataType.STRING, unique);
            });
            BUILT.put(unique, item.clone());
            return item;
        }
    }
}
