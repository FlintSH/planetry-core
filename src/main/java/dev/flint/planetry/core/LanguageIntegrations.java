package dev.flint.planetry.core;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional message hooks. Only administrator-authored language templates are expanded. */
public final class LanguageIntegrations {
    private static final Pattern PLACEHOLDER = Pattern.compile("%[^%\\s_]+_[^%\\r\\n]*%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final JavaPlugin plugin;
    private final BiFunction<Player, String, String> placeholders;
    private final ClassLookup classes;
    private final AtomicBoolean placeholderWarning = new AtomicBoolean();
    private final AtomicBoolean glyphWarning = new AtomicBoolean();
    private final AtomicBoolean shiftWarning = new AtomicBoolean();

    public LanguageIntegrations(JavaPlugin plugin) {
        this(plugin, PlaceholderApi::resolve, (name, loader) -> Class.forName(name, true, loader));
    }

    /** Hooks let tests exercise optional failures without installing either plugin. */
    LanguageIntegrations(JavaPlugin plugin, BiFunction<Player, String, String> placeholders, ClassLookup classes) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.classes = Objects.requireNonNull(classes, "classes");
    }

    @FunctionalInterface
    interface ClassLookup {
        Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException;
    }

    /**
     * Expand each original percent token once. Results are inserted as components, so MiniMessage,
     * Core tokens and additional PAPI tokens in a result remain literal. Legacy colors are allowed.
     * Console and calls without a player keep their tokens, rather than passing null to expansions.
     */
    public String placeholders(String template, CommandSender recipient, TagResolver.Builder resolver) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(resolver, "resolver");
        if (!(recipient instanceof Player player)) return template;
        var matcher = PLACEHOLDER.matcher(template);
        if (!matcher.find()) return template;
        try {
            if (enabled("PlaceholderAPI") == null) return template;
            String prefix = "planetry_papi_" + UUID.randomUUID().toString().replace("-", "") + "_";
            StringBuilder result = new StringBuilder();
            int offset = 0;
            int index = 0;
            do {
                String token = matcher.group();
                String value;
                try {
                    value = placeholders.apply(player, token);
                } catch (RuntimeException | LinkageError error) {
                    warn(placeholderWarning, "PlaceholderAPI", error);
                    continue;
                }
                // Unknown expansions normally return the original token; preserve that exact text.
                if (value == null || value.equals(token)) continue;
                String tag = prefix + index++;
                resolver.resolver(Placeholder.component(tag, LEGACY.deserialize(value.replace('§', '&'))));
                result.append(template, offset, matcher.start()).append('<').append(tag).append('>');
                offset = matcher.end();
            } while (matcher.find());
            return result.append(template, offset, template.length()).toString();
        } catch (RuntimeException | LinkageError error) {
            warn(placeholderWarning, "PlaceholderAPI", error);
            return template;
        }
    }

    /** Use Nexo's font-aware native resolvers and supported glyph arguments, without a dependency. */
    public TagResolver glyphs(CommandSender recipient) {
        try {
            Plugin nexo = enabled("Nexo");
            if (nexo == null) return TagResolver.empty();
            Player player = recipient instanceof Player value ? value : null;
            TagResolver glyph = nativeResolver(nexo, "GlyphTag", player);
            var result = TagResolver.builder().resolver(guarded(nexo, "glyph", glyph, glyphWarning));
            try {
                result.resolver(guarded(nexo, "shift", nativeResolver(nexo, "ShiftTag", null), shiftWarning));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                warn(shiftWarning, "Nexo shift tags", error);
            }
            return result.build();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(glyphWarning, "Nexo glyph tags", error);
            return TagResolver.empty();
        }
    }

    private Plugin enabled(String name) {
        Plugin installed = plugin.getServer().getPluginManager().getPlugin(name);
        return installed != null && installed.isEnabled() ? installed : null;
    }

    private TagResolver nativeResolver(Plugin nexo, String name, Player player) throws ReflectiveOperationException {
        // Official Nexo API: GlyphTag/ShiftTag.INSTANCE.getRESOLVER(). Older GlyphTag versions
        // also offer getResolverForPlayer(Player); prefer it to preserve their permission behavior.
        // https://jd.nexomc.com/1.8/com/nexomc/nexo/glyphs/GlyphTag.html
        // https://jd.nexomc.com/1.15/com/nexomc/nexo/glyphs/GlyphTag.html
        Class<?> type = classes.load("com.nexomc.nexo.glyphs." + name, nexo.getClass().getClassLoader());
        Object instance = type.getField("INSTANCE").get(null);
        Method method = null;
        if (player != null) {
            try { method = type.getMethod("getResolverForPlayer", Player.class); }
            catch (NoSuchMethodException unavailable) { /* Current Nexo exposes only its global resolver. */ }
        }
        Object resolved = method == null ? type.getMethod("getRESOLVER").invoke(instance)
                : method.invoke(instance, player);
        if (resolved instanceof TagResolver resolver) return resolver;
        throw new ReflectiveOperationException("Nexo did not provide an Adventure TagResolver");
    }

    /** A broken/disabled optional resolver must not prevent the rest of a message from rendering. */
    private TagResolver guarded(Plugin nexo, String name, TagResolver nativeResolver, AtomicBoolean warning) {
        return new TagResolver() {
            @Override public Tag resolve(String tag, ArgumentQueue arguments, Context context) {
                try {
                    if (name.equals(tag) && nexo.isEnabled()) return nativeResolver.resolve(tag, arguments, context);
                } catch (RuntimeException | LinkageError error) {
                    warn(warning, "Nexo " + name + " tags", error);
                }
                return null;
            }

            @Override public boolean has(String tag) {
                try { return name.equals(tag) && nexo.isEnabled() && nativeResolver.has(tag); }
                catch (RuntimeException | LinkageError error) {
                    warn(warning, "Nexo " + name + " tags", error);
                    return false;
                }
            }
        };
    }

    private void warn(AtomicBoolean warning, String integration, Throwable error) {
        if (warning.compareAndSet(false, true)) plugin.getLogger().warning("Language " + integration
                + " integration is unavailable (" + error.getClass().getSimpleName()
                + "); unresolved placeholders/tags stay visible. Further warnings are suppressed.");
    }

    /** Keep the optional API linkage behind the enabled-plugin check above. */
    private static final class PlaceholderApi {
        static String resolve(Player player, String token) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, token);
        }
    }
}
