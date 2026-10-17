package dev.flint.planetry.core.mail;

import java.util.Objects;
import java.util.UUID;

public record MailRecord(UUID id, UUID recipient, String sender, String command, long createdAt,
                         MailState state, UUID claimId, String serverId) {
    public MailRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(state, "state");
        MailValidation.sender(sender);
        MailValidation.command(command);
        if (createdAt < 0) {
            throw new IllegalArgumentException("Mail creation time cannot be negative");
        }
        if (state == MailState.PENDING) {
            if (claimId != null || serverId != null) {
                throw new IllegalArgumentException("Pending mail cannot have a claim");
            }
        } else {
            Objects.requireNonNull(claimId, "claimId");
            MailValidation.server(serverId);
        }
    }
}
