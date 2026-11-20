package app.jarvis.urlapp;

// 워치 → 폰 메시지. 폰의 앱이 알리는 능력(urlapp_phone)이 있는 기기에만 보낸다.

import android.content.Context;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;

final class WatchPhone {
    static final String CAPABILITY = "urlapp_phone";

    interface Result {
        void done(String error); // null = 보냄
    }

    private WatchPhone() {
    }

    static void send(Context ctx, String path, String json, Result result) {
        Context app = ctx.getApplicationContext();
        byte[] data = (json == null ? "" : json).getBytes(StandardCharsets.UTF_8);
        Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener((CapabilityInfo info) -> {
                    Node best = null;
                    for (Node n : info.getNodes()) if (best == null || n.isNearby()) best = n;
                    if (best == null) {
                        noPhone(app, result);
                        return;
                    }
                    Wearable.getMessageClient(app).sendMessage(best.getId(), path, data)
                            .addOnSuccessListener(x -> result.done(null))
                            .addOnFailureListener(e -> result.done("폰에 보내지 못했어요. 폰과의 연결을 확인해 주세요."));
                })
                .addOnFailureListener(e -> result.done("폰을 찾지 못했어요 (" + e.getClass().getSimpleName() + ")."));
    }

    /** 능력이 있는 폰이 없을 때: 폰과 연결은 됐는데 앱이 없는지, 아예 연결이 없는지 구분해 알린다. */
    private static void noPhone(Context app, Result result) {
        Wearable.getNodeClient(app).getConnectedNodes()
                .addOnSuccessListener(nodes -> result.done(nodes.isEmpty()
                        ? "폰과 연결되어 있지 않아요."
                        : "폰의 앱이 워치 기능이 없는 버전이에요. 폰에서 앱을 업데이트해 주세요."))
                .addOnFailureListener(e -> result.done("폰과 연결되어 있지 않아요."));
    }

    static void send(Context ctx, String path, String json) {
        send(ctx, path, json, err -> {
        });
    }
}
