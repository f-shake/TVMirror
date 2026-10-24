package app.tvmirror.phone;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

import app.tvmirror.protocol.CodecMime;

/**
 * 屏幕采集 + 硬编码。
 *
 * 用 Surface 输入而不是 YUV 输入：VirtualDisplay 直接往编码器的输入 Surface 合成，
 * 中间没有一次 CPU 拷贝，这是低延迟和低功耗的关键。
 *
 * 旋转屏时必须复用同一个 VirtualDisplay：Android 14 起一个 MediaProjection 只允许
 * createVirtualDisplay 一次，重建会直接抛异常并把整个投影终止
 * （现象就是「打开相机 / 转屏后投屏自动结束」）。所以重配走 setSurface + resize。
 */
public final class ScreenEncoder implements Runnable {

    public interface Sink {
        void onConfig(byte[] data, long ptsUs);

        void onVideo(byte[] data, long ptsUs);
    }

    private final MediaProjection projection;
    private final Sink sink;
    private final String codec;
    private int fps;
    private int bitrate;
    private int iFrameSeconds;
    private final boolean vbr;
    /**
     * 是否尝试 CQ（恒定质量）。
     *
     * 实测这台平板的 c2.qti.avc.encoder 接受 CQ 但质量旋钮基本无效：
     * 码率仅从 1.0 升到 1.7 Mbps，帧率却失控到 100fps 并开始丢帧，因此关闭。
     */
    private static final boolean USE_CQ = false;
    private static final int CQ_QUALITY = 85;
    /** 用 AVC High profile 提升同码率下的画质。 */
    private static final boolean USE_HIGH_PROFILE = true;
    /**
     * 是否用 GL 节拍器限制出帧率。
     *
     * VirtualDisplay 会按面板刷新率（144Hz）推帧，而 KEY_MAX_FPS_TO_ENCODER 与
     * Surface.setFrameRate 都管不住它。帧率过高会把码率预算摊薄，画面变糊，
     * 所以中间插一层 GL 做帧率整形。失败时自动退回直连，不影响可用性。
     */
    private static final boolean USE_GL_PACING = true;

    private MediaCodec codecObject;
    private Surface inputSurface;
    private GlFramePacer pacer;
    private VirtualDisplay display;
    private Thread thread;
    private volatile boolean running;
    /** 最近一次产出编码帧的时刻（毫秒），用于判断画面是否已经静止。 */
    private volatile long lastOutputMs;
    /** 本次静止期是否已经补过关键帧，避免静止时反复刷 IDR。 */
    private volatile boolean refreshedWhileIdle;

    public ScreenEncoder(MediaProjection projection, Sink sink, String codec,
                         int fps, int bitrate, int iFrameSeconds, boolean vbr) {
        this.projection = projection;
        this.sink = sink;
        this.codec = codec;
        this.fps = fps;
        this.bitrate = bitrate;
        this.iFrameSeconds = iFrameSeconds;
        this.vbr = vbr;
    }

    /** 运行中更新参数；分辨率/帧率变化需要 restart()，仅码率变化用 setBitrate()。 */
    public void update(int fps, int bitrate, int iFrameSeconds) {
        this.fps = fps;
        this.bitrate = bitrate;
        this.iFrameSeconds = iFrameSeconds;
    }

    public int fps() {
        return fps;
    }

    public int bitrate() {
        return bitrate;
    }

    public void start(int width, int height, int dpi) throws IOException {
        synchronized (this) {
            if (running) {
                return;
            }
            codecObject = createCodec(width, height);
            Surface displaySurface = setupPacer(width, height);
            if (display == null) {
                display = projection.createVirtualDisplay("TVMirror",
                        width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        displaySurface, null, null);
            } else {
                // 复用同一个 VirtualDisplay，避免触发「一个投影只能创建一次」的限制
                display.setSurface(displaySurface);
                display.resize(width, height, dpi);
            }
            applyFrameRate();
            running = true;
            thread = new Thread(this, "tvmirror-encode");
            thread.start();
        }
    }

    /** 建立 GL 节拍器并返回应当给 VirtualDisplay 用的 Surface。失败则退回直连。 */
    private Surface setupPacer(int width, int height) {
        if (!USE_GL_PACING) {
            return inputSurface;
        }
        if (pacer != null) {
            pacer.release();
            pacer = null;
        }
        try {
            pacer = GlFramePacer.create(inputSurface, width, height, fps);
            return pacer.producerSurface();
        } catch (Exception e) {
            android.util.Log.w("TVMirror", "GL 节拍器不可用，退回直连: " + e.getMessage());
            pacer = null;
            return inputSurface;
        }
    }

    /** 旋转屏后按新尺寸重建编码器；VirtualDisplay 始终复用。 */
    public void restart(int width, int height, int dpi) throws IOException {
        synchronized (this) {
            running = false;
            Thread old = thread;
            thread = null;
            if (old != null) {
                old.interrupt();
                try {
                    old.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            MediaCodec previous = codecObject;
            codecObject = null;
            if (previous != null) {
                try {
                    previous.stop();
                } catch (RuntimeException ignored) {
                    // 已经停机
                }
                try {
                    previous.release();
                } catch (RuntimeException ignored) {
                    // 已经释放
                }
            }

            codecObject = createCodec(width, height);
            Surface displaySurface = setupPacer(width, height);
            if (display != null) {
                display.setSurface(displaySurface);
                display.resize(width, height, dpi);
            }
            applyFrameRate();
            running = true;
            thread = new Thread(this, "tvmirror-encode");
            thread.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (display != null) {
                display.release();
                display = null;
            }
            // 先拆节拍器再释放编码器 Surface：它持有 EGLSurface，顺序反了会报 EGL 错误
            if (pacer != null) {
                pacer.release();
                pacer = null;
            }
            if (codecObject != null) {
                try {
                    codecObject.stop();
                } catch (RuntimeException ignored) {
                    // 已处于错误状态时 stop 也会抛，忽略
                }
                try {
                    codecObject.release();
                } catch (RuntimeException ignored) {
                    // 同上
                }
                codecObject = null;
            }
        }
    }

    /**
     * 画面静止后补一个关键帧。
     *
     * 编码器在画面不动时会停止出帧，最后一帧的模糊程度就被定格了——
     * 从复杂画面切到静态画面时尤其明显。这里在静止 400ms 后主动要一个 IDR，
     * 用完整码率重新编码一帧，画面就能「逐渐变清晰」。
     */
    private void maybeRefreshStaticFrame() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (refreshedWhileIdle || lastOutputMs == 0) {
            return;
        }
        if (now - lastOutputMs < 400) {
            return;
        }
        refreshedWhileIdle = true;
        requestKeyFrame();
    }

    private void applyFrameRate() {
        if (pacer != null) {
            // 有节拍器时由它真正控制帧率，Surface 上的帧率提示已无意义
            pacer.setFrameRate(fps);
            return;
        }
        if (inputSurface == null) {
            return;
        }
        try {
            // VirtualDisplay 在 API 34 上已没有公开的设帧率接口，改由输入 Surface 声明期望帧率。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // FIXED_SOURCE 表示「内容就是固定这个帧率」，比 DEFAULT 更强硬，
                // 允许合成器按该帧率节流，而不是按面板刷新率（144Hz）铺满。
                inputSurface.setFrameRate((float) fps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
        } catch (RuntimeException ignored) {
            // 个别固件不支持该接口，退化为按屏幕刷新率出帧
        }
    }

    private MediaCodec createCodec(int width, int height) throws IOException {
        String mime = CodecMime.mimeOf(codec);
        if (mime == null) {
            throw new IOException("不支持的编码: " + codec);
        }

        // 先乐观地按 CQ 配置：部分高通编码器能力表里不写 CQ，但实际能接受
        if (USE_CQ) {
            MediaCodec cq = null;
            try {
                MediaFormat cqFormat = buildFormat(mime, width, height,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                cqFormat.setInteger(MediaFormat.KEY_QUALITY, CQ_QUALITY);
                cq = MediaCodec.createEncoderByType(mime);
                cq.configure(cqFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                inputSurface = cq.createInputSurface();
                cq.start();
                logConfig(width, height, "CQ(" + CQ_QUALITY + ")");
                return cq;
            } catch (RuntimeException e) {
                if (cq != null) {
                    try {
                        cq.release();
                    } catch (RuntimeException ignored) {
                        // 已经释放
                    }
                }
                android.util.Log.d("TVMirror", "CQ 不被支持，回退: " + e.getMessage());
            }
        }

        MediaFormat format = buildFormat(mime, width, height,
                vbr ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        MediaCodec c = MediaCodec.createEncoderByType(mime);
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = c.createInputSurface();
        c.start();
        logConfig(width, height, vbr ? "VBR" : "CBR");
        return c;
    }

    private MediaFormat buildFormat(String mime, int width, int height, int bitrateMode) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameSeconds);
        format.setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);

        // High profile：同码率下 CABAC + 8x8 变换能省 10–20% 码率，是标准里唯一的画质杠杆。
        // 不指定 Level：它由分辨率决定，写死 4.2 会让 2880x1800 这种尺寸出非法码流，
        // 电视端一收到配置帧就报错（实测 OMX.MS.AVC.Decoder ERROR 0x90000001）。
        if (USE_HIGH_PROFILE && "video/avc".equals(mime)) {
            try {
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            } catch (RuntimeException ignored) {
                // 个别实现不接受显式 profile，保持默认
            }
        }
        // 复杂度提示：越低越快、越高越偏向质量（编码器可忽略）
        try {
            format.setFloat(MediaFormat.KEY_COMPLEXITY, 0.8f);
        } catch (RuntimeException ignored) {
            // 不支持就忽略
        }
        return format;
    }

    private void logConfig(int width, int height, String mode) {
        android.util.Log.d("TVMirror", "编码器已配置: " + width + "x" + height
                + " fps=" + fps + " bitrate=" + bitrate + " mode=" + mode
                + " iFrame=" + iFrameSeconds + "s");
    }

    public void requestKeyFrame() {
        MediaCodec c = codecObject;
        if (c == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (RuntimeException ignored) {
            // 编码器正在重建时可能抛，下一次心跳会再请求
        }
    }

    /** 运行中调码率，不需要重建编码器，所以画面不会中断。 */
    public void setBitrate(int bitsPerSecond) {
        MediaCodec c = codecObject;
        if (c == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond);
            c.setParameters(params);
        } catch (RuntimeException ignored) {
            // 个别编码器不支持动态调码率，保持原码率继续
        }
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec c = codecObject;
            if (c == null) {
                return;
            }
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 10_000);
            } catch (RuntimeException e) {
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                maybeRefreshStaticFrame();
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                if (info.size > 0) {
                    lastOutputMs = android.os.SystemClock.elapsedRealtime();
                    refreshedWhileIdle = false;
                    ByteBuffer buffer = c.getOutputBuffer(index);
                    if (buffer != null) {
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data);

                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (config) {
                            sink.onConfig(data, info.presentationTimeUs);
                        } else {
                            // 原样透传编码器输出（不要转 Annex-B，会花屏）。
                            // 编码器 PTS 用 CLOCK_MONOTONIC，与心跳的 CLOCK_BOOTTIME 差一个固定偏移；
                            // 换算后这个时间戳就是「画面被采集的时刻」，端到端才算得准。
                            long nanoToElapsedUs =
                                    (android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1000;
                            sink.onVideo(data, info.presentationTimeUs + nanoToElapsedUs);
                        }
                    }
                }
            } finally {
                // stop()/restart() 可能在 sink 阻塞期间已经把 codec 释放掉，
                // 这里必须兜住异常：finally 里抛出未捕获异常会直接杀掉进程。
                try {
                    c.releaseOutputBuffer(index, false);
                } catch (RuntimeException ignored) {
                    // codec 已 stop/release，丢掉这个 index 即可
                }
            }
        }
    }
}
