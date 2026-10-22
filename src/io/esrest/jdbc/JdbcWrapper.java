package io.esrest.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Wrapper;

/** Shared {@link Wrapper} implementation and error helpers for the JDBC classes. */
abstract class JdbcWrapper implements Wrapper {

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (!type.isInstance(this)) {
            throw new SQLException("Not a wrapper for " + type.getName());
        }
        return type.cast(this);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("Not supported by the Elasticsearch REST driver");
    }

    static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message);
    }
}
