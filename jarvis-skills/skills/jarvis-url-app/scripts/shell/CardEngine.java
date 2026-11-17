package app.jarvis.urlapp;

// 카드 자동 등록의 중심. 카드 결제 알림(MacroDroid 가 보내 주거나, 알림 듣기 서비스가 받은 것)을 45초 모아
// 같은 결제(월렛·카드사 앱·문자)를 하나로 합치고, 분류를 정해 가계부에 바로 등록한다.
// 모아 두기·다시 보내기는 저장소 + JobScheduler 로 해서 앱이 꺼져도 이어진다.
// 등록·되돌리기·승인취소 결과는 알림으로 알려 준다 ("되돌리기" 단추).
// 설정: assets/urlapp-cards.json (앱 기본값) 위에 사이트의 /urlapp.json "cards" 를 덮는다 (사이트를 고쳐도 연동 유지).

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

final class CardEngine {
    static final String CONFIG = "urlapp-cards.json";
    static final String ACTION_UNDO = "app.jarvis.urlapp.action.CARD_UNDO";
    private static final String CHANNEL = "urlapp_cards";
    static final int RETRY_JOB = 7101;
    static final int HOLD_JOB = 7102;

    // 카드사·월렛·문자 앱 (확인한 것과 흔히 알려진 것). 그 밖의 앱은 설정 화면에서 사용자가 허용한다.
    private static final String[] KNOWN = {
            "com.hyundaicard.appcard", "kr.co.samsungcard.mpocket", "com.lcacApp", "nh.smart.card",
            "nh.smart.nhallonepay", "com.shcard.smartpay", "com.shinhancard.smartshinhan",
            "com.samsung.android.spay", "com.samsung.android.messaging", "com.google.android.apps.messaging",
            "com.android.mms", "com.android.messaging",
    };
    private static final String[] TOKENS = {
            "card", "spay", "wallet", "messaging", ".mms", "sms", "lcac", "nonghyup", "nhallonepay", "shinhan",
    };
    private static final String[] MESSENGERS = {
            "com.kakao.talk", "jp.naver.line.android", "org.telegram.messenger", "com.facebook.orca",
            "com.instagram.android", "com.discord", "com.whatsapp", "com.nhn.android.band", "com.kakao.story",
    };

    private static final String[] MESSENGER_NAMES = {
            "line", "라인", "telegram", "텔레그램", "messenger", "메신저", "instagram", "인스타그램", "band", "밴드",
            "discord", "디스코드", "whatsapp", "왓츠앱",
    };

    private static CardEngine instance;

    final Context app;
    final CardStore store;
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final JSONObject builtin;
    private final String base;
    private volatile JSONObject cfg;
    private volatile LedgerClient client;
    private final List<String> owners = new ArrayList<>();
    private final List<CardCategorizer.Rule> rules = new ArrayList<>();
    private String[] fallback = {"기타", ""};
    private CardCategorizer categorizer;

    static synchronized CardEngine get(Context c) {
        if (instance == null) instance = new CardEngine(c.getApplicationContext());
        return instance;
    }

    /** 이 앱에 카드 자동 등록이 들어 있는지 (빌드할 때 --cards 를 준 앱만). */
    static boolean available(Context c) {
        try (InputStream in = c.getAssets().open(CONFIG)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    private CardEngine(Context app) {
        this.app = app;
        this.store = new CardStore(app);
        try {
            builtin = new JSONObject(asset(app, CONFIG));
            Uri u = Uri.parse(new JSONObject(asset(app, "urlapp.json")).getString("url"));
            base = u.getScheme() + "://" + u.getEncodedAuthority();
        } catch (Exception e) {
            throw new IllegalStateException("카드 설정을 읽을 수 없습니다", e);
        }
        apply(LinkConfig.merge(builtin, store.link()));
    }

    /** 설정을 적용한다 (앱 기본값 + 사이트 연동 파일). */
    private synchronized void apply(JSONObject c) {
        cfg = c;
        client = newClient(c);
        owners.clear();
        JSONArray o = c.optJSONArray("owners");
        for (int i = 0; o != null && i < o.length(); i++) owners.add(o.optString(i));
        rules.clear();
        JSONArray r = c.optJSONArray("rules");
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject x = r.optJSONObject(i);
            if (x == null) continue;
            JSONArray k = x.optJSONArray("keywords");
            String[] kw = new String[k == null ? 0 : k.length()];
            for (int j = 0; j < kw.length; j++) kw[j] = k.optString(j);
            rules.add(new CardCategorizer.Rule(x.optString("large"), x.optString("medium"), kw));
        }
        JSONArray d = c.optJSONArray("default_category");
        fallback = new String[] {d != null ? d.optString(0, "기타") : "기타", d != null ? d.optString(1, "") : ""};
        rebuildCategorizer();
    }

    /** 앱 연동 키를 붙인 클라이언트. 키 이름은 사이트 연동 파일의 auth.header (없으면 X-App-Key). 키가 없으면 붙이지 않는다. */
    private LedgerClient newClient(JSONObject c) {
        JSONObject a = c.optJSONObject("auth");
        String header = a == null ? "X-App-Key" : a.optString("header", "X-App-Key");
        return new LedgerClient(base, c).withAuth(header, store.appKey());
    }

    /** 설정 화면에서 키를 바꾸면 바로 적용. */
    void setAppKey(String key) {
        store.setAppKey(key);
        apply(cfg);
    }

    boolean authRequired() {
        return cfg.optJSONObject("auth") != null;
    }

    /** 카드 알림을 어디서 받는지: "intent" = MacroDroid 가 보내 줌, "listener" = 이 앱이 직접 읽음. */
    String via() {
        return builtin.optString("via", "listener");
    }

    boolean fromSite() {
        return cfg.optBoolean("_from_site", false);
    }

    String base() {
        return base;
    }

    /** 사이트의 /urlapp.json 을 다시 읽는다. 있으면 그 설정을, 없으면 앱 기본값을 쓴다. 연결 실패면 그대로. */
    boolean refreshLink() {
        try {
            String text = newClient(builtin).fetchText("/urlapp.json");
            if (text != null && text.contains("\"cards\"")) {
                store.saveLink(text, true);
                apply(LinkConfig.merge(builtin, text));
                return true;
            }
            store.saveLink(null, false);
            apply(LinkConfig.merge(builtin, null));
            return false;
        } catch (Exception e) {
            return fromSite();
        }
    }

    private void ensureLink(long maxAgeMs) {
        if (System.currentTimeMillis() - store.linkChecked() > maxAgeMs) refreshLink();
    }

    static String asset(Context c, String name) throws java.io.IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    List<String> owners() {
        return owners;
    }

    /** 담당자를 고르고 켜 둔 상태여야 등록한다. */
    boolean ready() {
        return store.enabled() && store.owner() != null;
    }

    boolean listenerEnabled() {
        String flat = Settings.Secure.getString(app.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(app, CardListenerService.class).flattenToString());
    }

    /** 어느 앱의 알림을 믿을지. 카카오톡은 보낸 쪽(제목)이 카드영수증·카드사 채널일 때만 (친구 대화로 등록되지 않게). */
    boolean trusted(String pkg, CharSequence title) {
        if ("com.kakao.talk".equals(pkg)) return CardParser.cardSender(title); // '허용'을 눌러도 친구 대화는 쓰지 않는다
        if (store.allowed(pkg)) return true;
        for (String m : MESSENGERS) if (m.equals(pkg)) return false;
        for (String k : KNOWN) if (k.equals(pkg)) return true;
        String low = pkg.toLowerCase(Locale.ROOT);
        for (String t : TOKENS) if (low.contains(t)) return true;
        return false;
    }

    /** MacroDroid 가 보낸 앱 이름(또는 패키지)을 믿을지. 패키지면 trusted(), 이름이면 메신저만 거른다. */
    boolean trustedSource(String app, CharSequence title) {
        if (app == null || app.trim().isEmpty()) return true; // MacroDroid 에서 사용자가 고른 앱들
        String a = app.trim();
        if (a.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return trusted(a, title);
        String low = a.toLowerCase(Locale.ROOT);
        if (low.contains("카카오") || low.contains("kakao")) return CardParser.cardSender(title);
        for (String m : MESSENGER_NAMES) if (low.equals(m) || (m.length() > 2 && low.contains(m))) return false;
        return true;
    }

    // ---------- 받기 → 모으기 → 처리 ----------

    /** 알림 한 건 (알림 듣기 서비스 또는 MacroDroid). 카드 결제로 읽혀 모아 두면 true. */
    boolean handleNotification(String app, String title, String body, long when, boolean appIsPackage) {
        return handleNotification(app, title, body, when, appIsPackage, appIsPackage ? null : app);
    }

    /** appName: 보낸 앱의 이름 (예: "롯데카드"). 문구에 카드사 이름이 없는 카드사 앱 알림의 카드사를 정하는 데 쓴다. */
    boolean handleNotification(String app, String title, String body, long when, boolean appIsPackage, String appName) {
        CardParser.Txn t = CardParser.parse(title, body, when, app, appName);
        if (t == null) return false;
        boolean ok = appIsPackage ? trusted(app, title) : trustedSource(app, title);
        if (!ok) {
            // 카카오톡은 '허용'해도 친구 대화는 쓰지 않으므로(카드영수증·카드사 채널만) 목록에 올리지 않는다
            if (!"com.kakao.talk".equals(app)) store.noteDetected(app);
            return false;
        }
        offer(t);
        return true;
    }

    /** 같은 결제가 여러 곳에서 오므로 바로 등록하지 않고 저장해 두었다가 45초 뒤 한꺼번에 처리한다. */
    void offer(CardParser.Txn t) {
        if (store.recentlyHandled(t)) return;
        store.addHolding(t);
        scheduleHold();
    }

    /** 모아 둔 결제 처리 (작업 스케줄러가 부른다). 받은 지 40초가 지난 것만, 같은 결제는 하나로. */
    void processHolding() {
        List<CardParser.Txn> items = store.takeHolding(40_000);
        List<CardParser.Txn> merged = new ArrayList<>();
        outer:
        for (CardParser.Txn t : items) {
            for (int i = 0; i < merged.size(); i++) {
                if (CardParser.sameTxn(merged.get(i), t)) {
                    merged.set(i, CardParser.richer(merged.get(i), t));
                    continue outer;
                }
            }
            merged.add(t);
        }
        for (CardParser.Txn t : merged) {
            if (store.recentlyHandled(t)) continue;
            if (t.cancel) processCancel(t);
            else processApprove(t);
        }
        if (store.hasHolding()) scheduleHold();
    }

    private void processApprove(CardParser.Txn t) {
        String owner = store.owner();
        if (owner == null) return;
        ensureLink(6 * 3600 * 1000L);
        ensureLearned();
        CardCategorizer.Pick pick = categorizer.pick(t.merchant);
        JSONObject e = store.newEntry(t, pick, owner);
        send(e);
    }

    private void processCancel(CardParser.Txn t) {
        store.noteCancel(t);
        JSONObject e = store.findForCancel(t);
        if (e == null) {
            notify("cancel-" + t.when, "카드 결제 취소 알림",
                    t.label() + " " + won(t.amount) + (t.merchant.isEmpty() ? "" : " · " + t.merchant)
                            + " — 가계부에서 같은 내역을 찾지 못했어요. 직접 확인해 주세요.", null);
            return;
        }
        try {
            boolean done = deleteEntry(e);
            put(e, "status", done ? CardStore.CANCELLED : e.optString("status"));
            store.save(e);
            notify(e.optString("id"), done ? "결제 취소를 반영했어요" : "결제 취소 알림",
                    e.optString("merchant") + " " + won(e.optLong("amount"))
                            + (done ? " — 가계부에서 지웠어요" : " — 가계부에서 찾지 못했어요. 직접 확인해 주세요."), null);
        } catch (Exception ex) {
            notify(e.optString("id"), "결제 취소를 반영하지 못했어요",
                    e.optString("merchant") + " " + won(e.optLong("amount")) + " — 인터넷 연결을 확인한 뒤 가계부에서 지워 주세요.", null);
        }
    }

    /** 한 건 보내기. 실패하면 대기로 두고(이유를 적어 둔다) 인터넷이 될 때 다시. 보냈으면 true. */
    private boolean send(JSONObject e) {
        Map<String, Object> v = new HashMap<>();
        v.put("date", e.optString("date"));
        v.put("large", e.optString("large"));
        v.put("medium", e.optString("medium"));
        v.put("merchant", e.optString("merchant"));
        v.put("amount", String.valueOf(e.optLong("amount")));
        v.put("owner", e.optString("owner"));
        v.put("card", CardStore.memo(e));
        boolean ok;
        LedgerClient c = client;
        String why = "";
        try {
            ok = c.add(v);
        } catch (Exception ex) {
            ok = false;
            why = ex.getClass().getSimpleName();
        }
        int code = c.lastCode();
        if (!ok) {
            put(e, "error", c.lastReason().isEmpty() ? (why.isEmpty() ? "HTTP " + code : why) : c.lastReason());
            put(e, "code", code);
        }
        if (!ok && (code == 401 || code == 403)) { // 사이트에 보안을 켰는데 앱 연동 키가 없거나 틀림
            put(e, "status", CardStore.PENDING);
            put(e, "tries", e.optInt("tries") + 1);
            store.save(e);
            int n = store.authErrors() + 1;
            store.setAuthErrors(n);
            if (System.currentTimeMillis() - store.linkAlertAt() > 6 * 3600 * 1000L) {
                store.setLinkAlertAt(System.currentTimeMillis());
                notify("auth-needed", "가계부 앱 연동 키를 확인해 주세요",
                        "가계부가 등록을 거절했어요 (HTTP " + code + "). 사이트에 보안을 켰다면, 사이트 설정에서 만든 "
                                + "'앱 연동 키'를 카드 자동 등록 설정에 넣어 주세요. 결제는 모아 두었다가 보내요.", null);
            }
            return false; // 키를 넣으면(설정 화면) 바로 다시 보낸다
        }
        boolean refused = !ok && code != 0 && code < 500; // 사이트가 거절 (주소·모양이 바뀜) ↔ 0·5xx = 인터넷·서버 문제
        if (refused && System.currentTimeMillis() - store.linkChecked() > 10 * 60 * 1000L) {
            refreshLink(); // 사이트가 연동 파일을 고쳤을 수 있다 → 새 설정으로 한 번 더
            try {
                c = client;
                ok = c.add(v);
            } catch (Exception ex) {
                ok = false;
            }
            code = c.lastCode();
            refused = !ok && code != 0 && code < 500;
            if (!ok) {
                put(e, "error", c.lastReason().isEmpty() ? "HTTP " + code : c.lastReason());
                put(e, "code", code);
            }
        }
        if (ok) {
            e.remove("error");
            e.remove("code");
            if (store.linkErrors() > 0) store.setLinkErrors(0, "");
            if (store.authErrors() > 0) store.setAuthErrors(0);
            put(e, "status", CardStore.REGISTERED);
            store.save(e);
            notify(e.optString("id"), "가계부에 등록했어요",
                    e.optString("merchant") + " " + won(e.optLong("amount")) + " · " + e.optString("large")
                            + (e.optString("medium").isEmpty() ? "" : "›" + e.optString("medium"))
                            + " (" + CardStore.memo(e) + ", " + e.optString("owner") + ")", e.optString("id"));
        } else {
            put(e, "status", CardStore.PENDING);
            put(e, "tries", e.optInt("tries") + 1);
            store.save(e);
            if (refused) {
                int n = store.linkErrors() + 1;
                store.setLinkErrors(n, "HTTP " + code);
                if (n >= 2 && System.currentTimeMillis() - store.linkAlertAt() > 6 * 3600 * 1000L) {
                    store.setLinkAlertAt(System.currentTimeMillis());
                    notify("link-broken", "가계부와 연결이 끊겼어요",
                            "가계부 사이트가 등록을 거절했어요 (HTTP " + code + "). 사이트를 고친 뒤 데이터 주소가 바뀌었을 수 "
                                    + "있어요. 결제는 모아 두었다가 연결되면 보내요. 사이트의 /urlapp.json 이 맞는지 확인해 주세요.", null);
                }
            }
            scheduleRetry();
        }
        return ok;
    }

    /** 대기 중인 건 다시 보내기 (앱을 열 때, 인터넷이 돌아올 때). 결과를 한 줄로 돌려준다 (설정 화면이 보여 줌). */
    String flush() {
        List<JSONObject> pending = store.pending();
        if (pending.isEmpty()) return "보낼 건이 없어요.";
        if (store.linkErrors() > 0) ensureLink(10 * 60 * 1000L);
        int sent = 0;
        JSONObject failed = null;
        for (JSONObject e : pending) {
            if (send(e)) sent++;
            else failed = e;
        }
        if (failed == null) return sent + "건을 가계부에 보냈어요.";
        return (sent > 0 ? sent + "건은 보냈지만 " : "") + "보내지 못했어요: " + failHint(failed.optInt("code"))
                + " (" + failed.optString("error") + ")";
    }

    /** 실패 코드를 사람이 할 일로. */
    static String failHint(int code) {
        if (code == 0) return "인터넷이나 사이트에 연결하지 못했어요";
        if (code == 401 || code == 403) return "가계부가 앱 연동 키를 받지 않았어요. 사이트에서 만든 키를 다시 붙여 넣어 주세요";
        if (code == 404 || code == 405) return "가계부의 주소가 바뀌었어요. 사이트의 /urlapp.json 을 확인해 주세요";
        if (code == 429) return "요청이 너무 많다고 해요. 잠시 뒤 다시 보내요";
        if (code >= 500) return "가계부 서버에서 오류가 났어요";
        if (code >= 200 && code < 300) return "가계부가 실패라고 답했어요";
        return "가계부가 내용을 거절했어요";
    }

    /** 마지막 요청의 실패 이유 (다시 배우기 결과 등에 쓴다). */
    String lastReason() {
        return client.lastReason();
    }

    void flushAsync() {
        exec.execute(this::flush);
    }

    boolean hasPending() {
        return !store.pending().isEmpty();
    }

    /** 되돌리기: 등록된 건은 가계부에서 지우고, 대기 중인 건은 보내지 않는다. 결과 문구를 돌려준다. */
    String undo(String id) {
        JSONObject e = store.find(id);
        if (e == null) return "기록을 찾지 못했어요.";
        String status = e.optString("status");
        if (CardStore.PENDING.equals(status)) {
            put(e, "status", CardStore.UNDONE);
            store.save(e);
            cancelNotice(id);
            return "보내지 않도록 했어요.";
        }
        if (!CardStore.REGISTERED.equals(status)) return "이미 처리된 기록이에요.";
        try {
            boolean done = deleteEntry(e);
            if (done) {
                put(e, "status", CardStore.UNDONE);
                store.save(e);
                notify(id, "등록을 되돌렸어요", e.optString("merchant") + " " + won(e.optLong("amount"))
                        + " — 가계부에서 지웠어요", null);
                return "가계부에서 지웠어요.";
            }
            return "가계부에서 같은 내역을 찾지 못했어요. 이미 지웠는지 확인해 주세요.";
        } catch (Exception ex) {
            return "인터넷 연결을 확인해 주세요.";
        }
    }

    private boolean deleteEntry(JSONObject e) throws Exception {
        ensureLink(6 * 3600 * 1000L);
        return client.deleteMatching(e.optString("date"), e.optString("merchant"), e.optLong("amount"),
                e.optString("owner"), CardStore.memo(e));
    }

    // ---------- 분류 ----------

    CardCategorizer.Pick pick(String merchant) {
        return categorizer.pick(merchant);
    }

    int learnedCount() {
        return categorizer.learnedCount();
    }

    private void rebuildCategorizer() {
        CardCategorizer c = new CardCategorizer(rules, fallback[0], fallback[1]);
        c.learn(store.learned());
        categorizer = c;
    }

    /** 지난 기록(기본 3개월)으로 분류를 배운다. 12시간에 한 번. 실패하면 규칙만 쓴다. */
    void ensureLearned() {
        if (System.currentTimeMillis() - store.learnedAt() < 12 * 3600 * 1000L) return;
        refreshLearned();
    }

    int refreshLearned() {
        int months = Math.max(1, Math.min(12, cfg.optInt("learn_months", 3)));
        JSONObject f = cfg.optJSONObject("history") == null ? null : cfg.optJSONObject("history").optJSONObject("fields");
        String kd = f == null ? "details" : f.optString("details", "details");
        String kl = f == null ? "category_large" : f.optString("large", "category_large");
        String km = f == null ? "category_medium" : f.optString("medium", "category_medium");
        List<String[]> rows = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        try {
            for (int i = 0; i < months; i++) {
                String month = String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
                for (JSONObject r : client.history(month)) {
                    String d = r.optString(kd, "").trim();
                    if (!d.isEmpty()) rows.add(new String[] {d, r.optString(kl, ""), r.optString(km, "")});
                }
                c.add(Calendar.MONTH, -1);
            }
        } catch (Exception e) {
            return -1;
        }
        store.saveLearned(rows);
        rebuildCategorizer();
        return categorizer.learnedCount();
    }

    // ---------- 알림·다시 시도 ----------

    private void scheduleHold() {
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(HOLD_JOB, new ComponentName(app, CardRetryJob.class))
                .setMinimumLatency(45_000)
                .setOverrideDeadline(150_000)
                .build());
    }

    private void scheduleRetry() {
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(RETRY_JOB, new ComponentName(app, CardRetryJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(30_000)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        js.schedule(job);
    }

    @SuppressWarnings("deprecation") // 안드로이드 8 미만용 알림 만들기
    private void notify(String key, String title, String text, String undoId) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "카드 자동 등록", NotificationManager.IMPORTANCE_LOW));
        }
        int icon = app.getResources().getIdentifier("urlapp_card_notify", "drawable", app.getPackageName());
        if (icon == 0) icon = android.R.drawable.stat_notify_more;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL)
                : new Notification.Builder(app);
        Intent open = new Intent(app, CardSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(app, 1, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (undoId != null) {
            Intent undo = new Intent(app, CardUndoReceiver.class).setAction(ACTION_UNDO).putExtra("id", undoId);
            PendingIntent pi = PendingIntent.getBroadcast(app, undoId.hashCode(), undo,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(0, "되돌리기", pi).build());
        }
        try {
            nm.notify(key.hashCode(), b.build());
        } catch (SecurityException ignored) {
            // 알림 권한이 없으면 조용히 (설정 화면에 상태가 보인다)
        }
    }

    private void cancelNotice(String key) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(key.hashCode());
    }

    static String won(long n) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(n) + "원";
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
            // 기본형만 넣는다
        }
    }
}
