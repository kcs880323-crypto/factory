package app.jarvis.urlapp;

// MacroDroid(플레이 스토어 자동화 앱)가 카드 결제 알림을 넘겨주는 입구.
// 이 앱이 직접 알림을 읽으면(알림 접근 권한) 구글 Play 프로텍트가 직접 설치를 막기 때문에, 알림 읽기는
// MacroDroid 가 하고 이 앱은 받은 글만 해석·등록한다.
// MacroDroid 매크로: 트리거 "알림 수신"(카드사·월렛·문자 앱) → 동작 "인텐트 보내기"(브로드캐스트)
//   action  = app.jarvis.urlapp.action.CARD_NOTIFICATION, package = 이 앱의 id
//   extras  = token(설정 화면의 키), title(알림 제목), text(알림 내용), app(앱 이름, 선택)
// 키가 맞지 않으면 무시한다 (다른 앱이 가짜 결제를 넣지 못하게).

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CardIntentReceiver extends BroadcastReceiver {
    static final String ACTION = "app.jarvis.urlapp.action.CARD_NOTIFICATION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!CardEngine.available(context)) return;
        CardEngine engine = CardEngine.get(context);
        if (!engine.store.tokenMatches(extra(intent, "token"))) {
            engine.store.noteReceived(false, true);
            return;
        }
        String title = extra(intent, "title");
        String text = extra(intent, "text");
        if (text.isEmpty()) text = extra(intent, "notification"); // 다른 이름으로 보낸 경우
        if (!engine.ready()) {
            engine.store.noteReceived(false, false);
            return;
        }
        boolean card = engine.handleNotification(extra(intent, "app"), title, text, System.currentTimeMillis(), false);
        engine.store.noteReceived(card, false);
    }

    @SuppressWarnings("deprecation") // Bundle.get: 문자열이 아닌 값으로 보낸 경우를 위해
    private static String extra(Intent i, String key) {
        CharSequence v = i.getCharSequenceExtra(key);
        if (v == null) {
            Object o = i.getExtras() == null ? null : i.getExtras().get(key);
            v = o == null ? null : o.toString();
        }
        return v == null ? "" : v.toString();
    }
}
