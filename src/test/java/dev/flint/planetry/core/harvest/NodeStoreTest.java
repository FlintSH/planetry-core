package dev.flint.planetry.core.harvest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeStoreTest {
    @TempDir Path folder;
    private NodeStore.Node node() {
        return new NodeStore.Node(new NodeStore.Key(UUID.randomUUID(), -18, 64, 20), "ore", "minecraft:obsidian", "minecraft:obsidian", "", 0, 0, false);
    }
    @Test void bindingsAndWriteAheadCooldownIntentsSurviveRestart() throws Exception {
        var original = node(); var claimed = original.intent("minecraft:bedrock", 100, 3100);
        try (var store = new NodeStore(folder)) { store.replace(Map.of(original.key(), original)); store.replace(Map.of(claimed.key(), claimed)); }
        try (var store = new NodeStore(folder)) {
            assertEquals(claimed, store.nodes().get(claimed.key()));
            assertTrue(store.nodes().get(claimed.key()).cooling());
            assertEquals("minecraft:obsidian", claimed.previous());
            store.replace(Map.of(claimed.key(), claimed.confirmed()));
        }
        try (var store = new NodeStore(folder)) { assertEquals("", store.nodes().get(claimed.key()).previous()); }
    }
    @Test void corruptionFailsClosedAndFailedOpenReleasesLock() throws Exception {
        var node = node();
        try (var store = new NodeStore(folder)) { store.replace(Map.of(node.key(), node)); }
        byte[] bytes = Files.readAllBytes(folder.resolve("harvest-nodes.dat")); bytes[bytes.length - 2] ^= 1;
        Files.write(folder.resolve("harvest-nodes.dat"), bytes);
        assertThrows(IOException.class, () -> new NodeStore(folder));
        assertThrows(IOException.class, () -> new NodeStore(folder));
        Files.delete(folder.resolve("harvest-nodes.dat"));
        try (var store = new NodeStore(folder)) { assertTrue(store.nodes().isEmpty()); }
    }
    @Test void removalIsDurableAndSnapshotsCannotBeMutated() throws Exception {
        var node = node();
        try (var store = new NodeStore(folder)) {
            store.replace(Map.of(node.key(), node));
            assertThrows(UnsupportedOperationException.class, () -> store.nodes().clear());
            store.replace(Map.of());
        }
        try (var store = new NodeStore(folder)) { assertTrue(store.nodes().isEmpty()); }
    }
    @Test void concurrentOpenCannotWriteTheSameBackendState() throws Exception {
        try (var ignored = new NodeStore(folder)) { assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> new NodeStore(folder)); }
        try (var ignored = new NodeStore(folder)) { assertNotNull(ignored); }
    }
    @Test void invalidLocationsAndCooldownsCannotEnterTheJournal() {
        assertThrows(IllegalArgumentException.class, () -> new NodeStore.Key(UUID.randomUUID(), 30_000_001, 64, 0));
        var original = node();
        assertThrows(IllegalArgumentException.class, () -> original.intent("minecraft:air", 100, 100));
        assertTrue(original.suspend().suspended());
    }
}
