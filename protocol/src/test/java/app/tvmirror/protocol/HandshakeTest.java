package app.tvmirror.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 握手解析与校验。重点测「电视端会不会被一份畸形握手带偏」，
 * 所以每条非法输入都断言具体的错误码——错误码直接决定界面文案。
 */
public class HandshakeTest {

    private static Handshake sample() {
        return new Handshake(Handshake.CODEC_HEVC, Preset.QUALITY.id, 1920, 1080, 60,
                20_000_000, true, "1234", "Xiaomi 14");
    }

    private static void assertCode(String expectedCode, String json) {
        try {
            Handshake.parse(json);
            fail("应当被拒绝: " + json);
        } catch (Handshake.ParseException e) {
            assertEquals(expectedCode, e.code);
        }
    }

    @Test
    public void 完整字段往返后完全一致() throws Exception {
        Handshake h = Handshake.parse(sample().toJson());
        assertEquals(Handshake.CODEC_HEVC, h.codec);
        assertEquals(Preset.QUALITY.id, h.preset);
        assertEquals(1920, h.width);
        assertEquals(1080, h.height);
        assertEquals(60, h.fps);
        assertEquals(20_000_000, h.bitrate);
        assertTrue(h.audio);
        assertEquals("1234", h.pin);
        assertEquals("Xiaomi 14", h.name);
    }

    @Test
    public void 缺省字段回落默认值() throws Exception {
        Handshake h = Handshake.parse("{\"v\":1}");
        assertEquals(Handshake.CODEC_AVC, h.codec);
        assertEquals(Preset.LATENCY.id, h.preset);
        assertEquals(1920, h.width);
        assertEquals(1080, h.height);
        assertEquals(60, h.fps);
        assertEquals(12_000_000, h.bitrate);
        assertTrue(!h.audio);
        assertNull(h.pin);
        assertEquals("Android", h.name);
    }

    @Test
    public void 版本不符被拒绝() {
        assertCode("version", "{\"v\":2}");
        assertCode("version", "{\"v\":0}");
        assertCode("version", "{}");
    }

    @Test
    public void 未知编码被拒绝() {
        assertCode("codec", "{\"v\":1,\"codec\":\"mpeg2\"}");
        assertCode("codec", "{\"v\":1,\"codec\":\"\"}");
    }

    @Test
    public void 未知预设被拒绝() {
        assertCode("preset", "{\"v\":1,\"preset\":\"turbo\"}");
    }

    @Test
    public void 非法分辨率被拒绝() {
        assertCode("range", "{\"v\":1,\"width\":1921,\"height\":1080}");
        assertCode("range", "{\"v\":1,\"width\":1920,\"height\":0}");
        assertCode("range", "{\"v\":1,\"width\":4096,\"height\":1080}");
        assertCode("range", "{\"v\":1,\"width\":1920,\"height\":2162}");
    }

    @Test
    public void 非法帧率被拒绝() {
        assertCode("range", "{\"v\":1,\"fps\":10}");
        assertCode("range", "{\"v\":1,\"fps\":121}");
    }

    @Test
    public void 高刷屏的120帧率是合法的() throws Exception {
        Handshake h = Handshake.parse("{\"v\":1,\"fps\":120}");
        assertEquals(120, h.fps);
    }

    @Test
    public void 非法码率被拒绝() {
        assertCode("range", "{\"v\":1,\"bitrate\":100000}");
        assertCode("range", "{\"v\":1,\"bitrate\":50000000}");
    }

    @Test
    public void 配对码必须是四位数字() {
        assertCode("pin", "{\"v\":1,\"pin\":\"12\"}");
        assertCode("pin", "{\"v\":1,\"pin\":\"abcd\"}");
        assertCode("pin", "{\"v\":1,\"pin\":\"12345\"}");
    }

    @Test
    public void 设备名过长被拒绝() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            sb.append('a');
        }
        assertCode("name", "{\"v\":1,\"name\":\"" + sb + "\"}");
    }

    @Test
    public void 非法JSON被拒绝() {
        assertCode("format", "not json");
        assertCode("format", "{\"v\":1,}");
        assertCode("format", "{\"v\":1} trailing");
        assertCode("format", "{v:1}");
    }

    @Test
    public void 字段类型不符被拒绝() {
        assertCode("format", "{\"v\":1,\"width\":\"big\"}");
        assertCode("format", "{\"v\":1,\"audio\":\"yes\"}");
    }

    @Test
    public void 特殊字符与中文能正确转义往返() throws Exception {
        Handshake h = new Handshake(Handshake.CODEC_AVC, Preset.LATENCY.id, 1280, 720, 30,
                8_000_000, false, null, "客厅\"电视\\测试\n换行");
        Handshake back = Handshake.parse(h.toJson());
        assertEquals("客厅\"电视\\测试\n换行", back.name);
    }
}
