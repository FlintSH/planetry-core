package dev.flint.planetry.core.storage;

import java.util.UUID;

public record RewardClaim(UUID claimId, int level, String serverId) {}
