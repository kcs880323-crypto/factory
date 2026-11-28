package app.jarvis.urlapp;

// 네이티브 알람(--alarms): 사이트의 /urlapp-alarms.json 을 앱과 같은 로그인 쿠키로 읽어 AlarmManager 에 정각 예약한다.
// 읽는 때: 앱을 열 때·15분 확인 작업(NotifyJob)·폰을 다시 켠 뒤·시각/시간대가 바뀐 뒤·사이트가 window.urlappSyncAlarms() 를 부를 때·
// 알람을 끄거나 미룬 뒤. setAlarmClock(시계 앱과 같은 방식 — 절전 중에도 정확한 시각, 상태 표시줄에 알람 표시)으로 걸고,
// 목록에서 빠진 알람은 취소한다. 로그인이 풀렸거나(401) 연결이 안 되면 지금 예약을 그대로 둔다 (알람이 사라지지 않게).
// 끄기·5분 뒤는 POST /api/alarms {"action":"dismiss"|"snooze", "id", "minutes"} 로 사이트에도 알린다 (실패해도 폰 진동은 이미 멈춤).

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AlarmSync {
    static final String PREFS = "urlapp_alarms";
    static final String ACTION_FIRE = "app.jarvis.urlapp.action.ALARM_FIRE";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private AlarmSync() {
    }

    static boolean enabled(Context ctx) {
        return NotifyJob.config(ctx).optBoolean("alarms", false);
    }

    static String origin(Context ctx) {
        Uri start = Uri.parse(NotifyJob.config(ctx).optString("url", ""));
        String scheme = start.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
        return scheme + "://" + start.getEncodedAuthority();
    }

    /** 곧 한 번 읽기 (여러 번 불러도 한 번으로 모은다 — 사이트가 알람 몇 개를 연달아 고친 뒤 등). */
    static void requestSync(Context ctx, String why, long delayMs) {
        if (!enabled(ctx)) return;
        Context app = ctx.getApplicationContext();
        if (!PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                // 바로
            }
            PENDING.set(false);
            sync(app, why);
        });
    }

    /** 사이트의 알람 목록을 읽어 예약을 맞춘다 (백그라운드 스레드에서). 결과 한 줄을 남긴다. */
    static synchronized String sync(Context ctx, String why) {
        String result;
        String origin = origin(ctx);
        if (origin == null) return "주소 없음";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(origin + "/urlapp-alarms.json").openConnection();
            try {
                prepare(ctx, c, origin);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 200) {
                    String body;
                    try (InputStream in = c.getInputStream()) {
                        body = new String(NotifyJob.readAll(in, 200_000), StandardCharsets.UTF_8);
                    }
                    List<AlarmPlan.Alarm> list = AlarmPlan.parse(body, System.currentTimeMillis());
                    if (list == null) {
                        result = "알람 목록 모양이 달라요";
                    } else {
                        int exact = apply(ctx, list);
                        result = "알람 " + list.size() + "개 예약" + (exact < list.size() ? " (정확한 시각 권한 없음 — 몇 분 늦을 수 있음)" : "");
                    }
                } else {
                    result = code == 401 || code == 403 ? "로그인 필요 (" + code + ") — 예약은 그대로" : "HTTP " + code + " — 예약은 그대로";
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            result = "연결 안 됨 — 예약은 그대로";
        }
        prefs(ctx).edit().putString("last", now() + " " + why + ": " + result).apply();
        return result;
    }

    /** 새 목록으로 예약을 맞춘다: 빠진 것은 취소, 있는 것은 (다시) 건다. 정확한 시각으로 건 수를 돌려준다. */
    static synchronized int apply(Context ctx, List<AlarmPlan.Alarm> list) {
        SharedPreferences p = prefs(ctx);
        List<String> before = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("ids", "[]"));
            for (int i = 0; i < a.length(); i++) before.add(a.optString(i));
        } catch (Exception ignored) {
            // 처음
        }
        for (String id : AlarmPlan.removed(before, list)) cancel(ctx, AlarmPlan.requestCode(id));
        int exact = 0;
        JSONArray ids = new JSONArray();
        for (AlarmPlan.Alarm a : list) {
            if (schedule(ctx, a.id, a.label, a.fireMs, false)) exact++;
            ids.put(a.id);
        }
        p.edit().putString("list", AlarmPlan.toArray(list).toString()).putString("ids", ids.toString()).apply();
        return exact;
    }

    /** 폰을 다시 켠 뒤·시각이 바뀐 뒤: 저장해 둔 목록과 '5분 뒤'를 다시 건다 (인터넷 없이). */
    static synchronized void reschedule(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        try {
            for (AlarmPlan.Alarm a : AlarmPlan.fromArray(new JSONArray(p.getString("list", "[]")), now)) {
                schedule(ctx, a.id, a.label, a.fireMs, false);
            }
        } catch (Exception ignored) {
            // 저장한 것 없음
        }
        JSONObject sn = snoozes(p);
        for (Iterator<String> it = sn.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject o = sn.optJSONObject(id);
            if (o != null && o.optLong("until", 0) > now) schedule(ctx, id, o.optString("label", "알람"), o.optLong("until"), true);
        }
    }

    /** 알람 하나를 건다. 정확한 시각(setAlarmClock)으로 걸었으면 true, 권한이 없어 대략으로 걸었으면 false. */
    static boolean schedule(Context ctx, String id, String label, long fireMs, boolean snooze) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        PendingIntent op = PendingIntent.getBroadcast(ctx, snooze ? AlarmPlan.snoozeCode(id) : AlarmPlan.requestCode(id),
                fireIntent(ctx).putExtra("id", id).putExtra("label", label).putExtra("fire_ms", fireMs).putExtra("snooze", snooze),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (canExact) {
            try {
                PendingIntent show = PendingIntent.getActivity(ctx, 7402, new Intent(ctx, MainActivity.class)
                                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(fireMs, show), op);
                return true;
            } catch (SecurityException e) {
                // 아래 대략 예약으로
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMs, op); // 정확한 시각 권한이 없을 때 (안드로이드 12 에서 꺼 둔 경우)
        return false;
    }

    static void cancel(Context ctx, int code) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, code, fireIntent(ctx),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pi == null) return;
        if (am != null) am.cancel(pi);
        pi.cancel();
    }

    private static Intent fireIntent(Context ctx) {
        return new Intent(ctx, AlarmReceiver.class).setAction(ACTION_FIRE);
    }

    /** '5분 뒤 다시': 폰에 다시 걸고 기억한다 (재부팅해도 남게). 다시 울릴 시각을 돌려준다. */
    static synchronized long snooze(Context ctx, String id, String label, int minutes) {
        long until = System.currentTimeMillis() + minutes * 60_000L;
        schedule(ctx, id, label, until, true);
        SharedPreferences p = prefs(ctx);
        JSONObject sn = snoozes(p);
        try {
            sn.put(id, new JSONObject().put("label", label).put("until", until));
        } catch (Exception ignored) {
            // 넣을 수 없는 값 없음
        }
        p.edit().putString("snoozes", sn.toString()).apply();
        return until;
    }

    static synchronized void clearSnooze(Context ctx, String id) {
        cancel(ctx, AlarmPlan.snoozeCode(id));
        SharedPreferences p = prefs(ctx);
        JSONObject sn = snoozes(p);
        sn.remove(id);
        p.edit().putString("snoozes", sn.toString()).apply();
    }

    /** 미룬 알람이 울렸다: 기록만 지운다 (예약은 이미 쓰였다). */
    static synchronized void forgetSnooze(Context ctx, String id) {
        SharedPreferences p = prefs(ctx);
        JSONObject sn = snoozes(p);
        sn.remove(id);
        p.edit().putString("snoozes", sn.toString()).apply();
    }

    private static JSONObject snoozes(SharedPreferences p) {
        try {
            return new JSONObject(p.getString("snoozes", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 끄기·미루기를 사이트에도 알린다 (뒤에서, 실패해도 그만). */
    static void post(Context ctx, String action, String id, int minutes) {
        Context app = ctx.getApplicationContext();
        WORKER.execute(() -> {
            String origin = origin(app);
            if (origin == null) return;
            String result;
            try {
                JSONObject body = new JSONObject().put("action", action).put("id", id);
                if (minutes > 0) body.put("minutes", minutes);
                HttpURLConnection c = (HttpURLConnection) new URL(origin + "/api/alarms").openConnection();
                try {
                    prepare(app, c, origin);
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Origin", origin); // 사이트의 '같은 출처' 검사
                    byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(b);
                    }
                    result = "HTTP " + c.getResponseCode();
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                result = "연결 안 됨";
            }
            prefs(app).edit().putString("last_post", now() + " " + action + " " + result).apply();
        });
    }

    private static void prepare(Context ctx, HttpURLConnection c, String origin) {
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(false); // 로그인 화면 등 다른 곳으로 넘기면 읽지 않는다
        c.setUseCaches(false);
        try {
            String cookie = CookieManager.getInstance().getCookie(origin + "/");
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx));
        } catch (RuntimeException ignored) {
            // 웹 화면 구성 요소를 쓸 수 없는 때 (업데이트 중 등)
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String now() {
        return new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(new Date());
    }
}
