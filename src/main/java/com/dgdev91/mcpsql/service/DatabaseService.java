package com.dgdev91.mcpsql.service;

import com.dgdev91.mcpsql.model.ColumnInfo;
import com.dgdev91.mcpsql.model.DatabaseConfig;
import com.dgdev91.mcpsql.model.TableInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.util.*;

public class DatabaseService {
    private static final Logger logger = LogManager.getLogger(DatabaseService.class);
    private static final String SELECT_FROM = "SELECT * FROM ";
    private static final int MAX_QUERY_LIMIT = 10000;
    private final DatabaseConfig config;

    public DatabaseService(DatabaseConfig config) {
        this.config = config;

        // Load only the necessary JDBC driver based on the JDBC URL
        loadDriverForJdbcUrl(config.getJdbcUrl());
    }

    public DatabaseConfig getConfig() {
        return config;
    }

    /**
     * Loads the appropriate JDBC driver based on the JDBC URL.
     */
    private void loadDriverForJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            logger.warn("JDBC URL is null or empty, no driver loaded");
            return;
        }

        String driverClass = null;
        String databaseType = null;

        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            driverClass = "org.postgresql.Driver";
            databaseType = "PostgreSQL";
        } else if (jdbcUrl.startsWith("jdbc:sqlserver:")) {
            driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            databaseType = "SQL Server";
        } else if (jdbcUrl.startsWith("jdbc:oracle:")) {
            driverClass = "oracle.jdbc.driver.OracleDriver";
            databaseType = "Oracle";
        } else if (jdbcUrl.startsWith("jdbc:mysql:")) {
            driverClass = "com.mysql.cj.jdbc.Driver";
            databaseType = "MySQL";
        } else if (jdbcUrl.startsWith("jdbc:mariadb:")) {
            driverClass = "org.mariadb.jdbc.Driver";
            databaseType = "MariaDB";
        } else if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            driverClass = "org.sqlite.JDBC";
            databaseType = "SQLite";
        } else {
            logger.warn("Unknown JDBC URL format: {}. Driver may not be loaded explicitly.", jdbcUrl);
            return;
        }

        try {
            Class.forName(driverClass);
            logger.info("{} driver loaded successfully", databaseType);
        } catch (ClassNotFoundException e) {
            logger.error("{} driver not found: {}", databaseType, driverClass, e);
            throw new RuntimeException("Required JDBC driver not found: " + driverClass, e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
            config.getJdbcUrl(),
            config.getUsername(),
            config.getPassword()
        );
    }

    /**
     * Validates an SQL identifier (schema/table name) to prevent SQL injection.
     * Allows only alphanumeric characters, underscores, and hyphens.
     */
    private void validateIdentifier(String identifier, String identifierType) throws SQLException {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new SQLException(identifierType + " cannot be null or empty");
        }
        
        // Check for suspicious patterns that could indicate SQL injection
        if (identifier.contains(";") || identifier.contains("--") || 
            identifier.contains("/*") || identifier.contains("*/") ||
            identifier.contains("'") || identifier.contains("\"") ||
            identifier.contains("=") || identifier.contains("<") ||
            identifier.contains(">")) {
            throw new SQLException("Invalid " + identifierType + ": contains forbidden characters");
        }
        
        // Validate identifier format (alphanumeric, underscore, hyphen only)
        if (!identifier.matches("^[a-zA-Z0-9_-]+$")) {
            throw new SQLException("Invalid " + identifierType + ": must contain only alphanumeric characters, underscores, or hyphens");
        }
    }

    /**
     * Quotes an identifier according to the database type to prevent SQL injection.
     * This is safer than concatenating raw strings into SQL queries.
     */
    private String quoteIdentifier(String identifier) {
        switch (config.getType()) {
            case POSTGRESQL:
                return "\"" + identifier.replace("\"", "\"\"") + "\"";
            case ORACLE:
                return "\"" + identifier.replace("\"", "\"\"") + "\"";
            case SQLSERVER:
                return "[" + identifier.replace("]", "]]") + "]";
            case MYSQL:
            case MARIADB:
                return "`" + identifier.replace("`", "``") + "`";
            case SQLITE:
                return "\"" + identifier.replace("\"", "\"\"") + "\"";
            default:
                return identifier;
        }
    }

    public List<String> listSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            switch (config.getType()) {
                case POSTGRESQL:
                    try (ResultSet rs = metaData.getSchemas()) {
                        while (rs.next()) {
                            String schema = rs.getString("TABLE_SCHEM");
                            if (!schema.startsWith("pg_") && !schema.equals("information_schema")) {
                                schemas.add(schema);
                            }
                        }
                    }
                    break;
                    
                case ORACLE:
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT username FROM all_users ORDER BY username")) {
                        while (rs.next()) {
                            schemas.add(rs.getString("username"));
                        }
                    }
                    break;
                    
                case SQLSERVER:
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT name FROM sys.schemas ORDER BY name")) {
                        while (rs.next()) {
                            String schema = rs.getString("name");
                            if (!schema.startsWith("db_") && !schema.equals("sys") && 
                                !schema.equals("INFORMATION_SCHEMA")) {
                                schemas.add(schema);
                            }
                        }
                    }
                    break;
                    
                case MYSQL:
                case MARIADB:
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY schema_name")) {
                        while (rs.next()) {
                            schemas.add(rs.getString("schema_name"));
                        }
                    }
                    break;
                    
                case SQLITE:
                    // SQLite doesn't have schemas in the traditional sense
                    // Return "main" as the default schema
                    schemas.add("main");
                    break;
            }
        }
        
        return schemas;
    }

    public List<String> listTables(String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        
        return tables;
    }

    public TableInfo getTableStructure(String schema, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        Set<String> primaryKeys = new HashSet<>();
        
        try (Connection conn = getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            // Get primary keys
            try (ResultSet rs = metaData.getPrimaryKeys(null, schema, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }
            
            // Get columns
            try (ResultSet rs = metaData.getColumns(null, schema, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("TYPE_NAME");
                    Integer columnSize = rs.getInt("COLUMN_SIZE");
                    boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    boolean isPrimaryKey = primaryKeys.contains(columnName);
                    
                    columns.add(new ColumnInfo(columnName, dataType, columnSize, nullable, isPrimaryKey));
                }
            }
        }
        
        return new TableInfo(schema, tableName, columns);
    }

    public List<Map<String, Object>> queryTable(String schema, String tableName, Integer limit) throws SQLException {
        // Validate inputs
        validateQueryTableInputs(schema, tableName, limit);
        
        // Build safe table name with quoted identifiers
        String fullTableName = buildFullTableName(schema, tableName);
        
        // Build and execute query
        String query = buildQueryWithLimit(fullTableName, limit);
        
        try (Connection conn = getConnection()) {
            return executeTableQuery(conn, query, limit);
        }
    }
    
    private void validateQueryTableInputs(String schema, String tableName, Integer limit) throws SQLException {
        validateIdentifier(tableName, "Table name");
        
        if (schema != null && !schema.trim().isEmpty()) {
            validateIdentifier(schema, "Schema name");
        }
        
        if (limit != null && limit < 0) {
            throw new SQLException("Limit cannot be negative");
        }
        if (limit != null && limit > MAX_QUERY_LIMIT) {
            throw new SQLException("Limit cannot exceed " + MAX_QUERY_LIMIT + " rows");
        }
    }
    
    private String buildFullTableName(String schema, String tableName) {
        if (schema != null && !schema.trim().isEmpty()) {
            return quoteIdentifier(schema) + "." + quoteIdentifier(tableName);
        }
        return quoteIdentifier(tableName);
    }
    
    private String buildQueryWithLimit(String fullTableName, Integer limit) {
        if (limit == null || limit <= 0) {
            return SELECT_FROM + fullTableName;
        }
        
        switch (config.getType()) {
            case POSTGRESQL:
                return SELECT_FROM + fullTableName + " LIMIT ?";
            case ORACLE:
                return SELECT_FROM + fullTableName + " WHERE ROWNUM <= ?";
            case SQLSERVER:
                return "SELECT TOP " + limit + " * FROM " + fullTableName;
            default:
                return SELECT_FROM + fullTableName;
        }
    }
    
    private List<Map<String, Object>> executeTableQuery(Connection conn, String query, Integer limit) throws SQLException {
        boolean usesPreparedStatement = shouldUsePreparedStatement(limit);
        
        if (usesPreparedStatement) {
            return executeWithPreparedStatement(conn, query, limit);
        } else {
            return executeWithStatement(conn, query);
        }
    }
    
    private boolean shouldUsePreparedStatement(Integer limit) {
        if (limit == null || limit <= 0) {
            return false;
        }
        return config.getType() == com.dgdev91.mcpsql.model.DatabaseType.POSTGRESQL || 
               config.getType() == com.dgdev91.mcpsql.model.DatabaseType.ORACLE;
    }
    
    private List<Map<String, Object>> executeWithPreparedStatement(Connection conn, String query, Integer limit) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setInt(1, limit);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                results = extractResultSet(rs);
            }
        }
        
        return results;
    }
    
    private List<Map<String, Object>> executeWithStatement(Connection conn, String query) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            results = extractResultSet(rs);
        }
        
        return results;
    }
    
    private List<Map<String, Object>> extractResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                Object value = rs.getObject(i);
                row.put(columnName, value);
            }
            results.add(row);
        }
        
        return results;
    }

    /**
     * Executes a raw SQL query.
     * WARNING: This method is potentially unsafe as it accepts arbitrary SQL.
     * It should only be used with trusted input. The caller is responsible
     * for ensuring the SQL is safe and does not contain user-controlled data
     * without proper sanitization.
     */
    public List<Map<String, Object>> executeQuery(String sql) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        
        if (sql == null || sql.trim().isEmpty()) {
            throw new SQLException("SQL query cannot be null or empty");
        }
        
        // Basic validation: ensure it's a SELECT query (if querySelectOnly is true)
        String trimmedSql = sql.trim().toUpperCase();
        if (config.isQuerySelectOnly() && !trimmedSql.startsWith("SELECT")) {
            throw new SQLException("Only SELECT queries are allowed");
        }
        
        // Check for multiple statements (basic protection)
        if (sql.contains(";")) {
            throw new SQLException("Multiple statements are not allowed");
        }
        
        logger.warn("Executing raw SQL query (SELECT only: {}): {}", config.isQuerySelectOnly(), sql);
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Use executeQuery for SELECT, execute for other statements
            boolean isSelect = trimmedSql.startsWith("SELECT");
            
            if (isSelect) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i);
                            Object value = rs.getObject(i);
                            row.put(columnName, value);
                        }
                        results.add(row);
                    }
                }
            } else {
                // For non-SELECT queries (INSERT, UPDATE, DELETE, etc.)
                int affectedRows = stmt.executeUpdate(sql);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("affectedRows", affectedRows);
                result.put("message", "Query executed successfully");
                results.add(result);
            }
        }
        
        return results;
    }

    public void testConnection() throws SQLException {
        try (Connection conn = getConnection()) {
            logger.info("Successfully connected to database: {}", config.getType());
        }
    }
}