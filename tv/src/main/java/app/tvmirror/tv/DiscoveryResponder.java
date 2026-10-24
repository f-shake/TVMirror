package app.tvmirror.tv;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import app.tvmirror.protocol.Json;

/**
 * UDP 广播应答：手机广播找人，电视把「我是谁、端口多少、要不要配对码」回给它。
 *
 * 为什么不广播配对码：Pairing 码一旦进了广播包，同网段任何人都能拿到，等于没有保护。
 */
public final class DiscoveryResponder implements Runnable {

    public static final String QUERY = "TVMIRROR_DISCOVER/1";
    private static final String TAG = "TVMirror";

    private final String deviceName;
    private final int tcpPort;
    private volatile boolean running = true;
    private DatagramSocket socket;

    public DiscoveryResponder(String deviceName, int tcpPort) {
        this.deviceName = deviceName;
        this.tcpPort = tcpPort;
    }

    public void start() {
        new Thread(this, "tvmirror-discovery").start();
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(ReceiverService.DISCOVERY_PORT);
            socket.setBroadcast(true);
            byte[] buf = new byte[512];
            while (running) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                socket.receive(in);
                String query = new String(in.getData(), 0, in.getLength(), "UTF-8").trim();
                if (!QUERY.equals(query)) {
                    continue;
                }
                String reply = "{\"name\":" + Json.quote(deviceName)
                        + ",\"port\":" + tcpPort
                        + ",\"pin\":true}";
                byte[] out = reply.getBytes("UTF-8");
                socket.send(new DatagramPacket(out, out.length, in.getAddress(), in.getPort()));
            }
        } catch (Exception e) {
            // 端口被占用或网卡变化时退出即可，服务本身仍可用（手输 IP 兜底）
            android.util.Log.w(TAG, "发现服务停止: " + e.getMessage());
        }
    }
}
