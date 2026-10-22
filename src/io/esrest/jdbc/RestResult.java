package io.esrest.jdbc;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw REST response into a table, trying in order: search hits, aggregation buckets, an array of objects
 * (e.g. _cat with format=json), a single object (e.g. _count, _cluster/health), text lines, a single JSON cell.
 */
final class RestResult {
    private static final Set<String> BUCKET_KEYS = Set.of("key", "key_as_string", "doc_count");

    private RestResult() { }

    static EsResultSet toResultSet(EsStatement statement, Object response, int maxRows) {
        List<Map<?, ?>> records = searchHits(response);
        if (records == null) {
            records = aggregations(response);
        }
        if (records == null) {
            records = objectArray(response);
        }
        if (records == null && response instanceof Map) {
            records = List.of((Map<?, ?>) response);
        }
        if (records != null) {
            return table(statement, records, maxRows);
        }

        List<List<Object>> rows = new ArrayList<>();
        if (response instanceof String) {
            for (String line : ((String) response).split("\r?\n")) {
                rows.add(Collections.singletonList(line));
            }
        } else {
            rows.add(Collections.singletonList(response == null ? "" : Json.write(response)));
        }
        List<EsColumn> columns = List.of(EsColumn.of("response", Types.VARCHAR));
        return new EsResultSet(statement, columns, rows, null, maxRows);
    }

    /** _search response: one record per hit with _index, _id, _score and the _source fields. */
    private static List<Map<?, ?>> searchHits(Object response) {
        if (!(response instanceof Map)) {
            return null;
        }
        Object hits = ((Map<?, ?>) response).get("hits");
        if (!(hits instanceof Map)) {
            return null;
        }
        Object hitList = ((Map<?, ?>) hits).get("hits");
        if (!(hitList instanceof List) || ((List<?>) hitList).isEmpty()) {
            return null;
        }

        List<Map<?, ?>> records = new ArrayList<>();
        for (Object item : (List<?>) hitList) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> hit = (Map<?, ?>) item;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("_index", hit.get("_index"));
            record.put("_id", hit.get("_id"));
            record.put("_score", hit.get("_score"));
            if (hit.get("_source") instanceof Map) {
                ((Map<?, ?>) hit.get("_source")).forEach((name, value) -> record.put(String.valueOf(name), value));
            }
            if (hit.get("fields") instanceof Map) {
                record.put("fields", hit.get("fields"));
            }
            records.add(record);
        }
        return records;
    }

    /**
     * Aggregations of a _search response: one row per bucket (aggregation, key, doc_count and the sub-aggregation
     * values), one row per metric aggregation (aggregation, value or its statistics).
     */
    private static List<Map<?, ?>> aggregations(Object response) {
        if (!(response instanceof Map)) {
            return null;
        }
        Object aggregations = ((Map<?, ?>) response).get("aggregations");
        if (!(aggregations instanceof Map) || ((Map<?, ?>) aggregations).isEmpty()) {
            return null;
        }
        List<Map<?, ?>> records = new ArrayList<>();
        ((Map<?, ?>) aggregations).forEach((name, result) -> {
            if (result instanceof Map) {
                addAggregationRows(String.valueOf(name), (Map<?, ?>) result, records);
            }
        });
        return records;
    }

    private static void addAggregationRows(String name, Map<?, ?> result, List<Map<?, ?>> records) {
        Object buckets = result.get("buckets");
        if (buckets instanceof List) {
            for (Object bucket : (List<?>) buckets) {
                records.add(bucketRow(name, null, (Map<?, ?>) bucket));
            }
            return;
        }
        if (buckets instanceof Map) {
            ((Map<?, ?>) buckets).forEach((key, bucket) -> records.add(bucketRow(name, key, (Map<?, ?>) bucket)));
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("aggregation", name);
        result.forEach((field, value) -> row.put(String.valueOf(field), value));
        records.add(row);
    }

    private static Map<String, Object> bucketRow(String name, Object keyedKey, Map<?, ?> bucket) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("aggregation", name);
        Object key = keyedKey != null ? keyedKey : bucket.containsKey("key_as_string") ? bucket.get("key_as_string") : bucket.get("key");
        row.put("key", key);
        row.put("doc_count", bucket.get("doc_count"));
        bucket.forEach((field, value) -> {
            if (BUCKET_KEYS.contains(String.valueOf(field))) {
                return;
            }
            boolean singleValueMetric = value instanceof Map && ((Map<?, ?>) value).containsKey("value");
            row.put(String.valueOf(field), singleValueMetric ? ((Map<?, ?>) value).get("value") : value);
        });
        return row;
    }

    /** JSON array of objects, e.g. _cat/...?format=json. */
    private static List<Map<?, ?>> objectArray(Object response) {
        if (!(response instanceof List)) {
            return null;
        }
        List<?> items = (List<?>) response;
        if (items.isEmpty() || !(items.get(0) instanceof Map)) {
            return null;
        }
        List<Map<?, ?>> records = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map) {
                records.add((Map<?, ?>) item);
            }
        }
        return records;
    }

    private static EsResultSet table(EsStatement statement, List<Map<?, ?>> records, int maxRows) {
        Map<String, Integer> columnIndexes = new LinkedHashMap<>();
        for (Map<?, ?> record : records) {
            for (Object name : record.keySet()) {
                columnIndexes.putIfAbsent(String.valueOf(name), columnIndexes.size());
            }
        }
        int[] types = new int[columnIndexes.size()];
        Arrays.fill(types, Types.NULL);

        List<List<Object>> rows = new ArrayList<>();
        for (Map<?, ?> record : records) {
            Object[] row = new Object[types.length];
            for (Map.Entry<?, ?> field : record.entrySet()) {
                int index = columnIndexes.get(String.valueOf(field.getKey()));
                Object value = field.getValue();
                if (value instanceof Map || value instanceof List) {
                    value = Json.write(value);
                }
                row[index] = value;
                types[index] = mergeType(types[index], value);
            }
            rows.add(new ArrayList<>(Arrays.asList(row)));
        }

        List<EsColumn> columns = new ArrayList<>();
        int index = 0;
        for (String name : columnIndexes.keySet()) {
            int type = types[index++];
            columns.add(EsColumn.of(name, type == Types.NULL ? Types.VARCHAR : type));
        }
        return new EsResultSet(statement, columns, rows, null, maxRows);
    }

    /** Widens the column type seen so far with the type of a new value. */
    private static int mergeType(int current, Object value) {
        if (value == null) {
            return current;
        }
        int type = value instanceof Boolean ? Types.BOOLEAN
                : value instanceof Long ? Types.BIGINT
                : value instanceof Number ? Types.DOUBLE
                : Types.VARCHAR;
        if (current == Types.NULL || current == type) {
            return type;
        }
        boolean bothNumeric = (current == Types.BIGINT || current == Types.DOUBLE) && (type == Types.BIGINT || type == Types.DOUBLE);
        return bothNumeric ? Types.DOUBLE : Types.VARCHAR;
    }
}
