package app.jarvis.urlapp;

// 홈 화면 위젯. 앱이 열려 있을 때 "위젯 스크립트"(assets/urlapp-widget.js)가 사이트에서 읽은 요약을
// MainActivity 의 통로로 넘기면 여기서 저장하고 그린다. 위젯이 직접 인터넷에 접속하지는 않는다.
// 데이터 모양: {"title": "11월 가계부", "rows": [{"label": "수입", "value": "1,234원", "tone": "pos|neg"}], "note": "…"}
// 위젯이 없는 앱에도 이 클래스는 들어 있지만, 매니페스트에 receiver 가 없으면 쓰이지 않는다.

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WidgetProvider extends AppWidgetProvider {
    static final String PREFS = "urlapp_widget";
    static final int MAX_ROWS = 8; // 레이아웃에 있는 줄(w_row1…)까지만 그린다 (기본 4줄, 스크립트가 정하면 8줄까지)

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, build(ctx));
    }

    /** 통로에서 부른다: 받은 요약을 저장하고 홈 화면의 위젯을 모두 다시 그린다. */
    static void save(Context ctx, String json) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("data", json).putLong("at", System.currentTimeMillis()).apply();
        refresh(ctx);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, build(ctx));
    }

    private static int id(Resources res, String pkg, String name) {
        return res.getIdentifier(name, "id", pkg);
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

    static RemoteViews build(Context ctx) {
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        RemoteViews v = new RemoteViews(pkg, res.getIdentifier("urlapp_widget", "layout", pkg));
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean ko = "ko".equals(Locale.getDefault().getLanguage());

        JSONObject data = null;
        try {
            String raw = p.getString("data", null);
            if (raw != null) data = new JSONObject(raw);
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
        for (int i = 0; i < MAX_ROWS; i++) {
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
        if (data == null) {
            note = ko ? "앱을 열고 로그인하면 여기에 요약이 보여요" : "Open the app (and log in) to fill this widget";
        } else {
            String when = new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(new Date(at));
            String extra = data.optString("note", "");
            note = (extra.isEmpty() ? "" : extra + " · ") + (ko ? "업데이트 " : "Updated ") + when;
        }
        v.setTextViewText(id(res, pkg, "w_note"), note);

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
