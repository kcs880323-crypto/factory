package app.jarvis.urlapp;

// 워치(Wear OS)와 주고받는 문 (껍데기 쪽). 실제 구현(GmsWatchLink, scripts/watchlink/)은 --watch 앱에만
// Google Play 서비스 Wearable 라이브러리와 함께 따로 들어간다 — 다른 앱의 껍데기는 그 라이브러리 없이 그대로 컴파일된다.
// 주고받는 것 (경로는 모두 /urlapp/ 아래, 같은 패키지·같은 서명의 폰·워치 앱끼리만 오간다):
//   폰 → 워치  데이터 /urlapp/widget {json: 위젯 요약, at}     — 워치 타일이 보여 준다
//   워치 → 폰  메시지 /urlapp/ask {id, q}                      — 워치에서 말한 질문
//   폰 → 워치  메시지 /urlapp/status {id, text}·/urlapp/answer {id, ok, text}
//   워치 → 폰  메시지 /urlapp/refresh                           — 타일을 볼 때 오래된 요약이면
//   폰 → 워치  메시지 /urlapp/alarm {act: ring|stop, id, label, at} — 폰 알람(--alarms)이 울림·멈춤 (워치도 진동)
//   워치 → 폰  메시지 /urlapp/alarm-action {act: dismiss|snooze, id} — 워치에서 끄기·5분 뒤

import android.content.Context;

interface WatchLink {
    void pushWidget(Context ctx, String json);

    void send(Context ctx, String node, String path, String json);

    /** 연결된 이 앱의 워치(능력 urlapp_watch) 모두에 보낸다. */
    void broadcast(Context ctx, String path, String json);
}
