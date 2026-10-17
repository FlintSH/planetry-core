package dev.flint.planetry.core.journal.storage;

import dev.flint.planetry.core.storage.JdbcProgressStore;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CORE_TEST_POSTGRES_URL", matches = ".+")
class PostgresqlJournalStoreTest extends JdbcJournalStoreContract {
    @Override
    protected JdbcProgressStore.Dialect dialect() {
        return JdbcProgressStore.Dialect.POSTGRESQL;
    }

    @Override
    protected String environmentPrefix() {
        return "CORE_TEST_POSTGRES";
    }
}
