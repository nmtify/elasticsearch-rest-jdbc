package io.esrest.jdbc;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Supplies the pages that follow the first page of a result set. */
interface PageSource {

    /** Next page of rows, or null when there are no more rows. */
    List<List<Object>> nextPage() throws SQLException;

    boolean hasMorePages();

    /** Releases server-side resources (e.g. an open SQL cursor). */
    void close();

    /** Pages of an Elasticsearch SQL query, fetched with the cursor returned by the previous page. */
    final class SqlCursor implements PageSource {
        private final EsClient client;
        private String cursor;

        SqlCursor(EsClient client, String cursor) {
            this.client = client;
            this.cursor = cursor;
        }

        /** Returns a page source for the response's cursor, or null when the response was the last page. */
        static PageSource of(EsClient client, Map<String, Object> response) {
            Object cursor = response.get("cursor");
            return cursor == null ? null : new SqlCursor(client, (String) cursor);
        }

        @Override
        public List<List<Object>> nextPage() throws SQLException {
            if (cursor == null) {
                return null;
            }
            Map<String, Object> response = client.sql(Map.of("cursor", cursor));
            Object nextCursor = response.get("cursor");
            cursor = nextCursor == null ? null : (String) nextCursor;
            return EsConnection.rowsOf(response);
        }

        @Override
        public boolean hasMorePages() {
            return cursor != null;
        }

        @Override
        public void close() {
            if (cursor == null) {
                return;
            }
            client.closeCursor(cursor);
            cursor = null;
        }
    }
}
