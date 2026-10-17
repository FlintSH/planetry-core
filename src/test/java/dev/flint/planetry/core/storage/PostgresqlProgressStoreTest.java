package dev.flint.planetry.core.storage;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CORE_TEST_POSTGRES_URL", matches = ".+")
class PostgresqlProgressStoreTest extends JdbcProgressStoreContract {
    @Override
    protected JdbcProgressStore.Dialect dialect() {
        return JdbcProgressStore.Dialect.POSTGRESQL;
    }

    @Override
    protected String environmentPrefix() {
        return "CORE_TEST_POSTGRES";
    }
}
