import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * End-to-end test of the driver against an in-process mock of the Elasticsearch REST API.
 * Run: java -cp dist/esrest-<version>.jar test/MockEsTest.java   (exit code 1 on failure)
 */
public class MockEsTest {
    static final List<String> requests = new ArrayList<>();
    static volatile long count = 1;
    static int failures;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().toString();
            if (!path.equals("/")) requests.add(ex.getRequestMethod() + " " + path + " " + body);
            int code = 200;
            String resp;
            if (path.equals("/")) {
                resp = "{\"cluster_name\":\"demo\",\"version\":{\"number\":\"8.15.0\"}}";
            } else if (path.startsWith("/_sql/close")) {
                resp = "{\"succeeded\":true}";
            } else if (path.startsWith("/_sql/translate") && body.contains("docs")) {
                String query = body.contains("WHERE") ? "\"query\":{\"term\":{\"status\":{\"value\":\"open\"}}}," : "";
                String sort = body.contains("ORDER BY") ? "[{\"title.keyword\":{\"order\":\"desc\"}}]" : "[{\"_doc\":{\"order\":\"asc\"}}]";
                resp = "{\"size\":1000," + query + "\"_source\":false,\"fields\":[{\"field\":\"title\"}],\"sort\":" + sort + "}";
            } else if (path.startsWith("/_sql/translate")) {
                resp = "{\"query\":{\"term\":{\"id\":{\"value\":7}}}}";
            } else if (path.startsWith("/docs/_field_caps")) {
                resp = "{\"indices\":[\"docs\"],\"fields\":{"
                        + "\"_id\":{\"_id\":{\"type\":\"_id\",\"metadata_field\":true}},"
                        + "\"_index\":{\"_index\":{\"type\":\"_index\",\"metadata_field\":true}},"
                        + "\"@timestamp\":{\"date\":{\"type\":\"date\"}},"
                        + "\"title\":{\"text\":{\"type\":\"text\"}},"
                        + "\"title.keyword\":{\"keyword\":{\"type\":\"keyword\"}},"
                        + "\"user\":{\"object\":{\"type\":\"object\"}},"
                        + "\"user.name\":{\"keyword\":{\"type\":\"keyword\"}},"
                        + "\"user.address\":{\"object\":{\"type\":\"object\"}},"
                        + "\"user.address.city\":{\"keyword\":{\"type\":\"keyword\"}},"
                        + "\"comments\":{\"nested\":{\"type\":\"nested\"}},"
                        + "\"comments.text\":{\"text\":{\"type\":\"text\"}}}}";
            } else if (path.startsWith("/docs/_search") && body.contains("aggs")) {
                resp = "{\"hits\":{\"hits\":[]},\"aggregations\":{"
                        + "\"by_status\":{\"buckets\":[{\"key\":\"open\",\"doc_count\":3,\"avg_score\":{\"value\":7.5}},"
                        + "{\"key\":\"closed\",\"doc_count\":1,\"avg_score\":{\"value\":2.0}}]},"
                        + "\"total\":{\"value\":4}}}";
            } else if (path.startsWith("/docs/_search")) {
                resp = "{\"hits\":{\"hits\":[{\"_index\":\"docs\",\"_id\":\"d1\",\"_source\":{\"@timestamp\":\"2024-01-01T00:00:00Z\","
                        + "\"title\":\"Hello\",\"user\":{\"name\":\"alice\",\"address\":{\"city\":\"HN\"}},\"comments\":[{\"text\":\"hi\"}]}},"
                        + "{\"_index\":\"docs\",\"_id\":\"d2\",\"_source\":{\"title\":\"World\",\"user.name\":\"bob\"}}]}}";
            } else if (path.startsWith("/_sql")) {
                if (body.contains("\"cursor\":\"c1\"")) {
                    resp = "{\"rows\":[[3,\"c\",\"2024-01-03T00:00:00.000Z\",null]]}";
                } else if (body.contains("SYS TABLES")) {
                    resp = "{\"columns\":[{\"name\":\"TABLE_CAT\",\"type\":\"keyword\"},{\"name\":\"TABLE_NAME\",\"type\":\"keyword\"},{\"name\":\"TABLE_TYPE\",\"type\":\"keyword\"}],"
                            + "\"rows\":[[\"demo\",\"logs-2024\",\"TABLE\"],[\"demo\",\"logs\",\"VIEW\"]]}";
                } else if (body.contains("SYS COLUMNS")) {
                    code = 400;
                    resp = "{\"error\":{\"root_cause\":[{\"type\":\"parsing_exception\",\"reason\":\"no SYS\"}]},\"status\":400}";
                } else if (body.contains("SHOW COLUMNS")) {
                    resp = "{\"columns\":[{\"name\":\"column\",\"type\":\"keyword\"},{\"name\":\"type\",\"type\":\"keyword\"},{\"name\":\"mapping\",\"type\":\"keyword\"}],"
                            + "\"rows\":[[\"id\",\"BIGINT\",\"long\"],[\"msg\",\"VARCHAR\",\"text\"]]}";
                } else if (body.contains("bad")) {
                    code = 400;
                    resp = "{\"error\":{\"root_cause\":[{\"type\":\"verification_exception\",\"reason\":\"Unknown index [bad]\"}]},\"status\":400}";
                } else {
                    resp = "{\"columns\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"msg\",\"type\":\"text\"},{\"name\":\"@timestamp\",\"type\":\"datetime\"},{\"name\":\"obj\",\"type\":\"object\"}],"
                            + "\"rows\":[[1,\"a\\\"q\",\"2024-01-01T10:00:00.123Z\",{\"k\":[1,2]}],[2,\"b\",\"2024-01-02T00:00:00.000+07:00\",null]],\"cursor\":\"c1\"}";
                }
            } else if (path.contains("/_count")) {
                resp = "{\"count\":" + count + "}";
            } else if (path.contains("/_update_by_query")) {
                resp = "{\"updated\":1,\"failures\":[]}";
            } else if (path.contains("/_delete_by_query")) {
                resp = "{\"deleted\":1,\"failures\":[]}";
            } else if (path.contains("/_doc") || path.contains("/_create")) {
                code = 201;
                resp = "{\"result\":\"created\"}";
            } else if (path.contains("/_search")) {
                resp = "{\"hits\":{\"hits\":[{\"_index\":\"logs\",\"_id\":\"x1\",\"_score\":1.0,\"_source\":{\"level\":\"INFO\",\"n\":5}}]}}";
            } else {
                code = 404;
                resp = "{}";
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        try {
            run("jdbc:esrest://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    static void run(String url) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "elastic");
        p.setProperty("password", "pw");
        try (Connection c = DriverManager.getConnection(url + "?timezone=Asia/Ho_Chi_Minh", p)) {
            DatabaseMetaData md = c.getMetaData();
            check("product version", "8.15.0".equals(md.getDatabaseProductVersion()));
            check("catalog is cluster name", "demo".equals(c.getCatalog()));
            check("jdbc:es: alias accepted", DriverManager.getDriver("jdbc:es://localhost:9200") != null);

            // Query + cursor paging + t.* rewrite
            Statement st = c.createStatement();
            st.setFetchSize(2);
            requests.clear();
            ResultSet rs = st.executeQuery("SELECT t.* FROM \"logs-2024\" t;");
            check("t.* rewritten", requests.get(0).contains("SELECT * FROM \\\"logs-2024\\\" t"));
            check("time zone sent", requests.get(0).contains("Asia/Ho_Chi_Minh"));
            check("column type", rs.getMetaData().getColumnType(3) == Types.TIMESTAMP);
            int rows = 0;
            String firstMsg = null;
            Object obj = null;
            while (rs.next()) {
                if (rows++ == 0) {
                    firstMsg = rs.getString("msg");
                    obj = rs.getObject(4);
                }
            }
            check("rows across pages", rows == 3);
            check("escaped string", "a\"q".equals(firstMsg));
            check("object as JSON", "{\"k\":[1,2]}".equals(obj));

            st.setMaxRows(1);
            rs = st.executeQuery("SELECT * FROM x");
            rows = 0;
            while (rs.next()) rows++;
            check("maxRows", rows == 1);
            st.setMaxRows(0);

            // Prepared statement params
            PreparedStatement ps = c.prepareStatement("SELECT * FROM x WHERE id = ?");
            ps.setInt(1, 7);
            requests.clear();
            ps.executeQuery().close();
            check("params sent", requests.get(0).contains("\"params\":[{\"type\":\"integer\",\"value\":7}]"));

            // Errors
            try {
                st.executeQuery("SELECT * FROM bad");
                check("error raised", false);
            } catch (SQLException e) {
                check("error message", e.getMessage().contains("Unknown index [bad]"));
            }

            // Metadata
            rs = md.getTables(null, null, "%", new String[]{"TABLE"});
            rows = 0;
            while (rs.next()) rows++;
            check("getTables filters by type", rows == 1);
            rs = md.getColumns(null, null, "logs-2024", "%");
            rows = 0;
            while (rs.next()) rows++;
            check("getColumns fallback", rows >= 2);

            // Raw REST
            rs = st.executeQuery("POST /logs/_search\n{\"query\":{\"match_all\":{}}}");
            check("search hit", rs.next() && "x1".equals(rs.getString("_id")) && "INFO".equals(rs.getString("level")));

            // DML
            requests.clear();
            ps = c.prepareStatement("UPDATE \"idx\" t SET t.a = ?, meta.score = 1 WHERE t.id = ?");
            ps.setString(1, "v");
            ps.setLong(2, 7);
            check("update count", ps.executeUpdate() == 1);
            check("update uses translate", requests.get(0).startsWith("POST /_sql/translate"));
            check("update_by_query", requests.get(2).contains("/idx/_update_by_query") && requests.get(2).contains("\"path\":[\"meta\",\"score\"]"));

            check("delete count", st.executeUpdate("DELETE FROM idx WHERE id = 7") == 1);

            requests.clear();
            check("insert count", st.executeUpdate("INSERT INTO idx (_id, a, b.c) VALUES ('x1', 'v', 2)") == 1);
            check("insert by id", requests.get(0).startsWith("PUT /idx/_create/x1") && requests.get(0).contains("\"b\":{\"c\":2}"));

            documentSearch(c);

            count = 5;
            requests.clear();
            try {
                st.executeUpdate("UPDATE idx SET a = 1");
                check("guard raised", false);
            } catch (SQLException e) {
                check("guard message", e.getMessage().contains("maxAffectedDocs"));
                check("guard changed nothing", requests.stream().noneMatch(r -> r.contains("_update_by_query")));
            }

            c.setReadOnly(true);
            try {
                st.executeUpdate("DELETE FROM idx WHERE a = 1");
                check("readOnly raised", false);
            } catch (SQLException e) {
                check("readOnly message", e.getMessage().contains("read-only"));
            }
        }
    }

    /** SELECT * runs as _search: _id column, columns from the mapping, JSON objects, sort, _id filters and edits. */
    static void documentSearch(Connection c) throws SQLException {
        Statement st = c.createStatement();
        st.setMaxRows(50);
        requests.clear();
        ResultSet rs = st.executeQuery("SELECT t.* FROM \"docs\" t WHERE status = 'open' ORDER BY title DESC");
        String search = requests.stream().filter(r -> r.startsWith("POST /docs/_search")).findFirst().orElse("");
        check("search uses translated query", search.contains("{\"term\":{\"status\":{\"value\":\"open\"}}}"));
        check("search uses translated sort", search.contains("\"sort\":[{\"title.keyword\":{\"order\":\"desc\"}}]"));
        check("search size from maxRows", search.contains("\"from\":0,\"size\":50"));

        java.sql.ResultSetMetaData meta = rs.getMetaData();
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnName(i));
        }
        check("document columns " + names, names.equals(List.of("_id", "@timestamp", "comments", "title", "user.address", "user.name")));
        check("date column is TIMESTAMP", meta.getColumnType(2) == Types.TIMESTAMP);
        check("nested column is JSON (OTHER)", meta.getColumnType(3) == Types.OTHER && "NESTED".equals(meta.getColumnTypeName(3)));

        check("first row", rs.next() && "d1".equals(rs.getString("_id")) && "Hello".equals(rs.getString("title")));
        check("nested kept as JSON array", "[{\"text\":\"hi\"}]".equals(rs.getString("comments")));
        check("deep object as JSON", "{\"city\":\"HN\"}".equals(rs.getString("user.address")));
        check("flattened leaf", "alice".equals(rs.getString("user.name")));
        check("timestamp parsed", rs.getTimestamp("@timestamp") != null);
        check("second row with dotted source key", rs.next() && "d2".equals(rs.getString(1)) && "bob".equals(rs.getString("user.name")));
        check("no more rows", !rs.next());

        requests.clear();
        st.executeQuery("SELECT * FROM docs WHERE _id = 'd1' AND status = 'open'").close();
        String translate = requests.stream().filter(r -> r.startsWith("POST /_sql/translate")).findFirst().orElse("");
        search = requests.stream().filter(r -> r.startsWith("POST /docs/_search")).findFirst().orElse("");
        check("_id removed before translate", translate.contains("WHERE status = 'open'") && !translate.contains("_id"));
        check("ids query combined", search.contains("{\"bool\":{\"filter\":[{\"ids\":{\"values\":[\"d1\"]}}"));

        requests.clear();
        st.executeQuery("SELECT * FROM docs ORDER BY _id DESC").close();
        search = requests.stream().filter(r -> r.startsWith("POST /docs/_search")).findFirst().orElse("");
        check("sort by _id", search.contains("\"sort\":[{\"_id\":{\"order\":\"desc\"}}]"));

        // Opening an index (no WHERE / ORDER BY) is a single _search: no translate, mapping from the cache
        requests.clear();
        st.executeQuery("SELECT * FROM docs").close();
        check("plain SELECT * is one request " + requests, requests.size() == 1 && requests.get(0).startsWith("POST /docs/_search"));
        check("plain SELECT * in index order", requests.get(0).contains("\"sort\":[{\"_doc\":{\"order\":\"asc\"}}]"));

        requests.clear();
        st.setMaxRows(0);
        st.executeQuery("SELECT COUNT(*) FROM docs").close();
        check("aggregate query stays ES SQL", requests.get(0).startsWith("POST /_sql?format=json"));

        // Edits by _id (DataGrip uses _id as the primary key)
        count = 1;
        requests.clear();
        PreparedStatement ps = c.prepareStatement("UPDATE docs SET user.address = ? WHERE _id = ?");
        ps.setString(1, "{\"city\":\"HCM\"}");
        ps.setString(2, "d1");
        check("update by _id", ps.executeUpdate() == 1);
        check("update by _id skips translate", requests.stream().noneMatch(r -> r.contains("/_sql/translate")));
        check("update by _id uses ids query", requests.get(0).contains("{\"query\":{\"ids\":{\"values\":[\"d1\"]}}}"));
        check("JSON text written as object", requests.get(1).contains("\"value\":{\"city\":\"HCM\"}"));
        try {
            st.executeUpdate("UPDATE docs SET _id = 'x' WHERE _id = 'd1'");
            check("changing _id refused", false);
        } catch (SQLException e) {
            check("changing _id refused", e.getMessage().contains("_id"));
        }

        requests.clear();
        check("insert with DEFAULT _id", st.executeUpdate("INSERT INTO docs (_id, title) VALUES (DEFAULT, 'x')") == 1);
        check("generated _id", requests.get(0).startsWith("POST /docs/_doc"));

        // Metadata: _id column and primary key
        DatabaseMetaData md = c.getMetaData();
        rs = md.getPrimaryKeys(null, null, "docs");
        check("primary key _id", rs.next() && "_id".equals(rs.getString("COLUMN_NAME")));
        rs = md.getColumns(null, null, "logs-2024", "%");
        check("_id first column", rs.next() && "_id".equals(rs.getString("COLUMN_NAME")) && rs.getInt("ORDINAL_POSITION") == 1);
        check("positions shifted", rs.next() && rs.getInt("ORDINAL_POSITION") == 2);

        // Raw REST: aggregations and single objects
        rs = st.executeQuery("POST /docs/_search\n{\"size\":0,\"aggs\":{\"by_status\":{\"terms\":{\"field\":\"status\"}}}}");
        check("aggregation bucket row", rs.next() && "by_status".equals(rs.getString("aggregation"))
                && "open".equals(rs.getString("key")) && rs.getLong("doc_count") == 3 && rs.getDouble("avg_score") == 7.5);
        check("second bucket", rs.next() && "closed".equals(rs.getString("key")));
        check("metric aggregation row", rs.next() && "total".equals(rs.getString("aggregation")) && rs.getLong("value") == 4);
        rs = st.executeQuery("GET /docs/_count");
        check("_count as a row", rs.next() && rs.getLong("count") == 1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
}
