package dev.flint.planetry.core.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMailStoreTest extends MailStoreContract {
    @TempDir Path directory;

    @Override
    protected MailStore openStore() throws IOException { return new FileMailStore(directory); }

    @Test
    void restartDropsPresenceWithoutLosingTheDirectoryOrMail() throws Exception {
        UUID session = UUID.randomUUID();
        try (MailStore store = openStore()) {
            store.register(player, "Player", session);
            assertEquals(1, store.send(Optional.empty(), "PLANETRY", "say saved"));
        }
        try (MailStore store = openStore()) {
            store.heartbeat(Map.of(player, session));
            assertEquals(0, store.send(Optional.empty(), "PLANETRY", "say restarted"));
            assertEquals(Optional.of(player), store.findPlayer("Player"));
            assertEquals(1, store.pending(player, 10).size());
        }
    }

    @Test
    void directoryCannotBeOpenedByTwoStoreInstances() throws Exception {
        try (MailStore store = openStore()) {
            assertThrows(IOException.class, this::openStore);
            store.send(Optional.of(player), "PLANETRY", "say still-open");
        }
        try (MailStore store = openStore()) {
            assertEquals(1, store.pending(player, 10).size());
        }
    }

    @Test
    void corruptStateIsPreservedAndConstructorReleasesItsLock() throws Exception {
        Path path = directory.resolve("mail.store");
        String corrupt = "planetry-core-mail:1\nmail:broken\n";
        Files.writeString(path, corrupt);
        assertThrows(IOException.class, this::openStore);
        assertEquals(corrupt, Files.readString(path));
        Files.writeString(path, "planetry-core-mail:1\n");
        try (MailStore store = openStore()) {
            assertTrue(store.pending(player, 1).isEmpty());
        }
    }

    @Test
    void failedSnapshotReplacementNeverReturnsAPartialSendAll() throws Exception {
        Path path = directory.resolve("mail.store");
        Path saved = directory.resolve("saved-before-failure");
        try (MailStore store = openStore()) {
            store.register(player, "First", UUID.randomUUID());
            store.register(UUID.randomUUID(), "Second", UUID.randomUUID());
            Files.move(path, saved);
            Files.createDirectory(path);
            Files.writeString(path.resolve("block-replacement"), "keep");
            assertThrows(IOException.class, () -> store.send(Optional.empty(), "PLANETRY", "say atomic"));
            assertThrows(IOException.class, () -> store.send(Optional.of(player), "PLANETRY", "say no-retry"));
            Files.delete(path.resolve("block-replacement"));
            Files.delete(path);
            Files.move(saved, path);
        }
        try (MailStore store = openStore()) {
            assertTrue(store.pending(player, 10).isEmpty());
            assertEquals(Optional.of(player), store.findPlayer("First"));
        }
    }
}
