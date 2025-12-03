# PowerShell script to run the MCP server in HTTP mode

# Set database configuration
$env:JMCP_JDBC_URL = "jdbc:postgresql://localhost:5432/mydb"
$env:JMCP_DB_USERNAME = "postgres"
$env:JMCP_DB_PASSWORD = "password"

# Set HTTP mode
$env:JMCP_MODE = "http"
$env:JMCP_HTTP_PORT = "3000"

# Run the server
java -jar target/java-mcp-sql-server-1.0.0.jar
