package dev.flint.planetry.core.journal.storage;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** Journal discoveries are independent of progression and durable before a successful write returns. */
public interface JournalStore extends AutoCloseable {
    /** Returns an immutable snapshot of the player's discovered entry IDs. */
    Set<String> found(UUID playerId) throws IOException;

    /** Atomically sets one discovery and returns true only when this call changed its stored state. */
    boolean setFound(UUID playerId, String entryId, boolean found) throws IOException;

    static void validateEntryId(String entryId) {
        if (entryId == null || !entryId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Journal entry ID must contain 1 to 64 lowercase letters, digits, underscores or hyphens and start with a letter or digit");
        }
    }

    @Override
    void close() throws IOException;
}
