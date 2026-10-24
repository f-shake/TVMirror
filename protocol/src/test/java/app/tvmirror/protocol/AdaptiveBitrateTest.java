package app.tvmirror.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 自适应码率。时间由测试注入，所以「10 秒后恢复」这类规则可以被确定性地验证，
 * 不需要真的等 10 秒。
 */
public class AdaptiveBitrateTest {

    private static final int MB = 1024 * 1024;

    @Test
    public void 初始码率等于上限() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        assertEquals(12000, ab.currentKbps());
        assertEquals(12000, ab.ceilingKbps());
    }

    @Test
    public void 单次拥塞不降码率() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        ab.onSample(200, 2 * MB, 0f, 0);
        ab.onSample(200, 2 * MB, 0f, 1000);
        assertEquals(12000, ab.currentKbps());
    }

    @Test
    public void 延迟优先连续三次拥塞后降三成() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        ab.onSample(200, 2 * MB, 0f, 0);
        ab.onSample(200, 2 * MB, 0f, 1000);
        ab.onSample(200, 2 * MB, 0f, 2000);
        int after = ab.onSample(200, 2 * MB, 0f, 3000);
        assertEquals(8400, after);
        assertEquals(AdaptiveBitrate.State.CONGESTED, ab.state());
    }

    @Test
    public void 码率不会低于下限() {
        // 上限本身低于默认下限时，下限跟随上限，不会反向抬高
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 2000);
        ab.onSample(200, 2 * MB, 0f, 0);
        for (int t = 1; t <= 30; t++) {
            ab.onSample(200, 2 * MB, 0f, t * 1000L);
        }
        assertEquals(2000, ab.currentKbps());
    }

    @Test
    public void 健康十秒后回升两成() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        ab.onSample(10, 0, 0f, 0);
        ab.onSample(10, 0, 0f, 1000);
        ab.onSample(10, 0, 0f, 2000);
        ab.onSample(200, 2 * MB, 0f, 3000);
        ab.onSample(200, 2 * MB, 0f, 4000);
        ab.onSample(200, 2 * MB, 0f, 5000);
        assertEquals(8400, ab.currentKbps());

        int t = 6000;
        int last = 0;
        for (int i = 0; i < 10; i++) {
            last = ab.onSample(10, 0, 0f, t);
            t += 1000;
        }
        assertEquals(10080, last);
        assertEquals(AdaptiveBitrate.State.RECOVER, ab.state());
    }

    @Test
    public void 回升不会超过上限() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        ab.onSample(10, 0, 0f, 0);
        int t = 1000;
        for (int i = 0; i < 200; i++) {
            ab.onSample(10, 0, 0f, t);
            t += 1000;
        }
        assertEquals(12000, ab.currentKbps());
    }

    @Test
    public void 画质优先只看丢帧不看RTT() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.QUALITY, 20000);
        ab.onSample(300, 5 * MB, 0f, 0);
        ab.onSample(300, 5 * MB, 0f, 1000);
        ab.onSample(300, 5 * MB, 0f, 2000);
        assertEquals("RTT 再高也不该降画质优先的码率", 20000, ab.currentKbps());

        ab.onSample(10, 0, 0.02f, 3000);
        ab.onSample(10, 0, 0.02f, 4000);
        ab.onSample(10, 0, 0.02f, 5000);
        assertEquals(17000, ab.currentKbps());
    }

    @Test
    public void 稳定性优先能容忍一百五十毫秒延迟() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.STABLE, 8000);
        ab.onSample(140, 0, 0f, 0);
        ab.onSample(140, 0, 0f, 1000);
        ab.onSample(140, 0, 0f, 2000);
        assertEquals(8000, ab.currentKbps());

        ab.onSample(200, 0, 0f, 3000);
        ab.onSample(200, 0, 0f, 4000);
        ab.onSample(200, 0, 0f, 5000);
        assertEquals(6800, ab.currentKbps());
    }

    @Test
    public void 解码丢帧也会触发降码率() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.STABLE, 8000);
        // 首个采样只用于对齐时钟，连续 3 次超阈值才降
        ab.onSample(10, 0, 0.05f, 0);
        ab.onSample(10, 0, 0.05f, 1000);
        ab.onSample(10, 0, 0.05f, 2000);
        ab.onSample(10, 0, 0.05f, 3000);
        assertEquals(6800, ab.currentKbps());
    }

    @Test
    public void 时钟回拨不会让状态错乱() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        ab.onSample(10, 0, 0f, 5000);
        ab.onSample(10, 0, 0f, 1000);
        assertEquals(12000, ab.currentKbps());
        assertTrue(ab.state() == AdaptiveBitrate.State.STEADY);
    }

    @Test
    public void 上限低于默认下限时不会被抬高() {
        // 用户把码率上限设成 1 Mbps 时，下限必须跟着降到 1 Mbps，而不是硬撑在 2 Mbps
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 1000);
        assertEquals(1000, ab.ceilingKbps());
        ab.onSample(200, 2 * MB, 0f, 0);
        ab.onSample(200, 2 * MB, 0f, 1000);
        ab.onSample(200, 2 * MB, 0f, 2000);
        assertEquals(1000, ab.onSample(200, 2 * MB, 0f, 3000));
    }

    @Test
    public void 极端积压一次采样不会造成巨大跳变() {
        AdaptiveBitrate ab = new AdaptiveBitrate(Preset.LATENCY, 12000);
        // 首个采样点只用于对齐时钟，不参与判断，所以要喂满 4 个点才触发降码率
        ab.onSample(200, Integer.MAX_VALUE, 0f, 0);
        ab.onSample(200, Integer.MAX_VALUE, 0f, 1000);
        ab.onSample(200, Integer.MAX_VALUE, 0f, 2000);
        ab.onSample(200, Integer.MAX_VALUE, 0f, 3000);
        assertEquals(8400, ab.currentKbps());
    }
}
