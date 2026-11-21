package app.jarvis.urlapp;

// 휴대폰 알림을 받아 카드 결제 알림만 골라 CardEngine 에 넘긴다. 사용자가 설정에서 "알림 접근"을 켜야 동작한다.
// 카드사 이름 + 승인/취소 + 금액(원)이 있는 알림만 다루고, 믿을 수 있는 앱(카드사·월렛·문자, 카드사 채널의 카카오
// 알림톡)이 아니면 쓰지 않고 앱 이름만 '감지한 앱'으로 남긴다. 알림 내용은 저장하지 않는다.

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CardListenerService extends NotificationListenerService {
    // 설정 화면의 '알림창에 남은 알림으로 시험'이 지금 알림창의 알림을 읽을 때 쓴다 (듣기가 연결돼 있을 때만)
    private static volatile CardListenerService live;

    @Override
    public void onListenerConnected() {
        live = this;
        if (CardEngine.available(this)) {
            CardStore st = CardEngine.get(this).store;
            st.noteListener(true);
            st.journal("듣기", "알림 듣기가 연결됐어요");
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (live == this) live = null;
        if (CardEngine.available(this)) {
            CardStore st = CardEngine.get(this).store;
            st.noteListener(false);
            st.journal("듣기", "알림 듣기가 끊겼어요");
        }
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        super.onDestroy();
    }

    /**
     * 지금 알림창에 남아 있는 알림 중 금액(원·₩)이 있는 것: {패키지, 앱 이름, 제목, 본문, 올라온 시각}. 최근 것부터 30개까지.
     * 알림 듣기가 연결되지 않았으면 null. 앱이 실제로 받을 때와 같은 방법으로 글을 꺼낸다.
     */
    static List<String[]> active(Context ctx) {
        CardListenerService s = live;
        if (s == null) return null;
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (RuntimeException e) {
            return null;
        }
        List<String[]> out = new ArrayList<>();
        if (all == null) return out;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        for (StatusBarNotification sbn : all) {
            String[] r = read(ctx, sbn);
            if (r != null) out.add(r);
            if (out.size() >= 30) break;
        }
        return out;
    }

    /** 알림 하나의 글: {패키지, 앱 이름, 제목, 본문, 올라온 시각}. 이 앱 것·묶음 머리·금액 없는 것은 null. */
    @SuppressWarnings("deprecation") // getParcelableArray: 안드로이드 13 미만과 같이 쓰려고
    static String[] read(Context ctx, StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(ctx.getPackageName())) return null;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        Bundle x = n.extras;
        if (x == null) return null;
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
        if (all.indexOf('원') < 0 && all.indexOf('₩') < 0 && all.indexOf('￦') < 0) return null; // 빠른 거르기 (월렛은 ₩58,600)
        String appName = null; // 보낸 앱 이름 (예: "롯데카드"): 문구에 카드사 이름이 없는 카드사 앱 알림에 쓴다
        try {
            PackageManager pm = ctx.getPackageManager();
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            appName = null;
        }
        return new String[] {pkg, appName, title == null ? "" : title.toString(), body.toString(),
                String.valueOf(sbn.getPostTime())};
    }
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

    private void handle(StatusBarNotification sbn) {
        if (!CardEngine.available(this)) return;
        String[] r = read(this, sbn);
        if (r == null) return;
        CardEngine engine = CardEngine.get(this);
        if (!engine.ready()) return;
        String sig = r[0] + "|" + (r[2] + "\n" + r[3]).hashCode();
        synchronized (seen) {
            if (seen.containsKey(sig)) return;
            seen.put(sig, Boolean.TRUE);
        }
        engine.handleNotification(r[0], r[2], r[3], Long.parseLong(r[4]), true, r[1]);
    }
}
