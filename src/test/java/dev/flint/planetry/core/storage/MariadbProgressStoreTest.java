package dev.flint.planetry.core.storage;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CORE_TEST_MARIADB_URL", matches = ".+")
class MariadbProgressStoreTest extends JdbcProgressStoreContract {
    @Override
    protected JdbcProgressStore.Dialect dialect() {
        return JdbcProgressStore.Dialect.MARIADB;
    }

    @Override
    protected String environmentPrefix() {
        return "CORE_TEST_MARIADB";
    }
}
