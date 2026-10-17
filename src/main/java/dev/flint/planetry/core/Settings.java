package dev.flint.planetry.core;

import dev.flint.planetry.core.storage.*;
import dev.flint.planetry.core.mail.*;
import org.bukkit.configuration.file.FileConfiguration;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

record Settings(boolean xpBar, boolean lockVanillaExperience, int refreshTicks, int dataRefreshTicks, double money,
                String currency, List<String> commands, String serverId) {
    static Settings read(FileConfiguration c) {
        double money = c.getDouble("rewards.money", 150);
        int refresh = c.getInt("display.refresh-ticks", 20);
        int dataRefresh = c.getInt("display.data-refresh-ticks", 100);
        if (!Double.isFinite(money) || money < 0 || money > 1_000_000_000)
            throw new IllegalArgumentException("rewards.money must be between 0 and 1000000000");
        if (refresh < 1 || refresh > 1200 || dataRefresh < 20 || dataRefresh > 12000)
            throw new IllegalArgumentException("Invalid display refresh interval");
        String serverId = c.getString("server-id", "");
        if (!serverId.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid server-id");
        var commands = c.getStringList("rewards.commands");
        if (commands.size() > 20) throw new IllegalArgumentException("At most 20 reward commands are supported");
        for (String command : commands)
            if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r") || command.length() > 1024)
                throw new IllegalArgumentException("Reward commands must be single console commands without a leading slash");
        return new Settings(c.getBoolean("display.xp-bar", true), c.getBoolean("vanilla-experience.lock", true), refresh, dataRefresh, money,
                c.getString("rewards.currency-label", "Credits"), List.copyOf(commands), serverId);
    }

    static ProgressStore openStore(FileConfiguration c, Path data) throws Exception {
        String type = c.getString("storage.type", "file").toLowerCase(Locale.ROOT);
        if (type.equals("file")) return new FileProgressStore(data.resolve("players"));
        Database db = database(c, type);
        return new JdbcProgressStore(db.connections(), db.dialect(), db.prefix());
    }

    static MailStore openMailStore(FileConfiguration c, Path data) throws Exception {
        String type = c.getString("storage.type", "file").toLowerCase(Locale.ROOT);
        if (type.equals("file")) return new FileMailStore(data.resolve("mail"));
        Database db = database(c, type);
        return new JdbcMailStore(db.connections(), db.dialect(), db.prefix());
    }

    private record Database(ConnectionFactory connections, JdbcProgressStore.Dialect dialect, String prefix) { }
    private static Database database(FileConfiguration c, String type) throws Exception {
        if (!Set.of("mariadb", "postgresql").contains(type)) throw new IllegalArgumentException("Unknown storage.type");
        String host = c.getString("storage.host", "127.0.0.1");
        String database = c.getString("storage.database", "planetry");
        int port = c.getInt("storage.port", type.equals("mariadb") ? 3306 : 5432);
        if (!host.matches("[A-Za-z0-9.:-]+") || !database.matches("[A-Za-z0-9_]{1,64}") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid database host, port or name");
        String ssl = c.getString("storage.ssl-mode", "require");
        if (!Set.of("require", "disable").contains(ssl)) throw new IllegalArgumentException("ssl-mode must be require or disable");
        Properties props = new Properties();
        props.setProperty("user", c.getString("storage.username", "planetry"));
        String password = c.getString("storage.password", "");
        String env = c.getString("storage.password-env", "");
        if (!env.isBlank()) {
            String value = System.getenv(env);
            if (value != null) password = value;
            else if (password.isBlank()) throw new IllegalArgumentException("Database password environment variable is unset");
        }
        props.setProperty("password", password);
        if (type.equals("postgresql")) {
            Class.forName("org.postgresql.Driver");
            props.setProperty("sslmode", ssl.equals("require") ? "verify-full" : "disable");
            props.setProperty("sslfactory", org.postgresql.ssl.DefaultJavaSSLFactory.class.getName());
            props.setProperty("connectTimeout", "10"); props.setProperty("socketTimeout", "15");
        } else {
            Class.forName("org.mariadb.jdbc.Driver");
            props.setProperty("sslMode", ssl.equals("require") ? "verify-full" : "disable");
            props.setProperty("connectTimeout", "10000"); props.setProperty("socketTimeout", "15000");
        }
        String address = host.contains(":") ? "[" + host + "]" : host;
        String url = "jdbc:" + type + "://" + address + ":" + port + "/" + database;
        return new Database(() -> DriverManager.getConnection(url, props),
                type.equals("mariadb") ? JdbcProgressStore.Dialect.MARIADB : JdbcProgressStore.Dialect.POSTGRESQL,
                c.getString("storage.table-prefix", "planetry_core_"));
    }
}
