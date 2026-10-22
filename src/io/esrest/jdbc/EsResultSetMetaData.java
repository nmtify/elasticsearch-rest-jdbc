package io.esrest.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

final class EsResultSetMetaData extends JdbcWrapper implements ResultSetMetaData {
    private final List<EsColumn> columns;

    EsResultSetMetaData(List<EsColumn> columns) {
        this.columns = columns;
    }

    private EsColumn column(int columnIndex) throws SQLException {
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Invalid column index " + columnIndex, "07009");
        }
        return columns.get(columnIndex - 1);
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnLabel(int columnIndex) throws SQLException {
        return column(columnIndex).name;
    }

    @Override
    public String getColumnName(int columnIndex) throws SQLException {
        return column(columnIndex).name;
    }

    @Override
    public int getColumnType(int columnIndex) throws SQLException {
        return column(columnIndex).sqlType;
    }

    @Override
    public String getColumnTypeName(int columnIndex) throws SQLException {
        return column(columnIndex).typeName;
    }

    @Override
    public String getColumnClassName(int columnIndex) throws SQLException {
        return EsTypes.className(column(columnIndex).sqlType);
    }

    @Override
    public int getColumnDisplaySize(int columnIndex) throws SQLException {
        return EsTypes.displaySize(column(columnIndex).sqlType);
    }

    @Override
    public int getPrecision(int columnIndex) throws SQLException {
        return EsTypes.precision(column(columnIndex).sqlType);
    }

    @Override
    public int getScale(int columnIndex) throws SQLException {
        return EsTypes.scale(column(columnIndex).sqlType);
    }

    @Override
    public boolean isSigned(int columnIndex) throws SQLException {
        return EsTypes.isSigned(column(columnIndex).sqlType);
    }

    @Override
    public boolean isCaseSensitive(int columnIndex) throws SQLException {
        int sqlType = column(columnIndex).sqlType;
        return sqlType == Types.VARCHAR || sqlType == Types.OTHER;
    }

    @Override
    public int isNullable(int columnIndex) {
        return columnNullableUnknown;
    }

    @Override
    public boolean isAutoIncrement(int columnIndex) {
        return false;
    }

    @Override
    public boolean isSearchable(int columnIndex) {
        return true;
    }

    @Override
    public boolean isCurrency(int columnIndex) {
        return false;
    }

    @Override
    public boolean isReadOnly(int columnIndex) {
        return false;
    }

    @Override
    public boolean isWritable(int columnIndex) {
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int columnIndex) {
        return false;
    }

    @Override
    public String getSchemaName(int columnIndex) {
        return "";
    }

    @Override
    public String getTableName(int columnIndex) {
        return "";
    }

    @Override
    public String getCatalogName(int columnIndex) {
        return "";
    }
}
