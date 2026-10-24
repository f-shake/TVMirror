package app.tvmirror.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

import app.tvmirror.protocol.Handshake;

/**
 * 接收端常驻服务：监听 TCP 端口、完成握手、把会话交给界面。
 *
 * 为什么要有前台服务而不是只在 Activity 里监听：电视经常停在待机页，
 * Activity 可能被系统回收，但「手机一连就能投」要求监听必须一直活着。
 */
public final class ReceiverService extends Service {

    public static final int PORT = 27183;
    public static final int DISCOVERY_PORT = 27184;
    public static final int AUDIO_PORT = 27185;

    private static volatile Socket pendingAudioSocket;

    /** 手机端会另开一条连接专门发音频；会话建立后在这里等它。 */
    public static Socket takeAudioSocket() {
        Socket s = pendingAudioSocket;
        pendingAudioSocket = null;
        return s;
    }

    private static final String TAG = "TVMirror";
    private static final String CHANNEL_ID = "tvmirror_cast";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile String pinCache;
    private static volatile Session activeSession;

    private ServerSocket serverSocket;
    private DiscoveryResponder discovery;
    private volatile boolean running = true;
    private final Object lock = new Object();

    /**
     * 配对码持久化：同一台电视的码在卸载前保持不变。
     * 这样手机只需首次输入，之后记住即可——否则每次开机都换码，配对等于没做。
     */
    public static String pin(Context context) {
        if (pinCache == null) {
            synchronized (ReceiverService.class) {
                if (pinCache == null) {
                    android.content.SharedPreferences sp =
                            context.getSharedPreferences("tvmirror", Context.MODE_PRIVATE);
                    String saved = sp.getString("pin", null);
                    if (saved == null || !saved.matches("\\d{4}")) {
                        saved = String.format(java.util.Locale.US,
                                "%04d", new SecureRandom().nextInt(10000));
                        sp.edit().putString("pin", saved).apply();
                    }
                    pinCache = saved;
                }
            }
        }
        return pinCache;
    }

    /** 仅用于界面显示；服务未起来时可能为 null，调用方应改用 pin(context)。 */
    public static String pin() {
        return pinCache;
    }

    public static Session activeSession() {
        return activeSession;
    }

    public static void clearSession(Session session) {
        if (activeSession == session) {
            activeSession = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pin(this);
        // 部分电视固件会拒绝第三方 App 拉起前台服务；此时不能让它把 onCreate 打断，
        // 否则后面的端口监听永远不会执行，表现就是「手机连不上、电视毫无反应」。
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException e) {
            Log.w(TAG, "前台服务被拒，降级为普通服务: " + e.getMessage());
        }
        startServer();
        discovery = new DiscoveryResponder(Build.MODEL == null ? "Android TV" : Build.MODEL, PORT);
        discovery.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (discovery != null) {
            discovery.stop();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响进程退出
            }
        }
        Session s = activeSession;
        if (s != null) {
            s.close();
        }
        super.onDestroy();
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    while (running) {
                        final Socket client = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        }, "tvmirror-accept").start();
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "监听失败: " + e.getMessage());
                    }
                }
            }
        }, "tvmirror-server").start();
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(10_000);

            String line = readLine(client.getInputStream());
            if (line == null) {
                client.close();
                return;
            }

            Handshake handshake;
            try {
                handshake = Handshake.parse(line);
            } catch (Handshake.ParseException e) {
                respond(client, "{\"ok\":false,\"err\":\"" + e.code + "\"}");
                client.close();
                return;
            }

            if (handshake.pin == null || !handshake.pin.equals(pin(this))) {
                respond(client, "{\"ok\":false,\"err\":\"pin\"}");
                client.close();
                return;
            }

            if (!DecoderCaps.canDecode(handshake.codec)) {
                respond(client, "{\"ok\":false,\"err\":\"codec\"}");
                client.close();
                return;
            }

            // 尺寸超能力就明确拒绝，别让解码器在半路报错把会话打崩
            if (!DecoderCaps.supports(handshake.codec, handshake.width, handshake.height)) {
                Log.w(TAG, "拒绝尺寸 " + handshake.width + "x" + handshake.height);
                DecoderCaps.logCapabilities(handshake.codec);
                respond(client, "{\"ok\":false,\"err\":\"size\"}");
                client.close();
                return;
            }
            DecoderCaps.logCapabilities(handshake.codec);

            synchronized (lock) {
                if (activeSession != null && !activeSession.isClosed()) {
                    respond(client, "{\"ok\":false,\"err\":\"busy\"}");
                    client.close();
                    return;
                }
                respond(client, "{\"ok\":true,\"session\":1}");
                // 心跳是每秒一次，但手机锁屏后 MIUI 会冻结整个进程（连 wake lock 都被禁），
                // 这时心跳也会停。判活窗口设宽一些：短暂锁屏不该导致断线，
                // 醒来后心跳恢复，会话继续；真掉线（Wi-Fi 断、进程被杀）才收摊。
                client.setSoTimeout(120_000);
                Session session = new Session(client, handshake);
                activeSession = session;
                SessionHub.publish(session);
            }
            startAudioAccept(handshake.audio);
            bringToFront();
        } catch (IOException e) {
            Log.w(TAG, "连接处理失败: " + e.getMessage());
            try {
                client.close();
            } catch (IOException ignored) {
                // 已经断开
            }
        }
    }

    /** 手机端要投声音才监听音频端口；上一条会话残留的连接必须先关掉。 */
    private void startAudioAccept(final boolean wantAudio) {
        if (!wantAudio) {
            return;
        }
        dropPendingAudio();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    // 上一条会话的 accept 线程可能还在超时窗口内，允许端口复用
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(AUDIO_PORT));
                    ss.setSoTimeout(15000);
                    Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    Socket previous = pendingAudioSocket;
                    pendingAudioSocket = s;
                    if (previous != null) {
                        try {
                            previous.close();
                        } catch (IOException ignored) {
                            // 已经断开
                        }
                    }
                } catch (IOException e) {
                    // 手机端没连音频（静音或权限被拒）时视频照常，但要把原因留在日志里
                    Log.w(TAG, "音频监听结束: " + e.getMessage());
                } finally {
                    if (ss != null) {
                        try {
                            ss.close();
                        } catch (IOException ignored) {
                            // 已经关闭
                        }
                    }
                }
            }
        }, "tvmirror-audio-accept").start();
    }

    /** 丢掉尚未被消费的音频连接，避免被下一条会话误当成自己的。 */
    public static void dropPendingAudio() {
        Socket s = pendingAudioSocket;
        pendingAudioSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经断开
            }
        }
    }

    /** 逐字节读一行，不用 BufferedReader——它会预读，把后续二进制帧吃掉。 */
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

    private static void respond(Socket client, String json) {
        try {
            OutputStream out = client.getOutputStream();
            out.write((json + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (IOException ignored) {
            // 对端已经断了，回复没有意义
        }
    }

    private void bringToFront() {
        Intent intent = new Intent(this, MirrorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            // 后台启动 Activity 受限时只能让用户手动打开，服务仍然保持会话
            Log.w(TAG, "无法唤起界面: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "投屏接收", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("等待手机连接");
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MirrorActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("TVMirror 投屏")
                .setContentText("等待手机连接")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
