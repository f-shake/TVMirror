package app.tvmirror.phone;

/** 手机端要用到的端口。音频单开一条连接，避免被大关键帧堵住。 */
public final class ReceiverServicePorts {

    /** 电视端主控端口（握手 + 视频）。 */
    public static final int MAIN = 27183;

    /** 电视端音频端口。 */
    public static final int AUDIO = 27185;

    private ReceiverServicePorts() {
    }
}
