package app.tvmirror.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 编码降级链。这条链决定了「用户选了不支持的编码之后，是报错还是悄悄换一个」，
 * 所以每个分支都要钉死。
 */
public class CodecFallbackTest {

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    @Test
    public void 用户选择的编码可用时排在链首() {
        assertEquals("hevc", CodecFallback.pick("hevc", set("avc", "hevc")));
    }

    @Test
    public void 用户选择的编码不可用时回落到HEVC() {
        List<String> chain = CodecFallback.chain("av1", set("avc", "hevc"));
        assertEquals(Arrays.asList("hevc", "avc"), chain);
    }

    @Test
    public void 只有AVC时只能AVC() {
        assertEquals(Arrays.asList("avc"), CodecFallback.chain("hevc", set("avc")));
    }

    @Test
    public void 什么都不支持时链为空() {
        assertTrue(CodecFallback.chain("hevc", set()).isEmpty());
        assertNull(CodecFallback.pick("avc", set()));
    }

    @Test
    public void 链中不出现重复编码() {
        List<String> chain = CodecFallback.chain("avc", set("avc", "hevc"));
        assertEquals(Arrays.asList("avc", "hevc"), chain);
        assertEquals(chain.size(), new HashSet<>(chain).size());
    }

    @Test
    public void 用户选择为null时直接走默认链() {
        assertEquals(Arrays.asList("hevc", "avc"), CodecFallback.chain(null, set("avc", "hevc")));
    }

    @Test
    public void AV1可用时优先AV1() {
        assertEquals("av1", CodecFallback.pick("av1", set("avc", "hevc", "av1")));
    }
}
