package app.jarvis.urlapp;

// 카드 자동 등록의 중심. 알림 읽기(CardListenerService)가 넘긴 결제를 45초 모아 같은 결제(월렛·카드사 앱·문자)를
// 하나로 합치고, 분류를 정해 가계부에 바로 등록한다. 인터넷이 안 되면 모아 두었다가 다시 보낸다.
// 등록·되돌리기·승인취소 결과는 알림으로 알려 준다 ("되돌리기" 단추).
// 설정: assets/urlapp-cards.json (사이트별: 담당자 목록, 주소, 분류 규칙). 주소의 사이트: assets/urlapp.json 의 url.

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
import android.os.Handler;
import android.os.Looper;
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
    private static final long HOLD_MS = 45_000;
    private static final int RETRY_JOB = 7101;

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
    private static final Pattern CARD_SENDER = Pattern.compile("현대카드|삼성카드|롯데카드|농협카드|NH농협|신한카드");

    private static CardEngine instance;

    final Context app;
    final CardStore store;
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject cfg;
    private final LedgerClient client;
    private final List<String> owners = new ArrayList<>();
    private final List<CardCategorizer.Rule> rules = new ArrayList<>();
    private final String[] fallback;
    private CardCategorizer categorizer;
    private final List<CardParser.Txn> holding = new ArrayList<>();

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
        JSONObject c;
        String base;
        try {
            c = new JSONObject(asset(app, CONFIG));
            Uri u = Uri.parse(new JSONObject(asset(app, "urlapp.json")).getString("url"));
            base = u.getScheme() + "://" + u.getEncodedAuthority();
        } catch (Exception e) {
            throw new IllegalStateException("카드 설정을 읽을 수 없습니다", e);
        }
        cfg = c;
        client = new LedgerClient(base, c);
        JSONArray o = c.optJSONArray("owners");
        for (int i = 0; o != null && i < o.length(); i++) owners.add(o.optString(i));
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

    /** 어느 앱의 알림을 믿을지. 카카오톡은 보낸 쪽(제목)이 카드사 채널일 때만 (친구 대화로 등록되지 않게). */
    boolean trusted(String pkg, CharSequence title) {
        if (store.allowed(pkg)) return true;
        if ("com.kakao.talk".equals(pkg)) return title != null && CARD_SENDER.matcher(title).find();
        for (String m : MESSENGERS) if (m.equals(pkg)) return false;
        for (String k : KNOWN) if (k.equals(pkg)) return true;
        String low = pkg.toLowerCase(Locale.ROOT);
        for (String t : TOKENS) if (low.contains(t)) return true;
        return false;
    }

    // ---------- 받기 → 모으기 → 처리 ----------

    synchronized void offer(CardParser.Txn t) {
        for (int i = 0; i < holding.size(); i++) {
            if (CardParser.sameTxn(holding.get(i), t)) {
                holding.set(i, CardParser.richer(holding.get(i), t));
                return;
            }
        }
        if (store.recentlyHandled(t)) return;
        holding.add(t);
        main.postDelayed(() -> release(t), HOLD_MS);
    }

    private void release(CardParser.Txn first) {
        CardParser.Txn chosen = null;
        synchronized (this) {
            for (int i = 0; i < holding.size(); i++) {
                if (CardParser.sameTxn(holding.get(i), first)) {
                    chosen = holding.remove(i);
                    break;
                }
            }
        }
        if (chosen == null) return;
        final CardParser.Txn t = chosen;
        exec.execute(() -> {
            if (t.cancel) processCancel(t);
            else processApprove(t);
        });
    }

    private void processApprove(CardParser.Txn t) {
        String owner = store.owner();
        if (owner == null) return;
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
                    t.card + " " + won(t.amount) + (t.merchant.isEmpty() ? "" : " · " + t.merchant)
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

    /** 한 건 보내기. 실패하면 대기로 두고 인터넷이 될 때 다시. */
    private void send(JSONObject e) {
        Map<String, Object> v = new HashMap<>();
        v.put("date", e.optString("date"));
        v.put("large", e.optString("large"));
        v.put("medium", e.optString("medium"));
        v.put("merchant", e.optString("merchant"));
        v.put("amount", String.valueOf(e.optLong("amount")));
        v.put("owner", e.optString("owner"));
        v.put("card", e.optString("card"));
        boolean ok;
        try {
            ok = client.add(v);
        } catch (Exception ex) {
            ok = false;
        }
        if (ok) {
            put(e, "status", CardStore.REGISTERED);
            store.save(e);
            notify(e.optString("id"), "가계부에 등록했어요",
                    e.optString("merchant") + " " + won(e.optLong("amount")) + " · " + e.optString("large")
                            + (e.optString("medium").isEmpty() ? "" : "›" + e.optString("medium"))
                            + " (" + e.optString("card") + ", " + e.optString("owner") + ")", e.optString("id"));
        } else {
            put(e, "status", CardStore.PENDING);
            put(e, "tries", e.optInt("tries") + 1);
            store.save(e);
            scheduleRetry();
        }
    }

    /** 대기 중인 건 다시 보내기 (앱을 열 때, 인터넷이 돌아올 때). */
    void flush() {
        for (JSONObject e : store.pending()) send(e);
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
        return client.deleteMatching(e.optString("date"), e.optString("merchant"), e.optLong("amount"),
                e.optString("owner"), e.optString("card"));
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
