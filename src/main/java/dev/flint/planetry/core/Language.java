package dev.flint.planetry.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable MiniMessage translations. Runtime values are never parsed as markup. */
public final class Language {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§](?:x(?:[&§][0-9a-f]){6}|#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private final Map<String, String> templates;
    private final String name;
    private final LanguageIntegrations integrations;

    private Language(Map<String, String> templates, String name, LanguageIntegrations integrations) {
        this.templates = Map.copyOf(templates); this.name = name; this.integrations = integrations;
    }

    public static Language load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        return load(plugin.getDataFolder().toPath(), plugin.getConfig()).withIntegrations(plugin);
    }

    /** Read just the language selection, without changing the plugin's active configuration. */
    public static Language reload(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        var config = new YamlConfiguration();
        config.load(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
        return reload(plugin.getDataFolder().toPath(), config).withIntegrations(plugin);
    }

    private Language withIntegrations(JavaPlugin plugin) {
        return new Language(templates, name, new LanguageIntegrations(plugin));
    }

    public String name() { return name; }

    static Language load(Path data, FileConfiguration config) throws IOException, InvalidConfigurationException {
        return read(data, config, true);
    }

    static Language reload(Path data, FileConfiguration config) throws IOException, InvalidConfigurationException {
        return read(data, config, false);
    }

    private static Language read(Path data, FileConfiguration config, boolean install) throws IOException, InvalidConfigurationException {
        Object selection = config.get("language", "en");
        if (!(selection instanceof String selected) || !selected.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("language must name a lang/*.yml file, for example en or de");
        Map<String, YamlConfiguration> bundled = new LinkedHashMap<>();
        for (String name : new String[]{"en", "de"}) bundled.put(name, bundled(name));
        Path directory = data.resolve("lang");
        Path selectedFile = directory.resolve(selected + ".yml");
        boolean existing = Files.exists(selectedFile);
        if (!existing && (!install || !bundled.containsKey(selected)))
            throw new IllegalArgumentException("Selected language '" + selected + "' is missing. Create lang/" + selected
                    + ".yml or set language to an existing file.");
        YamlConfiguration custom = new YamlConfiguration();
        if (existing) custom.load(selectedFile.toFile());
        // Validate before writing anything, including files with partial custom translations.
        strings(custom, "lang/" + selected + ".yml");
        if (!install) return merged(selected, bundled, custom);
        Path marker = directory.resolve(".legacy-config-imported");
        boolean importLegacy = !Files.exists(marker);
        boolean changed = false;
        if (importLegacy) {
            Map<String, String> english = strings(bundled.get("en"), "bundled English");
            changed |= importSection(config, "messages", custom, "messages", english);
            changed |= importSection(config, "mail.messages", custom, "mail", english);
        }
        Files.createDirectories(directory);
        if (!existing) {
            YamlConfiguration complete = new YamlConfiguration();
            if (bundled.containsKey(selected))
                strings(bundled.get(selected), "bundled language").forEach(complete::set);
            strings(custom, "lang/" + selected + ".yml").forEach(complete::set);
            complete.save(selectedFile.toFile());
            custom = complete;
        } else if (changed) custom.save(selectedFile.toFile());
        for (String name : bundled.keySet()) {
            Path target = directory.resolve(name + ".yml");
            if (!Files.exists(target)) {
                try (var stream = Language.class.getResourceAsStream("/lang/" + name + ".yml")) {
                    if (stream == null) throw new IOException("Missing bundled language " + name);
                    Files.copy(stream, target);
                }
            }
        }
        if (importLegacy) Files.writeString(marker, "Legacy config messages imported once. Edit lang/*.yml from now on.\n", StandardCharsets.UTF_8);
        return merged(selected, bundled, custom);
    }

    private static Language merged(String selected, Map<String, YamlConfiguration> bundled, YamlConfiguration custom) {
        Map<String, String> result = new HashMap<>(strings(bundled.get("en"), "bundled English"));
        if (bundled.containsKey(selected)) result.putAll(strings(bundled.get(selected), "bundled language"));
        result.putAll(strings(custom, "lang/" + selected + ".yml"));
        return new Language(result, selected, null);
    }

    private static YamlConfiguration bundled(String name) throws IOException, InvalidConfigurationException {
        var result = new YamlConfiguration();
        try (var stream = Language.class.getResourceAsStream("/lang/" + name + ".yml")) {
            if (stream == null) throw new IOException("Missing bundled language " + name);
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, String> strings(YamlConfiguration source, String label) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : source.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection) continue;
            if (!(entry.getValue() instanceof String value))
                throw new IllegalArgumentException(label + ": " + entry.getKey() + " must be a text string");
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static boolean importSection(FileConfiguration config, String sourcePath, YamlConfiguration target,
                                         String targetPath, Map<String, String> english) {
        var section = config.getConfigurationSection(sourcePath);
        if (section == null) return false;
        boolean changed = false;
        for (String key : section.getKeys(false)) {
            String source = sourcePath + "." + key;
            String destination = targetPath + "." + key;
            // isSet ignores JavaPlugin's bundled config defaults. Explicit translations always win.
            if (!config.isSet(source) || target.contains(destination)) continue;
            if (!(config.get(source) instanceof String value))
                throw new IllegalArgumentException(source + " must be a text string");
            String converted = MINI.serialize(LEGACY.deserialize(value.replace('§', '&')));
            String originalDefault = english.get(destination);
            // Shipped old English defaults must not override a newly selected German language.
            if (originalDefault != null && converted.equals(MINI.serialize(format(originalDefault)))) continue;
            target.set(destination, converted);
            changed = true;
        }
        return changed;
    }

    public Component text(String key, Map<String, String> values) { return text(key, values, Map.of()); }

    /** Component substitutions are for already-rendered trusted formatting, such as sender ranks. */
    public Component text(String key, Map<String, String> values, Map<String, Component> components) {
        return text(null, key, values, components);
    }

    public Component text(CommandSender recipient, String key, Map<String, String> values) {
        return text(recipient, key, values, Map.of());
    }

    public Component text(CommandSender recipient, String key, Map<String, String> values, Map<String, Component> components) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(components, "components");
        var resolver = TagResolver.builder();
        String source = templates.getOrDefault(key, key);
        if (integrations != null) {
            source = integrations.placeholders(source, recipient, resolver);
            resolver.resolver(integrations.glyphs(recipient));
        }
        var matcher = TOKEN.matcher(legacy(source, resolver));
        StringBuilder template = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            String token = matcher.group(1);
            Component component = components.get(token);
            if (component == null && values.containsKey(token)) component = Component.text(values.get(token));
            if (component == null) continue;
            String tag = "planetry_value_" + index++;
            resolver.resolver(Placeholder.component(tag, component));
            matcher.appendReplacement(template, "<" + tag + ">");
        }
        matcher.appendTail(template);
        return MINI.deserialize(template.toString(), resolver.build());
    }

    public boolean hasToken(String key, String token) {
        return templates.getOrDefault(key, "").contains("{" + token + "}");
    }

    public static Component format(String template) {
        var resolver = TagResolver.builder();
        return MINI.deserialize(legacy(template, resolver), resolver.build());
    }

    /** Normalize trusted MiniMessage/legacy formatting to portable MiniMessage. */
    public static String miniMessage(String template) { return MINI.serialize(format(template)); }

    private static String legacy(String template, TagResolver.Builder resolver) {
        Objects.requireNonNull(template, "template");
        StringBuilder result = new StringBuilder();
        var matcher = LEGACY_CODE.matcher(template);
        int index = 0;
        for (int offset = 0; offset < template.length();) {
            char character = template.charAt(offset);
            if (character == '\\' && offset + 1 < template.length()) {
                result.append(template, offset, offset + 2);
                offset += 2;
                continue;
            }
            if (character == '<') {
                int end = tagEnd(template, offset + 1);
                if (end >= 0) {
                    // Tag arguments may contain URLs, commands, quoted '>' or escaped quotes.
                    // Their ampersands belong to MiniMessage and must remain byte-for-byte intact.
                    result.append(template, offset, end + 1);
                    offset = end + 1;
                    continue;
                }
            }
            if ((character == '&' || character == '§') && matcher.region(offset, template.length()).lookingAt()) {
                String code = matcher.group();
                String tag = "planetry_legacy_" + index++;
                // Unique styling tags preserve enclosing MiniMessage scopes. A literal <reset>
                // would destroy them and turn later closing tags into visible text.
                resolver.resolver(TagResolver.resolver(tag, legacyStyle(code)));
                result.append('<').append(tag).append('>');
                offset += code.length();
            } else {
                result.append(character);
                offset++;
            }
        }
        return result.toString();
    }

    private static int tagEnd(String template, int offset) {
        char quote = 0;
        for (int index = offset; index < template.length(); index++) {
            char character = template.charAt(index);
            if (character == '\\') { index++; continue; }
            if (quote != 0) {
                if (character == quote) quote = 0;
            } else if (character == '\'' || character == '"') quote = character;
            else if (character == '>') return index;
        }
        return -1;
    }

    private static Tag legacyStyle(String code) {
        char kind = Character.toLowerCase(code.charAt(1));
        int colorIndex = Character.digit(kind, 16);
        TextColor color;
        if (kind == 'x') {
            StringBuilder hex = new StringBuilder("#");
            for (int index = 3; index < code.length(); index += 2) hex.append(code.charAt(index));
            color = TextColor.fromHexString(hex.toString());
        } else if (kind == '#') color = TextColor.fromHexString(code.substring(1));
        else color = colorIndex >= 0 ? NamedTextColor.NAMES.value(COLORS[colorIndex]) : kind == 'r' ? NamedTextColor.WHITE : null;
        return Tag.styling(style -> {
            if (color != null) {
                style.color(color);
                for (TextDecoration decoration : TextDecoration.values()) style.decoration(decoration, false);
            } else {
                style.decoration(switch (kind) {
                    case 'k' -> TextDecoration.OBFUSCATED;
                    case 'l' -> TextDecoration.BOLD;
                    case 'm' -> TextDecoration.STRIKETHROUGH;
                    case 'n' -> TextDecoration.UNDERLINED;
                    default -> TextDecoration.ITALIC;
                }, true);
            }
        });
    }
}
