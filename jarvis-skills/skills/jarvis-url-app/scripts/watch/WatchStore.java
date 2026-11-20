package app.jarvis.urlapp;

// 워치가 폰에서 받은 위젯 요약을 보관하고 타일에 보일 모양으로 바꾼다.
// 요약 모양은 폰 위젯과 같다: {"title", "rows": [{"label", "value", "tone": "pos|neg"}], "note"}.
// parse 는 안드로이드 없이(org.json 만) 돈다 — PC 시험(test/WatchTests.java).

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class WatchStore {
    static final String PREFS = "urlapp_watch";
    static final int MAX_ROWS = 3; // 둥근 화면에 들어가는 만큼

    static final class Row {
        final String label, value, tone;

        Row(String label, String value, String tone) {
            this.label = label;
            this.value = value;
            this.tone = tone;
        }
    }

    static final class Summary {
        String title = "";
        final List<Row> rows = new ArrayList<>();
        String note = "";
        boolean empty = true;
    }

    private WatchStore() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, String json, long at) {
        if (json == null || json.length() > 16000) return;
        prefs(c).edit().putString("json", json).putLong("at", at).apply();
    }

    static long at(Context c) {
        return prefs(c).getLong("at", 0);
    }

    static Summary load(Context c, String appName) {
        return parse(prefs(c).getString("json", null), at(c), System.currentTimeMillis(), appName);
    }

    /** 요약 JSON → 타일에 보일 것. 비었거나 깨졌으면 empty=true 와 안내 글. */
    static Summary parse(String json, long at, long now, String appName) {
        Summary s = new Summary();
        s.title = appName == null ? "" : appName;
        JSONObject o = null;
        try {
            if (json != null) o = new JSONObject(json);
        } catch (Exception e) {
            o = null;
        }
        if (o == null) {
            s.note = "폰의 앱을 한 번 열어 주세요";
            return s;
        }
        s.empty = false;
        String t = clean(o.optString("title", ""), 40);
        if (!t.isEmpty()) s.title = t;
        JSONArray rows = o.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length() && s.rows.size() < MAX_ROWS; i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String label = clean(r.optString("label", ""), 30), value = clean(r.optString("value", ""), 20);
            if (label.isEmpty() && value.isEmpty()) continue;
            String tone = r.optString("tone", "");
            s.rows.add(new Row(label, value, "pos".equals(tone) || "neg".equals(tone) ? tone : ""));
        }
        String note = clean(o.optString("note", ""), 40);
        s.note = (note.isEmpty() ? "" : note + " · ") + ago(at, now);
        return s;
    }

    /** "방금" · "12분 전" · "3시간 전" · "11/20 17:03" */
    static String ago(long at, long now) {
        if (at <= 0) return "";
        long min = Math.max(0, (now - at) / 60_000);
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        if (min < 24 * 60) return (min / 60) + "시간 전";
        return new SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(new Date(at));
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\u0000-\\u001f\\u007f\\u200b-\\u200f\\u202a-\\u202e]", " ")
                .replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
