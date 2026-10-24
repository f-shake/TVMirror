package app.tvmirror.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

import app.tvmirror.protocol.FrameHeader;
import app.tvmirror.protocol.FrameType;

/**
 * 音频播放：手机端直接传 PCM，这里写进 AudioTrack。
 *
 * 音频优先走独立连接（27185），因为和视频共用一条 TCP 流时，
 * 一个几十上百 KB 的关键帧会把音频包堵在后面，听感就是「时不时卡一下」。
 */
public final class AudioDecoder implements VideoDecoder.AudioSink, Runnable {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    /** 约 23ms 的缓冲：与视频端 24ms 的呈现延迟对齐，既抗抖动又不落后画面。 */
    private static final int BUFFER_BYTES = 4096;

    private volatile boolean running = true;
    private Thread thread;
    private AudioTrack track;
    /** run() 里拿到的音频连接：stop() 必须关掉它，否则读阻塞会让线程永远退不出来。 */
    private volatile Socket socket;

    public void start() {
        thread = new Thread(this, "tvmirror-audio");
        thread.start();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (java.io.IOException ignored) {
                // 已经断开
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void submit(long ptsUs, byte[] payload) {
        // 音频只走 27185 独立连接；主连接上不会出现 AUDIO 帧，这里保留空实现以兼容接口。
    }

    @Override
    public void onConfig(byte[] csd) {
        // PCM 不需要编码配置
    }

    @Override
    public void run() {
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_MASK)
                            .build())
                    .setBufferSizeInBytes(BUFFER_BYTES)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();

            socket = waitForAudioSocket();
            if (socket != null) {
                readFromSocket();
            }
        } catch (RuntimeException e) {
            android.util.Log.w("TVMirror", "音频播放不可用: " + e.getMessage());
        } finally {
            if (track != null) {
                try {
                    track.stop();
                    track.release();
                } catch (RuntimeException ignored) {
                    // 释放阶段异常不影响退出
                }
                track = null;
            }
        }
    }

    /** 等手机端建立音频专用连接；整个会话期间都可以等到，不设死线。 */
    private Socket waitForAudioSocket() {
        while (running) {
            Socket s = ReceiverService.takeAudioSocket();
            if (s != null) {
                return s;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    private void readFromSocket() {
        Socket s = socket;
        if (s == null) {
            return;
        }
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (running) {
                byte[] prefix = new byte[FrameHeader.PREFIX_BYTES];
                in.readFully(prefix);
                int bodyLength = FrameHeader.peekBodyLength(prefix);
                if (bodyLength < 9 || bodyLength > FrameHeader.MAX_BODY_BYTES) {
                    return;
                }
                byte[] full = new byte[FrameHeader.PREFIX_BYTES + bodyLength];
                System.arraycopy(prefix, 0, full, 0, FrameHeader.PREFIX_BYTES);
                in.readFully(full, FrameHeader.PREFIX_BYTES, bodyLength);
                FrameHeader frame = FrameHeader.parse(full);
                if (frame.type == FrameType.AUDIO) {
                    track.write(frame.payload, 0, frame.payload.length);
                }
            }
        } catch (EOFException e) {
            // 对端关闭，正常结束
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("TVMirror", "音频连接结束: " + e.getMessage());
        } finally {
            // 连接由本对象持有，退出时自己关，别指望 stop() 之外的人
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经断开
            }
        }
    }
}
