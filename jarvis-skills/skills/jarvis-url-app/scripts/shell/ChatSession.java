package app.jarvis.urlapp;

// 음성 대화의 '같은 대화' 표시 (--mic 앱): 대화 창(VoiceActivity)과 워치 질문(WatchAskJob)이 사이트에 물을 때마다 숨은 웹 화면을 새로 열기 때문에
// "…등록해줄까요?" → "응" 처럼 두 번에 나눠 말하면 사이트(자비스)가 다른 대화로 보기 쉽다 (2026-11-22 자비스 PC 의 질문).
// 그래서 앱이 무작위 대화 표시(session)를 하나 만들어 두고 마지막 질문 뒤 15분 안에는 같은 값을, 지나면 새 값을 사이트에 넘긴다:
//   window.urlappAsk(질문, { session: "<표시>" })  — 사이트가 이 값을 쓰든 말든 기존 동작 그대로다 (뒤호환: 인자를 안 보는 사이트는 무시).
// 표시는 무작위 16자리이고 이 폰 앱 안에만 있다 (사이트의 같은 주소로만 넘어간다).

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

final class ChatSession {
    static final long KEEP_MS = 15 * 60_000L;
    private static final String PREFS = "urlapp_chat_session";

    private ChatSession() {
    }

    /** 이어 쓸지(15분 안·시계가 거꾸로 가지 않음) 정해서 쓸 표시를 돌려준다 (순수 함수 — PC 에서 시험한다). */
    static String pick(String prevId, long prevAt, long now, String freshId) {
        boolean keep = prevId != null && !prevId.isEmpty() && prevAt > 0 && now >= prevAt && now - prevAt <= KEEP_MS;
        return keep ? prevId : freshId;
    }

    /** 지금 질문에 붙일 표시 (부르면 '마지막 질문 시각'이 지금으로 갱신된다). */
    static synchronized String current(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String id = pick(p.getString("id", null), p.getLong("at", 0), now, fresh());
        p.edit().putString("id", id).putLong("at", now).apply();
        return id;
    }

    private static String fresh() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
