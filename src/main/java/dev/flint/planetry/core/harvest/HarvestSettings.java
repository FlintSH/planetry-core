package dev.flint.planetry.core.harvest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Startup-only, strict configuration. A profile never changes ordinary blocks. */
public record HarvestSettings(boolean enabled, int maxNodes, int pollTicks, Map<String, Profile> profiles) {
    public record Drop(String type, String id, int amount) {
        public Drop { token(type, "MMOItems type"); token(id, "MMOItems id"); if (amount < 1 || amount > 2304) throw invalid("drop amount must be 1..2304"); }
    }
    public record Profile(String id, Material source, Material cooldown, long cooldownMillis,
                          boolean growth, List<Drop> drops, String skill, double skillXp) {
        public Profile {
            token(id, "profile id");
            if (source == null || source.name().endsWith("AIR") || source == Material.WATER || source == Material.LAVA)
                throw invalid("source must be a non-air block");
            if (cooldown == null || cooldown == Material.WATER || cooldown == Material.LAVA)
                throw invalid("cooldown-block must be a block material");
            if (source == cooldown) throw invalid("source and cooldown-block must differ");
            if (cooldownMillis < 1000 || cooldownMillis > 604_800_000L) throw invalid("cooldown-seconds must be 1..604800");
            drops = List.copyOf(drops);
            if (drops.isEmpty() || drops.size() > 32) throw invalid("profiles require 1..32 MMOItems drops");
            if (skill == null || !skill.isEmpty() && !skill.matches("[a-z0-9_./:-]{1,128}")) throw invalid("skill must be an AuraSkills registry id");
            if (!Double.isFinite(skillXp) || skillXp < 0 || skillXp > 1_000_000) throw invalid("skill-xp must be 0..1000000");
            if (skillXp > 0 && skill.isEmpty()) throw invalid("positive skill-xp requires a skill");
        }
    }

    public HarvestSettings {
        if (maxNodes < 1 || maxNodes > 10000) throw invalid("max-nodes must be 1..10000");
        if (pollTicks < 1 || pollTicks > 1200) throw invalid("poll-ticks must be 1..1200");
        profiles = Map.copyOf(profiles);
        if (profiles.size() > 256 || enabled && profiles.isEmpty()) throw invalid("enabled harvesting requires 1..256 profiles");
    }

    public static HarvestSettings read(ConfigurationSection config) {
        Object enabled = config.get("enabled", false);
        if (!(enabled instanceof Boolean flag)) throw invalid("enabled must be true or false");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("profiles");
        if (section != null) for (String id : section.getKeys(false)) {
            ConfigurationSection profile = section.getConfigurationSection(id);
            if (profile == null) throw invalid("profile " + id + " must be a section");
            List<Drop> drops = new ArrayList<>();
            if (!(profile.get("drops") instanceof List<?> list)) throw invalid(id + ".drops must be a list");
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> drop)) throw invalid(id + ".drops must contain maps");
                drops.add(new Drop(text(drop.get("type")), text(drop.get("id")), integer(drop.get("amount"), "drop amount")));
            }
            Object grow = profile.get("growth-stages", false);
            if (!(grow instanceof Boolean growth)) throw invalid(id + ".growth-stages must be true or false");
            Object xp = profile.get("skill-xp", 0);
            if (!(xp instanceof Number number)) throw invalid(id + ".skill-xp must be a number");
            long seconds = integer(profile.get("cooldown-seconds"), "cooldown-seconds");
            profiles.put(id, new Profile(id, material(profile.get("source")), material(profile.get("cooldown-block", "BEDROCK")),
                    seconds * 1000, growth, drops, text(profile.get("skill", "")), number.doubleValue()));
        }
        return new HarvestSettings(flag, integer(config.get("max-nodes", 2000), "max-nodes"),
                integer(config.get("poll-ticks", 10), "poll-ticks"), profiles);
    }

    private static Material material(Object value) {
        try { return Material.valueOf(text(value)); }
        catch (IllegalArgumentException error) { throw invalid("unknown material: " + value); }
    }
    private static String text(Object value) { if (!(value instanceof String text)) throw invalid("expected a string"); return text; }
    private static int integer(Object value, String field) {
        if (!(value instanceof Integer number)) throw invalid(field + " must be an integer");
        return number;
    }
    private static void token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_:-]{1,128}")) throw invalid(field + " must be a 1..128 character identifier");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("harvest.yml: " + message); }
}
