package dev.flint.planetry.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageTest {
    @TempDir Path data;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test void installsBothBundledLanguagesAndLoadsEnglishByDefault() throws Exception {
        Language language = Language.load(data, new YamlConfiguration());
        assertTrue(Files.isRegularFile(data.resolve("lang/en.yml")));
        assertTrue(Files.isRegularFile(data.resolve("lang/de.yml")));
        Component text = language.text("messages.updated", Map.of("player", "Alice", "level", "3", "gamexp", "25"));
        assertEquals("Alice is level 3 (25/100 Game XP).", plain.serialize(text));
        assertTrue(MiniMessage.miniMessage().serialize(text).contains("<green>"));
    }

    @Test void selectedGermanUsesBundledGermanForMissingCustomKeysWithoutRewritingTheFile() throws Exception {
        Path file = translation("de", "messages:\n  level: '<gold>Dein Level: {level}'\n");
        var config = new YamlConfiguration();
        config.set("language", "de");
        Language language = Language.load(data, config);
        assertEquals("Dein Level: 4", plain.serialize(language.text("messages.level", Map.of("level", "4"))));
        assertEquals("Du hast keine unabgeholte Post.", plain.serialize(language.text("mail.empty", Map.of())));
        assertEquals("messages:\n  level: '<gold>Dein Level: {level}'\n", Files.readString(file));
    }

    @Test void aCustomLanguageFallsBackPerKeyToEnglish() throws Exception {
        translation("fr", "mail:\n  empty: '<gray>Aucun courrier.'\n");
        var config = new YamlConfiguration();
        config.set("language", "fr");
        var language = Language.load(data, config);
        assertEquals("Aucun courrier.", plain.serialize(language.text("mail.empty", Map.of())));
        assertEquals("Your game XP is loading. Try again in a moment.", plain.serialize(language.text("messages.loading", Map.of())));
    }

    @Test void missingAndMalformedLanguageSelectionsFailWithActionableSettings() {
        var config = new YamlConfiguration();
        config.set("language", "fr");
        var missing = assertThrows(IllegalArgumentException.class, () -> Language.load(data, config));
        assertTrue(missing.getMessage().contains("lang/fr.yml"));
        for (Object value : new Object[]{"../outside", "en.yml", 1, ""}) {
            config.set("language", value);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> Language.load(data, config)).getMessage().contains("language"));
        }
    }

    @Test void firstLoadMigratesExplicitLegacyMessagesAndPreservesLiteralOldMarkup() throws Exception {
        var config = new YamlConfiguration();
        String legacy = "&aWelcome {player} <click:run_command:'/op someone'>literal</click>";
        config.set("messages.updated", legacy);
        config.set("mail.messages.empty", "§dCustom inbox");
        var language = Language.load(data, config);
        Component updated = language.text("messages.updated", Map.of("player", "Alice"));
        assertEquals("Welcome Alice <click:run_command:'/op someone'>literal</click>", plain.serialize(updated));
        assertNoEvents(updated);
        assertEquals("Custom inbox", plain.serialize(language.text("mail.empty", Map.of())));
        assertEquals(legacy, config.getString("messages.updated"), "The old config remains unchanged");
        var saved = new YamlConfiguration();
        saved.load(data.resolve("lang/en.yml").toFile());
        assertTrue(saved.getString("messages.updated").contains("<green>"));
        assertFalse(saved.getString("messages.updated").contains("&a"));
    }

    @Test void existingTranslationsWinAndLegacyImportRunsOnlyOnce() throws Exception {
        Path file = translation("en", "mail:\n  empty: '<gold>My translated inbox'\n");
        var config = new YamlConfiguration();
        config.set("mail.messages.empty", "&cOld inbox");
        config.set("messages.updated", "&bOld custom progress");
        var first = Language.load(data, config);
        assertEquals("My translated inbox", plain.serialize(first.text("mail.empty", Map.of())));
        assertEquals("Old custom progress", plain.serialize(first.text("messages.updated", Map.of())));
        var edited = new YamlConfiguration();
        edited.load(file.toFile());
        edited.set("messages.updated", null);
        edited.save(file.toFile());
        var second = Language.load(data, config);
        assertEquals("Alice is level 1 (0/100 Game XP).", plain.serialize(second.text("messages.updated",
                Map.of("player", "Alice", "level", "1", "gamexp", "0"))));
    }

    @Test void inheritedConfigDefaultsAreNotImportedAsUserTranslations() throws Exception {
        var config = new YamlConfiguration();
        var defaults = new YamlConfiguration();
        defaults.set("messages.loading", "Should not migrate");
        config.setDefaults(defaults);
        assertEquals("Your game XP is loading. Try again in a moment.", plain.serialize(Language.load(data, config)
                .text("messages.loading", Map.of())));
    }

    @Test void oldShippedEnglishDefaultsDoNotOverrideTheSelectedGermanLanguage() throws Exception {
        var config = new YamlConfiguration();
        config.set("language", "de");
        config.set("messages.loading", "&7Your game XP is loading. Try again in a moment.");
        config.set("mail.messages.empty", "&7You have no unclaimed mail.");
        var language = Language.load(data, config);
        assertEquals("Deine Game XP werden geladen. Versuche es gleich erneut.", plain.serialize(language.text("messages.loading", Map.of())));
        assertEquals("Du hast keine unabgeholte Post.", plain.serialize(language.text("mail.empty", Map.of())));
    }

    @Test void valuesCannotInjectMarkupOrLegacyColorsAndAreSubstitutedOnlyOnce() throws Exception {
        var language = Language.load(data, new YamlConfiguration());
        String command = "give {player} <click:run_command:'/op Alice'><red>loot</red></click> &c {count}";
        Component text = language.text("mail.sent-command", Map.of("command", command, "player", "Alice", "count", "7"));
        assertEquals("Command: " + command, plain.serialize(text));
        assertNoEvents(text);
        assertNoRed(text);
    }

    @Test void componentTokensKeepTrustedColorWhileNamesStayLiteralAndUnknownTokensRemain() throws Exception {
        var language = Language.load(data, new YamlConfiguration());
        var rank = Component.text("[Staff] {player}", NamedTextColor.RED);
        Component text = language.text("mail.sender-player", Map.of("player", "<blue>Alice"), Map.of("rank", rank));
        assertEquals("[Staff] {player} <blue>Alice", plain.serialize(text));
        assertTrue(MiniMessage.miniMessage().serialize(text).contains("<red>"));
        assertTrue(plain.serialize(language.text("mail.invalid-command", Map.of())).contains("{player}, {uuid}"));
    }

    @Test void currencyLabelsCanKeepTheirConfiguredFormattingWhileMoneyIsPlain() throws Exception {
        var language = Language.load(data, new YamlConfiguration());
        Component text = language.text("messages.reward", Map.of("money", "150", "currency", "&bCredits"),
                Map.of("currency", Language.format("&bCredits")));
        assertEquals("  ↪ Rewards: 150 Credits ✦", plain.serialize(text));
        assertTrue(MiniMessage.miniMessage().serialize(text).contains("<aqua>"));
    }

    @Test void mixedMiniMessageLegacyAndHexColorsAreSupported() {
        for (String value : new String[]{"<bold>&cRed", "<bold>&cRed</bold>", "&x&1&2&3&4&5&6Hex <italic>text", "§#123456Hex <italic>text"}) {
            String rendered = plain.serialize(Language.format(value));
            assertFalse(rendered.contains("&"), rendered);
            assertFalse(rendered.contains("§"), rendered);
            assertFalse(rendered.contains("<"), rendered);
        }
        assertTrue(Language.miniMessage("<bold>&cRed</bold>").contains("<red>"));
    }

    @Test void legacyConversionPreservesMiniMessageTagArgumentsIncludingAmpersandsAndQuotedAngles() {
        String url = "https://example.com/?a=1&b=2";
        assertEquals(url, clickValue(Language.format("<click:open_url:'" + url + "'>Open</click>")));
        String command = "say it's &b > literal";
        assertEquals(command, clickValue(Language.format("<click:run_command:'say it\\'s &b > literal'>Run</click>")));
    }

    @Test void placeholdersParticipateInMiniMessageEffectsWithoutBeingParsedAsTags() throws Exception {
        translation("en", "messages:\n  updated: '<gradient:red:blue>{player}</gradient>'\n");
        var language = Language.load(data, new YamlConfiguration());
        assertEquals("Alice", plain.serialize(language.text("messages.updated", Map.of("player", "Alice"))));
    }

    @Test void invalidTranslationValuesFailInsteadOfSilentlyFallingBack() throws Exception {
        translation("en", "mail:\n  empty: true\n");
        var error = assertThrows(IllegalArgumentException.class, () -> Language.load(data, new YamlConfiguration()));
        assertTrue(error.getMessage().contains("mail.empty"));
    }

    @Test void reloadBuildsANewSnapshotAndKeepsExistingReadersUnchanged() throws Exception {
        var config = new YamlConfiguration();
        Language original = Language.load(data, config);
        Path file = translation("en", "mail:\n  empty: '<gold>Reloaded inbox'\n");
        Language replacement = Language.reload(data, config);
        assertEquals("You have no unclaimed mail.", plain.serialize(original.text("mail.empty", Map.of())));
        assertEquals("Reloaded inbox", plain.serialize(replacement.text("mail.empty", Map.of())));
        assertEquals("en", replacement.name());
        assertEquals("mail:\n  empty: '<gold>Reloaded inbox'\n", Files.readString(file));
        config.set("language", "de");
        assertEquals("Du hast keine unabgeholte Post.", plain.serialize(Language.reload(data, config).text("mail.empty", Map.of())));
    }

    @Test void reloadDoesNotInstallFilesOrImportLegacyMessages() throws Exception {
        translation("en", "mail:\n  empty: '<gold>Current inbox'\n");
        var config = new YamlConfiguration();
        config.set("mail.messages.empty", "Legacy inbox");
        config.set("messages.loading", "Legacy loading");
        Language replacement = Language.reload(data, config);
        assertEquals("Current inbox", plain.serialize(replacement.text("mail.empty", Map.of())));
        assertEquals("Your game XP is loading. Try again in a moment.", plain.serialize(replacement.text("messages.loading", Map.of())));
        assertFalse(Files.exists(data.resolve("lang/.legacy-config-imported")));
        assertFalse(Files.exists(data.resolve("lang/de.yml")));
    }

    @Test void reloadRejectsMissingFilesBadYamlAndBadValues() throws Exception {
        var config = new YamlConfiguration();
        assertThrows(IllegalArgumentException.class, () -> Language.reload(data, config));
        assertFalse(Files.exists(data.resolve("lang")));
        Language original = Language.load(data, config);
        for (String invalid : new String[]{"mail: [broken", "mail:\n  empty: 123\n"}) {
            Path file = translation("en", invalid);
            assertThrows(Exception.class, () -> Language.reload(data, config), invalid);
            assertEquals(invalid, Files.readString(file));
            assertEquals("You have no unclaimed mail.", plain.serialize(original.text("mail.empty", Map.of())));
        }
    }

    private Path translation(String name, String content) throws Exception {
        Files.createDirectories(data.resolve("lang"));
        return Files.writeString(data.resolve("lang/" + name + ".yml"), content, StandardCharsets.UTF_8);
    }

    private static void assertNoEvents(Component text) {
        assertNull(text.clickEvent());
        assertNull(text.hoverEvent());
        text.children().forEach(LanguageTest::assertNoEvents);
    }

    private static void assertNoRed(Component text) {
        assertNotEquals(NamedTextColor.RED, text.color());
        text.children().forEach(LanguageTest::assertNoRed);
    }

    private static String clickValue(Component text) {
        if (text.clickEvent() != null) return text.clickEvent().value();
        for (Component child : text.children()) {
            String value = clickValue(child);
            if (value != null) return value;
        }
        return null;
    }
}
