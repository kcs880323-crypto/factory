package app.jarvis.urlapp;

// WatchLink 구현을 찾아 부른다 (--watch 앱이 아니면 아무것도 하지 않는다). 워치와의 일이 실패해도 앱은 그대로.

import android.content.Context;

final class WatchBridge {
    private static WatchLink link;
    private static boolean tried;

    private WatchBridge() {
    }

    static synchronized WatchLink get() {
        if (!tried) {
            tried = true;
            try {
                link = (WatchLink) Class.forName("app.jarvis.urlapp.GmsWatchLink").newInstance();
            } catch (Throwable t) {
                link = null;
            }
        }
        return link;
    }

    static boolean available() {
        return get() != null;
    }

    static void pushWidget(Context ctx, String json) {
        WatchLink l = get();
        if (l == null) return;
        try {
            l.pushWidget(ctx.getApplicationContext(), json);
        } catch (Throwable t) {
            android.util.Log.w("urlapp", "워치로 위젯 보내기 실패: " + t);
        }
    }

    static void send(Context ctx, String node, String path, String json) {
        WatchLink l = get();
        if (l == null || node == null || node.isEmpty()) return;
        try {
            l.send(ctx.getApplicationContext(), node, path, json);
        } catch (Throwable t) {
            android.util.Log.w("urlapp", "워치로 보내기 실패: " + t);
        }
    }
}
