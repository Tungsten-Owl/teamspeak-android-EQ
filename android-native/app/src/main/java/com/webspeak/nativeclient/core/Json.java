package com.webspeak.nativeclient.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer.
 *
 * <p>The client deliberately avoids a JSON library: the gateway protocol is small,
 * and keeping the parser in plain Java means the protocol layer can be compiled
 * and exercised on a desktop JVM without Android or third-party dependencies.
 * Parsed values use {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Double}, {@code Boolean} and {@code null}, mirroring JavaScript's
 * {@code JSON.parse} closely enough for the wire types in {@code core}.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;
    // Directory snapshots may carry base64 avatars for every member.
    private static final int MAX_TEXT_LENGTH = 32 * 1024 * 1024;

    /** Thrown for malformed JSON. Callers treat malformed control frames as droppable. */
    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String json) throws JsonException {
        if (json == null) throw new JsonException("input is null");
        if (json.length() > MAX_TEXT_LENGTH) throw new JsonException("input is too large");
        Json parser = new Json(json);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.index != json.length()) {
            throw new JsonException("trailing characters at " + parser.index);
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder(128);
        writeValue(builder, value, 0);
        return builder.toString();
    }

    /** Convenience builder for command payloads and test fixtures. */
    public static Map<String, Object> object(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("pairs expected");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            result.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    public static String string(Map<String, Object> object, String key) {
        if (object == null) return null;
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    public static boolean has(Map<String, Object> object, String key) {
        return object != null && object.containsKey(key) && object.get(key) != null;
    }

    /** Returns an integral double as an Integer, or null when absent/non-integral. */
    public static Integer integer(Map<String, Object> object, String key) {
        if (object == null) return null;
        Object value = object.get(key);
        if (!(value instanceof Double)) return null;
        double number = (Double) value;
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
        return (int) number;
    }

    public static int intOr(Map<String, Object> object, String key, int fallback) {
        Integer value = integer(object, key);
        return value == null ? fallback : value;
    }

    public static double number(Map<String, Object> object, String key, double fallback) {
        if (object == null) return fallback;
        Object value = object.get(key);
        return value instanceof Double ? (Double) value : fallback;
    }

    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        if (object == null) return fallback;
        Object value = object.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public static Map<String, Object> object(Map<String, Object> object, String key) {
        return object == null ? null : asObject(object.get(key));
    }

    public static List<Object> array(Map<String, Object> object, String key) {
        return object == null ? null : asArray(object.get(key));
    }

    // ---------------------------------------------------------------- reading

    private Object readValue(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw new JsonException("nesting is too deep");
        skipWhitespace();
        if (index >= text.length()) throw new JsonException("unexpected end of input");
        char c = text.charAt(index);
        switch (c) {
            case '{':
                return readObject(depth + 1);
            case '[':
                return readArray(depth + 1);
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                throw new JsonException("unexpected character '" + c + "' at " + index);
        }
    }

    private Map<String, Object> readObject(int depth) throws JsonException {
        Map<String, Object> result = new LinkedHashMap<>();
        index++; // '{'
        skipWhitespace();
        if (peek() == '}') { index++; return result; }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw new JsonException("object key expected at " + index);
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw new JsonException("':' expected at " + index);
            index++;
            result.put(key, readValue(depth));
            skipWhitespace();
            char c = peek();
            if (c == ',') { index++; continue; }
            if (c == '}') { index++; return result; }
            throw new JsonException("',' or '}' expected at " + index);
        }
    }

    private List<Object> readArray(int depth) throws JsonException {
        List<Object> result = new ArrayList<>();
        index++; // '['
        skipWhitespace();
        if (peek() == ']') { index++; return result; }
        while (true) {
            result.add(readValue(depth));
            skipWhitespace();
            char c = peek();
            if (c == ',') { index++; continue; }
            if (c == ']') { index++; return result; }
            throw new JsonException("',' or ']' expected at " + index);
        }
    }

    private String readString() throws JsonException {
        index++; // opening quote
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (index >= text.length()) throw new JsonException("unterminated string");
            char c = text.charAt(index++);
            if (c == '"') return builder.toString();
            if (c == '\\') {
                if (index >= text.length()) throw new JsonException("unterminated escape");
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw new JsonException("truncated unicode escape");
                        String hex = text.substring(index, index + 4);
                        index += 4;
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException error) {
                            throw new JsonException("invalid unicode escape \\u" + hex);
                        }
                        break;
                    default:
                        throw new JsonException("invalid escape '\\" + escape + "'");
                }
                continue;
            }
            if (c < 0x20) throw new JsonException("unescaped control character in string");
            builder.append(c);
        }
    }

    private Double readNumber() throws JsonException {
        int start = index;
        if (peek() == '-') index++;
        while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        if (index < text.length() && text.charAt(index) == '.') {
            index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        }
        String raw = text.substring(start, index);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException error) {
            throw new JsonException("invalid number '" + raw + "'");
        }
    }

    private void expect(String literal) throws JsonException {
        if (!text.startsWith(literal, index)) {
            throw new JsonException("'" + literal + "' expected at " + index);
        }
        index += literal.length();
    }

    private char peek() throws JsonException {
        if (index >= text.length()) throw new JsonException("unexpected end of input");
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') index++;
            else break;
        }
    }

    // ---------------------------------------------------------------- writing

    private static void writeValue(StringBuilder builder, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("value nesting is too deep");
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            writeString(builder, (String) value);
        } else if (value instanceof Boolean) {
            builder.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            writeNumber(builder, ((Number) value).doubleValue());
        } else if (value instanceof Number) {
            builder.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            writeObject(builder, (Map<?, ?>) value, depth + 1);
        } else if (value instanceof Iterable) {
            writeArray(builder, (Iterable<?>) value, depth + 1);
        } else if (value instanceof int[]) {
            int[] array = (int[]) value;
            builder.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) builder.append(',');
                builder.append(array[i]);
            }
            builder.append(']');
        } else if (value instanceof String[]) {
            String[] array = (String[]) value;
            builder.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) builder.append(',');
                writeString(builder, array[i]);
            }
            builder.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map, int depth) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue(), depth);
        }
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, Iterable<?> items, int depth) {
        builder.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) builder.append(',');
            first = false;
            writeValue(builder, item, depth);
        }
        builder.append(']');
    }

    private static void writeNumber(StringBuilder builder, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            builder.append("null");
            return;
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            builder.append((long) value);
        } else {
            builder.append(value);
        }
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        builder.append('"');
    }
}
