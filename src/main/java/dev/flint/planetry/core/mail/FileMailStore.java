package dev.flint.planetry.core.mail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Single-server storage. A complete snapshot makes send-all atomic, including across restarts. */
public final class FileMailStore implements MailStore {
    private static final String HEADER = "planetry-core-mail:1";
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final Comparator<MailRecord> ORDER = Comparator.comparingLong(MailRecord::createdAt)
            .thenComparing(mail -> mail.id().toString());
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final Map<UUID, UUID> presence = new HashMap<>();
    private Snapshot snapshot;
    private boolean closed;
    private IOException writeFailure;

    public FileMailStore(Path directory) throws IOException {
        Files.createDirectories(Objects.requireNonNull(directory, "directory"));
        this.directory = directory.toRealPath();
        FileChannel channel = FileChannel.open(this.directory.resolve(".mail.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) {
                throw new IOException("Mail directory is already in use");
            }
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Cannot exclusively lock mail directory; use JDBC for multiple servers", error);
        }
        lockChannel = channel;
        directoryLock = acquired;
        try {
            snapshot = read();
        } catch (IOException | RuntimeException | Error error) {
            try {
                close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @Override
    public synchronized void register(UUID id, String name, UUID session) throws IOException {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        MailValidation.name(name);
        if (!name.equals(snapshot.names.get(id))) {
            Map<UUID, String> names = new HashMap<>(snapshot.names);
            names.put(id, name);
            write(new Snapshot(names, snapshot.mail));
        }
        presence.put(id, session);
    }

    @Override
    public synchronized void heartbeat(Map<UUID, UUID> sessions) throws IOException {
        ensureOpen();
        // Local presence has no lease. Only register creates it; stale heartbeats never resurrect it.
        MailValidation.sessions(sessions);
    }

    @Override
    public synchronized void disconnect(UUID id, UUID session) throws IOException {
        ensureOpen();
        presence.remove(Objects.requireNonNull(id, "id"), Objects.requireNonNull(session, "session"));
    }

    @Override
    public synchronized Optional<UUID> findPlayer(String name) throws IOException {
        ensureOpen();
        MailValidation.name(name);
        List<UUID> matches = snapshot.names.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).toList();
        if (matches.size() > 1) {
            throw new IOException("Ambiguous player name; use the player's UUID");
        }
        return matches.stream().findFirst();
    }

    @Override
    public synchronized int send(Optional<UUID> recipient, String sender, String command) throws IOException {
        ensureOpen();
        Objects.requireNonNull(recipient, "recipient");
        MailValidation.sender(sender);
        MailValidation.command(command);
        List<UUID> recipients = recipient.map(List::of).orElseGet(() -> List.copyOf(presence.keySet()));
        if (recipients.isEmpty()) {
            return 0;
        }
        Map<UUID, MailRecord> mail = new HashMap<>(snapshot.mail);
        long now = System.currentTimeMillis();
        for (UUID target : recipients) {
            MailRecord record = new MailRecord(UUID.randomUUID(), target, sender, command, now,
                    MailState.PENDING, null, null);
            if (mail.putIfAbsent(record.id(), record) != null) {
                throw new IOException("Duplicate mail UUID; send was not saved");
            }
        }
        write(new Snapshot(snapshot.names, mail));
        return recipients.size();
    }

    @Override
    public synchronized List<MailRecord> pending(UUID player, int limit) throws IOException {
        return list(player, limit, true);
    }

    @Override
    public synchronized List<MailRecord> pendingAfter(UUID player, MailRecord after, int limit) throws IOException {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(after, "after");
        MailValidation.limit(limit);
        if (!player.equals(after.recipient())) throw new IllegalArgumentException("Cursor belongs to another player");
        return snapshot.mail.values().stream().filter(mail -> mail.recipient().equals(player)
                        && mail.state() == MailState.PENDING && ORDER.compare(mail, after) > 0)
                .sorted(ORDER).limit(limit).toList();
    }

    @Override
    public synchronized List<MailRecord> review(UUID player, int limit) throws IOException {
        return list(player, limit, false);
    }

    private List<MailRecord> list(UUID player, int limit, boolean pending) throws IOException {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        MailValidation.limit(limit);
        return snapshot.mail.values().stream().filter(mail -> mail.recipient().equals(player)
                        && (mail.state() == MailState.PENDING) == pending)
                .sorted(ORDER).limit(limit).toList();
    }

    @Override
    public synchronized Optional<MailRecord> claim(UUID player, UUID mailId, String serverId) throws IOException {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mailId, "mailId");
        MailValidation.server(serverId);
        MailRecord mail = snapshot.mail.get(mailId);
        if (mail == null || !mail.recipient().equals(player) || mail.state() != MailState.PENDING) {
            return Optional.empty();
        }
        MailRecord claimed = new MailRecord(mail.id(), player, mail.sender(), mail.command(), mail.createdAt(),
                MailState.CLAIMED, UUID.randomUUID(), serverId);
        replace(mailId, claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized void complete(UUID player, UUID mailId, UUID claimId, boolean success) throws IOException {
        ensureOpen();
        Objects.requireNonNull(claimId, "claimId");
        MailRecord mail = requireMail(player, mailId);
        if (mail.state() == MailState.PENDING || !claimId.equals(mail.claimId())) {
            throw new IOException("Unknown mail claim");
        }
        replace(mailId, success ? null : new MailRecord(mail.id(), player, mail.sender(), mail.command(),
                mail.createdAt(), MailState.REVIEW, claimId, mail.serverId()));
    }

    @Override
    public synchronized void acknowledge(UUID player, UUID mailId) throws IOException {
        ensureOpen();
        if (requireMail(player, mailId).state() == MailState.PENDING) {
            throw new IOException("Pending mail cannot be acknowledged");
        }
        replace(mailId, null);
    }

    private MailRecord requireMail(UUID player, UUID mailId) throws IOException {
        Objects.requireNonNull(player, "player");
        MailRecord mail = snapshot.mail.get(Objects.requireNonNull(mailId, "mailId"));
        if (mail == null || !mail.recipient().equals(player)) {
            throw new IOException("Unknown or already completed mail");
        }
        return mail;
    }

    private void replace(UUID id, MailRecord replacement) throws IOException {
        Map<UUID, MailRecord> mail = new HashMap<>(snapshot.mail);
        if (replacement == null) {
            mail.remove(id);
        } else {
            mail.put(id, replacement);
        }
        write(new Snapshot(snapshot.names, mail));
    }

    private Snapshot read() throws IOException {
        Path path = directory.resolve("mail.store");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(Map.of(), Map.of());
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("Invalid mail file; file was preserved");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        try {
            if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
                throw new IllegalArgumentException("Unknown mail format");
            }
            Map<UUID, String> names = new HashMap<>();
            Map<UUID, MailRecord> mail = new HashMap<>();
            for (int index = 1; index < lines.size(); index++) {
                String[] fields = lines.get(index).split(":", -1);
                if (fields.length == 3 && fields[0].equals("player")) {
                    UUID id = uuid(fields[1]);
                    String name = decode(fields[2]);
                    MailValidation.name(name);
                    if (names.putIfAbsent(id, name) != null) {
                        throw new IllegalArgumentException("Duplicate directory entry");
                    }
                } else if (fields.length == 9 && fields[0].equals("mail")) {
                    MailRecord record = new MailRecord(uuid(fields[1]), uuid(fields[2]), decode(fields[3]),
                            decode(fields[4]), Long.parseLong(fields[5]), MailState.valueOf(fields[6]),
                            fields[7].isEmpty() ? null : uuid(fields[7]), fields[8].isEmpty() ? null : decode(fields[8]));
                    if (mail.putIfAbsent(record.id(), record) != null) {
                        throw new IllegalArgumentException("Duplicate mail entry");
                    }
                } else {
                    throw new IllegalArgumentException("Invalid mail entry");
                }
            }
            return new Snapshot(names, mail);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Corrupt mail file; file was preserved", error);
        }
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid UUID encoding");
        }
        return id;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();
    }

    private void write(Snapshot next) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        next.names.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> content.append("player:")
                .append(entry.getKey()).append(':').append(encode(entry.getValue())).append('\n'));
        next.mail.values().stream().sorted(ORDER).forEach(mail -> content.append("mail:").append(mail.id()).append(':')
                .append(mail.recipient()).append(':').append(encode(mail.sender())).append(':').append(encode(mail.command()))
                .append(':').append(mail.createdAt()).append(':').append(mail.state()).append(':')
                .append(mail.claimId() == null ? "" : mail.claimId()).append(':')
                .append(mail.serverId() == null ? "" : encode(mail.serverId())).append('\n'));
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(content.toString());
        if (bytes.remaining() > MAX_FILE_BYTES) {
            throw new IOException("Mail store exceeds the supported file size");
        }
        Path temporary = Files.createTempFile(directory, ".mail-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, directory.resolve("mail.store"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
            snapshot = next;
        } catch (IOException error) {
            // A rename may already have committed. Fail closed until reopen rather than overwrite uncertain state.
            writeFailure = error;
            throw error;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed || writeFailure != null) {
            throw new IOException("Mail store is closed or requires reopening after a failed durable write", writeFailure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            presence.clear();
            try {
                directoryLock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    private record Snapshot(Map<UUID, String> names, Map<UUID, MailRecord> mail) {}
}
