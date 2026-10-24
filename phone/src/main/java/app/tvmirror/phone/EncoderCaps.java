package app.tvmirror.phone;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.util.LinkedHashSet;
import java.util.Set;

import app.tvmirror.protocol.CodecMime;
import app.tvmirror.protocol.Handshake;

/**
 * 手机端的编码能力探测。
 *
 * 判定标准不是「有这个 MIME」，而是「在这个分辨率与帧率下真的能编」——
 * 有些机型有 HEVC 编码器但只支持到 1080p30，直接按 MIME 放行会得到握手成功、画面全黑。
 */
public final class EncoderCaps {

    private static final String[] ALL = {
            Handshake.CODEC_AVC, Handshake.CODEC_HEVC, Handshake.CODEC_AV1, Handshake.CODEC_VP9
    };

    private EncoderCaps() {
    }

    public static Set<String> available(int width, int height, int fps) {
        Set<String> result = new LinkedHashSet<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String mime : info.getSupportedTypes()) {
                String codec = CodecMime.codecOf(mime);
                if (codec == null || result.contains(codec)) {
                    continue;
                }
                if (supports(info, mime, width, height, fps)) {
                    result.add(codec);
                }
            }
        }
        return result;
    }

    private static boolean supports(MediaCodecInfo info, String mime, int width, int height, int fps) {
        try {
            MediaCodecInfo.VideoCapabilities caps =
                    info.getCapabilitiesForType(mime).getVideoCapabilities();
            if (caps == null) {
                return false;
            }
            if (!caps.isSizeSupported(width, height)) {
                return false;
            }
            return caps.getSupportedFrameRatesFor(width, height).contains((double) fps);
        } catch (RuntimeException e) {
            // 某些厂商编码器在查询能力时会抛异常，按不支持处理
            return false;
        }
    }

    /** 给界面用的置灰原因。 */
    public static String reasonFor(String codec) {
        switch (codec) {
            case Handshake.CODEC_AV1:
                return "本机不支持 AV1 编码";
            case Handshake.CODEC_VP9:
                return "本机不支持 VP9 编码";
            case Handshake.CODEC_HEVC:
                return "本机不支持 HEVC 编码";
            default:
                return "本机不支持该编码";
        }
    }

    public static String[] allCodecs() {
        return ALL.clone();
    }

    /** 指定分辨率下本机能达到的最高编码帧率；拿不到时返回 60 兜底。 */
    public static int maxFps(int width, int height) {
        int best = 0;
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String mime : info.getSupportedTypes()) {
                if (CodecMime.codecOf(mime) == null) {
                    continue;
                }
                try {
                    MediaCodecInfo.VideoCapabilities caps =
                            info.getCapabilitiesForType(mime).getVideoCapabilities();
                    if (caps == null || !caps.isSizeSupported(width, height)) {
                        continue;
                    }
                    double upper = caps.getSupportedFrameRatesFor(width, height).getUpper();
                    best = Math.max(best, (int) Math.floor(upper));
                } catch (RuntimeException ignored) {
                    // 能力查询失败的编码器直接跳过
                }
            }
        }
        if (best <= 0) {
            return 60;
        }
        return Math.min(120, best);
    }

    /** 探测结果：能用的编码 + 实际可落地的采集长边与帧率。 */
    public static final class Plan {
        public final Set<String> codecs;
        public final int maxLongSide;
        public final int fps;

        Plan(Set<String> codecs, int maxLongSide, int fps) {
            this.codecs = codecs;
            this.maxLongSide = maxLongSide;
            this.fps = fps;
        }

        public boolean usable() {
            return !codecs.isEmpty();
        }
    }

    /**
     * 从最理想参数开始逐级降级，直到找到本机能编的组合。
     *
     * 为什么必须降级：不少设备的硬编只到 1080p30，硬按 1080p60 判定会得出「一台编码器都没有」，
     * 用户看到的就是「本机不支持」，而实际上 720p30 完全能跑。
     */
    public static Plan detect(int width, int height, int wantedFps) {
        int longSide = Math.max(width, height);
        int[][] candidates = {
                {width, height, longSide},
                {1280, 720, 1280},
                {960, 540, 960},
        };
        int[] rates = {wantedFps, 30, 24};
        for (int[] size : candidates) {
            for (int rate : rates) {
                Set<String> found = available(size[0], size[1], rate);
                if (!found.isEmpty()) {
                    return new Plan(found, size[2], Math.min(rate, wantedFps));
                }
            }
        }
        return new Plan(new LinkedHashSet<String>(), 1280, 30);
    }
}
