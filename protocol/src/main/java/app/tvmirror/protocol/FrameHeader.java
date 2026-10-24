package app.tvmirror.protocol;

/**
 * 帧头：4 字节大端长度 + 1 字节类型 + 8 字节采集时间戳（纳秒级，微秒单位）+ payload。
 *
 * 长度字段统计的是「长度字段之后的全部字节」，即 1（类型）+ 8（时间戳）+ payload。
 * 这样接收端先读 4 字节就知道整帧要读多少，不会把半个帧交给解码器。
 */
public final class FrameHeader {

    public static final int PREFIX_BYTES = 4;
    public static final int HEADER_BYTES = 13;
    public static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    public final int type;
    public final long ptsUs;
    public final byte[] payload;

    public FrameHeader(int type, long ptsUs, byte[] payload) {
        if (!FrameType.isValid(type)) {
            throw new IllegalArgumentException("非法帧类型: " + type);
        }
        if (ptsUs < 0) {
            throw new IllegalArgumentException("时间戳不能为负: " + ptsUs);
        }
        this.type = type;
        this.ptsUs = ptsUs;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public static int bodyLength(int payloadLength) {
        return 9 + payloadLength;
    }

    public static byte[] encode(int type, long ptsUs, byte[] payload) {
        FrameHeader f = new FrameHeader(type, ptsUs, payload);
        int bodyLen = bodyLength(f.payload.length);
        if (bodyLen > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("帧过大: " + bodyLen);
        }
        byte[] out = new byte[PREFIX_BYTES + bodyLen];
        putInt(out, 0, bodyLen);
        out[4] = (byte) type;
        putLong(out, 5, ptsUs);
        System.arraycopy(f.payload, 0, out, HEADER_BYTES, f.payload.length);
        return out;
    }

    /** 输入必须是「4 + bodyLen」的完整帧，长度不符直接抛错，避免半帧被解码。 */
    public static FrameHeader parse(byte[] full) {
        if (full == null || full.length < HEADER_BYTES) {
            throw new IllegalArgumentException("帧长不足: " + (full == null ? -1 : full.length));
        }
        int bodyLen = getInt(full, 0);
        if (bodyLen < 9 || bodyLen > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("非法帧长: " + bodyLen);
        }
        if (full.length != PREFIX_BYTES + bodyLen) {
            throw new IllegalArgumentException(
                    "帧长不符: 声明 " + bodyLen + "，实际 " + (full.length - PREFIX_BYTES));
        }
        int type = full[4] & 0xFF;
        if (!FrameType.isValid(type)) {
            throw new IllegalArgumentException("非法帧类型: " + type);
        }
        long pts = getLong(full, 5);
        if (pts < 0) {
            throw new IllegalArgumentException("时间戳为负: " + pts);
        }
        byte[] payload = new byte[bodyLen - 9];
        System.arraycopy(full, HEADER_BYTES, payload, 0, payload.length);
        return new FrameHeader(type, pts, payload);
    }

    public static int peekBodyLength(byte[] prefix) {
        if (prefix == null || prefix.length < PREFIX_BYTES) {
            throw new IllegalArgumentException("前缀不足 4 字节");
        }
        return getInt(prefix, 0);
    }

    public static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
