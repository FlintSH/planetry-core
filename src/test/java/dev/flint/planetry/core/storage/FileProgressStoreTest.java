package dev.flint.planetry.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FileProgressStoreTest extends ProgressStoreContract {
    @TempDir Path directory;

    @Override
    protected ProgressStore openStore() throws IOException {
        return new FileProgressStore(directory);
    }

    @Test
    void aSecondServerCannotOpenTheSameDirectoryUntilTheFirstCloses() throws Exception {
        try (ProgressStore first = openStore()) {
            first.mutate(player, Operation.GIVE, 123);
            assertThrows(IOException.class, this::openStore);
        }
        try (ProgressStore reopened = openStore()) {
            assertEquals(new Progress(1, 23, 1), reopened.get(player));
        }
    }

    @Test
    void aClosedStoreCannotReadMutateOrClaim() throws Exception {
        ProgressStore store = openStore();
        store.close();
        store.close();
        assertThrows(IOException.class, () -> store.get(player));
        assertThrows(IOException.class, () -> store.mutate(player, Operation.GIVE, 100));
        assertThrows(IOException.class, () -> store.claimNextReward(player, "survival"));
        assertThrows(IOException.class, () -> store.rewardsNeedingReview(player));
    }

    @ParameterizedTest
    @MethodSource("corruptFiles")
    void corruptProgressIsPreservedAndCannotBeOverwrittenByAMutation(String corrupt) throws Exception {
        Path file = directory.resolve(player + ".progress");
        Files.writeString(file, corrupt);

        try (ProgressStore store = openStore()) {
            assertThrows(IOException.class, () -> store.get(player));
            assertThrows(IOException.class, () -> store.mutate(player, Operation.GIVE, 1));
            assertThrows(IOException.class, () -> store.claimNextReward(player, "survival"));
            assertEquals(corrupt, Files.readString(file));
        }
    }

    static Stream<String> corruptFiles() {
        String header = "planetry-core-progress:1\n";
        String claim = "e40424f1-f3b3-4dfb-b13b-7642acb0fa98";
        return Stream.of(
                "",
                "planetry-core-progress:99\nprogress:0:0:0\n",
                header,
                header + "progress:1:100:1\n",
                header + "progress:2:0:1\n",
                header + "progress:1:0:1\nreward:1:QUEUED::\nreward:1:QUEUED::\n",
                header + "progress:1:0:1\nreward:2:QUEUED::\n",
                header + "progress:1:0:1\nreward:1:CLAIMED:invalid-uuid:c3Vydml2YWw\n",
                header + "progress:1:0:1\nreward:1:QUEUED:" + claim + ":c3Vydml2YWw\n",
                header + "progress:1:0:1\nreward:1:CLAIMED::c3Vydml2YWw\n",
                header + "progress:1:0:1\nreward:1:CLAIMED:" + claim + ":!invalid!\n",
                header + "progress:1:0:1\nreward:1:CLAIMED:" + claim + ":_w\n");
    }

    @Test
    void aPlayerFileSymlinkCannotRedirectReadsOrWritesOutsideTheStore() throws Exception {
        Path outside = Files.createTempFile(directory.getParent(), "planetry-progress-target-", ".progress");
        String original = "planetry-core-progress:1\nprogress:0:0:0\n";
        try {
            Files.writeString(outside, original);
            Files.createSymbolicLink(directory.resolve(player + ".progress"), outside);
            try (ProgressStore store = openStore()) {
                assertThrows(IOException.class, () -> store.get(player));
                assertThrows(IOException.class, () -> store.mutate(player, Operation.GIVE, 100));
                assertEquals(original, Files.readString(outside));
                assertTrue(Files.isSymbolicLink(directory.resolve(player + ".progress")));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
