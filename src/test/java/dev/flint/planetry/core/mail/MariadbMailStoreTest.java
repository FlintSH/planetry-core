package dev.flint.planetry.core.mail;

import dev.flint.planetry.core.storage.JdbcProgressStore;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CORE_TEST_MARIADB_URL", matches = ".+")
class MariadbMailStoreTest extends JdbcMailStoreContract {
    @Override
    protected JdbcProgressStore.Dialect dialect() { return JdbcProgressStore.Dialect.MARIADB; }
    @Override
    protected String environmentPrefix() { return "CORE_TEST_MARIADB"; }
}
