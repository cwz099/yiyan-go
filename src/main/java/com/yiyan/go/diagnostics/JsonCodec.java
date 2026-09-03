package com.yiyan.go.diagnostics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec for versioned local records and API envelopes. */
public final class JsonCodec {
    private JsonCodec() { }

    public static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        write(value, result, 0);
        return result.toString();
    }

    private static void write(Object value, StringBuilder result, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        if (value == null) { result.append("null"); return; }
        if (value instanceof String text) {
            result.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    case '\b' -> result.append("\\b");
                    case '\f' -> result.append("\\f");
                    default -> {
                        if (c < 0x20 || Character.isSurrogate(c)) result.append(String.format("\\u%04x", (int) c));
                        else result.append(c);
                    }
                }
            }
            result.append('"');
        } else if (value instanceof Boolean) result.append(value);
        else if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Non-finite number");
            result.append(number);
        } else if (value instanceof Map<?, ?> map) {
            result.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON object key");
                if (!first) result.append(',');
                write(entry.getKey(), result, depth + 1);
                result.append(':');
                write(entry.getValue(), result, depth + 1);
                first = false;
            }
            result.append('}');
        } else if (value instanceof Iterable<?> list) {
            result.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) result.append(',');
                write(item, result, depth + 1);
                first = false;
            }
            result.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }

    public static Object parse(String text) throws IOException {
        if (text == null) throw new IOException("Invalid JSON");
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.index != text.length()) throw parser.invalid();
        return result;
    }

    private static final class Parser {
        private final String text;
        private int index;
        Parser(String text) { this.text = text; }
        IOException invalid() { return new IOException("Invalid JSON"); }
        void space() { while (index < text.length() && " \n\r\t".indexOf(text.charAt(index)) >= 0) index++; }
        boolean take(char c) { if (index < text.length() && text.charAt(index) == c) { index++; return true; } return false; }
        Object value(int depth) throws IOException {
            if (depth > 64) throw invalid();
            space();
            if (index >= text.length()) throw invalid();
            char c = text.charAt(index);
            if (c == '"') return string();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (take('}')) return map;
                do {
                    space();
                    String key = string();
                    space();
                    if (!take(':') || map.containsKey(key)) throw invalid();
                    map.put(key, value(depth + 1));
                    space();
                    if (take('}')) return map;
                } while (take(','));
                throw invalid();
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>();
                space();
                if (take(']')) return list;
                do { list.add(value(depth + 1)); space(); if (take(']')) return list; } while (take(','));
                throw invalid();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, index)) {
                    index += literal.length();
                    return literal.equals("null") ? null : literal.equals("true");
                }
            }
            int start = index;
            take('-');
            if (take('0')) { /* A leading zero cannot be followed by digits. */ }
            else {
                if (index >= text.length() || text.charAt(index) < '1' || text.charAt(index) > '9') throw invalid();
                digits();
            }
            boolean decimal = false;
            if (take('.')) { decimal = true; int before = index; digits(); if (before == index) throw invalid(); }
            if (take('e') || take('E')) {
                decimal = true;
                if (!take('+')) take('-');
                int before = index; digits(); if (before == index) throw invalid();
            }
            try {
                String number = text.substring(start, index);
                if (!decimal) return Long.parseLong(number);
                double result = Double.parseDouble(number);
                if (!Double.isFinite(result)) throw invalid();
                return result;
            } catch (NumberFormatException exception) { throw invalid(); }
        }
        void digits() { while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++; }
        String string() throws IOException {
            if (!take('"')) throw invalid();
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw invalid();
                if (c != '\\') { result.append(c); continue; }
                if (index >= text.length()) throw invalid();
                switch (text.charAt(index++)) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) throw invalid();
                        for (int offset = 0; offset < 4; offset++) {
                            char hex = text.charAt(index + offset);
                            if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f')
                                    || (hex >= 'A' && hex <= 'F'))) throw invalid();
                        }
                        try { result.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); }
                        catch (NumberFormatException exception) { throw invalid(); }
                        index += 4;
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }
    }
}
