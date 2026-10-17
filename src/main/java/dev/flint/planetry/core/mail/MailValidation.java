package dev.flint.planetry.core.mail;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MailValidation {
    private MailValidation() {}

    static void name(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Player name must contain 1 to 16 ASCII letters, digits or underscores");
        }
    }

    static void sender(String sender) { singleLine(sender, 1024, "Sender"); }
    static void command(String command) { singleLine(command, 2048, "Command"); }
    static void server(String server) { singleLine(server, 128, "Server ID"); }

    private static void singleLine(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                    || code == 0x2028 || code == 0x2029)) {
            throw new IllegalArgumentException(field + " must be a single line of 1 to " + maximum + " characters");
        }
    }

    static void limit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Mail limit must be positive");
        }
    }

    static Map<UUID, UUID> sessions(Map<UUID, UUID> sessions) {
        return Map.copyOf(Objects.requireNonNull(sessions, "sessions"));
    }
}
