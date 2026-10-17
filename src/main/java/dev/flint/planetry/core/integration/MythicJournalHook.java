package dev.flint.planetry.core.integration;

import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers the public MythicMobs 5.x death event without a hard runtime dependency. */
public final class MythicJournalHook {
    private MythicJournalHook() { }

    public static void attach(JavaPlugin core, BiConsumer<Player, String> unlock) {
        Plugin mythic = core.getServer().getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled()) return;
        try {
            ClassLoader loader = mythic.getClass().getClassLoader();
            Class<? extends Event> eventType = Class.forName("io.lumine.mythic.bukkit.events.MythicMobDeathEvent", true, loader).asSubclass(Event.class);
            Class<?> mobType = Class.forName("io.lumine.mythic.api.mobs.MythicMob", true, loader);
            var killer = eventType.getMethod("getKiller");
            var mob = eventType.getMethod("getMobType");
            var name = mobType.getMethod("getInternalName");
            core.getServer().getPluginManager().registerEvent(eventType, new Listener() { }, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!mythic.isEnabled() || !core.isEnabled() || event.isAsynchronous()) return;
                        try {
                            if (killer.invoke(event) instanceof Player player && player.isOnline()) {
                                Object id = name.invoke(mob.invoke(event));
                                if (id instanceof String text) unlock.accept(player, text);
                            }
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                            core.getLogger().warning("MythicMobs Journal unlock failed; no completion was confirmed.");
                        }
                    }, core, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            core.getLogger().warning("MythicMobs Journal hook unavailable. Check the supported MythicMobs 5.x API.");
        }
    }
}
