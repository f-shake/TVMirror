package app.tvmirror.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 三档预设的参数包。这里锁的是 docs/PLAN.md 2.5 那张表——
 * 参数被人拍过板，改它必须先改计划，否则两端行为会悄悄漂移。
 */
public class PresetTest {

    @Test
    public void 按ID能取回对应预设() {
        assertEquals(Preset.LATENCY, Preset.fromId("latency"));
        assertEquals(Preset.QUALITY, Preset.fromId("quality"));
        assertEquals(Preset.STABLE, Preset.fromId("stable"));
    }

    @Test
    public void 未知ID返回null() {
        assertNull(Preset.fromId("turbo"));
        assertNull(Preset.fromId(""));
        assertNull(Preset.fromId(null));
    }

    @Test
    public void 延迟优先不缓冲且关键帧更密() {
        assertEquals(30, Preset.LATENCY.fps);
        assertEquals(12_000_000, Preset.LATENCY.bitrateBps);
        assertEquals(0, Preset.LATENCY.bufferFrames);
        assertEquals(1, Preset.LATENCY.iFrameSeconds);
        assertEquals(Handshake.CODEC_AVC, Preset.LATENCY.preferredCodec);
    }

    @Test
    public void 画质优先码率最高并留缓冲() {
        assertEquals(16_000_000, Preset.QUALITY.bitrateBps);
        assertEquals(2, Preset.QUALITY.iFrameSeconds);
        assertTrue(Preset.QUALITY.bufferFrames > 0);
        assertEquals(Handshake.CODEC_HEVC, Preset.QUALITY.preferredCodec);
    }

    @Test
    public void 三档都是三十帧基线上限() {
        for (Preset p : Preset.values()) {
            assertEquals(30, p.fps);
        }
    }

    @Test
    public void 稳定性优先降帧率() {
        assertEquals(30, Preset.STABLE.fps);
        assertEquals(8_000_000, Preset.STABLE.bitrateBps);
        assertTrue(Preset.STABLE.bufferFrames >= 2);
    }

    @Test
    public void 三档码率各不相同且都在握手允许区间() {
        assertTrue(Preset.LATENCY.bitrateBps != Preset.QUALITY.bitrateBps);
        assertTrue(Preset.QUALITY.bitrateBps != Preset.STABLE.bitrateBps);
        for (Preset p : Preset.values()) {
            assertTrue(p.bitrateBps >= 2_000_000 && p.bitrateBps <= 25_000_000);
            assertTrue(p.width % 2 == 0 && p.height % 2 == 0);
            assertTrue(p.fps >= 24 && p.fps <= 60);
        }
    }

    @Test
    public void 每档的推荐编码都是已知编码() {
        for (Preset p : Preset.values()) {
            assertNotNull(p.preferredCodec);
            assertTrue(Handshake.isKnownCodec(p.preferredCodec));
        }
    }

    @Test
    public void kbps换算正确() {
        assertEquals(12000, Preset.LATENCY.bitrateKbps());
        assertEquals(16000, Preset.QUALITY.bitrateKbps());
        assertEquals(8000, Preset.STABLE.bitrateKbps());
    }
}
