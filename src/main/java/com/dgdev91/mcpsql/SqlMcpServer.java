package com.dgdev91.mcpsql;

import com.dgdev91.mcpsql.model.ColumnInfo;
import com.dgdev91.mcpsql.model.DatabaseConfig;
import com.dgdev91.mcpsql.model.TableInfo;
import com.dgdev91.mcpsql.service.DatabaseService;
import com.fasterxml.jackson.core.JsonProcessingException;
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
        
        // SQLite files have no credentials
        boolean isSqlite = jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:");
        if (jdbcUrl == null || (!isSqlite && (username == null || password == null))) {
            logger.error("Missing required environment variables: JMCP_JDBC_URL, JMCP_DB_USERNAME, JMCP_DB_PASSWORD");
            System.exit(1);
        }
        if (isSqlite) {
            username = username != null ? username : "";
            password = password != null ? password : "";
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
        
        // POST endpoint for MCP messages (receives requests, responds in the HTTP response body)
        app.post("/mcp", ctx -> {
            try {
                String body = ctx.body();
                logger.info("Received MCP request: {}", body);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> request = objectMapper.readValue(body, Map.class);
                Map<String, Object> response = handleRequest(request);

                if (response == null) {
                    // Notification: no JSON-RPC response, just acknowledge
                    ctx.status(202);
                    return;
                }

                String responseJson = objectMapper.writeValueAsString(response);
                logger.info("Sending MCP response: {}", responseJson);
                
                // The response goes only to the caller (Streamable HTTP): broadcasting it
                // on the SSE streams would leak it to every other connected client
                ctx.json(response);
            } catch (JsonProcessingException e) {
                logger.error("Invalid JSON in MCP request", e);
                ctx.status(400).json(errorResponse(null, -32700, "Parse error"));
            } catch (Exception e) {
                logger.error("Error processing MCP request", e);
                ctx.status(500).json(errorResponse(null, -32603, e.getMessage()));
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
                "version", "1.0.1",
                "connectedClients", sseClients.size()
            ));
        });
    }

    private static void runMcpServer() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> response;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = objectMapper.readValue(line, Map.class);
                    response = handleRequest(request);
                } catch (JsonProcessingException e) {
                    logger.error("Invalid JSON received", e);
                    response = errorResponse(null, -32700, "Parse error");
                } catch (Exception e) {
                    logger.error("Error processing request", e);
                    response = errorResponse(null, -32603, e.getMessage());
                }
                // null = notification, nothing to send back
                if (response != null) {
                    System.out.println(objectMapper.writeValueAsString(response));
                }
            }
        } catch (Exception e) {
            logger.error("Error in MCP server loop", e);
        }
    }

    /** JSON-RPC protocol-level error (as opposed to a tool execution error). */
    private static class JsonRpcException extends Exception {
        final int code;

        JsonRpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message != null ? message : "Internal error");

        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    /** Returns the JSON-RPC response, or null when none must be sent (notifications). */
    private static Map<String, Object> handleRequest(Map<String, Object> request) {
        if (request == null) {
            return errorResponse(null, -32600, "Invalid Request");
        }

        Object id = request.get("id");
        boolean isNotification = !request.containsKey("id");

        if (!(request.get("method") instanceof String method)) {
            // A response from the client, or a malformed message
            if (request.containsKey("result") || request.containsKey("error") || isNotification) {
                return null;
            }
            return errorResponse(id, -32600, "Invalid Request");
        }

        if (isNotification) {
            // e.g. notifications/initialized, notifications/cancelled: never answered
            logger.debug("Ignoring notification: {}", method);
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> p
            ? (Map<String, Object>) p
            : new HashMap<>();

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolCall(params);
                default -> throw new JsonRpcException(-32601, "Method not found: " + method);
            };

            Map<String, Object> response = new HashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (JsonRpcException e) {
            return errorResponse(id, e.code, e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling method {}", method, e);
            return errorResponse(id, -32603, e.getMessage());
        }
    }

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
        List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private static Map<String, Object> handleInitialize(Map<String, Object> params) {
        // Echo the client's protocol version when supported, otherwise our oldest one
        Object requested = params.get("protocolVersion");
        String protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
            ? (String) requested
            : SUPPORTED_PROTOCOL_VERSIONS.get(0);

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", protocolVersion);
        result.put("serverInfo", Map.of(
            "name", "java-mcp-sql-server",
            "version", "1.0.1"
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

    private static Map<String, Object> handleToolCall(Map<String, Object> params) throws JsonRpcException {
        if (!(params.get("name") instanceof String toolName)) {
            throw new JsonRpcException(-32602, "Missing tool name");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> a
            ? (Map<String, Object>) a
            : new HashMap<>();

        try {
            Object result = switch (toolName) {
                case "list_schemas" -> databaseService.listSchemas();
                case "list_tables" -> databaseService.listTables(stringArg(arguments, "schema"));
                case "get_table_structure" -> {
                    TableInfo tableInfo = databaseService.getTableStructure(
                        stringArg(arguments, "schema"),
                        stringArg(arguments, "table")
                    );
                    yield formatTableStructure(tableInfo);
                }
                case "query_table" -> databaseService.queryTable(
                    stringArg(arguments, "schema"),
                    stringArg(arguments, "table"),
                    arguments.get("limit") instanceof Number n ? n.intValue() : null
                );
                case "execute_query" -> databaseService.executeQuery(stringArg(arguments, "sql"));
                default -> throw new JsonRpcException(-32602, "Unknown tool: " + toolName);
            };

            return toolResult(objectMapper.writeValueAsString(result), false);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            // Tool execution errors are reported in the result so the model can see and react to them
            logger.error("Tool {} failed", toolName, e);
            return toolResult(e.getMessage() != null ? e.getMessage() : e.toString(), true);
        }
    }

    private static String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        if (isError) {
            result.put("isError", true);
        }
        return result;
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