package com.dgdev91.mcpsql.model;

public class DatabaseConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private DatabaseType type;
    private boolean querySelectOnly;

    public DatabaseConfig(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, true);
    }

    public DatabaseConfig(String jdbcUrl, String username, String password, boolean querySelectOnly) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.querySelectOnly = querySelectOnly;
        this.type = detectDatabaseType(jdbcUrl);
    }

    private DatabaseType detectDatabaseType(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return DatabaseType.POSTGRESQL;
        } else if (jdbcUrl.startsWith("jdbc:oracle:")) {
            return DatabaseType.ORACLE;
        } else if (jdbcUrl.startsWith("jdbc:sqlserver:")) {
            return DatabaseType.SQLSERVER;
        }
        throw new IllegalArgumentException("Unsupported database type in JDBC URL: " + jdbcUrl);
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public DatabaseType getType() {
        return type;
    }

    public boolean isQuerySelectOnly() {
        return querySelectOnly;
    }
}
