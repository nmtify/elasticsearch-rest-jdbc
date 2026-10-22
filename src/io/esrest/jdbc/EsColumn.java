package io.esrest.jdbc;

import java.sql.JDBCType;
import java.util.Locale;

/** Column description for result sets. */
final class EsColumn {
    final String name;
    final String typeName;
    final int sqlType;

    private EsColumn(String name, String typeName, int sqlType) {
        this.name = name;
        this.typeName = typeName;
        this.sqlType = sqlType;
    }

    /** Column from an Elasticsearch SQL response: {"name": ..., "type": "keyword"}. */
    static EsColumn fromEs(String name, String esType) {
        String typeName = esType == null ? "UNKNOWN" : esType.toUpperCase(Locale.ROOT);
        return new EsColumn(name, typeName, EsTypes.sqlType(esType));
    }

    static EsColumn of(String name, int sqlType, String typeName) {
        return new EsColumn(name, typeName, sqlType);
    }

    /** Column of a driver-generated (metadata or raw REST) result set. */
    static EsColumn of(String name, int sqlType) {
        try {
            return new EsColumn(name, JDBCType.valueOf(sqlType).getName(), sqlType);
        } catch (IllegalArgumentException unknownType) {
            return new EsColumn(name, "OTHER", sqlType);
        }
    }
}
