package dev.flint.planetry.core.journal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class JournalSettingsTest {
    static YamlConfiguration config() {
        var config = new YamlConfiguration();
        JournalSettings.CATEGORY_IDS.forEach(id -> config.createSection("categories." + id));
        config.set("messages.unlock", List.of());
        return config;
    }
    static void entry(YamlConfiguration config, String category, String id) {
        String path = "categories." + category + ".entries." + id;
        config.set(path + ".mmoitems-type", "MATERIAL"); config.set(path + ".mmoitems-id", "MOON_CRYSTAL");
    }

    @Test void bundledFileStartsWithSevenEmptyCategoriesAndMultilineMessages() throws Exception {
        var config = new YamlConfiguration();
        try (var input = getClass().getResourceAsStream("/journal.yml")) {
            assertNotNull(input); config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        var settings = JournalSettings.read(config);
        assertEquals(JournalSettings.CATEGORY_IDS, List.copyOf(settings.categories().keySet()));
        assertTrue(settings.entries().isEmpty()); assertEquals(4, settings.unlockMessage().size());
        assertTrue(settings.unlockMessage().get(1).contains("{item}"));
    }

    @Test void triggersUseExactDefinitionIdsAndCommandsAreTheDefault() {
        var config = config(); entry(config, "mining", "moon_crystal"); entry(config, "achievements", "first_steps");
        String path = "categories.mining.entries.moon_crystal";
        config.set(path + ".harvest-nodes", List.of("moon_node")); config.set(path + ".mythic-mobs", List.of("MoonGuardian"));
        var settings = JournalSettings.read(config);
        assertEquals("moon_crystal", settings.harvest("moon_node").getFirst().id());
        assertTrue(settings.harvest("MOON_NODE").isEmpty());
        assertEquals("moon_crystal", settings.kills("MoonGuardian").getFirst().id());
        assertTrue(settings.kills("moonguardian").isEmpty());
        assertTrue(settings.entries().get("first_steps").harvestNodes().isEmpty());
        assertTrue(settings.entries().get("first_steps").mythicMobs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> settings.entries().clear());
    }

    @Test void duplicateUnsafeAndAmbiguousEntryIdsFailBeforeStarting() {
        var duplicate = config(); entry(duplicate, "mining", "same_id"); entry(duplicate, "farming", "same_id");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(duplicate));
        for (String id : List.of("MixedCase", "spaces here", "bad;command", "_leading", "x".repeat(65))) {
            var config = config(); entry(config, "mining", id);
            assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(config), id);
        }
        assertTrue(JournalSettings.validId("moon_crystal-found"));
        assertFalse(JournalSettings.validId(null));
    }

    @Test void malformedCategoriesDefinitionsAndTriggersFailClosed() {
        var absent = config(); absent.set("categories.mining", null);
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(absent));
        var unknown = config(); unknown.createSection("categories.admin");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(unknown));
        var scalar = config(); scalar.set("categories.mining.entries", List.of("invalid"));
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(scalar));
        var missing = config(); missing.createSection("categories.mining.entries.missing");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(missing));
        for (Object invalid : List.of("moon_node", List.of(42), List.of("node\ncommand"))) {
            var config = config(); entry(config, "mining", "crystal");
            config.set("categories.mining.entries.crystal.harvest-nodes", invalid);
            assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(config));
        }
    }

    @Test void multilineMessagesMustBeBoundedTextLists() {
        var config = config(); config.set("messages.unlock", "not a list");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(config));
        config.set("messages.unlock", List.of(42));
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(config));
        config.set("messages.unlock", java.util.Collections.nCopies(33, "line"));
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.read(config));
        config.set("messages.unlock", List.of("", "{item}", "{open}"));
        assertEquals(3, JournalSettings.read(config).unlockMessage().size());
    }

    @Test void paginationKeepsEveryEntryReachableAndClampsUntrustedPages() {
        assertEquals(1, JournalSettings.pages(0)); assertEquals(1, JournalSettings.pages(45));
        assertEquals(2, JournalSettings.pages(46)); assertEquals(2, JournalSettings.pages(90));
        assertEquals(3, JournalSettings.pages(91)); assertEquals(0, JournalSettings.page(-10, 91));
        assertEquals(2, JournalSettings.page(99, 91)); assertEquals(0, JournalSettings.page(99, 0));
    }
}
