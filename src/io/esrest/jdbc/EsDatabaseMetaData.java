package io.esrest.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata backed by Elasticsearch SQL "SYS TABLES / SYS COLUMNS / SYS TYPES" (the commands the official driver
 * uses), with a fallback to "SHOW TABLES / SHOW COLUMNS". Elasticsearch has no schemas; the cluster name is exposed as
 * the single catalog.
 */
final class EsDatabaseMetaData extends JdbcWrapper implements DatabaseMetaData {
    /** Positions (0-based) of TABLE_NAME and ORDINAL_POSITION in the getColumns result. */
    private static final int COLUMN_TABLE_NAME = 2;
    private static final int COLUMN_ORDINAL_POSITION = 16;

    private static final String[] FOREIGN_KEY_COLUMNS = {"PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
            "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE",
            "FK_NAME", "PK_NAME", "DEFERRABILITY"};

    private final EsConnection connection;

    EsDatabaseMetaData(EsConnection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- tables and columns

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        MetadataTable result = new MetadataTable("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION");
        if (isForeignCatalog(catalog) || hasSchema(schemaPattern)) {
            return result.toResultSet();
        }

        Set<String> wantedTypes = upperCase(types);
        for (TableInfo table : listTables(tableNamePattern)) {
            if (wantedTypes != null && !wantedTypes.contains(table.type)) {
                continue;
            }
            result.add(connection.clusterName, null, table.name, table.type, "", null, null, null, null, null);
        }
        return result.toResultSet();
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        MetadataTable result = new MetadataTable("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME")
                .column("DATA_TYPE", Types.INTEGER).column("TYPE_NAME")
                .column("COLUMN_SIZE", Types.INTEGER).column("BUFFER_LENGTH", Types.INTEGER)
                .column("DECIMAL_DIGITS", Types.INTEGER).column("NUM_PREC_RADIX", Types.INTEGER)
                .column("NULLABLE", Types.INTEGER).column("REMARKS").column("COLUMN_DEF")
                .column("SQL_DATA_TYPE", Types.INTEGER).column("SQL_DATETIME_SUB", Types.INTEGER)
                .column("CHAR_OCTET_LENGTH", Types.INTEGER).column("ORDINAL_POSITION", Types.INTEGER)
                .column("IS_NULLABLE").column("SCOPE_CATALOG").column("SCOPE_SCHEMA").column("SCOPE_TABLE")
                .column("SOURCE_DATA_TYPE", Types.SMALLINT).column("IS_AUTOINCREMENT").column("IS_GENERATEDCOLUMN");
        if (isForeignCatalog(catalog) || hasSchema(schemaPattern)) {
            return result.toResultSet();
        }

        List<Map<String, Object>> sysColumns;
        try {
            sysColumns = query("SYS COLUMNS TABLE LIKE " + like(tableNamePattern) + " LIKE " + like(columnNamePattern));
        } catch (SQLException sysNotSupported) {
            addColumnsFromShowColumns(result, tableNamePattern, columnNamePattern);
            return withIdColumns(result, columnNamePattern).toResultSet();
        }
        for (Map<String, Object> column : sysColumns) {
            Object dataTypeValue = column.get("DATA_TYPE");
            int dataType = dataTypeValue instanceof Number ? ((Number) dataTypeValue).intValue() : Types.OTHER;
            result.add(connection.clusterName, null, column.get("TABLE_NAME"), column.get("COLUMN_NAME"), dataType,
                    valueOr(column, "TYPE_NAME", "UNKNOWN"),
                    toInt(valueOr(column, "COLUMN_SIZE", EsTypes.precision(dataType))),
                    toInt(column.get("BUFFER_LENGTH")), toInt(column.get("DECIMAL_DIGITS")),
                    toInt(valueOr(column, "NUM_PREC_RADIX", 10)), columnNullable, "", null,
                    toInt(valueOr(column, "SQL_DATA_TYPE", dataType)), toInt(column.get("SQL_DATETIME_SUB")),
                    toInt(column.get("CHAR_OCTET_LENGTH")), toInt(valueOr(column, "ORDINAL_POSITION", 0)), "YES",
                    null, null, null, null, "NO", "NO");
        }
        return withIdColumns(result, columnNamePattern).toResultSet();
    }

    /**
     * Adds the document _id as the first column of every table, shifting the other ordinal positions.
     * It is also reported as the primary key, so DataGrip edits rows with "WHERE _id = ?".
     */
    private MetadataTable withIdColumns(MetadataTable columns, String columnNamePattern) {
        Pattern columnFilter = likeToRegex(columnNamePattern);
        boolean includeId = columnFilter == null || columnFilter.matcher("_id").matches();
        MetadataTable result = columns.emptyCopy();
        Object previousTable = null;
        for (List<Object> row : columns.rows) {
            Object table = row.get(COLUMN_TABLE_NAME);
            if (includeId && !table.equals(previousTable)) {
                result.add(connection.clusterName, null, table, "_id", Types.VARCHAR, "KEYWORD", 512, null, null, 10,
                        columnNoNulls, "Document id", null, Types.VARCHAR, null, null, 1, "NO",
                        null, null, null, null, "NO", "NO");
            }
            previousTable = table;
            List<Object> shifted = new ArrayList<>(row);
            Object position = shifted.get(COLUMN_ORDINAL_POSITION);
            if (includeId && position instanceof Number) {
                shifted.set(COLUMN_ORDINAL_POSITION, ((Number) position).intValue() + 1);
            }
            result.rows.add(shifted);
        }
        return result;
    }

    /** Fallback for clusters without SYS COLUMNS: SHOW COLUMNS per matching table. */
    private void addColumnsFromShowColumns(MetadataTable result, String tableNamePattern, String columnNamePattern) throws SQLException {
        Pattern columnFilter = likeToRegex(columnNamePattern);
        for (TableInfo table : listTables(tableNamePattern)) {
            int position = 0;
            for (Map<String, Object> column : query("SHOW COLUMNS FROM " + quoteIdentifier(table.name))) {
                position++;
                String name = String.valueOf(column.get("COLUMN"));
                if (columnFilter != null && !columnFilter.matcher(name).matches()) {
                    continue;
                }

                String jdbcTypeName = String.valueOf(column.get("TYPE"));
                String mapping = String.valueOf(valueOr(column, "MAPPING", ""));
                int dataType = EsTypes.sqlTypeFromJdbcName(jdbcTypeName);
                if (dataType == Types.OTHER) {
                    dataType = EsTypes.sqlType(mapping);
                }
                String typeName = mapping.isEmpty() ? jdbcTypeName : mapping.toUpperCase(Locale.ROOT);
                result.add(connection.clusterName, null, table.name, name, dataType, typeName,
                        EsTypes.precision(dataType), null, null, 10, columnNullable, "", null, dataType, null,
                        null, position, "YES", null, null, null, null, "NO", "NO");
            }
        }
    }

    private static final class TableInfo {
        final String name;
        final String type;

        TableInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /** Indices (TABLE) and aliases (VIEW) matching the pattern, via SYS TABLES or SHOW TABLES. */
    private List<TableInfo> listTables(String tableNamePattern) throws SQLException {
        try {
            return tablesFromSysTables(tableNamePattern);
        } catch (SQLException sysNotSupported) {
            try {
                return tablesFromShowTables(tableNamePattern);
            } catch (SQLException showFailed) {
                sysNotSupported.addSuppressed(showFailed);
                throw sysNotSupported;
            }
        }
    }

    private List<TableInfo> tablesFromSysTables(String tableNamePattern) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        for (Map<String, Object> row : query("SYS TABLES LIKE " + like(tableNamePattern))) {
            Object name = row.get("TABLE_NAME");
            if (name == null || String.valueOf(name).isEmpty()) {
                continue;
            }
            String type = String.valueOf(valueOr(row, "TABLE_TYPE", "TABLE")).toUpperCase(Locale.ROOT);
            tables.add(new TableInfo(String.valueOf(name), type));
        }
        return tables;
    }

    private List<TableInfo> tablesFromShowTables(String tableNamePattern) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        for (Map<String, Object> row : query("SHOW TABLES LIKE " + like(tableNamePattern))) {
            String type = String.valueOf(valueOr(row, "TYPE", "TABLE")).toUpperCase(Locale.ROOT);
            String kind = String.valueOf(valueOr(row, "KIND", "")).toUpperCase(Locale.ROOT);
            boolean isView = type.contains("VIEW") || kind.equals("ALIAS");
            tables.add(new TableInfo(String.valueOf(row.get("NAME")), isView ? "VIEW" : "TABLE"));
        }
        return tables;
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        MetadataTable result = new MetadataTable("TYPE_NAME").column("DATA_TYPE", Types.INTEGER)
                .column("PRECISION", Types.INTEGER).column("LITERAL_PREFIX").column("LITERAL_SUFFIX")
                .column("CREATE_PARAMS").column("NULLABLE", Types.SMALLINT).column("CASE_SENSITIVE", Types.BOOLEAN)
                .column("SEARCHABLE", Types.SMALLINT).column("UNSIGNED_ATTRIBUTE", Types.BOOLEAN)
                .column("FIXED_PREC_SCALE", Types.BOOLEAN).column("AUTO_INCREMENT", Types.BOOLEAN)
                .column("LOCAL_TYPE_NAME").column("MINIMUM_SCALE", Types.SMALLINT).column("MAXIMUM_SCALE", Types.SMALLINT)
                .column("SQL_DATA_TYPE", Types.INTEGER).column("SQL_DATETIME_SUB", Types.INTEGER)
                .column("NUM_PREC_RADIX", Types.INTEGER);
        List<Map<String, Object>> types;
        try {
            types = query("SYS TYPES");
        } catch (SQLException olderCluster) {
            return result.toResultSet();
        }
        for (Map<String, Object> type : types) {
            result.add(type.get("TYPE_NAME"), toInt(type.get("DATA_TYPE")), toInt(type.get("PRECISION")),
                    type.get("LITERAL_PREFIX"), type.get("LITERAL_SUFFIX"), type.get("CREATE_PARAMS"),
                    toInt(valueOr(type, "NULLABLE", typeNullable)), valueOr(type, "CASE_SENSITIVE", false),
                    toInt(valueOr(type, "SEARCHABLE", typeSearchable)), valueOr(type, "UNSIGNED_ATTRIBUTE", false),
                    valueOr(type, "FIXED_PREC_SCALE", false), valueOr(type, "AUTO_INCREMENT", false),
                    type.get("LOCAL_TYPE_NAME"), toInt(type.get("MINIMUM_SCALE")), toInt(type.get("MAXIMUM_SCALE")),
                    toInt(type.get("SQL_DATA_TYPE")), toInt(type.get("SQL_DATETIME_SUB")),
                    toInt(valueOr(type, "NUM_PREC_RADIX", 10)));
        }
        return result.toResultSet();
    }

    @Override
    public ResultSet getCatalogs() {
        MetadataTable result = new MetadataTable("TABLE_CAT");
        result.add(connection.clusterName);
        return result.toResultSet();
    }

    @Override
    public ResultSet getTableTypes() {
        MetadataTable result = new MetadataTable("TABLE_TYPE");
        result.add("TABLE");
        result.add("VIEW");
        return result.toResultSet();
    }

    @Override
    public ResultSet getSchemas() {
        return empty("TABLE_SCHEM", "TABLE_CATALOG");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) {
        return getSchemas();
    }

    // ---------------------------------------------------------------- empty metadata (no keys, indexes, procedures, ...)

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) {
        MetadataTable result = new MetadataTable("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME")
                .column("KEY_SEQ", Types.SMALLINT).column("PK_NAME");
        if (table == null || isForeignCatalog(catalog) || hasSchema(schema)) {
            return result.toResultSet();
        }
        result.add(connection.clusterName, null, table, "_id", 1, "_id");
        return result.toResultSet();
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable) {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME", "TYPE",
                "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
        return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED_1", "RESERVED_2", "RESERVED_3",
                "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) {
        return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE",
                "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF",
                "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
        return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) {
        return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE",
                "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
                "IS_GRANTABLE");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) {
        return empty("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "PSEUDO_COLUMN");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) {
        return empty("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "PSEUDO_COLUMN");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE",
                "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG",
                "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE");
    }

    @Override
    public ResultSet getClientInfoProperties() {
        return empty("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE",
                "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
    }

    // ---------------------------------------------------------------- helpers

    /** In-memory result set for metadata; columns default to VARCHAR. */
    private static final class MetadataTable {
        private final List<EsColumn> columns = new ArrayList<>();
        private final List<List<Object>> rows = new ArrayList<>();

        MetadataTable(String... varcharColumns) {
            for (String name : varcharColumns) {
                column(name);
            }
        }

        MetadataTable column(String name) {
            return column(name, Types.VARCHAR);
        }

        MetadataTable column(String name, int sqlType) {
            columns.add(EsColumn.of(name, sqlType));
            return this;
        }

        void add(Object... values) {
            rows.add(new ArrayList<>(Arrays.asList(values)));
        }

        MetadataTable emptyCopy() {
            MetadataTable copy = new MetadataTable();
            copy.columns.addAll(columns);
            return copy;
        }

        EsResultSet toResultSet() {
            return new EsResultSet(null, columns, rows);
        }
    }

    private ResultSet empty(String... columnNames) {
        return new MetadataTable(columnNames).toResultSet();
    }

    /** Runs a query and returns its rows as maps keyed by upper-cased column name. */
    private List<Map<String, Object>> query(String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (EsResultSet resultSet = connection.executeSql(sql, null, null, 0, 0, 0)) {
            List<EsColumn> columns = resultSet.columns();
            while (resultSet.next()) {
                List<Object> values = resultSet.currentRow();
                Map<String, Object> row = new HashMap<>();
                for (int i = 0; i < columns.size() && i < values.size(); i++) {
                    row.put(columns.get(i).name.toUpperCase(Locale.ROOT), values.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** LIKE pattern as an SQL literal; adds ESCAPE when the pattern uses the backslash search escape. */
    private static String like(String pattern) {
        String value = pattern == null || pattern.isEmpty() ? "%" : pattern;
        String literal = "'" + value.replace("'", "''") + "'";
        return value.indexOf('\\') >= 0 ? literal + " ESCAPE '\\'" : literal;
    }

    private static Pattern likeToRegex(String pattern) {
        if (pattern == null) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (current == '%') {
                regex.append(".*");
            } else if (current == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private boolean isForeignCatalog(String catalog) {
        return catalog != null && !catalog.isEmpty() && !catalog.equals(connection.clusterName);
    }

    private static boolean hasSchema(String schemaPattern) {
        return schemaPattern != null && !schemaPattern.isEmpty() && !schemaPattern.equals("%");
    }

    private static Set<String> upperCase(String[] values) {
        if (values == null) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value != null) {
                result.add(value.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** Like Map.getOrDefault, but also falls back when the key is present with a null value. */
    private static Object valueOr(Map<String, Object> row, String key, Object defaultValue) {
        Object value = row.get(key);
        return value == null ? defaultValue : value;
    }

    private static Object toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : value;
    }

    private int versionPart(int index) {
        try {
            return Integer.parseInt(connection.esVersion.split("[.-]")[index]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- product information

    @Override
    public String getURL() {
        return connection.url;
    }

    @Override
    public String getUserName() {
        return connection.config.user;
    }

    @Override
    public boolean isReadOnly() {
        return connection.isReadOnly();
    }

    @Override
    public String getDatabaseProductName() {
        return "Elasticsearch";
    }

    @Override
    public String getDatabaseProductVersion() {
        return connection.esVersion;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return versionPart(0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return versionPart(1);
    }

    @Override
    public String getDriverName() {
        return "Elasticsearch REST JDBC";
    }

    @Override
    public String getDriverVersion() {
        return EsDriver.VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return EsDriver.MAJOR;
    }

    @Override
    public int getDriverMinorVersion() {
        return EsDriver.MINOR;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    // ---------------------------------------------------------------- SQL dialect

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogTerm() {
        return "cluster";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return ":";
    }

    @Override
    public String getSQLKeywords() {
        return "ANALYZE,ANALYZED,CATALOGS,COLUMNS,DEBUG,ESCAPE,EXECUTABLE,EXPLAIN,FIRST,FORMAT,FROZEN,FULL,FUNCTIONS,"
                + "GRAPHVIZ,INCLUDE,LAST,LIMIT,MAPPED,OPTIMIZED,PARSED,PHYSICAL,PIVOT,PLAN,QUERY,RLIKE,SHOW,SYS,"
                + "TABLES,TEXT,TOP,TYPE,TYPES,VERIFY";
    }

    @Override
    public String getNumericFunctions() {
        return "ABS,ACOS,ASIN,ATAN,ATAN2,CBRT,CEIL,CEILING,COS,COSH,COT,DEGREES,E,EXP,EXPM1,FLOOR,LOG,LOG10,MOD,PI,"
                + "POWER,RADIANS,RAND,RANDOM,ROUND,SIGN,SIGNUM,SIN,SINH,SQRT,TAN,TRUNCATE";
    }

    @Override
    public String getStringFunctions() {
        return "ASCII,BIT_LENGTH,CHAR,CHAR_LENGTH,CHARACTER_LENGTH,CONCAT,INSERT,LCASE,LEFT,LENGTH,LOCATE,LTRIM,"
                + "OCTET_LENGTH,POSITION,REPEAT,REPLACE,RIGHT,RTRIM,SPACE,STARTS_WITH,SUBSTRING,TRIM,UCASE";
    }

    @Override
    public String getSystemFunctions() {
        return "DATABASE,IFNULL,USER,COALESCE,GREATEST,LEAST,NULLIF,NVL,IIF,CASE";
    }

    @Override
    public String getTimeDateFunctions() {
        return "CURDATE,CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,CURTIME,DATE_ADD,DATE_DIFF,DATE_FORMAT,DATE_PARSE,"
                + "DATE_PART,DATE_TRUNC,DATETIME_FORMAT,DATETIME_PARSE,DAY_NAME,DAY_OF_MONTH,DAY_OF_WEEK,DAY_OF_YEAR,"
                + "EXTRACT,HOUR_OF_DAY,ISO_DAY_OF_WEEK,ISO_WEEK_OF_YEAR,MINUTE_OF_DAY,MINUTE_OF_HOUR,MONTH_NAME,"
                + "MONTH_OF_YEAR,NOW,SECOND_OF_MINUTE,TIME_PARSE,TODAY,WEEK_OF_YEAR,YEAR";
    }

    // ---------------------------------------------------------------- identifiers

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    // ---------------------------------------------------------------- supported SQL features

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return true;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return true;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 1;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    // ---------------------------------------------------------------- unsupported SQL features

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    // ---------------------------------------------------------------- limits (0 = unknown / no limit)

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }
}
