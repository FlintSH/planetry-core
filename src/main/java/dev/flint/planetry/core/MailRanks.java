package dev.flint.planetry.core;

import org.bukkit.entity.Player;

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
    private static boolean usable(String value) { return value != null && !org.bukkit.ChatColor.stripColor(value.replace('&', '§')).isBlank(); }
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
