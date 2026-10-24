package app.tvmirror.phone;

import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * 采集尺寸计算：把屏幕分辨率等比缩到长边不超过上限，并保证宽高是偶数。
 *
 * 抽出来是为了让「能力探测」和「实际采集」用同一套算法——
 * 两边算法不一致时，会出现「探测说支持、真正建编码器时失败」。
 */
public final class CaptureGeometry {

    private CaptureGeometry() {
    }

    public static int[] computeSize(int screenWidth, int screenHeight, int maxLongSide) {
        int longSide = Math.max(screenWidth, screenHeight);
        float scale = longSide > maxLongSide ? (float) maxLongSide / longSide : 1f;
        int w = Math.max(64, Math.round(screenWidth * scale / 2f) * 2);
        int h = Math.max(64, Math.round(screenHeight * scale / 2f) * 2);
        return new int[]{w, h};
    }

    public static int[] screenSize(WindowManager windowManager) {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = windowManager.getDefaultDisplay();
        display.getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels, metrics.densityDpi};
    }
}
