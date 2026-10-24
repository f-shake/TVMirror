package app.tvmirror.tv;

import app.tvmirror.protocol.FrameHeader;
import app.tvmirror.protocol.Handshake;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * 一条已建立的投屏会话：一个 Socket、一份握手、一个统计器。
 *
 * 读写都在这一个类里收口，避免上层各自操作流导致帧边界错乱
 * （TCP 是字节流，两边同时按「帧」读写必须共用同一套长度前缀逻辑）。
 */
public final class Session {

    private final Socket socket;
    public final Handshake handshake;
    public final StatsCollector stats = new StatsCollector();

    private final DataInputStream in;
    private final DataOutputStream out;
    private volatile boolean closed;

    public Session(Socket socket, Handshake handshake) throws IOException {
        this.socket = socket;
        this.handshake = handshake;
        this.socket.setTcpNoDelay(true);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    public FrameHeader readFrame() throws IOException {
        byte[] prefix = new byte[FrameHeader.PREFIX_BYTES];
        in.readFully(prefix);
        int bodyLen = FrameHeader.peekBodyLength(prefix);
        if (bodyLen < 9 || bodyLen > FrameHeader.MAX_BODY_BYTES) {
            throw new IOException("非法帧长: " + bodyLen);
        }
        byte[] full = new byte[FrameHeader.PREFIX_BYTES + bodyLen];
        System.arraycopy(prefix, 0, full, 0, FrameHeader.PREFIX_BYTES);
        in.readFully(full, FrameHeader.PREFIX_BYTES, bodyLen);
        return FrameHeader.parse(full);
    }

    public void writeFrame(int type, long ptsUs, byte[] payload) throws IOException {
        byte[] frame = FrameHeader.encode(type, ptsUs, payload);
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败不影响上层状态机，会话已经标记为结束
        }
    }
}
