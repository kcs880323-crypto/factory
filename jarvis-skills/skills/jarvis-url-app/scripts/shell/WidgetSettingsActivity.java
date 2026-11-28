package app.jarvis.urlapp;

// 위젯 꾸미기: 바탕 투명도 (0% 불투명 ~ 100% 투명). 위젯을 길게 눌러 '설정'(안드로이드 12+, 위젯 정보의 reconfigurable)으로 열고,
// 안드로이드 11 이하는 위젯을 놓을 때 한 번 뜬다. 바꾸는 대로 미리보기와 홈 화면의 위젯(이 앱의 위젯 모두)에 바로 적용한다.
// 창을 그냥 닫아도 위젯은 그대로 놓인다 (처음부터 '확인' 결과를 돌려준다).

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class WidgetSettingsActivity extends Activity {
    private static final int STEP = 5; // 5% 단위
    private final Handler main = new Handler(Looper.getMainLooper());
    private int transparency;
    private Drawable previewBg;
    private TextView valueView;
    private SeekBar bar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        // 그냥 닫아도 위젯이 놓이도록 처음부터 '확인'
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        setTitle(t("위젯 꾸미기", "Widget style"));
        transparency = WidgetProvider.transparency(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(box);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets in = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return insets;
            });
        }

        TextView title = text(box, t("위젯 꾸미기", "Widget style"), 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text(box, t("바탕을 투명하게 하면 바탕화면이 비쳐 보여요. 이 앱의 위젯 모두에 같이 적용돼요.",
                "A transparent background lets your wallpaper show through. Applies to all of this app's widgets."), 14)
                .setAlpha(0.8f);

        box.addView(preview(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        valueView = text(box, "", 17);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setPadding(0, dp(18), 0, dp(4));
        bar = new SeekBar(this);
        bar.setMax(100 / STEP);
        bar.setProgress(transparency / STEP);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) set(progress * STEP);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                apply();
            }
        });
        box.addView(bar);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] values = {0, 25, 50, 75, 100};
        for (int v : values) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(v == 0 ? t("불투명", "Solid") : v == 100 ? t("투명", "Clear") : v + "%");
            b.setTextSize(13);
            b.setOnClickListener(x -> {
                set(v);
                bar.setProgress(v / STEP);
                apply();
            });
            presets.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(presets);

        text(box, t("글자가 잘 안 보이면 투명도를 조금 낮춰 주세요. 바꾸는 대로 홈 화면의 위젯에도 바로 적용돼요.",
                "If the text gets hard to read, lower it a little. Changes apply to your home screen right away."), 13)
                .setAlpha(0.7f);

        Button done = new Button(this);
        done.setAllCaps(false);
        done.setText(t("완료", "Done"));
        done.setOnClickListener(x -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(done, lp);
        set(transparency);
    }

    /** 바탕화면 대신 색 띠 위에 위젯 모양 (실제 위젯 바탕 그림·글자 색). */
    private View preview() {
        FrameLayout wall = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Color.rgb(255, 154, 98), Color.rgb(108, 92, 231), Color.rgb(0, 184, 148)});
        g.setCornerRadius(dp(16));
        wall.setBackground(g);
        wall.setPadding(dp(18), dp(18), dp(18), dp(18));
        wall.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(16), dp(12), dp(16), dp(10));
        Resources res = getResources();
        int bgId = res.getIdentifier("urlapp_widget_bg", "drawable", getPackageName());
        if (bgId != 0) {
            previewBg = getDrawable(bgId).mutate();
            w.setBackground(previewBg);
        }
        int appName = res.getIdentifier("app_name", "string", getPackageName());
        TextView name = new TextView(this);
        name.setText(appName != 0 ? res.getString(appName) : "");
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(color("widget_title", Color.WHITE));
        w.addView(name);
        w.addView(row(t("지금 하는 일", "Now"), t("진행 중", "Running"), true));
        w.addView(row(t("예시 줄", "Sample row"), "12:30", false));
        TextView note = new TextView(this);
        note.setText(t("업데이트 11/20 17:03", "Updated 11/20 17:03"));
        note.setTextSize(10);
        note.setTextColor(color("widget_note", Color.LTGRAY));
        w.addView(note);
        wall.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wall;
    }

    private View row(String label, String value, boolean accent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(color("widget_label", Color.LTGRAY));
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(accent ? color("widget_accent", Color.CYAN) : color("widget_value", Color.WHITE));
        r.addView(v);
        return r;
    }

    private void set(int pct) {
        transparency = Math.max(0, Math.min(100, pct));
        valueView.setText(String.format(Locale.ROOT, t("투명도 %d%%", "Transparency %d%%"), transparency));
        if (previewBg != null) previewBg.setAlpha(WidgetProvider.bgAlpha(transparency));
        main.removeCallbacks(applyLater);
        main.postDelayed(applyLater, 300); // 끄는 동안에도 홈 화면 위젯에 곧 적용
    }

    private final Runnable applyLater = this::apply;

    private void apply() {
        main.removeCallbacks(applyLater);
        if (WidgetProvider.transparency(this) != transparency) WidgetProvider.setTransparency(this, transparency);
    }

    @Override
    protected void onPause() {
        apply();
        super.onPause();
    }

    private int color(String name, int fallback) {
        int c = getResources().getIdentifier(name, "color", getPackageName());
        if (c == 0) return fallback;
        try {
            return getResources().getColor(c, getTheme());
        } catch (Resources.NotFoundException e) {
            return fallback;
        }
    }

    private TextView text(LinearLayout box, String s, int sp) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setPadding(0, dp(4), 0, dp(4));
        box.addView(v);
        return v;
    }

    private String t(String ko, String en) {
        return "ko".equals(Locale.getDefault().getLanguage()) ? ko : en;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
