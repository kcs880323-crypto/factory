package app.jarvis.urlapp;

// 사이트 알림 목록 읽기 (--notify 앱, PC 자바로 시험할 수 있다). 사이트의 /urlapp-notify.json 모양:
//   {"items": [{"id": "42", "title": "작업 끝", "body": "블로그 글 작성이 끝났어요", "open": "/tasks/42"}]}
// 처음 읽을 때(primed=false)는 이미 있던 알림을 띄우지 않고 '본 것'으로만 적는다 (설치하자마자 옛 알림이 쏟아지지 않게).
// 그 뒤로는 본 적 없는 id 만 띄운다. open 은 같은 사이트 안의 경로(/로 시작)만 받는다.

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class NotifyFeed {
    public static final class Item {
        public final String id, title, body, open;

        Item(String id, String title, String body, String open) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.open = open;
        }
    }

    /** 새로 띄울 것(show, 최대 max 개, 목록 순서대로)과 이번에 본 모든 id(ids). */
    public static final class Result {
        public final List<Item> show = new ArrayList<>();
        public final List<String> ids = new ArrayList<>();
        public int skipped; // max 를 넘어 띄우지 않은 새 알림 수
    }

    private NotifyFeed() {}

    public static Result read(String json, Collection<String> seen, boolean primed, int max) {
        Result r = new Result();
        JSONArray items;
        try {
            Object o = new org.json.JSONTokener(json).nextValue();
            items = o instanceof JSONObject ? ((JSONObject) o).optJSONArray("items") : o instanceof JSONArray ? (JSONArray) o : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("알림 목록을 읽지 못했어요");
        }
        if (items == null) throw new IllegalArgumentException("알림 목록(items)이 없어요");
        for (int i = 0; i < items.length() && i < 100; i++) {
            JSONObject x = items.optJSONObject(i);
            if (x == null) continue;
            String id = x.optString("id", "").trim();
            if (id.isEmpty() || id.length() > 64 || r.ids.contains(id)) continue;
            r.ids.add(id);
            if (!primed || seen.contains(id)) continue;
            String title = clip(x.optString("title", ""), 80);
            String body = clip(x.optString("body", ""), 300);
            if (title.isEmpty() && body.isEmpty()) continue;
            if (r.show.size() >= max) {
                r.skipped++;
                continue;
            }
            r.show.add(new Item(id, title, body, safePath(x.optString("open", ""))));
        }
        return r;
    }

    /** 같은 사이트 안의 경로만 (/로 시작, //·\\ 로 시작하지 않음, 공백 없음). 아니면 "". */
    public static String safePath(String p) {
        if (p == null || p.length() > 500 || !p.startsWith("/") || p.startsWith("//") || p.startsWith("/\\")) return "";
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c <= ' ' || c == '\\') return "";
        }
        return p;
    }

    static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
