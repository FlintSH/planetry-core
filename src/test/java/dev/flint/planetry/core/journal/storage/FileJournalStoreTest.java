package dev.flint.planetry.core.journal.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FileJournalStoreTest extends JournalStoreContract {
    @TempDir Path directory;

    @Override
    protected JournalStore openStore() throws IOException {
        return new FileJournalStore(directory);
    }

    @Test
    void aSecondServerCannotOpenTheDirectoryUntilTheFirstCloses() throws Exception {
        try (JournalStore first = openStore()) {
            first.setFound(player, "copper", true);
            assertThrows(IOException.class, this::openStore);
        }
        try (JournalStore reopened = openStore()) {
            assertEquals(Set.of("copper"), reopened.found(player));
        }
    }

    @ParameterizedTest
    @MethodSource("corruptFiles")
    void corruptDiscoveriesArePreservedAndCannotBeOverwritten(String corrupt) throws Exception {
        Path file = directory.resolve(player + ".journal");
        Files.writeString(file, corrupt);
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "copper", true));
            assertThrows(IOException.class, () -> store.setFound(player, "copper", false));
            assertEquals(corrupt, Files.readString(file));
        }
    }

    static Stream<String> corruptFiles() {
        String header = "planetry-core-journal:1\n";
        return Stream.of("", "planetry-core-journal:2\n", "planetry-core-journal:1", header + "\n",
                header + "copper", header + "copper\ncopper\n", header + "Copper\n",
                header + "../copper\n", header + "ore:1\n", header + "a".repeat(65) + "\n",
                header + "copper\r\n", header + "copper\nunknown data\n");
    }

    @Test
    void malformedUtf8IsPreservedAndCannotBeOverwritten() throws Exception {
        Path file = directory.resolve(player + ".journal");
        byte[] corrupt = {(byte) 0xff, (byte) 0xfe};
        Files.write(file, corrupt);
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "copper", true));
            assertArrayEquals(corrupt, Files.readAllBytes(file));
        }
    }

    @Test
    void oversizedFilesFailClosedWithoutReadingOrReplacingThem() throws Exception {
        Path file = directory.resolve(player + ".journal");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(16L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        long originalSize = Files.size(file);
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "copper", true));
            assertEquals(originalSize, Files.size(file));
        }
    }

    @Test
    void playerFileSymlinksCannotRedirectReadsOrWrites() throws Exception {
        Path outside = Files.createTempFile(directory.getParent(), "planetry-journal-target-", ".journal");
        String original = "planetry-core-journal:1\ncopper\n";
        try {
            Files.writeString(outside, original);
            Path file = directory.resolve(player + ".journal");
            Files.createSymbolicLink(file, outside);
            try (JournalStore store = openStore()) {
                assertThrows(IOException.class, () -> store.found(player));
                assertThrows(IOException.class, () -> store.setFound(player, "iron", true));
                assertThrows(IOException.class, () -> store.setFound(player, "copper", false));
                assertEquals(original, Files.readString(outside));
                assertTrue(Files.isSymbolicLink(file));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void incompleteTemporaryFilesDoNotReplaceTheLastDurableDiscoverySet() throws Exception {
        try (JournalStore store = openStore()) {
            store.setFound(player, "copper", true);
        }
        Path unfinished = directory.resolve("." + player + "-unfinished.tmp");
        Files.writeString(unfinished, "planetry-core-journal:1\nir");
        try (JournalStore reopened = openStore()) {
            assertEquals(Set.of("copper"), reopened.found(player));
            assertTrue(reopened.setFound(player, "iron", true));
        }
        try (JournalStore reopened = openStore()) {
            assertEquals(Set.of("copper", "iron"), reopened.found(player));
        }
        assertEquals("planetry-core-journal:1\nir", Files.readString(unfinished));
    }

    @Test
    void validLookingEntryCorruptionFailsTheIntegrityCheck() throws Exception {
        try (JournalStore store = openStore()) {
            store.setFound(player, "copper", true);
        }
        Path file = directory.resolve(player + ".journal");
        String corrupt = Files.readString(file).replace("copper\n", "silver\n");
        Files.writeString(file, corrupt);
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "iron", true));
            assertEquals(corrupt, Files.readString(file));
        }
    }

    @Test
    void truncationAtACompleteEntryBoundaryCannotSilentlyEraseDiscoveries() throws Exception {
        try (JournalStore store = openStore()) {
            store.setFound(player, "copper", true);
            store.setFound(player, "silver", true);
        }
        Path file = directory.resolve(player + ".journal");
        String corrupt = Files.readString(file).replace("silver\n", "");
        Files.writeString(file, corrupt);
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "iron", true));
            assertEquals(corrupt, Files.readString(file));
        }
    }

    @Test
    void filePathsThatAreDirectoriesFailClosed() throws Exception {
        Path file = directory.resolve(player + ".journal");
        Files.createDirectory(file);
        Files.writeString(file.resolve("preserved"), "existing data");
        try (JournalStore store = openStore()) {
            assertThrows(IOException.class, () -> store.found(player));
            assertThrows(IOException.class, () -> store.setFound(player, "copper", true));
            assertEquals("existing data", Files.readString(file.resolve("preserved")));
        }
    }
}
