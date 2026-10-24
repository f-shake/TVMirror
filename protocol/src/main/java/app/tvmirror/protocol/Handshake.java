package app.tvmirror.protocol;

import java.util.Map;

/**
 * 握手报文。手机端在 TCP 连上后先发一行 JSON（以 \n 结尾），电视端回一行 JSON。
 *
 * 校验放在这里而不是各端各写一份：两端共用同一套规则，就不会出现「手机放行、电视拒绝」的错位。
 */
public final class Handshake {

    public static final int VERSION = 1;

    public static final String CODEC_AVC = "avc";
    public static final String CODEC_HEVC = "hevc";
    public static final String CODEC_AV1 = "av1";
    public static final String CODEC_VP9 = "vp9";

    private static final int MIN_DIM = 64;
    private static final int MAX_WIDTH = 3840;
    private static final int MAX_HEIGHT = 2160;
    private static final int MIN_FPS = 24;
    /** 允许到 120：高刷屏「不限帧率」时确实能跑到 120，协议层不能再写死 60。 */
    private static final int MAX_FPS = 120;
    private static final int MIN_BITRATE = 500_000;
    private static final int MAX_BITRATE = 25_000_000;
    private static final int MAX_NAME = 64;
    /** 接收端缓冲上限（毫秒）。超过 2 秒对交互投屏已无意义。 */
    private static final int MAX_BUFFER_MS = 2000;

    public final String codec;
    public final String preset;
    public final int width;
    public final int height;
    public final int fps;
    public final int bitrate;
    /**
     * 接收端呈现前的缓冲（毫秒）。0 表示用默认的小缓冲。
     *
     * 缓冲越大越抗抖动（画面节奏更稳），代价是端到端延迟同步增加。
     */
    public final int bufferMs;
    public final boolean audio;
    public final String pin;
    public final String name;

    public Handshake(String codec, String preset, int width, int height, int fps,
                     int bitrate, boolean audio, String pin, String name) {
        this(codec, preset, width, height, fps, bitrate, 0, audio, pin, name);
    }

    public Handshake(String codec, String preset, int width, int height, int fps,
                     int bitrate, int bufferMs, boolean audio, String pin, String name) {
        this.codec = codec == null ? CODEC_AVC : codec;
        this.preset = preset == null ? Preset.LATENCY.id : preset;
        this.width = width <= 0 ? 1920 : width;
        this.height = height <= 0 ? 1080 : height;
        this.fps = fps <= 0 ? 60 : fps;
        this.bitrate = bitrate <= 0 ? 12_000_000 : bitrate;
        this.bufferMs = Math.max(0, Math.min(MAX_BUFFER_MS, bufferMs));
        this.audio = audio;
        this.pin = pin;
        this.name = name == null ? "Android" : name;
    }

    public static final class ParseException extends Exception {
        /** 与握手失败应答里的 err 字段一一对应，便于两端用同一套文案。 */
        public final String code;

        public ParseException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static boolean isKnownCodec(String codec) {
        return CODEC_AVC.equals(codec) || CODEC_HEVC.equals(codec)
                || CODEC_AV1.equals(codec) || CODEC_VP9.equals(codec);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(192);
        sb.append('{')
                .append(Json.quote("v")).append(':').append(VERSION).append(',')
                .append(Json.quote("codec")).append(':').append(Json.quote(codec)).append(',')
                .append(Json.quote("preset")).append(':').append(Json.quote(preset)).append(',')
                .append(Json.quote("width")).append(':').append(width).append(',')
                .append(Json.quote("height")).append(':').append(height).append(',')
                .append(Json.quote("fps")).append(':').append(fps).append(',')
                .append(Json.quote("bitrate")).append(':').append(bitrate).append(',')
                .append(Json.quote("bufferMs")).append(':').append(bufferMs).append(',')
                .append(Json.quote("audio")).append(':').append(audio).append(',')
                .append(Json.quote("pin")).append(':').append(pin == null ? "null" : Json.quote(pin)).append(',')
                .append(Json.quote("name")).append(':').append(Json.quote(name))
                .append('}');
        return sb.toString();
    }

    public static Handshake parse(String json) throws ParseException {
        Map<String, Object> m;
        try {
            m = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new ParseException("format", "握手不是合法 JSON: " + e.getMessage());
        }

        long v = asLong(m, "v", -1);
        if (v != VERSION) {
            throw new ParseException("version", "协议版本不支持: " + v);
        }

        String codec = asString(m, "codec", CODEC_AVC);
        if (!isKnownCodec(codec)) {
            throw new ParseException("codec", "未知编码: " + codec);
        }

        String preset = asString(m, "preset", Preset.LATENCY.id);
        if (Preset.fromId(preset) == null) {
            throw new ParseException("preset", "未知预设: " + preset);
        }

        // 先按 long 校验再收窄为 int：直接 (int) 截断会让 4294967424 变成 128 混过校验
        long widthL = asLong(m, "width", 1920);
        long heightL = asLong(m, "height", 1080);
        long fpsL = asLong(m, "fps", 60);
        long bitrateL = asLong(m, "bitrate", 12_000_000);

        if (widthL < MIN_DIM || widthL > MAX_WIDTH || widthL % 2 != 0
                || heightL < MIN_DIM || heightL > MAX_HEIGHT || heightL % 2 != 0) {
            throw new ParseException("range", "分辨率非法: " + widthL + "x" + heightL);
        }
        if (fpsL < MIN_FPS || fpsL > MAX_FPS) {
            throw new ParseException("range", "帧率非法: " + fpsL);
        }
        if (bitrateL < MIN_BITRATE || bitrateL > MAX_BITRATE) {
            throw new ParseException("range", "码率非法: " + bitrateL);
        }
        long bufferMsL = asLong(m, "bufferMs", 0);
        if (bufferMsL < 0 || bufferMsL > MAX_BUFFER_MS) {
            throw new ParseException("range", "缓冲参数非法: " + bufferMsL);
        }
        int width = (int) widthL;
        int height = (int) heightL;
        int fps = (int) fpsL;
        int bitrate = (int) bitrateL;
        int bufferMs = (int) bufferMsL;

        String pin = asNullableString(m, "pin");
        if (pin != null && !pin.matches("\\d{4}")) {
            throw new ParseException("pin", "配对码必须是 4 位数字");
        }

        String name = asString(m, "name", "Android");
        if (name.length() > MAX_NAME) {
            throw new ParseException("name", "设备名过长");
        }

        boolean audio = asBoolean(m, "audio", false);
        return new Handshake(codec, preset, width, height, fps, bitrate, bufferMs,
                audio, pin, name);
    }

    private static long asLong(Map<String, Object> m, String key, long def) throws ParseException {
        Object o = m.get(key);
        if (o == null) {
            return def;
        }
        if (o instanceof Long) {
            return (Long) o;
        }
        if (o instanceof Double) {
            return Math.round((Double) o);
        }
        throw new ParseException("format", "字段 " + key + " 应为数字");
    }

    private static String asNullableString(Map<String, Object> m, String key) throws ParseException {
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        throw new ParseException("format", "字段 " + key + " 应为字符串");
    }

    private static String asString(Map<String, Object> m, String key, String def) throws ParseException {
        String s = asNullableString(m, key);
        return s == null ? def : s;
    }

    private static boolean asBoolean(Map<String, Object> m, String key, boolean def) throws ParseException {
        Object o = m.get(key);
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        throw new ParseException("format", "字段 " + key + " 应为布尔");
    }
}
