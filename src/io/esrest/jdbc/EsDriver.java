package io.esrest.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for Elasticsearch that talks to the free REST SQL endpoint (/_sql).
 * URL: jdbc:esrest://host:9200 or jdbc:esrest:https://host:9200
 */
public final class EsDriver implements Driver {
    /** Version from the jar manifest (set by build.ps1 -Version), e.g. "1.2.3". */
    static final String VERSION = versionFromManifest();
    static final int MAJOR = versionPart(0);
    static final int MINOR = versionPart(1);

    static {
        try {
            DriverManager.registerDriver(new EsDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return new EsConnection(url, EsConfig.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return EsConfig.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return EsConfig.propertyInfo();
    }

    @Override
    public int getMajorVersion() {
        return MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw JdbcWrapper.unsupported();
    }

    private static String versionFromManifest() {
        Package driverPackage = EsDriver.class.getPackage();
        String version = driverPackage == null ? null : driverPackage.getImplementationVersion();
        return version == null || version.isEmpty() ? "1.0.0" : version;
    }

    private static int versionPart(int index) {
        try {
            return Integer.parseInt(VERSION.split("[.-]")[index]);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
