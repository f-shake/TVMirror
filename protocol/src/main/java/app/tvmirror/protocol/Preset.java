package app.tvmirror.protocol;

/**
 * 三档预设的参数包。切换预设 = 换这一组数字，不是换一套代码。
 *
 * 注意：目前界面只使用 {@link #STABLE} 一档（模式选择器已隐藏），
 * 另外两档保留给后续放开。
 */
public enum Preset {

    // 三档都把基线帧率压到 30：实测这台电视在 60fps 下解码跟不上，
    // 一旦丢帧就会触发关键帧补偿，反而更卡。需要 60fps 可在界面上手动覆盖。
    LATENCY("latency", "延迟优先", 1920, 1080, 30, 12_000_000, 1, 0, Handshake.CODEC_AVC),

    QUALITY("quality", "画质优先", 1920, 1080, 30, 16_000_000, 2, 2, Handshake.CODEC_HEVC),

    STABLE("stable", "稳定性优先", 1920, 1080, 30, 8_000_000, 1, 3, Handshake.CODEC_AVC);

    public final String id;
    public final String label;
    public final int width;
    public final int height;
    public final int fps;
    public final int bitrateBps;
    /** 关键帧间隔（秒）。越大越省带宽，但断线恢复越慢。 */
    public final int iFrameSeconds;
    /**
     * 接收端渲染前缓冲的帧数。**当前没有消费者**：
     * 接收端用的是硬编码的 24ms 呈现延迟，改这个值不会有任何行为变化。
     */
    public final int bufferFrames;
    /** 预设偏好的编码。**当前没有消费者**：编码选择器已隐藏，链路固定 H.264。 */
    public final String preferredCodec;

    Preset(String id, String label, int width, int height, int fps,
           int bitrateBps, int iFrameSeconds, int bufferFrames, String preferredCodec) {
        this.id = id;
        this.label = label;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrateBps = bitrateBps;
        this.iFrameSeconds = iFrameSeconds;
        this.bufferFrames = bufferFrames;
        this.preferredCodec = preferredCodec;
    }

    public int bitrateKbps() {
        return bitrateBps / 1000;
    }

    /** 找不到返回 null，由调用方决定是回落默认值还是报错。 */
    public static Preset fromId(String id) {
        if (id == null) {
            return null;
        }
        for (Preset p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
