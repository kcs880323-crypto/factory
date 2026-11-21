package app.jarvis.urlapp;

// 카드 자동 등록이 정상인지 판정한다 (안드로이드 API 를 쓰지 않아 PC 자바로 시험할 수 있다).
// 사실(facts)은 CardEngine.status() 가 모은다:
//   access(알림 접근), listener_connected_at / listener_disconnected_at, owner, auth_required, key_saved,
//   auth_errors, pending, pending_error, link_errors, battery_ok, last_card_at, now
// 결과: facts 에 "ok"(true/false), "problems"(고칠 것), "notes"(권하는 것)를 더해 돌려준다.

import org.json.JSONArray;
import org.json.JSONObject;

public final class CardStatus {
    private CardStatus() {}

    static final long QUIET_DAYS = 3; // 카드 알림이 이만큼 없으면 확인을 권한다 (결제가 없었을 수도 있어 문제로 보지는 않음)

    public static JSONObject judge(JSONObject f) {
        JSONArray problems = new JSONArray();
        JSONArray notes = new JSONArray();
        boolean intent = "intent".equals(f.optString("via"));
        if (!intent && !f.optBoolean("access")) {
            problems.put("알림 접근이 꺼져 있어요 — 카드 알림을 읽지 못해요");
        } else if (!intent && f.optLong("listener_disconnected_at") > f.optLong("listener_connected_at")) {
            problems.put("알림 듣기가 끊겼어요 — 알림 접근을 껐다 켜거나 폰을 다시 켜 주세요");
        }
        if (f.optString("owner", "").isEmpty()) problems.put("이 폰의 담당자를 고르지 않았어요");
        if (f.optBoolean("auth_required") && !f.optBoolean("key_saved")) {
            problems.put("앱 연동 키가 없어요 — 사이트에서 만든 키를 넣어 주세요");
        } else if (f.optInt("auth_errors") > 0) {
            problems.put("가계부가 앱 연동 키를 받지 않았어요 — 사이트의 키 목록을 확인해 주세요");
        }
        int pending = f.optInt("pending");
        if (pending > 0) {
            String why = f.optString("pending_error", "");
            problems.put("가계부로 못 보낸 결제 " + pending + "건" + (why.isEmpty() ? "" : " — " + why));
        }
        if (f.optInt("link_errors") > 0) problems.put("가계부가 등록을 거절했어요 — 사이트 주소·형식이 바뀌었을 수 있어요");
        if (!f.optBoolean("battery_ok", true)) notes.put("배터리 최적화에서 빼면 알림을 더 놓치지 않아요");
        long last = f.optLong("last_card_at"), now = f.optLong("now");
        if (now > 0 && (last == 0 || now - last > QUIET_DAYS * 24 * 3600 * 1000L)) {
            notes.put(last == 0 ? "아직 받은 카드 알림이 없어요" : QUIET_DAYS + "일 넘게 카드 알림이 없었어요 (결제가 없었다면 괜찮아요)");
        }
        try {
            JSONObject out = new JSONObject(f.toString());
            out.put("ok", problems.length() == 0);
            out.put("problems", problems);
            out.put("notes", notes);
            return out;
        } catch (Exception e) {
            return f;
        }
    }

    /** 사이트에 보낼 모양: 판정 + 사실 + 최근 기록. 알림 원문·키 값은 넣지 않는다. */
    public static JSONObject report(JSONObject judged, JSONArray journal) {
        JSONObject r = new JSONObject();
        try {
            r.put("type", "urlapp_card_status");
            r.put("version", 1);
            r.put("status", judged);
            JSONArray j = new JSONArray();
            for (int i = 0; journal != null && i < journal.length() && i < 30; i++) j.put(journal.opt(i));
            r.put("journal", j);
        } catch (Exception e) {
            // 기본형만 넣으므로 일어나지 않는다
        }
        return r;
    }
}
