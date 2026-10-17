package dev.flint.planetry.core.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The same durable delivery guarantees apply to local files and both shared database engines. */
abstract class MailStoreContract {
    protected final UUID player = UUID.randomUUID();
    protected abstract MailStore openStore() throws Exception;

    @Test
    void paginationAdvancesPastSkippedMailEvenWhenItsCursorHasBeenDelivered() throws Exception {
        try (MailStore store = openStore()) {
            for (int index = 0; index < 55; index++) {
                store.send(Optional.of(player), "PLANETRY", "say item-" + index);
            }
            List<MailRecord> all = store.pending(player, 100);
            List<MailRecord> first = store.pending(player, 50);
            assertEquals(all.subList(0, 50), first);
            MailRecord cursor = first.getLast();
            MailRecord claim = store.claim(player, cursor.id(), "backend").orElseThrow();
            store.complete(player, cursor.id(), claim.claimId(), true);
            assertEquals(all.subList(50, 55), store.pendingAfter(player, cursor, 50));
            assertEquals(54, store.pending(player, 100).size());
            assertTrue(store.pendingAfter(player, all.getLast(), 50).isEmpty());
            assertEquals(first.subList(0, 49), store.pending(player, 49));
        }
    }

    @Test
    void paginationRejectsAnotherPlayersCursorAndInvalidLimits() throws Exception {
        try (MailStore store = openStore()) {
            store.send(Optional.of(player), "PLANETRY", "say gift");
            MailRecord cursor = store.pending(player, 1).getFirst();
            assertThrows(IllegalArgumentException.class,
                    () -> store.pendingAfter(UUID.randomUUID(), cursor, 50));
            assertThrows(IllegalArgumentException.class,
                    () -> store.pendingAfter(player, cursor, 0));
        }
    }

    @Test
    void offlineUuidMailPersistsWithOriginalSenderAndCommand() throws Exception {
        MailRecord original;
        String sender = "§6[Admin] Flint ☀";
        String command = "give {player} minecraft:diamond 3";
        try (MailStore store = openStore()) {
            assertTrue(store.findPlayer("Unknown").isEmpty());
            assertEquals(1, store.send(Optional.of(player), sender, command));
            original = store.pending(player, 10).getFirst();
            assertEquals(player, original.recipient());
            assertEquals(sender, original.sender());
            assertEquals(command, original.command());
            assertEquals(MailState.PENDING, original.state());
            assertTrue(original.createdAt() > 0);
            assertTrue(store.pending(UUID.randomUUID(), 10).isEmpty());
            assertTrue(store.review(player, 10).isEmpty());
        }
        try (MailStore store = openStore()) {
            assertEquals(List.of(original), store.pending(player, 10));
        }
    }

    @Test
    void sendAllCapturesOnlyCurrentlyOnlinePlayersOnce() throws Exception {
        UUID second = UUID.randomUUID();
        UUID departed = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        UUID departedSession = UUID.randomUUID();
        try (MailStore store = openStore()) {
            assertEquals(0, store.send(Optional.empty(), "PLANETRY", "say empty"));
            store.register(player, "First", firstSession);
            store.register(player, "First", firstSession);
            store.register(second, "Second", UUID.randomUUID());
            store.register(departed, "Departed", departedSession);
            store.disconnect(departed, departedSession);
            assertEquals(2, store.send(Optional.empty(), "PLANETRY", "give {uuid} stone"));
            store.register(later, "Later", UUID.randomUUID());
            assertEquals(1, store.pending(player, 10).size());
            assertEquals(1, store.pending(second, 10).size());
            assertTrue(store.pending(departed, 10).isEmpty());
            assertTrue(store.pending(later, 10).isEmpty());
        }
    }

    @Test
    void directorySurvivesRestartAndTracksRenamesCaseInsensitively() throws Exception {
        UUID session = UUID.randomUUID();
        try (MailStore store = openStore()) {
            store.register(player, "Original", session);
            assertEquals(Optional.of(player), store.findPlayer("oRiGiNaL"));
            store.register(player, "Renamed", session);
            assertTrue(store.findPlayer("Original").isEmpty());
            store.disconnect(player, session);
        }
        try (MailStore store = openStore()) {
            assertEquals(Optional.of(player), store.findPlayer("RENAMED"));
            assertEquals(1, store.send(store.findPlayer("renamed"), "PLANETRY", "say offline"));
            assertEquals(1, store.pending(player, 10).size());
        }
    }

    @Test
    void duplicateNamesAreRejectedUntilTheDirectoryIsUnambiguous() throws Exception {
        UUID other = UUID.randomUUID();
        try (MailStore store = openStore()) {
            store.register(player, "SameName", UUID.randomUUID());
            store.register(other, "sAMEnAME", UUID.randomUUID());
            assertThrows(IOException.class, () -> store.findPlayer("samename"));
            store.register(other, "Different", UUID.randomUUID());
            assertEquals(Optional.of(player), store.findPlayer("SAMENAME"));
        }
    }

    @Test
    void oldSessionsCannotDisconnectOrResurrectNewSessions() throws Exception {
        UUID oldSession = UUID.randomUUID();
        UUID currentSession = UUID.randomUUID();
        try (MailStore store = openStore()) {
            store.register(player, "Player", oldSession);
            store.register(player, "Player", currentSession);
            store.heartbeat(Map.of(player, oldSession));
            store.disconnect(player, oldSession);
            assertEquals(1, store.send(Optional.empty(), "PLANETRY", "say current"));
            store.disconnect(player, currentSession);
            store.heartbeat(Map.of(player, oldSession));
            store.heartbeat(Map.of(player, currentSession));
            assertEquals(0, store.send(Optional.empty(), "PLANETRY", "say disconnected"));
            assertEquals(Optional.of(player), store.findPlayer("Player"));
        }
    }

    @Test
    void claimedAndFailedMailStayReviewableAcrossRestartWithoutRetry() throws Exception {
        MailRecord claimed;
        MailRecord failed;
        try (MailStore store = openStore()) {
            store.send(Optional.of(player), "Staff", "say uncertain");
            store.send(Optional.of(player), "Staff", "say failed");
            List<MailRecord> records = store.pending(player, 10);
            claimed = store.claim(player, records.get(0).id(), "survival").orElseThrow();
            MailRecord second = store.claim(player, records.get(1).id(), "creative").orElseThrow();
            store.complete(player, second.id(), second.claimId(), false);
            failed = new MailRecord(second.id(), player, second.sender(), second.command(), second.createdAt(),
                    MailState.REVIEW, second.claimId(), second.serverId());
        }
        try (MailStore store = openStore()) {
            assertTrue(store.pending(player, 10).isEmpty());
            assertEquals(List.of(claimed, failed), store.review(player, 10));
            assertTrue(store.claim(player, claimed.id(), "different-backend").isEmpty());
            assertTrue(store.claim(player, failed.id(), "different-backend").isEmpty());
            store.heartbeat(Map.of());
            assertTrue(store.claim(player, claimed.id(), "survival").isEmpty());
            assertNotNull(claimed.claimId());
        }
    }

    @Test
    void completionRequiresTheRecipientMailAndClaimIdentity() throws Exception {
        try (MailStore store = openStore()) {
            store.send(Optional.of(player), "Staff", "give {player} diamond");
            UUID mailId = store.pending(player, 1).getFirst().id();
            assertTrue(store.claim(UUID.randomUUID(), mailId, "backend").isEmpty());
            assertTrue(store.claim(player, UUID.randomUUID(), "backend").isEmpty());
            MailRecord claim = store.claim(player, mailId, "backend").orElseThrow();
            assertThrows(IOException.class, () -> store.complete(UUID.randomUUID(), mailId, claim.claimId(), true));
            assertThrows(IOException.class, () -> store.complete(player, UUID.randomUUID(), claim.claimId(), true));
            assertThrows(IOException.class, () -> store.complete(player, mailId, UUID.randomUUID(), true));
            assertEquals(List.of(claim), store.review(player, 10));
            store.complete(player, mailId, claim.claimId(), true);
            assertTrue(store.review(player, 10).isEmpty());
            assertThrows(IOException.class, () -> store.complete(player, mailId, claim.claimId(), true));
        }
        try (MailStore store = openStore()) {
            assertTrue(store.pending(player, 10).isEmpty());
            assertTrue(store.review(player, 10).isEmpty());
        }
    }

    @Test
    void acknowledgementRejectsPendingAndRemovesOnlyTheSpecifiedReviewedMail() throws Exception {
        try (MailStore store = openStore()) {
            store.send(Optional.of(player), "Staff", "say first");
            store.send(Optional.of(player), "Staff", "say second");
            List<MailRecord> pending = store.pending(player, 10);
            UUID first = pending.getFirst().id();
            UUID second = pending.get(1).id();
            assertThrows(IOException.class, () -> store.acknowledge(player, first));
            MailRecord claimed = store.claim(player, first, "backend").orElseThrow();
            assertThrows(IOException.class, () -> store.acknowledge(UUID.randomUUID(), first));
            assertThrows(IOException.class, () -> store.acknowledge(player, UUID.randomUUID()));
            store.acknowledge(player, first);
            assertThrows(IOException.class, () -> store.complete(player, first, claimed.claimId(), true));
            MailRecord failed = store.claim(player, second, "backend").orElseThrow();
            store.complete(player, second, failed.claimId(), false);
            store.complete(player, second, failed.claimId(), false);
            store.acknowledge(player, second);
            assertFalse(store.claim(player, second, "backend").isPresent());
        }
        try (MailStore store = openStore()) {
            assertTrue(store.pending(player, 10).isEmpty());
            assertTrue(store.review(player, 10).isEmpty());
        }
    }

    @Test
    @Timeout(30)
    void concurrentClaimsHaveExactlyOneDurableWinner() throws Exception {
        try (MailStore store = openStore()) {
            store.send(Optional.of(player), "PLANETRY", "give {uuid} diamond");
            UUID id = store.pending(player, 1).getFirst().id();
            List<Optional<MailRecord>> results = concurrently(8, () -> store.claim(player, id,
                    "backend-" + Thread.currentThread().threadId()));
            List<MailRecord> winners = results.stream().flatMap(Optional::stream).toList();
            assertEquals(1, winners.size());
            assertEquals(winners, store.review(player, 10));
            assertTrue(store.pending(player, 10).isEmpty());
        }
    }

    @Test
    void limitsArePositiveAndReturnStableOldestFirstPages() throws Exception {
        try (MailStore store = openStore()) {
            for (int index = 0; index < 4; index++) {
                store.send(Optional.of(player), "Staff", "say " + index);
            }
            List<MailRecord> all = store.pending(player, 10);
            assertEquals(all.subList(0, 2), store.pending(player, 2));
            for (MailRecord record : all) {
                store.claim(player, record.id(), "backend");
            }
            assertEquals(store.review(player, 10).subList(0, 2), store.review(player, 2));
            assertThrows(IllegalArgumentException.class, () -> store.pending(player, 0));
            assertThrows(IllegalArgumentException.class, () -> store.review(player, -1));
        }
    }

    @Test
    void invalidInputsDoNotChangeDirectoryPresenceOrMail() throws Exception {
        try (MailStore store = openStore()) {
            for (String name : List.of("", " ", "too_long_username1", "bad-name", "line\nname")) {
                assertThrows(IllegalArgumentException.class, () -> store.register(player, name, UUID.randomUUID()));
                assertThrows(IllegalArgumentException.class, () -> store.findPlayer(name));
            }
            for (String sender : List.of("", " ", "s".repeat(1025), "bad\nsender")) {
                assertThrows(IllegalArgumentException.class, () -> store.send(Optional.of(player), sender, "say valid"));
            }
            for (String command : List.of("", " ", "c".repeat(2049), "say one\nsay two", "say one\rsay two",
                    "say one\u0000two", "say one\u2028two")) {
                assertThrows(IllegalArgumentException.class, () -> store.send(Optional.of(player), "Staff", command));
            }
            assertThrows(NullPointerException.class, () -> store.register(null, "Player", UUID.randomUUID()));
            assertThrows(NullPointerException.class, () -> store.register(player, "Player", null));
            assertThrows(NullPointerException.class, () -> store.pending(null, 1));
            assertThrows(NullPointerException.class, () -> store.claim(player, null, "backend"));
            assertThrows(NullPointerException.class, () -> store.heartbeat(null));
            assertTrue(store.findPlayer("Player").isEmpty());
            assertTrue(store.pending(player, 10).isEmpty());
            assertEquals(0, store.send(Optional.empty(), "PLANETRY", "say empty"));
            store.send(Optional.of(player), "s".repeat(1024), "c".repeat(2048));
            UUID id = store.pending(player, 1).getFirst().id();
            for (String server : List.of("", " ", "x".repeat(129), "bad\nserver")) {
                assertThrows(IllegalArgumentException.class, () -> store.claim(player, id, server));
            }
            assertEquals(1, store.pending(player, 10).size());
        }
    }

    @Test
    void closedStoresRejectFurtherOperations() throws Exception {
        MailStore store = openStore();
        store.close();
        store.close();
        assertThrows(IOException.class, () -> store.send(Optional.of(player), "PLANETRY", "say closed"));
        assertThrows(IOException.class, () -> store.pending(player, 1));
        assertThrows(IOException.class, () -> store.register(player, "Player", UUID.randomUUID()));
        assertThrows(IOException.class, () -> store.heartbeat(Map.of()));
    }

    protected static <T> List<T> concurrently(int workers, Callable<T> action) throws Exception {
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
