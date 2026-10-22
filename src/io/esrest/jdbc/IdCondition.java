package io.esrest.jdbc;

import io.esrest.jdbc.SqlTokens.Kind;
import io.esrest.jdbc.SqlTokens.Token;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits {@code _id} filters out of a WHERE condition, because ES SQL doesn't know the {@code _id} column.
 * {@code _id = 'a' AND status = ?} becomes ids [a] plus the remaining condition {@code status = ?} for ES SQL.
 * Supported forms: {@code _id = value} and {@code _id IN (value, ...)}, combined with other conditions by AND.
 */
final class IdCondition {
    /** Document ids to match, or null when the condition has no _id filter. */
    final List<String> ids;
    /** Condition left for ES SQL, or null when nothing is left. */
    final String remainingCondition;
    final List<Map<String, Object>> remainingParams;

    private IdCondition(List<String> ids, String remainingCondition, List<Map<String, Object>> remainingParams) {
        this.ids = ids;
        this.remainingCondition = remainingCondition;
        this.remainingParams = remainingParams;
    }

    static IdCondition extract(String condition, List<Map<String, Object>> params) throws SQLException {
        List<Token> tokens = SqlTokens.tokenize(condition);
        List<int[]> conjuncts = splitTopLevelAnd(tokens);
        boolean hasTopLevelOr = hasTopLevelKeyword(tokens, "OR");

        Set<String> ids = null;
        List<String> remaining = new ArrayList<>();
        List<Map<String, Object>> remainingParams = new ArrayList<>();
        int paramIndex = 0;
        for (int[] range : conjuncts) {
            List<Token> conjunct = tokens.subList(range[0], range[1]);
            int paramCount = countParams(conjunct);
            if (!mentionsId(conjunct)) {
                remaining.add(condition.substring(conjunct.get(0).start, conjunct.get(conjunct.size() - 1).end));
                remainingParams.addAll(params.subList(paramIndex, Math.min(paramIndex + paramCount, params.size())));
                paramIndex += paramCount;
                continue;
            }
            if (hasTopLevelOr) {
                throw unsupported(condition);
            }
            Set<String> conjunctIds = idValues(conjunct, params, paramIndex, condition);
            paramIndex += paramCount;
            if (ids == null) {
                ids = conjunctIds;
            } else {
                ids.retainAll(conjunctIds);
            }
        }
        if (ids == null) {
            return new IdCondition(null, condition, params);
        }
        return new IdCondition(new ArrayList<>(ids), joinConjuncts(remaining), remainingParams);
    }

    private static String joinConjuncts(List<String> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        if (conjuncts.size() == 1) {
            return conjuncts.get(0);
        }
        return "(" + String.join(") AND (", conjuncts) + ")";
    }

    /** Ranges [start, end) of the tokens between top-level AND keywords. */
    private static List<int[]> splitTopLevelAnd(List<Token> tokens) {
        List<int[]> ranges = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inBetween = false; // BETWEEN x AND y must not be split
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            depth += token.isSymbol('(') ? 1 : token.isSymbol(')') ? -1 : 0;
            if (depth != 0) {
                continue;
            }
            if (token.isKeyword("BETWEEN")) {
                inBetween = true;
                continue;
            }
            if (!token.isKeyword("AND")) {
                continue;
            }
            if (inBetween) {
                inBetween = false;
                continue;
            }
            ranges.add(new int[] {start, i});
            start = i + 1;
        }
        ranges.add(new int[] {start, tokens.size()});
        ranges.removeIf(range -> range[0] >= range[1]);
        return ranges;
    }

    private static boolean hasTopLevelKeyword(List<Token> tokens, String keyword) {
        int depth = 0;
        for (Token token : tokens) {
            depth += token.isSymbol('(') ? 1 : token.isSymbol(')') ? -1 : 0;
            if (depth == 0 && token.isKeyword(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsId(List<Token> tokens) {
        return tokens.stream().anyMatch(token -> token.isIdentifier() && token.text.equals("_id"));
    }

    private static int countParams(List<Token> tokens) {
        return (int) tokens.stream().filter(token -> token.kind == Kind.PARAMETER).count();
    }

    /** Values of "[qualifier.]_id = value" or "[qualifier.]_id IN (value, ...)". */
    private static Set<String> idValues(List<Token> tokens, List<Map<String, Object>> params, int paramIndex,
                                        String condition) throws SQLException {
        if (!tokens.get(0).isIdentifier()) {
            throw unsupported(condition);
        }
        List<String> column = new ArrayList<>();
        int position = SqlTokens.readIdentifier(tokens, 0, column);
        if (!column.get(column.size() - 1).equals("_id") || position >= tokens.size()) {
            throw unsupported(condition);
        }

        Set<String> ids = new LinkedHashSet<>();
        Token operator = tokens.get(position);
        if (operator.isSymbol('=') && position + 2 == tokens.size()) {
            ids.add(literal(tokens.get(position + 1), params, paramIndex, condition));
            return ids;
        }
        boolean inList = operator.isKeyword("IN") && position + 1 < tokens.size() && tokens.get(position + 1).isSymbol('(')
                && tokens.get(tokens.size() - 1).isSymbol(')');
        if (!inList) {
            throw unsupported(condition);
        }
        int nextParam = paramIndex;
        for (int i = position + 2; i < tokens.size() - 1; i++) {
            Token token = tokens.get(i);
            if (token.isSymbol(',')) {
                continue;
            }
            ids.add(literal(token, params, nextParam, condition));
            if (token.kind == Kind.PARAMETER) {
                nextParam++;
            }
        }
        return ids;
    }

    private static String literal(Token token, List<Map<String, Object>> params, int paramIndex, String condition)
            throws SQLException {
        if (token.kind == Kind.STRING || token.kind == Kind.NUMBER) {
            return token.text;
        }
        if (token.kind != Kind.PARAMETER) {
            throw unsupported(condition);
        }
        if (paramIndex >= params.size()) {
            throw new SQLException("Missing value for parameter " + (paramIndex + 1), "07001");
        }
        return String.valueOf(params.get(paramIndex).get("value"));
    }

    private static SQLException unsupported(String condition) {
        return new SQLException("Only '_id = value' and '_id IN (...)' combined with AND are supported for _id, got: "
                + condition, "0A000");
    }
}
