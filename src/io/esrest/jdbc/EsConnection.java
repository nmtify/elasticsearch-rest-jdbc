package io.esrest.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

final class EsConnection extends JdbcWrapper implements Connection {
    private static final String DEFAULT_CLUSTER_NAME = "elasticsearch";
    private static final String UNKNOWN_VERSION = "0.0.0";

    final String url;
    final EsConfig config;
    final EsClient client;
    final DocumentSearch.ColumnCache documentColumns = new DocumentSearch.ColumnCache();
    final String clusterName;
    final String esVersion;
    private volatile boolean closed;
    private boolean readOnly;

    EsConnection(String url, EsConfig config) throws SQLException {
        this.url = url;
        this.config = config;
        this.client = new EsClient(config);
        this.readOnly = config.readOnly;

        Map<?, ?> info = clusterInfo();
        this.clusterName = info.get("cluster_name") == null ? DEFAULT_CLUSTER_NAME : String.valueOf(info.get("cluster_name"));
        Object version = info.get("version");
        Object number = version instanceof Map ? ((Map<?, ?>) version).get("number") : null;
        this.esVersion = number == null ? UNKNOWN_VERSION : String.valueOf(number);
    }

    /** GET / for the cluster name and version; a user without the 'monitor' privilege gets 403, so ping via SQL instead. */
    private Map<?, ?> clusterInfo() throws SQLException {
        try {
            Object root = client.json("GET", "/", null);
            return root instanceof Map ? (Map<?, ?>) root : Map.of();
        } catch (SQLException e) {
            if (e.getErrorCode() != 403) {
                throw e;
            }
            executeSql("SELECT 1", null, null, 1, 1, 0).close();
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- execution

    EsResultSet executeSql(String sql, List<Map<String, Object>> params, EsStatement statement,
                           int fetchSize, int maxRows, int timeoutSeconds) throws SQLException {
        checkOpen();
        int pageSize = fetchSize > 0 ? fetchSize : config.fetchSize;
        if (maxRows > 0) {
            pageSize = Math.min(pageSize, maxRows);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", sql);
        body.put("fetch_size", pageSize);
        body.put("time_zone", config.timeZone);
        if (config.fieldMultiValueLeniency) {
            body.put("field_multi_value_leniency", true);
        }
        if (timeoutSeconds > 0) {
            body.put("request_timeout", timeoutSeconds + "s");
        }
        if (params != null && !params.isEmpty()) {
            body.put("params", params);
        }

        Map<String, Object> response = client.sql(body);
        return new EsResultSet(statement, columnsOf(response), rowsOf(response), PageSource.SqlCursor.of(client, response), maxRows);
    }

    /** SELECT * on an index runs as a document search (with _id and JSON objects); everything else as ES SQL. */
    EsResultSet executeQuery(String sql, List<Map<String, Object>> params, EsStatement statement,
                             int fetchSize, int maxRows, int timeoutSeconds) throws SQLException {
        checkOpen();
        EsResultSet documents = DocumentSearch.tryExecute(this, sql, params, statement, fetchSize, maxRows);
        if (documents != null) {
            return documents;
        }
        return executeSql(sql, params, statement, fetchSize, maxRows, timeoutSeconds);
    }

    /** Raw REST passthrough: "GET /_cat/indices?format=json" or "POST /idx/_search { ... }". */
    EsResultSet executeRest(String method, String path, String body, EsStatement statement, int maxRows) throws SQLException {
        checkOpen();
        return RestResult.toResultSet(statement, client.json(method, path, body), maxRows);
    }

    int executeDml(String sql, List<Map<String, Object>> params) throws SQLException {
        checkOpen();
        try {
            return new EsDml(this).execute(sql, params);
        } finally {
            documentColumns.clear(); // a write can add fields to the mapping
        }
    }

    private static List<EsColumn> columnsOf(Map<String, Object> response) {
        Object columns = response.get("columns");
        if (!(columns instanceof List)) {
            return Collections.emptyList();
        }
        List<EsColumn> result = new ArrayList<>();
        for (Object item : (List<?>) columns) {
            Map<?, ?> column = (Map<?, ?>) item;
            result.add(EsColumn.fromEs(String.valueOf(column.get("name")), (String) column.get("type")));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rowsOf(Map<String, Object> response) {
        Object rows = response.get("rows");
        return rows instanceof List ? (List<List<Object>>) rows : Collections.emptyList();
    }

    void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed", "08003");
        }
    }

    // ---------------------------------------------------------------- statements

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new EsStatement(this);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return new EsPreparedStatement(this, sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw unsupported("Stored procedures are not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public String nativeSQL(String sql) {
        return sql;
    }

    // ---------------------------------------------------------------- state

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new EsDatabaseMetaData(this);
    }

    @Override
    public boolean isValid(int timeoutSeconds) {
        if (closed) {
            return false;
        }
        int previousTimeout = client.readTimeoutMillis;
        if (timeoutSeconds > 0) {
            client.readTimeoutMillis = timeoutSeconds * 1000;
        }
        try {
            client.send("GET", "/", null);
            return true;
        } catch (SQLException e) {
            return e.getErrorCode() == 403; // reachable, the user just lacks the 'monitor' privilege
        } finally {
            client.readTimeoutMillis = previousTimeout;
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void abort(Executor executor) {
        closed = true;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly || config.readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public String getCatalog() {
        return clusterName;
    }

    @Override
    public void setCatalog(String catalog) { }

    @Override
    public String getSchema() {
        return null;
    }

    @Override
    public void setSchema(String schema) { }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) {
        client.readTimeoutMillis = milliseconds;
    }

    @Override
    public int getNetworkTimeout() {
        return client.readTimeoutMillis;
    }

    // ---------------------------------------------------------------- transactions (not supported, every write is immediate)

    @Override
    public void setAutoCommit(boolean autoCommit) { }

    @Override
    public boolean getAutoCommit() {
        return true;
    }

    @Override
    public void commit() { }

    @Override
    public void rollback() { }

    @Override
    public void setTransactionIsolation(int level) { }

    @Override
    public int getTransactionIsolation() {
        return TRANSACTION_NONE;
    }

    @Override
    public void setHoldability(int holdability) { }

    @Override
    public int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw unsupported("Transactions are not supported");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw unsupported("Transactions are not supported");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw unsupported("Transactions are not supported");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw unsupported("Transactions are not supported");
    }

    // ---------------------------------------------------------------- misc

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() { }

    @Override
    public Map<String, Class<?>> getTypeMap() {
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) { }

    @Override
    public void setClientInfo(String name, String value) { }

    @Override
    public void setClientInfo(Properties properties) { }

    @Override
    public String getClientInfo(String name) {
        return null;
    }

    @Override
    public Properties getClientInfo() {
        return new Properties();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw unsupported();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw unsupported();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw unsupported();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw unsupported();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw unsupported();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw unsupported();
    }
}
