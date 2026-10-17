package dev.flint.planetry.core.mail;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable claims precede console effects. Claimed mail is never automatically retried. */
public interface MailStore extends AutoCloseable {
    long HEARTBEAT_INTERVAL_MILLIS = 5_000;
    long PRESENCE_TTL_MILLIS = 30_000;

    void register(UUID id, String name, UUID session) throws IOException;
    void heartbeat(Map<UUID, UUID> sessions) throws IOException;
    void disconnect(UUID id, UUID session) throws IOException;
    Optional<UUID> findPlayer(String name) throws IOException;
    /** An empty recipient means one atomic snapshot of currently online network players. */
    int send(Optional<UUID> recipient, String sender, String command) throws IOException;
    List<MailRecord> pending(UUID player, int limit) throws IOException;
    /** Keyset page after a previously seen record, which may already have been delivered. */
    List<MailRecord> pendingAfter(UUID player, MailRecord after, int limit) throws IOException;
    Optional<MailRecord> claim(UUID player, UUID mailId, String serverId) throws IOException;
    void complete(UUID player, UUID mailId, UUID claimId, boolean success) throws IOException;
    /** Includes both failed deliveries and claims with uncertain external effects. */
    List<MailRecord> review(UUID player, int limit) throws IOException;
    /** Removes reviewed mail without running its command; pending mail must be claimed first. */
    void acknowledge(UUID player, UUID mailId) throws IOException;
    @Override
    void close() throws IOException;
}
