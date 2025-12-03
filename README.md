# Java MCP SQL Server

A very simple MCP (Model Context Protocol) server written in Java for querying and inspecting SQL databases (PostgreSQL, Oracle, SQL Server, MySQL, MariaDB, SQLite).

## Features

- Support for PostgreSQL, Oracle, SQL Server, MySQL, MariaDB, and SQLite
- List schemas and tables
- Inspect table structure (columns, types, primary keys)
- Query table data
- Execute custom SQL queries (SELECT only by default, set `JMCP_QUERY_SELECT_ONLY=false` to allow INSERT, UPDATE, DELETE, etc.)
- Two connection modes: Stdio (direct launch) and HTTP/SSE (remote connection)

## Requirements

- Java 21 or higher
- Maven 3.6+
- Access to a PostgreSQL, Oracle, SQL Server, MySQL, MariaDB, or SQLite database

## Build

```bash
mvn clean package
```

## Configuration

Set the following environment variables:

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `JMCP_JDBC_URL` | Yes | - | JDBC connection URL |
| `JMCP_DB_USERNAME` | Yes | - | Database username |
| `JMCP_DB_PASSWORD` | Yes | - | Database password |
| `JMCP_MODE` | No | `stdio` | Server mode: `stdio` or `http` |
| `JMCP_HTTP_PORT` | No | `3000` | HTTP server port (only for `http` mode) |
| `JMCP_QUERY_SELECT_ONLY` | No | `true` | If `true`, only SELECT queries are allowed. If `false`, allows INSERT, UPDATE, DELETE, etc. |

### JDBC URL Examples

**PostgreSQL:**
```
jdbc:postgresql://localhost:5432/mydb
```

**Oracle:**
```
jdbc:oracle:thin:@localhost:1521:ORCL
```

**SQL Server:**
```
jdbc:sqlserver://localhost:1433;databaseName=mydb
```

**MySQL:**
```
jdbc:mysql://localhost:3306/mydb
```

**MariaDB:**
```
jdbc:mariadb://localhost:3306/mydb
```

**SQLite:**
```
jdbc:sqlite:/path/to/database.db
```

## Running & Connecting to the MCP Server

After building the server, you need to configure your MCP client to connect to it. There are two connection modes:

### Mode 1: Stdio (Direct Launch)

MCP client launches the server as a subprocess, reading JSON-RPC messages from stdin and writing responses to stdout. This is the default mode used by Claude Desktop and other MCP clients that launch the server directly.

#### Configuration for Claude Desktop

Add the following to your Claude Desktop configuration file:

**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`  
**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Linux:** `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "java-mcp-sql-server": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\path\\to\\java-mcp-sql-server-1.0.0.jar"
      ],
      "env": {
        "JMCP_JDBC_URL": "jdbc:postgresql://localhost:5432/mydb",
        "JMCP_DB_USERNAME": "postgres",
        "JMCP_DB_PASSWORD": "password"
      }
    }
  }
}
```

#### Configuration for Visual Studio Code (Copilot)

Add the following to your VS Code settings file (`settings.json`):

**Windows:** `%APPDATA%\Code\User\settings.json`  
**macOS:** `~/Library/Application Support/Code/User/settings.json`  
**Linux:** `~/.config/Code/User/settings.json`

```json
{
  "github.copilot.chat.mcp.enabled": true,
  "github.copilot.chat.mcp.servers": {
    "java-mcp-sql-server": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\path\\to\\java-mcp-sql-server-1.0.0.jar"
      ],
      "env": {
        "JMCP_JDBC_URL": "jdbc:postgresql://localhost:5432/mydb",
        "JMCP_DB_USERNAME": "postgres",
        "JMCP_DB_PASSWORD": "password"
      }
    }
  }
}
```

**Important:** Replace `C:\\path\\to\\java-mcp-sql-server-1.0.0.jar` with the actual path to your compiled JAR file (use double backslashes `\\` on Windows).

### Mode 2: HTTP/SSE (Remote Connection)

The server starts an HTTP server with SSE (Server-Sent Events) support, allowing MCP clients to connect to a running instance instead of launching a new process.

#### Step 1: Start the server in HTTP mode

```bash
export JDBC_URL="jdbc:postgresql://localhost:5432/mydb"
export DB_USERNAME="postgres"
export DB_PASSWORD="password"
export MCP_MODE="http"
export MCP_HTTP_PORT="3000"
java -jar target/java-mcp-sql-server-1.0.0.jar
```

Or use the provided scripts (`run_http.sh` or `run_http.ps1`).

The server will be available at `http://localhost:3000` with the following endpoints:
- `/mcp` - SSE endpoint for MCP protocol
- `/health` - Health check endpoint

#### Step 2: Configure the MCP client

Add the following to your Claude Desktop configuration file:

```json
{
  "mcpServers": {
    "java-mcp-sql-server": {
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

Or, for Visual Studio Code (Copilot):
```json
{
  "github.copilot.chat.mcp.enabled": true,
  "github.copilot.chat.mcp.servers": {
      "java-mcp-sql-server-http": {
        "url": "http://localhost:3000/mcp"
      }
    }
}
```

### Verifying the Connection

After configuring and restarting your MCP client:

1. The server should appear in the list of available MCP servers
2. You should see the available tools listed
3. Try using the `list_schemas` tool to verify the connection works

## Available Tools

1. **list_schemas**: List all schemas in the database
2. **list_tables**: List all tables in a schema
3. **get_table_structure**: Get the structure of a table
4. **query_table**: Query data from a table with optional limit
5. **execute_query**: Execute a custom SQL query (SELECT only by default, set `JMCP_QUERY_SELECT_ONLY=false` to allow INSERT, UPDATE, DELETE, etc.)

## License

MIT