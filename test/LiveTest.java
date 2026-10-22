import java.sql.*;
import java.util.Properties;

public class LiveTest {
    public static void main(String[] a) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", System.getenv("ES_USER"));
        p.setProperty("password", System.getenv("ES_PASS"));
        p.setProperty("trustAll", "true");
        try (Connection c = DriverManager.getConnection(System.getenv("ES_URL"), p)) {
            DatabaseMetaData md = c.getMetaData();
            System.out.println("Connected: " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion() + ", cluster=" + c.getCatalog());

            String first = null;
            int n = 0;
            try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", null)) {
                while (rs.next()) {
                    if (n++ < 15) System.out.println("  table: " + rs.getString("TABLE_NAME") + " (" + rs.getString("TABLE_TYPE") + ")");
                    if (first == null && "TABLE".equals(rs.getString("TABLE_TYPE"))) first = rs.getString("TABLE_NAME");
                }
            }
            System.out.println("Total tables/views: " + n);
            if (first == null) return;

            System.out.println("Columns of " + first + ":");
            int k = 0;
            try (ResultSet rs = md.getColumns(c.getCatalog(), null, first.replace("_", "\\_"), "%")) {
                while (rs.next() && k++ < 15)
                    System.out.println("  " + rs.getString("COLUMN_NAME") + " " + rs.getString("TYPE_NAME") + " (" + rs.getInt("DATA_TYPE") + ")");
            }

            try (Statement st = c.createStatement()) {
                st.setMaxRows(5);
                try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + first + "\"")) {
                    ResultSetMetaData m = rs.getMetaData();
                    int cols = Math.min(m.getColumnCount(), 6);
                    StringBuilder h = new StringBuilder("SELECT * FROM \"" + first + "\" ->");
                    for (int i = 1; i <= cols; i++) h.append(' ').append(m.getColumnName(i));
                    System.out.println(h);
                    while (rs.next()) {
                        StringBuilder r = new StringBuilder("  ");
                        for (int i = 1; i <= cols; i++) {
                            String v = String.valueOf(rs.getObject(i));
                            r.append(v.length() > 40 ? v.substring(0, 40) + "…" : v).append(" | ");
                        }
                        System.out.println(r);
                    }
                }
                try (ResultSet rs = st.executeQuery("GET /_cluster/health")) {
                    while (rs.next()) System.out.println("Health: " + rs.getString(1));
                }
            }
            System.out.println("OK");
        }
    }
}
