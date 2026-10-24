package app.tvmirror.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 电视端界面：待机页 + 全屏 SurfaceView + 统计浮层。
 *
 * 待机页要显示 IP 和配对码，因为手机端需要这两样才能连上；
 * 统计浮层默认隐藏，按遥控器菜单键才出来，平时不挡画面。
 */
public final class MirrorActivity extends Activity
        implements SurfaceHolder.Callback, SessionHub.Listener, VideoDecoder.Callback {

    private SurfaceView surfaceView;
    private Surface surface;
    private boolean surfaceReady;

    private LinearLayout standby;
    private TextView standbyIp;
    private TextView standbyPin;
    private TextView statsView;

    private Session session;
    private VideoDecoder decoder;
    private AudioDecoder audio;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (session != null) {
                statsView.setText(session.stats.describe());
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mirror);

        surfaceView = findViewById(R.id.surface);
        standby = findViewById(R.id.standby);
        standbyIp = findViewById(R.id.standbyIp);
        standbyPin = findViewById(R.id.standbyPin);
        statsView = findViewById(R.id.stats);

        surfaceView.getHolder().addCallback(this);

        startService(new Intent(this, ReceiverService.class));
        refreshStandby();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionHub.setListener(this);
        Session active = ReceiverService.activeSession();
        if (active != null && session == null) {
            onSession(active);
        }
        refreshStandby();
    }

    @Override
    protected void onPause() {
        SessionHub.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopSession();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (session != null) {
            stopSession();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            statsView.setVisibility(statsView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        surfaceReady = true;
        maybeStartDecoding();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 分辨率变化由解码器自己适配，这里不需要重建
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        surface = null;
        stopSession();
    }

    // ---- SessionHub.Listener ----

    @Override
    public void onSession(Session incoming) {
        if (session != null && session != incoming) {
            stopSession();
        }
        session = incoming;
        maybeStartDecoding();
    }

    // ---- VideoDecoder.Callback ----

    @Override
    public void onFirstFrame() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                standby.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MirrorActivity.this, message, Toast.LENGTH_LONG).show();
                stopSession();
            }
        });
    }

    @Override
    public void onEnded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopSession();
            }
        });
    }

    @Override
    public void onFormatChanged(final int width, final int height) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyAspect(width, height);
            }
        });
    }

    /**
     * 按视频宽高比等比缩放到屏幕内并居中。
     *
     * 为什么必须做：SurfaceView 默认铺满父布局，竖屏手机的画面会被横向拉宽到 16:9，
     * 看着就是「变横了、脸被拉扁」。保持比例后多余区域留黑边，画面才是正确的。
     */
    private void applyAspect(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        float scale = Math.min((float) screenWidth / videoWidth, (float) screenHeight / videoHeight);
        int viewWidth = Math.max(1, Math.round(videoWidth * scale));
        int viewHeight = Math.max(1, Math.round(videoHeight * scale));

        android.widget.FrameLayout.LayoutParams params =
                new android.widget.FrameLayout.LayoutParams(viewWidth, viewHeight);
        params.gravity = android.view.Gravity.CENTER;
        surfaceView.setLayoutParams(params);
    }

    // ---- 内部状态 ----

    private void maybeStartDecoding() {
        if (session == null || !surfaceReady || surface == null || decoder != null) {
            return;
        }
        standby.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        applyAspect(session.handshake.width, session.handshake.height);
        decoder = new VideoDecoder(surface, session, this);
        if (session.handshake.audio) {
            audio = new AudioDecoder();
            audio.start();
            decoder.setAudioSink(audio);
        }
        decoder.start();
        handler.post(statsTick);
    }

    private void stopSession() {
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
        if (audio != null) {
            audio.stop();
            audio = null;
        }
        if (session != null) {
            session.close();
            ReceiverService.clearSession(session);
            SessionHub.clear(session);
            session = null;
        }
        handler.removeCallbacks(statsTick);
        statsView.setVisibility(View.GONE);
        standby.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshStandby();
    }

    private void refreshStandby() {
        String ip = localIp();
        standbyIp.setText("本机地址  " + ip + ":" + ReceiverService.PORT);
        standbyPin.setText("配对码  " + ReceiverService.pin(this));
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到地址时给占位符，用户仍可手输
        }
        return "未获取到地址";
    }
}
