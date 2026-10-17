package dev.flint.planetry.core.storage;

import dev.flint.planetry.core.progression.Progress;
import java.util.Objects;
import java.util.UUID;

public record RewardRecord(int level, RewardState state, UUID claimId, String serverId) {
    public RewardRecord {
        Objects.requireNonNull(state, "state");
        if (level < 1 || level > Progress.MAX_LEVEL) {
            throw new IllegalArgumentException("Invalid reward level");
        }
        if (state == RewardState.QUEUED) {
            if (claimId != null || serverId != null) {
                throw new IllegalArgumentException("Queued rewards must not have a claim");
            }
        } else {
            Objects.requireNonNull(claimId, "claimId");
            validateServerId(serverId);
        }
    }

    public static void validateServerId(String serverId) {
        if (serverId == null || serverId.isBlank() || serverId.length() > 128) {
            throw new IllegalArgumentException("Server ID must contain 1 to 128 characters");
        }
    }
}
