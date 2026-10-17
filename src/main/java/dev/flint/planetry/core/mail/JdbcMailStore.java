package dev.flint.planetry.core.mail;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared mail and network presence. Transactions are never automatically retried after uncertain commits. */
public final class JdbcMailStore implements MailStore {
    private static final String COLUMNS = "mail_uuid, recipient_uuid, sender, command, created_at, state, claim_id, server_id";
    private final ConnectionFactory connections;
    private final JdbcProgressStore.Dialect dialect;
    private final String directory;
    private final String presence;
    private final String mail;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdbcMailStore(ConnectionFactory factory, JdbcProgressStore.Dialect dialect, String tablePrefix) throws IOException {
        connections = Objects.requireNonNull(factory, "factory");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (tablePrefix == null || !tablePrefix.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")) {
            throw new IllegalArgumentException("Table prefix must start with a letter or underscore and contain at most 40 letters, digits or underscores");
        }
        directory = tablePrefix + "mail_directory";
        presence = tablePrefix + "mail_presence";
        mail = tablePrefix + "mail";
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
                throw new IOException("Mail storage requires PostgreSQL 14+ or MariaDB 10.11+ matching database.type");
            }
            String schema = "planetry-core-mail:" + connection.getCatalog() + ":" + mail.toLowerCase(Locale.ROOT);
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
                            // Concurrent fresh backends must serialize catalog creation, not only data writes.
                        }
                    }
                    createTables(connection);
                    connection.commit();
                } catch (SQLException | RuntimeException | Error error) {
                    rollback(connection, error);
                    throw error;
                }
            } else {
                initializeMariadb(connection, "planetry-core-mail:" + lockId);
            }
        } catch (SQLException error) {
            throw new IOException("Cannot initialize mail database", error);
        }
    }

    private void initializeMariadb(Connection connection, String lockName) throws SQLException {
        connection.setAutoCommit(true);
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            statement.setString(1, lockName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) {
                    throw new SQLException("Cannot acquire mail schema lock within 10 seconds", "HYT00");
                }
            }
        }
        SQLException failure = null;
        try {
            // MariaDB DDL commits implicitly; use a connection-scoped schema lock.
            createTables(connection);
        } catch (SQLException error) {
            failure = error;
            throw error;
        } finally {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, lockName);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) {
                        throw new SQLException("Cannot release mail schema lock");
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

    private void createTables(Connection connection) throws SQLException {
        String engine = dialect == JdbcProgressStore.Dialect.MARIADB ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + directory + " ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(16) NOT NULL, name_key VARCHAR(16) NOT NULL)" + engine);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + presence + " ("
                    + "player_uuid VARCHAR(36) NOT NULL, session_uuid VARCHAR(36) NOT NULL, expires_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (player_uuid, session_uuid))" + engine);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + mail + " ("
                    + "mail_uuid VARCHAR(36) NOT NULL, recipient_uuid VARCHAR(36) NOT NULL, "
                    + "sender VARCHAR(1024) NOT NULL, command VARCHAR(2048) NOT NULL, created_at BIGINT NOT NULL, "
                    + "state VARCHAR(16) NOT NULL, claim_id VARCHAR(36), server_id VARCHAR(128), "
                    + "PRIMARY KEY (recipient_uuid, mail_uuid), UNIQUE (claim_id), CHECK (created_at >= 0), "
                    + "CHECK ((state = 'PENDING' AND claim_id IS NULL AND server_id IS NULL) OR "
                    + "(state IN ('CLAIMED', 'REVIEW') AND claim_id IS NOT NULL AND server_id IS NOT NULL)))" + engine);
        }
    }

    private String clock() {
        return dialect == JdbcProgressStore.Dialect.POSTGRESQL
                ? "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
                : "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
    }

    @Override
    public void register(UUID id, String name, UUID session) throws IOException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        MailValidation.name(name);
        transaction(connection -> {
            String conflict = dialect == JdbcProgressStore.Dialect.POSTGRESQL
                    ? " ON CONFLICT (player_uuid) DO UPDATE SET player_name = EXCLUDED.player_name, name_key = EXCLUDED.name_key"
                    : " ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), name_key = VALUES(name_key)";
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + directory
                    + " (player_uuid, player_name, name_key) VALUES (?, ?, ?)" + conflict)) {
                statement.setString(1, id.toString());
                statement.setString(2, name);
                statement.setString(3, name.toLowerCase(Locale.ROOT));
                statement.executeUpdate();
            }
            conflict = dialect == JdbcProgressStore.Dialect.POSTGRESQL
                    ? " ON CONFLICT (player_uuid, session_uuid) DO UPDATE SET expires_at = EXCLUDED.expires_at"
                    : " ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)";
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + presence
                    + " (player_uuid, session_uuid, expires_at) VALUES (?, ?, " + clock() + " + " + PRESENCE_TTL_MILLIS + ")" + conflict)) {
                statement.setString(1, id.toString());
                statement.setString(2, session.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void heartbeat(Map<UUID, UUID> sessions) throws IOException {
        Map<UUID, UUID> snapshot = MailValidation.sessions(sessions);
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + presence
                    + " SET expires_at = " + clock() + " + " + PRESENCE_TTL_MILLIS
                    + " WHERE player_uuid = ? AND session_uuid = ?")) {
                // Stable lock order avoids deadlocks when local snapshots overlap during transfers.
                for (UUID id : snapshot.keySet().stream().sorted().toList()) {
                    statement.setString(1, id.toString());
                    statement.setString(2, snapshot.get(id).toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void disconnect(UUID id, UUID session) throws IOException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + presence
                    + " WHERE player_uuid = ? AND session_uuid = ?")) {
                statement.setString(1, id.toString());
                statement.setString(2, session.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<UUID> findPlayer(String name) throws IOException {
        MailValidation.name(name);
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM " + directory
                    + " WHERE name_key = ? LIMIT 2")) {
                statement.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    UUID id = readUuid(rows.getString(1));
                    if (rows.next()) {
                        throw new IOException("Ambiguous player name; use the player's UUID");
                    }
                    return Optional.of(id);
                }
            }
        });
    }

    @Override
    public int send(Optional<UUID> recipient, String sender, String command) throws IOException {
        Objects.requireNonNull(recipient, "recipient");
        MailValidation.sender(sender);
        MailValidation.command(command);
        return transaction(connection -> {
            List<UUID> recipients = new ArrayList<>();
            if (recipient.isPresent()) {
                recipients.add(recipient.orElseThrow());
            } else {
                // Session rows are independent during transfers. Snapshot each player exactly once.
                try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT player_uuid FROM " + presence
                        + " WHERE expires_at > " + clock()); ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        recipients.add(readUuid(rows.getString(1)));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + mail
                    + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, " + clock() + ", 'PENDING', NULL, NULL)")) {
                for (UUID id : recipients) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, id.toString());
                    statement.setString(3, sender);
                    statement.setString(4, command);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return recipients.size();
        });
    }

    @Override
    public List<MailRecord> pending(UUID player, int limit) throws IOException { return list(player, limit, true); }

    @Override
    public List<MailRecord> pendingAfter(UUID player, MailRecord after, int limit) throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(after, "after");
        MailValidation.limit(limit);
        if (!player.equals(after.recipient())) throw new IllegalArgumentException("Cursor belongs to another player");
        return transaction(connection -> {
            List<MailRecord> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + mail
                    + " WHERE recipient_uuid = ? AND state = 'PENDING'"
                    + " AND (created_at > ? OR (created_at = ? AND mail_uuid > ?))"
                    + " ORDER BY created_at, mail_uuid LIMIT ?")) {
                statement.setString(1, player.toString());
                statement.setLong(2, after.createdAt());
                statement.setLong(3, after.createdAt());
                statement.setString(4, after.id().toString());
                statement.setInt(5, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(readMail(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public List<MailRecord> review(UUID player, int limit) throws IOException { return list(player, limit, false); }

    private List<MailRecord> list(UUID player, int limit, boolean pending) throws IOException {
        Objects.requireNonNull(player, "player");
        MailValidation.limit(limit);
        return transaction(connection -> {
            List<MailRecord> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + mail
                    + " WHERE recipient_uuid = ? AND state " + (pending ? "= 'PENDING'" : "<> 'PENDING'")
                    + " ORDER BY created_at, mail_uuid LIMIT ?")) {
                statement.setString(1, player.toString());
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readMail(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<MailRecord> claim(UUID player, UUID mailId, String serverId) throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mailId, "mailId");
        MailValidation.server(serverId);
        return transaction(connection -> {
            Optional<MailRecord> existing = lockMail(connection, player, mailId);
            if (existing.isEmpty() || existing.orElseThrow().state() != MailState.PENDING) {
                return Optional.empty();
            }
            MailRecord previous = existing.orElseThrow();
            UUID claimId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + mail
                    + " SET state = 'CLAIMED', claim_id = ?, server_id = ?"
                    + " WHERE recipient_uuid = ? AND mail_uuid = ? AND state = 'PENDING'")) {
                statement.setString(1, claimId.toString());
                statement.setString(2, serverId);
                statement.setString(3, player.toString());
                statement.setString(4, mailId.toString());
                requireOne(statement.executeUpdate());
            }
            return Optional.of(new MailRecord(mailId, player, previous.sender(), previous.command(), previous.createdAt(),
                    MailState.CLAIMED, claimId, serverId));
        });
    }

    @Override
    public void complete(UUID player, UUID mailId, UUID claimId, boolean success) throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mailId, "mailId");
        Objects.requireNonNull(claimId, "claimId");
        transaction(connection -> {
            MailRecord record = lockMail(connection, player, mailId)
                    .orElseThrow(() -> new IOException("Unknown or already completed mail"));
            if (record.state() == MailState.PENDING || !claimId.equals(record.claimId())) {
                throw new IOException("Unknown mail claim");
            }
            if (!success && record.state() == MailState.REVIEW) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement((success ? "DELETE FROM " + mail
                    : "UPDATE " + mail + " SET state = 'REVIEW'")
                    + " WHERE recipient_uuid = ? AND mail_uuid = ? AND claim_id = ?")) {
                statement.setString(1, player.toString());
                statement.setString(2, mailId.toString());
                statement.setString(3, claimId.toString());
                requireOne(statement.executeUpdate());
            }
            return null;
        });
    }

    @Override
    public void acknowledge(UUID player, UUID mailId) throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mailId, "mailId");
        transaction(connection -> {
            MailRecord record = lockMail(connection, player, mailId)
                    .orElseThrow(() -> new IOException("Unknown or already completed mail"));
            if (record.state() == MailState.PENDING) {
                throw new IOException("Pending mail cannot be acknowledged");
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + mail
                    + " WHERE recipient_uuid = ? AND mail_uuid = ? AND state <> 'PENDING'")) {
                statement.setString(1, player.toString());
                statement.setString(2, mailId.toString());
                requireOne(statement.executeUpdate());
            }
            return null;
        });
    }

    private Optional<MailRecord> lockMail(Connection connection, UUID player, UUID id) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + mail
                + " WHERE recipient_uuid = ? AND mail_uuid = ? FOR UPDATE")) {
            statement.setString(1, player.toString());
            statement.setString(2, id.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readMail(rows)) : Optional.empty();
            }
        }
    }

    private static MailRecord readMail(ResultSet rows) throws SQLException, IOException {
        try {
            String claim = rows.getString("claim_id");
            return new MailRecord(readUuid(rows.getString("mail_uuid")), readUuid(rows.getString("recipient_uuid")),
                    rows.getString("sender"), rows.getString("command"), rows.getLong("created_at"),
                    MailState.valueOf(rows.getString("state")), claim == null ? null : readUuid(claim), rows.getString("server_id"));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Corrupt mail database row; data was preserved", error);
        }
    }

    private static UUID readUuid(String value) throws IOException {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw new IllegalArgumentException("Invalid UUID encoding");
            }
            return id;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Corrupt UUID in mail database; data was preserved", error);
        }
    }

    private <T> T transaction(SqlAction<T> action) throws IOException {
        if (closed.get()) {
            throw new IOException("Mail store is closed");
        }
        try (Connection connection = connections.open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | IOException | RuntimeException | Error error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("Mail database transaction failed; do not retry uncertain console effects", error);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException error) {
            failure.addSuppressed(error);
        }
    }

    private static void requireOne(int count) throws IOException {
        if (count != 1) {
            throw new IOException("Mail write did not affect exactly one expected row");
        }
    }

    @Override
    public void close() { closed.set(true); }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException, IOException;
    }
}
