package dev.flint.planetry.core.journal.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

abstract class JournalStoreContract {
    protected final UUID player = UUID.randomUUID();

    protected abstract JournalStore openStore() throws IOException;

    @Test
    void discoveriesAreDurableIndependentAndIdempotent() throws Exception {
        UUID otherPlayer = UUID.randomUUID();
        try (JournalStore store = openStore()) {
            assertTrue(store.found(player).isEmpty());
            assertFalse(store.setFound(player, "copper_ore", false));
            assertTrue(store.setFound(player, "copper_ore", true));
            assertFalse(store.setFound(player, "copper_ore", true));
            assertTrue(store.setFound(player, "retired-entry", true));
            assertTrue(store.setFound(otherPlayer, "copper_ore", true));
            assertEquals(Set.of("copper_ore", "retired-entry"), store.found(player));
        }
        try (JournalStore reopened = openStore()) {
            assertEquals(Set.of("copper_ore", "retired-entry"), reopened.found(player));
            assertEquals(Set.of("copper_ore"), reopened.found(otherPlayer));
            assertTrue(reopened.setFound(player, "copper_ore", false));
            assertFalse(reopened.setFound(player, "copper_ore", false));
            assertEquals(Set.of("retired-entry"), reopened.found(player));
            assertEquals(Set.of("copper_ore"), reopened.found(otherPlayer));
        }
        try (JournalStore reopened = openStore()) {
            assertEquals(Set.of("retired-entry"), reopened.found(player));
        }
    }

    @Test
    void snapshotsCannotChangeStorageOrChangeAfterLaterWrites() throws Exception {
        try (JournalStore store = openStore()) {
            store.setFound(player, "copper", true);
            Set<String> snapshot = store.found(player);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add("forged"));
            store.setFound(player, "copper", false);
            assertEquals(Set.of("copper"), snapshot);
            assertTrue(store.found(player).isEmpty());
        }
    }

    @Test
    void entryIdsAreValidatedBeforeEitherAddingOrRemoving() throws Exception {
        try (JournalStore store = openStore()) {
            assertTrue(store.setFound(player, "0", true));
            assertTrue(store.setFound(player, "a".repeat(64), true));
            for (String invalid : new String[] {null, "", "Uppercase", "-ore", "_ore", "a".repeat(65),
                    "two words", "a/b", "../ore", "ore\n", "ore\r", "ore:1", "o\u00e9", "a\u0000"}) {
                assertThrows(IllegalArgumentException.class, () -> store.setFound(player, invalid, true));
                assertThrows(IllegalArgumentException.class, () -> store.setFound(player, invalid, false));
            }
            assertThrows(NullPointerException.class, () -> store.found(null));
            assertThrows(NullPointerException.class, () -> store.setFound(null, "copper", true));
            assertEquals(Set.of("0", "a".repeat(64)), store.found(player));
        }
    }

    @Test
    void closeIsIdempotentAndRefusesFurtherReadsOrWrites() throws Exception {
        JournalStore store = openStore();
        store.close();
        store.close();
        assertThrows(IOException.class, () -> store.found(player));
        assertThrows(IOException.class, () -> store.setFound(player, "copper", true));
        assertThrows(IOException.class, () -> store.setFound(player, "copper", false));
    }

    @Test
    @Timeout(30)
    void onlyOneConcurrentCallerChangesEachDiscoveryState() throws Exception {
        try (JournalStore store = openStore()) {
            assertEquals(1, concurrently(4, () -> store.setFound(player, "copper", true)).stream()
                    .filter(Boolean::booleanValue).count());
            assertEquals(Set.of("copper"), store.found(player));
            assertEquals(1, concurrently(4, () -> store.setFound(player, "copper", false)).stream()
                    .filter(Boolean::booleanValue).count());
            assertTrue(store.found(player).isEmpty());
        }
    }

    @Test
    @Timeout(30)
    void concurrentDiscoveriesOfDifferentEntriesDoNotLoseEachOther() throws Exception {
        try (JournalStore store = openStore()) {
            AtomicInteger nextWorker = new AtomicInteger();
            concurrently(4, () -> {
                int worker = nextWorker.getAndIncrement();
                for (int entry = 0; entry < 10; entry++) {
                    assertTrue(store.setFound(player, "ore_" + worker + "_" + entry, true));
                }
                return null;
            });
            assertEquals(40, store.found(player).size());
        }
        try (JournalStore reopened = openStore()) {
            assertEquals(40, reopened.found(player).size());
        }
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
