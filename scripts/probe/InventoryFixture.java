package dev.flint.planetrycore.probe;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only Inventory Sync readiness contract; never part of the release artifact. */
public final class InventoryFixture extends JavaPlugin {
    public boolean ready = true;

    public boolean isReady(UUID player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Readiness called off main thread");
        return ready;
    }
}
