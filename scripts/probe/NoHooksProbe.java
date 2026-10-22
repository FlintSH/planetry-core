package dev.flint.planetrycore.probe;

import dev.flint.planetry.core.PlanetryCore;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** A separate probe class prevents optional API references from masking a missing-hook startup bug. */
public final class NoHooksProbe extends JavaPlugin {
    private PlanetryCore core;
    private final UUID id = UUID.randomUUID();
    private Player player;
    private int shownLevel = 17;
    private float shownProgress = .5f;
    private boolean finished;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                for (String hook : new String[]{"PlaceholderAPI", "Vault", "NetworkInventorySync"})
                    check(Bukkit.getPluginManager().getPlugin(hook) == null, "Optional plugin unexpectedly installed: " + hook);
                Plugin loaded = Bukkit.getPluginManager().getPlugin("PlanetryCore");
                check(loaded instanceof PlanetryCore && loaded.isEnabled(), "Core did not enable without optional hooks");
                core = (PlanetryCore) loaded;
                player = fakePlayer();
                var join = new PlayerJoinEvent(player,
                        Component.translatable("multiplayer.player.joined", Component.text("NoHooksProbe")));
                Bukkit.getPluginManager().callEvent(join);
                check(join.joinMessage() == null, "Core allowed the vanilla backend join announcement without optional hooks");
                waitFor(() -> core.cachedProgress(id) != null, () -> {
                    core.getCommand("gamexp").execute(Bukkit.getConsoleSender(), "gamexp", new String[]{"give", id.toString(), "250"});
                    waitFor(() -> core.cachedProgress(id) != null && core.cachedProgress(id).level() == 2
                            && core.cachedProgress(id).experience() == 50, () -> {
                        core.refresh(player);
                        check(shownLevel == 2 && shownProgress == .5f, "Core XP display failed without optional hooks");
                        core.getCommand("level").execute(player, "level", new String[0]);
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            try { finish(); } catch (Throwable error) { fail(error); }
                        }, 105);
                    });
                });
            } catch (Throwable error) { fail(error); }
        });
    }

    private void finish() {
        check(core.isEnabled() && core.cachedProgress(id).level() == 2 && core.cachedProgress(id).experience() == 50,
                "Missing economy changed or disabled game progression");
        check(player.getLevel() == 17 && player.getExp() == .5f && player.getTotalExperience() == 321,
                "Core changed real vanilla XP without optional hooks");
        Bukkit.getPluginManager().disablePlugin(core);
        check(shownLevel == 17 && shownProgress == .5f, "Core did not restore vanilla display without optional hooks");
        finished = true;
        getLogger().info("CORE_NO_HOOKS_PROBE_OK: real Paper startup without Vault, PlaceholderAPI or Inventory Sync; commands, game progress, missing-economy queue path and preserved vanilla XP");
        Bukkit.shutdown();
    }

    private Player fakePlayer() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "NoHooksProbe";
                    case "isOnline", "isValid", "hasPlayedBefore" -> true;
                    case "isDead", "hasPermission", "isPermissionSet", "isOp" -> false;
                    case "getServer" -> Bukkit.getServer();
                    case "getLevel" -> 17;
                    case "getExp" -> .5f;
                    case "getTotalExperience" -> 321;
                    case "setLevel", "setExp", "setTotalExperience", "giveExp", "giveExpLevels", "setExperienceLevelAndProgress" ->
                            throw new IllegalStateException("Core called vanilla XP mutator " + method.getName());
                    case "sendExperienceChange" -> {
                        check(Bukkit.isPrimaryThread(), "XP packet sent off main thread");
                        shownProgress = (float) args[0];
                        if (args.length > 1) shownLevel = (int) args[1];
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "NoHooksProbe[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private void waitFor(BooleanSupplier test, Runnable next) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                try {
                    if (finished) { cancel(); return; }
                    if (test.getAsBoolean()) { cancel(); next.run(); }
                    else if (++ticks > 400) { cancel(); fail(new IllegalStateException("No-hooks probe step timed out")); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }

    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("CORE_PROBE_FAILED: " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
