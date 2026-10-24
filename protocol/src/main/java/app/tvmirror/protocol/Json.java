package app.tvmirror.protocol;

/**
 * 极简 JSON：只支持「扁平对象」——键是字符串，值是字符串、数字、布尔或 null。
 *
 * 为什么手写而不是引第三方库：protocol 模块要能在纯 JVM 上单测，不能带 Android 依赖；
 * 也不希望 Android 的 org.json 与 JVM 实现产生行为差异。握手报文本来就是扁平对象，够用。
 */
public final class Json {

    private Json() {
    }

    /** 解析失败时抛出，消息里带出错位置，便于定位两端协议不一致。 */
    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // 控制字符必须转义，否则对端解析会失败
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static java.util.Map<String, Object> parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        Parser p = new Parser(text);
        return p.parseObject();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        java.util.Map<String, Object> parseObject() throws JsonException {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWs();
            expect('{');
            skipWs();
            if (peek() == '}') {
                i++;
                finish();
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object value = parseValue();
                if (map.containsKey(key)) {
                    throw new JsonException("重复的键: " + key + " @" + i);
                }
                map.put(key, value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    break;
                }
                throw new JsonException("期望 , 或 }，实际: " + c + " @" + i);
            }
            finish();
            return map;
        }

        private Object parseValue() throws JsonException {
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                literal("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                literal("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                literal("null");
                return null;
            }
            return parseNumber();
        }

        private Object parseNumber() throws JsonException {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                throw new JsonException("非法值 @" + i);
            }
            String num = s.substring(start, i);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字: " + num + " @" + start);
            }
        }

        private String parseString() throws JsonException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new JsonException("转义未完成");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new JsonException("\\u 转义不完整");
                            }
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new JsonException("非法 \\u 转义: " + hex);
                            }
                            break;
                        default:
                            throw new JsonException("未知转义: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void literal(String word) throws JsonException {
            if (!s.startsWith(word, i)) {
                throw new JsonException("期望 " + word + " @" + i);
            }
            i += word.length();
        }

        private void expect(char c) throws JsonException {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new JsonException("期望 " + c + " @" + i);
            }
            i++;
        }

        private char peek() throws JsonException {
            if (i >= s.length()) {
                throw new JsonException("意外的结尾");
            }
            return s.charAt(i);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private void finish() throws JsonException {
            skipWs();
            if (i != s.length()) {
                throw new JsonException("对象结束后仍有多余内容 @" + i);
            }
        }
    }
}
