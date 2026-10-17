package dev.flint.planetry.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.flint.planetry.core.mail.MailStore;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class MailPresentationTest {
    @Test void mailHasNoCompletionsEvenForStaff() {
        var service = new MailService(mock(PlanetryCore.class), mock(MailStore.class), "test", id -> true);
        var sender = mock(CommandSender.class);
        when(sender.hasPermission(MailService.PERMISSION)).thenReturn(true);
        var mail = mock(Command.class);
        when(mail.getName()).thenReturn("mail");
        var sendmail = mock(Command.class);
        when(sendmail.getName()).thenReturn("sendmail");
        try {
            assertEquals(List.of(), service.onTabComplete(sender, mail, "mail", new String[]{""}));
            assertEquals(List.of(), service.onTabComplete(sender, mail, "mail", new String[]{"p"}));
            assertEquals(List.of("player", "all", "status"), service.onTabComplete(sender, sendmail, "sendmail", new String[]{""}));
            assertEquals(List.of("player"), service.onTabComplete(sender, sendmail, "sendmail", new String[]{"p"}));
        } finally { service.close(); }
    }

    @Test void trimsRenderedRankSpacingAcrossColorsWithoutRemovingInternalSpaces() {
        for (String prefix : List.of("&6Senior Admin  &r", "<gold>Senior Admin  </gold>", "<gold>Senior <bold>Admin  </bold> </gold>", "§6Senior Admin   §r")) {
            Component rank = MailRanks.trimTrailing(Language.format(prefix));
            assertEquals("Senior Admin", plain(rank));
            assertEquals("Senior Admin MailAdmin", plain(rank.append(Component.space()).append(Component.text("MailAdmin", NamedTextColor.WHITE))));
        }
    }

    @Test void trimmingKeepsRankFormattingAndLeavesLeadingSpacingIntact() {
        Component original = Component.text(" [Admin]  ", NamedTextColor.GOLD)
                .append(Component.text("   ", NamedTextColor.RED));
        Component result = MailRanks.trimTrailing(original);
        assertEquals(" [Admin]", plain(result));
        assertEquals(NamedTextColor.GOLD, result.color());
    }

    @Test void ornateSenderSnapshotsRemainWithinDurableFieldLimits() {
        Component rank = Language.format(("&cA&bB").repeat(100)).append(Component.text(" MailAdmin"));
        String saved = MailRanks.snapshot(rank);
        assertTrue(saved.length() <= 1024);
        assertEquals("AB".repeat(100) + " MailAdmin", plain(Language.format(saved)));
        assertFalse(saved.contains("\n"));
        assertEquals("Staff Name", plain(Language.format(MailRanks.snapshot(Component.text("Staff\nName")))));
    }

    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
}
