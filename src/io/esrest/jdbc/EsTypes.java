package io.esrest.jdbc;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/** Mapping between Elasticsearch SQL types and JDBC types. */
final class EsTypes {
    private static final Map<String, Integer> SQL_TYPES = Map.ofEntries(
            Map.entry("null", Types.NULL),
            Map.entry("boolean", Types.BOOLEAN),
            Map.entry("byte", Types.TINYINT),
            Map.entry("short", Types.SMALLINT),
            Map.entry("integer", Types.INTEGER),
            Map.entry("long", Types.BIGINT),
            Map.entry("unsigned_long", Types.NUMERIC),
            Map.entry("double", Types.DOUBLE),
            Map.entry("scaled_float", Types.DOUBLE),
            Map.entry("float", Types.REAL),
            Map.entry("half_float", Types.FLOAT),
            Map.entry("keyword", Types.VARCHAR),
            Map.entry("text", Types.VARCHAR),
            Map.entry("constant_keyword", Types.VARCHAR),
            Map.entry("wildcard", Types.VARCHAR),
            Map.entry("match_only_text", Types.VARCHAR),
            Map.entry("ip", Types.VARCHAR),
            Map.entry("version", Types.VARCHAR),
            Map.entry("flattened", Types.VARCHAR),
            Map.entry("binary", Types.VARBINARY),
            Map.entry("datetime", Types.TIMESTAMP),
            Map.entry("date_nanos", Types.TIMESTAMP),
            Map.entry("date", Types.DATE),
            Map.entry("time", Types.TIME));

    private EsTypes() { }

    /** ES SQL type name (e.g. "keyword") to java.sql.Types; object, nested, geo and interval types map to OTHER. */
    static int sqlType(String esType) {
        if (esType == null) {
            return Types.OTHER;
        }
        return SQL_TYPES.getOrDefault(esType.toLowerCase(Locale.ROOT), Types.OTHER);
    }

    /** JDBC type name as printed by SHOW COLUMNS (e.g. VARCHAR, TIMESTAMP) to java.sql.Types. */
    static int sqlTypeFromJdbcName(String name) {
        if (name == null) {
            return Types.OTHER;
        }
        try {
            return JDBCType.valueOf(name.trim().toUpperCase(Locale.ROOT)).getVendorTypeNumber();
        } catch (IllegalArgumentException unknown) {
            return Types.OTHER;
        }
    }

    static String className(int sqlType) {
        switch (sqlType) {
            case Types.BOOLEAN: return Boolean.class.getName();
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER: return Integer.class.getName();
            case Types.BIGINT: return Long.class.getName();
            case Types.NUMERIC:
            case Types.DECIMAL: return BigDecimal.class.getName();
            case Types.REAL: return Float.class.getName();
            case Types.FLOAT:
            case Types.DOUBLE: return Double.class.getName();
            case Types.VARBINARY: return byte[].class.getName();
            case Types.TIMESTAMP: return Timestamp.class.getName();
            case Types.DATE: return Date.class.getName();
            case Types.TIME: return Time.class.getName();
            default: return String.class.getName();
        }
    }

    static int precision(int sqlType) {
        switch (sqlType) {
            case Types.BOOLEAN: return 1;
            case Types.TINYINT: return 3;
            case Types.SMALLINT: return 5;
            case Types.INTEGER: return 10;
            case Types.BIGINT: return 19;
            case Types.NUMERIC: return 20;
            case Types.REAL: return 7;
            case Types.FLOAT:
            case Types.DOUBLE: return 15;
            case Types.TIMESTAMP: return 29;
            case Types.DATE: return 10;
            case Types.TIME: return 18;
            default: return 32766;
        }
    }

    static int displaySize(int sqlType) {
        switch (sqlType) {
            case Types.BOOLEAN: return 5;
            case Types.TINYINT: return 4;
            case Types.SMALLINT: return 6;
            case Types.INTEGER: return 11;
            case Types.BIGINT: return 20;
            case Types.NUMERIC: return 21;
            case Types.REAL: return 15;
            case Types.FLOAT:
            case Types.DOUBLE: return 25;
            default: return precision(sqlType);
        }
    }

    static int scale(int sqlType) {
        switch (sqlType) {
            case Types.FLOAT:
            case Types.DOUBLE: return 15;
            case Types.REAL: return 7;
            case Types.TIMESTAMP: return 3;
            default: return 0;
        }
    }

    static boolean isSigned(int sqlType) {
        switch (sqlType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE: return true;
            default: return false;
        }
    }

    /** Parses the date/time formats returned by ES SQL (ISO with offset or zone, local date-time, or date only). */
    static ZonedDateTime parseDateTime(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException notZoned) {
            // try the next format
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException notLocalDateTime) {
            // try the next format
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC);
        } catch (DateTimeParseException notDate) {
            // last attempt below
        }
        return Instant.parse(value).atZone(ZoneOffset.UTC);
    }

    static LocalTime parseTime(String value) {
        try {
            return OffsetTime.parse(value).toLocalTime();
        } catch (DateTimeParseException notOffsetTime) {
            // try the next format
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException notLocalTime) {
            // last attempt below
        }
        return parseDateTime(value).toLocalTime();
    }
}
