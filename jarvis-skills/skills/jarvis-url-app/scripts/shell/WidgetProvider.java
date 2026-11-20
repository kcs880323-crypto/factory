package app.jarvis.urlapp;

// 홈 화면 위젯. 앱이 열려 있을 때 "위젯 스크립트"(assets/urlapp-widget.js)가 사이트에서 읽은 요약을
// MainActivity 의 통로로 넘기면 여기서 저장하고 그린다. 위젯이 직접 인터넷에 접속하지는 않는다.
// 데이터 모양: {"title": "11월 가계부", "rows": [{"label": "수입", "value": "1,234원", "tone": "pos|neg"}], "note": "…",
//              "alt": {같은 모양}}  ← alt 는 두 번째 위젯(WidgetProviderAlt, 예: '카드 사용')이 그린다
// 위젯이 없는 앱에도 이 클래스는 들어 있지만, 매니페스트에 receiver 가 없으면 쓰이지 않는다.
// 위젯 스크립트에 "// urlapp:refresh-min N" 이 있으면(설정의 widget_refresh_min) 위젯에 새로고침 단추가 생기고,
// 화면이 켜져 있는 동안 N분마다 앱을 열지 않고 새로 고친다 (WidgetRefreshJob: 보이지 않는 웹 화면).
// 단추·알람을 받으면 예약하지 않고 그 자리에서 바로 돌려(goAsync) 45초 안에 끝낸다. 아래 줄에는 늘 마지막 업데이트 시간을 보이고,
// "새로고침 중…"은 그 뒤에 덧붙인다. 앱이 도중에 꺼져도 70초 뒤 다시 그리기 알람이 "새로고침 중…"을 지운다.

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WidgetProvider extends AppWidgetProvider {
    static final String PREFS = "urlapp_widget";
    static final int MAX_ROWS = 8; // 레이아웃에 있는 줄(w_row1…)까지만 그린다 (기본 4줄, 스크립트가 정하면 8줄까지)
    static final String ACTION_REFRESH = "app.jarvis.urlapp.action.WIDGET_REFRESH"; // 위젯의 새로고침 단추
    static final String ACTION_AUTO = "app.jarvis.urlapp.action.WIDGET_AUTO";       // N분마다 (화면이 켜져 있을 때만)
    static final String ACTION_REDRAW = "app.jarvis.urlapp.action.WIDGET_REDRAW";   // 새로고침 시작 70초 뒤 다시 그리기
    static final long REFRESH_TIMEOUT_MS = 45_000;   // 이 안에 반드시 끝낸다 (방송 수신 제한 60초보다 짧게)
    static final long REFRESHING_SHOW_MS = 70_000;   // "새로고침 중…"을 보이는 최대 시간
    private static JSONObject cfg; // assets/urlapp.json (widget_refresh_min: 0 = 단추·자동 새로고침 없음, mic)

    /** 위젯 종류: "" 첫 위젯, "alt" 두 번째 위젯(저장한 값의 "alt" 를 그린다),
     *  "s2"·"s3" 첫 위젯과 같은 값을 다른 크기로 (--widget-sizes 의 두·세 번째 크기). 모양 이름은 urlapp_widget[_종류]. */
    String kind() {
        return "";
    }

    /** 이 앱에 들어 있을 수 있는 위젯 종류 (매니페스트에 없는 것은 놓인 것이 없다). */
    private static final Class<?>[] KINDS = {WidgetProvider.class, WidgetProviderAlt.class, WidgetProviderS2.class,
            WidgetProviderS3.class};
    private static final String[] KIND_NAMES = {"", "alt", "s2", "s3"};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, build(ctx, kind()));
        scheduleAuto(ctx); // 폰을 다시 켠 뒤에도 (시스템이 위젯마다 onUpdate 를 부른다)
    }

    @Override
    public void onEnabled(Context ctx) {
        scheduleAuto(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        if (!anyPlaced(ctx)) cancelAuto(ctx); // 두 종류 모두 홈 화면에서 치웠을 때만
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            noteRefreshing(ctx);
            runNow(ctx);
            return;
        }
        if (ACTION_AUTO.equals(action)) {
            refresh(ctx); // 남아 있던 "새로고침 중…"도 여기서 지워진다
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            if (screenOn && anyPlaced(ctx)) runNow(ctx); // 화면이 꺼져 있으면 건너뛴다 (배터리)
            return;
        }
        if (ACTION_REDRAW.equals(action)) {
            refresh(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** 그 자리에서 바로 새로 고친다 (작업 예약은 절전 때문에 한참 미뤄질 수 있다). 끝날 때까지 방송을 붙잡아 둔다. */
    private void runNow(Context ctx) {
        final PendingResult pr = goAsync();
        try {
            WidgetRefreshJob.runNow(ctx.getApplicationContext(), REFRESH_TIMEOUT_MS, pr::finish);
        } catch (RuntimeException e) {
            noteRefresh(ctx, false);
            pr.finish();
        }
    }

    private static JSONObject config(Context ctx) {
        if (cfg != null) return cfg;
        try (InputStream in = ctx.getAssets().open("urlapp.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            cfg = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        return cfg;
    }

    /** 설정의 widget_refresh_min (분). 없으면 0. */
    static int refreshMinutes(Context ctx) {
        return Math.max(0, config(ctx).optInt("widget_refresh_min", 0));
    }

    /** --mic 로 만든 앱이면 위젯에 마이크 단추. */
    static boolean micEnabled(Context ctx) {
        return config(ctx).optBoolean("mic", false);
    }

    private static PendingIntent autoIntent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 1, new Intent(ctx, WidgetProvider.class).setAction(ACTION_AUTO),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** N분마다 새로 고치기 (깨우지 않는 알람: 폰이 깨어 있을 때만 울린다). 위젯이 놓여 있을 때만. */
    static void scheduleAuto(Context ctx) {
        int min = refreshMinutes(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (min <= 0 || !anyPlaced(ctx)) {
            am.cancel(autoIntent(ctx));
            return;
        }
        long every = min * 60_000L;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + every, every, autoIntent(ctx));
    }

    static void cancelAuto(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(autoIntent(ctx));
    }

    private static boolean anyPlaced(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return false;
        for (Class<?> c : KINDS) {
            try {
                int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, c));
                if (ids != null && ids.length > 0) return true;
            } catch (RuntimeException ignored) {
                // 매니페스트에 없는 위젯
            }
        }
        return false;
    }

    /** 새로고침을 시작했다 (위젯 아래 줄에 "새로고침 중…"). 앱이 도중에 꺼져도 지워지도록 다시 그리기 알람을 건다. */
    static void noteRefreshing(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("refreshing_at", System.currentTimeMillis()).apply();
        refresh(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + REFRESHING_SHOW_MS + 2_000,
                    PendingIntent.getBroadcast(ctx, 3, new Intent(ctx, WidgetProvider.class).setAction(ACTION_REDRAW),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    /** 새로고침이 끝났다. 실패면 그 시각을 남긴다 (성공이면 save 가 이미 저장했다). */
    static void noteRefresh(Context ctx, boolean ok) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("refreshing_at");
        if (ok) e.remove("refresh_failed_at");
        else e.putLong("refresh_failed_at", System.currentTimeMillis());
        e.apply();
        refresh(ctx);
    }

    /** 통로에서 부른다: 받은 요약을 저장하고 홈 화면의 위젯을 모두 다시 그린다.
     *  요약에 notify(알림 목록)가 있으면 알림 기능(--notify)에 넘기고, 저장할 때는 뺀다. */
    static void save(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray notify = o.optJSONArray("notify");
            if (notify != null) {
                o.remove("notify");
                json = o.toString();
                if (NotifyJob.enabled(ctx)) NotifyJob.apply(ctx, new JSONObject().put("items", notify).toString(), "widget");
            }
        } catch (Exception ignored) {
            // 모양이 이상하면 그대로 저장 (그리기에서 걸러진다)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("data", json).putLong("at", System.currentTimeMillis()).remove("refresh_failed_at").apply();
        refresh(ctx);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        for (int i = 0; i < KINDS.length; i++) update(ctx, mgr, KINDS[i], KIND_NAMES[i]); // 이 앱에 없는 종류는 놓인 것이 없다
    }

    private static void update(Context ctx, AppWidgetManager mgr, Class<?> cls, String kind) {
        try {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, cls));
            if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, build(ctx, kind));
        } catch (RuntimeException e) {
            // 매니페스트에 없는 위젯
        }
    }

    private static int id(Resources res, String pkg, String name) {
        return res.getIdentifier(name, "id", pkg);
    }

    private static int integer(Resources res, String pkg, String name, int fallback) {
        int r = res.getIdentifier(name, "integer", pkg);
        if (r == 0) return fallback;
        try {
            return res.getInteger(r);
        } catch (Resources.NotFoundException e) {
            return fallback;
        }
    }

    private static int color(Resources res, String pkg, String name, int fallback) {
        int c = res.getIdentifier(name, "color", pkg);
        if (c == 0) return fallback;
        try {
            return res.getColor(c, null);
        } catch (Resources.NotFoundException e) {
            return fallback;
        }
    }

    static RemoteViews build(Context ctx, String kind) {
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        boolean alt = "alt".equals(kind);
        // 종류마다 자기 모양(urlapp_widget_alt·_s2·_s3, 줄 수가 다를 수 있음)으로. 없으면 첫 위젯 모양.
        String name = kind == null || kind.isEmpty() ? "urlapp_widget" : "urlapp_widget_" + kind;
        int layout = res.getIdentifier(name, "layout", pkg);
        if (layout == 0) {
            name = "urlapp_widget";
            layout = res.getIdentifier(name, "layout", pkg);
        }
        RemoteViews v = new RemoteViews(pkg, layout);
        // 그 모양에 있는 줄 수 (줄 id 는 모양들이 같이 쓰므로, 모양에 없는 줄을 건드리면 위젯이 깨진다)
        int count = integer(res, pkg, name + "_rows", MAX_ROWS);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean ko = "ko".equals(Locale.getDefault().getLanguage());

        JSONObject data = null;
        try {
            String raw = p.getString("data", null);
            if (raw != null) data = new JSONObject(raw);
            if (alt) data = data != null ? data.optJSONObject("alt") : null;
        } catch (Exception e) {
            data = null;
        }
        int appName = res.getIdentifier("app_name", "string", pkg);
        String title = data != null ? data.optString("title", "") : "";
        if (title.isEmpty() && appName != 0) title = res.getString(appName);
        v.setTextViewText(id(res, pkg, "w_title"), title);

        int accent = color(res, pkg, "widget_accent", 0xFF4F46E5);
        int negative = color(res, pkg, "widget_negative", 0xFFE11D48);
        int normal = color(res, pkg, "widget_value", 0xFF0F172A);
        JSONArray rows = data != null ? data.optJSONArray("rows") : null;
        for (int i = 0; i < Math.min(count, MAX_ROWS); i++) {
            int row = id(res, pkg, "w_row" + (i + 1));
            if (row == 0) break; // 이 앱의 위젯은 여기까지
            JSONObject r = rows != null ? rows.optJSONObject(i) : null;
            if (r == null) {
                v.setViewVisibility(row, View.GONE);
                continue;
            }
            v.setViewVisibility(row, View.VISIBLE);
            v.setTextViewText(id(res, pkg, "w_label" + (i + 1)), r.optString("label", ""));
            v.setTextViewText(id(res, pkg, "w_value" + (i + 1)), r.optString("value", ""));
            String tone = r.optString("tone", "");
            v.setTextColor(id(res, pkg, "w_value" + (i + 1)),
                    "pos".equals(tone) ? accent : "neg".equals(tone) ? negative : normal);
        }

        String note;
        long at = p.getLong("at", 0);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        long since = System.currentTimeMillis() - p.getLong("refreshing_at", 0);
        boolean refreshing = since >= 0 && since < REFRESHING_SHOW_MS;
        long failed = p.getLong("refresh_failed_at", 0);
        String when = new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(new Date(at));
        if (refreshing) { // 새로 고치는 동안에도 마지막 업데이트 시간을 보인다
            note = (data != null && at > 0 ? (ko ? "업데이트 " : "Updated ") + when + " · " : "")
                    + (ko ? "새로고침 중…" : "Refreshing…");
        } else if (data == null) {
            note = failed > 0 ? (ko ? "새로고침 못 함 — 앱을 열어 로그인해 주세요" : "Couldn't refresh — open the app and log in")
                    : (ko ? "앱을 열고 로그인하면 여기에 요약이 보여요" : "Open the app (and log in) to fill this widget");
        } else {
            String extra = data.optString("note", "");
            note = (extra.isEmpty() ? "" : extra + " · ") + (ko ? "업데이트 " : "Updated ") + when
                    + (failed > at ? (ko ? " · 새로고침 실패 " : " · refresh failed ") + hm.format(new Date(failed)) : "");
        }
        v.setTextViewText(id(res, pkg, "w_note"), note);

        // 마이크 단추 (--mic 앱만): 앱을 열고 음성 대화를 시작한다
        int micBtn = id(res, pkg, "w_mic");
        if (micBtn != 0) {
            if (micEnabled(ctx)) {
                v.setViewVisibility(micBtn, View.VISIBLE);
                // 앱 화면을 열지 않고 홈 화면 위에 작은 대화 창만 (빅스비처럼 바로 듣기)
                Intent mic = new Intent(ctx, VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.setOnClickPendingIntent(micBtn, PendingIntent.getActivity(ctx, 5, mic,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                v.setViewVisibility(micBtn, View.GONE);
            }
        }

        // 새로고침 단추 (자동 새로고침을 켠 앱만)
        int refreshBtn = id(res, pkg, "w_refresh");
        if (refreshBtn != 0) {
            if (refreshMinutes(ctx) > 0) {
                v.setViewVisibility(refreshBtn, View.VISIBLE);
                PendingIntent rp = PendingIntent.getBroadcast(ctx, 2,
                        new Intent(ctx, WidgetProvider.class).setAction(ACTION_REFRESH),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                v.setOnClickPendingIntent(refreshBtn, rp);
            } else {
                v.setViewVisibility(refreshBtn, View.GONE);
            }
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(id(res, pkg, "w_root"), pi);
        return v;
    }
}
