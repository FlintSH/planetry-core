package dev.flint.planetry.core.journal.storage;

import dev.flint.planetry.core.storage.ConnectionFactory;
import dev.flint.planetry.core.storage.JdbcProgressStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared discoveries with atomic unique-key writes. Uncertain commits are never retried automatically. */
public final class JdbcJournalStore implements JournalStore {
    private final ConnectionFactory connections;
    private final JdbcProgressStore.Dialect dialect;
    private final String discoveries;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdbcJournalStore(ConnectionFactory connections, JdbcProgressStore.Dialect dialect, String prefix) throws IOException {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (prefix == null || !prefix.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")) {
            throw new IllegalArgumentException("Table prefix must start with a letter or underscore and contain at most 40 letters, digits or underscores");
        }
        discoveries = prefix + "journal_found";
        initialize();
    }

    private void initialize() throws IOException {
        try (Connection connection = connections.open()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
            int major = metadata.getDatabaseMajorVersion();
            int minor = metadata.getDatabaseMinorVersion();
            if ((dialect == JdbcProgressStore.Dialect.POSTGRESQL && (!product.contains("postgresql") || major < 14))
                    || (dialect == JdbcProgressStore.Dialect.MARIADB && (!product.contains("mariadb")
                    || major < 10 || (major == 10 && minor < 11)))) {
                throw new IOException("Journal storage requires PostgreSQL 14+ or MariaDB 10.11+ matching database.type");
            }
            String schema = "planetry-core-journal:" + connection.getCatalog() + ":" + discoveries.toLowerCase(Locale.ROOT);
            UUID lockId = UUID.nameUUIDFromBytes(schema.getBytes(StandardCharsets.UTF_8));
            if (dialect == JdbcProgressStore.Dialect.POSTGRESQL) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET LOCAL lock_timeout = '10s'");
                        statement.execute("SET LOCAL statement_timeout = '15s'");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                        statement.setLong(1, lockId.getMostSignificantBits());
                        try (ResultSet ignored = statement.executeQuery()) {
                            // Serialize catalog creation across fresh backend instances.
                        }
                    }
                    createTable(connection);
                    connection.commit();
                } catch (SQLException | RuntimeException | Error error) {
                    rollback(connection, error);
                    throw error;
                }
            } else {
                initializeMariadb(connection, "planetry-core-journal:" + lockId);
            }
        } catch (SQLException error) {
            throw new IOException("Cannot initialize journal database", error);
        }
    }

    private void initializeMariadb(Connection connection, String lockName) throws SQLException {
        connection.setAutoCommit(true);
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            statement.setString(1, lockName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) {
                    throw new SQLException("Cannot acquire journal schema lock within 10 seconds", "HYT00");
                }
            }
        }
        SQLException failure = null;
        try {
            // MariaDB DDL commits implicitly; keep the schema lock for the connection.
            createTable(connection);
        } catch (SQLException error) {
            failure = error;
            throw error;
        } finally {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, lockName);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) {
                        throw new SQLException("Cannot release journal schema lock");
                    }
                }
            } catch (SQLException error) {
                if (failure == null) {
                    throw error;
                }
                failure.addSuppressed(error);
            }
        }
    }

    private void createTable(Connection connection) throws SQLException {
        String validEntry = dialect == JdbcProgressStore.Dialect.POSTGRESQL
                ? "entry_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'"
                : "entry_id REGEXP BINARY '^[a-z0-9][a-z0-9_-]{0,63}$'";
        String engine = dialect == JdbcProgressStore.Dialect.MARIADB
                ? " ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin" : "";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + discoveries + " ("
                    + "player_uuid VARCHAR(36) NOT NULL, entry_id VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY (player_uuid, entry_id), CHECK (" + validEntry + "))" + engine);
        }
    }

    @Override
    public Set<String> found(UUID playerId) throws IOException {
        ensureOpen();
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT entry_id FROM " + discoveries + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> result = new HashSet<>();
                while (rows.next()) {
                    String entryId = rows.getString(1);
                    try {
                        JournalStore.validateEntryId(entryId);
                        if (!result.add(entryId)) {
                            throw new IllegalArgumentException("Duplicate journal entry");
                        }
                    } catch (IllegalArgumentException error) {
                        throw new IOException("Corrupt journal database row; data was preserved", error);
                    }
                }
                return Set.copyOf(result);
            }
        } catch (SQLException error) {
            throw new IOException("Cannot read journal discoveries", error);
        }
    }

    @Override
    public boolean setFound(UUID playerId, String entryId, boolean found) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        JournalStore.validateEntryId(entryId);
        return transaction(connection -> {
            String sql;
            if (found) {
                sql = "INSERT INTO " + discoveries + " (player_uuid, entry_id) VALUES (?, ?)";
                if (dialect == JdbcProgressStore.Dialect.POSTGRESQL) {
                    sql += " ON CONFLICT (player_uuid, entry_id) DO NOTHING";
                }
            } else {
                sql = "DELETE FROM " + discoveries + " WHERE player_uuid = ? AND entry_id = ?";
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, entryId);
                int changed;
                try {
                    changed = statement.executeUpdate();
                } catch (SQLException error) {
                    // Plain INSERT gives a reliable duplicate result regardless of CLIENT_FOUND_ROWS.
                    // Never use INSERT IGNORE, which could conceal unrelated write failures.
                    if (found && dialect == JdbcProgressStore.Dialect.MARIADB
                            && error.getErrorCode() == 1062 && "23000".equals(error.getSQLState())) {
                        return false;
                    }
                    throw error;
                }
                if (changed < 0 || changed > 1) {
                    throw new IOException("Journal write affected an unexpected number of rows");
                }
                return changed == 1;
            }
        });
    }

    private boolean transaction(SqlAction action) throws IOException {
        ensureOpen();
        try (Connection connection = connections.open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                boolean changed = action.run(connection);
                connection.commit();
                return changed;
            } catch (SQLException | IOException | RuntimeException | Error error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("Journal database transaction failed; commit may be uncertain", error);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException error) {
            failure.addSuppressed(error);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Journal store is closed");
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @FunctionalInterface
    private interface SqlAction {
        boolean run(Connection connection) throws SQLException, IOException;
    }
}
