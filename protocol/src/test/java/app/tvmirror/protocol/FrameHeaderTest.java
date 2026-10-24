package app.tvmirror.protocol;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 帧头编解码。这一层错一个字节，电视端就会解出花屏或直接崩，所以测的是「位级」正确性。
 */
public class FrameHeaderTest {

    private static byte[] payload(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    @Test
    public void 编码后长度字段等于类型加时间戳加负载() {
        byte[] f = FrameHeader.encode(FrameType.VIDEO, 123L, payload(100, 7));
        assertEquals(FrameHeader.PREFIX_BYTES + 9 + 100, f.length);
        assertEquals(9 + 100, FrameHeader.peekBodyLength(f));
    }

    @Test
    public void 往返编解码后字段完全一致() {
        byte[] p = payload(300, 11);
        byte[] f = FrameHeader.encode(FrameType.CONFIG, 987654321L, p);
        FrameHeader back = FrameHeader.parse(f);
        assertEquals(FrameType.CONFIG, back.type);
        assertEquals(987654321L, back.ptsUs);
        assertArrayEquals(p, back.payload);
    }

    @Test
    public void 空负载也能正确往返() {
        byte[] f = FrameHeader.encode(FrameType.PING, 0L, null);
        FrameHeader back = FrameHeader.parse(f);
        assertEquals(FrameType.PING, back.type);
        assertEquals(0, back.payload.length);
    }

    @Test
    public void 大端序字节序正确() {
        // 0x01020304 必须写成 01 02 03 04，否则对端会算出天文数字的帧长
        byte[] f = FrameHeader.encode(FrameType.PING, 0L, new byte[0]);
        assertEquals(9, f[3] & 0xFF);
        assertEquals(0, f[2] & 0xFF);
    }

    @Test
    public void 时间戳跨越大值仍然精确() {
        long pts = 9_000_000_000_000L;
        byte[] f = FrameHeader.encode(FrameType.VIDEO, pts, new byte[]{1});
        assertEquals(pts, FrameHeader.parse(f).ptsUs);
    }

    @Test
    public void 声明长度与实际不符时拒绝() {
        byte[] f = FrameHeader.encode(FrameType.VIDEO, 1L, payload(10, 0));
        byte[] truncated = new byte[f.length - 3];
        System.arraycopy(f, 0, truncated, 0, truncated.length);
        try {
            FrameHeader.parse(truncated);
            fail("应当拒绝截断帧");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("帧长"));
        }
    }

    @Test
    public void 超过上限的帧被拒绝() {
        byte[] huge = new byte[FrameHeader.MAX_BODY_BYTES];
        try {
            FrameHeader.encode(FrameType.VIDEO, 1L, huge);
            fail("应当拒绝超大帧");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("过大"));
        }
    }

    @Test
    public void 非法帧类型被拒绝() {
        try {
            FrameHeader.encode(99, 1L, new byte[0]);
            fail("应当拒绝未知类型");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("非法帧类型"));
        }
    }

    @Test
    public void 负数时间戳被拒绝() {
        try {
            FrameHeader.encode(FrameType.VIDEO, -1L, new byte[0]);
            fail("应当拒绝负时间戳");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不能为负"));
        }
    }

    @Test
    public void 长度前缀不足四字节时拒绝() {
        try {
            FrameHeader.peekBodyLength(new byte[]{1, 2});
            fail("应当拒绝不足 4 字节的前缀");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void 帧类型边界值可用() {
        assertTrue(FrameType.isValid(FrameType.VIDEO));
        assertTrue(FrameType.isValid(FrameType.BYE));
        assertTrue(FrameType.isValid(FrameType.FORMAT));
        assertTrue(FrameType.isValid(FrameType.AUDIO_CONFIG));
        assertTrue(!FrameType.isValid(0));
        assertTrue(!FrameType.isValid(FrameType.AUDIO_CONFIG + 1));
    }
}
