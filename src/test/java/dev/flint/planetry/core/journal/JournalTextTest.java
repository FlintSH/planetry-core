package dev.flint.planetry.core.journal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class JournalTextTest {
    @Test void untrustedNamesStayLiteralAcrossMiniMessageAndLegacyTemplates() {
        String name = "<click:run_command:'/op someone'><red>Alice</red></click>&a";
        Component result = JournalText.render("&aUnlocked {player}", Map.of("player", name), Map.of());
        assertEquals("Unlocked " + name, PlainTextComponentSerializer.plainText().serialize(result));
        assertNoClick(result);
    }
    @Test void trustedItemHoverAndOpenLinkSurviveComponentSubstitution() {
        Component item = Component.text("Moon crystal").hoverEvent(Component.text("Item lore"));
        Component link = Component.text("[OPEN]").clickEvent(ClickEvent.runCommand("/journal mining"));
        Component result = JournalText.render("<green>{item} {open}", Map.of(), Map.of("item", item, "open", link));
        assertEquals("Moon crystal [OPEN]", PlainTextComponentSerializer.plainText().serialize(result));
        assertTrue(containsClick(result)); assertTrue(containsHover(result));
    }
    private static void assertNoClick(Component component) { assertNull(component.clickEvent()); component.children().forEach(JournalTextTest::assertNoClick); }
    private static boolean containsClick(Component component) { return component.clickEvent() != null || component.children().stream().anyMatch(JournalTextTest::containsClick); }
    private static boolean containsHover(Component component) { return component.hoverEvent() != null || component.children().stream().anyMatch(JournalTextTest::containsHover); }
}
