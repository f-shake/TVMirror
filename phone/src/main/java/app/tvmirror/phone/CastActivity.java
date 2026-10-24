package app.tvmirror.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import app.tvmirror.protocol.Handshake;
import app.tvmirror.protocol.Preset;

/**
 * 手机端入口：找电视、输配对码、选模式与编码、开始投屏。
 *
 * 编码列表只把本机真正有硬编的项放开，其余置灰并写明原因——
 * 让用户选一个编不出来的编码，比直接不显示更糟。
 */
public final class CastActivity extends Activity {

    private static final int REQ_PROJECTION = 100;
    private static final int REQ_AUDIO = 101;
    private static final String PREFS = "tvmirror_phone";

    private TextView statusView;
    private TextView deviceView;
    /** 「电视/搜索电视」整张卡片：投屏中隐藏。 */
    private View deviceCardView;
    private EditText pinInput;
    private EditText ipInput;
    private Spinner presetSpinner;
    private Spinner codecSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private Spinner resolutionSpinner;
    private Button scanButton;
    private Button toggleButton;

    /** 0=不限（设备上限），其余为显式帧率。自适应模式已无「跟随模式」可言。 */
    private static final int[] FPS_VALUES = {30, 60, 0};
    private static final int[] BITRATE_MBPS = {0, 1, 2, 4, 8, 12, 16, 20};
    private static final int[] RESOLUTION_LONG_SIDE = {0, 1920, 1280, 960};

    /**
     * 是否显示编码选择器。
     *
     * 目前锁成 H.264：这台电视的 HEVC 解码器在 1080p 运动中会花屏，720p30 又牺牲太多，
     * 不值得为边际清晰度牺牲可用性。能力探测与降级链都还留着，改成 true 即可放开。
     */
    private static final boolean SHOW_CODEC_SELECTOR = false;

    /**
     * 是否显示模式选择器。
     *
     * 实测只有「稳定性优先」在这台设备上可用，另外两档会卡顿掉帧，
     * 所以先收起，统一走自适应模式（内部按 STABLE 参数 + 实时丢帧反馈调码率）。
     */
    private static final boolean SHOW_PRESET_SELECTOR = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Discovery.Tv selected;
    private boolean[] codecEnabled = new boolean[0];
    private String[] codecIds = new String[0];
    private EncoderCaps.Plan plan;
    private int deviceMaxFps = 60;
    /** 上一次轮询时是否处于投屏状态，用来判断「刚结束」并复位状态文案。 */
    private boolean wasCasting;
    /** 接收端缓冲（毫秒）；0 表示用电视端默认的小缓冲。 */
    private int bufferMs;
    /** 是否把声音一起投过去。 */
    private boolean audioEnabled = true;
    /** 平板原生长边（例如 2880），「自动」优先用它。 */
    private int nativeLongSide = 1920;
    private boolean nativeSupported;
    private String targetHost;
    private String targetPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 调试用：`am start ... --ez bench true` 启动后，投屏只采集编码、不连电视，
        // 便于在不动电视的情况下测量编码器实际输出。
        boolean bench = getIntent().getBooleanExtra("bench", false);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("bench", bench).apply();
        // 调试用：`am start ... --ei bufferMs 1000` 可设定电视端缓冲（毫秒），验证抖动与延迟的取舍
        int requestedBuffer = getIntent().getIntExtra("bufferMs", -1);
        if (requestedBuffer >= 0) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("bufferMs", requestedBuffer).apply();
        }
        // 调试用：`am start ... --ez audio false` 可关掉声音（默认投声音）
        if (getIntent().hasExtra("audio")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("audioEnabled", getIntent().getBooleanExtra("audio", true)).apply();
        }
        // 从「投屏已暂停」通知进来：立刻重新申请录屏权限，恢复原会话。
        // 注意必须在 setContentView 与 findViewById 之后再做，否则 statusView 还是 null。
        setContentView(R.layout.activity_cast);

        statusView = findViewById(R.id.status);
        deviceView = findViewById(R.id.device);
        pinInput = findViewById(R.id.pin);
        ipInput = findViewById(R.id.manualIp);
        presetSpinner = findViewById(R.id.preset);
        codecSpinner = findViewById(R.id.codec);
        fpsSpinner = findViewById(R.id.fps);
        bitrateSpinner = findViewById(R.id.bitrate);
        resolutionSpinner = findViewById(R.id.resolution);
        scanButton = findViewById(R.id.scan);
        toggleButton = findViewById(R.id.toggle);
        deviceCardView = findViewById(R.id.deviceCard);

        // 长按「开始/停止」切换是否投声音，并在状态里显示出来。
        // 声音开关没有独立控件，但它必须可发现、可恢复，不能只靠启动参数。
        toggleButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                audioEnabled = !audioEnabled;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("audioEnabled", audioEnabled).apply();
                if (!CaptureService.isRunning()) {
                    setStatus(idleStatus());
                }
                Toast.makeText(CastActivity.this,
                        audioEnabled ? "下次投屏会带上声音" : "下次投屏不投声音",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        setupImmersive();
        setupQuality();

        setupPresets();
        setupCodecs();
        restore();

        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (CaptureService.isRunning()) {
                    stopCasting();
                } else {
                    startCasting();
                }
            }
        });

        android.widget.AdapterView.OnItemSelectedListener liveUpdate =
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                               int position, long id) {
                        pushUpdateIfRunning();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                };
        presetSpinner.setOnItemSelectedListener(liveUpdate);
        resolutionSpinner.setOnItemSelectedListener(liveUpdate);
        fpsSpinner.setOnItemSelectedListener(liveUpdate);
        bitrateSpinner.setOnItemSelectedListener(liveUpdate);

        // 视图都就绪后再处理「从暂停通知进入」：此时 setStatus 才安全
        maybeHandleResumeIntent();
    }

    /**
     * 点「投屏已暂停」通知进来时要重新申请录屏权限。
     *
     * 必须同时覆盖 onCreate 与 onNewIntent：通知用的是 SINGLE_TOP，
     * 实例通常already存在，只会走 onNewIntent（之前漏了这条路径，点通知毫无反应）。
     */
    private void maybeHandleResumeIntent() {
        if (!getIntent().getBooleanExtra("resume", false) || !CaptureService.isRunning()) {
            return;
        }
        setStatus("投屏已暂停，正在重新申请录屏权限…");
        handler.post(new Runnable() {
            @Override
            public void run() {
                launchProjection();
            }
        });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeHandleResumeIntent();
    }

    /** 投屏中改参数立刻生效；没在投屏时什么都不做（setSelection 也会触发回调）。 */
    private void pushUpdateIfRunning() {
        if (!CaptureService.isRunning()) {
            return;
        }
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(CaptureService.ACTION_UPDATE);
        intent.putExtra(CaptureService.EXTRA_PRESET,
                Preset.values()[presetSpinner.getSelectedItemPosition()].id);
        intent.putExtra(CaptureService.EXTRA_FPS, selectedFps());
        intent.putExtra(CaptureService.EXTRA_BITRATE, selectedBitrate());
        intent.putExtra(CaptureService.EXTRA_MAX_LONG_SIDE, effectiveLongSide());
        startService(intent);
        persist();
        setStatus("参数已更新");
    }

    private int selectedFps() {
        int wanted = FPS_VALUES[Math.max(0, fpsSpinner.getSelectedItemPosition())];
        if (wanted == 0) {
            int[] screen = CaptureGeometry.screenSize((android.view.WindowManager)
                    getSystemService(WINDOW_SERVICE));
            int[] size = CaptureGeometry.computeSize(screen[0], screen[1], effectiveLongSide());
            wanted = Math.min(deviceMaxFps, EncoderCaps.maxFps(size[0], size[1]));
        }
        return Math.min(wanted, plan.fps);
    }

    private int selectedBitrate() {
        int mbps = BITRATE_MBPS[Math.max(0, bitrateSpinner.getSelectedItemPosition())];
        return mbps == 0
                ? Preset.values()[presetSpinner.getSelectedItemPosition()].bitrateBps
                : mbps * 1_000_000;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButton();
        handler.post(watchService);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(watchService);
        super.onPause();
    }

    /**
     * 持续盯住服务状态。
     *
     * 服务可能因为电视拒绝、网络断开、系统回收而消失，界面不能一直显示「停止投屏」——
     * 那会让用户以为还在投，实际早就断了。这里每 500ms 校正一次按钮与状态文案。
     */
    private final Runnable watchService = new Runnable() {
        @Override
        public void run() {
            boolean running = CaptureService.isRunning();
            // 用「连接是否真的建立」而不是 running 判断展示阶段：
            // running 在 onStartCommand 一开始就为 true，早于 TCP 握手完成
            boolean connected = CaptureService.isConnected();
            boolean paused = CaptureService.isPaused();
            boolean casting = connected && !paused;

            toggleButton.setText(running ? R.string.stop : R.string.start);
            toggleButton.setBackgroundResource(
                    running ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
            // 投屏期间保持屏幕常亮：小米在锁屏时会直接收回 MediaProjection，
            // 一旦自动息屏投屏就必然中断，所以从源头避免它自己锁屏。
            if (running) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            // 只有真正在投屏时才隐藏「电视/搜索电视」卡片；连接中、暂停时保留
            if (deviceCardView != null) {
                deviceCardView.setVisibility(casting ? View.GONE : View.VISIBLE);
            }

            String error = CaptureService.lastError();
            if (error != null) {
                // 错误既可能发生在结束时（断线），也可能发生在运行中（暂停/恢复失败）
                setErrorStatus(error);
                CaptureService.clearError();
            } else if (casting) {
                if (!wasCasting) {
                    setStatus("正在投屏到 " + hostLabel());
                }
            } else if (running) {
                setStatus(paused ? "投屏已暂停，等待重新授权" : "正在连接电视…");
            } else if (wasCasting) {
                setStatus(idleStatus());
            }

            wasCasting = casting;
            handler.postDelayed(this, 500);
        }
    };

    /**
     * 沉浸式状态栏：内容画到状态栏下面，再用系统栏 inset 给内容留出安全距离。
     *
     * 不用「隐藏状态栏」而是「透明 + 留白」：投屏过程中用户要看时间/电量，
     * 全隐反而难受；同时避免内容顶到刘海或状态栏图标上。
     */
    private void setupImmersive() {
        android.view.Window window = getWindow();
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        final View root = findViewById(R.id.root);
        final int baseStart = dp(20);
        final int baseTop = dp(16);
        final int baseEnd = dp(20);
        final int baseBottom = dp(28);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int top;
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars =
                            insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(baseStart, baseTop + top, baseEnd, baseBottom + bottom);
                return insets;
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private <T> ArrayAdapter<T> styledAdapter(List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, items);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        return adapter;
    }

    private void setupQuality() {
        List<String> fpsLabels = new ArrayList<>();
        fpsLabels.add("30 fps（推荐）");
        fpsLabels.add("60 fps");
        fpsLabels.add("不限（设备上限）");
        fpsSpinner.setAdapter(styledAdapter(fpsLabels));

        List<String> bitrateLabels = new ArrayList<>();
        bitrateLabels.add("默认（8 Mbps）");
        for (int i = 1; i < BITRATE_MBPS.length; i++) {
            bitrateLabels.add(BITRATE_MBPS[i] + " Mbps");
        }
        bitrateSpinner.setAdapter(styledAdapter(bitrateLabels));

        List<String> resolutionLabels = new ArrayList<>();
        resolutionLabels.add("自动（1080p 上限）");
        resolutionLabels.add("1080p");
        resolutionLabels.add("720p");
        resolutionLabels.add("540p");
        resolutionSpinner.setAdapter(styledAdapter(resolutionLabels));
    }

    private void setupPresets() {        List<String> labels = new ArrayList<>();
        for (Preset p : Preset.values()) {
            labels.add(p.label);
        }
        presetSpinner.setAdapter(styledAdapter(labels));
        // 默认「稳定性优先」：实测这台电视在 60fps/高码率下会丢帧
        presetSpinner.setSelection(2);

        if (!SHOW_PRESET_SELECTOR) {
            View label = findViewById(R.id.presetLabel);
            if (label != null) {
                label.setVisibility(View.GONE);
            }
            presetSpinner.setVisibility(View.GONE);
        }
    }

    private void setupCodecs() {
        int[] screen = CaptureGeometry.screenSize((android.view.WindowManager)
                getSystemService(WINDOW_SERVICE));
        nativeLongSide = Math.max(screen[0], screen[1]);
        int[] nativeCapture = CaptureGeometry.computeSize(screen[0], screen[1], nativeLongSide);
        float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        deviceMaxFps = Math.max(24, Math.min(120, Math.round(hz)));

        // 「自动」= 原生分辨率；编码器支持不了再退回 1080p 长边
        plan = EncoderCaps.detect(nativeCapture[0], nativeCapture[1], deviceMaxFps);
        nativeSupported = plan.usable();
        if (!nativeSupported) {
            int[] fallback = CaptureGeometry.computeSize(screen[0], screen[1], 1920);
            plan = EncoderCaps.detect(fallback[0], fallback[1], deviceMaxFps);
        }

        codecIds = EncoderCaps.allCodecs();
        codecEnabled = new boolean[codecIds.length];
        List<String> labels = new ArrayList<>();

        for (int i = 0; i < codecIds.length; i++) {
            String id = codecIds[i];
            boolean ok = plan.codecs.contains(id);
            codecEnabled[i] = ok;
            labels.add(displayName(id) + (ok ? "" : "（" + EncoderCaps.reasonFor(id) + "）"));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.item_spinner, labels) {
            @Override
            public boolean isEnabled(int position) {
                return codecEnabled[position];
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                v.setAlpha(codecEnabled[position] ? 1f : 0.35f);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setAlpha(codecEnabled[position] ? 1f : 0.35f);
                return v;
            }
        };
        codecSpinner.setAdapter(adapter);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        codecSpinner.setSelection(0);

        if (!SHOW_CODEC_SELECTOR) {
            View label = findViewById(R.id.codecLabel);
            if (label != null) {
                label.setVisibility(View.GONE);
            }
            codecSpinner.setVisibility(View.GONE);
        }

        if (!plan.usable()) {
            toggleButton.setEnabled(false);
            setStatus("本机没有可用的硬件编码器，无法投屏");
        }
    }

    private static String displayName(String codec) {
        switch (codec) {
            case Handshake.CODEC_AVC:
                return "H.264（兼容最好）";
            case Handshake.CODEC_HEVC:
                return "H.265（同码率更清晰）";
            case Handshake.CODEC_AV1:
                return "AV1";
            case Handshake.CODEC_VP9:
                return "VP9";
            default:
                return codec;
        }
    }

    private void scan() {
        scanButton.setEnabled(false);
        scanButton.setAlpha(0.45f);
        setStatus("正在搜索电视…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Discovery.Tv> found = Discovery.scan(1600);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        scanButton.setEnabled(true);
                        scanButton.setAlpha(1f);
                        if (found.isEmpty()) {
                            deviceView.setText("没找到电视");
                            setStatus("没找到电视，请确认手机和电视连同一个 Wi-Fi");
                            return;
                        }
                        selected = found.get(0);
                        deviceView.setText(selected.name + "\n" + selected.host);
                        ipInput.setText(selected.host);
                        setStatus("已找到：" + selected.name);
                    }
                });
            }
        }, "tvmirror-scan").start();
    }

    private void startCasting() {
        String host = ipInput.getText().toString().trim();
        if (host.isEmpty() && selected != null) {
            host = selected.host;
        }
        if (host.isEmpty()) {
            setStatus("请先搜索电视，或填写电视 IP");
            return;
        }
        String pin = pinInput.getText().toString().trim();
        if (pin.length() != 4) {
            setStatus("请输入电视上显示的 4 位配对码");
            return;
        }

        targetHost = host;
        targetPin = pin;
        persist();

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        launchProjection();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            // 拒绝录音权限也继续：只是没有声音，画面照投
            launchProjection();
        }
    }

    private void launchProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // Android 14 默认让用户「选一个 App」共享；投屏要的是整个桌面，
        // 所以必须用 createConfigForDefaultDisplay() 明确声明整屏，否则会弹应用选择器。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityForResult(
                    manager.createScreenCaptureIntent(
                            android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()),
                    REQ_PROJECTION);
        } else {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            setStatus("需要录屏权限才能开始投屏");
            return;
        }

        // 会话还在（只是录屏权限被收回）→ 只恢复采集，不重新连接
        if (CaptureService.isRunning()) {
            Intent resume = new Intent(this, CaptureService.class);
            resume.setAction(CaptureService.ACTION_RESUME);
            resume.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            resume.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(resume);
            setStatus("正在恢复投屏…");
            return;
        }

        int codecIndex = codecSpinner.getSelectedItemPosition();
        if (codecIndex < 0 || codecIndex >= codecIds.length || !codecEnabled[codecIndex]) {
            setStatus("请选择一个本机支持的编码");
            Toast.makeText(this, "该编码在本机不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        // 选择器隐藏时固定用 H.264
        String chosenCodec = SHOW_CODEC_SELECTOR ? codecIds[codecIndex] : Handshake.CODEC_AVC;

        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(CaptureService.ACTION_START);
        intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        intent.putExtra(CaptureService.EXTRA_HOST, targetHost);
        intent.putExtra(CaptureService.EXTRA_PORT, 27183);
        intent.putExtra(CaptureService.EXTRA_PIN, targetPin);
        intent.putExtra(CaptureService.EXTRA_PRESET,
                Preset.values()[presetSpinner.getSelectedItemPosition()].id);
        intent.putExtra(CaptureService.EXTRA_CODEC, chosenCodec);
        intent.putExtra(CaptureService.EXTRA_AUDIO, audioEnabled);
        int longSide = effectiveLongSide();
        int effectiveFps = selectedFps();
        if (Handshake.CODEC_HEVC.equals(chosenCodec)) {
            // 这台电视的 HEVC 解码器在 1080p 运动中吃不下：限到 720p30 才能既稳又不卡
            longSide = Math.min(longSide, 1280);
            effectiveFps = Math.min(effectiveFps, 30);
            setStatus("H.265 已限制为 720p 30fps（该电视 1080p 解码吃力）");
        }
        intent.putExtra(CaptureService.EXTRA_MAX_LONG_SIDE, longSide);
        intent.putExtra(CaptureService.EXTRA_FPS, effectiveFps);
        intent.putExtra(CaptureService.EXTRA_BITRATE, selectedBitrate());
        // 缓冲是握手参数，只能在建立会话时生效，投屏中改不了
        intent.putExtra(CaptureService.EXTRA_BUFFER_MS, bufferMs);
        startForegroundService(intent);

        setStatus("正在连接电视…");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshButton();
            }
        }, 600);
    }

    private void stopCasting() {
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(CaptureService.ACTION_STOP);
        startService(intent);
        setStatus("已停止投屏");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshButton();
            }
        }, 400);
    }

    private void refreshButton() {
        boolean running = CaptureService.isRunning();
        toggleButton.setText(running ? R.string.stop : R.string.start);
        toggleButton.setBackgroundResource(
                running ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
    }

    /** 状态栏里显示的电视地址：字段没回填时退回输入框内容，避免出现「正在投屏到 电视」。 */
    private String hostLabel() {
        String host = targetHost;
        if (host == null || host.isEmpty()) {
            host = ipInput == null ? null : ipInput.getText().toString().trim();
        }
        return host == null || host.isEmpty() ? "电视" : host;
    }

    /** 空闲态文案：把「当前是否投声音」明确写出来，否则这个开关完全不可见。 */
    private String idleStatus() {
        return audioEnabled ? "准备就绪" : "准备就绪（当前不投声音）";
    }

    private void setStatus(String text) {
        statusView.setText(text);
        statusView.setTextColor(getColor(R.color.accent));
    }

    /** 失败信息用红色，和正常状态区分开。 */
    private void setErrorStatus(String text) {
        statusView.setText(text);
        statusView.setTextColor(getColor(R.color.danger));
    }

    private int effectiveLongSide() {
        int index = Math.max(0, resolutionSpinner.getSelectedItemPosition());
        int chosen = RESOLUTION_LONG_SIDE[Math.min(index, RESOLUTION_LONG_SIDE.length - 1)];
        if (chosen != 0) {
            return chosen;
        }
        // 「自动」= 原生分辨率，但同时受两道上限约束：
        // ① AUTO_NATIVE_CAP：这台电视的 OMX.MS.AVC.Decoder 声称支持 4096x2304，
        //    实测喂 2880x1800 会直接报 ERROR(0x90000001)，所以停在 1080p 长边以内；
        // ② plan.maxLongSide：编码器探测出的真实可用长边（不支持 1080p 的机型会降到 720p/540p）。
        return Math.min(Math.min(nativeLongSide, AUTO_NATIVE_CAP), plan.maxLongSide);
    }

    /** 自动档允许的最大长边。 */
    private static final int AUTO_NATIVE_CAP = 1920;

    private void restore() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        targetPin = sp.getString("pin", "");
        targetHost = sp.getString("host", "");
        pinInput.setText(targetPin);
        ipInput.setText(targetHost);
        presetSpinner.setSelection(Math.max(0, sp.getInt("preset", 2)));
        // 存档记录的是「值」而不是下标：选项增删时下标含义会变，值不会
        fpsSpinner.setSelection(indexOf(FPS_VALUES, sp.getInt("fpsValue", 30)));
        bitrateSpinner.setSelection(indexOf(BITRATE_MBPS, sp.getInt("bitrateMbps", 0)));
        bufferMs = sp.getInt("bufferMs", 0);
        audioEnabled = sp.getBoolean("audioEnabled", true);
        resolutionSpinner.setSelection(
                indexOf(RESOLUTION_LONG_SIDE, sp.getInt("resolutionLong", 0)));
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return 0;
    }

    private void persist() {
        // 必须兜住 null：Activity 重建后这些字段可能还是空的，
        // 直接写进去会把存档里的地址/配对码清掉
        String host = targetHost;
        if (host == null) {
            host = ipInput == null ? "" : ipInput.getText().toString().trim();
        }
        String pin = targetPin;
        if (pin == null) {
            pin = pinInput == null ? "" : pinInput.getText().toString().trim();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pin", pin)
                .putString("host", host)
                .putInt("preset", presetSpinner.getSelectedItemPosition())
                .putInt("fpsValue", FPS_VALUES[Math.max(0, fpsSpinner.getSelectedItemPosition())])
                .putInt("bitrateMbps", BITRATE_MBPS[Math.max(0,
                        bitrateSpinner.getSelectedItemPosition())])
                .putInt("resolutionLong", RESOLUTION_LONG_SIDE[Math.max(0,
                        resolutionSpinner.getSelectedItemPosition())])
                .putInt("codec", codecSpinner.getSelectedItemPosition())
                .apply();
    }
}
