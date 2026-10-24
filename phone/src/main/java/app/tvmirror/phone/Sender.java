package app.tvmirror.phone;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import app.tvmirror.protocol.FrameHeader;
import app.tvmirror.protocol.FrameType;
import app.tvmirror.protocol.Handshake;

/**
 * 发送端：握手 + 视频/音频/心跳收发。
 *
 * 写操作在编码线程里同步完成，不排队：一旦排队，延迟就会随网络抖动累积；
 * 宁可让编码线程短暂阻塞，也不要让画面越来越滞后。
 */
public final class Sender {

    public interface Listener {
        void onDisconnected(String reason);

        void onKeyFrameRequested();

        /** 心跳回包：往返时延 + 电视端上个周期的丢帧/解码帧数。 */
        void onPong(int rttMs, int droppedFrames, int decodedFrames);
    }

    /** 握手被电视拒绝，携带 err 码用于界面文案。 */
    public static final class HandshakeFailure extends IOException {
        public final String code;

        HandshakeFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final String host;
    private final int port;
    private final Handshake handshake;
    private final Listener listener;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean running;
    private volatile long lastRttMs;
    /** 音频走独立连接：和视频共用一条流时，大关键帧会把音频堵在后面。 */
    private Socket audioSocket;
    private OutputStream audioOut;
    /** 音频与视频各自加锁：共用一把锁会让音频阻塞时把视频和心跳一起堵死。 */
    private final Object mainLock = new Object();
    private final Object audioLock = new Object();

    public Sender(String host, int port, Handshake handshake, Listener listener) {
        this.host = host;
        this.port = port;
        this.handshake = handshake;
        this.listener = listener;
    }

    public void connect() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);

            out = s.getOutputStream();
            in = new DataInputStream(s.getInputStream());

            out.write((handshake.toJson() + "\n").getBytes("UTF-8"));
            out.flush();

            String reply = readLine(in);
            if (reply == null) {
                throw new HandshakeFailure("closed", "电视没有响应");
            }
            if (reply.contains("\"ok\":false")) {
                String code = extract(reply, "err");
                throw new HandshakeFailure(code == null ? "unknown" : code, "电视拒绝连接: " + code);
            }
        } catch (IOException e) {
            // 超时/拒绝/写失败都要把已连接的 socket 关掉，否则句柄泄漏，
            // 电视端还会留下一条永远收不到数据的会话。
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经断开
            }
            throw e;
        }

        socket = s;
        socket.setSoTimeout(0);
        running = true;
        startReader();
        startHeartbeat();
    }

    private void startHeartbeat() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(1000);
                        // 与编码器 PTS 同为 CLOCK_BOOTTIME 时基；用 nanoTime 会让延迟整体偏大
                        long nowUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
                        // PING 负载带上本机测得的 RTT，电视端直接显示
                        byte[] pingPayload = new byte[4];
                        FrameHeader.putInt(pingPayload, 0, (int) Math.min(Integer.MAX_VALUE, lastRttMs));
                        writeFrame(FrameType.PING, nowUs, pingPayload);
                    } catch (InterruptedException e) {
                        return;
                    } catch (IOException e) {
                        disconnect("心跳失败");
                        return;
                    }
                }
            }
        }, "tvmirror-ping");
        t.setDaemon(true);
        t.start();
    }

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running) {
                        byte[] prefix = new byte[FrameHeader.PREFIX_BYTES];
                        in.readFully(prefix);
                        int bodyLen = FrameHeader.peekBodyLength(prefix);
                        if (bodyLen < 9 || bodyLen > FrameHeader.MAX_BODY_BYTES) {
                            disconnect("收到非法帧");
                            return;
                        }
                        byte[] full = new byte[FrameHeader.PREFIX_BYTES + bodyLen];
                        System.arraycopy(prefix, 0, full, 0, FrameHeader.PREFIX_BYTES);
                        in.readFully(full, FrameHeader.PREFIX_BYTES, bodyLen);
                        FrameHeader frame = FrameHeader.parse(full);

                        if (frame.type == FrameType.KEYFRAME_REQ) {
                            listener.onKeyFrameRequested();
                        } else if (frame.type == FrameType.PONG) {
                            if (frame.payload.length >= 8) {
                                long sentUs = readLong(frame.payload);
                                long nowUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
                                int rtt = (int) Math.max(0, (nowUs - sentUs) / 1000);
                                lastRttMs = rtt;
                                int drops = 0;
                                int decoded = 0;
                                if (frame.payload.length >= 16) {
                                    drops = FrameHeader.getInt(frame.payload, 8);
                                    decoded = FrameHeader.getInt(frame.payload, 12);
                                }
                                listener.onPong(rtt, drops, decoded);
                            }
                        } else if (frame.type == FrameType.BYE) {
                            disconnect("电视结束了会话");
                            return;
                        }
                    }
                } catch (EOFException e) {
                    disconnect("连接已断开");
                } catch (IOException e) {
                    disconnect("读取失败: " + e.getMessage());
                }
            }
        }, "tvmirror-reader");
        t.setDaemon(true);
        t.start();
    }

    public void sendConfig(byte[] data, long ptsUs) throws IOException {
        writeFrame(FrameType.CONFIG, ptsUs, data);
    }

    public void sendVideo(byte[] data, long ptsUs) throws IOException {
        writeFrame(FrameType.VIDEO, ptsUs, data);
    }

    public void sendAudio(byte[] data, long ptsUs) {
        if (!running) {
            return;
        }
        try {
            if (audioOut == null) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, ReceiverServicePorts.AUDIO), 3000);
                s.setTcpNoDelay(true);
                audioSocket = s;
                audioOut = s.getOutputStream();
            }
            byte[] frame = FrameHeader.encode(FrameType.AUDIO, ptsUs, data);
            synchronized (audioLock) {
                audioOut.write(frame);
                audioOut.flush();
            }
        } catch (IOException e) {
            // 音频断了不影响视频，下一次发送会重连
            closeAudio();
        }
    }

    private void closeAudio() {
        try {
            if (audioSocket != null) {
                audioSocket.close();
            }
        } catch (IOException ignored) {
            // 已经断开
        }
        audioSocket = null;
        audioOut = null;
    }

    public void sendAudioConfig(byte[] csd) throws IOException {
        writeFrame(FrameType.AUDIO_CONFIG, 0, csd);
    }

    public void sendFormat(int width, int height) throws IOException {
        String json = "{\"width\":" + width + ",\"height\":" + height + "}";
        writeFrame(FrameType.FORMAT, 0, json.getBytes("UTF-8"));
    }

    public int lastRttMs() {
        return (int) lastRttMs;
    }

    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }

    private void writeFrame(int type, long ptsUs, byte[] payload) throws IOException {
        if (!running) {
            throw new IOException("会话已结束");
        }
        byte[] frame = FrameHeader.encode(type, ptsUs, payload);
        synchronized (mainLock) {
            out.write(frame);
            out.flush();
        }
    }

    /** 主动结束会话时先发 BYE，电视端据此回待机页而不是当成异常断开。 */
    public void sendBye() {
        try {
            writeFrame(FrameType.BYE, 0, new byte[0]);
        } catch (IOException ignored) {
            // 对端已经断开，无需再通知
        }
    }

    public void close() {
        running = false;
        closeAudio();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 关闭失败不影响退出
        }
    }

    private void disconnect(String reason) {
        if (!running) {
            return;
        }
        running = false;
        closeAudio();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 已经断开
        }
        listener.onDisconnected(reason);
    }

    private static long readLong(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    /** 逐字节读一行，避免 BufferedReader 预读吞掉后续二进制帧。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        while (out.size() < 8192) {
            int b = in.read();
            if (b < 0) {
                return out.size() == 0 ? null : out.toString("UTF-8");
            }
            if (b == '\n') {
                return out.toString("UTF-8");
            }
            out.write(b);
        }
        return null;
    }

    private static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        return end < 0 ? null : json.substring(start + 1, end);
    }
}
