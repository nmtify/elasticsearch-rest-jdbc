package io.esrest.jdbc;

import io.esrest.jdbc.SqlTokens.Kind;
import io.esrest.jdbc.SqlTokens.Token;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes INSERT / UPDATE / DELETE by translating them into Elasticsearch document APIs:
 * <ul>
 *   <li>{@code UPDATE idx SET a = ? WHERE ...} becomes {@code _update_by_query} with a painless script</li>
 *   <li>{@code DELETE FROM idx WHERE ...} becomes {@code _delete_by_query}</li>
 *   <li>{@code INSERT INTO idx (a, b) VALUES (?, ?)} becomes {@code _doc} (or {@code _create} when {@code _id} is given)</li>
 * </ul>
 * The WHERE clause is converted to query DSL by Elasticsearch itself ({@code POST /_sql/translate}), so it has exactly
 * the semantics of ES SQL. UPDATE/DELETE refuse to touch more than {@code maxAffectedDocs} documents.
 */
final class EsDml {
    private static final Pattern DML_START = Pattern.compile(
            "^(?:/\\*.*?\\*/|--[^\\n]*\\n|\\s)*(INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Sets each field; dotted names walk (and create) nested objects unless the source has the literal dotted key.
     * When a step of the path is an array of objects, the field is set on every element ({@code affected[*].product}).
     */
    private static final String SET_FIELDS_SCRIPT =
            "void setPath(def object, List path, int index, def value, String name) {"
            + "  if (object instanceof List) {"
            + "    for (def item : object) { setPath(item, path, index, value, name); }"
            + "    return;"
            + "  }"
            + "  if (!(object instanceof Map)) {"
            + "    throw new IllegalArgumentException('Field [' + path.get(index - 1) + '] of [' + name + '] is not an object');"
            + "  }"
            + "  String key = path.get(index);"
            + "  if (index == path.size() - 1) { object[key] = value; return; }"
            + "  def child = object[key];"
            + "  if (child == null) { child = new HashMap(); object[key] = child; }"
            + "  setPath(child, path, index + 1, value, name);"
            + "}"
            + "for (def field : params.fields) {"
            + "  if (ctx._source.containsKey(field.name)) { ctx._source[field.name] = field.value; continue; }"
            + "  setPath(ctx._source, field.path, 0, field.value, field.name);"
            + "}";

    private static final Map<String, Object> MATCH_ALL = Map.of("match_all", Map.of());

    private final EsConnection connection;

    EsDml(EsConnection connection) {
        this.connection = connection;
    }

    static boolean isDml(String sql) {
        return DML_START.matcher(sql).lookingAt();
    }

    int execute(String sql, List<Map<String, Object>> params) throws SQLException {
        if (connection.isReadOnly()) {
            throw new SQLException("Connection is read-only (readOnly=true or DataGrip 'Read-only' option)", "25006");
        }
        SqlParser parser = new SqlParser(sql, params == null ? List.of() : params);
        String keyword = parser.peekKeyword();
        switch (keyword) {
            case "UPDATE": return update(parser);
            case "DELETE": return delete(parser);
            case "INSERT": return insert(parser);
            default: throw new SQLException("Unsupported statement: " + keyword, "42000");
        }
    }

    // ---------------------------------------------------------------- statements

    private int update(SqlParser parser) throws SQLException {
        parser.expectKeyword("UPDATE");
        Target target = parser.target("SET");
        parser.expectKeyword("SET");
        List<Map<String, Object>> fields = new ArrayList<>();
        do {
            String column = target.column(parser.identifier());
            if (column.equals("_id")) {
                throw new SQLException("The _id of a document can't be changed; insert a new document instead", "0A000");
            }
            parser.expectSymbol('=');
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", column);
            field.put("path", Arrays.asList(column.split("\\.")));
            field.put("value", jsonOrValue(parser.value()));
            fields.add(field);
        } while (parser.trySymbol(','));
        Map<String, Object> query = whereQuery(parser, target);

        if (countGuarded(target, query, "UPDATE") == 0) {
            return 0;
        }

        Map<String, Object> script = new LinkedHashMap<>();
        script.put("lang", "painless");
        script.put("source", SET_FIELDS_SCRIPT);
        script.put("params", Map.of("fields", fields));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("max_docs", connection.config.maxAffectedDocs);
        body.put("query", query);
        body.put("script", script);
        return byQuery(target.index, "_update_by_query", body, "updated");
    }

    private int delete(SqlParser parser) throws SQLException {
        parser.expectKeyword("DELETE");
        parser.expectKeyword("FROM");
        Target target = parser.target("WHERE");
        Map<String, Object> query = whereQuery(parser, target);

        if (countGuarded(target, query, "DELETE") == 0) {
            return 0;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("max_docs", connection.config.maxAffectedDocs);
        body.put("query", query);
        return byQuery(target.index, "_delete_by_query", body, "deleted");
    }

    private int insert(SqlParser parser) throws SQLException {
        parser.expectKeyword("INSERT");
        parser.expectKeyword("INTO");
        Target target = parser.target("(");
        List<String> columns = new ArrayList<>();
        parser.expectSymbol('(');
        do {
            columns.add(target.column(parser.identifier()));
        } while (parser.trySymbol(','));
        parser.expectSymbol(')');
        parser.expectKeyword("VALUES");

        List<List<Object>> rows = new ArrayList<>();
        do {
            rows.add(valueList(parser, columns.size()));
        } while (parser.trySymbol(','));
        parser.expectEnd();

        for (List<Object> row : rows) {
            indexDocument(target.index, columns, row);
        }
        return rows.size();
    }

    private static List<Object> valueList(SqlParser parser, int expectedSize) throws SQLException {
        parser.expectSymbol('(');
        List<Object> values = new ArrayList<>();
        do {
            values.add(jsonOrValue(parser.value()));
        } while (parser.trySymbol(','));
        parser.expectSymbol(')');
        if (values.size() != expectedSize) {
            throw new SQLException("INSERT has " + expectedSize + " columns but " + values.size() + " values", "21S01");
        }
        return values;
    }

    private void indexDocument(String index, List<String> columns, List<Object> values) throws SQLException {
        Map<String, Object> document = new LinkedHashMap<>();
        String id = null;
        for (int i = 0; i < columns.size(); i++) {
            Object value = values.get(i);
            if (columns.get(i).equals("_id")) {
                id = value == null ? null : String.valueOf(value);
                continue;
            }
            if (value != null) {
                putPath(document, columns.get(i), value);
            }
        }
        String body = Json.write(document);
        if (id == null) {
            connection.client.json("POST", "/" + encode(index) + "/_doc?refresh=true", body);
            return;
        }
        connection.client.json("PUT", "/" + encode(index) + "/_create/" + encode(id) + "?refresh=true", body);
    }

    // ---------------------------------------------------------------- Elasticsearch calls

    private Map<String, Object> whereQuery(SqlParser parser, Target target) throws SQLException {
        if (!parser.tryKeyword("WHERE")) {
            parser.expectEnd();
            return MATCH_ALL;
        }
        IdCondition condition = IdCondition.extract(parser.remainingText(), parser.remainingParams());
        Map<String, Object> query = condition.remainingCondition == null
                ? null
                : translateCondition(target, condition.remainingCondition, condition.remainingParams);
        if (condition.ids == null) {
            return query;
        }
        Map<String, Object> idsQuery = Map.of("ids", Map.of("values", condition.ids));
        if (query == null) {
            return idsQuery;
        }
        return Map.of("bool", Map.of("filter", List.of(idsQuery, query)));
    }

    /** Converts a WHERE condition into query DSL with POST /_sql/translate. */
    private Map<String, Object> translateCondition(Target target, String condition, List<Map<String, Object>> whereParams)
            throws SQLException {
        String from = quote(target.index) + (target.alias == null ? "" : " " + quote(target.alias));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "SELECT * FROM " + from + " WHERE " + condition);
        body.put("time_zone", connection.config.timeZone);
        if (!whereParams.isEmpty()) {
            body.put("params", whereParams);
        }

        Object response = connection.client.json("POST", "/_sql/translate", Json.write(body));
        Object query = response instanceof Map ? ((Map<?, ?>) response).get("query") : null;
        if (!(query instanceof Map)) {
            return MATCH_ALL;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dsl = (Map<String, Object>) query;
        return dsl;
    }

    /** Counts matching documents and refuses when more than maxAffectedDocs would be touched. */
    private long countGuarded(Target target, Map<String, Object> query, String operation) throws SQLException {
        Object response = connection.client.json("POST", "/" + encode(target.index) + "/_count", Json.write(Map.of("query", query)));
        Object countValue = response instanceof Map ? ((Map<?, ?>) response).get("count") : null;
        long count = countValue instanceof Number ? ((Number) countValue).longValue() : -1;
        int maxAffectedDocs = connection.config.maxAffectedDocs;
        if (count <= maxAffectedDocs) {
            return count;
        }
        throw new SQLException(operation + " would affect " + count + " documents in [" + target.index
                + "], more than maxAffectedDocs=" + maxAffectedDocs
                + ". Nothing was changed. Narrow the WHERE clause or raise the maxAffectedDocs property.", "21000");
    }

    private int byQuery(String index, String api, Map<String, Object> body, String countField) throws SQLException {
        String path = "/" + encode(index) + "/" + api + "?conflicts=proceed&refresh=true";
        Object response = connection.client.json("POST", path, Json.write(body));
        if (!(response instanceof Map)) {
            throw new SQLException("Unexpected response from " + api + ": " + response, "HY000");
        }

        Map<?, ?> result = (Map<?, ?>) response;
        Object failures = result.get("failures");
        if (failures instanceof List && !((List<?>) failures).isEmpty()) {
            throw new SQLException(api + " failed: " + Json.write(((List<?>) failures).get(0)), "HY000");
        }
        return ((Number) result.get(countField)).intValue();
    }

    // ---------------------------------------------------------------- helpers

    /** Text holding a JSON object or array (as shown for object/nested columns) is written back as JSON. */
    private static Object jsonOrValue(Object value) {
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        boolean looksLikeJson = (text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"));
        if (!looksLikeJson) {
            return value;
        }
        try {
            return Json.parse(text);
        } catch (IllegalArgumentException notJson) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> document, String path, Object value) {
        String[] names = path.split("\\.");
        Map<String, Object> object = document;
        for (int i = 0; i < names.length - 1; i++) {
            Object child = object.get(names[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                object.put(names[i], child);
            }
            object = (Map<String, Object>) child;
        }
        object.put(names[names.length - 1], value);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String encode(String pathSegment) {
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2A", "*").replace("%2C", ",");
    }

    /** Target index plus optional alias, used to strip qualifiers such as t.col. */
    private final class Target {
        final String index;
        final String alias;

        Target(List<String> tableParts, String alias) {
            List<String> parts = new ArrayList<>(tableParts);
            if (parts.size() > 1 && parts.get(0).equals(connection.clusterName)) {
                parts.remove(0);
            }
            this.index = String.join(".", parts);
            this.alias = alias;
        }

        String column(List<String> columnParts) {
            List<String> parts = new ArrayList<>(columnParts);
            boolean qualified = parts.size() > 1 && (parts.get(0).equals(alias) || parts.get(0).equals(index));
            if (qualified) {
                parts.remove(0);
            }
            return String.join(".", parts);
        }
    }

    // ---------------------------------------------------------------- parser

    private final class SqlParser {
        private final String sql;
        private final List<Map<String, Object>> params;
        private final List<Token> tokens;
        private int position;
        private int nextParam;

        SqlParser(String sql, List<Map<String, Object>> params) throws SQLException {
            this.sql = sql;
            this.params = params;
            this.tokens = SqlTokens.tokenize(sql);
        }

        private SQLException syntaxError(String message) {
            return new SQLException(message + " in: " + sql, "42000");
        }

        private Token peek() {
            return position < tokens.size() ? tokens.get(position) : null;
        }

        String peekKeyword() {
            Token token = peek();
            if (token == null || token.kind != Kind.WORD) {
                return "";
            }
            return token.text.toUpperCase(Locale.ROOT);
        }

        boolean tryKeyword(String keyword) {
            Token token = peek();
            if (token == null || !token.isKeyword(keyword)) {
                return false;
            }
            position++;
            return true;
        }

        void expectKeyword(String keyword) throws SQLException {
            if (!tryKeyword(keyword)) {
                throw syntaxError("Expected " + keyword);
            }
        }

        boolean trySymbol(char symbol) {
            Token token = peek();
            if (token == null || !token.isSymbol(symbol)) {
                return false;
            }
            position++;
            return true;
        }

        void expectSymbol(char symbol) throws SQLException {
            if (!trySymbol(symbol)) {
                throw syntaxError("Expected '" + symbol + "'");
            }
        }

        void expectEnd() throws SQLException {
            while (trySymbol(';')) {
                // trailing semicolons are allowed
            }
            if (peek() != null) {
                throw syntaxError("Unexpected '" + peek().text + "'");
            }
        }

        /** Reads a possibly qualified identifier such as {@code t."meta".score} into its parts. */
        List<String> identifier() throws SQLException {
            Token first = peek();
            if (first == null || !first.isIdentifier()) {
                throw syntaxError("Expected identifier");
            }
            List<String> parts = new ArrayList<>();
            position = SqlTokens.readIdentifier(tokens, position, parts);
            return parts;
        }

        /** Reads the table name and an optional alias (with or without AS), stopping at the given keyword or symbol. */
        Target target(String stopWord) throws SQLException {
            List<String> table = identifier();
            Token next = peek();
            boolean hasAlias = next != null && next.isIdentifier() && !next.isKeyword(stopWord);
            if (!hasAlias) {
                return new Target(table, null);
            }
            tryKeyword("AS");
            return new Target(table, String.join(".", identifier()));
        }

        Object value() throws SQLException {
            Token token = peek();
            if (token == null) {
                throw syntaxError("Expected value");
            }
            position++;
            switch (token.kind) {
                case PARAMETER: return nextParamValue();
                case STRING: return token.text;
                case NUMBER: return parseNumber(token.text);
                case WORD: return keywordValue(token);
                case SYMBOL: return negativeNumber(token);
                default: throw notALiteral(token);
            }
        }

        private Object nextParamValue() throws SQLException {
            if (nextParam >= params.size()) {
                throw syntaxError("Missing value for parameter " + (nextParam + 1));
            }
            return params.get(nextParam++).get("value");
        }

        /** NULL and DEFAULT both mean "no value" (for _id: let Elasticsearch generate one). */
        private Object keywordValue(Token token) throws SQLException {
            if (token.isKeyword("NULL") || token.isKeyword("DEFAULT")) {
                return null;
            }
            if (token.isKeyword("TRUE")) {
                return Boolean.TRUE;
            }
            if (token.isKeyword("FALSE")) {
                return Boolean.FALSE;
            }
            throw notALiteral(token);
        }

        private Object negativeNumber(Token minus) throws SQLException {
            Token number = peek();
            if (!minus.isSymbol('-') || number == null || number.kind != Kind.NUMBER) {
                throw notALiteral(minus);
            }
            position++;
            return parseNumber("-" + number.text);
        }

        private SQLException notALiteral(Token token) {
            return syntaxError("Only literal values and ? parameters are supported, got '" + token.text + "'");
        }

        private Object parseNumber(String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException notInteger) {
                return new BigDecimal(text);
            }
        }

        /** Raw SQL text from the current token to the end (the WHERE condition). */
        String remainingText() throws SQLException {
            if (peek() == null) {
                throw syntaxError("Empty WHERE clause");
            }
            String text = sql.substring(peek().start).trim();
            while (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            return text;
        }

        /** Parameters not consumed yet, i.e. those referenced by the WHERE clause. */
        List<Map<String, Object>> remainingParams() throws SQLException {
            long parameterCount = tokens.subList(position, tokens.size()).stream()
                    .filter(token -> token.kind == Kind.PARAMETER)
                    .count();
            int end = nextParam + (int) parameterCount;
            if (end > params.size()) {
                throw syntaxError("Not all parameters are set");
            }
            return params.subList(nextParam, end);
        }
    }
}
