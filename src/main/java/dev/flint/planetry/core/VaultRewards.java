package dev.flint.planetry.core;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

/** Loaded only when Vault is enabled, so the plugin also runs without its API. */
final class VaultRewards {
    static boolean available(Server server) { return server.getServicesManager().getRegistration(Economy.class) != null; }
    static void deposit(Server server, OfflinePlayer player, double amount) {
        var registration = server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) throw new IllegalStateException("Economy provider unavailable");
        var result = registration.getProvider().depositPlayer(player, amount);
        if (result == null || !result.transactionSuccess()) throw new IllegalStateException("Economy provider refused the reward");
    }
}
