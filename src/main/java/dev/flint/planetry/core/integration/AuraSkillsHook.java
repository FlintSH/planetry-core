package dev.flint.planetry.core.integration;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** AuraSkills 2.x public API, resolved only when the optional plugin is enabled. */
public final class AuraSkillsHook {
    private AuraSkillsHook() { }
    private record SkillApi(Object api, Object skill, Class<?> apiType, Class<?> skillType, Class<?> userType) { }

    public static boolean available(String skill) {
        try { return resolve(skill) != null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    public static boolean available(Player player, String skill) {
        if (player == null || !player.isOnline()) return false;
        try {
            SkillApi resolved = resolve(skill);
            if (resolved == null) return false;
            Object user = resolved.apiType().getMethod("getUser", UUID.class).invoke(resolved.api(), player.getUniqueId());
            return user != null && Boolean.TRUE.equals(resolved.userType().getMethod("isLoaded").invoke(user));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    public static boolean award(Player player, String skill, double xp) {
        if (player == null || !player.isOnline() || !Double.isFinite(xp) || xp < 0) return false;
        if (xp == 0) return true;
        try {
            SkillApi resolved = resolve(skill);
            if (resolved == null) return false;
            Object user = resolved.apiType().getMethod("getUser", UUID.class).invoke(resolved.api(), player.getUniqueId());
            if (user == null || !Boolean.TRUE.equals(resolved.userType().getMethod("isLoaded").invoke(user))) return false;
            // addSkillXp deliberately retains AuraSkills' configured XP multipliers.
            resolved.userType().getMethod("addSkillXp", resolved.skillType(), double.class).invoke(user, resolved.skill(), xp);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }

    private static SkillApi resolve(String key) throws ReflectiveOperationException {
        if (key == null || !key.matches("[A-Za-z0-9_-]+(?:[/:][A-Za-z0-9_-]+)?")) return null;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("AuraSkills");
        if (plugin == null || !plugin.isEnabled()) return null;
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> apiType = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi", true, loader);
        Class<?> skillType = Class.forName("dev.aurelium.auraskills.api.skill.Skill", true, loader);
        Class<?> userType = Class.forName("dev.aurelium.auraskills.api.user.SkillsUser", true, loader);
        Class<?> idType = Class.forName("dev.aurelium.auraskills.api.registry.NamespacedId", true, loader);
        Class<?> registryType = Class.forName("dev.aurelium.auraskills.api.registry.GlobalRegistry", true, loader);
        Object api = apiType.getMethod("get").invoke(null);
        if (api == null) return null;
        String[] parts = key.toLowerCase(Locale.ROOT).replace(':', '/').split("/", 2);
        Object id = idType.getMethod("of", String.class, String.class).invoke(null,
                parts.length == 1 ? "auraskills" : parts[0], parts.length == 1 ? parts[0] : parts[1]);
        Object registry = apiType.getMethod("getGlobalRegistry").invoke(api);
        Object skill = registryType.getMethod("getSkill", idType).invoke(registry, id);
        return skill == null ? null : new SkillApi(api, skill, apiType, skillType, userType);
    }
}
