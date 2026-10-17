package dev.flint.planetry.core.storage;

import dev.flint.planetry.core.progression.MutationResult;
import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import dev.flint.planetry.core.progression.Progression;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared network storage. Every write locks the player's row in a database transaction. */
public final class JdbcProgressStore implements ProgressStore {
    public enum Dialect { POSTGRESQL, MARIADB }

    private final ConnectionFactory connections;
    private final Dialect dialect;
    private final String players;
    private final String rewards;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdbcProgressStore(ConnectionFactory connections, Dialect dialect, String prefix) throws IOException {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (prefix == null || !prefix.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")) {
            throw new IllegalArgumentException("Table prefix must start with a letter or underscore and contain at most 40 letters, digits or underscores");
        }
        players = prefix + "players";
        rewards = prefix + "rewards";
        initialize();
    }

    private void initialize() throws IOException {
        try (Connection connection = connections.open()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
            int major = metadata.getDatabaseMajorVersion();
            int minor = metadata.getDatabaseMinorVersion();
            if ((dialect == Dialect.POSTGRESQL && (!product.contains("postgresql") || major < 14))
                    || (dialect == Dialect.MARIADB && (!product.contains("mariadb")
                    || major < 10 || (major == 10 && minor < 11)))) {
                throw new IOException("Storage requires PostgreSQL 14+ or MariaDB 10.11+ matching database.type");
            }
            if (dialect == Dialect.POSTGRESQL) {
                initializePostgresql(connection);
            } else {
                initializeMariadb(connection);
            }
        } catch (SQLException error) {
            throw new IOException("Cannot initialize progression database", error);
        }
    }

    private void initializePostgresql(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                statement.execute("SET LOCAL statement_timeout = '15s'");
            }
            // IF NOT EXISTS alone does not serialize concurrent PostgreSQL catalog creation.
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, schemaLockId(connection).getMostSignificantBits());
                try (ResultSet ignored = statement.executeQuery()) {
                    // The transaction-scoped lock remains held through both DDL statements and commit.
                }
            }
            createTables(connection);
            connection.commit();
        } catch (SQLException error) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
    }

    private void initializeMariadb(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        String lockName = "planetry-core:" + schemaLockId(connection);
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new SQLException("Could not acquire progression schema lock within 10 seconds", "HYT00");
                }
            }
        }
        SQLException failure = null;
        try {
            // MariaDB DDL commits implicitly, so this lock must have connection rather than transaction scope.
            createTables(connection);
        } catch (SQLException error) {
            failure = error;
            throw error;
        } finally {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, lockName);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                        throw new SQLException("Could not release progression schema lock");
                    }
                }
            } catch (SQLException releaseError) {
                if (failure == null) {
                    throw releaseError;
                }
                failure.addSuppressed(releaseError);
            }
        }
    }

    private UUID schemaLockId(Connection connection) throws SQLException {
        // Unquoted PostgreSQL identifiers fold to lowercase. Hashing also bounds MariaDB's lock name length.
        String name = "planetry-core-schema:" + connection.getCatalog() + ":" + players.toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " ("
                        + "player_uuid VARCHAR(36) PRIMARY KEY, "
                        + "level INTEGER NOT NULL, experience INTEGER NOT NULL, reward_high_watermark INTEGER NOT NULL, "
                        + "CHECK (level >= 0 AND level <= 1000000), "
                        + "CHECK (experience >= 0 AND experience < 100 AND (level < 1000000 OR experience = 0)), "
                        + "CHECK (reward_high_watermark >= level AND reward_high_watermark <= 1000000))"
                        + engineClause());
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + rewards + " ("
                        + "player_uuid VARCHAR(36) NOT NULL, reward_level INTEGER NOT NULL, "
                        + "state VARCHAR(16) NOT NULL, claim_id VARCHAR(36), server_id VARCHAR(128), "
                        + "PRIMARY KEY (player_uuid, reward_level), UNIQUE (claim_id), "
                        + "FOREIGN KEY (player_uuid) REFERENCES " + players + "(player_uuid), "
                        + "CHECK (reward_level >= 1 AND reward_level <= 1000000), "
                        + "CHECK ((state = 'QUEUED' AND claim_id IS NULL AND server_id IS NULL) OR "
                        + "(state IN ('CLAIMED', 'REVIEW') AND claim_id IS NOT NULL AND server_id IS NOT NULL)))"
                        + engineClause());
        }
    }

    private String engineClause() {
        return dialect == Dialect.MARIADB ? " ENGINE=InnoDB" : "";
    }

    @Override
    public Progress get(UUID playerId) throws IOException {
        ensureOpen();
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT level, experience, reward_high_watermark FROM " + players + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readProgress(rows) : Progress.initial();
            }
        } catch (SQLException error) {
            throw new IOException("Cannot read progression", error);
        }
    }

    @Override
    public MutationResult mutate(UUID playerId, Operation operation, long amount) throws IOException {
        return transaction(connection -> {
            Progress previous = lockPlayer(connection, playerId);
            MutationResult result = Progression.apply(previous, operation, amount);
            if (!result.current().equals(previous)) {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + players
                        + " SET level = ?, experience = ?, reward_high_watermark = ? WHERE player_uuid = ?")) {
                    statement.setInt(1, result.current().level());
                    statement.setInt(2, result.current().experience());
                    statement.setInt(3, result.current().rewardHighWatermark());
                    statement.setString(4, playerId.toString());
                    requireOne(statement.executeUpdate());
                }
            }
            if (!result.newlyRewardedLevels().isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + rewards
                        + " (player_uuid, reward_level, state, claim_id, server_id) VALUES (?, ?, 'QUEUED', NULL, NULL)")) {
                    for (int level : result.newlyRewardedLevels()) {
                        statement.setString(1, playerId.toString());
                        statement.setInt(2, level);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
            return result;
        });
    }

    @Override
    public Optional<RewardClaim> claimNextReward(UUID playerId, String serverId) throws IOException {
        RewardRecord.validateServerId(serverId);
        return transaction(connection -> {
            Progress progress = lockPlayer(connection, playerId);
            try (PreparedStatement statement = connection.prepareStatement("SELECT reward_level, state, claim_id, server_id FROM "
                    + rewards + " WHERE player_uuid = ? AND state = 'QUEUED' ORDER BY reward_level LIMIT 1 FOR UPDATE")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    RewardRecord reward = readReward(rows, progress);
                    UUID claimId = UUID.randomUUID();
                    try (PreparedStatement claim = connection.prepareStatement("UPDATE " + rewards
                            + " SET state = 'CLAIMED', claim_id = ?, server_id = ? "
                            + "WHERE player_uuid = ? AND reward_level = ? AND state = 'QUEUED'")) {
                        claim.setString(1, claimId.toString());
                        claim.setString(2, serverId);
                        claim.setString(3, playerId.toString());
                        claim.setInt(4, reward.level());
                        requireOne(claim.executeUpdate());
                    }
                    return Optional.of(new RewardClaim(claimId, reward.level(), serverId));
                }
            }
        });
    }

    @Override
    public void completeReward(UUID playerId, UUID claimId, RewardOutcome outcome) throws IOException {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(outcome, "outcome");
        transaction(connection -> {
            Progress progress = lockPlayer(connection, playerId);
            try (PreparedStatement statement = connection.prepareStatement("SELECT reward_level, state, claim_id, server_id FROM "
                    + rewards + " WHERE player_uuid = ? AND claim_id = ? FOR UPDATE")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, claimId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IOException("Unknown or already completed reward claim");
                    }
                    RewardRecord reward = readReward(rows, progress);
                    if (outcome == RewardOutcome.REVIEW && reward.state() == RewardState.REVIEW) {
                        return null;
                    }
                    String sql = outcome == RewardOutcome.COMPLETE
                            ? "DELETE FROM " + rewards + " WHERE player_uuid = ? AND reward_level = ? AND claim_id = ?"
                            : "UPDATE " + rewards + " SET state = 'REVIEW' WHERE player_uuid = ? AND reward_level = ? AND claim_id = ?";
                    try (PreparedStatement complete = connection.prepareStatement(sql)) {
                        complete.setString(1, playerId.toString());
                        complete.setInt(2, reward.level());
                        complete.setString(3, claimId.toString());
                        requireOne(complete.executeUpdate());
                    }
                }
            }
            return null;
        });
    }

    @Override
    public List<RewardRecord> rewardsNeedingReview(UUID playerId) throws IOException {
        return transaction(connection -> {
            Progress progress = lockPlayer(connection, playerId);
            List<RewardRecord> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT reward_level, state, claim_id, server_id FROM "
                    + rewards + " WHERE player_uuid = ? AND state <> 'QUEUED' ORDER BY reward_level")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readReward(rows, progress));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    private Progress lockPlayer(Connection connection, UUID playerId) throws SQLException, IOException {
        Objects.requireNonNull(playerId, "playerId");
        String conflict = dialect == Dialect.POSTGRESQL ? " ON CONFLICT (player_uuid) DO NOTHING"
                : " ON DUPLICATE KEY UPDATE player_uuid = player_uuid";
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + players
                + " (player_uuid, level, experience, reward_high_watermark) VALUES (?, 0, 0, 0)" + conflict)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT level, experience, reward_high_watermark FROM "
                + players + " WHERE player_uuid = ? FOR UPDATE")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IOException("Player row disappeared while locking progression");
                }
                return readProgress(rows);
            }
        }
    }

    private static Progress readProgress(ResultSet rows) throws SQLException, IOException {
        try {
            return new Progress(rows.getInt("level"), rows.getInt("experience"), rows.getInt("reward_high_watermark"));
        } catch (IllegalArgumentException error) {
            throw new IOException("Corrupt progression database row; data was preserved", error);
        }
    }

    private static RewardRecord readReward(ResultSet rows, Progress progress) throws SQLException, IOException {
        try {
            String claim = rows.getString("claim_id");
            RewardRecord reward = new RewardRecord(rows.getInt("reward_level"), RewardState.valueOf(rows.getString("state")),
                    claim == null ? null : UUID.fromString(claim), rows.getString("server_id"));
            if (reward.level() > progress.rewardHighWatermark()) {
                throw new IllegalArgumentException("Reward exceeds earned high-water mark");
            }
            return reward;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Corrupt reward database row; data was preserved", error);
        }
    }

    private <T> T transaction(SqlAction<T> action) throws IOException {
        ensureOpen();
        try (Connection connection = connections.open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | IOException | RuntimeException | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("Progression database transaction failed", error);
        }
    }

    private static void requireOne(int count) throws IOException {
        if (count != 1) {
            throw new IOException("Progression write did not affect exactly one expected row");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Progress store is closed");
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException, IOException;
    }
}
