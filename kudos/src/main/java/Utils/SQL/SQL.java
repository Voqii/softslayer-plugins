package Utils.SQL;

import Utils.ConfigManagement;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.urbance.Main;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class SQL {
    private static HikariConfig hikariConfig;
    private static HikariDataSource hikariDataSource;
    private static boolean useMySQL;
    private static String dataFolderPath;
    private FileConfiguration mysqlConfig;
    private FileConfiguration config;
    private Main plugin;
    private String host;
    private String port;
    private String database;
    private String username;
    private String password;
    private String useSSL;

    public SQL() {
        this.plugin = Main.getPlugin(Main.class);
        this.config = plugin.getConfig();
        this.mysqlConfig = ConfigManagement.getMySQLConfig();
        this.host = mysqlConfig.getString("hostname");
        this.port = mysqlConfig.getString("port");
        this.database = mysqlConfig.getString("database");
        this.username = mysqlConfig.getString("username");
        this.password = mysqlConfig.getString("password");
        this.useSSL = mysqlConfig.getString("useSSL");
    }


    public void init() {
        hikariConfig = new HikariConfig();
        determineSQLType();

        if (useMySQL) {
            prepareMySQLConnection();
        } else {
            prepareSQLiteConnection();
        }

        try {
            initConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void determineSQLType() {
        useMySQL = config.getBoolean("general-settings.use-MySQL");

        if (config.isConfigurationSection("general"))
            useMySQL = config.getBoolean("general.use-SQL");
    }


    private void prepareMySQLConnection() {
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%s/%s?&useSSL=%s", host, port, database, useSSL));
        hikariConfig.setDriverClassName("com.mysql.jdbc.Driver");
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", 250);
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
        hikariConfig.addDataSourceProperty("cachePrepStmts", true);
        hikariConfig.addDataSourceProperty("useServerPrepStmts ", true);

        SQLGetter.driverClassName = hikariConfig.getDriverClassName();
        hikariDataSource = new HikariDataSource(hikariConfig);
    }

    // voqii: SQLite used to open a brand new DriverManager connection for every single query.
    // Pool it like MySQL so connections are reused and safe to use from several threads at once.
    private void prepareSQLiteConnection() {
        SQLGetter.driverClassName = "org.sqlite.JDBC";
        dataFolderPath = (String.format("%s/data", plugin.getDataFolder()));
        createDataFolder(dataFolderPath);

        hikariConfig.setJdbcUrl(String.format("jdbc:sqlite:%s/database.db", dataFolderPath));
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setPoolName("Kudos-SQLite");
        hikariConfig.setMaximumPoolSize(4);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setConnectionInitSql("PRAGMA busy_timeout=5000");
        hikariDataSource = new HikariDataSource(hikariConfig);
    }

    private void createDataFolder(String dataFolderPath) {
        File dataFolder = new File(dataFolderPath);
        if (!dataFolder.exists())
            dataFolder.mkdir();
    }

    private static void initConnection() throws SQLException {
        try (Connection connection = hikariDataSource.getConnection()) {
            connection.isValid(5);
        }
    }

    // voqii: was `if (connection != null || connection.isClosed()) initConnection();`, which reopened a
    // connection on every call and handed one shared static Connection to whichever thread asked last.
    // Every caller closes what it gets (try-with-resources), so just borrow from the pool.
    public static Connection getConnection() throws SQLException {
        return hikariDataSource.getConnection();
    }

    public static void disconnect() throws SQLException {
        if (hikariDataSource != null)
            hikariDataSource.close();
    }
}
