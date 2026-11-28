package app.jarvis.urlapp;

// 네이티브 알람(--alarms)의 계획: 사이트의 /urlapp-alarms.json 을 읽어 '앞으로 울릴 알람' 목록으로 만든다 (순수 자바 — PC 에서 시험).
//   {"alarms": [{"id": "a1b2c3d4", "label": "가락시장 출발", "time": "12:40", "fire_ms": 1795750800000, "repeat": "once"}]}
// fire_ms(정확히 울릴 UTC 밀리초)가 지났거나 1년 넘게 먼 것, id 가 이상한 것은 뺀다. 빠른 순, 같은 id 는 하나, 30개까지.
// 반복 알람은 사이트가 다음 울릴 때(fire_ms)를 주고, 울린 뒤 앱이 다시 읽어 그다음을 예약한다.

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AlarmPlan {
    public static final int MAX = 30;
    public static final long MAX_AHEAD_MS = 400L * 24 * 60 * 60_000L;
    public static final int LABEL_MAX = 60;

    private AlarmPlan() {
    }

    public static final class Alarm {
        public final String id, label, repeat;
        public final long fireMs;

        public Alarm(String id, String label, long fireMs, String repeat) {
            this.id = id;
            this.label = label;
            this.fireMs = fireMs;
            this.repeat = repeat;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("label", label).put("fire_ms", fireMs).put("repeat", repeat);
            } catch (Exception ignored) {
                // 넣을 수 없는 값 없음
            }
            return o;
        }
    }

    /** 사이트 응답 → 앞으로 울릴 알람. 모양이 틀리면 null (그때는 지금 예약을 건드리지 않는다). */
    public static List<Alarm> parse(String body, long now) {
        JSONArray a;
        try {
            Object root = new org.json.JSONTokener(body == null ? "" : body).nextValue();
            if (root instanceof JSONObject) a = ((JSONObject) root).optJSONArray("alarms");
            else if (root instanceof JSONArray) a = (JSONArray) root;
            else return null;
        } catch (Exception e) {
            return null;
        }
        if (a == null) return null;
        return fromArray(a, now);
    }

    /** 저장해 둔 목록(또는 사이트 목록) → 앞으로 울릴 알람만. */
    public static List<Alarm> fromArray(JSONArray a, long now) {
        List<Alarm> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            if (!validId(id) || ids.contains(id)) continue;
            long fire = o.optLong("fire_ms", 0);
            if (fire <= now || fire > now + MAX_AHEAD_MS) continue;
            ids.add(id);
            out.add(new Alarm(id, cleanLabel(o.optString("label", "")), fire, cleanRepeat(o.optString("repeat", ""))));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.fireMs, y.fireMs));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    public static JSONArray toArray(List<Alarm> list) {
        JSONArray a = new JSONArray();
        for (Alarm x : list) a.put(x.toJson());
        return a;
    }

    /** 영문·숫자와 _ - . : 만, 1~64자 (알림·예약 번호를 만드는 데 쓰므로 좁게). */
    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    /** 알림에 보일 이름: 제어 문자를 빼고 60자까지, 비면 '알람'. */
    public static String cleanLabel(String s) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > LABEL_MAX) t = t.substring(0, LABEL_MAX);
        return t.isEmpty() ? "알람" : t;
    }

    static String cleanRepeat(String s) {
        String t = s == null ? "" : s.trim();
        return t.matches("[A-Za-z0-9_,-]{1,40}") ? t : "once";
    }

    /** AlarmManager 예약 번호 (같은 id 는 늘 같은 번호 — 다시 읽으면 바꿔 쓰고, 목록에서 빠지면 그 번호로 취소). */
    public static int requestCode(String id) {
        return ("urlapp-alarm:" + id).hashCode();
    }

    /** '5분 뒤 다시' 예약 번호 (원래 알람과 따로). */
    public static int snoozeCode(String id) {
        return ("urlapp-alarm-snooze:" + id).hashCode();
    }

    /** 예전에 걸어 둔 id 중 새 목록에 없는 것 (취소할 것). */
    public static List<String> removed(List<String> before, List<Alarm> now) {
        Set<String> keep = new LinkedHashSet<>();
        for (Alarm x : now) keep.add(x.id);
        List<String> out = new ArrayList<>();
        for (String id : before) if (!keep.contains(id) && !out.contains(id)) out.add(id);
        return out;
    }
}
