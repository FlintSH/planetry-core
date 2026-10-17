package dev.flint.planetry.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The same persistence and reward guarantees must hold for every storage backend. */
abstract class ProgressStoreContract {
    protected final UUID player = UUID.randomUUID();

    protected abstract ProgressStore openStore() throws Exception;

    @Test
    void unknownPlayersHaveIndependentEmptyProgress() throws Exception {
        try (ProgressStore store = openStore()) {
            assertEquals(Progress.initial(), store.get(player));
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
            assertTrue(store.rewardsNeedingReview(player).isEmpty());
            store.mutate(player, Operation.GIVE, 145);
            assertEquals(Progress.initial(), store.get(UUID.randomUUID()));
        }
    }

    @Test
    void progressQueuedRewardsAndReviewDecisionsSurviveRestart() throws Exception {
        RewardClaim uncertain;
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 345);
            RewardClaim first = store.claimNextReward(player, "survival").orElseThrow();
            assertEquals(1, first.level());
            assertEquals("survival", first.serverId());
            store.completeReward(player, first.claimId(), RewardOutcome.COMPLETE);
            uncertain = store.claimNextReward(player, "creative").orElseThrow();
            assertEquals(2, uncertain.level());
            store.completeReward(player, uncertain.claimId(), RewardOutcome.REVIEW);
        }

        try (ProgressStore store = openStore()) {
            assertEquals(new Progress(3, 45, 3), store.get(player));
            assertEquals(List.of(new RewardRecord(2, RewardState.REVIEW, uncertain.claimId(), "creative")),
                    store.rewardsNeedingReview(player));
            RewardClaim last = store.claimNextReward(player, "survival").orElseThrow();
            assertEquals(3, last.level());
            assertNotEquals(uncertain.claimId(), last.claimId());
            store.completeReward(player, last.claimId(), RewardOutcome.COMPLETE);
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
        }
    }

    @Test
    void aCrashAfterClaimDoesNotAutomaticallyRetryAnUncertainPayout() throws Exception {
        RewardClaim original;
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 100);
            original = store.claimNextReward(player, "backend-a").orElseThrow();
        }

        try (ProgressStore store = openStore()) {
            assertTrue(store.claimNextReward(player, "backend-a").isEmpty());
            assertTrue(store.claimNextReward(player, "backend-b").isEmpty());
            assertEquals(List.of(new RewardRecord(1, RewardState.CLAIMED, original.claimId(), "backend-a")),
                    store.rewardsNeedingReview(player));
        }
    }

    @Test
    void completedRewardsRemainCompletedAfterLosingAndRegainingLevels() throws Exception {
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 200);
            for (int level = 1; level <= 2; level++) {
                RewardClaim claim = store.claimNextReward(player, "survival").orElseThrow();
                assertEquals(level, claim.level());
                store.completeReward(player, claim.claimId(), RewardOutcome.COMPLETE);
            }
            store.mutate(player, Operation.TAKE, Long.MAX_VALUE);
        }

        try (ProgressStore store = openStore()) {
            assertEquals(new Progress(0, 0, 2), store.get(player));
            store.mutate(player, Operation.GIVE, 200);
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
            store.mutate(player, Operation.GIVE, 100);
            assertEquals(3, store.claimNextReward(player, "survival").orElseThrow().level());
        }
    }

    @Test
    void administrativeLevelChangesDoNotCreatePayoutsOrPermitRewardFarming() throws Exception {
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 99);
            store.mutate(player, Operation.SET_LEVEL, 4);
            assertEquals(new Progress(4, 0, 4), store.get(player));
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
            store.mutate(player, Operation.SET_LEVEL, 0);
            store.mutate(player, Operation.GIVE, 400);
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
            store.mutate(player, Operation.GIVE, 100);
            assertEquals(5, store.claimNextReward(player, "survival").orElseThrow().level());
        }
    }

    @Test
    void claimCompletionIsBoundToBothPlayerAndRandomClaimIdentity() throws Exception {
        UUID otherPlayer = UUID.randomUUID();
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 100);
            store.mutate(otherPlayer, Operation.GIVE, 100);
            RewardClaim claim = store.claimNextReward(player, "survival").orElseThrow();
            RewardClaim otherClaim = store.claimNextReward(otherPlayer, "creative").orElseThrow();

            assertThrows(IOException.class,
                    () -> store.completeReward(otherPlayer, claim.claimId(), RewardOutcome.COMPLETE));
            assertThrows(IOException.class,
                    () -> store.completeReward(player, UUID.randomUUID(), RewardOutcome.COMPLETE));
            assertEquals(1, store.rewardsNeedingReview(player).size());
            assertEquals(otherClaim.claimId(), store.rewardsNeedingReview(otherPlayer).getFirst().claimId());

            store.completeReward(player, claim.claimId(), RewardOutcome.COMPLETE);
            assertTrue(store.rewardsNeedingReview(player).isEmpty());
            assertThrows(IOException.class,
                    () -> store.completeReward(player, claim.claimId(), RewardOutcome.COMPLETE));
        }
    }

    @Test
    void anExplicitlyReviewedClaimCanLaterBeCompletedWithoutChangingProgress() throws Exception {
        UUID claimId;
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 100);
            claimId = store.claimNextReward(player, "survival").orElseThrow().claimId();
            store.completeReward(player, claimId, RewardOutcome.REVIEW);
        }
        try (ProgressStore store = openStore()) {
            store.completeReward(player, claimId, RewardOutcome.COMPLETE);
            assertTrue(store.rewardsNeedingReview(player).isEmpty());
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
            assertEquals(new Progress(1, 0, 1), store.get(player));
        }
    }

    @Test
    void invalidMutationsAndServerIdentifiersDoNotChangeDurableState() throws Exception {
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 99);
            assertThrows(IllegalArgumentException.class,
                    () -> store.mutate(player, Operation.GIVE, Progress.MAX_GIVE + 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> store.mutate(player, Operation.SET_LEVEL, -1));
            for (String invalidServer : List.of("", "   ", "x".repeat(129))) {
                assertThrows(IllegalArgumentException.class,
                        () -> store.claimNextReward(player, invalidServer));
            }
            assertEquals(new Progress(0, 99, 0), store.get(player));
            assertTrue(store.claimNextReward(player, "survival").isEmpty());
        }
        try (ProgressStore store = openStore()) {
            assertEquals(new Progress(0, 99, 0), store.get(player));
        }
    }

    @Test
    @Timeout(30)
    void concurrentMutationsDoNotLoseExperienceOrDuplicateRewards() throws Exception {
        try (ProgressStore store = openStore()) {
            runConcurrently(4, () -> {
                for (int update = 0; update < 25; update++) {
                    store.mutate(player, Operation.GIVE, 7);
                }
                return null;
            });
            assertEquals(new Progress(7, 0, 7), store.get(player));
            List<Integer> levels = new ArrayList<>();
            for (var claim = store.claimNextReward(player, "survival"); claim.isPresent();
                    claim = store.claimNextReward(player, "survival")) {
                levels.add(claim.orElseThrow().level());
            }
            assertEquals(IntStream.rangeClosed(1, 7).boxed().toList(), levels);
        }
    }

    @Test
    @Timeout(30)
    void concurrentConsumersClaimEachRewardOnlyOnce() throws Exception {
        try (ProgressStore store = openStore()) {
            store.mutate(player, Operation.GIVE, 1_000);
            ConcurrentLinkedQueue<RewardClaim> claims = new ConcurrentLinkedQueue<>();
            runConcurrently(4, () -> {
                for (var claim = store.claimNextReward(player, "backend-" + Thread.currentThread().threadId());
                        claim.isPresent();
                        claim = store.claimNextReward(player, "backend-" + Thread.currentThread().threadId())) {
                    claims.add(claim.orElseThrow());
                }
                return null;
            });

            assertEquals(10, claims.size());
            assertEquals(IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toSet()),
                    claims.stream().map(RewardClaim::level).collect(Collectors.toSet()));
            Set<UUID> claimIds = claims.stream().map(RewardClaim::claimId).collect(Collectors.toSet());
            assertEquals(10, claimIds.size());
            assertEquals(10, store.rewardsNeedingReview(player).size());
            assertFalse(store.claimNextReward(player, "another-backend").isPresent());
        }
    }

    protected static <T> List<T> runConcurrently(int workers, Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    return action.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
