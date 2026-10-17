package dev.flint.planetry.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

final class PlanetryExpansion extends PlaceholderExpansion {
    private final PlanetryCore plugin;
    PlanetryExpansion(PlanetryCore plugin) { this.plugin = plugin; }
    static PlanetryExpansion attach(PlanetryCore plugin) {
        if (PlaceholderAPI.isRegistered("planetry"))
            throw new IllegalStateException("The planetry placeholder identifier is already registered");
        var candidate = new PlanetryExpansion(plugin);
        if (!candidate.register()) throw new IllegalStateException("Could not register planetry placeholders");
        return candidate;
    }
    void detach() {
        if (PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion("planetry") == this) unregister();
    }
    @Override public @NotNull String getIdentifier() { return "planetry"; }
    @Override public @NotNull String getAuthor() { return "FlintSH"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        var progress = plugin.cachedProgress(player.getUniqueId());
        if (progress == null) return "";
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "level" -> Integer.toString(progress.level());
            case "gamexp" -> Integer.toString(progress.experience());
            default -> null;
        };
    }
}
