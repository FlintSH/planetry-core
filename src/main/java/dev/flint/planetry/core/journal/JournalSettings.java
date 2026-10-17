package dev.flint.planetry.core.journal;

import dev.flint.planetry.core.Language;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable journal definitions. IDs are global so commands and placeholders stay unambiguous. */
public record JournalSettings(Map<String, Category> categories, Map<String, Entry> entries,
                              Map<String, String> messages, List<String> unlockMessage) {
    public static final List<String> CATEGORY_IDS = List.of("farming", "foraging", "fighting", "mining", "fishing", "rockets", "achievements");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern INTEGRATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}");
    public record Category(String id, String name, String material, List<Entry> entries) {
        public Category { entries = List.copyOf(entries); }
    }
    public record Entry(String id, String category, String type, String item, Set<String> harvestNodes, Set<String> mythicMobs) {
        public Entry { harvestNodes = Set.copyOf(harvestNodes); mythicMobs = Set.copyOf(mythicMobs); }
    }
    public JournalSettings {
        categories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        entries = Map.copyOf(entries); messages = Map.copyOf(messages); unlockMessage = List.copyOf(unlockMessage);
    }

    public static JournalSettings load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        var path = plugin.getDataFolder().toPath().resolve("journal.yml");
        if (!Files.exists(path)) plugin.saveResource("journal.yml", false);
        var config = new YamlConfiguration();
        config.load(path.toFile());
        return read(config);
    }

    public static JournalSettings read(ConfigurationSection config) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, Category> categories = new LinkedHashMap<>();
        var section = config.getConfigurationSection("categories");
        if (section == null) throw invalid("categories", "must contain the seven journal categories");
        for (String category : section.getKeys(false))
            if (!CATEGORY_IDS.contains(category)) throw invalid("categories." + category, "is not a supported category");
        String[] materials = {"WHEAT", "OAK_SAPLING", "IRON_SWORD", "IRON_PICKAXE", "FISHING_ROD", "FIREWORK_ROCKET", "NETHER_STAR"};
        for (int index = 0; index < CATEGORY_IDS.size(); index++) {
            String id = CATEGORY_IDS.get(index), path = "categories." + id;
            var source = config.getConfigurationSection(path);
            if (source == null) throw invalid(path, "must be a section");
            String name = text(source, "name", id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1));
            String material = text(source, "material", materials[index]);
            if (!material.matches("[A-Z0-9_]{1,64}")) throw invalid(path + ".material", "must be a Bukkit material name");
            List<Entry> categoryEntries = new ArrayList<>();
            if (source.contains("entries") && !source.isConfigurationSection("entries"))
                throw invalid(path + ".entries", "must be a map of entry IDs");
            var definitions = source.getConfigurationSection("entries");
            if (definitions != null) for (String entryId : definitions.getKeys(false)) {
                requireId(entryId);
                var entry = definitions.getConfigurationSection(entryId);
                if (entry == null) throw invalid(path + ".entries." + entryId, "must be a section");
                Entry value = new Entry(entryId, id, integration(text(entry, "mmoitems-type", null), entryId + ".mmoitems-type"),
                        integration(text(entry, "mmoitems-id", null), entryId + ".mmoitems-id"),
                        integrationList(entry, "harvest-nodes"), integrationList(entry, "mythic-mobs"));
                if (entries.putIfAbsent(entryId, value) != null) throw invalid(entryId, "is duplicated; journal entry IDs must be globally unique");
                categoryEntries.add(value);
            }
            if (categoryEntries.size() > 10000) throw invalid(path + ".entries", "must contain at most 10000 entries");
            categories.put(id, new Category(id, name, material, categoryEntries));
        }
        Map<String, String> messages = new LinkedHashMap<>(defaults());
        if (config.contains("messages") && !config.isConfigurationSection("messages"))
            throw invalid("messages", "must be a map of message keys");
        var supplied = config.getConfigurationSection("messages");
        if (supplied != null) for (String key : supplied.getKeys(false)) {
            if (key.equals("unlock")) continue;
            String value = text(supplied, key, null);
            Language.format(value);
            messages.put(key, value);
        }
        List<String> unlock = List.of("", "<green>Journal unlocked: {item} {open}", "<gray>{category}: {unlocked}/{max}", "");
        if (config.contains("messages.unlock")) {
            Object value = config.get("messages.unlock");
            if (!(value instanceof List<?> lines) || lines.size() > 32) throw invalid("messages.unlock", "must be a list of at most 32 text lines");
            var parsed = new ArrayList<String>();
            for (Object line : lines) {
                if (!(line instanceof String string) || string.length() > 4096) throw invalid("messages.unlock", "must contain text lines of at most 4096 characters");
                Language.format(string); parsed.add(string);
            }
            unlock = parsed;
        }
        return new JournalSettings(categories, entries, messages, unlock);
    }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
    public static void requireId(String id) { if (!validId(id)) throw invalid("journal entry ID", "must match [a-z0-9][a-z0-9_-]{0,63}"); }
    public List<Entry> harvest(String node) { return entries.values().stream().filter(entry -> entry.harvestNodes().contains(node)).toList(); }
    public List<Entry> kills(String mob) { return entries.values().stream().filter(entry -> entry.mythicMobs().contains(mob)).toList(); }
    public static int pages(int count) { return Math.max(1, (count + 44) / 45); }
    public static int page(int requested, int count) { return Math.max(0, Math.min(requested, pages(count) - 1)); }

    private static Set<String> integrationList(ConfigurationSection source, String key) {
        Object raw = source.get(key, List.of());
        if (!(raw instanceof List<?> values)) throw invalid(key, "must be a list of integration IDs");
        if (values.size() > 1024) throw invalid(key, "must contain at most 1024 IDs");
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String string)) throw invalid(key, "must contain text IDs");
            result.add(integration(string, key));
        }
        return result;
    }
    private static String integration(String value, String path) {
        if (!INTEGRATION_ID.matcher(value).matches()) throw invalid(path, "must be an integration ID without spaces");
        return value;
    }
    private static String text(ConfigurationSection source, String key, String fallback) {
        Object raw = source.get(key, fallback);
        if (!(raw instanceof String value) || value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(key, "must be a text string of at most 4096 characters, without control characters");
        return value;
    }
    private static IllegalArgumentException invalid(String path, String message) { return new IllegalArgumentException("journal.yml: " + path + " " + message); }
    private static Map<String, String> defaults() {
        return Map.ofEntries(
                Map.entry("title", "<dark_gray>Journal"), Map.entry("category-title", "<dark_gray>{category} <gray>{page}/{pages}"),
                Map.entry("category-lore", "<gray>Collected: <white>{unlocked}/{max}"),
                Map.entry("locked-name", "<gray>Unknown: <white>{id}"), Map.entry("locked-lore", "<gray>Discover this item to unlock its journal entry."),
                Map.entry("found-lore", "<green>Collected"), Map.entry("missing-name", "<red>Unavailable item: {id}"),
                Map.entry("missing-lore", "<gray>MMOItems definition unavailable: {type}/{item}"),
                Map.entry("back", "<yellow>Categories"), Map.entry("previous", "<yellow>Previous page"), Map.entry("next", "<yellow>Next page"),
                Map.entry("empty", "<gray>No entries configured in this category."), Map.entry("open", "<green>[OPEN]"),
                Map.entry("open-hover", "<gray>Open the {category} journal."), Map.entry("loading", "<gray>Your journal is loading. Try again shortly."),
                Map.entry("denied", "<red>You do not have permission."), Map.entry("player-only", "<red>Use /journal open <player> <category> from console."),
                Map.entry("usage", "<red>Usage: /journal [category] or /journal open <player> <category>"),
                Map.entry("admin-usage", "<red>Usage: /journaladmin <known-name|uuid> <add|remove> <entryId>"),
                Map.entry("unknown-entry", "<red>Unknown journal entry: {id}"), Map.entry("unknown-category", "<red>Unknown journal category: {category}"),
                Map.entry("unknown-player", "<red>Player not found. Use an online/known name or a UUID."),
                Map.entry("added", "<green>Added {id} to {player}'s journal."), Map.entry("removed", "<green>Removed {id} from {player}'s journal."),
                Map.entry("unchanged", "<gray>{player}'s journal already has the requested state for {id}."),
                Map.entry("unavailable", "<red>Journal storage could not confirm this operation. Try again shortly."));
    }
}
