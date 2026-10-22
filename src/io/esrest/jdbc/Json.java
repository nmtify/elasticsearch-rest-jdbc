package io.esrest.jdbc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser and writer, so the driver has no external dependencies. */
final class Json {
    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position < text.length()) {
            throw parser.error("Trailing characters");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(value, builder);
        return builder.toString();
    }

    // ---------------------------------------------------------------- parsing

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + position);
    }

    private void skipWhitespace() {
        while (position < text.length() && " \n\r\t".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private boolean at(char expected) {
        return position < text.length() && text.charAt(position) == expected;
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        switch (text.charAt(position)) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': return readLiteral("true", Boolean.TRUE);
            case 'f': return readLiteral("false", Boolean.FALSE);
            case 'n': return readLiteral("null", null);
            default: return readNumber();
        }
    }

    private Object readLiteral(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("Expected " + word);
        }
        position += word.length();
        return value;
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (at('}')) {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (!at('"')) {
                throw error("Expected object key");
            }
            String key = readString();
            skipWhitespace();
            if (!at(':')) {
                throw error("Expected ':'");
            }
            position++;
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            if (at('}')) {
                position++;
                return object;
            }
            if (!at(',')) {
                throw error("Expected ',' or '}'");
            }
            position++;
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        position++;
        skipWhitespace();
        if (at(']')) {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            if (at(']')) {
                position++;
                return array;
            }
            if (!at(',')) {
                throw error("Expected ',' or ']'");
            }
            position++;
        }
    }

    private String readString() {
        StringBuilder builder = new StringBuilder();
        position++;
        while (true) {
            if (position >= text.length()) {
                throw error("Unterminated string");
            }
            char current = text.charAt(position++);
            if (current == '"') {
                return builder.toString();
            }
            if (current != '\\') {
                builder.append(current);
                continue;
            }
            builder.append(readEscape());
        }
    }

    private char readEscape() {
        if (position >= text.length()) {
            throw error("Unterminated escape");
        }
        char escape = text.charAt(position++);
        switch (escape) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u':
                if (position + 4 > text.length()) {
                    throw error("Bad unicode escape");
                }
                char unicode = (char) Integer.parseInt(text.substring(position, position + 4), 16);
                position += 4;
                return unicode;
            default:
                throw error("Bad escape");
        }
    }

    private Object readNumber() {
        int start = position;
        boolean decimal = false;
        while (position < text.length()) {
            char current = text.charAt(position);
            if (current == '.' || current == 'e' || current == 'E') {
                decimal = true;
            } else if (!Character.isDigit(current) && current != '-' && current != '+') {
                break;
            }
            position++;
        }
        if (start == position) {
            throw error("Unexpected character '" + text.charAt(position) + "'");
        }
        String number = text.substring(start, position);
        if (decimal) {
            return Double.parseDouble(number);
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException tooLarge) {
            return new BigDecimal(number);
        }
    }

    // ---------------------------------------------------------------- writing

    private static void write(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, builder);
        } else if (value instanceof List) {
            writeArray((List<?>) value, builder);
        } else {
            quote(value.toString(), builder);
        }
    }

    private static void writeObject(Map<?, ?> object, StringBuilder builder) {
        builder.append('{');
        Iterator<? extends Map.Entry<?, ?>> entries = object.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<?, ?> entry = entries.next();
            quote(String.valueOf(entry.getKey()), builder);
            builder.append(':');
            write(entry.getValue(), builder);
            if (entries.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
    }

    private static void writeArray(List<?> array, StringBuilder builder) {
        builder.append('[');
        Iterator<?> items = array.iterator();
        while (items.hasNext()) {
            write(items.next(), builder);
            if (items.hasNext()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    private static void quote(String value, StringBuilder builder) {
        builder.append('"');
        for (char current : value.toCharArray()) {
            switch (current) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                default:
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
            }
        }
        builder.append('"');
    }
}
