package app.jarvis.urlapp;

// 카드 자동 등록의 저장소 (이 폰 안의 SharedPreferences, 다른 앱은 읽을 수 없다).
// 담당자, 켜짐, 사용자가 허용한 앱, 감지했지만 쓰지 않은 앱(패키지 이름·횟수만, 알림 내용은 저장하지 않음),
// 최근 등록 기록(최대 60건), 분류 학습용 지난 기록(가맹점·분류만).

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

final class CardStore {
    static final String REGISTERED = "registered";
    static final String PENDING = "pending";   // 아직 못 보냄 (인터넷 등) → 다시 시도
    static final String UNDONE = "undone";     // 사용자가 되돌림
    static final String CANCELLED = "cancelled"; // 승인취소 알림으로 지움
    private static final int MAX_LOG = 60;

    private final SharedPreferences p;

    CardStore(Context c) {
        p = c.getSharedPreferences("urlapp_cards", Context.MODE_PRIVATE);
    }

    String owner() {
        return p.getString("owner", null);
    }

    void setOwner(String o) {
        p.edit().putString("owner", o).apply();
    }

    boolean enabled() {
        return p.getBoolean("enabled", true);
    }

    void setEnabled(boolean on) {
        p.edit().putBoolean("enabled", on).apply();
    }

    boolean prompted() {
        return p.getBoolean("prompted", false);
    }

    void setPrompted() {
        p.edit().putBoolean("prompted", true).apply();
    }

    // ---------- 허용·감지한 앱 ----------

    synchronized boolean allowed(String pkg) {
        return arr("allowed").toString().contains("\"" + pkg + "\"");
    }

    synchronized void allow(String pkg) {
        JSONArray a = arr("allowed");
        if (!allowed(pkg)) a.put(pkg);
        p.edit().putString("allowed", a.toString()).apply();
        JSONObject d = obj("detected");
        d.remove(pkg);
        p.edit().putString("detected", d.toString()).apply();
    }

    synchronized void noteDetected(String pkg) {
        JSONObject d = obj("detected");
        try {
            JSONObject e = d.optJSONObject(pkg);
            if (e == null) e = new JSONObject();
            e.put("count", e.optInt("count") + 1);
            e.put("last", System.currentTimeMillis());
            d.put(pkg, e);
        } catch (JSONException ignored) {
            return;
        }
        p.edit().putString("detected", d.toString()).apply();
    }

    synchronized List<String> detected() {
        List<String> out = new ArrayList<>();
        Iterator<String> it = obj("detected").keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // ---------- 등록 기록 ----------

    synchronized JSONObject newEntry(CardParser.Txn t, CardCategorizer.Pick pick, String owner) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", UUID.randomUUID().toString());
            e.put("date", CardParser.ymd(t.when));
            e.put("when", t.when);
            e.put("merchant", t.merchant.isEmpty() ? t.card + " 결제" : t.merchant);
            e.put("amount", t.amount);
            e.put("card", t.card);
            e.put("large", pick.large);
            e.put("medium", pick.medium);
            e.put("source", pick.source);
            e.put("owner", owner);
            e.put("status", PENDING);
            e.put("at", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // 넣는 값이 모두 기본형이라 일어나지 않는다
        }
        save(e);
        return e;
    }

    synchronized void save(JSONObject e) {
        JSONArray log = arr("log");
        JSONArray out = new JSONArray();
        out.put(e);
        for (int i = 0; i < log.length() && out.length() < MAX_LOG; i++) {
            JSONObject o = log.optJSONObject(i);
            if (o != null && !o.optString("id").equals(e.optString("id"))) out.put(o);
        }
        p.edit().putString("log", out.toString()).apply();
    }

    synchronized List<JSONObject> log() {
        JSONArray log = arr("log");
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    synchronized JSONObject find(String id) {
        for (JSONObject e : log()) if (e.optString("id").equals(id)) return e;
        return null;
    }

    /** 같은 결제를 이미 처리했는지 (10분 안, 같은 카드·금액·승인/취소). */
    synchronized boolean recentlyHandled(CardParser.Txn t) {
        for (JSONObject e : log()) {
            boolean cancelEntry = e.optBoolean("cancel", false);
            if (cancelEntry == t.cancel && e.optLong("amount") == t.amount && t.card.equals(e.optString("card"))
                    && Math.abs(e.optLong("when") - t.when) <= 10 * 60 * 1000L) {
                return true;
            }
        }
        return false;
    }

    /** 승인취소에 맞는 등록 건: 같은 카드·금액, 31일 안, 가장 최근. 가맹점을 알면 같은 가맹점 우선. */
    synchronized JSONObject findForCancel(CardParser.Txn t) {
        JSONObject best = null;
        for (JSONObject e : log()) {
            if (!REGISTERED.equals(e.optString("status")) || e.optBoolean("cancel", false)) continue;
            if (e.optLong("amount") != t.amount || !t.card.equals(e.optString("card"))) continue;
            if (t.when - e.optLong("when") > 31L * 24 * 3600 * 1000 || e.optLong("when") - t.when > 3600 * 1000) continue;
            if (!t.merchant.isEmpty() && t.merchant.equals(e.optString("merchant"))) return e;
            if (best == null) best = e;
        }
        return best;
    }

    synchronized void noteCancel(CardParser.Txn t) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", UUID.randomUUID().toString());
            e.put("cancel", true);
            e.put("when", t.when);
            e.put("amount", t.amount);
            e.put("card", t.card);
            e.put("merchant", t.merchant);
            e.put("status", "cancel-seen");
            e.put("at", System.currentTimeMillis());
        } catch (JSONException ignored) {
            return;
        }
        save(e);
    }

    synchronized List<JSONObject> pending() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : log()) if (PENDING.equals(e.optString("status"))) out.add(e);
        return out;
    }

    // ---------- 분류 학습 ----------

    synchronized void saveLearned(List<String[]> rows) {
        JSONArray a = new JSONArray();
        for (String[] r : rows) {
            JSONArray x = new JSONArray();
            for (String s : r) x.put(s);
            a.put(x);
        }
        p.edit().putString("learned", a.toString()).putLong("learned_at", System.currentTimeMillis()).apply();
    }

    synchronized List<String[]> learned() {
        JSONArray a = arr("learned");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray x = a.optJSONArray(i);
            if (x == null || x.length() < 3) continue;
            out.add(new String[] {x.optString(0), x.optString(1), x.optString(2)});
        }
        return out;
    }

    long learnedAt() {
        return p.getLong("learned_at", 0);
    }

    private JSONArray arr(String key) {
        try {
            return new JSONArray(p.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private JSONObject obj(String key) {
        try {
            return new JSONObject(p.getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
