package dev.flint.planetry.core;

import org.bukkit.entity.Player;
import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Optional APIs are resolved only after their plugins have been checked. */
final class MailRanks {
    private MailRanks() { }
    static String rank(PlanetryCore plugin, Player player) {
        String placeholder = plugin.getConfig().getString("mail.rank-placeholder", "%luckperms_prefix%");
        if (!placeholder.isBlank() && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                String rank = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
                if (usable(rank) && !rank.contains("%")) return normalize(rank);
            } catch (RuntimeException | LinkageError ignored) { }
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            try {
                String rank = VaultRank.rank(plugin, player);
                if (usable(rank)) return normalize(rank);
            } catch (RuntimeException | LinkageError ignored) { }
        }
        return normalize(plugin.getConfig().getString("mail.fallback-rank", "&cStaff"));
    }
    private static boolean usable(String value) {
        return value != null && !PlainTextComponentSerializer.plainText().serialize(Language.format(value)).isBlank();
    }

    /** Remove only the rendered trailing whitespace, even before closing color tags/resets. */
    static Component trimTrailing(Component component) {
        var children = new ArrayList<>(component.children());
        boolean trailing = true;
        for (int index = children.size() - 1; index >= 0 && trailing; index--) {
            Component child = trimTrailing(children.get(index));
            children.set(index, child);
            trailing = PlainTextComponentSerializer.plainText().serialize(child).isEmpty();
        }
        Component result = component.children(children);
        if (trailing && result instanceof TextComponent text) result = text.content(text.content().stripTrailing());
        return result;
    }
    /** Sender records have a bounded single-line field; ornate formatting can expand on serialization. */
    static String snapshot(Component identity) {
        String formatted = MiniMessage.miniMessage().serialize(identity);
        if (formatted.length() <= 1024 && formatted.codePoints().noneMatch(MailRanks::lineBreak)) return formatted;
        String plain = PlainTextComponentSerializer.plainText().serialize(identity)
                .replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").strip();
        if (plain.isBlank()) plain = "PLANETRY";
        int length = Math.min(plain.codePointCount(0, plain.length()), 256);
        return MiniMessage.miniMessage().serialize(Component.text(plain.substring(0, plain.offsetByCodePoints(0, length))));
    }

    private static boolean lineBreak(int code) { return Character.isISOControl(code) || code == 0x2028 || code == 0x2029; }

    private static String normalize(String value) {
        String clean = value.replace('§', '&').replaceAll("[\\p{Cntrl}]", "");
        return clean.substring(0, Math.min(clean.length(), 512));
    }
    private static final class VaultRank {
        static String rank(PlanetryCore plugin, Player player) {
            var registration = plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
            if (registration == null) return null;
            var chat = registration.getProvider();
            String prefix = chat.getPlayerPrefix(player);
            return usable(prefix) ? prefix : chat.getPrimaryGroup(player);
        }
    }
}
