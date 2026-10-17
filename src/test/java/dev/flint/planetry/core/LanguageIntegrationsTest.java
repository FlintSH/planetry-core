package dev.flint.planetry.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanguageIntegrationsTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Plugin papi = mock(Plugin.class);
    private final Plugin nexo = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private final Logger logger = mock(Logger.class);
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugins.getPlugin("PlaceholderAPI")).thenReturn(papi);
        when(plugins.getPlugin("Nexo")).thenReturn(nexo);
        when(papi.isEnabled()).thenReturn(true);
        when(nexo.isEnabled()).thenReturn(true);
    }

    @Test void percentResultsAreLiteralComponentsWithLegacyColorsAndNoRecursiveExpansion() {
        List<String> requested = new ArrayList<>();
        String external = "§aRank <click:run_command:'/op Alice'><red>text</red></click> <glyph:heart> %other_value% {player}";
        var integration = adapter((recipient, token) -> {
            assertSame(player, recipient);
            requested.add(token);
            return external;
        });
        Component message = render(integration, "Result %luckperms_prefix%!", player);
        assertEquals("Result Rank <click:run_command:'/op Alice'><red>text</red></click> <glyph:heart> %other_value% {player}!",
                plain.serialize(message));
        assertEquals(List.of("%luckperms_prefix%"), requested);
        assertTrue(hasColor(message, NamedTextColor.GREEN));
        assertNoEvents(message);
    }

    @Test void ampersandHexAndSectionColorsWorkWithoutAutomaticLinkEvents() {
        var integration = adapter((recipient, token) -> "&#123456Hex §bAqua &aGreen https://example.org");
        Component message = render(integration, "%test_colors%", player);
        assertEquals("Hex Aqua Green https://example.org", plain.serialize(message));
        assertTrue(MiniMessage.miniMessage().serialize(message).contains("<#123456>"));
        assertTrue(hasColor(message, NamedTextColor.AQUA));
        assertTrue(hasColor(message, NamedTextColor.GREEN));
        assertNoEvents(message);
    }

    @Test void unresolvedAndNullResultsRetainTokensWhileEmptyResultsAreValid() {
        var integration = adapter((recipient, token) -> switch (token) {
            case "%test_null%" -> null;
            case "%test_empty%" -> "";
            default -> token;
        });
        assertEquals("%missing_value%|%test_null%||%invalid%",
                plain.serialize(render(integration, "%missing_value%|%test_null%|%test_empty%|%invalid%", player)));
    }

    @Test void onlyOriginalTokensAreEvaluatedAndAdjacentValuesCannotCreateNewTokens() {
        List<String> calls = new ArrayList<>();
        var integration = adapter((recipient, token) -> {
            calls.add(token);
            return token.equals("%test_first%") ? "%test_" : "third%";
        });
        assertEquals("%test_third%", plain.serialize(render(integration, "%test_first%%test_second%", player)));
        assertEquals(List.of("%test_first%", "%test_second%"), calls);
    }

    @Test void templateTokensWithEmptyOrComplexParametersArePassedWithoutModification() {
        List<String> calls = new ArrayList<>();
        var integration = adapter((recipient, token) -> { calls.add(token); return "ok"; });
        assertEquals("ok ok %invalid%", plain.serialize(render(integration, "%server_% %math_2 + 2% %invalid%", player)));
        assertEquals(List.of("%server_%", "%math_2 + 2%"), calls);
    }

    @Test void missingDisabledAndNonPlayerHooksNeverInvokeTheOptionalApi() {
        AtomicInteger calls = new AtomicInteger();
        var integration = adapter((recipient, token) -> { calls.incrementAndGet(); return "unexpected"; });
        String template = "%player_name%";
        assertEquals(template, plain.serialize(render(integration, template, null)));
        assertEquals(template, plain.serialize(render(integration, template, mock(CommandSender.class))));
        when(papi.isEnabled()).thenReturn(false);
        assertEquals(template, plain.serialize(render(integration, template, player)));
        when(plugins.getPlugin("PlaceholderAPI")).thenReturn(null);
        assertEquals(template, plain.serialize(render(integration, template, player)));
        assertEquals(0, calls.get());
        verifyNoInteractions(logger);
    }

    @Test void aDisabledPlaceholderApiCanBecomeAvailableWithoutRebuildingTheAdapter() {
        var integration = adapter((recipient, token) -> "ready");
        when(papi.isEnabled()).thenReturn(false);
        assertEquals("%test_value%", plain.serialize(render(integration, "%test_value%", player)));
        when(papi.isEnabled()).thenReturn(true);
        assertEquals("ready", plain.serialize(render(integration, "%test_value%", player)));
    }

    @Test void brokenExpansionsLeaveTheirTokensAndOtherExpansionsStillRenderWithBoundedWarnings() {
        var integration = adapter((recipient, token) -> {
            if (token.equals("%test_runtime%")) throw new IllegalStateException("broken expansion");
            if (token.equals("%test_linkage%")) throw new NoClassDefFoundError("optional dependency");
            return "OK";
        });
        for (int index = 0; index < 5; index++) {
            assertEquals("%test_runtime% OK %test_linkage% OK", plain.serialize(render(integration,
                    "%test_runtime% %test_good% %test_linkage% %test_good%", player)));
        }
        verify(logger, times(1)).warning(anyString());
    }

    @Test void repeatedCallsAddUniqueTagsToTheSameResolver() {
        var integration = adapter((recipient, token) -> token.equals("%test_a%") ? "first" : "second");
        var resolver = TagResolver.builder();
        String first = integration.placeholders("%test_a%", player, resolver);
        String second = integration.placeholders("%test_b%", player, resolver);
        assertNotEquals(first, second);
        assertEquals("first second", plain.serialize(MiniMessage.miniMessage().deserialize(first + " " + second, resolver.build())));
    }

    @Test void nativeGlyphResolverUsesNexosPluginClassloaderAndKeepsFontAndArguments() {
        List<ClassLoader> loaders = new ArrayList<>();
        var integration = new LanguageIntegrations(plugin, (recipient, token) -> token, (name, loader) -> {
            loaders.add(loader);
            return name.endsWith("GlyphTag") ? GlobalGlyph.class : NativeShift.class;
        });
        Component message = MiniMessage.miniMessage().deserialize("<glyph:heart:colorable><shift:-8>", integration.glyphs(player));
        assertEquals("heart:colorable-8", plain.serialize(message));
        assertTrue(MiniMessage.miniMessage().serialize(message).contains("<font:nexo:custom>"));
        assertEquals(List.of(nexo.getClass().getClassLoader(), nexo.getClass().getClassLoader()), loaders);
    }

    @Test void oldPlayerAwareResolverIsPreferredAndConsoleUsesItsGlobalForm() {
        when(player.getName()).thenReturn("Alice");
        var integration = nativeAdapter(PlayerGlyph.class);
        assertEquals("Alice", plain.serialize(MiniMessage.miniMessage().deserialize("<glyph:heart>", integration.glyphs(player))));
        assertEquals("global", plain.serialize(MiniMessage.miniMessage().deserialize("<glyph:heart>", integration.glyphs(mock(CommandSender.class)))));
        verifyNoInteractions(logger);
    }

    @Test void unavailableNexoDoesNotAttemptReflectionAndUnknownTagsRemainVisible() {
        AtomicInteger loads = new AtomicInteger();
        var integration = new LanguageIntegrations(plugin, (recipient, token) -> token, (name, loader) -> {
            loads.incrementAndGet();
            throw new AssertionError("Nexo must be enabled before reflection");
        });
        when(nexo.isEnabled()).thenReturn(false);
        assertEquals("<glyph:heart><shift:-8>", plain.serialize(MiniMessage.miniMessage()
                .deserialize("<glyph:heart><shift:-8>", integration.glyphs(player))));
        when(plugins.getPlugin("Nexo")).thenReturn(null);
        assertFalse(integration.glyphs(player).has("glyph"));
        assertEquals(0, loads.get());
        verifyNoInteractions(logger);
    }

    @Test void missingNativeApiLeavesTagsVisibleAndOnlyLogsOneWarning() {
        var integration = new LanguageIntegrations(plugin, (recipient, token) -> token, (name, loader) -> {
            throw new ClassNotFoundException(name);
        });
        for (int index = 0; index < 5; index++)
            assertEquals("<glyph:heart>", plain.serialize(MiniMessage.miniMessage()
                    .deserialize("<glyph:heart>", integration.glyphs(player))));
        verify(logger, times(1)).warning(anyString());
    }

    @Test void missingShiftApiPreservesWorkingGlyphs() {
        var integration = new LanguageIntegrations(plugin, (recipient, token) -> token, (name, loader) -> {
            if (name.endsWith("ShiftTag")) throw new ClassNotFoundException(name);
            return GlobalGlyph.class;
        });
        assertEquals("heart<shift:-8>", plain.serialize(MiniMessage.miniMessage()
                .deserialize("<glyph:heart><shift:-8>", integration.glyphs(player))));
        verify(logger, times(1)).warning(anyString());
    }

    @Test void anIncompatibleNativeReturnTypeAndReflectiveInvocationFailureAreContained() {
        for (Class<?> type : List.of(WrongGlyph.class, ThrowingGlyph.class)) {
            assertFalse(nativeAdapter(type).glyphs(player).has("glyph"));
        }
        verify(logger, times(2)).warning(anyString());
    }

    @Test void nativeResolverExecutionFailuresLeaveTagsVisibleAndWarningsAreBounded() {
        var integration = nativeAdapter(BrokenGlyph.class);
        TagResolver resolver = integration.glyphs(player);
        for (int index = 0; index < 5; index++) {
            assertEquals("<glyph:heart>", plain.serialize(MiniMessage.miniMessage().deserialize("<glyph:heart>", resolver)));
        }
        verify(logger, times(1)).warning(anyString());
    }

    @Test void anAlreadyCreatedResolverStopsWorkingWhenNexoIsDisabled() {
        TagResolver resolver = nativeAdapter(GlobalGlyph.class).glyphs(player);
        when(nexo.isEnabled()).thenReturn(false);
        assertEquals("<glyph:heart>", plain.serialize(MiniMessage.miniMessage().deserialize("<glyph:heart>", resolver)));
        verifyNoInteractions(logger);
    }

    private LanguageIntegrations adapter(BiFunction<Player, String, String> placeholders) {
        return new LanguageIntegrations(plugin, placeholders, (name, loader) -> { throw new ClassNotFoundException(name); });
    }

    private LanguageIntegrations nativeAdapter(Class<?> glyph) {
        return new LanguageIntegrations(plugin, (recipient, token) -> token,
                (name, loader) -> name.endsWith("GlyphTag") ? glyph : NativeShift.class);
    }

    private Component render(LanguageIntegrations integration, String template, CommandSender recipient) {
        var resolver = TagResolver.builder();
        return MiniMessage.miniMessage().deserialize(integration.placeholders(template, recipient, resolver), resolver.build());
    }

    private static boolean hasColor(Component component, NamedTextColor color) {
        return color.equals(component.color()) || component.children().stream().anyMatch(child -> hasColor(child, color));
    }

    private static void assertNoEvents(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        assertNull(component.insertion());
        component.children().forEach(LanguageIntegrationsTest::assertNoEvents);
    }

    public static final class GlobalGlyph {
        public static final GlobalGlyph INSTANCE = new GlobalGlyph();
        public TagResolver getRESOLVER() {
            return TagResolver.resolver("glyph", (arguments, context) -> {
                String id = arguments.pop().value();
                if (arguments.hasNext()) id += ":" + arguments.pop().value();
                return Tag.inserting(Component.text(id).font(Key.key("nexo:custom")));
            });
        }
    }

    public static final class NativeShift {
        public static final NativeShift INSTANCE = new NativeShift();
        public TagResolver getRESOLVER() {
            return TagResolver.resolver("shift", (arguments, context) -> Tag.inserting(Component.text(arguments.pop().value())));
        }
    }

    public static final class PlayerGlyph {
        public static final PlayerGlyph INSTANCE = new PlayerGlyph();
        public TagResolver getResolverForPlayer(Player player) {
            return resolver(player.getName());
        }
        public TagResolver getRESOLVER() { return resolver("global"); }
        private TagResolver resolver(String identity) {
            return TagResolver.resolver("glyph", (arguments, context) -> {
                arguments.pop(); // Native Nexo consumes the required glyph ID before inserting its component.
                return Tag.inserting(Component.text(identity));
            });
        }
    }

    public static final class WrongGlyph {
        public static final WrongGlyph INSTANCE = new WrongGlyph();
        public String getRESOLVER() { return "incompatible"; }
    }

    public static final class ThrowingGlyph {
        public static final ThrowingGlyph INSTANCE = new ThrowingGlyph();
        public TagResolver getRESOLVER() { throw new NoClassDefFoundError("incompatible Nexo dependency"); }
    }

    public static final class BrokenGlyph {
        public static final BrokenGlyph INSTANCE = new BrokenGlyph();
        public TagResolver getRESOLVER() {
            return new TagResolver() {
                @Override public Tag resolve(String name, ArgumentQueue arguments, Context context) {
                    throw new NoClassDefFoundError("broken glyph renderer");
                }
                @Override public boolean has(String name) { return name.equals("glyph"); }
            };
        }
    }
}
