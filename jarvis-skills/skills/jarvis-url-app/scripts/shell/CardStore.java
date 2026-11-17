package app.jarvis.urlapp;

// 카드 자동 등록의 저장소 (이 폰 안의 SharedPreferences, 다른 앱은 읽을 수 없다).
// 담당자, 켜짐, 사용자가 허용한 앱, 감지했지만 쓰지 않은 앱(패키지 이름·횟수만, 알림 내용은 저장하지 않음),
// 최근 등록 기록(최대 60건), 분류 학습용 지난 기록(가맹점·분류만).

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

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
    private final File keyFile; // 앱 연동 키의 사본 (설정 파일이 깨지거나 비어도 되살린다)

    CardStore(Context c) {
        p = c.getSharedPreferences("urlapp_cards", Context.MODE_PRIVATE);
        keyFile = new File(c.getFilesDir(), "urlapp_app_key");
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

    /** 설정이 덜 끝났을 때 앱을 열면 묻기 — 사용자가 '다시 묻지 않기'를 누르면 끈다. */
    boolean neverAsk() {
        return p.getBoolean("never_ask", false);
    }

    void setNeverAsk() {
        p.edit().putBoolean("never_ask", true).apply();
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
            e.put("product", t.product);
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

    /** 가계부 메모에 쓰는 카드 이름 (카드사 + 상품명, 예: "현대카드 네이버"). 되돌리기도 같은 이름으로 찾는다. */
    static String memo(JSONObject e) {
        return CardParser.label(e.optString("card"), e.optString("product", ""));
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
            if (cancelEntry == t.cancel && e.optLong("amount") == t.amount && CardParser.sameCard(t.card, e.optString("card"))
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
            if (e.optLong("amount") != t.amount || !CardParser.sameCard(t.card, e.optString("card"))) continue;
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

    // ---------- 모아 두기 (같은 결제를 45초 모아 하나로. 앱이 꺼져도 남게 저장) ----------

    synchronized void addHolding(CardParser.Txn t) {
        JSONArray a = arr("holding");
        JSONObject o = new JSONObject();
        try {
            o.put("card", t.card);
            o.put("product", t.product);
            o.put("cancel", t.cancel);
            o.put("amount", t.amount);
            o.put("when", t.when);
            o.put("whenFromText", t.whenFromText);
            o.put("merchant", t.merchant);
            o.put("received", System.currentTimeMillis());
        } catch (JSONException ignored) {
            return;
        }
        a.put(o);
        p.edit().putString("holding", a.toString()).apply();
    }

    /** 받은 지 olderThanMs 가 지난 것을 꺼낸다 (나머지는 남긴다). */
    synchronized List<CardParser.Txn> takeHolding(long olderThanMs) {
        JSONArray a = arr("holding");
        JSONArray keep = new JSONArray();
        List<CardParser.Txn> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (now - o.optLong("received") < olderThanMs) {
                keep.put(o);
                continue;
            }
            CardParser.Txn t = new CardParser.Txn();
            t.card = o.optString("card");
            t.product = o.optString("product", "");
            t.cancel = o.optBoolean("cancel");
            t.amount = o.optLong("amount");
            t.when = o.optLong("when");
            t.whenFromText = o.optBoolean("whenFromText");
            t.merchant = o.optString("merchant");
            out.add(t);
        }
        p.edit().putString("holding", keep.toString()).apply();
        return out;
    }

    synchronized boolean hasHolding() {
        return arr("holding").length() > 0;
    }

    // ---------- MacroDroid 연결 ----------

    /** MacroDroid 가 함께 보내야 하는 비밀 키 (처음 한 번 만든다). 다른 앱이 가짜 결제를 넣지 못하게. */
    synchronized String token() {
        String t = p.getString("token", null);
        if (t == null) {
            String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            t = b.toString();
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    boolean tokenMatches(String given) {
        if (given == null) return false;
        byte[] a = token().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = given.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    void noteReceived(boolean card, boolean badToken) {
        SharedPreferences.Editor e = p.edit().putLong("rx_at", System.currentTimeMillis());
        if (badToken) e.putLong("rx_bad_token_at", System.currentTimeMillis());
        else if (card) e.putLong("rx_card_at", System.currentTimeMillis());
        e.apply();
    }

    long receivedAt(String which) {
        return p.getLong(which, 0);
    }

    // ---------- 가계부 앱 연동 키 (사이트에 보안을 켠 뒤, 화면 밖 요청에 붙이는 키) ----------

    // 한 번 넣은 키는 사용자가 '키 지우기'를 확인할 때만 지운다: 빈 값으로 덮지 않고, 바로 디스크에 쓰고(commit),
    // 앱 전용 파일에 사본을 둔다. 가계부가 키를 거절해도(401) 키는 그대로 둔다 (사이트 쪽 문제일 수 있으므로).

    String appKey() {
        String k = p.getString("app_key", "");
        if (k.isEmpty()) {
            k = readKeyFile();
            if (!k.isEmpty()) p.edit().putString("app_key", k).commit(); // 사본에서 되살림
        }
        return k;
    }

    /** 새 키 저장. 빈 값이면 아무것도 바꾸지 않고 false. */
    boolean setAppKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return false;
        boolean ok = p.edit().putString("app_key", k).putLong("app_key_at", System.currentTimeMillis())
                .putInt("auth_errors", 0).remove("key_ok_at").commit();
        writeKeyFile(k);
        return ok;
    }

    /** 사용자가 '키 지우기'를 확인했을 때만. */
    void clearAppKey() {
        p.edit().remove("app_key").remove("app_key_at").remove("key_ok_at").putInt("auth_errors", 0).commit();
        //noinspection ResultOfMethodCallIgnored
        keyFile.delete();
    }

    long appKeyAt() {
        return p.getLong("app_key_at", 0);
    }

    /** 가계부가 이 키로 요청을 받아 준 마지막 시각. */
    long keyOkAt() {
        return p.getLong("key_ok_at", 0);
    }

    void noteKeyOk() {
        p.edit().putLong("key_ok_at", System.currentTimeMillis()).putInt("auth_errors", 0).apply();
    }

    private String readKeyFile() {
        if (!keyFile.isFile() || keyFile.length() > 4096) return "";
        try (FileInputStream in = new FileInputStream(keyFile)) {
            byte[] b = new byte[(int) keyFile.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void writeKeyFile(String k) {
        File tmp = new File(keyFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(k.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(keyFile); // 한 번에 바꿔 끼운다 (쓰다 끊겨도 반쪽 키가 남지 않게)
    }

    int authErrors() {
        return p.getInt("auth_errors", 0);
    }

    void setAuthErrors(int n) {
        p.edit().putInt("auth_errors", n).apply();
    }

    // ---------- 사이트 연동 파일 (/urlapp.json) ----------

    synchronized void saveLink(String json, boolean found) {
        p.edit().putString("link", found ? json : null).putLong("link_checked", System.currentTimeMillis())
                .putBoolean("link_found", found).apply();
    }

    String link() {
        return p.getString("link", null);
    }

    long linkChecked() {
        return p.getLong("link_checked", 0);
    }

    /** 사이트가 요청을 거절한 횟수 (사이트가 바뀌어 연동이 끊겼을 때). 성공하면 0. */
    int linkErrors() {
        return p.getInt("link_errors", 0);
    }

    void setLinkErrors(int n, String why) {
        p.edit().putInt("link_errors", n).putString("link_error_why", why).apply();
    }

    String linkErrorWhy() {
        return p.getString("link_error_why", "");
    }

    long linkAlertAt() {
        return p.getLong("link_alert_at", 0);
    }

    void setLinkAlertAt(long t) {
        p.edit().putLong("link_alert_at", t).apply();
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
