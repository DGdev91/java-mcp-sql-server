package com.dgdev91.mcpsql.model;

public class ColumnInfo {
    private String columnName;
    private String dataType;
    private Integer columnSize;
    private boolean nullable;
    private boolean primaryKey;

    public ColumnInfo(String columnName, String dataType, Integer columnSize, boolean nullable, boolean primaryKey) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.columnSize = columnSize;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public Integer getColumnSize() {
        return columnSize;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }
}
