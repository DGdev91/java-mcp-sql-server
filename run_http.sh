#!/bin/bash
# Script to run the MCP server in HTTP mode

# Set database configuration
export JMCP_JDBC_URL="jdbc:postgresql://localhost:5432/mydb"
export JMCP_DB_USERNAME="postgres"
export JMCP_DB_PASSWORD="password"

# Set HTTP mode
export JMCP_MODE="http"
export JMCP_HTTP_PORT="3000"

# Run the server
java -jar target/java-mcp-sql-server-1.0.0.jar
