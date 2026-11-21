package app.jarvis.urlapp;

// 앱 알림 (--notify 로 만든 앱). 앱이 꺼져 있어도 약 15분마다(안드로이드가 정하는 때, 절전 중이면 더 늦게)
// 사이트의 /urlapp-notify.json 을 앱과 같은 로그인 쿠키로 읽어, 본 적 없는 알림을 폰 알림으로 띄운다 (NotifyFeed).
// 알림을 누르면 앱이 열리고, 알림에 경로(open)가 있으면 그 페이지로 간다. 폰을 다시 켜도 이어서 돈다 (setPersisted).
// 앱이 열려 있을 때는 사이트가 웹 알림(new Notification)을 쓰면 바로 뜬다 (notify.js → show()).

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

public class NotifyJob extends JobService {
    static final int JOB = 7301;
    static final String PREFS = "urlapp_notify";
    static final String CHANNEL = "urlapp_site";
    private static final long EVERY_MS = 15 * 60_000L; // 안드로이드가 허락하는 가장 짧은 주기

    private static JSONObject cfg;

    static JSONObject config(Context ctx) {
        if (cfg != null) return cfg;
        try (InputStream in = ctx.getAssets().open("urlapp.json")) {
            cfg = new JSONObject(new String(readAll(in, 1_000_000), StandardCharsets.UTF_8));
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        return cfg;
    }

    static boolean enabled(Context ctx) {
        return config(ctx).optBoolean("notify", false);
    }

    /** 15분마다 확인하기 (이미 걸려 있으면 그대로). */
    static void schedule(Context ctx) {
        if (!enabled(ctx)) return;
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB) return;
        Jobs.safe(js, new JobInfo.Builder(JOB, new ComponentName(ctx, NotifyJob.class))
                .setPeriodic(EVERY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        Context app = getApplicationContext();
        new Thread(() -> {
            check(app);
            HotwordService.remind(app, false); // 켜 둔 '하이 자비스'가 꺼져 있으면 '다시 켜기' 알림 (6시간에 한 번)
            // 위젯 스크립트가 있는 앱은 그 스크립트도 돌린다 (보이지 않는 웹 화면, 45초 안에 끝남): 위젯을 새로 고치고,
            // 스크립트가 돌려준 notify 도 알림으로 띄운다 — 화면이 꺼져 있어도 15분마다.
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                if (config(app).optInt("widget_refresh_min", 0) > 0 || hasWidget(app)) {
                    WidgetRefreshJob.runNow(app, 45_000, () -> jobFinished(p, false));
                } else {
                    jobFinished(p, false);
                }
            });
        }, "urlapp-notify").start();
        return true;
    }

    private static boolean hasWidget(Context ctx) {
        try (InputStream in = ctx.getAssets().open("urlapp-widget.js")) {
            return in.read() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return true; // 다음 주기에 다시
    }

    /** 사이트의 알림 목록을 읽어 새 알림을 띄운다 (백그라운드 스레드). 결과를 한 줄로 남긴다 (설정·상태 보기용). */
    static String check(Context ctx) {
        String result;
        try {
            Uri start = Uri.parse(config(ctx).optString("url", ""));
            String scheme = start.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return "주소 없음";
            String origin = scheme + "://" + start.getEncodedAuthority();
            HttpURLConnection c = (HttpURLConnection) new URL(origin + "/urlapp-notify.json").openConnection();
            try {
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false); // 로그인 화면 등 다른 곳으로 넘기면 읽지 않는다
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                try {
                    String cookie = CookieManager.getInstance().getCookie(origin + "/");
                    if (cookie != null) c.setRequestProperty("Cookie", cookie);
                    c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx));
                } catch (RuntimeException ignored) {
                    // 웹 화면 구성 요소를 쓸 수 없는 때 (업데이트 중 등)
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    result = code == 401 || code == 403 ? "로그인 필요 (" + code + ")" : "HTTP " + code;
                } else {
                    String body;
                    try (InputStream in = c.getInputStream()) {
                        body = new String(readAll(in, 200_000), StandardCharsets.UTF_8);
                    }
                    result = apply(ctx, body, "site");
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            result = "연결 안 됨 (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last", new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(new Date()) + " " + result)
                .apply();
        return result;
    }

    /** 받은 목록에서 새 알림을 띄우고 '본 것'을 저장한다.
     *  source: "site"(사이트의 /urlapp-notify.json) 또는 "widget"(위젯 스크립트의 notify). 처음 읽었는지는 출처마다 따로 센다 —
     *  한쪽이 이미 읽혔다고 다른 쪽의 옛 알림이 한꺼번에 뜨지 않게. */
    static synchronized String apply(Context ctx, String body, String source) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = "primed_" + source;
        boolean primed = p.getBoolean(key, "widget".equals(source) && p.getBoolean("primed", false));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString("seen", "[]"));
            for (int i = 0; i < a.length(); i++) seen.add(a.optString(i));
        } catch (Exception ignored) {
            // 처음
        }
        NotifyFeed.Result r = NotifyFeed.read(body, seen, primed, 5);
        for (NotifyFeed.Item it : r.show) show(ctx, "n:" + it.id, it.title, it.body, it.open);
        if (r.skipped > 0) {
            show(ctx, "n:more", "", "새 알림이 " + r.skipped + "개 더 있어요. 앱에서 보세요.", "");
        }
        seen.addAll(r.ids);
        ArrayList<String> keep = new ArrayList<>(seen);
        if (keep.size() > 300) keep = new ArrayList<>(keep.subList(keep.size() - 300, keep.size()));
        p.edit().putBoolean(key, true).putString("seen", new JSONArray(keep).toString()).apply();
        return primed ? "새 알림 " + (r.show.size() + r.skipped) + "개" : "처음 확인 (이전 알림 " + r.ids.size() + "개는 띄우지 않음)";
    }

    /** 폰 알림 하나. key 가 같으면 바꿔 쓴다. path 는 같은 사이트 안 경로(없으면 처음 화면). 알림 권한이 없으면 띄우지 않는다. */
    static boolean show(Context ctx, String key, String title, String body, String path) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        String appName = ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()).toString();
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, appName + " 알림", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_OPEN)
                .putExtra("path", NotifyFeed.safePath(path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, key.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = ctx.getResources().getIdentifier("urlapp_notify", "drawable", ctx.getPackageName());
        if (icon == 0) icon = android.R.drawable.stat_notify_chat;
        @SuppressWarnings("deprecation") // Notification.Builder(Context): 안드로이드 8 미만
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        String t = title == null || title.isEmpty() ? appName : title;
        String text = body == null ? "" : body;
        b.setSmallIcon(icon)
                .setContentTitle(t)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(pi);
        int accent = ctx.getResources().getIdentifier("widget_accent", "color", ctx.getPackageName());
        try {
            if (accent != 0) b.setColor(ctx.getColor(accent));
        } catch (RuntimeException ignored) {
            // 색이 없는 앱
        }
        nm.notify(CHANNEL, key.hashCode(), b.build());
        return true;
    }

    static void cancel(Context ctx, String key) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CHANNEL, key.hashCode());
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new java.io.IOException("too big");
        }
        return out.toByteArray();
    }
}
