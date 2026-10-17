package dev.flint.planetry.core.journal.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

abstract class JdbcJournalStoreContract extends JournalStoreContract {
    protected final String prefix = "core_journal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";

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
    protected JournalStore openStore() throws IOException {
        return new JdbcJournalStore(connections(), dialect(), prefix);
    }

    @AfterEach
    void removeOnlyThisTestsTable() throws SQLException {
        try (Connection connection = connections().open(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "journal_found");
        }
    }

    @Test
    @Timeout(30)
    void freshBackendsCanBootstrapAndDiscoverTheSameEntryConcurrently() throws Exception {
        CountDownLatch initialConnectionsReady = new CountDownLatch(4);
        AtomicInteger openedConnections = new AtomicInteger();
        ConnectionFactory simultaneousConnections = () -> {
            Connection connection = connections().open();
            if (openedConnections.getAndIncrement() >= 4) {
                return connection;
            }
            boolean handedOff = false;
            try {
                initialConnectionsReady.countDown();
                if (!initialConnectionsReady.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("Concurrent journal constructors did not all open their first connection");
                }
                handedOff = true;
                return connection;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while aligning concurrent journal startup", error);
            } finally {
                if (!handedOff) {
                    connection.close();
                }
            }
        };
        var changes = concurrently(4, () -> {
            try (JournalStore backend = new JdbcJournalStore(simultaneousConnections, dialect(), prefix)) {
                return backend.setFound(player, "copper", true);
            }
        });
        assertEquals(1, changes.stream().filter(Boolean::booleanValue).count());
        try (JournalStore verifier = openStore()) {
            assertEquals(Set.of("copper"), verifier.found(player));
        }
    }

    @Test
    @Timeout(30)
    void separateBackendInstancesReportExactlyOneChangeForEachStateTransition() throws Exception {
        try (JournalStore first = openStore(); JournalStore second = openStore()) {
            AtomicInteger worker = new AtomicInteger();
            assertEquals(1, concurrently(4, () -> {
                JournalStore backend = worker.getAndIncrement() % 2 == 0 ? first : second;
                return backend.setFound(player, "copper", true);
            }).stream().filter(Boolean::booleanValue).count());
            assertEquals(Set.of("copper"), second.found(player));
            assertEquals(1, concurrently(4, () -> {
                JournalStore backend = worker.getAndIncrement() % 2 == 0 ? first : second;
                return backend.setFound(player, "copper", false);
            }).stream().filter(Boolean::booleanValue).count());
            assertTrue(first.found(player).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFailureAfterExecutingAWriteRollsBackTheDiscoveryChange(boolean discover) throws Exception {
        try (JournalStore healthy = openStore()) {
            if (!discover) {
                healthy.setFound(player, "copper", true);
            }
            AtomicBoolean armed = new AtomicBoolean();
            AtomicBoolean injected = new AtomicBoolean();
            ConnectionFactory failingConnections = () -> proxyConnection(connections().open(), (target, method, args) -> {
                Object result = invoke(target, method, args);
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                        && (sql.startsWith("INSERT INTO " + prefix + "journal_found")
                        || sql.startsWith("DELETE FROM " + prefix + "journal_found"))) {
                    PreparedStatement statement = (PreparedStatement) result;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (proxy, statementMethod, statementArgs) -> {
                                Object writeResult = invoke(statement, statementMethod, statementArgs);
                                if (statementMethod.getName().equals("executeUpdate") && armed.compareAndSet(true, false)) {
                                    injected.set(true);
                                    throw new SQLException("Injected failure after journal write", "HY000");
                                }
                                return writeResult;
                            });
                }
                return result;
            });
            try (JournalStore failing = new JdbcJournalStore(failingConnections, dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> failing.setFound(player, "copper", discover));
                assertTrue(injected.get());
            }
            assertEquals(discover ? Set.of() : Set.of("copper"), healthy.found(player));
            assertTrue(healthy.setFound(player, "copper", discover));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFailedCommitDoesNotReportOrPersistAChange(boolean discover) throws Exception {
        try (JournalStore healthy = openStore()) {
            if (!discover) {
                healthy.setFound(player, "copper", true);
            }
            AtomicBoolean armed = new AtomicBoolean();
            AtomicInteger commitAttempts = new AtomicInteger();
            ConnectionFactory failingConnections = () -> proxyConnection(connections().open(), (target, method, args) -> {
                if (method.getName().equals("commit") && armed.compareAndSet(true, false)) {
                    commitAttempts.incrementAndGet();
                    throw new SQLException("Injected failure before journal commit", "HY000");
                }
                return invoke(target, method, args);
            });
            try (JournalStore failing = new JdbcJournalStore(failingConnections, dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> failing.setFound(player, "copper", discover));
                assertEquals(1, commitAttempts.get());
            }
            assertEquals(discover ? Set.of() : Set.of("copper"), healthy.found(player));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aLostCommitAcknowledgementIsReportedAndNeverAutomaticallyRetried(boolean discover) throws Exception {
        try (JournalStore healthy = openStore()) {
            if (!discover) {
                healthy.setFound(player, "copper", true);
            }
            AtomicBoolean armed = new AtomicBoolean();
            AtomicInteger mutationConnections = new AtomicInteger();
            AtomicInteger commitAttempts = new AtomicInteger();
            ConnectionFactory uncertainConnections = () -> {
                if (armed.get()) {
                    mutationConnections.incrementAndGet();
                }
                return proxyConnection(connections().open(), (target, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("commit") && armed.get()) {
                        commitAttempts.incrementAndGet();
                        throw new SQLException("Injected loss of journal commit acknowledgement", "08006");
                    }
                    return result;
                });
            };
            try (JournalStore uncertain = new JdbcJournalStore(uncertainConnections, dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> uncertain.setFound(player, "copper", discover));
                assertEquals(1, commitAttempts.get());
                assertEquals(1, mutationConnections.get());
            }
            assertEquals(discover ? Set.of("copper") : Set.of(), healthy.found(player));
            assertFalse(healthy.setFound(player, "copper", discover));
        }
    }

    @Test
    void discoveryWritesDoNotCreateProgressionOrMailTables() throws Exception {
        try (JournalStore store = openStore(); Connection connection = connections().open()) {
            assertTrue(store.setFound(player, "copper", true));
            for (String suffix : new String[] {"players", "rewards", "mail", "mail_directory", "mail_presence"}) {
                try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, prefix + suffix,
                        new String[] {"TABLE"})) {
                    assertFalse(tables.next(), "Journal storage must not create " + suffix);
                }
            }
        }
    }

    @Test
    void unsafeTablePrefixesAreRejectedBeforeOpeningAConnection() {
        ConnectionFactory unexpected = () -> {
            throw new AssertionError("Invalid prefixes must be rejected before connecting");
        };
        for (String invalid : new String[] {null, "", "1core", "core; DROP TABLE users", "a".repeat(41)}) {
            assertThrows(IllegalArgumentException.class, () -> new JdbcJournalStore(unexpected, dialect(), invalid));
        }
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
    private interface ConnectionInvocation {
        Object invoke(Connection target, Method method, Object[] args) throws Throwable;
    }
}
