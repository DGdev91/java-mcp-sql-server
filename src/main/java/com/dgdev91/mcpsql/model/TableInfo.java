package com.dgdev91.mcpsql.model;

import java.util.List;

public class TableInfo {
    private String schema;
    private String tableName;
    private List<ColumnInfo> columns;

    public TableInfo(String schema, String tableName, List<ColumnInfo> columns) {
        this.schema = schema;
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }
}
