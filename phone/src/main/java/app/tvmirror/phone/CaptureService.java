package app.tvmirror.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import app.tvmirror.protocol.AdaptiveBitrate;
import app.tvmirror.protocol.Handshake;
import app.tvmirror.protocol.Preset;

/**
 * 投屏会话的宿主：持有 MediaProjection、编码器、发送器与会话生命周期。
 *
 * 为什么必须前台服务：录屏期间系统要求有可见通知，否则进程随时可能被回收；
 * 同时投屏是长时间任务，息屏后被冻结就断了。
 */
public final class CaptureService extends Service implements Sender.Listener {

    public static final String ACTION_START = "app.tvmirror.phone.START";
    public static final String ACTION_STOP = "app.tvmirror.phone.STOP";
    /** 投屏进行中修改参数，不必重新申请录屏权限。 */
    public static final String ACTION_UPDATE = "app.tvmirror.phone.UPDATE";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_PRESET = "preset";
    public static final String EXTRA_CODEC = "codec";
    public static final String EXTRA_AUDIO = "audio";
    public static final String EXTRA_MAX_LONG_SIDE = "maxLongSide";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";

    private static final String TAG = "TVMirror";
    private static final String CHANNEL_ID = "tvmirror_cast";
    private static final int NOTIFICATION_ID = 2002;

    /** 界面据此决定按钮显示「开始」还是「停止」；服务被系统杀掉时可能短暂不准。 */
    private static volatile boolean running;

    /** 最近一次失败原因，界面用它给用户一个明确交代，而不是静默回到「开始投屏」。 */
    private static volatile String lastError;

    public static boolean isRunning() {
        return running;
    }

    public static String lastError() {
        return lastError;
    }

    public static void clearError() {
        lastError = null;
    }

    private MediaProjection projection;
    private volatile Sender sender;
    private volatile ScreenEncoder encoder;
    private AudioEncoder audioEncoder;
    private PowerManager.WakeLock wakeLock;
    private android.media.AudioManager audioManager;
    private boolean mutedSource;
    /** 周期性重申静音的任务；MIUI 的音量记忆会把音量恢复，单次静音不够。 */
    private Thread muteKeeper;
    private volatile Preset preset;
    private String targetHost = "";
    private volatile AdaptiveBitrate adaptive;
    private volatile int maxLongSide = 1920;
    private volatile int targetFps = 60;
    private volatile int targetBitrate;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UPDATE.equals(intent.getAction())) {
            applyUpdate(intent);
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在连接电视…"));
        running = true;

        targetHost = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 27183);
        String pin = intent.getStringExtra(EXTRA_PIN);
        String codec = intent.getStringExtra(EXTRA_CODEC);
        preset = Preset.fromId(intent.getStringExtra(EXTRA_PRESET));
        if (preset == null) {
            preset = Preset.STABLE;
        }
        maxLongSide = intent.getIntExtra(EXTRA_MAX_LONG_SIDE, preset.width);
        targetFps = intent.getIntExtra(EXTRA_FPS, preset.fps);
        targetBitrate = intent.getIntExtra(EXTRA_BITRATE, preset.bitrateBps);
        boolean wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, null);

        acquireWakeLock();
        startSession(codec, pin, port, wantAudio);
        return START_STICKY;
    }

    /**
     * 投屏中改参数：码率直接调，分辨率/帧率需要重建编码器。
     *
     * 重建时复用同一个 VirtualDisplay（Android 14 一个投影只能创建一次），
     * 并通过 FORMAT 帧告诉电视端新的画面尺寸。
     */
    private void applyUpdate(Intent intent) {
        final int newFps = intent.getIntExtra(EXTRA_FPS, targetFps);
        final int newBitrate = intent.getIntExtra(EXTRA_BITRATE, targetBitrate);
        final int newLongSide = intent.getIntExtra(EXTRA_MAX_LONG_SIDE, maxLongSide);
        Preset newPreset = Preset.fromId(intent.getStringExtra(EXTRA_PRESET));
        final boolean presetChanged = newPreset != null && !newPreset.id.equals(preset.id);
        if (newPreset != null) {
            preset = newPreset;
        }

        final ScreenEncoder enc = encoder;
        if (enc == null) {
            return;
        }
        final boolean needsRestart = newFps != targetFps || newLongSide != maxLongSide
                || presetChanged;
        final boolean bitrateChanged = newBitrate != targetBitrate;

        targetFps = newFps;
        targetBitrate = newBitrate;
        maxLongSide = newLongSide;
        if (bitrateChanged || presetChanged) {
            // 自适应里存着本轮的上限与当前值，不同步更新的话，
            // 下一个心跳（≤1 秒）就会把用户刚选的码率覆盖回旧值。
            adaptive = new AdaptiveBitrate(preset, newBitrate / 1000);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (needsRestart) {
                        int[] size = computeSize();
                        enc.update(targetFps, targetBitrate, preset.iFrameSeconds);
                        enc.restart(size[0], size[1], size[2]);
                        Sender s = sender;
                        if (s != null) {
                            s.sendFormat(size[0], size[1]);
                        }
                    } else if (bitrateChanged) {
                        enc.setBitrate(targetBitrate);
                    }
                } catch (Exception e) {
                    // 重建失败会让编码线程永久停摆，必须让界面看见，而不是只留一行日志
                    fail("切换参数失败：" + e.getMessage());
                }
            }
        }, "tvmirror-update").start();
    }

    private void startSession(final String codec, final String pin, final int port,
                              final boolean wantAudio) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int[] size = computeSize();
                Handshake handshake = new Handshake(codec, preset.id, size[0], size[1],
                        targetFps, targetBitrate, wantAudio, pin, Build.MODEL);
                adaptive = new AdaptiveBitrate(preset, targetBitrate / 1000);

                final boolean bench = getSharedPreferences("tvmirror_phone", MODE_PRIVATE)
                        .getBoolean("bench", false);
                if (!bench) {
                    sender = new Sender(targetHost, port, handshake, CaptureService.this);
                    try {
                        sender.connect();
                    } catch (Sender.HandshakeFailure e) {
                        fail("电视拒绝连接：" + friendly(e.code));
                        return;
                    } catch (Exception e) {
                        fail("连不上电视：" + e.getMessage());
                        return;
                    }
                } else {
                    Log.i(TAG, "基准模式：只采集与编码，不连接电视");
                }

                encoder = new ScreenEncoder(projection, new ScreenEncoder.Sink() {
                    @Override
                    public void onConfig(byte[] data, long ptsUs) {
                        Sender s = sender;
                        if (s == null) {
                            return;
                        }
                        try {
                            s.sendConfig(data, ptsUs);
                        } catch (Exception e) {
                            stopSelf();
                        }
                    }

                    @Override
                    public void onVideo(byte[] data, long ptsUs) {
                        Sender s = sender;
                        if (s == null) {
                            return;
                        }
                        try {
                            s.sendVideo(data, ptsUs);
                        } catch (Exception e) {
                            stopSelf();
                        }
                    }
                }, codec, targetFps, targetBitrate, preset.iFrameSeconds,
                        preset == Preset.QUALITY);

                try {
                    encoder.start(size[0], size[1], size[2]);
                } catch (Exception e) {
                    fail("编码器启动失败：" + e.getMessage());
                    return;
                }

                if (wantAudio) {
                    audioEncoder = new AudioEncoder(projection, new AudioEncoder.Sink() {
                        @Override
                        public void onAudio(byte[] data, long ptsUs) {
                            Sender s = sender;
                            if (s == null) {
                                return;
                            }
                            s.sendAudio(data, ptsUs);
                        }

                        @Override
                        public void onAudioConfig(byte[] csd) {
                            // 当前是 PCM 直传，AudioEncoder 不会调用这里；
                            // 保留这条路径只为兼容旧的 AAC 协议帧，实际不可达。
                            try {
                                Sender s = sender;
                                if (s != null) {
                                    s.sendAudioConfig(csd);
                                }
                            } catch (Exception ignored) {
                                // 同上
                            }
                        }
                    });
                    audioEncoder.start();
                }

                updateNotification(bench ? "基准模式：仅采集编码（不投屏）"
                        : "正在投屏到 " + targetHost);
                muteSourceAudio();
                registerDisplayListener();
            }
        }, "tvmirror-start").start();
    }

    private void registerDisplayListener() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    return;
                }
                restartForRotation();
            }
        }, null);
    }

    /** 旋转屏后必须按新尺寸重建采集与编码，否则画面会被裁切或拉伸。 */
    private void restartForRotation() {
        final ScreenEncoder current = encoder;
        if (current == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int[] size = computeSize();
                    current.restart(size[0], size[1], size[2]);
                    Sender s = sender;
                    if (s != null) {
                        s.sendFormat(size[0], size[1]);
                    }
                } catch (Exception e) {
                    // restart() 失败会让编码线程永久停摆，画面冻结；必须让用户看到原因
                    fail("旋转后重建失败：" + e.getMessage());
                }
            }
        }, "tvmirror-rotate").start();
    }

    private int[] computeSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int[] screen = CaptureGeometry.screenSize(wm);
        int[] size = CaptureGeometry.computeSize(screen[0], screen[1], maxLongSide);
        return new int[]{size[0], size[1], screen[2]};
    }

    private void fail(String message) {
        Log.w(TAG, message);
        lastError = message;
        stopSelf();
    }

    private String friendly(String code) {
        switch (code) {
            case "pin":
                return "配对码不对";
            case "busy":
                return "电视正在被另一台设备投屏";
            case "codec":
                return "电视不支持这个编码";
            case "version":
                return "两边版本不一致，请都更新到最新版";
            case "range":
                return "参数超出电视端允许范围，请把电视端 App 也更新到最新版";
            case "size":
                return "电视解码器不支持这个分辨率，已退回较低档";
            default:
                return code;
        }
    }

    /**
     * 投屏期间把本机媒体音量静音。
     *
     * 播放采集（AudioPlaybackCapture）只「复制」音频，不会让本机不出声；
     * 用户想要「只听电视」就必须在本机静音。结束后恢复。
     */
    private void muteSourceAudio() {
        try {
            audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_MUTE, 0);
            mutedSource = true;
            if (muteKeeper == null) {
                muteKeeper = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (running) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (!mutedSource || audioManager == null) {
                                continue;
                            }
                            // 播放器启动时常把媒体音量恢复，这里持续压回去
                            audioManager.adjustStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_MUTE, 0);
                        }
                    }
                }, "tvmirror-mute");
                muteKeeper.setDaemon(true);
                muteKeeper.start();
            }
        } catch (RuntimeException ignored) {
            // 部分系统限制第三方改音量，静音失败不影响投屏
        }
    }

    private void restoreSourceAudio() {
        mutedSource = false;
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_UNMUTE, 0);
        } catch (RuntimeException ignored) {
            // 已经恢复或系统拒绝
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVMirror:cast");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (muteKeeper != null) {
            muteKeeper.interrupt();
            muteKeeper = null;
        }
        restoreSourceAudio();
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder = null;
        }
        if (sender != null) {
            // 先发 BYE 再断开：否则电视端读到 EOF 会当成异常断开并弹错误提示
            sender.sendBye();
            sender.close();
            sender = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Sender.Listener ----

    @Override
    public void onDisconnected(String reason) {
        Log.w(TAG, "会话断开: " + reason);
        lastError = "连接已断开：" + reason;
        stopSelf();
    }

    @Override
    public void onKeyFrameRequested() {
        ScreenEncoder current = encoder;
        if (current != null) {
            current.requestKeyFrame();
        }
    }

    @Override
    public void onPong(int rttMs, int droppedFrames, int decodedFrames) {
        AdaptiveBitrate ab = adaptive;
        ScreenEncoder enc = encoder;
        if (ab == null || enc == null) {
            return;
        }
        int total = droppedFrames + decodedFrames;
        float droppedRatio = total > 0 ? (float) droppedFrames / total : 0f;
        int kbps = ab.onSample(rttMs, 0, droppedRatio, android.os.SystemClock.elapsedRealtime());
        Log.d(TAG, "自适应: rtt=" + rttMs + "ms 丢帧=" + droppedFrames + "/" + total
                + " 目标码率=" + kbps + "kbps 状态=" + ab.state());
        enc.setBitrate(kbps * 1000);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("TVMirror 投屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
                .setOngoing(true)
                .build();
    }
}
