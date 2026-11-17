package app.jarvis.urlapp;

// 휴대폰 알림을 받아 카드 결제 알림만 골라 CardEngine 에 넘긴다. 사용자가 설정에서 "알림 접근"을 켜야 동작한다.
// 카드사 이름 + 승인/취소 + 금액(원)이 있는 알림만 다루고, 믿을 수 있는 앱(카드사·월렛·문자, 카드사 채널의 카카오
// 알림톡)이 아니면 쓰지 않고 앱 이름만 '감지한 앱'으로 남긴다. 알림 내용은 저장하지 않는다.

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.Map;

public class CardListenerService extends NotificationListenerService {
    // 같은 알림이 고쳐 다시 올라와도 한 번만 (최근 200개)
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 200;
        }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (RuntimeException e) {
            // 알림 하나 때문에 듣기가 멈추지 않게
        }
    }

    @SuppressWarnings("deprecation") // getParcelableArray: 안드로이드 13 미만과 같이 쓰려고
    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!CardEngine.available(this)) return;
        CardEngine engine = CardEngine.get(this);
        if (!engine.ready()) return;

        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT);
        StringBuilder body = new StringBuilder(text == null ? "" : text.toString());
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES); // 문자·카카오 (대화 모양 알림)
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            CharSequence last = ((Bundle) messages[messages.length - 1]).getCharSequence("text");
            if (last != null && body.indexOf(last.toString()) < 0) body.append('\n').append(last);
        }
        String all = (title == null ? "" : title) + "\n" + body;
        if (all.indexOf('원') < 0) return; // 빠른 거르기

        String sig = pkg + "|" + all.hashCode();
        synchronized (seen) {
            if (seen.containsKey(sig)) return;
            seen.put(sig, Boolean.TRUE);
        }
        CardParser.Txn t = CardParser.parse(title == null ? "" : title.toString(), body.toString(), sbn.getPostTime());
        if (t == null) return;
        if (!engine.trusted(pkg, title)) {
            engine.store.noteDetected(pkg);
            return;
        }
        engine.offer(t);
    }
}
