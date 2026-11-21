package app.jarvis.urlapp;

// 폰 → 워치 (Google Play 서비스 Wearable Data Layer). --watch 앱에만 들어간다.
// 같은 패키지 이름·같은 서명의 워치 앱에만 닿는다 (Wear OS 규칙).

import android.content.Context;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;

public final class GmsWatchLink implements WatchLink {
    public GmsWatchLink() {
    }

    @Override
    public void pushWidget(Context ctx, String json) {
        PutDataMapRequest r = PutDataMapRequest.create("/urlapp/widget");
        r.getDataMap().putString("json", json == null ? "" : json);
        r.getDataMap().putLong("at", System.currentTimeMillis()); // 내용이 같아도 시각이 바뀌어 워치가 새로 받는다
        Wearable.getDataClient(ctx).putDataItem(r.asPutDataRequest().setUrgent());
    }

    @Override
    public void send(Context ctx, String node, String path, String json) {
        Wearable.getMessageClient(ctx).sendMessage(node, path, (json == null ? "" : json).getBytes(StandardCharsets.UTF_8));
    }
}
