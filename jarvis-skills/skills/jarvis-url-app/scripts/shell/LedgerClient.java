package app.jarvis.urlapp;

// 가계부 사이트와 주고받기 (자바 표준 HttpURLConnection + org.json. 안드로이드에도 있고 PC 자바로 시험할 수 있다).
// 어떤 주소에 어떤 모양으로 보낼지는 assets/urlapp-cards.json(사이트별 설정)이 정한다:
//   add     : {"method","path","body":{…"{date}"…}}   지출 한 건 추가
//   history : {"path":"…{month}…","fields":{details,large,medium,amount,date,memo,owner,id,table}}
//   delete  : {"method","path","body":{…"{id}"…}}     한 건 지우기 (되돌리기·승인취소)
// body 의 "{이름}" 자리는 값으로 바꾼다. 값 전체가 "{이름}" 하나면 원래 형(숫자 등)을 그대로 쓴다.

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class LedgerClient {
    private final String base;   // https://사이트 (끝에 / 없음)
    private final JSONObject cfg;

    public LedgerClient(String baseUrl, JSONObject cfg) {
        String b = baseUrl;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.cfg = cfg;
    }

    /** 한 건 추가. 사이트가 {"success":true} 나 2xx 로 답하면 true. */
    public boolean add(Map<String, Object> vars) throws IOException, JSONException {
        JSONObject a = cfg.getJSONObject("add");
        Object body = fill(a.get("body"), vars);
        Response r = request(a.optString("method", "POST"), url(a.getString("path"), vars), body.toString());
        return r.ok();
    }

    /** 한 달 기록. 응답 안의 모든 '행'(세부항목·대분류가 있는 객체)을 모아 돌려준다. month = "2026-11". */
    public List<JSONObject> history(String month) throws IOException, JSONException {
        JSONObject h = cfg.getJSONObject("history");
        java.util.HashMap<String, Object> v = new java.util.HashMap<>();
        v.put("month", month);
        Response r = request("GET", url(h.getString("path"), v), null);
        if (!r.ok()) throw new IOException("history HTTP " + r.code);
        Object json = new org.json.JSONTokener(r.body).nextValue();
        JSONObject f = h.optJSONObject("fields");
        String detailsKey = f == null ? "details" : f.optString("details", "details");
        String largeKey = f == null ? "category_large" : f.optString("large", "category_large");
        List<JSONObject> rows = new ArrayList<>();
        collect(json, detailsKey, largeKey, rows, 0);
        return rows;
    }

    private static void collect(Object o, String detailsKey, String largeKey, List<JSONObject> out, int depth) {
        if (depth > 6 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(detailsKey) && j.has(largeKey)) {
                out.add(j);
                return;
            }
            Iterator<String> it = j.keys();
            while (it.hasNext()) collect(j.opt(it.next()), detailsKey, largeKey, out, depth + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), detailsKey, largeKey, out, depth + 1);
        }
    }

    /**
     * 앞서 등록한 한 건을 찾아 지운다 (날짜·세부항목·금액·담당자·메모가 같은 것 중 하나).
     * 찾아서 지우면 true, 못 찾으면 false.
     */
    public boolean deleteMatching(String date, String details, long amount, String owner, String memo)
            throws IOException, JSONException {
        JSONObject h = cfg.getJSONObject("history");
        JSONObject f = h.optJSONObject("fields");
        String kDate = field(f, "date"), kDetails = field(f, "details"), kAmount = field(f, "amount");
        String kOwner = field(f, "owner"), kMemo = field(f, "memo"), kId = field(f, "id"), kTable = field(f, "table");
        for (JSONObject row : history(date.substring(0, 7))) {
            if (!date.equals(row.optString(kDate, "").substring(0, Math.min(10, row.optString(kDate, "").length())))) continue;
            if (!details.equals(row.optString(kDetails, ""))) continue;
            if (amount != toLong(row.opt(kAmount))) continue;
            if (owner != null && !owner.isEmpty() && row.has(kOwner) && !owner.equals(row.optString(kOwner))) continue;
            if (memo != null && !memo.isEmpty() && row.has(kMemo) && !memo.equals(row.optString(kMemo))) continue;
            if (!row.has(kId)) continue;
            java.util.HashMap<String, Object> v = new java.util.HashMap<>();
            v.put("id", row.get(kId));
            v.put("table", row.opt(kTable) == null ? "" : row.opt(kTable));
            JSONObject d = cfg.getJSONObject("delete");
            Response r = request(d.optString("method", "DELETE"), url(d.getString("path"), v), fill(d.get("body"), v).toString());
            return r.ok();
        }
        return false;
    }

    private static String field(JSONObject f, String name) {
        String def;
        switch (name) {
            case "large": def = "category_large"; break;
            case "medium": def = "category_medium"; break;
            case "owner": def = "earner_name"; break;
            case "table": def = "source_table"; break;
            default: def = name;
        }
        return f == null ? def : f.optString(name, def);
    }

    static long toLong(Object o) {
        if (o == null) return -1;
        if (o instanceof Number) return Math.round(((Number) o).doubleValue());
        try {
            return Math.round(Double.parseDouble(o.toString().replace(",", "").trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------- 자리 채우기 ----------

    static Object fill(Object template, Map<String, Object> vars) throws JSONException {
        if (template instanceof JSONObject) {
            JSONObject in = (JSONObject) template;
            JSONObject out = new JSONObject();
            Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, fill(in.get(k), vars));
            }
            return out;
        }
        if (template instanceof JSONArray) {
            JSONArray in = (JSONArray) template;
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(fill(in.get(i), vars));
            return out;
        }
        if (template instanceof String) {
            String s = (String) template;
            if (s.matches("\\{[a-z_]+\\}")) {
                Object v = vars.get(s.substring(1, s.length() - 1));
                return v == null ? "" : v;
            }
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
            return s;
        }
        return template;
    }

    private String url(String path, Map<String, Object> vars) {
        String p = path;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            try {
                p = p.replace("{" + e.getKey() + "}", URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
                // UTF-8 은 늘 있다
            }
        }
        return base + (p.startsWith("/") ? p : "/" + p);
    }

    // ---------- HTTP ----------

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            if (code < 200 || code >= 300) return false;
            try {
                Object j = new org.json.JSONTokener(body).nextValue();
                if (j instanceof JSONObject && ((JSONObject) j).has("success")) {
                    return ((JSONObject) j).optBoolean("success", false);
                }
            } catch (Exception e) {
                // JSON 이 아니면 상태 코드로만 판단
            }
            return true;
        }
    }

    private static Response request(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 4_000_000) break;
                    }
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Response(code, text);
        } finally {
            c.disconnect();
        }
    }
}
