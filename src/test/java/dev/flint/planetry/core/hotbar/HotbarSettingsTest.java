package dev.flint.planetry.core.hotbar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HotbarSettingsTest {
    @Test void defaultsReserveTheLastTwoSlotsAndRequireCommandsToBeConfigured() {
        try (var fixture = new HotbarFixture()) {
            var settings = HotbarSettings.read(fixture.config);
            assertTrue(settings.enabled());
            assertEquals(20, settings.restoreIntervalTicks());
            assertEquals(6, settings.activationCooldownTicks());
            assertEquals(7, settings.questBook().slot());
            assertEquals(8, settings.novaLink().slot());
            assertEquals("BOOK", settings.questBook().material().name());
            assertEquals("COMPASS", settings.novaLink().material().name());
            assertEquals("", settings.questBook().nexoId());
            assertEquals("", settings.novaLink().command());
            assertEquals(HotbarSettings.Executor.PLAYER, settings.questBook().executor());
        }
    }

    @Test void configuredNexoDisplayCommandExecutorAndIntervalsAreRead() {
        try (var fixture = new HotbarFixture()) {
            fixture.config.set("hotbar.enabled", false);
            fixture.config.set("hotbar.restore-interval-ticks", 80);
            fixture.config.set("hotbar.activation-cooldown-ticks", 12);
            fixture.config.set("hotbar.quest-book.nexo-id", "quest_book");
            fixture.config.set("hotbar.quest-book.material", "PAPER");
            fixture.config.set("hotbar.quest-book.display-name", "&dQuests");
            fixture.config.set("hotbar.quest-book.command", "quests open {player} {uuid}");
            fixture.config.set("hotbar.quest-book.executor", "console");
            var settings = HotbarSettings.read(fixture.config);
            assertFalse(settings.enabled());
            assertEquals(80, settings.restoreIntervalTicks());
            assertEquals(12, settings.activationCooldownTicks());
            assertEquals("quest_book", settings.questBook().nexoId());
            assertEquals("PAPER", settings.questBook().material().name());
            assertEquals("&dQuests", settings.questBook().displayName());
            assertEquals("quests open {player} {uuid}", settings.questBook().command());
            assertEquals(HotbarSettings.Executor.CONSOLE, settings.questBook().executor());
        }
    }

    @Test void nonItemMaterialsIncludingWaterAndLavaFailClearlyInsteadOfBreakingItemCreation() {
        try (var fixture = new HotbarFixture()) {
            for (String material : new String[]{"WATER", "LAVA", "AIR", "not_a_material"}) {
                fixture.config.set("hotbar.quest-book.material", material);
                assertInvalid(fixture, "hotbar.quest-book.material");
            }
        }
    }

    @Test void malformedSettingsFailWithTheirConfigurationPath() {
        Map<String, Object> invalid = Map.ofEntries(
                Map.entry("hotbar.enabled", "true"),
                Map.entry("hotbar.restore-interval-ticks", 0),
                Map.entry("hotbar.activation-cooldown-ticks", 1201),
                Map.entry("hotbar.quest-book.nexo-id", "a".repeat(129)),
                Map.entry("hotbar.novalink.nexo-id", "item with spaces"),
                Map.entry("hotbar.quest-book.display-name", " "),
                Map.entry("hotbar.novalink.display-name", "a".repeat(257)),
                Map.entry("hotbar.quest-book.command", "menu\nop Alice"),
                Map.entry("hotbar.novalink.command", "/menu"),
                Map.entry("hotbar.quest-book.executor", "operator"));
        try (var fixture = new HotbarFixture()) {
            for (var entry : invalid.entrySet()) {
                fixture.config.set(entry.getKey(), entry.getValue());
                assertInvalid(fixture, entry.getKey());
                fixture.config.set(entry.getKey(), null);
            }
            for (Object command : new Object[]{"menu\rop Alice", "menu\top", "menu\u2028op", "menu\u2029op", "menu {other}", "menu {player", " menu", "menu ", "a".repeat(1025), 42}) {
                fixture.config.set("hotbar.quest-book.command", command);
                assertInvalid(fixture, "hotbar.quest-book.command");
            }
            fixture.config.set("hotbar.quest-book.command", "");
            fixture.config.set("hotbar.restore-interval-ticks", 2.5);
            assertInvalid(fixture, "hotbar.restore-interval-ticks");
        }
    }

    private static void assertInvalid(HotbarFixture fixture, String path) {
        var error = assertThrows(IllegalArgumentException.class, () -> HotbarSettings.read(fixture.config));
        assertTrue(error.getMessage().contains(path), error.getMessage());
    }
}
