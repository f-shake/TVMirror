package app.tvmirror.protocol;

/**
 * 编码名与 MediaCodec MIME 的映射。
 *
 * 放在协议层是因为手机端和电视端都要用；各写一份必然有一天两边对不上，
 * 那时候表现是「握手成功、画面全黑」，极难排查。
 */
public final class CodecMime {

    private CodecMime() {
    }

    /** 未知编码返回 null，调用方应当视为不支持。 */
    public static String mimeOf(String codec) {
        if (codec == null) {
            return null;
        }
        switch (codec) {
            case Handshake.CODEC_AVC:
                return "video/avc";
            case Handshake.CODEC_HEVC:
                return "video/hevc";
            case Handshake.CODEC_AV1:
                return "video/av01";
            case Handshake.CODEC_VP9:
                return "video/x-vnd.on2.vp9";
            default:
                return null;
        }
    }

    /** MIME 反查编码名；不认识返回 null。 */
    public static String codecOf(String mime) {
        if (mime == null) {
            return null;
        }
        switch (mime.toLowerCase(java.util.Locale.US)) {
            case "video/avc":
                return Handshake.CODEC_AVC;
            case "video/hevc":
                return Handshake.CODEC_HEVC;
            case "video/av01":
                return Handshake.CODEC_AV1;
            case "video/x-vnd.on2.vp9":
                return Handshake.CODEC_VP9;
            default:
                return null;
        }
    }
}
