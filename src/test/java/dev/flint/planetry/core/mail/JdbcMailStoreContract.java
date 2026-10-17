package dev.flint.planetry.core.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.flint.planetry.core.storage.ConnectionFactory;
import dev.flint.planetry.core.storage.JdbcProgressStore;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

abstract class JdbcMailStoreContract extends MailStoreContract {
    protected final String prefix = "mail_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";

    protected abstract JdbcProgressStore.Dialect dialect();
    protected abstract String environmentPrefix();

    protected ConnectionFactory connections() {
        String environment = environmentPrefix();
        String url = System.getenv(environment + "_URL");
        Properties properties = new Properties();
        String user = System.getenv(environment + "_USER");
        String password = System.getenv(environment + "_PASSWORD");
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return () -> DriverManager.getConnection(url, properties);
    }

    @Override
    protected MailStore openStore() throws IOException { return new JdbcMailStore(connections(), dialect(), prefix); }

    @AfterEach
    void removeOnlyThisTestsTables() throws SQLException {
        try (Connection connection = connections().open(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "mail");
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "mail_presence");
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "mail_directory");
        }
    }

    @Test
    @Timeout(30)
    void freshBackendsBootstrapTheSameMailSchemaConcurrently() throws Exception {
        CountDownLatch ready = new CountDownLatch(4);
        AtomicInteger opened = new AtomicInteger();
        ConnectionFactory simultaneous = () -> {
            Connection connection = connections().open();
            if (opened.getAndIncrement() >= 4) {
                return connection;
            }
            boolean handedOff = false;
            try {
                ready.countDown();
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("Concurrent mail constructors did not open all connections");
                }
                handedOff = true;
                return connection;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while aligning constructors", error);
            } finally {
                if (!handedOff) {
                    connection.close();
                }
            }
        };
        concurrently(4, () -> {
            try (MailStore store = new JdbcMailStore(simultaneous, dialect(), prefix)) {
                store.send(Optional.of(player), "PLANETRY", "say concurrent-start");
            }
            return true;
        });
        try (MailStore store = openStore()) {
            assertEquals(4, store.pending(player, 10).size());
        }
    }

    @Test
    @Timeout(30)
    void separateBackendsShareTheDirectoryPresenceAndOneClaimWinner() throws Exception {
        UUID other = UUID.randomUUID();
        try (MailStore first = openStore(); MailStore second = openStore()) {
            first.register(player, "First", UUID.randomUUID());
            second.register(other, "Second", UUID.randomUUID());
            assertEquals(Optional.of(other), first.findPlayer("sEcOnD"));
            assertEquals(Optional.of(player), second.findPlayer("FIRST"));
            assertEquals(2, first.send(Optional.empty(), "Staff", "say network"));
            assertEquals(1, second.pending(other, 10).size());
            UUID id = first.pending(player, 1).getFirst().id();
            AtomicInteger worker = new AtomicInteger();
            List<Optional<MailRecord>> claims = concurrently(8, () ->
                    (worker.getAndIncrement() % 2 == 0 ? first : second).claim(player, id, "backend"));
            assertEquals(1L, claims.stream().filter(Optional::isPresent).count());
            assertEquals(first.review(player, 10), second.review(player, 10));
            MailRecord claim = second.review(player, 10).getFirst();
            second.complete(player, id, claim.claimId(), true);
            assertTrue(first.review(player, 10).isEmpty());
        }
    }

    @Test
    void presenceUsesAThirtySecondDatabaseClockLeaseAndFencedRenewal() throws Exception {
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        try (MailStore first = openStore(); MailStore second = openStore()) {
            first.register(player, "Player", old);
            second.register(player, "Player", current);
            String clock = dialect() == JdbcProgressStore.Dialect.POSTGRESQL
                    ? "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
                    : "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
            try (Connection connection = connections().open(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT expires_at - " + clock + " FROM "
                            + prefix + "mail_presence WHERE player_uuid = '" + player + "'")) {
                assertTrue(rows.next());
                long remaining = rows.getLong(1);
                assertTrue(remaining > 25_000 && remaining <= 30_000, "Lease must use the DB clock and a 30-second TTL");
            }
            expirePresence(player);
            first.disconnect(player, old);
            first.heartbeat(Map.of(player, old));
            assertEquals(0, first.send(Optional.empty(), "PLANETRY", "say expired"));
            assertEquals(Optional.of(player), first.findPlayer("Player"));
            second.heartbeat(Map.of(player, current));
            assertEquals(1, first.send(Optional.empty(), "PLANETRY", "say renewed"));
            first.disconnect(player, old);
            assertEquals(1, first.send(Optional.empty(), "PLANETRY", "say still-current"));
            second.disconnect(player, current);
            second.heartbeat(Map.of(player, current));
            assertEquals(0, first.send(Optional.empty(), "PLANETRY", "say disconnected"));
        }
    }

    @Test
    void delayedSourceRegistrationAndDisconnectCannotReplaceDestinationPresence() throws Exception {
        UUID sourceSession = UUID.randomUUID();
        UUID destinationSession = UUID.randomUUID();
        try (MailStore source = openStore(); MailStore destination = openStore()) {
            destination.register(player, "Player", destinationSession);
            // The source queue drains after the destination has already completed registration.
            source.register(player, "Player", sourceSession);
            source.disconnect(player, sourceSession);
            destination.heartbeat(Map.of(player, destinationSession));
            assertEquals(1, source.send(Optional.empty(), "Staff", "say destination-still-online"));
            assertEquals(1, destination.pending(player, 10).size());
            expirePresence(player);
            destination.heartbeat(Map.of(player, destinationSession));
            assertEquals(1, source.send(Optional.empty(), "Staff", "say destination-renewed"));
            assertEquals(2, destination.pending(player, 10).size());
        }
    }

    @Test
    void overlappingBackendSessionsDeliverOneMailPerPlayerInTheNetworkSnapshot() throws Exception {
        UUID sourceSession = UUID.randomUUID();
        UUID destinationSession = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (MailStore source = openStore(); MailStore destination = openStore()) {
            source.register(player, "Player", sourceSession);
            destination.register(player, "Player", destinationSession);
            destination.register(other, "Other", UUID.randomUUID());
            source.heartbeat(Map.of(player, sourceSession));
            destination.heartbeat(Map.of(player, destinationSession));
            assertEquals(2, source.send(Optional.empty(), "Staff", "say transfer-snapshot"));
            assertEquals(1, source.pending(player, 10).size());
            assertEquals(1, destination.pending(other, 10).size());
            destination.disconnect(player, destinationSession);
            assertEquals(2, destination.send(Optional.empty(), "Staff", "say source-still-present"));
            assertEquals(2, destination.pending(player, 10).size());
        }
    }

    @Test
    void closingOneBackendDoesNotDeleteOtherBackendPresence() throws Exception {
        UUID other = UUID.randomUUID();
        try (MailStore first = openStore()) {
            first.register(player, "First", UUID.randomUUID());
            try (MailStore second = openStore()) {
                second.register(other, "Second", UUID.randomUUID());
            }
            assertEquals(2, first.send(Optional.empty(), "PLANETRY", "say crash-lease"));
            expirePresence(other);
            assertEquals(1, first.send(Optional.empty(), "PLANETRY", "say expired-backend"));
        }
    }

    @Test
    void sendAllUsesTheCapturedPresenceSetEvenWhenItChangesBeforeInsertion() throws Exception {
        UUID departing = UUID.randomUUID();
        UUID arriving = UUID.randomUUID();
        UUID departingSession = UUID.randomUUID();
        try (MailStore healthy = openStore()) {
            healthy.register(player, "First", UUID.randomUUID());
            healthy.register(departing, "Departing", departingSession);
            AtomicBoolean changePresence = new AtomicBoolean();
            ConnectionFactory changing = interceptMailBatch(() -> {
                if (changePresence.compareAndSet(true, false)) {
                    healthy.disconnect(departing, departingSession);
                    healthy.register(arriving, "Arriving", UUID.randomUUID());
                }
            }, false);
            try (MailStore store = new JdbcMailStore(changing, dialect(), prefix)) {
                changePresence.set(true);
                assertEquals(2, store.send(Optional.empty(), "Staff", "say snapshot"));
            }
            assertTrue(!changePresence.get(), "Test must change presence after the snapshot and before the batch");
            assertEquals(1, healthy.pending(player, 10).size());
            assertEquals(1, healthy.pending(departing, 10).size());
            assertTrue(healthy.pending(arriving, 10).isEmpty());
        }
    }

    @Test
    void anInsertionFailureRollsBackEveryRecipientOfSendAll() throws Exception {
        UUID other = UUID.randomUUID();
        AtomicBoolean injected = new AtomicBoolean();
        try (MailStore healthy = openStore()) {
            healthy.register(player, "First", UUID.randomUUID());
            healthy.register(other, "Second", UUID.randomUUID());
            ConnectionFactory failing = interceptMailBatch(() -> injected.set(true), true);
            try (MailStore store = new JdbcMailStore(failing, dialect(), prefix)) {
                assertThrows(IOException.class, () -> store.send(Optional.empty(), "Staff", "say rollback"));
            }
            assertTrue(injected.get(), "The test must fail after the batch actually inserted its rows");
            assertTrue(healthy.pending(player, 10).isEmpty());
            assertTrue(healthy.pending(other, 10).isEmpty());
            assertEquals(2, healthy.send(Optional.empty(), "Staff", "say healthy"));
        }
    }

    @Test
    void aClaimCannotEscapeBeforeItsCommitSucceeds() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        try (MailStore healthy = openStore()) {
            healthy.send(Optional.of(player), "Staff", "say durable");
            UUID id = healthy.pending(player, 1).getFirst().id();
            ConnectionFactory failing = interceptCommit(armed, false);
            try (MailStore store = new JdbcMailStore(failing, dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> store.claim(player, id, "failed-backend"));
            }
            assertTrue(!armed.get(), "Test must reach commit");
            assertTrue(healthy.review(player, 10).isEmpty());
            assertEquals(MailState.PENDING, healthy.pending(player, 1).getFirst().state());
            assertTrue(healthy.claim(player, id, "healthy-backend").isPresent());
        }
    }

    @Test
    void losingAClaimCommitAcknowledgementLeavesItDurableAndUnretryable() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        UUID id;
        try (MailStore healthy = openStore()) {
            healthy.send(Optional.of(player), "Staff", "say uncertain");
            id = healthy.pending(player, 1).getFirst().id();
            try (MailStore store = new JdbcMailStore(interceptCommit(armed, true), dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> store.claim(player, id, "uncertain-backend"));
            }
            assertTrue(!armed.get(), "Test must lose the acknowledgement after the real commit");
            assertTrue(healthy.claim(player, id, "other-backend").isEmpty());
        }
        try (MailStore restarted = openStore()) {
            MailRecord mail = restarted.review(player, 10).getFirst();
            assertEquals(id, mail.id());
            assertEquals(MailState.CLAIMED, mail.state());
            assertEquals("uncertain-backend", mail.serverId());
            assertNotNull(mail.claimId());
            assertEquals("Staff", mail.sender());
            assertEquals("say uncertain", mail.command());
            assertTrue(restarted.claim(player, id, "restarted-backend").isEmpty());
        }
    }

    @Test
    void losingASendAllCommitAcknowledgementCommitsTheEntireSnapshot() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        UUID other = UUID.randomUUID();
        try (MailStore healthy = openStore()) {
            healthy.register(player, "First", UUID.randomUUID());
            healthy.register(other, "Second", UUID.randomUUID());
            try (MailStore store = new JdbcMailStore(interceptCommit(armed, true), dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> store.send(Optional.empty(), "Staff", "say uncertain-send"));
            }
            assertTrue(!armed.get());
            assertEquals(1, healthy.pending(player, 10).size());
            assertEquals(1, healthy.pending(other, 10).size());
        }
    }

    private void expirePresence(UUID player) throws SQLException {
        try (Connection connection = connections().open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + prefix + "mail_presence SET expires_at = 1 WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            assertTrue(statement.executeUpdate() >= 1);
        }
    }

    private ConnectionFactory interceptCommit(AtomicBoolean armed, boolean afterCommit) {
        return () -> proxyConnection(connections().open(), (target, method, args) -> {
            if (method.getName().equals("commit") && armed.compareAndSet(true, false)) {
                if (afterCommit) {
                    invoke(target, method, args);
                }
                throw new SQLException("Injected " + (afterCommit ? "lost commit acknowledgement" : "failure before commit"), "08006");
            }
            return invoke(target, method, args);
        });
    }

    private ConnectionFactory interceptMailBatch(CheckedAction action, boolean failAfterBatch) {
        return () -> proxyConnection(connections().open(), (target, method, args) -> {
            Object result = invoke(target, method, args);
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                    && sql.startsWith("INSERT INTO " + prefix + "mail (")) {
                PreparedStatement statement = (PreparedStatement) result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class}, (proxy, batchMethod, batchArgs) -> {
                            if (batchMethod.getName().equals("executeBatch")) {
                                if (!failAfterBatch) {
                                    action.run();
                                }
                                Object batchResult = invoke(statement, batchMethod, batchArgs);
                                if (failAfterBatch) {
                                    action.run();
                                    throw new SQLException("Injected failure after mail insertion", "HY000");
                                }
                                return batchResult;
                            }
                            return invoke(statement, batchMethod, batchArgs);
                        });
            }
            return result;
        });
    }

    private static Connection proxyConnection(Connection target, ConnectionInvocation invocation) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> invocation.invoke(target, method, args));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    @FunctionalInterface
    private interface ConnectionInvocation { Object invoke(Connection target, Method method, Object[] args) throws Throwable; }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
