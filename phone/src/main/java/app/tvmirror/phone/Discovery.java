package app.tvmirror.phone;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tvmirror.protocol.Json;

/**
 * 局域网发现：广播找人，收集应答。
 *
 * 同时发「定向广播」和「255.255.255.255」两种地址，因为部分路由器只放行其中一种；
 * 重复的应答按 IP 去重，避免同一台电视出现两次。
 */
public final class Discovery {

    public static final int PORT = 27184;
    private static final String QUERY = "TVMIRROR_DISCOVER/1";

    public static final class Tv {
        public final String name;
        public final String host;
        public final int port;
        public final boolean needPin;

        Tv(String name, String host, int port, boolean needPin) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.needPin = needPin;
        }

        @Override
        public String toString() {
            return name + "  (" + host + ")";
        }
    }

    private Discovery() {
    }

    public static List<Tv> scan(int timeoutMs) {
        Map<String, Tv> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(300);
            byte[] payload = QUERY.getBytes("UTF-8");

            broadcast(socket, payload, "255.255.255.255");

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    socket.receive(in);
                    String text = new String(in.getData(), 0, in.getLength(), "UTF-8").trim();
                    Map<String, Object> json = Json.parse(text);
                    String host = in.getAddress().getHostAddress();
                    Object nameObj = json.get("name");
                    Object portObj = json.get("port");
                    int port = portObj instanceof Long ? ((Long) portObj).intValue() : 27183;
                    boolean needPin = Boolean.TRUE.equals(json.get("pin"));
                    String name = nameObj instanceof String ? (String) nameObj : host;
                    found.put(host, new Tv(name, host, port, needPin));
                } catch (java.net.SocketTimeoutException e) {
                    // 超时继续等，直到总时限
                } catch (Exception e) {
                    // 单条畸形应答不应中断整个扫描
                }
            }
        } catch (Exception e) {
            // 没有网络权限或被系统限制，返回空列表由界面提示
        }
        return new ArrayList<>(found.values());
    }

    private static void broadcast(DatagramSocket socket, byte[] payload, String address) {
        try {
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(address), PORT));
        } catch (Exception ignored) {
            // 某个广播地址不可用不影响另一种
        }
    }
}
