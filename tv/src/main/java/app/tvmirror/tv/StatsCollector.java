package app.tvmirror.tv;

/**
 * 接收端统计：1 秒滑动窗口算帧率与码率，并记录两个不同口径的耗时。
 *
 * 「解码」= 送入解码器 → 上屏，纯本地量，不受跨设备时钟影响。
 * 「端到端」= 手机出帧 → 电视上屏。需要把手机时间戳换算到本地时钟：
 * 时差由心跳估算，并减掉单向网络延迟（RTT/2），否则会系统性偏小约半个 RTT。
 * 静态画面下这个值会偏大——它显示的是最后一帧的年龄，不是链路卡顿。
 */
public final class StatsCollector {

    private long windowStartUs;
    private long framesInWindow;
    private long bytesInWindow;

    private float fps;
    private float kbps;
    private int lastLatencyMs;
    private long totalFrames;
    private long droppedFrames;
    private long lastReportedDrops;
    private long lastReportedFrames;
    private int width;
    private int height;
    private int rttMs;
    /** 电视时钟 − 手机时钟（微秒）；由心跳算出，用于把帧时间戳换算到本地时钟。 */
    private long clockOffsetUs = Long.MIN_VALUE;
    private int endToEndMs;
    private long lateFrames;
    private long receivedFrames;
    private long decodedFrames;
    private long displayedFrames;
    /** 最近一次收到视频帧的本地时刻；用来判断源画面是否已经停了。 */
    private long lastVideoMs;

    public synchronized void setResolution(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 手机端测得的往返时延，随心跳带过来。 */
    public synchronized void setRtt(int rttMs) {
        this.rttMs = rttMs;
    }

    public synchronized void setClockOffset(long offsetUs) {
        this.clockOffsetUs = offsetUs;
    }

    public synchronized void onFrame(int sizeBytes, long captureUs, long arrivalUs, long nowUs) {
        if (windowStartUs == 0) {
            windowStartUs = nowUs;
        }
        framesInWindow++;
        totalFrames++;
        // 注意：sizeBytes 是解码输出缓冲大小，不等于码流字节数，
        // 码率只由 onReceived() 累加的真实帧负载计算。

        long decodeMs = (nowUs - arrivalUs) / 1000;
        if (decodeMs >= 0 && decodeMs < 5000) {
            lastLatencyMs = (int) decodeMs;
        }

        // 端到端：手机出帧时刻（换算到本地时钟）→ 此刻上屏
        if (clockOffsetUs != Long.MIN_VALUE && captureUs > 0) {
            long e2e = (nowUs - (captureUs + clockOffsetUs)) / 1000;
            if (e2e >= 0 && e2e < 5000) {
                endToEndMs = (int) e2e;
            }
        }

        long elapsed = nowUs - windowStartUs;
        if (elapsed >= 1_000_000) {
            fps = framesInWindow * 1_000_000f / elapsed;
            kbps = bytesInWindow * 8f * 1_000_000f / elapsed / 1000f;
            framesInWindow = 0;
            bytesInWindow = 0;
            windowStartUs = nowUs;
        }
    }

    /** 收到一个视频帧时调用，用负载长度统计真实码流码率。 */
    public synchronized void onReceived(int payloadBytes) {
        bytesInWindow += Math.max(0, payloadBytes);
        receivedFrames++;
        lastVideoMs = android.os.SystemClock.elapsedRealtime();
    }

    /** 解码器吐出一帧（无论是否上屏）。 */
    public synchronized void onDecoded() {
        decodedFrames++;
    }

    /** 一帧真正上屏。 */
    public synchronized void onDisplayed() {
        displayedFrames++;
    }

    /**
     * 记录一次「晚于计划时刻上屏」的事件。
     *
     * 卡顿的主观测感往往不是丢帧，而是上屏时刻不均匀：帧该在 A 时刻出现，
     * 实际拖到 A+X 才出现。这个计数就是量化这件事。
     */
    public synchronized void onLate(long lateUs) {
        if (lateUs > 20_000) {
            lateFrames++;
        }
    }

    public synchronized void onDropped() {
        droppedFrames++;
    }

    public synchronized String describe() {
        String resolution = width > 0 ? width + "x" + height : "--";
        return String.format(java.util.Locale.US,
                "分辨率 %s   帧率 %.1f fps   码率 %.1f Mbps   端到端 %d ms   解码 %d ms   网络 %d ms   收 %d   解 %d   显 %d   丢 %d   晚 %d%s",
                resolution, fps, kbps / 1000f, endToEndMs, lastLatencyMs, rttMs,
                receivedFrames, decodedFrames, displayedFrames, droppedFrames, lateFrames,
                stalledHint());
    }

    /** 源画面停了就明确写出来：会话还活着但收不到帧，画面会冻在最后一帧。 */
    private String stalledHint() {
        if (lastVideoMs == 0) {
            return "";
        }
        long idle = android.os.SystemClock.elapsedRealtime() - lastVideoMs;
        if (idle < 5000) {
            return "";
        }
        return "   ⚠ 源画面已停止 " + (idle / 1000) + "s";
    }

    public synchronized float fps() {
        return fps;
    }

    public synchronized int lastLatencyMs() {
        return lastLatencyMs;
    }

    public synchronized long totalFrames() {
        return totalFrames;
    }

    public synchronized long droppedFrames() {
        return droppedFrames;
    }

    /** 自上次调用以来新增的丢帧数与解码帧数，用于回传给手机做自适应。 */
    public synchronized int[] takeDelta() {
        int dropped = (int) (droppedFrames - lastReportedDrops);
        int decoded = (int) (totalFrames - lastReportedFrames);
        lastReportedDrops = droppedFrames;
        lastReportedFrames = totalFrames;
        return new int[]{dropped, decoded};
    }
}
