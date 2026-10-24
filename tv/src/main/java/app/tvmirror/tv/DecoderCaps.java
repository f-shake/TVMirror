package app.tvmirror.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import app.tvmirror.protocol.CodecMime;

/**
 * 电视端解码能力查询。
 *
 * 用来在握手阶段拒绝手机端送来、但本机解码器吃不下的大尺寸，
 * 而不是等解码器报错后整个会话崩掉。
 */
public final class DecoderCaps {

    private DecoderCaps() {
    }

    /** 本机是否存在该编码的解码器。 */
    public static boolean canDecode(String codec) {
        String mime = CodecMime.mimeOf(codec);
        if (mime == null) {
            return false;
        }
        return findDecoder(mime) != null;
    }

    /** 该编码是否支持这个尺寸。 */
    public static boolean supports(String codec, int width, int height) {
        String mime = CodecMime.mimeOf(codec);
        if (mime == null || width <= 0 || height <= 0) {
            return false;
        }
        MediaCodecInfo info = findDecoder(mime);
        if (info == null) {
            return false;
        }
        try {
            MediaCodecInfo.VideoCapabilities caps =
                    info.getCapabilitiesForType(mime).getVideoCapabilities();
            return caps != null && caps.isSizeSupported(width, height);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 把该编码支持的最大尺寸打进日志，便于排查「电视到底能不能解」。 */
    public static void logCapabilities(String codec) {
        String mime = CodecMime.mimeOf(codec);
        if (mime == null) {
            return;
        }
        MediaCodecInfo info = findDecoder(mime);
        if (info == null) {
            Log.i("TVMirror", "解码能力: " + mime + " 无可用解码器");
            return;
        }
        try {
            MediaCodecInfo.VideoCapabilities caps =
                    info.getCapabilitiesForType(mime).getVideoCapabilities();
            Log.i("TVMirror", "解码能力: " + info.getName() + " 最大 "
                    + caps.getSupportedWidths().getUpper() + "x"
                    + caps.getSupportedHeights().getUpper()
                    + " 支持2880x1800=" + caps.isSizeSupported(2880, 1800)
                    + " 支持1920x1200=" + caps.isSizeSupported(1920, 1200));
        } catch (RuntimeException e) {
            Log.i("TVMirror", "解码能力查询失败: " + e.getMessage());
        }
    }

    private static MediaCodecInfo findDecoder(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) {
                    return info;
                }
            }
        }
        return null;
    }
}
