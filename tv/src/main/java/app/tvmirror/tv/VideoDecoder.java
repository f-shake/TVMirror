package app.tvmirror.tv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

import app.tvmirror.protocol.FrameHeader;
import app.tvmirror.protocol.FrameType;

/**
 * 视频解码：把收到的帧原样喂给 MediaCodec，解码结果直接输出到 Surface。
 *
 * 为什么不做 NAL 解析：编码器输出的就是它自己认的封装（AVC 的 AVCC、HEVC 的 HVCC），
 * 只要原样交给同编码的解码器，加上 codec-config 帧，MediaCodec 能自己解析。
 * 这样 AVC/HEVC 共用一条通路，也避免了手写 NAL 解析引入的越界风险。
 */
public final class VideoDecoder implements Runnable {

    public interface Callback {
        void onFirstFrame();

        void onError(String message);

        /** 会话正常结束（对端 BYE 或主动停止），界面应收起画面回到待机页。 */
        void onEnded();

        /** 画面尺寸变化（旋转屏），界面需要重新按比例摆放松 SurfaceView。 */
        void onFormatChanged(int width, int height);
    }

    /** 音频帧的接收方。视频线程顺带把 AUDIO 帧转发出去，避免再开一条 socket 读线程。 */
    public interface AudioSink {
        void submit(long ptsUs, byte[] payload);

        /** AAC 的 AudioSpecificConfig，解码器配置时必需。 */
        void onConfig(byte[] csd);
    }

    private final Surface surface;
    private final Session session;
    private final Callback callback;
    private final String mime;
    private int width;
    private int height;

    private volatile boolean running = true;
    private volatile AudioSink audioSink;
    private Thread thread;
    /** 专职负责按时上屏的线程。 */
    private Thread presentThread;
    /** 保护 pending 队列与 codec 释放动作：解码线程与上屏线程都会碰它。 */
    private final Object presentLock = new Object();
    private MediaCodec codec;
    private boolean firstFrameSeen;
    /** 最近一次把帧送进解码器的时刻，用于统计「送入 → 上屏」的真实耗时。 */
    private long lastFeedUs;
    private long lastSyncRequestMs;
    /**
     * 视频上屏前的固定延迟（微秒）。
     *
     * 音频要经过 AudioTrack 缓冲才能出声，视频却是解完即上屏；不补偿的话声音永远慢半拍。
     * 这里把视频压后同样的时长，让两条链路的呈现时刻对齐。
     */
    private static final long PRESENTATION_DELAY_US = 24_000;
    /** 默认缓冲可容纳的帧数；实际上限按缓冲时长与帧率换算。 */
    private static final int MIN_PENDING_FRAMES = 6;
    /** 已解码但还没到呈现时刻的帧：{bufferIndex, 到期时刻us, 字节数, 手机出帧时刻us}。 */
    private final java.util.ArrayDeque<long[]> pending = new java.util.ArrayDeque<>();
    /** 按送入顺序记录视频帧的手机时间戳，输出时按同序取用。 */
    private final java.util.ArrayDeque<Long> fedPts = new java.util.ArrayDeque<>();

    /** 上一帧排定的呈现时刻（微秒），用来把输出排成等间隔。 */
    private long lastDueUs;
    /** 电视时钟 − 手机时钟（微秒），由心跳校准；用来把采集时刻换算成本地时刻。 */
    private volatile long clockOffsetUs = Long.MIN_VALUE;
    /** 手机在握手时指定的缓冲时长（微秒）；0 表示用默认小缓冲。 */
    private final long bufferDelayUs;

    private long presentationDelayUs() {
        return bufferDelayUs > 0 ? bufferDelayUs : PRESENTATION_DELAY_US;
    }

    /**
     * 待呈现队列的上限：缓冲时长 × 帧率 × 1.5。
     *
     * 1.5 倍是给稳态抖动留的余量——队列平时就停在「延迟 × 帧率」附近，
     * 上限贴着这个数会持续丢最旧的帧、打断参考链（表现为花屏）。
     *
     * 注意：这个队列持有的是**解码后的输出 buffer**，数量受解码器实例限制
     * （通常只有一二十个），所以缓冲时长本身不能设得太大，否则解码器会被我们卡住。
     */
    private int maxPendingFrames() {
        long delayUs = presentationDelayUs();
        int fps = Math.max(24, session.handshake == null ? 60 : session.handshake.fps);
        long buffered = delayUs * fps / 1_000_000L;
        long needed = buffered * 3 / 2;
        return (int) Math.max(MIN_PENDING_FRAMES, Math.min(120, needed));
    }

    public VideoDecoder(Surface surface, Session session, Callback callback) {
        this.surface = surface;
        this.session = session;
        this.callback = callback;
        this.mime = app.tvmirror.protocol.CodecMime.mimeOf(session.handshake.codec);
        this.width = session.handshake.width;
        this.height = session.handshake.height;
        this.bufferDelayUs = (long) session.handshake.bufferMs * 1000L;
    }

    public void setAudioSink(AudioSink sink) {
        this.audioSink = sink;
    }

    public void start() {
        // 上屏必须由独立线程按固定频率驱动。
        // 之前只在「收到新帧」时顺便检查一次到期时刻，检查粒度等于帧间隔（~16.7ms），
        // 该上屏的帧会被推迟到下一帧到达才显示，画面节奏因此忽快忽慢。
        presentThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        synchronized (presentLock) {
                            releaseDueLocked();
                        }
                    } catch (RuntimeException e) {
                        // 上屏线程不能静默死掉：一旦死了，画面节奏就彻底失控且无任何提示
                        android.util.Log.w("TVMirror", "上屏异常: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "tvmirror-present");
        presentThread.start();
        thread = new Thread(this, "tvmirror-decode");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread p = presentThread;
        presentThread = null;
        if (p != null) {
            p.interrupt();
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public void run() {
        if (mime == null) {
            callback.onError("不支持的编码: " + session.handshake.codec);
            return;
        }
        try {
            openCodec();
            loop();
        } catch (IOException e) {
            android.util.Log.e("TVMirror", "解码器创建失败", e);
            callback.onError("无法创建解码器: " + e.getMessage());
        } catch (RuntimeException e) {
            android.util.Log.e("TVMirror", "解码失败", e);
            callback.onError("解码失败: " + e.getMessage());
        } finally {
            releaseCodec();
        }
    }

    private void openCodec() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, surface, null, 0);
        codec.start();
        session.stats.setResolution(width, height);
    }

    /** 手机旋转屏后尺寸会变，解码器必须按新尺寸重建，否则画面会被裁切或拉伸。 */
    private void handleFormat(byte[] payload) {
        try {
            java.util.Map<String, Object> json =
                    app.tvmirror.protocol.Json.parse(new String(payload, "UTF-8"));
            Object w = json.get("width");
            Object h = json.get("height");
            if (!(w instanceof Long) || !(h instanceof Long)) {
                return;
            }
            int newW = ((Long) w).intValue();
            int newH = ((Long) h).intValue();
            if (newW == width && newH == height) {
                return;
            }
            width = newW;
            height = newH;
            // 旧解码器已出队但未释放的 buffer index 在新解码器上无效，
            // 残留的 PTS 也会让端到端统计错位，必须一起清空。
            // 必须持 presentLock：上屏线程正在同一个队列上取帧。
            synchronized (presentLock) {
                pending.clear();
                fedPts.clear();
                releaseCodec();
                openCodec();
            }
            callback.onFormatChanged(width, height);
        } catch (Exception e) {
            // 格式帧损坏时保持原尺寸继续解码，比直接断开体验好
        }
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running && !session.isClosed()) {
            FrameHeader frame;
            try {
                frame = session.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                // 长时间没有任何帧（含每秒一次的 PING）→ 手机可能被系统冻结或已掉线
                if (running) {
                    callback.onError("手机端已失联（超过 2 分钟无任何数据）");
                }
                return;
            } catch (IOException e) {
                if (running) {
                    callback.onError("连接已断开");
                }
                return;
            }

            if (frame.type == FrameType.BYE) {
                callback.onEnded();
                return;
            }
            if (frame.type == FrameType.PING) {
                onPing(frame.ptsUs, frame.payload);
                continue;
            }
            if (frame.type == FrameType.FORMAT) {
                handleFormat(frame.payload);
                continue;
            }
            if (frame.type == FrameType.AUDIO_CONFIG) {
                AudioSink sink = audioSink;
                if (sink != null) {
                    sink.onConfig(frame.payload);
                }
                continue;
            }
            if (frame.type == FrameType.AUDIO) {
                AudioSink sink = audioSink;
                if (sink != null) {
                    sink.submit(frame.ptsUs, frame.payload);
                }
                continue;
            }
            if (frame.type == FrameType.VIDEO || frame.type == FrameType.CONFIG) {
                // 码率必须按实际收到的码流字节统计，解码器输出的 info.size 不是码流大小
                session.stats.onReceived(frame.payload.length);
                int flags = frame.type == FrameType.CONFIG ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                feed(frame.payload, frame.ptsUs, flags);
                drain(info);
            }
        }
    }

    private void onPing(long phoneUs, byte[] pingPayload) {
        long tvNowUs = SystemClock.elapsedRealtimeNanos() / 1000;
        try {
            // 手机把它测到的往返时延带在 PING 里，电视端直接显示
            if (pingPayload != null && pingPayload.length >= 4) {
                session.stats.setRtt(FrameHeader.getInt(pingPayload, 0));
            }
            // 校准时差：手机发 PING 的时刻 → 本地收到时刻，这个差里含单程网络延迟，
            // 减掉 RTT/2 才是真正的时钟差；否则端到端会系统性偏小。
            int phoneRtt = (pingPayload != null && pingPayload.length >= 4)
                    ? FrameHeader.getInt(pingPayload, 0) : 0;
            long offset = tvNowUs - phoneUs - phoneRtt * 500L;
            clockOffsetUs = offset;
            session.stats.setClockOffset(offset);
            int[] delta = session.stats.takeDelta();
            // 回包：8 字节回显 + 4 字节丢帧 + 4 字节解码帧，手机据此判断是否需要降码率
            byte[] payload = new byte[16];
            for (int i = 0; i < 8; i++) {
                payload[i] = (byte) (phoneUs >>> (56 - 8 * i));
            }
            FrameHeader.putInt(payload, 8, delta[0]);
            FrameHeader.putInt(payload, 12, delta[1]);
            session.writeFrame(FrameType.PONG, tvNowUs, payload);
        } catch (IOException ignored) {
            // PING 回包失败不影响解码，下一次心跳会重新校准
        }
    }

    private void feed(byte[] payload, long ptsUs, int flags) {
        lastFeedUs = SystemClock.elapsedRealtimeNanos() / 1000;
        // 只等 50ms：等太久会让 TCP 反压，延迟像滚雪球一样涨上去。
        // 吃不下就丢这一帧并向手机要关键帧，代价是短暂花屏，但延迟不会失控。
        int index;
        try {
            index = codec.dequeueInputBuffer(50_000);
        } catch (RuntimeException e) {
            // 解码器已被释放（对端断开或硬件报错），让循环体干净退出
            android.util.Log.w("TVMirror", "解码器不可用，结束会话: " + e.getMessage());
            running = false;
            return;
        }
        if (index < 0) {
            session.stats.onDropped();
            long now = SystemClock.elapsedRealtime();
            if (now - lastSyncRequestMs > 500) {
                lastSyncRequestMs = now;
                requestSyncFrame();
            }
            return;
        }
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < payload.length) {
            // 缓冲太小或拿不到：用一帧空数据把 index 还回去。
            // 直接 return 会让这个 index 永久丢失，反复发生会耗尽输入缓冲把画面卡死。
            try {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
            } catch (RuntimeException ignored) {
                // 解码器已释放
            }
            android.util.Log.w("TVMirror", "输入缓冲不足，丢弃一帧 size=" + payload.length);
            return;
        }
        buffer.clear();
        buffer.put(payload);
        try {
            codec.queueInputBuffer(index, 0, payload.length, ptsUs, flags);
            if (flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                android.util.Log.d("TVMirror", "已送入配置帧 " + payload.length + " 字节");
            } else {
                // 按送入顺序记录手机出帧时刻，输出时同序取出算端到端。
                // 队列必须有上限：一旦与输出不再一一对应（解码器丢帧/补帧），
                // 它会永久错位且无限增长，端到端与缓冲都会失真。
                fedPts.addLast(ptsUs);
                while (fedPts.size() > 240) {
                    fedPts.pollFirst();
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.e("TVMirror", "送入帧失败 size=" + payload.length + " flags=" + flags, e);
            throw e;
        }
    }

    /** 解码器实在吃不下时，向手机要一个关键帧，让码流从下一个 IDR 重新对齐。 */
    private void requestSyncFrame() {
        try {
            session.writeFrame(FrameType.KEYFRAME_REQ, 0, new byte[0]);
        } catch (IOException ignored) {
            // 对端已断开时会走别的错误分支
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        while (running) {
            // 上屏由 presentThread 负责，这里只负责解码与入队
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (out >= 0) {
                session.stats.onDecoded();
                if (info.size <= 0) {
                    codec.releaseOutputBuffer(out, false);
                    continue;
                }
                // 上屏时刻按「采集时刻 + 缓冲」还原，而不是套一个固定帧率网格。
                // 源视频常常不是目标帧率（例如 24fps 内容、30fps 网格），
                // 套网格会让两套节奏互相踩，看起来就是一顿一顿。
                long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
                Long captureUs = fedPts.pollFirst();
                long dueUs;
                if (captureUs != null && captureUs > 0 && clockOffsetUs != Long.MIN_VALUE) {
                    dueUs = captureUs + clockOffsetUs + presentationDelayUs();
                    // 采集时刻换算过来可能已经过期（首帧、校时重置），别让它立刻扎堆上屏
                    if (dueUs < nowUs) {
                        dueUs = nowUs;
                    }
                } else {
                    dueUs = nowUs + presentationDelayUs();
                    if (lastDueUs != 0) {
                        long intervalUs = 1_000_000L / Math.max(24,
                                session.handshake == null ? 60 : session.handshake.fps);
                        dueUs = Math.max(dueUs, lastDueUs + intervalUs);
                    }
                }
                long maxAheadUs = presentationDelayUs() + 500_000L;
                if (dueUs - nowUs > maxAheadUs) {
                    dueUs = nowUs + presentationDelayUs();
                }
                lastDueUs = dueUs;
                synchronized (presentLock) {
                    pending.addLast(new long[]{out, dueUs, info.size,
                            captureUs == null ? -1 : captureUs});
                    // 缓冲太多说明呈现线程跟不上，放掉最旧的一帧，避免解码器被占满
                    while (pending.size() > maxPendingFrames()) {
                        releaseOne();
                    }
                }
            }
        }
    }

    /** 调用方必须持有 presentLock。 */
    private void releaseDueLocked() {
        long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
        while (!pending.isEmpty()) {
            long[] head = pending.peekFirst();
            if (head == null || head[1] > nowUs) {
                return;
            }
            releaseOne();
        }
    }

    private void releaseOne() {
        long[] item = pending.pollFirst();
        if (item == null || codec == null) {
            return;
        }
        long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
        session.stats.onLate(nowUs - item[1]);
        try {
            codec.releaseOutputBuffer((int) item[0], true);
        } catch (RuntimeException e) {
            return;
        }
        session.stats.onDisplayed();
        session.stats.onFrame((int) item[2], item[3], lastFeedUs, nowUs);
        if (!firstFrameSeen) {
            firstFrameSeen = true;
            android.util.Log.d("TVMirror", "首帧已渲染");
            callback.onFirstFrame();
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // 已经出错时 stop 也可能抛，忽略以免掩盖原始错误
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
                // 同上
            }
            codec = null;
        }
    }
}
