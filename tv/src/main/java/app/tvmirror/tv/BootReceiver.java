package app.tvmirror.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启：只负责把常驻服务拉起来。
 *
 * 不在开机时直接拉起界面：Android 10+ 对后台启动 Activity 有严格限制，
 * 开机阶段强行拉起大概率被拦；服务起来后，手机一连，服务再唤起界面即可。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Intent service = new Intent(context, ReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException e) {
            Log.w("TVMirror", "开机启动服务失败: " + e.getMessage());
        }
    }
}
