package io.esrest.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prepared statement using Elasticsearch SQL "params" (positional '?' placeholders). */
final class EsPreparedStatement extends EsStatement implements PreparedStatement {
    private final String sql;
    private final List<Map<String, Object>> params = new ArrayList<>();
    private final List<List<Map<String, Object>>> batches = new ArrayList<>();

    EsPreparedStatement(EsConnection connection, String sql) {
        super(connection);
        this.sql = sql;
    }

    /** Stores a parameter as {"type": esType, "value": value}, the format of ES SQL "params". */
    private void bind(int parameterIndex, String esType, Object value) throws SQLException {
        checkOpen();
        if (parameterIndex < 1) {
            throw new SQLException("Invalid parameter index " + parameterIndex, "07009");
        }
        while (params.size() < parameterIndex) {
            params.add(null);
        }
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("type", esType);
        param.put("value", value);
        params.set(parameterIndex - 1, param);
    }

    private void bindOrNull(int parameterIndex, String esType, Object value) throws SQLException {
        bind(parameterIndex, value == null ? "null" : esType, value);
    }

    private List<Map<String, Object>> boundParams() throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) == null) {
                throw new SQLException("Parameter " + (i + 1) + " is not set", "07001");
            }
        }
        return params;
    }

    // ---------------------------------------------------------------- execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        run(sql, boundParams());
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        return runUpdate(sql, boundParams());
    }

    @Override
    public boolean execute() throws SQLException {
        return run(sql, boundParams());
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        batches.add(new ArrayList<>(boundParams()));
    }

    @Override
    public void clearBatch() {
        batches.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        try {
            int[] counts = new int[batches.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = runUpdate(sql, batches.get(i));
            }
            return counts;
        } finally {
            batches.clear();
        }
    }

    @Override
    public void clearParameters() {
        params.clear();
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return null;
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw unsupported();
    }

    // ---------------------------------------------------------------- setters

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        bind(parameterIndex, "null", null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean value) throws SQLException {
        bind(parameterIndex, "boolean", value);
    }

    @Override
    public void setByte(int parameterIndex, byte value) throws SQLException {
        bind(parameterIndex, "byte", value);
    }

    @Override
    public void setShort(int parameterIndex, short value) throws SQLException {
        bind(parameterIndex, "short", value);
    }

    @Override
    public void setInt(int parameterIndex, int value) throws SQLException {
        bind(parameterIndex, "integer", value);
    }

    @Override
    public void setLong(int parameterIndex, long value) throws SQLException {
        bind(parameterIndex, "long", value);
    }

    @Override
    public void setFloat(int parameterIndex, float value) throws SQLException {
        bind(parameterIndex, "float", value);
    }

    @Override
    public void setDouble(int parameterIndex, double value) throws SQLException {
        bind(parameterIndex, "double", value);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal value) throws SQLException {
        bindOrNull(parameterIndex, "double", value);
    }

    @Override
    public void setString(int parameterIndex, String value) throws SQLException {
        bindOrNull(parameterIndex, "keyword", value);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] value) throws SQLException {
        bindOrNull(parameterIndex, "keyword", value == null ? null : Base64.getEncoder().encodeToString(value));
    }

    @Override
    public void setDate(int parameterIndex, Date value) throws SQLException {
        bindOrNull(parameterIndex, "date", value == null ? null : value.toLocalDate().toString());
    }

    @Override
    public void setDate(int parameterIndex, Date value, Calendar calendar) throws SQLException {
        setDate(parameterIndex, value);
    }

    @Override
    public void setTime(int parameterIndex, Time value) throws SQLException {
        bindOrNull(parameterIndex, "time", value == null ? null : value.toLocalTime().toString());
    }

    @Override
    public void setTime(int parameterIndex, Time value, Calendar calendar) throws SQLException {
        setTime(parameterIndex, value);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException {
        bindOrNull(parameterIndex, "datetime", value == null ? null : value.toInstant().toString());
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value, Calendar calendar) throws SQLException {
        setTimestamp(parameterIndex, value);
    }

    @Override
    public void setURL(int parameterIndex, URL value) throws SQLException {
        setString(parameterIndex, value == null ? null : value.toString());
    }

    @Override
    public void setObject(int parameterIndex, Object value) throws SQLException {
        if (value == null) {
            setNull(parameterIndex, 0);
        } else if (value instanceof String) {
            setString(parameterIndex, (String) value);
        } else if (value instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) value);
        } else if (value instanceof Byte) {
            setByte(parameterIndex, (Byte) value);
        } else if (value instanceof Short) {
            setShort(parameterIndex, (Short) value);
        } else if (value instanceof Integer) {
            setInt(parameterIndex, (Integer) value);
        } else if (value instanceof Long) {
            setLong(parameterIndex, (Long) value);
        } else if (value instanceof Float) {
            setFloat(parameterIndex, (Float) value);
        } else if (value instanceof Double) {
            setDouble(parameterIndex, (Double) value);
        } else if (value instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) value);
        } else if (value instanceof Timestamp) {
            setTimestamp(parameterIndex, (Timestamp) value);
        } else if (value instanceof Date) {
            setDate(parameterIndex, (Date) value);
        } else if (value instanceof Time) {
            setTime(parameterIndex, (Time) value);
        } else if (value instanceof java.util.Date) {
            setTimestamp(parameterIndex, new Timestamp(((java.util.Date) value).getTime()));
        } else if (value instanceof LocalDate) {
            bind(parameterIndex, "date", value.toString());
        } else if (value instanceof LocalTime) {
            bind(parameterIndex, "time", value.toString());
        } else if (value instanceof TemporalAccessor) {
            bind(parameterIndex, "datetime", value.toString());
        } else if (value instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) value);
        } else if (value instanceof Number) {
            bind(parameterIndex, "double", value);
        } else {
            setString(parameterIndex, value.toString());
        }
    }

    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType) throws SQLException {
        setObject(parameterIndex, value);
    }

    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, value);
    }

    // ---------------------------------------------------------------- unsupported setters (streams, LOBs, ...)

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value) throws SQLException {
        throw unsupported();
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setRef(int parameterIndex, Ref value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBlob(int parameterIndex, Blob value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setClob(int parameterIndex, Clob value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setArray(int parameterIndex, Array value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setRowId(int parameterIndex, RowId value) throws SQLException {
        throw unsupported();
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML value) throws SQLException {
        throw unsupported();
    }
}
