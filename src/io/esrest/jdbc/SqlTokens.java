package io.esrest.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Small SQL tokenizer shared by the DML parser, the _id filter extraction and the document search. */
final class SqlTokens {

    enum Kind { WORD, QUOTED_IDENTIFIER, STRING, NUMBER, PARAMETER, SYMBOL }

    static final class Token {
        final Kind kind;
        final String text;
        final int start;
        final int end;

        Token(Kind kind, String text, int start, int end) {
            this.kind = kind;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        boolean isSymbol(char symbol) {
            return kind == Kind.SYMBOL && text.charAt(0) == symbol;
        }

        boolean isKeyword(String keyword) {
            return kind == Kind.WORD && text.equalsIgnoreCase(keyword);
        }

        boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED_IDENTIFIER;
        }
    }

    private final String sql;
    private final List<Token> tokens = new ArrayList<>();

    private SqlTokens(String sql) {
        this.sql = sql;
    }

    static List<Token> tokenize(String sql) throws SQLException {
        SqlTokens tokenizer = new SqlTokens(sql);
        int index = 0;
        while (index < sql.length()) {
            index = tokenizer.skipWhitespaceAndComments(index);
            if (index >= sql.length()) {
                break;
            }
            index = tokenizer.readToken(index);
        }
        return tokenizer.tokens;
    }

    /**
     * Reads a possibly qualified identifier such as {@code t."meta".score} starting at {@code start}.
     * Returns the index of the first token after it; the parts are added to {@code parts}.
     */
    static int readIdentifier(List<Token> tokens, int start, List<String> parts) {
        parts.add(tokens.get(start).text);
        int position = start + 1;
        while (isAdjacentQualifier(tokens, position)) {
            parts.add(tokens.get(position + 1).text);
            position += 2;
        }
        return position;
    }

    /** True when tokens[position] is a '.' glued to the previous token and followed by a name. */
    private static boolean isAdjacentQualifier(List<Token> tokens, int position) {
        if (position + 1 >= tokens.size()) {
            return false;
        }
        Token dot = tokens.get(position);
        Token name = tokens.get(position + 1);
        boolean nameKind = name.isIdentifier() || name.kind == Kind.NUMBER;
        return dot.isSymbol('.') && nameKind && dot.start == tokens.get(position - 1).end;
    }

    // ---------------------------------------------------------------- scanning

    private int skipWhitespaceAndComments(int index) {
        while (index < sql.length()) {
            if (Character.isWhitespace(sql.charAt(index))) {
                index++;
            } else if (sql.startsWith("--", index)) {
                int lineEnd = sql.indexOf('\n', index);
                index = lineEnd < 0 ? sql.length() : lineEnd;
            } else if (sql.startsWith("/*", index)) {
                int commentEnd = sql.indexOf("*/", index + 2);
                index = commentEnd < 0 ? sql.length() : commentEnd + 2;
            } else {
                return index;
            }
        }
        return index;
    }

    private int readToken(int start) throws SQLException {
        char current = sql.charAt(start);
        if (current == '\'') {
            return readQuoted(start, '\'', Kind.STRING);
        }
        if (current == '"') {
            return readQuoted(start, '"', Kind.QUOTED_IDENTIFIER);
        }
        if (current == '?') {
            return add(Kind.PARAMETER, start, start + 1);
        }
        if (Character.isDigit(current)) {
            return add(Kind.NUMBER, start, endOfNumber(start));
        }
        if (isWordStart(current)) {
            return add(Kind.WORD, start, endOfWord(start));
        }
        return add(Kind.SYMBOL, start, start + 1);
    }

    private int add(Kind kind, int start, int end) {
        tokens.add(new Token(kind, sql.substring(start, end), start, end));
        return end;
    }

    /** Reads 'string' or "identifier", where a doubled quote character is an escaped quote. */
    private int readQuoted(int start, char quote, Kind kind) throws SQLException {
        StringBuilder text = new StringBuilder();
        int index = start + 1;
        while (true) {
            if (index >= sql.length()) {
                String what = quote == '\'' ? "string" : "identifier";
                throw new SQLException("Unterminated " + what + " in: " + sql, "42000");
            }
            char current = sql.charAt(index);
            if (current != quote) {
                text.append(current);
                index++;
                continue;
            }
            boolean escapedQuote = index + 1 < sql.length() && sql.charAt(index + 1) == quote;
            if (!escapedQuote) {
                break;
            }
            text.append(quote);
            index += 2;
        }
        tokens.add(new Token(kind, text.toString(), start, index + 1));
        return index + 1;
    }

    private int endOfNumber(int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isDigit(current) || current == '.') {
                index++;
            } else if ((current == 'e' || current == 'E') && index + 1 < sql.length()) {
                index++;
                if (sql.charAt(index) == '+' || sql.charAt(index) == '-') {
                    index++;
                }
            } else {
                break;
            }
        }
        return index;
    }

    private static boolean isWordStart(char current) {
        return Character.isLetter(current) || current == '_' || current == '@' || current == '*';
    }

    private int endOfWord(int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            boolean wordCharacter = Character.isLetterOrDigit(current) || "_@*-:".indexOf(current) >= 0;
            if (!wordCharacter) {
                break;
            }
            index++;
        }
        return index;
    }
}
