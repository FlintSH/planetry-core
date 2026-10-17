package dev.flint.planetry.core.journal.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Atomic per-player discovery files for one server. Shared networks must use JDBC. */
public final class FileJournalStore implements JournalStore {
    private static final String HEADER = "planetry-core-journal:1";
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private boolean closed;

    public FileJournalStore(Path directory) throws IOException {
        Files.createDirectories(Objects.requireNonNull(directory, "directory"));
        this.directory = directory.toRealPath();
        FileChannel channel = FileChannel.open(this.directory.resolve(".store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                throw new IOException("Journal directory is already in use; file storage is single-server only");
            }
            directoryLock = acquired;
            lockChannel = channel;
        } catch (IOException | OverlappingFileLockException error) {
            try {
                channel.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw new IOException("Cannot exclusively lock journal directory; use JDBC for multiple servers", error);
        }
    }

    @Override
    public synchronized Set<String> found(UUID playerId) throws IOException {
        ensureOpen();
        return Set.copyOf(read(playerId));
    }

    @Override
    public synchronized boolean setFound(UUID playerId, String entryId, boolean found) throws IOException {
        ensureOpen();
        JournalStore.validateEntryId(entryId);
        TreeSet<String> discoveries = read(playerId);
        boolean changed = found ? discoveries.add(entryId) : discoveries.remove(entryId);
        if (changed) {
            write(playerId, discoveries);
        }
        return changed;
    }

    private TreeSet<String> read(UUID playerId) throws IOException {
        Path file = path(playerId);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid journal file for " + playerId);
        }
        String content;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = channel.size();
            if (length > MAX_FILE_BYTES) {
                throw new IOException("Journal file exceeds maximum size for " + playerId);
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) length);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) == -1) {
                    throw new IOException("Journal file changed while reading " + playerId);
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("Journal file changed while reading " + playerId);
            }
            bytes.flip();
            content = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
        } catch (NoSuchFileException missing) {
            return new TreeSet<>();
        }
        try {
            // The checksum also detects truncation at an otherwise valid entry boundary.
            String[] lines = content.split("\n", -1);
            if (lines.length < 3 || !HEADER.equals(lines[0]) || !lines[lines.length - 1].isEmpty()) {
                throw new IllegalArgumentException("Unknown or truncated journal format");
            }
            String checksum = lines[lines.length - 2];
            int checksumStart = content.length() - checksum.length() - 1;
            if (!checksum.equals("sha256:" + checksum(content.substring(0, checksumStart)))) {
                throw new IllegalArgumentException("Journal checksum mismatch");
            }
            TreeSet<String> discoveries = new TreeSet<>();
            for (int index = 1; index < lines.length - 2; index++) {
                String entryId = lines[index];
                JournalStore.validateEntryId(entryId);
                if (!discoveries.add(entryId)) {
                    throw new IllegalArgumentException("Duplicate journal entry");
                }
            }
            return discoveries;
        } catch (IllegalArgumentException error) {
            throw new IOException("Corrupt journal file for " + playerId + "; file was preserved", error);
        }
    }

    private void write(UUID playerId, Set<String> discoveries) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        discoveries.forEach(entryId -> content.append(entryId).append('\n'));
        String checksum = checksum(content.toString());
        content.append("sha256:").append(checksum).append('\n');
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(content.toString());
        if (bytes.remaining() > MAX_FILE_BYTES) {
            throw new IOException("Journal discoveries exceed maximum file size for " + playerId);
        }
        Path temporary = Files.createTempFile(directory, "." + playerId + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, path(playerId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path path(UUID playerId) {
        return directory.resolve(Objects.requireNonNull(playerId, "playerId") + ".journal");
    }

    private static String checksum(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Journal store is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                directoryLock.release();
            } finally {
                lockChannel.close();
            }
        }
    }
}
