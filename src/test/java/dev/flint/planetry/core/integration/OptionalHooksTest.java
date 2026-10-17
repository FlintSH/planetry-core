package dev.flint.planetry.core.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.tools.ToolProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class OptionalHooksTest {
    @TempDir Path directory;
    private final JavaPlugin core = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Logger logger = mock(Logger.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(core.getServer()).thenReturn(server);
        when(core.getLogger()).thenReturn(logger);
        when(core.isEnabled()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(plugins);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
    }

    @Test
    void missingAndDisabledAuraSkillsCannotConfirmOrAwardExperience() {
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            assertFalse(AuraSkillsHook.available("mining"));
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            Plugin disabled = mock(Plugin.class);
            when(plugins.getPlugin("AuraSkills")).thenReturn(disabled);
            assertFalse(AuraSkillsHook.available("mining"));
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            verify(disabled, times(3)).isEnabled();
        }
    }

    @Test
    void auraSkillKeysPlayersAndAmountsAreValidatedWithoutCallingAnOptionalApi() {
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            for (String invalid : new String[] {null, "", "mining space", "../mining", "a:b:c", ":mining", "a/"}) {
                assertFalse(AuraSkillsHook.available(invalid));
                assertFalse(AuraSkillsHook.award(player, invalid, 10));
            }
            assertFalse(AuraSkillsHook.available(null, "mining"));
            assertFalse(AuraSkillsHook.award(null, "mining", 10));
            for (double invalid : new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                assertFalse(AuraSkillsHook.award(player, "mining", invalid));
            }
            when(player.isOnline()).thenReturn(false);
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            verifyNoInteractions(plugins);
        }
    }

    @Test
    void aZeroXpAwardIsAnOnlineNoOpWithoutAnOptionalPlugin() {
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            assertTrue(AuraSkillsHook.award(player, "mining", 0));
            verifyNoInteractions(plugins);
        }
    }

    @Test
    void enabledAuraSkillsWithMissingOrWrongApiFailsClosed() throws Exception {
        try (URLClassLoader loader = api(Map.of()); MockedStatic<Bukkit> bukkit = bukkit()) {
            when(plugins.getPlugin("AuraSkills")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            assertFalse(AuraSkillsHook.available("mining"));
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
        }
        try (URLClassLoader loader = api(Map.of("dev.aurelium.auraskills.api.AuraSkillsApi", """
                package dev.aurelium.auraskills.api;
                public class AuraSkillsApi { public static String get() { return "wrong API"; } }
                """)); MockedStatic<Bukkit> bukkit = bukkit()) {
            when(plugins.getPlugin("AuraSkills")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            assertFalse(AuraSkillsHook.available("mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
        }
    }

    @Test
    void auraSkillsUsesTheRegistryLoadedPlayerAndMultiplierAwareAwardApi() throws Exception {
        try (URLClassLoader loader = api(auraApi()); MockedStatic<Bukkit> bukkit = bukkit()) {
            when(plugins.getPlugin("AuraSkills")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            assertTrue(AuraSkillsHook.available("MINING"));
            Object api = loader.loadClass("dev.aurelium.auraskills.api.AuraSkillsApi").getMethod("get").invoke(null);
            Object registry = api.getClass().getField("registry").get(api);
            Object id = registry.getClass().getField("lastId").get(registry);
            assertEquals("auraskills/mining", id.toString());
            assertTrue(AuraSkillsHook.available(player, "CUSTOM:Excavation"));
            id = registry.getClass().getField("lastId").get(registry);
            assertEquals("custom/excavation", id.toString());
            assertEquals(playerId, api.getClass().getField("lastPlayer").get(api));
            assertTrue(AuraSkillsHook.award(player, "mining", 12.5));
            Object user = api.getClass().getField("user").get(api);
            assertEquals(12.5, user.getClass().getField("awarded").getDouble(user));
            assertEquals(1, user.getClass().getField("awards").getInt(user));
        }
    }

    @Test
    void unloadedMissingAndUnknownAuraSkillsDataCannotAwardXp() throws Exception {
        try (URLClassLoader loader = api(auraApi()); MockedStatic<Bukkit> bukkit = bukkit()) {
            when(plugins.getPlugin("AuraSkills")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            Object api = loader.loadClass("dev.aurelium.auraskills.api.AuraSkillsApi").getMethod("get").invoke(null);
            Object user = api.getClass().getField("user").get(api);
            user.getClass().getField("loaded").setBoolean(user, false);
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            assertEquals(0, user.getClass().getField("awards").getInt(user));
            api.getClass().getField("user").set(api, null);
            assertFalse(AuraSkillsHook.available(player, "mining"));
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            Object registry = api.getClass().getField("registry").get(api);
            registry.getClass().getField("skill").set(registry, null);
            assertFalse(AuraSkillsHook.available("unknown"));
            assertFalse(AuraSkillsHook.award(player, "unknown", 10));
        }
    }

    @Test
    void anAuraSkillsAwardThatThrowsIsNotAutomaticallyRepeated() throws Exception {
        try (URLClassLoader loader = api(auraApi()); MockedStatic<Bukkit> bukkit = bukkit()) {
            when(plugins.getPlugin("AuraSkills")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            Object api = loader.loadClass("dev.aurelium.auraskills.api.AuraSkillsApi").getMethod("get").invoke(null);
            Object user = api.getClass().getField("user").get(api);
            user.getClass().getField("fail").setBoolean(user, true);
            assertFalse(AuraSkillsHook.award(player, "mining", 10));
            assertEquals(1, user.getClass().getField("awards").getInt(user));
        }
    }

    @Test
    void missingDisabledInvalidAndAbsentMmoItemsNeverBecomeOrdinaryItems() {
        MmoItemsHook hook = new MmoItemsHook(core);
        assertNull(hook.apply("MATERIAL", "COPPER"));
        Plugin disabled = mock(Plugin.class);
        assertNull(MmoItemsHook.build(disabled, "MATERIAL", "COPPER"));
        Plugin enabled = mock(Plugin.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("MMOItems")).thenReturn(enabled);
        assertNull(hook.apply("MATERIAL", "COPPER"));
        for (String invalid : new String[] {null, "", "wrong value", "../ore", "a".repeat(129)}) {
            assertNull(MmoItemsHook.build(enabled, "MATERIAL", invalid));
            assertNull(MmoItemsHook.build(enabled, invalid, "COPPER"));
        }
    }

    @Test
    void mmoItemsWithAnIncompatibleApiCannotProduceAFallbackItem() throws Exception {
        try (URLClassLoader loader = api(Map.of("net.Indyuce.mmoitems.MMOItems", """
                package net.Indyuce.mmoitems;
                public class MMOItems { public static final Object plugin = new Object(); }
                """))) {
            assertNull(MmoItemsHook.build(plugin(loader, new AtomicBoolean(true)), "MATERIAL", "COPPER"));
        }
    }

    @Test
    void missingAndDisabledMythicMobsNeverRegisterAnEventListener() {
        MythicJournalHook.attach(core, (target, id) -> fail("Unexpected unlock"));
        Plugin disabled = mock(Plugin.class);
        when(plugins.getPlugin("MythicMobs")).thenReturn(disabled);
        MythicJournalHook.attach(core, (target, id) -> fail("Unexpected unlock"));
        verify(plugins, never()).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(logger);
    }

    @Test
    void missingAndIncompatibleMythicMobsApisWarnWithoutRegisteringAnEventListener() throws Exception {
        try (URLClassLoader loader = api(Map.of())) {
            when(plugins.getPlugin("MythicMobs")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            MythicJournalHook.attach(core, (target, id) -> fail("Unexpected unlock"));
        }
        try (URLClassLoader loader = api(Map.of("io.lumine.mythic.bukkit.events.MythicMobDeathEvent", """
                package io.lumine.mythic.bukkit.events;
                public class MythicMobDeathEvent { }
                """))) {
            when(plugins.getPlugin("MythicMobs")).thenReturn(plugin(loader, new AtomicBoolean(true)));
            MythicJournalHook.attach(core, (target, id) -> fail("Unexpected unlock"));
        }
        verify(plugins, never()).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
        verify(logger, times(2)).warning(contains("hook unavailable"));
    }

    private MockedStatic<Bukkit> bukkit() {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        return bukkit;
    }

    /** Every optional API lives in its own plugin loader, including deliberately incompatible versions. */
    private URLClassLoader api(Map<String, String> sources) throws IOException {
        Path output = Files.createTempDirectory(directory, "api-");
        if (!sources.isEmpty()) {
            List<String> arguments = new ArrayList<>(List.of("-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", output.toString()));
            for (var source : sources.entrySet()) {
                Path file = output.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                arguments.add(file.toString());
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    arguments.toArray(String[]::new)), "The isolated API fixture must compile");
        }
        return new URLClassLoader(new java.net.URL[] {output.toUri().toURL()}, Plugin.class.getClassLoader());
    }

    private static Plugin plugin(ClassLoader loader, AtomicBoolean enabled) {
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "isEnabled" -> enabled.get();
                case "toString" -> "Isolated optional-plugin fixture";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected optional plugin call: " + method);
            };
        });
    }

    private static Map<String, String> auraApi() {
        return Map.of(
                "dev.aurelium.auraskills.api.AuraSkillsApi", """
                        package dev.aurelium.auraskills.api;
                        import java.util.UUID;
                        import dev.aurelium.auraskills.api.registry.GlobalRegistry;
                        import dev.aurelium.auraskills.api.user.SkillsUser;
                        public class AuraSkillsApi {
                            private static final AuraSkillsApi INSTANCE = new AuraSkillsApi();
                            public GlobalRegistry registry = new GlobalRegistry();
                            public SkillsUser user = new SkillsUser();
                            public UUID lastPlayer;
                            public static AuraSkillsApi get() { return INSTANCE; }
                            public GlobalRegistry getGlobalRegistry() { return registry; }
                            public SkillsUser getUser(UUID id) { lastPlayer = id; return user; }
                        }
                        """,
                "dev.aurelium.auraskills.api.skill.Skill", """
                        package dev.aurelium.auraskills.api.skill;
                        public class Skill { }
                        """,
                "dev.aurelium.auraskills.api.registry.NamespacedId", """
                        package dev.aurelium.auraskills.api.registry;
                        public record NamespacedId(String namespace, String key) {
                            public static NamespacedId of(String namespace, String key) { return new NamespacedId(namespace, key); }
                            public String toString() { return namespace + "/" + key; }
                        }
                        """,
                "dev.aurelium.auraskills.api.registry.GlobalRegistry", """
                        package dev.aurelium.auraskills.api.registry;
                        import dev.aurelium.auraskills.api.skill.Skill;
                        public class GlobalRegistry {
                            public Skill skill = new Skill();
                            public NamespacedId lastId;
                            public Skill getSkill(NamespacedId id) { lastId = id; return skill; }
                        }
                        """,
                "dev.aurelium.auraskills.api.user.SkillsUser", """
                        package dev.aurelium.auraskills.api.user;
                        import dev.aurelium.auraskills.api.skill.Skill;
                        public class SkillsUser {
                            public boolean loaded = true, fail;
                            public double awarded;
                            public int awards;
                            public boolean isLoaded() { return loaded; }
                            public void addSkillXp(Skill skill, double xp) {
                                awarded += xp; awards++;
                                if (fail) throw new IllegalStateException("Injected failure after an uncertain XP award");
                            }
                        }
                        """);
    }
}
