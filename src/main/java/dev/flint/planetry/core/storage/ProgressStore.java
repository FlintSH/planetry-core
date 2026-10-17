package dev.flint.planetry.core.storage;

import dev.flint.planetry.core.progression.MutationResult;
import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All mutations and reward claims are durable before their methods return. */
public interface ProgressStore extends AutoCloseable {
    Progress get(UUID playerId) throws IOException;
    MutationResult mutate(UUID playerId, Operation operation, long amount) throws IOException;

    /** Claims never expire: a crash after claiming requires operator review, never automatic retry. */
    Optional<RewardClaim> claimNextReward(UUID playerId, String serverId) throws IOException;

    /** COMPLETE removes the outbox entry; REVIEW preserves an uncertain external payout. */
    void completeReward(UUID playerId, UUID claimId, RewardOutcome outcome) throws IOException;
    List<RewardRecord> rewardsNeedingReview(UUID playerId) throws IOException;

    @Override
    void close() throws IOException;
}
