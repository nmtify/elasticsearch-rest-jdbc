package io.esrest.jdbc;

import io.esrest.jdbc.SqlTokens.Kind;
import io.esrest.jdbc.SqlTokens.Token;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs {@code SELECT * FROM index [alias] [WHERE ...] [ORDER BY ...] [LIMIT n]} (what DataGrip sends when you open an
 * index) through {@code _search} instead of ES SQL, so that:
 * <ul>
 *   <li>every row carries its document {@code _id} as the first column (ES SQL can't return it);</li>
 *   <li>columns come from {@code _source}: objects are flattened to {@code a.b} columns up to
 *       {@link #MAX_FLATTEN_DEPTH} levels, deeper objects and {@code nested} fields are shown as JSON;</li>
 *   <li>WHERE and ORDER BY keep exact ES SQL semantics: Elasticsearch translates them ({@code POST /_sql/translate}).</li>
 * </ul>
 * Other queries (functions, GROUP BY, explicit columns, unbounded result sizes) run as plain ES SQL.
 */
final class DocumentSearch {
    static final int MAX_FLATTEN_DEPTH = 2;
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final Set<String> METADATA_FIELDS = Set.of("_id", "_index", "_source", "_routing", "_seq_no",
            "_version", "_field_names", "_ignored", "_doc_count", "_tier", "_primary_term", "_data_stream_timestamp",
            "_feature", "_nested_path", "_ignored_source");

    private final EsConnection connection;

    private DocumentSearch(EsConnection connection) {
        this.connection = connection;
    }

    /** Executes the query as a document search, or returns null when it must run as plain ES SQL. */
    static EsResultSet tryExecute(EsConnection connection, String sql, List<Map<String, Object>> params,
                                  EsStatement statement, int fetchSize, int maxRows) throws SQLException {
        SelectStar select = SelectStar.parse(sql, connection.clusterName);
        if (select == null) {
            return null;
        }
        return new DocumentSearch(connection).execute(select, params == null ? List.of() : params, statement, fetchSize, maxRows);
    }

    private EsResultSet execute(SelectStar select, List<Map<String, Object>> params, EsStatement statement,
                                int fetchSize, int maxRows) throws SQLException {
        IdCondition idCondition = select.where == null
                ? null
                : IdCondition.extract(select.where, params);
        boolean filtersById = idCondition != null && idCondition.ids != null;

        int limit = resultLimit(select.limit, maxRows, filtersById ? idCondition.ids.size() : 0);
        if (limit <= 0 || limit > MAX_RESULT_WINDOW) {
            return null; // unbounded or too large for from/size paging: plain ES SQL handles it
        }

        Map<String, Object> translated;
        List<DocumentColumn> columns;
        try {
            translated = translate(select, idCondition, params);
            columns = columns(select.index);
        } catch (SQLException e) {
            if (filtersById) {
                throw e;
            }
            return null; // let ES SQL report the problem in its own words
        }
        if (translated.containsKey("aggregations") || translated.containsKey("script_fields")) {
            return null;
        }

        Map<String, Object> query = combine(asMap(translated.get("query")), filtersById ? idCondition.ids : null);
        List<Object> sort = sort(translated.get("sort"), select.idSortOrder);
        int pageSize = Math.min(fetchSize > 0 ? fetchSize : connection.config.fetchSize, limit);
        SearchPages pages = new SearchPages(select.index, query, sort, columns, pageSize, limit);
        List<List<Object>> firstPage = pages.nextPage();

        List<EsColumn> resultColumns = new ArrayList<>();
        resultColumns.add(EsColumn.fromEs("_id", "keyword"));
        for (DocumentColumn column : columns) {
            resultColumns.add(column.toEsColumn());
        }
        return new EsResultSet(statement, resultColumns, firstPage == null ? List.of() : firstPage, pages, maxRows);
    }

    private static int resultLimit(Integer sqlLimit, int maxRows, int idCount) {
        int limit = Integer.MAX_VALUE;
        if (sqlLimit != null) {
            limit = sqlLimit;
        }
        if (maxRows > 0) {
            limit = Math.min(limit, maxRows);
        }
        if (idCount > 0) {
            limit = Math.min(limit, idCount);
        }
        return limit == Integer.MAX_VALUE ? -1 : limit;
    }

    // ---------------------------------------------------------------- query translation

    /** Translates WHERE (without the _id filter) and ORDER BY (without _id) into query DSL. */
    private Map<String, Object> translate(SelectStar select, IdCondition idCondition, List<Map<String, Object>> params)
            throws SQLException {
        String condition = idCondition == null ? null : idCondition.remainingCondition;
        List<Map<String, Object>> conditionParams = idCondition == null ? List.of() : idCondition.remainingParams;
        if (condition == null && select.orderBy == null) {
            return Map.of(); // nothing to translate: match_all in index order, saves a round trip
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(select.fromText);
        if (condition != null) {
            sql.append(" WHERE ").append(condition);
        }
        if (select.orderBy != null) {
            sql.append(" ORDER BY ").append(select.orderBy);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", sql.toString());
        body.put("time_zone", connection.config.timeZone);
        if (!conditionParams.isEmpty()) {
            body.put("params", conditionParams);
        }
        Object response = connection.client.json("POST", "/_sql/translate", Json.write(body));
        return asMap(response);
    }

    private static Map<String, Object> combine(Map<String, Object> query, List<String> ids) {
        if (ids == null) {
            return query.isEmpty() ? Map.of("match_all", Map.of()) : query;
        }
        Map<String, Object> idsQuery = Map.of("ids", Map.of("values", ids));
        if (query.isEmpty()) {
            return idsQuery;
        }
        List<Object> filters = List.of(idsQuery, query);
        return Map.of("bool", Map.of("filter", filters));
    }

    /**
     * Translated sort plus an optional _id sort (ORDER BY _id is not known to ES SQL, so it is applied here).
     * Without any sort, documents come in index order (_doc), the cheapest order for paging.
     */
    private static List<Object> sort(Object translatedSort, String idSortOrder) {
        List<Object> sort = new ArrayList<>();
        if (translatedSort instanceof List) {
            sort.addAll((List<?>) translatedSort);
        }
        if (idSortOrder == null) {
            if (sort.isEmpty()) {
                sort.add(Map.of("_doc", Map.of("order", "asc")));
            }
            return sort;
        }
        sort.removeIf(item -> item instanceof Map && ((Map<?, ?>) item).containsKey("_doc"));
        sort.add(Map.of("_id", Map.of("order", idSortOrder)));
        return sort;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    // ---------------------------------------------------------------- columns from the mapping

    /** A result column: a field path plus the ES type used for JDBC typing. */
    private static final class DocumentColumn {
        final String path;
        final String esType;

        DocumentColumn(String path, String esType) {
            this.path = path;
            this.esType = esType;
        }

        boolean isJson() {
            return esType.equals("object") || esType.equals("nested");
        }

        EsColumn toEsColumn() {
            if (isJson()) {
                return EsColumn.of(path, Types.OTHER, esType.toUpperCase(Locale.ROOT));
            }
            String sqlTypeName = esType.equals("date") ? "datetime" : esType;
            return EsColumn.fromEs(path, sqlTypeName);
        }
    }

    /**
     * Per-connection cache of index columns, so paging, sorting and filtering the same index don't fetch _field_caps
     * again. Entries expire after {@link #TTL_MILLIS} and are dropped after every write (new fields may appear).
     */
    static final class ColumnCache {
        private static final long TTL_MILLIS = 60_000;
        private final Map<String, CachedColumns> entries = new ConcurrentHashMap<>();

        private List<DocumentColumn> get(String index) {
            CachedColumns cached = entries.get(index);
            if (cached == null || System.currentTimeMillis() - cached.loadedAtMillis > TTL_MILLIS) {
                return null;
            }
            return cached.columns;
        }

        private void put(String index, List<DocumentColumn> columns) {
            entries.put(index, new CachedColumns(columns, System.currentTimeMillis()));
        }

        void clear() {
            entries.clear();
        }
    }

    private static final class CachedColumns {
        final List<DocumentColumn> columns;
        final long loadedAtMillis;

        CachedColumns(List<DocumentColumn> columns, long loadedAtMillis) {
            this.columns = columns;
            this.loadedAtMillis = loadedAtMillis;
        }
    }

    /** Columns of the index (or alias / pattern), from the cache or _field_caps. */
    private List<DocumentColumn> columns(String index) throws SQLException {
        List<DocumentColumn> cached = connection.documentColumns.get(index);
        if (cached != null) {
            return cached;
        }
        List<DocumentColumn> columns = fetchColumns(index);
        connection.documentColumns.put(index, columns);
        return columns;
    }

    /** Columns of the index (or alias / pattern) from _field_caps, sorted by name. */
    private List<DocumentColumn> fetchColumns(String index) throws SQLException {
        Object response = connection.client.json("GET", "/" + encode(index) + "/_field_caps?fields=*", null);
        Map<String, Object> fields = asMap(asMap(response).get("fields"));
        Map<String, String> types = new TreeMap<>();
        fields.forEach((name, capabilities) -> {
            String type = fieldType(name, asMap(capabilities));
            if (type != null) {
                types.put(name, type);
            }
        });

        List<DocumentColumn> columns = new ArrayList<>();
        types.forEach((path, type) -> {
            if (isVisible(path, type, types)) {
                columns.add(new DocumentColumn(path, type));
            }
        });
        return columns;
    }

    /** The field's type, or null for metadata and unmapped fields. Conflicting types fall back to keyword. */
    private static String fieldType(String name, Map<String, Object> capabilities) {
        if (METADATA_FIELDS.contains(name)) {
            return null;
        }
        List<String> types = new ArrayList<>();
        for (Map.Entry<String, Object> entry : capabilities.entrySet()) {
            if (Boolean.TRUE.equals(asMap(entry.getValue()).get("metadata_field"))) {
                return null;
            }
            if (!entry.getKey().equals("unmapped")) {
                types.add(entry.getKey());
            }
        }
        if (types.isEmpty()) {
            return null;
        }
        return types.size() == 1 ? types.get(0) : "keyword";
    }

    /**
     * Decides whether a field gets its own column: leaves up to MAX_FLATTEN_DEPTH levels, objects at the depth limit
     * and nested fields (shown as JSON). Multi-fields such as name.keyword and fields inside a JSON column are hidden.
     */
    private static boolean isVisible(String path, String type, Map<String, String> types) {
        String[] parts = path.split("\\.");
        StringBuilder ancestor = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) {
                ancestor.append('.');
            }
            ancestor.append(parts[i]);
            String ancestorType = types.get(ancestor.toString());
            if (ancestorType == null) {
                continue;
            }
            boolean ancestorIsObject = ancestorType.equals("object") || ancestorType.equals("nested");
            if (!ancestorIsObject) {
                return false; // multi-field, e.g. name.keyword under a text field
            }
            if (ancestorType.equals("nested") || i + 1 >= MAX_FLATTEN_DEPTH) {
                return false; // already shown inside the ancestor's JSON column
            }
        }
        boolean isObject = type.equals("object") || type.equals("nested");
        if (!isObject) {
            return parts.length <= MAX_FLATTEN_DEPTH;
        }
        return type.equals("nested") || parts.length == MAX_FLATTEN_DEPTH;
    }

    private static String encode(String index) {
        return URLEncoder.encode(index, StandardCharsets.UTF_8).replace("%2A", "*").replace("%2C", ",");
    }

    // ---------------------------------------------------------------- paging with from/size

    private final class SearchPages implements PageSource {
        private final String index;
        private final Map<String, Object> query;
        private final List<Object> sort;
        private final List<DocumentColumn> columns;
        private final int pageSize;
        private final int limit;
        private int fetched;
        private boolean done;

        SearchPages(String index, Map<String, Object> query, List<Object> sort, List<DocumentColumn> columns,
                    int pageSize, int limit) {
            this.index = index;
            this.query = query;
            this.sort = sort;
            this.columns = columns;
            this.pageSize = pageSize;
            this.limit = limit;
        }

        @Override
        public List<List<Object>> nextPage() throws SQLException {
            if (done) {
                return null;
            }
            int size = Math.min(pageSize, limit - fetched);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            if (!sort.isEmpty()) {
                body.put("sort", sort);
            }
            body.put("from", fetched);
            body.put("size", size);
            body.put("track_total_hits", false);
            Object response = connection.client.json("POST", "/" + encode(index) + "/_search", Json.write(body));

            Object hits = asMap(asMap(response).get("hits")).get("hits");
            List<List<Object>> rows = new ArrayList<>();
            if (hits instanceof List) {
                for (Object hit : (List<?>) hits) {
                    rows.add(row(asMap(hit)));
                }
            }
            fetched += rows.size();
            done = rows.size() < size || fetched >= limit;
            return rows;
        }

        private List<Object> row(Map<String, Object> hit) {
            Map<String, Object> source = asMap(hit.get("_source"));
            List<Object> row = new ArrayList<>(columns.size() + 1);
            row.add(hit.get("_id"));
            for (DocumentColumn column : columns) {
                row.add(cellValue(valueAt(source, column.path), column.isJson()));
            }
            return row;
        }

        @Override
        public boolean hasMorePages() {
            return !done;
        }

        @Override
        public void close() {
            done = true;
        }
    }

    /** Value at a dotted path in _source; supports both nested objects and literal dotted keys. */
    private static Object valueAt(Object node, String path) {
        if (node instanceof List) {
            List<Object> values = new ArrayList<>();
            for (Object item : (List<?>) node) {
                Object value = valueAt(item, path);
                if (value != null) {
                    values.add(value);
                }
            }
            return values.isEmpty() ? null : values;
        }
        if (!(node instanceof Map)) {
            return null;
        }
        Map<?, ?> object = (Map<?, ?>) node;
        if (object.containsKey(path)) {
            return object.get(path);
        }
        for (int dot = path.indexOf('.'); dot > 0; dot = path.indexOf('.', dot + 1)) {
            String head = path.substring(0, dot);
            if (!object.containsKey(head)) {
                continue;
            }
            Object value = valueAt(object.get(head), path.substring(dot + 1));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Objects and arrays become JSON text; in non-JSON columns a single-element array becomes the element. */
    private static Object cellValue(Object value, boolean jsonColumn) {
        if (!jsonColumn && value instanceof List && ((List<?>) value).size() == 1) {
            value = ((List<?>) value).get(0);
        }
        if (value instanceof Map || value instanceof List) {
            return Json.write(value);
        }
        return value;
    }

    // ---------------------------------------------------------------- SQL shape

    /** {@code SELECT * FROM table [alias] [WHERE ...] [ORDER BY ...] [LIMIT n]}, split into its clauses. */
    private static final class SelectStar {
        String index;
        String fromText;
        String where;
        String orderBy;
        String idSortOrder;
        Integer limit;

        static SelectStar parse(String sql, String clusterName) {
            List<Token> tokens;
            try {
                tokens = SqlTokens.tokenize(sql);
            } catch (SQLException unparsable) {
                return null;
            }
            boolean selectStar = tokens.size() >= 4 && tokens.get(0).isKeyword("SELECT")
                    && tokens.get(1).kind == Kind.WORD && tokens.get(1).text.equals("*") && tokens.get(2).isKeyword("FROM")
                    && tokens.get(3).isIdentifier();
            if (!selectStar || hasTopLevelKeyword(tokens, "GROUP", "HAVING", "PIVOT", "UNION")) {
                return null;
            }

            SelectStar select = new SelectStar();
            List<String> tableParts = new ArrayList<>();
            int position = SqlTokens.readIdentifier(tokens, 3, tableParts);
            if (tableParts.size() > 1 && tableParts.get(0).equals(clusterName)) {
                tableParts.remove(0);
            }
            select.index = String.join(".", tableParts);

            int fromEnd = position;
            if (position < tokens.size() && tokens.get(position).isKeyword("AS")) {
                position++;
            }
            boolean hasAlias = position < tokens.size() && tokens.get(position).isIdentifier() && !isClauseKeyword(tokens.get(position));
            if (hasAlias) {
                position++;
                fromEnd = position;
            }
            select.fromText = sql.substring(tokens.get(3).start, tokens.get(fromEnd - 1).end);
            return select.parseClauses(sql, tokens, position) ? select : null;
        }

        /** Reads WHERE / ORDER BY / LIMIT in that order; anything else means the query isn't a plain SELECT *. */
        private boolean parseClauses(String sql, List<Token> tokens, int position) {
            int whereStart = -1;
            int orderStart = -1;
            int limitStart = -1;
            int depth = 0;
            for (int i = position; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                depth += token.isSymbol('(') ? 1 : token.isSymbol(')') ? -1 : 0;
                if (depth != 0) {
                    continue;
                }
                if (i == position && token.isKeyword("WHERE")) {
                    whereStart = i;
                } else if (token.isKeyword("ORDER") && i + 1 < tokens.size() && tokens.get(i + 1).isKeyword("BY")) {
                    orderStart = i;
                } else if (token.isKeyword("LIMIT")) {
                    limitStart = i;
                }
            }
            int clauseStart = whereStart >= 0 ? whereStart : orderStart >= 0 ? orderStart : limitStart >= 0 ? limitStart : tokens.size();
            if (clauseStart != position) {
                return false;
            }
            int end = tokens.size();
            if (limitStart >= 0) {
                boolean validLimit = limitStart + 2 == tokens.size() && tokens.get(limitStart + 1).kind == Kind.NUMBER;
                if (!validLimit) {
                    return false;
                }
                limit = Integer.parseInt(tokens.get(limitStart + 1).text);
                end = limitStart;
            }
            if (orderStart >= 0) {
                if (orderStart >= end) {
                    return false;
                }
                parseOrderBy(sql, tokens.subList(orderStart + 2, end));
                end = orderStart;
            }
            if (whereStart >= 0) {
                if (whereStart + 1 >= end) {
                    return false;
                }
                where = sql.substring(tokens.get(whereStart + 1).start, tokens.get(end - 1).end);
            }
            return true;
        }

        /** Keeps the ORDER BY items for ES SQL, except "_id [ASC|DESC]" which becomes a direct _id sort. */
        private void parseOrderBy(String sql, List<Token> tokens) {
            List<String> items = new ArrayList<>();
            int itemStart = 0;
            for (int i = 0; i <= tokens.size(); i++) {
                boolean itemEnd = i == tokens.size() || tokens.get(i).isSymbol(',');
                if (!itemEnd || i == itemStart) {
                    continue;
                }
                List<Token> item = tokens.subList(itemStart, i);
                String direction = orderOfIdItem(item);
                if (direction == null) {
                    items.add(sql.substring(item.get(0).start, item.get(item.size() - 1).end));
                } else {
                    idSortOrder = direction;
                }
                itemStart = i + 1;
            }
            orderBy = items.isEmpty() ? null : String.join(", ", items);
        }

        /** "asc"/"desc" when the item is "[qualifier.]_id [ASC|DESC]", otherwise null. */
        private static String orderOfIdItem(List<Token> item) {
            if (!item.get(0).isIdentifier()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            int position = SqlTokens.readIdentifier(item, 0, parts);
            if (!parts.get(parts.size() - 1).equals("_id")) {
                return null;
            }
            if (position == item.size()) {
                return "asc";
            }
            boolean direction = position + 1 == item.size() && (item.get(position).isKeyword("ASC") || item.get(position).isKeyword("DESC"));
            return direction ? item.get(position).text.toLowerCase(Locale.ROOT) : null;
        }

        private static boolean isClauseKeyword(Token token) {
            return token.isKeyword("WHERE") || token.isKeyword("ORDER") || token.isKeyword("LIMIT");
        }

        private static boolean hasTopLevelKeyword(List<Token> tokens, String... keywords) {
            int depth = 0;
            for (Token token : tokens) {
                depth += token.isSymbol('(') ? 1 : token.isSymbol(')') ? -1 : 0;
                if (depth != 0) {
                    continue;
                }
                for (String keyword : keywords) {
                    if (token.isKeyword(keyword)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
