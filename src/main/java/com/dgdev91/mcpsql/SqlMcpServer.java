package com.dgdev91.mcpsql;

import com.dgdev91.mcpsql.model.ColumnInfo;
import com.dgdev91.mcpsql.model.DatabaseConfig;
import com.dgdev91.mcpsql.model.TableInfo;
import com.dgdev91.mcpsql.service.DatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SqlMcpServer {
    private static final Logger logger = LogManager.getLogger(SqlMcpServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static DatabaseService databaseService;
    private static final Map<String, SseClient> sseClients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        logger.info("Starting SQL MCP Server...");
        
        // Read configuration from environment or arguments
        String jdbcUrl = System.getenv("JMCP_JDBC_URL");
        String username = System.getenv("JMCP_DB_USERNAME");
        String password = System.getenv("JMCP_DB_PASSWORD");
        String serverMode = System.getenv("JMCP_MODE"); // "stdio" or "http"
        String httpPort = System.getenv("JMCP_HTTP_PORT");
        String querySelectOnlyStr = System.getenv("JMCP_QUERY_SELECT_ONLY");
        boolean querySelectOnly = querySelectOnlyStr == null || !querySelectOnlyStr.equalsIgnoreCase("false");
        
        if (jdbcUrl == null || username == null || password == null) {
            logger.error("Missing required environment variables: JMCP_JDBC_URL, JMCP_DB_USERNAME, JMCP_DB_PASSWORD");
            System.exit(1);
        }
        
        try {
            DatabaseConfig config = new DatabaseConfig(jdbcUrl, username, password, querySelectOnly);
            databaseService = new DatabaseService(config);
            databaseService.testConnection();
            
            logger.info("Database connection successful. Type: {}, Query SELECT only: {}", config.getType(), config.isQuerySelectOnly());
            
            // Determine server mode
            if ("http".equalsIgnoreCase(serverMode)) {
                int port = httpPort != null ? Integer.parseInt(httpPort) : 3000;
                runHttpServer(port);
            } else {
                // Default to stdio mode
                runMcpServer();
            }
            
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    private static void runHttpServer(int port) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(port);
        
        logger.info("MCP HTTP Server running on port {}", port);
        logger.info("Connect using: http://localhost:{}/mcp", port);
        
        // POST endpoint for MCP messages (receives requests, responds via both HTTP and SSE)
        app.post("/mcp", ctx -> {
            try {
                String body = ctx.body();
                logger.info("Received MCP request: {}", body);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> request = objectMapper.readValue(body, Map.class);
                Map<String, Object> response = handleRequest(request);
                
                String responseJson = objectMapper.writeValueAsString(response);
                logger.info("Sending MCP response: {}", responseJson);
                
                // Send response via HTTP
                ctx.json(response);
                
                // Also broadcast via SSE to all connected clients
                for (SseClient client : sseClients.values()) {
                    try {
                        client.sendEvent("message", responseJson);
                    } catch (Exception e) {
                        logger.error("Error sending SSE message to client", e);
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing MCP request", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("jsonrpc", "2.0");
                errorResponse.put("error", Map.of(
                    "code", -32603,
                    "message", e.getMessage()
                ));
                ctx.status(500).json(errorResponse);
            }
        });
        
        // SSE endpoint for MCP protocol (long-lived connection for server-sent events)
        app.sse("/mcp", client -> {
            String clientId = UUID.randomUUID().toString();
            sseClients.put(clientId, client);
            logger.info("New SSE client connected: {}", clientId);
            
            client.onClose(() -> {
                sseClients.remove(clientId);
                logger.info("SSE client disconnected: {}", clientId);
            });
            
            // Keep connection alive with periodic pings
            client.keepAlive();
        });
        
        // Health check endpoint
        app.get("/health", ctx -> {
            ctx.json(Map.of(
                "status", "ok",
                "server", "java-mcp-sql-server",
                "version", "1.0.0",
                "connectedClients", sseClients.size()
            ));
        });
    }

    private static void runMcpServer() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = objectMapper.readValue(line, Map.class);
                    Map<String, Object> response = handleRequest(request);
                    System.out.println(objectMapper.writeValueAsString(response));
                } catch (Exception e) {
                    logger.error("Error processing request", e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", e.getMessage());
                    System.out.println(objectMapper.writeValueAsString(errorResponse));
                }
            }
        } catch (Exception e) {
            logger.error("Error in MCP server loop", e);
        }
    }

    private static Map<String, Object> handleRequest(Map<String, Object> request) throws Exception {
        String method = (String) request.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", new HashMap<>());
        
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", request.get("id"));
        
        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolCall(params);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            
            response.put("result", result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("code", -32603);
            error.put("message", e.getMessage());
            response.put("error", error);
        }
        
        return response;
    }

    private static Map<String, Object> handleInitialize() {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", Map.of(
            "name", "java-mcp-sql-server",
            "version", "1.0.0"
        ));
        
        Map<String, Object> capabilities = new HashMap<>();
        Map<String, Object> toolsCapability = new HashMap<>();
        capabilities.put("tools", toolsCapability);
        result.put("capabilities", capabilities);
        
        return result;
    }

    private static Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // List Schemas Tool
        tools.add(Map.of(
            "name", "list_schemas",
            "description", "List all schemas in the database",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            )
        ));
        
        // List Tables Tool
        tools.add(Map.of(
            "name", "list_tables",
            "description", "List all tables in a schema",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "schema", Map.of(
                        "type", "string",
                        "description", "Schema name"
                    )
                ),
                "required", List.of("schema")
            )
        ));
        
        // Get Table Structure Tool
        tools.add(Map.of(
            "name", "get_table_structure",
            "description", "Get the structure (columns, types, constraints) of a table",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "schema", Map.of(
                        "type", "string",
                        "description", "Schema name"
                    ),
                    "table", Map.of(
                        "type", "string",
                        "description", "Table name"
                    )
                ),
                "required", List.of("schema", "table")
            )
        ));
        
        // Query Table Tool
        tools.add(Map.of(
            "name", "query_table",
            "description", "Query data from a table with optional limit",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "schema", Map.of(
                        "type", "string",
                        "description", "Schema name"
                    ),
                    "table", Map.of(
                        "type", "string",
                        "description", "Table name"
                    ),
                    "limit", Map.of(
                        "type", "integer",
                        "description", "Maximum number of rows to return"
                    )
                ),
                "required", List.of("schema", "table")
            )
        ));
        
        // Execute SQL Tool
        String queryDescription = databaseService.getConfig().isQuerySelectOnly()
            ? "Execute a custom SQL SELECT query"
            : "Execute a custom SQL query";
        String sqlParamDescription = databaseService.getConfig().isQuerySelectOnly()
            ? "SQL SELECT query to execute"
            : "SQL query to execute";
        tools.add(Map.of(
            "name", "execute_query",
            "description", queryDescription,
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "sql", Map.of(
                        "type", "string",
                        "description", sqlParamDescription
                    )
                ),
                "required", List.of("sql")
            )
        ));
        
        return Map.of("tools", tools);
    }

    private static Map<String, Object> handleToolCall(Map<String, Object> params) throws Exception {
        String toolName = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", new HashMap<>());
        
        Object result = switch (toolName) {
            case "list_schemas" -> databaseService.listSchemas();
            case "list_tables" -> databaseService.listTables((String) arguments.get("schema"));
            case "get_table_structure" -> {
                TableInfo tableInfo = databaseService.getTableStructure(
                    (String) arguments.get("schema"),
                    (String) arguments.get("table")
                );
                yield formatTableStructure(tableInfo);
            }
            case "query_table" -> databaseService.queryTable(
                (String) arguments.get("schema"),
                (String) arguments.get("table"),
                (Integer) arguments.get("limit")
            );
            case "execute_query" -> databaseService.executeQuery((String) arguments.get("sql"));
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
        
        return Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", objectMapper.writeValueAsString(result)
            ))
        );
    }

    private static Map<String, Object> formatTableStructure(TableInfo tableInfo) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("schema", tableInfo.getSchema());
        structure.put("table", tableInfo.getTableName());
        
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns()) {
            Map<String, Object> columnMap = new LinkedHashMap<>();
            columnMap.put("name", col.getColumnName());
            columnMap.put("type", col.getDataType());
            columnMap.put("size", col.getColumnSize());
            columnMap.put("nullable", col.isNullable());
            columnMap.put("primaryKey", col.isPrimaryKey());
            columns.add(columnMap);
        }
        structure.put("columns", columns);
        
        return structure;
    }
}