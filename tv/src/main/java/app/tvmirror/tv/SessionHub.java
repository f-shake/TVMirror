package app.tvmirror.tv;

import android.os.Handler;
import android.os.Looper;

/**
 * 服务与界面之间的会话交接点。
 *
 * 为什么用静态持有而不是绑定服务：会话是在服务里建立的，但解码必须画到 Activity 的 Surface 上。
 * 两者生命周期不对齐，用一次性交接（take）比双向绑定简单，也不会因为 Activity 重建而丢会话。
 */
public final class SessionHub {

    public interface Listener {
        void onSession(Session session);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Session pending;
    private static volatile Listener listener;

    private SessionHub() {
    }

    public static void publish(Session session) {
        pending = session;
        final Listener l = listener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Session s = take();
                    if (s != null) {
                        l.onSession(s);
                    }
                }
            });
        }
    }

    public static void setListener(Listener l) {
        listener = l;
        if (l != null) {
            final Session s = take();
            if (s != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onSession(s);
                    }
                });
            }
        }
    }

    public static Session take() {
        Session s = pending;
        pending = null;
        return s;
    }

    public static void clear(Session session) {
        if (pending == session) {
            pending = null;
        }
    }
}
