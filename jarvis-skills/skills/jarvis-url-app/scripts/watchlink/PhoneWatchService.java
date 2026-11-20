package app.jarvis.urlapp;

// 워치가 보낸 메시지를 폰이 받는다 (--watch 앱, Google Play 서비스가 깨운다).
//   /urlapp/ask {id, q}  → '폰이 받았어요' 상태를 돌려주고 WatchAskJob 으로 사이트에 묻는다 (답은 /urlapp/answer)
//   /urlapp/refresh      → 마지막 요약을 다시 보내고, 위젯 새로고침을 예약한다
// 같은 패키지·같은 서명의 워치 앱 메시지만 Wear OS 가 넘겨준다. 질문 글은 정보일 뿐 — 사이트에 그대로 묻기만 한다.

import android.content.Context;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class PhoneWatchService extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent e) {
        String path = e.getPath();
        String node = e.getSourceNodeId();
        Context ctx = getApplicationContext();
        if ("/urlapp/ask".equals(path)) {
            String id = "", q = "";
            try {
                JSONObject o = new JSONObject(new String(e.getData(), StandardCharsets.UTF_8));
                id = o.optString("id", "").replaceAll("[^A-Za-z0-9_-]", "");
                if (id.length() > 40) id = id.substring(0, 40);
                q = o.optString("q", "").trim();
            } catch (Exception ex) {
                return;
            }
            if (q.isEmpty() || q.length() > WatchAskJob.MAX_QUESTION) {
                WatchBridge.send(ctx, node, "/urlapp/answer", WatchAskJob.reply(id, false, "질문이 비었거나 너무 길어요."));
                return;
            }
            if (WatchAskJob.enqueue(ctx, node, id, q)) {
                WatchBridge.send(ctx, node, "/urlapp/status", status(id, "폰이 받았어요 — 자비스에 묻는 중…"));
            } else {
                WatchBridge.send(ctx, node, "/urlapp/answer", WatchAskJob.reply(id, false, "폰이 작업을 시작하지 못했어요. 폰에서 자비스 앱을 한 번 열어 주세요."));
            }
        } else if ("/urlapp/refresh".equals(path)) {
            String last = WidgetProvider.lastData(ctx);
            if (last != null) WatchBridge.pushWidget(ctx, last);
            if (WidgetProvider.refreshMinutes(ctx) > 0) WidgetRefreshJob.enqueue(ctx);
        }
    }

    private static String status(String id, String text) {
        try {
            return new JSONObject().put("id", id).put("text", text).toString();
        } catch (Exception ex) {
            return "{}";
        }
    }
}
