package com.nexomc.nexo.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Test-only shape of the documented Nexo item builder API; never released. */
public final class NexoItems {
    public static Builder itemFromId(String id) {
        return "probe_book".equals(id) ? new Builder() : null;
    }
    public static final class Builder {
        public ItemStack build() {
            ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
            item.editMeta(meta -> {
                meta.lore(List.of(Component.text("Nexo fixture metadata")));
                meta.getPersistentDataContainer().set(new NamespacedKey("nexo", "id"), PersistentDataType.STRING, "probe_book");
            });
            return item;
        }
    }
}
