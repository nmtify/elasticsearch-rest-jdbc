package io.esrest.jdbc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Connection settings parsed from the JDBC URL and properties.
 *
 * <pre>
 * jdbc:esrest://host:9200[/path][?property=value&amp;...]       (http, or https when ssl=true)
 * jdbc:esrest:https://host:9200[/path][?property=value&amp;...]  (explicit scheme)
 * jdbc:es://host:9200/...                                        (prefix of the official driver, also accepted)
 * </pre>
 */
final class EsConfig {
    static final String PREFIX = "jdbc:esrest:";
    static final String OFFICIAL_PREFIX = "jdbc:es:";
    private static final int DEFAULT_PORT = 9200;

    String baseUrl;
    String user;
    String password;
    String apiKey;
    boolean trustAll;
    String caCertPath;
    String timeZone = "Z";
    int fetchSize = 1000;
    int connectTimeoutMillis = 10_000;
    int readTimeoutMillis = 300_000;
    boolean fieldMultiValueLeniency = true;
    boolean readOnly;
    int maxAffectedDocs = 1;

    static boolean accepts(String url) {
        return prefixOf(url) != null;
    }

    static EsConfig parse(String url, Properties info) throws SQLException {
        String prefix = prefixOf(url);
        if (prefix == null) {
            throw invalidUrl();
        }

        String address = url.trim().substring(prefix.length());
        String query = null;
        int queryStart = address.indexOf('?');
        if (queryStart >= 0) {
            query = address.substring(queryStart + 1);
            address = address.substring(0, queryStart);
        }
        Properties properties = merge(query, info);

        EsConfig config = new EsConfig();
        config.baseUrl = baseUrl(address, bool(properties, "ssl", false));
        config.user = properties.getProperty("user");
        config.password = properties.getProperty("password");
        config.apiKey = properties.getProperty("apiKey");
        config.trustAll = bool(properties, "trustAll", false);
        config.caCertPath = properties.getProperty("caCertPath");
        config.timeZone = properties.getProperty("timezone", config.timeZone);
        config.fetchSize = integer(properties, "fetchSize", config.fetchSize);
        config.connectTimeoutMillis = integer(properties, "connectTimeout", config.connectTimeoutMillis);
        config.readTimeoutMillis = integer(properties, "readTimeout", config.readTimeoutMillis);
        config.fieldMultiValueLeniency = bool(properties, "fieldMultiValueLeniency", config.fieldMultiValueLeniency);
        config.readOnly = bool(properties, "readOnly", config.readOnly);
        config.maxAffectedDocs = integer(properties, "maxAffectedDocs", config.maxAffectedDocs);
        return config;
    }

    static DriverPropertyInfo[] propertyInfo() {
        return new DriverPropertyInfo[] {
            describe("user", null, "Basic auth user name"),
            describe("password", null, "Basic auth password"),
            describe("apiKey", null, "Base64 encoded API key (id:api_key), sent as 'Authorization: ApiKey ...'"),
            describe("ssl", "false", "Use https when the URL has no scheme (jdbc:esrest://host:port)"),
            describe("trustAll", "false", "Trust any TLS certificate and skip host name verification (self-signed clusters)"),
            describe("caCertPath", null, "Path to a PEM CA certificate (e.g. config/certs/http_ca.crt)"),
            describe("timezone", "Z", "Time zone used by Elasticsearch SQL, e.g. Asia/Ho_Chi_Minh"),
            describe("fetchSize", "1000", "Rows per page fetched from Elasticsearch"),
            describe("connectTimeout", "10000", "Connect timeout in milliseconds"),
            describe("readTimeout", "300000", "Read timeout in milliseconds"),
            describe("fieldMultiValueLeniency", "true", "Return the first value of array fields instead of failing"),
            describe("readOnly", "false", "Reject INSERT/UPDATE/DELETE"),
            describe("maxAffectedDocs", "1", "Maximum documents a single UPDATE/DELETE may change; larger matches are refused"),
        };
    }

    // ---------------------------------------------------------------- helpers

    private static String prefixOf(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return PREFIX;
        }
        if (trimmed.regionMatches(true, 0, OFFICIAL_PREFIX, 0, OFFICIAL_PREFIX.length())) {
            return OFFICIAL_PREFIX;
        }
        return null;
    }

    private static SQLException invalidUrl() {
        return new SQLException("Invalid URL, expected " + PREFIX + "//host:port or " + PREFIX + "https://host:port", "08001");
    }

    /** URL query parameters first, then connection properties (which win when not empty). */
    private static Properties merge(String query, Properties info) {
        Properties properties = new Properties();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                properties.setProperty(decode(name), decode(value));
            }
        }
        if (info == null) {
            return properties;
        }
        for (String name : info.stringPropertyNames()) {
            String value = info.getProperty(name);
            if (value != null && !value.isEmpty()) {
                properties.setProperty(name, value);
            }
        }
        return properties;
    }

    /** "//host[:port][/path]" or "http(s)://host[:port][/path]" to "scheme://host:port/path" without trailing slash. */
    private static String baseUrl(String address, boolean ssl) throws SQLException {
        String url;
        if (address.startsWith("http://") || address.startsWith("https://")) {
            url = address;
        } else if (address.startsWith("//")) {
            url = (ssl ? "https:" : "http:") + withDefaultPort(address);
        } else {
            throw invalidUrl();
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String withDefaultPort(String address) {
        String hostAndPath = address.substring(2);
        int slash = hostAndPath.indexOf('/');
        String authority = slash < 0 ? hostAndPath : hostAndPath.substring(0, slash);
        String path = slash < 0 ? "" : hostAndPath.substring(slash);
        if (authority.isEmpty()) {
            authority = "localhost";
        }
        boolean hasPort = authority.startsWith("[") ? authority.contains("]:") : authority.contains(":");
        if (!hasPort) {
            authority += ":" + DEFAULT_PORT;
        }
        return "//" + authority + path;
    }

    private static DriverPropertyInfo describe(String name, String defaultValue, String description) {
        DriverPropertyInfo info = new DriverPropertyInfo(name, defaultValue);
        info.description = description;
        return info;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean bool(Properties properties, String name, boolean defaultValue) {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static int integer(Properties properties, String name, int defaultValue) throws SQLException {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid value for " + name + ": " + value, "08001");
        }
    }
}
