package io.esrest.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** Forward-only result set; further pages (SQL cursor or _search) are fetched on demand from a {@link PageSource}. */
final class EsResultSet extends JdbcWrapper implements ResultSet {
    private final EsStatement statement;
    private final List<EsColumn> columns;
    private final int maxRows;
    private List<List<Object>> page;
    private PageSource morePages;
    private int positionInPage = -1;
    private long rowNumber;
    private List<Object> currentRow;
    private boolean afterLast;
    private boolean lastValueWasNull;
    private boolean closed;

    /**
     * @param firstPage rows already fetched
     * @param morePages source of the following pages, or null when {@code firstPage} holds every row
     * @param maxRows   stop after this many rows (0 = no limit)
     */
    EsResultSet(EsStatement statement, List<EsColumn> columns, List<List<Object>> firstPage, PageSource morePages,
                int maxRows) {
        this.statement = statement;
        this.columns = columns;
        this.page = firstPage;
        this.morePages = morePages;
        this.maxRows = maxRows;
    }

    /** Result set whose rows are all in memory (metadata, raw REST responses). */
    EsResultSet(EsStatement statement, List<EsColumn> columns, List<List<Object>> rows) {
        this(statement, columns, rows, null, 0);
    }

    List<EsColumn> columns() {
        return columns;
    }

    /** Raw values of the current row, as decoded from JSON. */
    List<Object> currentRow() {
        return currentRow;
    }

    // ---------------------------------------------------------------- cursor

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (afterLast) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            finish();
            return false;
        }
        positionInPage++;
        while (positionInPage >= page.size()) {
            List<List<Object>> nextPage = morePages == null ? null : morePages.nextPage();
            if (nextPage == null) {
                finish();
                return false;
            }
            page = nextPage;
            positionInPage = 0;
        }
        currentRow = page.get(positionInPage);
        rowNumber++;
        return true;
    }

    private void finish() {
        afterLast = true;
        currentRow = null;
        closePages();
    }

    private void closePages() {
        if (morePages == null) {
            return;
        }
        morePages.close();
        morePages = null;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closePages();
        closed = true;
        currentRow = null;
        if (statement != null) {
            statement.resultSetClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed", "HY010");
        }
    }

    // ---------------------------------------------------------------- value access

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (currentRow == null) {
            throw new SQLException("No current row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Invalid column index " + columnIndex, "07009");
        }
        Object value = columnIndex <= currentRow.size() ? currentRow.get(columnIndex - 1) : null;
        lastValueWasNull = value == null;
        return value;
    }

    private int sqlType(int columnIndex) {
        return columns.get(columnIndex - 1).sqlType;
    }

    private static SQLException conversionError(Object value, String target) {
        return new SQLException("Cannot convert '" + value + "' to " + target, "22018");
    }

    private Number number(int columnIndex, String target) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw conversionError(value, target);
        }
    }

    private ZonedDateTime dateTime(int columnIndex, String target) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue()).atZone(ZoneOffset.UTC);
        }
        try {
            return EsTypes.parseDateTime(value.toString());
        } catch (RuntimeException e) {
            throw conversionError(value, target);
        }
    }

    @Override
    public boolean wasNull() {
        return lastValueWasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof List) {
            return Json.write(value);
        }
        int sqlType = sqlType(columnIndex);
        boolean floatingColumn = sqlType == Types.DOUBLE || sqlType == Types.FLOAT || sqlType == Types.REAL;
        if (value instanceof Double && !floatingColumn) {
            return BigDecimal.valueOf((Double) value).toPlainString();
        }
        return value.toString();
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        String text = value.toString().trim();
        return text.equalsIgnoreCase("true") || text.equals("1");
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "byte");
        return value == null ? 0 : value.byteValue();
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "short");
        return value == null ? 0 : value.shortValue();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "int");
        return value == null ? 0 : value.intValue();
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "long");
        return value == null ? 0 : value.longValue();
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "float");
        return value == null ? 0 : value.floatValue();
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "double");
        return value == null ? 0 : value.doubleValue();
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Number value = number(columnIndex, "BigDecimal");
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        boolean integral = value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
        return integral ? BigDecimal.valueOf(value.longValue()) : new BigDecimal(value.toString());
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        String text = value instanceof String ? (String) value : Json.write(value);
        if (sqlType(columnIndex) != Types.VARBINARY) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException notBase64) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        ZonedDateTime value = dateTime(columnIndex, "Date");
        return value == null ? null : Date.valueOf(value.toLocalDate());
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        try {
            return Time.valueOf(EsTypes.parseTime(value.toString()));
        } catch (RuntimeException e) {
            throw conversionError(value, "Time");
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        ZonedDateTime value = dateTime(columnIndex, "Timestamp");
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        if (value(columnIndex) == null) {
            return null;
        }
        try {
            return typedObject(columnIndex);
        } catch (SQLException unexpectedFormat) {
            return getString(columnIndex); // show the raw value rather than fail
        }
    }

    private Object typedObject(int columnIndex) throws SQLException {
        switch (sqlType(columnIndex)) {
            case Types.BOOLEAN: return getBoolean(columnIndex);
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER: return getInt(columnIndex);
            case Types.BIGINT: return getLong(columnIndex);
            case Types.NUMERIC:
            case Types.DECIMAL: return getBigDecimal(columnIndex);
            case Types.REAL: return getFloat(columnIndex);
            case Types.FLOAT:
            case Types.DOUBLE: return getDouble(columnIndex);
            case Types.TIMESTAMP: return getTimestamp(columnIndex);
            case Types.DATE: return getDate(columnIndex);
            case Types.TIME: return getTime(columnIndex);
            case Types.VARBINARY: return getBytes(columnIndex);
            default: return getString(columnIndex);
        }
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("type is null");
        }
        Object raw = value(columnIndex);
        if (raw == null) {
            return null;
        }
        Object converted = convert(columnIndex, type, raw);
        if (!type.isInstance(converted)) {
            throw conversionError(raw, type.getName());
        }
        return type.cast(converted);
    }

    private Object convert(int columnIndex, Class<?> type, Object raw) throws SQLException {
        if (type == String.class) {
            return getString(columnIndex);
        }
        if (type == Boolean.class) {
            return getBoolean(columnIndex);
        }
        if (type == Byte.class) {
            return getByte(columnIndex);
        }
        if (type == Short.class) {
            return getShort(columnIndex);
        }
        if (type == Integer.class) {
            return getInt(columnIndex);
        }
        if (type == Long.class) {
            return getLong(columnIndex);
        }
        if (type == Float.class) {
            return getFloat(columnIndex);
        }
        if (type == Double.class) {
            return getDouble(columnIndex);
        }
        if (type == BigDecimal.class) {
            return getBigDecimal(columnIndex);
        }
        if (type == byte[].class) {
            return getBytes(columnIndex);
        }
        if (type == Timestamp.class) {
            return getTimestamp(columnIndex);
        }
        if (type == Date.class) {
            return getDate(columnIndex);
        }
        if (type == Time.class) {
            return getTime(columnIndex);
        }
        if (type == LocalTime.class) {
            return EsTypes.parseTime(raw.toString());
        }
        if (type == Instant.class) {
            return dateTime(columnIndex, "Instant").toInstant();
        }
        if (type == ZonedDateTime.class) {
            return dateTime(columnIndex, "ZonedDateTime");
        }
        if (type == OffsetDateTime.class) {
            return dateTime(columnIndex, "OffsetDateTime").toOffsetDateTime();
        }
        if (type == LocalDateTime.class) {
            return dateTime(columnIndex, "LocalDateTime").toLocalDateTime();
        }
        if (type == LocalDate.class) {
            return dateTime(columnIndex, "LocalDate").toLocalDate();
        }
        return getObject(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return getObject(columnIndex);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public Date getDate(int columnIndex, Calendar calendar) throws SQLException {
        return getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex, Calendar calendar) throws SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar calendar) throws SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw conversionError(value, "URL");
        }
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] value = getBytes(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new StringReader(value);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    // ---------------------------------------------------------------- access by column label

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equals(columnLabel)) {
                return i + 1;
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Column not found: " + columnLabel, "42S22");
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar calendar) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar calendar) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar calendar) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    // ---------------------------------------------------------------- navigation (forward only)

    @Override
    public int getRow() {
        return currentRow == null ? 0 : (int) rowNumber;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return rowNumber == 0 && !afterLast;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return afterLast && rowNumber > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return rowNumber == 1 && currentRow != null;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        if (currentRow == null) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            return true;
        }
        return positionInPage == page.size() - 1 && (morePages == null || !morePages.hasMorePages());
    }

    private static SQLException forwardOnly() {
        return new SQLException("ResultSet is TYPE_FORWARD_ONLY", "HY106");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly();
    }

    // ---------------------------------------------------------------- properties

    @Override
    public ResultSetMetaData getMetaData() {
        return new EsResultSetMetaData(columns);
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() { }

    @Override
    public String getCursorName() throws SQLException {
        throw unsupported();
    }

    @Override
    public void setFetchDirection(int direction) { }

    @Override
    public int getFetchDirection() {
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) { }

    @Override
    public int getFetchSize() {
        return page.size();
    }

    @Override
    public int getType() {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() {
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() {
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean rowUpdated() {
        return false;
    }

    @Override
    public boolean rowInserted() {
        return false;
    }

    @Override
    public boolean rowDeleted() {
        return false;
    }

    // ---------------------------------------------------------------- unsupported getters (LOBs, refs, ...)

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        throw unsupported();
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw unsupported();
    }

    // ---------------------------------------------------------------- updates (read-only; write with SQL UPDATE instead)

    private static SQLException readOnly() {
        return unsupported("ResultSet is read-only; use an UPDATE statement instead");
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object value, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object value, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader value, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader value) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader value) throws SQLException {
        throw readOnly();
    }
}
