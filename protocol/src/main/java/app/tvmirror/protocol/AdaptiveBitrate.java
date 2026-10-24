package app.tvmirror.protocol;

/**
 * 自适应码率状态机。
 *
 * 纯逻辑、时间由调用方注入，所以能在 JVM 上单测，不需要真机。
 * 三档预设的阈值不同：延迟优先最敏感（宁可糊也要跟手），画质优先只在真的丢帧时退让，
 * 稳定性优先容忍更高的 RTT，避免在弱网下反复升降造成画质抖动。
 */
public final class AdaptiveBitrate {

    public enum State {
        STEADY, CONGESTED, RECOVER
    }

    public static final int FLOOR_KBPS = 4000;

    /** 连续多少个采样周期拥塞才真正降码率——单次抖动不反应，避免误判。 */
    private static final int BAD_STREAK_TRIGGER = 3;

    private final Preset preset;
    private final int ceilingKbps;
    private final int floorKbps;
    private int currentKbps;
    private State state = State.STEADY;
    private int badStreak;
    private long goodMs;
    private long lastSampleMs = -1;

    public AdaptiveBitrate(Preset preset, int initialKbps) {
        if (preset == null) {
            throw new IllegalArgumentException("preset 不能为 null");
        }
        this.preset = preset;
        this.ceilingKbps = Math.max(1, initialKbps);
        // 用户可能把码率上限设得比默认下限还低（比如 1 Mbps），此时下限要跟着让位
        this.floorKbps = Math.min(FLOOR_KBPS, this.ceilingKbps);
        this.currentKbps = this.ceilingKbps;
    }

    public int currentKbps() {
        return currentKbps;
    }

    public int ceilingKbps() {
        return ceilingKbps;
    }

    public State state() {
        return state;
    }

    /**
     * 喂一个采样点，返回本条策略下的目标码率（kbps）。
     *
     * @param rttMs        往返时延
     * @param queueBytes   发送队列积压字节数
     * @param droppedRatio 接收端报告的丢帧比例（0~1）
     * @param nowMs        单调时钟毫秒
     */
    public int onSample(int rttMs, int queueBytes, float droppedRatio, long nowMs) {
        if (lastSampleMs < 0) {
            lastSampleMs = nowMs;
            return currentKbps;
        }
        long delta = nowMs - lastSampleMs;
        if (delta < 0) {
            // 时钟回拨时按 0 处理，不能让它把恢复计时倒扣
            delta = 0;
        }
        if (delta > 1000) {
            // 采样间隔过大（比如线程被挂起）时封顶，防止一次跳过整个恢复窗口
            delta = 1000;
        }
        lastSampleMs = nowMs;

        if (isCongested(rttMs, queueBytes, droppedRatio)) {
            badStreak++;
            goodMs = 0;
            if (badStreak >= BAD_STREAK_TRIGGER) {
                int next = (int) (currentKbps * decreaseFactor());
                currentKbps = Math.max(floorKbps, Math.min(ceilingKbps, next));
                state = State.CONGESTED;
                badStreak = 0;
            }
            return currentKbps;
        }

        badStreak = 0;
        if (state == State.CONGESTED) {
            // 拥塞解除但还没到恢复窗口，先把状态摘掉，避免统计界面上一直显示拥塞
            state = State.STEADY;
        }
        goodMs += delta;
        if (goodMs >= recoverWindowMs()) {
            goodMs = 0;
            // 每次 +20%，从地板爬回上限只需几次
            int step = Math.max(1, currentKbps / 5);
            currentKbps = Math.min(ceilingKbps, currentKbps + step);
            state = State.RECOVER;
        }
        return currentKbps;
    }

    private boolean isCongested(int rttMs, int queueBytes, float droppedRatio) {
        switch (preset) {
            case LATENCY:
                return rttMs > 50 || queueBytes > 256 * 1024;
            case QUALITY:
                return droppedRatio > 0.01f;
            case STABLE:
                // 局域网上 RTT 很少真的高，主要看解码端是否丢帧；阈值放宽避免误判
                return rttMs > 150 || queueBytes > 1024 * 1024 || droppedRatio > 0.02f;
            default:
                return false;
        }
    }

    private double decreaseFactor() {
        switch (preset) {
            case LATENCY:
                return 0.70;
            case QUALITY:
                return 0.85;
            case STABLE:
                return 0.85;
            default:
                return 0.80;
        }
    }

    private long recoverWindowMs() {
        switch (preset) {
            case LATENCY:
                return 10_000;
            case QUALITY:
                return 20_000;
            case STABLE:
                // 恢复要快：LAN 上带宽充足，慢爬会让画面长时间停留在低码率
                return 5000;
            default:
                return 10_000;
        }
    }
}
