package dev.flint.planetry.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.flint.planetry.core.progression.Operation;
import dev.flint.planetry.core.progression.Progress;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

abstract class JdbcProgressStoreContract extends ProgressStoreContract {
    protected final String prefix = "core_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";

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
    protected ProgressStore openStore() throws IOException {
        return new JdbcProgressStore(connections(), dialect(), prefix);
    }

    @AfterEach
    void removeOnlyThisTestsTables() throws SQLException {
        try (Connection connection = connections().open(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "rewards");
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "players");
        }
    }

    @Test
    @Timeout(30)
    void freshBackendInstancesCanBootstrapTheSameSchemaConcurrently() throws Exception {
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
                    throw new SQLException("Concurrent constructors did not all open their initial connection");
                }
                handedOff = true;
                return connection;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while aligning concurrent constructor startup", error);
            } finally {
                if (!handedOff) {
                    connection.close();
                }
            }
        };

        runConcurrently(4, () -> {
            try (ProgressStore backend = new JdbcProgressStore(simultaneousConnections, dialect(), prefix)) {
                backend.mutate(player, Operation.GIVE, 125);
            }
            return null;
        });

        try (ProgressStore verifier = openStore()) {
            assertEquals(new Progress(5, 0, 5), verifier.get(player));
            assertTrue(verifier.rewardsNeedingReview(player).isEmpty());
            for (int level = 1; level <= 5; level++) {
                RewardClaim claim = verifier.claimNextReward(player, "verification-backend").orElseThrow();
                assertEquals(level, claim.level());
                verifier.completeReward(player, claim.claimId(), RewardOutcome.COMPLETE);
            }
            assertTrue(verifier.claimNextReward(player, "verification-backend").isEmpty());
            assertTrue(verifier.rewardsNeedingReview(player).isEmpty());
        }
    }

    @Test
    @Timeout(30)
    void separateBackendInstancesSerializeConcurrentMutationsOfTheSamePlayer() throws Exception {
        try (ProgressStore first = openStore(); ProgressStore second = openStore()) {
            AtomicInteger nextWorker = new AtomicInteger();
            runConcurrently(4, () -> {
                ProgressStore backend = nextWorker.getAndIncrement() % 2 == 0 ? first : second;
                for (int update = 0; update < 20; update++) {
                    backend.mutate(player, Operation.GIVE, 7);
                }
                return null;
            });

            assertEquals(new Progress(5, 60, 5), first.get(player));
            assertEquals(first.get(player), second.get(player));
            for (int level = 1; level <= 5; level++) {
                ProgressStore claimant = level % 2 == 0 ? first : second;
                ProgressStore completer = level % 2 == 0 ? second : first;
                RewardClaim claim = claimant.claimNextReward(player, "backend-" + level).orElseThrow();
                assertEquals(level, claim.level());
                completer.completeReward(player, claim.claimId(), RewardOutcome.COMPLETE);
            }
            assertTrue(first.claimNextReward(player, "backend-a").isEmpty());
            assertTrue(second.rewardsNeedingReview(player).isEmpty());
        }
    }

    @Test
    void failedRewardBatchRollsBackBothExperienceAndAlreadyInsertedRewards() throws Exception {
        try (ProgressStore healthy = openStore()) {
            healthy.mutate(player, Operation.GIVE, 99);
            AtomicBoolean armed = new AtomicBoolean();
            AtomicBoolean injected = new AtomicBoolean();
            ConnectionFactory failingConnections = () -> proxyConnection(connections().open(), (target, method, args) -> {
                Object result = invoke(target, method, args);
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                        && sql.startsWith("INSERT INTO " + prefix + "rewards")) {
                    PreparedStatement statement = (PreparedStatement) result;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (proxy, statementMethod, statementArgs) -> {
                                Object batchResult = invoke(statement, statementMethod, statementArgs);
                                if (statementMethod.getName().equals("executeBatch") && armed.get()) {
                                    injected.set(true);
                                    throw new SQLException("Injected failure after reward rows were inserted", "HY000");
                                }
                                return batchResult;
                            });
                }
                return result;
            });

            try (ProgressStore failing = new JdbcProgressStore(failingConnections, dialect(), prefix)) {
                armed.set(true);
                assertThrows(IOException.class, () -> failing.mutate(player, Operation.GIVE, 201));
                assertTrue(injected.get(), "The test must reach the reward-write failure");
            }

            assertEquals(new Progress(0, 99, 0), healthy.get(player));
            assertTrue(healthy.claimNextReward(player, "survival").isEmpty());
            assertTrue(healthy.rewardsNeedingReview(player).isEmpty());
            healthy.mutate(player, Operation.GIVE, 1);
            assertEquals(1, healthy.claimNextReward(player, "survival").orElseThrow().level());
        }
    }

    @Test
    void aClaimWhoseTransactionCannotCommitIsNotReturnedOrConsumed() throws Exception {
        try (ProgressStore healthy = openStore()) {
            healthy.mutate(player, Operation.GIVE, 100);
            AtomicBoolean failCommit = new AtomicBoolean();
            AtomicBoolean injected = new AtomicBoolean();
            ConnectionFactory failingConnections = () -> proxyConnection(connections().open(), (target, method, args) -> {
                if (method.getName().equals("commit") && failCommit.compareAndSet(true, false)) {
                    injected.set(true);
                    throw new SQLException("Injected failure before claim commit", "HY000");
                }
                return invoke(target, method, args);
            });

            try (ProgressStore failing = new JdbcProgressStore(failingConnections, dialect(), prefix)) {
                failCommit.set(true);
                assertThrows(IOException.class, () -> failing.claimNextReward(player, "backend-a"));
                assertTrue(injected.get(), "The test must reach the failed commit");
            }

            assertTrue(healthy.rewardsNeedingReview(player).isEmpty());
            RewardClaim retry = healthy.claimNextReward(player, "backend-b").orElseThrow();
            assertEquals(1, retry.level());
            assertEquals("backend-b", retry.serverId());
            assertTrue(healthy.claimNextReward(player, "backend-c").isEmpty());
        }
    }

    @Test
    void aLostCommitAcknowledgementPreservesTheClaimAndPreventsAutomaticRetry() throws Exception {
        try (ProgressStore healthy = openStore()) {
            healthy.mutate(player, Operation.GIVE, 100);
            AtomicBoolean loseAcknowledgement = new AtomicBoolean();
            AtomicBoolean injected = new AtomicBoolean();
            ConnectionFactory uncertainConnections = () -> proxyConnection(connections().open(), (target, method, args) -> {
                Object result = invoke(target, method, args);
                if (method.getName().equals("commit") && loseAcknowledgement.compareAndSet(true, false)) {
                    injected.set(true);
                    throw new SQLException("Injected loss of acknowledgement after committed claim", "08006");
                }
                return result;
            });

            try (ProgressStore uncertain = new JdbcProgressStore(uncertainConnections, dialect(), prefix)) {
                loseAcknowledgement.set(true);
                assertThrows(IOException.class, () -> uncertain.claimNextReward(player, "backend-a"));
                assertTrue(injected.get(), "The test must lose the acknowledgement after the actual commit");
            }

            assertTrue(healthy.claimNextReward(player, "backend-b").isEmpty());
            var records = healthy.rewardsNeedingReview(player);
            assertEquals(1, records.size());
            RewardRecord claimed = records.getFirst();
            assertEquals(1, claimed.level());
            assertEquals(RewardState.CLAIMED, claimed.state());
            assertEquals("backend-a", claimed.serverId());
            assertNotNull(claimed.claimId());
            assertEquals(new Progress(1, 0, 1), healthy.get(player));
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
