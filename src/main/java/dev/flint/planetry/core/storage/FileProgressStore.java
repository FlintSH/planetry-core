package dev.flint.planetry.core.storage;

import dev.flint.planetry.core.progression.MutationResult;
import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import dev.flint.planetry.core.progression.Progression;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Atomic per-player files for one server only. Use JDBC for a shared network. */
public final class FileProgressStore implements ProgressStore {
    private static final String HEADER = "planetry-core-progress:1";
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private boolean closed;

    public FileProgressStore(Path directory) throws IOException {
        Files.createDirectories(Objects.requireNonNull(directory, "directory"));
        this.directory = directory.toRealPath();
        FileChannel channel = FileChannel.open(this.directory.resolve(".store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) {
                throw new IOException("Progress directory is already in use; file storage is single-server only");
            }
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Cannot exclusively lock progress directory; use JDBC for multiple servers", error);
        }
        lockChannel = channel;
        directoryLock = acquired;
    }

    @Override
    public synchronized Progress get(UUID playerId) throws IOException {
        ensureOpen();
        return read(playerId).progress;
    }

    @Override
    public synchronized MutationResult mutate(UUID playerId, Operation operation, long amount) throws IOException {
        ensureOpen();
        Snapshot snapshot = read(playerId);
        MutationResult result = Progression.apply(snapshot.progress, operation, amount);
        for (int level : result.newlyRewardedLevels()) {
            snapshot.rewards.put(level, new RewardRecord(level, RewardState.QUEUED, null, null));
        }
        if (!result.current().equals(result.previous()) || !result.newlyRewardedLevels().isEmpty()) {
            write(playerId, new Snapshot(result.current(), snapshot.rewards));
        }
        return result;
    }

    @Override
    public synchronized Optional<RewardClaim> claimNextReward(UUID playerId, String serverId) throws IOException {
        ensureOpen();
        RewardRecord.validateServerId(serverId);
        Snapshot snapshot = read(playerId);
        for (RewardRecord reward : snapshot.rewards.values()) {
            if (reward.state() == RewardState.QUEUED) {
                UUID claimId = UUID.randomUUID();
                snapshot.rewards.put(reward.level(), new RewardRecord(reward.level(), RewardState.CLAIMED,
                        claimId, serverId));
                write(playerId, snapshot);
                return Optional.of(new RewardClaim(claimId, reward.level(), serverId));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void completeReward(UUID playerId, UUID claimId, RewardOutcome outcome) throws IOException {
        ensureOpen();
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(outcome, "outcome");
        Snapshot snapshot = read(playerId);
        RewardRecord reward = snapshot.rewards.values().stream()
                .filter(record -> claimId.equals(record.claimId())).findFirst()
                .orElseThrow(() -> new IOException("Unknown or already completed reward claim"));
        if (outcome == RewardOutcome.COMPLETE) {
            snapshot.rewards.remove(reward.level());
        } else {
            snapshot.rewards.put(reward.level(), new RewardRecord(reward.level(), RewardState.REVIEW,
                    reward.claimId(), reward.serverId()));
        }
        write(playerId, snapshot);
    }

    @Override
    public synchronized List<RewardRecord> rewardsNeedingReview(UUID playerId) throws IOException {
        ensureOpen();
        return read(playerId).rewards.values().stream()
                .filter(reward -> reward.state() != RewardState.QUEUED).toList();
    }

    private Snapshot read(UUID playerId) throws IOException {
        Path path = path(playerId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(Progress.initial(), new TreeMap<>());
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("Invalid progress file for " + playerId);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        try {
            if (lines.size() < 2 || !HEADER.equals(lines.getFirst())) {
                throw new IllegalArgumentException("Unknown progress format");
            }
            String[] fields = lines.get(1).split(":", -1);
            if (fields.length != 4 || !"progress".equals(fields[0])) {
                throw new IllegalArgumentException("Missing progress state");
            }
            Progress progress = new Progress(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]));
            TreeMap<Integer, RewardRecord> rewards = new TreeMap<>();
            for (int index = 2; index < lines.size(); index++) {
                String[] entry = lines.get(index).split(":", -1);
                if (entry.length != 5 || !"reward".equals(entry[0])) {
                    throw new IllegalArgumentException("Invalid reward entry");
                }
                int level = Integer.parseInt(entry[1]);
                RewardState state = RewardState.valueOf(entry[2]);
                UUID claim = entry[3].isEmpty() ? null : UUID.fromString(entry[3]);
                String server = entry[4].isEmpty() ? null
                        : StandardCharsets.UTF_8.newDecoder().decode(
                                ByteBuffer.wrap(Base64.getUrlDecoder().decode(entry[4]))).toString();
                RewardRecord reward = new RewardRecord(level, state, claim, server);
                if (level > progress.rewardHighWatermark() || rewards.putIfAbsent(level, reward) != null) {
                    throw new IllegalArgumentException("Duplicate or unearned reward entry");
                }
            }
            return new Snapshot(progress, rewards);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Corrupt progress file for " + playerId + "; file was preserved", error);
        }
    }

    private void write(UUID playerId, Snapshot snapshot) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n').append("progress:")
                .append(snapshot.progress.level()).append(':').append(snapshot.progress.experience()).append(':')
                .append(snapshot.progress.rewardHighWatermark()).append('\n');
        for (RewardRecord reward : snapshot.rewards.values()) {
            content.append("reward:").append(reward.level()).append(':').append(reward.state()).append(':');
            if (reward.claimId() != null) {
                content.append(reward.claimId());
            }
            content.append(':');
            if (reward.serverId() != null) {
                content.append(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(reward.serverId().getBytes(StandardCharsets.UTF_8)));
            }
            content.append('\n');
        }
        Path temporary = Files.createTempFile(directory, "." + playerId + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content.toString());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, path(playerId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
                directoryChannel.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path path(UUID playerId) {
        return directory.resolve(Objects.requireNonNull(playerId, "playerId") + ".progress");
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Progress store is closed");
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

    private record Snapshot(Progress progress, TreeMap<Integer, RewardRecord> rewards) {}
}
