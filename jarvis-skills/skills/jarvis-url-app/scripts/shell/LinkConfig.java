package app.jarvis.urlapp;

// 사이트의 "연동 약속 파일"(/urlapp.json)로 앱의 카드 연동 설정을 덮는다 (org.json, PC 자바로 시험 가능).
// 사이트를 AI Studio 에서 고쳐 API 주소·칸 이름이 바뀌어도, 사이트가 이 파일을 같이 고치면 앱은 업데이트 없이 따라간다.
//   { "version": 1, "cards": { "add": {...}, "history": {...}, "delete": {...},
//                              "default_category": [...], "rules": [...], "owners": [...], "learn_months": 3,
//                              "auth": {"header": "X-App-Key"} } }   ← 사이트에 보안(앱 연동 키)을 켰을 때
// 주소(path)는 같은 사이트 안("/" 로 시작, "//" 아님)만 받는다. 모양이 이상하면 앱 기본 설정을 그대로 쓴다.

import org.json.JSONArray;
import org.json.JSONObject;

public final class LinkConfig {
    private LinkConfig() {}

    public static JSONObject merge(JSONObject builtin, String remoteJson) {
        if (remoteJson == null || remoteJson.trim().isEmpty()) return builtin;
        try {
            JSONObject r = new JSONObject(remoteJson);
            JSONObject c = r.optJSONObject("cards");
            if (c == null) return builtin;
            JSONObject out = new JSONObject(builtin.toString());
            for (String key : new String[] {"add", "history", "delete"}) {
                if (!c.has(key)) continue;
                JSONObject v = c.optJSONObject(key);
                if (!validEndpoint(v, key)) return builtin;
                out.put(key, v);
            }
            if (c.has("default_category")) {
                JSONArray d = c.optJSONArray("default_category");
                if (d == null || d.length() != 2) return builtin;
                out.put("default_category", d);
            }
            if (c.has("rules")) {
                JSONArray rules = c.optJSONArray("rules");
                if (rules == null) return builtin;
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject x = rules.optJSONObject(i);
                    if (x == null || x.optString("large").isEmpty() || x.optJSONArray("keywords") == null) return builtin;
                }
                out.put("rules", rules);
            }
            if (c.has("owners")) {
                JSONArray o = c.optJSONArray("owners");
                if (o == null || o.length() == 0) return builtin;
                out.put("owners", o);
            }
            if (c.has("learn_months")) out.put("learn_months", c.optInt("learn_months", 3));
            if (c.has("auth")) { // 사이트에 보안을 켜면: {"header": "X-App-Key"} → 앱이 저장한 연동 키를 이 이름으로 붙인다
                JSONObject a = c.optJSONObject("auth");
                if (a == null || !a.optString("header").matches("[A-Za-z0-9-]{1,40}")) return builtin;
                out.put("auth", a);
            }
            out.put("_from_site", true);
            return out;
        } catch (Exception e) {
            return builtin;
        }
    }

    static boolean validEndpoint(JSONObject v, String key) {
        if (v == null) return false;
        String path = v.optString("path", "");
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) return false;
        return "history".equals(key) || v.optJSONObject("body") != null;
    }
}
