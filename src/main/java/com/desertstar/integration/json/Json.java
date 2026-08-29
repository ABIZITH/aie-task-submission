package com.desertstar.integration.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON reader/writer.
 *
 * This project intentionally avoids Jackson/Gson: the sandbox this was built in
 * has no Maven Central access, and pulling a real JSON library is a one-line
 * change later (see README "Known limitations"). Values are represented as:
 * Map<String,Object>, List<Object>, String, Double, Boolean, or null.
 */
public final class Json {

    private Json() {
    }

    // ---------- Parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object result = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("Unexpected trailing content at position " + p.pos);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object at the top level");
        }
        return (Map<String, Object>) v;
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static class Parser {
        final String s;
        int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            if (atEnd()) throw new JsonException("Unexpected end of input at position " + pos);
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(peek())) pos++;
        }

        void expect(char c) {
            if (atEnd() || peek() != c) {
                throw new JsonException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return parseObjectValue();
                case '[': return parseArrayValue();
                case '"': return parseStringValue();
                case 't':
                case 'f': return parseBooleanValue();
                case 'n': return parseNullValue();
                default: return parseNumberValue();
            }
        }

        Map<String, Object> parseObjectValue() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseStringValue();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new JsonException("Expected ',' or '}' at position " + (pos - 1));
            }
            return result;
        }

        List<Object> parseArrayValue() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new JsonException("Expected ',' or ']' at position " + (pos - 1));
            }
            return result;
        }

        String parseStringValue() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: throw new JsonException("Invalid escape \\" + esc + " at position " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBooleanValue() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Object parseNullValue() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Double parseNumberValue() {
            int start = pos;
            if (!atEnd() && peek() == '-') pos++;
            while (!atEnd() && Character.isDigit(peek())) pos++;
            if (!atEnd() && peek() == '.') {
                pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++;
                while (!atEnd() && Character.isDigit(peek())) pos++;
            }
            String numStr = s.substring(start, pos);
            if (numStr.isEmpty() || numStr.equals("-")) {
                throw new JsonException("Invalid number at position " + start);
            }
            return Double.parseDouble(numStr);
        }
    }

    // ---------- Writing ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValuePretty(value, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                writeValue(v, sb);
            }
            sb.append(']');
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Number) {
            sb.append(formatNumber((Number) value));
        } else {
            writeString(value.toString(), sb);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValuePretty(Object value, StringBuilder sb, int indent) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                indent(sb, indent + 1);
                writeString(e.getKey(), sb);
                sb.append(": ");
                writeValuePretty(e.getValue(), sb, indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, indent + 1);
                writeValuePretty(list.get(i), sb, indent + 1);
                if (i < list.size() - 1) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append(']');
        } else {
            writeValue(value, sb);
        }
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    private static String formatNumber(Number n) {
        double d = n.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            // Render whole numbers without a trailing .0, but keep 2dp money via BigDecimal upstream.
            long l = (long) d;
            if (l == d) return String.valueOf(l);
        }
        return String.valueOf(d);
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
