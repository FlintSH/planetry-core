package net.Indyuce.mmoitems;
import java.util.List;
import net.Indyuce.mmoitems.api.Type;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
/** Controlled public MMOItems API shape, never a real MMOItems compatibility assertion. */
public final class MMOItems extends JavaPlugin {
    public static MMOItems plugin;
    public boolean available = true;
    public final TypeManager types = new TypeManager();
    @Override public void onEnable() { plugin = this; }
    public TypeManager getTypes() { return types; }
    public ItemStack getItem(Type type, String id) {
        if (!available || type == null || !List.of("ORE", "CROP", "MOB").contains(id)) return null;
        ItemStack item = ItemStack.of(Material.DIAMOND);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Custom " + id));
            meta.lore(List.of(Component.text("Lore " + id), Component.text("<red>literal lore")));
            meta.getPersistentDataContainer().set(new NamespacedKey("mmoitems", "id"), PersistentDataType.STRING, id);
        });
        return item;
    }
    public static final class TypeManager {
        public Type get(String id) { return id.equals("MATERIAL") ? new Type(id) : null; }
    }
}
