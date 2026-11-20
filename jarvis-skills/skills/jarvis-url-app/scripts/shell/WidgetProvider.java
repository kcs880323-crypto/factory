package app.jarvis.urlapp;

// 홈 화면 위젯. 앱이 열려 있을 때 "위젯 스크립트"(assets/urlapp-widget.js)가 사이트에서 읽은 요약을
// MainActivity 의 통로로 넘기면 여기서 저장하고 그린다. 위젯이 직접 인터넷에 접속하지는 않는다.
// 데이터 모양: {"title": "11월 가계부", "rows": [{"label": "수입", "value": "1,234원", "tone": "pos|neg"}], "note": "…",
//              "alt": {같은 모양}}  ← alt 는 두 번째 위젯(WidgetProviderAlt, 예: '카드 사용')이 그린다
// 위젯이 없는 앱에도 이 클래스는 들어 있지만, 매니페스트에 receiver 가 없으면 쓰이지 않는다.
// 위젯 스크립트에 "// urlapp:refresh-min N" 이 있으면(설정의 widget_refresh_min) 위젯에 새로고침 단추가 생기고,
// 화면이 켜져 있는 동안 N분마다 앱을 열지 않고 새로 고친다 (WidgetRefreshJob: 보이지 않는 웹 화면).

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
    private static int refreshMin = -1; // 설정의 widget_refresh_min (0 = 단추·자동 새로고침 없음)

    /** 두 번째 위젯(WidgetProviderAlt)이면 true: 저장한 값의 "alt" 를 그린다. */
    boolean alt() {
        return false;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, build(ctx, alt()));
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
            WidgetRefreshJob.enqueue(ctx);
            return;
        }
        if (ACTION_AUTO.equals(action)) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            if (screenOn && anyPlaced(ctx)) WidgetRefreshJob.enqueue(ctx); // 화면이 꺼져 있으면 건너뛴다 (배터리)
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** 설정의 widget_refresh_min (분). 없으면 0. */
    static int refreshMinutes(Context ctx) {
        if (refreshMin >= 0) return refreshMin;
        int m = 0;
        try (InputStream in = ctx.getAssets().open("urlapp.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            m = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optInt("widget_refresh_min", 0);
        } catch (Exception e) {
            m = 0;
        }
        refreshMin = Math.max(0, m);
        return refreshMin;
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
        for (Class<?> c : new Class<?>[] {WidgetProvider.class, WidgetProviderAlt.class}) {
            try {
                int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, c));
                if (ids != null && ids.length > 0) return true;
            } catch (RuntimeException ignored) {
                // 매니페스트에 없는 위젯
            }
        }
        return false;
    }

    /** 새로고침을 시작했다 (위젯 아래 줄에 "새로고침 중…"). */
    static void noteRefreshing(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("refreshing_at", System.currentTimeMillis()).apply();
        refresh(ctx);
    }

    /** 새로고침이 끝났다. 실패면 그 시각을 남긴다 (성공이면 save 가 이미 저장했다). */
    static void noteRefresh(Context ctx, boolean ok) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("refreshing_at");
        if (ok) e.remove("refresh_failed_at");
        else e.putLong("refresh_failed_at", System.currentTimeMillis());
        e.apply();
        refresh(ctx);
    }

    /** 통로에서 부른다: 받은 요약을 저장하고 홈 화면의 위젯을 모두 다시 그린다. */
    static void save(Context ctx, String json) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("data", json).putLong("at", System.currentTimeMillis()).remove("refresh_failed_at").apply();
        refresh(ctx);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        update(ctx, mgr, WidgetProvider.class, false);
        update(ctx, mgr, WidgetProviderAlt.class, true); // 두 번째 위젯이 없는 앱이면 놓인 것이 없다
    }

    private static void update(Context ctx, AppWidgetManager mgr, Class<?> cls, boolean alt) {
        try {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, cls));
            if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, build(ctx, alt));
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

    static RemoteViews build(Context ctx, boolean alt) {
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        // 두 번째 위젯은 자기 모양(urlapp_widget_alt, 줄 수가 다를 수 있음)으로. 없으면 첫 위젯 모양.
        int layout = alt ? res.getIdentifier("urlapp_widget_alt", "layout", pkg) : 0;
        boolean ownLayout = layout != 0;
        if (layout == 0) layout = res.getIdentifier("urlapp_widget", "layout", pkg);
        RemoteViews v = new RemoteViews(pkg, layout);
        // 그 모양에 있는 줄 수 (줄 id 는 두 모양이 같이 쓰므로, 모양에 없는 줄을 건드리면 위젯이 깨진다)
        int count = integer(res, pkg, ownLayout ? "urlapp_widget_alt_rows" : "urlapp_widget_rows", MAX_ROWS);
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
        boolean refreshing = System.currentTimeMillis() - p.getLong("refreshing_at", 0) < 90_000;
        long failed = p.getLong("refresh_failed_at", 0);
        if (refreshing) {
            note = ko ? "새로고침 중…" : "Refreshing…";
        } else if (data == null) {
            note = failed > 0 ? (ko ? "새로고침 못 함 — 앱을 열어 로그인해 주세요" : "Couldn't refresh — open the app and log in")
                    : (ko ? "앱을 열고 로그인하면 여기에 요약이 보여요" : "Open the app (and log in) to fill this widget");
        } else {
            String when = new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(new Date(at));
            String extra = data.optString("note", "");
            note = (extra.isEmpty() ? "" : extra + " · ") + (ko ? "업데이트 " : "Updated ") + when
                    + (failed > at ? (ko ? " · 새로고침 실패 " : " · refresh failed ") + hm.format(new Date(failed)) : "");
        }
        v.setTextViewText(id(res, pkg, "w_note"), note);

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
