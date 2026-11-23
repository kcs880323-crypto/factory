package app.jarvis.urlapp;

// 카드 알림 읽기 '사용 중지' 화면 (앱 아이콘을 길게 누르면 나오는 바로가기, 카드 설정 화면의 단추). 홈 화면 위에 작은 창으로 뜬다.
// 은행 앱(NH pay 등)이 알림을 읽는 앱을 '악성 앱'으로 탐지하면: 일시중지 → 은행 앱 쓰기 → 다시 켜기.
// 일시중지 = 알림 듣기 서비스를 끈다(CardPause). 시스템의 '알림 접근' 허용은 사용자가 설정에서 꺼야 해서 그 화면으로 안내한다.
// 일시중지 중에 온 카드 결제 알림은 자동으로 등록되지 않는다 (다시 켠 뒤의 알림부터).

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class CardPauseActivity extends Activity {
    private static final int BLUE = Color.rgb(59, 111, 217), INK = Color.rgb(15, 23, 42), GRAY = Color.rgb(100, 116, 139);
    private TextView title, state, info;
    private Button main, access;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.55f);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> close()); // 창 밖을 누르면 닫기
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(24));
        panel.setBackground(bg);
        panel.setClickable(true); // 창 안을 눌러도 닫히지 않게
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.setMargins(dp(24), dp(24), dp(24), dp(24));
        root.addView(panel, lp);
        setContentView(root);

        title = text(panel, 20, INK, true);
        title.setText(t("카드 알림 읽기", "Card notification reading"));
        state = text(panel, 16, INK, true);
        state.setPadding(0, dp(12), 0, dp(2));
        info = text(panel, 14, GRAY, false);
        info.setLineSpacing(dp(3), 1f);

        main = button(panel, true);
        main.setOnClickListener(v -> onMain());
        access = button(panel, false);
        access.setOnClickListener(v -> openAccess());
        Button close = button(panel, false);
        close.setText(t("닫기", "Close"));
        close.setOnClickListener(v -> close());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // 시스템 설정에서 돌아왔을 때도 지금 상태로 다시 그린다
    }

    private void render() {
        boolean paused = CardPause.paused(this);
        boolean granted = CardPause.accessGranted(this);
        if (!CardPause.available(this)) {
            state.setText(t("이 앱에는 카드 알림 읽기가 없어요", "No card reading in this app"));
            info.setText("");
            main.setVisibility(View.GONE);
            access.setVisibility(View.GONE);
            return;
        }
        main.setVisibility(View.VISIBLE);
        if (!paused) {
            state.setText(t("지금: 켜져 있음 (카드 결제를 읽는 중)", "Now: on (reading card payments)"));
            info.setText(t("NH pay 같은 은행 앱이 이 앱을 '악성 앱'으로 막으면, 일시중지한 뒤 은행 앱을 쓰고 끝나면 다시 켜세요.\n"
                            + "일시중지 중에 온 카드 결제 알림은 자동으로 등록되지 않아요.",
                    "If a bank app flags this app, pause it, use the bank app, then turn it back on.\n"
                            + "Card payments that arrive while paused are not registered automatically."));
            main.setText(t("일시중지", "Pause"));
            access.setVisibility(View.GONE);
        } else if (granted) {
            state.setText(t("일시중지함 — 알림 접근을 꺼야 마무리돼요", "Paused — turn off notification access to finish"));
            info.setText(t("알림 읽기는 멈췄어요. 은행 앱이 '알림 접근' 허용 자체를 보는 경우가 있어서, 설정에서 '우리집 가계부'의 "
                            + "알림 접근도 꺼 주세요. (끄고 이 창으로 돌아오면 '완료'로 바뀌어요)",
                    "Reading is stopped. Some bank apps look at the notification-access permission itself, so also turn it off in settings."));
            main.setText(t("다시 켜기", "Turn back on"));
            access.setText(t("알림 접근 끄러 가기", "Open notification access settings"));
            access.setVisibility(View.VISIBLE);
        } else {
            state.setText(t("✔ 일시중지 완료 — 은행 앱을 쓰셔도 돼요", "✔ Paused — you can use your bank app"));
            info.setText(t("끝나면 아래 '다시 켜기'를 눌러 주세요. 다시 켤 때 '알림 접근'이 꺼져 있으면 설정 화면을 열어 드려요.",
                    "When you're done, tap Turn back on."));
            main.setText(t("다시 켜기", "Turn back on"));
            access.setVisibility(View.GONE);
        }
    }

    private void onMain() {
        if (!CardPause.paused(this)) {
            if (!CardPause.pause(this)) {
                Toast.makeText(this, t("일시중지하지 못했어요", "Couldn't pause"), Toast.LENGTH_LONG).show();
                return;
            }
            render();
            if (CardPause.accessGranted(this)) { // 마무리: 시스템에서 알림 접근 끄기
                Toast.makeText(this, t("'우리집 가계부'의 알림 접근을 꺼 주세요", "Turn off notification access for this app"),
                        Toast.LENGTH_LONG).show();
                openAccess();
            }
        } else {
            if (!CardPause.resume(this)) {
                Toast.makeText(this, t("다시 켜지 못했어요", "Couldn't turn back on"), Toast.LENGTH_LONG).show();
                return;
            }
            render();
            if (!CardPause.accessGranted(this)) { // 알림 접근을 꺼 두었으면 켜야 읽는다
                Toast.makeText(this, t("알림 접근을 켜 주세요", "Turn notification access back on"), Toast.LENGTH_LONG).show();
                openAccess();
            } else {
                Toast.makeText(this, t("다시 켰어요", "Turned back on"), Toast.LENGTH_SHORT).show();
                close();
            }
        }
    }

    private void openAccess() {
        try {
            startActivity(CardPause.accessSettings(this));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // 설정을 열 수 없으면 안내만
            }
        }
    }

    /** 닫기. 완전히 중지된 상태(알림 접근도 꺼짐)면 앱 프로세스도 끝내 '실행 중'으로 남지 않게 한다. */
    private void close() {
        boolean fully = CardPause.available(this) && CardPause.paused(this) && !CardPause.accessGranted(this);
        finishAndRemoveTask();
        if (fully) Process.killProcess(Process.myPid());
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private TextView text(LinearLayout box, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(v);
        return v;
    }

    private Button button(LinearLayout box, boolean primary) {
        Button v = new Button(this);
        v.setAllCaps(false);
        v.setTextSize(16);
        v.setTextColor(primary ? Color.WHITE : BLUE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        if (primary) {
            g.setColor(BLUE);
        } else {
            g.setColor(Color.TRANSPARENT);
        }
        v.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(primary ? 16 : 6);
        box.addView(v, p);
        return v;
    }

    private String t(String ko, String en) {
        return "ko".equals(Locale.getDefault().getLanguage()) ? ko : en;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
