package com.smsbridge.mobile;

import java.util.LinkedHashMap;
import java.util.Map;

/** 与桌面端一致的极简扁平 JSON，用来解析配对/短信回执。 */
public final class Json {
    private final LinkedHashMap<String, Object> map = new LinkedHashMap<>();

    public static Json obj() {
        return new Json();
    }

    public static Json parse(String text) {
        Parser p = new Parser(text);
        Json value = p.parseObject();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("JSON 结束后还有多余内容");
        }
        return value;
    }

    public Json put(String key, String value) {
        map.put(key, value);
        return this;
    }

    public Json put(String key, long value) {
        map.put(key, value);
        return this;
    }

    public Json put(String key, boolean value) {
        map.put(key, value);
        return this;
    }

    public String getString(String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public long getLong(String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public boolean getBool(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return def;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, e.getKey());
            sb.append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                quote(sb, String.valueOf(v));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Json parseObject() {
            skipWs();
            expect('{');
            Json j = new Json();
            skipWs();
            if (peek() == '}') {
                i++;
                return j;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object value = parseValue();
                j.map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return j;
                }
                if (c != ',') {
                    throw err("期望 ',' 或 '}'");
                }
            }
        }

        private Object parseValue() {
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                throw err("无效的字面量");
            }
            if (c == 'n') {
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                throw err("无效的字面量");
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                int start = i;
                if (peek() == '-') {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                boolean floating = false;
                if (i < s.length() && s.charAt(i) == '.') {
                    floating = true;
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    floating = true;
                    i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                        i++;
                    }
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                String num = s.substring(start, i);
                try {
                    return floating ? Double.parseDouble(num) : Long.parseLong(num);
                } catch (NumberFormatException e) {
                    throw err("无效的数字: " + num);
                }
            }
            throw err("无法识别的值");
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("字符串没有结束");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw err("转义没有结束");
                    }
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw err("\\u 转义不完整");
                            }
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw err("\\u 转义无效");
                            }
                        }
                        default -> throw err("未知转义 \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw err("内容意外结束");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            char got = next();
            if (got != c) {
                throw err("期望 '" + c + "'，实际得到 '" + got + "'");
            }
        }

        private IllegalArgumentException err(String message) {
            return new IllegalArgumentException(message + " (位置 " + i + ")");
        }
    }
}
