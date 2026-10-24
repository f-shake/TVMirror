package app.tvmirror.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 编码降级链：用户选择 → HEVC → AVC。
 *
 * **当前未接入生产链路**：编码选择器已隐藏、固定使用 H.264，
 * 电视回 err=codec 时手机端直接报错，不会沿这条链重试。
 * 保留它是为了以后放开编码选择时直接接上；接之前不要以为降级已经生效。
 *
 * 为什么固定这个顺序：AVC 是唯一能保证「任何 Android 设备都有硬编」的编码，
 * 所以它必须在链尾兜底；HEVC 作为中间档，是因为它比 AVC 画质好且支持面已经够广。
 */
public final class CodecFallback {

    private CodecFallback() {
    }

    /**
     * @param requested 用户选择的编码
     * @param available 本机真实可用的编码集合（由 MediaCodecList 探测得到）
     * @return 可依次尝试的编码列表；空表示连 AVC 都没有，应直接报错
     */
    public static List<String> chain(String requested, Set<String> available) {
        List<String> out = new ArrayList<>(3);
        if (requested != null && available.contains(requested)) {
            out.add(requested);
        }
        if (available.contains(Handshake.CODEC_HEVC) && !out.contains(Handshake.CODEC_HEVC)) {
            out.add(Handshake.CODEC_HEVC);
        }
        if (available.contains(Handshake.CODEC_AVC) && !out.contains(Handshake.CODEC_AVC)) {
            out.add(Handshake.CODEC_AVC);
        }
        return out;
    }

    /** 取链首；链为空返回 null。 */
    public static String pick(String requested, Set<String> available) {
        List<String> chain = chain(requested, available);
        return chain.isEmpty() ? null : chain.get(0);
    }
}
