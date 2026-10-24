package app.tvmirror.protocol;

/**
 * 帧类型。手机端发 VIDEO/CONFIG/AUDIO/PING/BYE，电视端回 KEYFRAME_REQ/PONG。
 * 数值一旦发布就不能改：两端可能版本不一致，靠它做兼容判断。
 */
public final class FrameType {

    /** 视频帧，payload 为编码器输出的原始 buffer（含编码器自身的封装格式）。 */
    public static final int VIDEO = 1;

    /** 电视端请求一个关键帧，payload 为空。 */
    public static final int KEYFRAME_REQ = 2;

    public static final int PING = 3;
    public static final int PONG = 4;

    /** 编码配置（SPS/PPS 等），payload 即编码器的 codec-config buffer。 */
    public static final int CONFIG = 5;

    /** 音频帧：payload 是**裸 PCM**（44.1kHz/立体声/16bit），PTS 在帧头，不在 payload 里。 */
    public static final int AUDIO = 6;

    /** 主动结束会话，payload 为空。 */
    public static final int BYE = 7;

    /**
     * 画面尺寸变更（旋转屏/切分辨率），payload 为 JSON：{"width":1920,"height":1080}。
     *
     * 为什么不复用 CONFIG：CONFIG 承载的是编码器吐出的 codec-config buffer（二进制），
     * 两者语义完全不同，混用会让解码端只能靠猜。
     */
    public static final int FORMAT = 8;

    /**
     * 音频编码配置（历史遗留，当前链路不发送）。
     *
     * 早期设计用 AAC，需要 AudioSpecificConfig；后来改为 PCM 直传，
     * 编码配置不再需要。数值保留以免破坏协议兼容，链路两端对它的处理都是可选的。
     */
    public static final int AUDIO_CONFIG = 9;

    private FrameType() {
    }

    public static boolean isValid(int type) {
        return type >= VIDEO && type <= AUDIO_CONFIG;
    }
}
