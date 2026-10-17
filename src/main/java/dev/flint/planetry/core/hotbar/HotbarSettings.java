package dev.flint.planetry.core.hotbar;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable startup configuration. Slots are fixed, zero-based Bukkit storage indices. */
public record HotbarSettings(boolean enabled, int restoreIntervalTicks, int activationCooldownTicks,
                             Item questBook, Item novaLink) {
    public enum Executor { PLAYER, CONSOLE }

    public record Item(String id, int slot, String nexoId, String command, Executor executor) {
        public Item {
            if (!("quest-book".equals(id) && slot == 7 || "novalink".equals(id) && slot == 8))
                throw new IllegalArgumentException("Hotbar items must use the fixed quest-book/novalink slots");
            String path = "hotbar." + id + ".";
            text(nexoId, path + "nexo-id", 1, 128);
            if (!nexoId.matches("[A-Za-z0-9_./:-]+"))
                throw invalid(path + "nexo-id", "must be a Nexo item ID without spaces");
            text(command, path + "command", 0, 1024);
            if (!command.isEmpty() && (command.isBlank() || !command.equals(command.strip()) || command.startsWith("/")))
                throw invalid(path + "command", "must be a single command without a leading slash or surrounding whitespace");
            if (TOKENS.matcher(command.replace("{player}", "").replace("{uuid}", "")).find())
                throw invalid(path + "command", "supports only {player} and {uuid} placeholders");
            Objects.requireNonNull(executor, path + "executor");
        }
    }

    private static final Pattern TOKENS = Pattern.compile("[{}]");

    public HotbarSettings {
        interval(restoreIntervalTicks, "hotbar.restore-interval-ticks");
        interval(activationCooldownTicks, "hotbar.activation-cooldown-ticks");
        Objects.requireNonNull(questBook, "questBook");
        Objects.requireNonNull(novaLink, "novaLink");
        if (!questBook.id().equals("quest-book") || !novaLink.id().equals("novalink"))
            throw new IllegalArgumentException("Hotbar item definitions are in the wrong slots");
    }

    public List<Item> items() { return List.of(questBook, novaLink); }

    public static HotbarSettings read(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new HotbarSettings(bool(config, "hotbar.enabled", true),
                number(config, "hotbar.restore-interval-ticks", 20),
                number(config, "hotbar.activation-cooldown-ticks", 6),
                item(config, "quest-book", 7, "quest_book"),
                item(config, "novalink", 8, "novalink"));
    }

    private static Item item(FileConfiguration config, String id, int slot, String defaultNexoId) {
        String path = "hotbar." + id + ".";
        String nexoId = string(config, path + "nexo-id", defaultNexoId);
        // Older releases wrote blank IDs to opt into generic items. Upgrade those
        // configurations to the network's Nexo IDs without rewriting their files.
        if (nexoId.isBlank()) nexoId = defaultNexoId;
        String executor = string(config, path + "executor", "player");
        Executor parsed;
        try { parsed = Executor.valueOf(executor.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw invalid(path + "executor", "must be player or console"); }
        return new Item(id, slot, nexoId, string(config, path + "command", ""), parsed);
    }

    private static String string(FileConfiguration config, String path, String fallback) {
        Object value = config.get(path, fallback);
        if (!(value instanceof String result)) throw invalid(path, "must be a string");
        return result;
    }

    private static int number(FileConfiguration config, String path, int fallback) {
        Object value = config.get(path, fallback);
        if (!(value instanceof Integer result)) throw invalid(path, "must be a whole number between 1 and 1200");
        interval(result, path);
        return result;
    }

    private static boolean bool(FileConfiguration config, String path, boolean fallback) {
        Object value = config.get(path, fallback);
        if (!(value instanceof Boolean result)) throw invalid(path, "must be true or false");
        return result;
    }

    private static void interval(int value, String path) {
        if (value < 1 || value > 1200) throw invalid(path, "must be between 1 and 1200 ticks");
    }

    private static void text(String value, String path, int min, int max) {
        if (value == null || value.length() < min || value.length() > max
                || value.codePoints().anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
            throw invalid(path, "must contain " + min + ".." + max + " characters and no control characters");
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason);
    }
}
