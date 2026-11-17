package app.jarvis.urlapp;

// 카드 자동 등록 설정 화면 (코드로 그린다, 외부 라이브러리 없음).
// 담당자 고르기, 켜고 끄기, 필요한 권한(알림 접근·알림 표시·배터리) 상태와 여는 단추, 알림 문구 시험,
// 최근 등록과 되돌리기, 감지했지만 쓰지 않은 앱 허용, 분류 학습 상태.

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CardSettingsActivity extends Activity {
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private CardEngine engine;
    private LinearLayout box;
    private String testText = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("카드 결제 자동 등록");
        if (!CardEngine.available(this)) {
            finish();
            return;
        }
        engine = CardEngine.get(this);
        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(box);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            // 안드로이드 15+ 는 화면 끝까지 그리므로 막대 영역만큼 띄운다
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets in = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.ime());
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return insets;
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) render();
    }

    private void render() {
        box.removeAllViews();
        title("카드 결제 자동 등록");
        para("카드 결제 알림(문자, 카드사 앱, 삼성 월렛, 카드사 카카오 알림톡)을 읽어 가계부에 바로 등록해요. "
                + "결제 알림만 골라 읽고, 날짜·금액·가맹점·카드만 가계부로 보내요. 알림 내용은 이 폰에 저장하지 않아요.");

        // 1) 담당자
        head("이 폰의 담당자");
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        String owner = engine.store.owner();
        for (String o : engine.owners()) {
            RadioButton r = new RadioButton(this);
            r.setText(o);
            r.setId(View.generateViewId());
            r.setChecked(o.equals(owner));
            r.setOnCheckedChangeListener((b, on) -> {
                if (on) {
                    engine.store.setOwner(o);
                    render();
                }
            });
            g.addView(r);
        }
        box.addView(g);
        if (owner == null) note("담당자를 고르면 등록을 시작해요.");

        // 2) 켜기
        Switch s = new Switch(this);
        s.setText("자동 등록 켜기");
        s.setTextSize(16);
        s.setChecked(engine.store.enabled());
        s.setPadding(0, dp(12), 0, dp(4));
        s.setOnCheckedChangeListener((CompoundButton b, boolean on) -> engine.store.setEnabled(on));
        box.addView(s);

        // 3) 권한
        head("필요한 설정");
        boolean listener = engine.listenerEnabled();
        row((listener ? "✔ " : "✖ ") + "알림 접근 (필수)", listener ? null : "설정 열기", v -> openListenerSettings());
        if (!listener) {
            note("설정에서 '우리집 가계부'를 켜 주세요. 켤 수 없게 막혀 있으면: 앱 정보 → 오른쪽 위 ⋮ → "
                    + "'제한된 설정 허용' 을 누른 뒤 다시 켜요 (직접 설치한 앱이라 한 번 필요해요).");
            row("", "앱 정보 열기", v -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            boolean post = checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            row((post ? "✔ " : "✖ ") + "알림 표시 (등록 결과·되돌리기)", post ? null : "허용",
                    v -> requestPermissions(new String[] {POST_NOTIFICATIONS}, 1));
        }
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean free = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            row((free ? "✔ " : "△ ") + "배터리 최적화에서 빼기 (권장)", free ? null : "설정 열기",
                    v -> startSafe(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
            if (!free) note("삼성 폰은 배터리 관리가 강해서, 빼 두지 않으면 알림을 놓칠 수 있어요. "
                    + "목록에서 '우리집 가계부' → 최적화 안 함. (설정 → 배터리 → 백그라운드 사용 제한에도 있으면 빼요)");
        }

        // 4) 시험
        head("알림 문구로 시험");
        para("받은 카드 알림 문구를 붙여 넣으면 어떻게 읽히는지 보여 줘요. 가계부에는 등록하지 않아요.");
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        in.setGravity(Gravity.TOP);
        in.setText(testText);
        in.setHint("[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:30\n메가커피 강남점");
        box.addView(in);
        TextView out = para("");
        row("", "읽어 보기", v -> {
            testText = in.getText().toString();
            CardParser.Txn t = CardParser.parse("", testText, System.currentTimeMillis());
            if (t == null) {
                out.setText("카드 결제 알림으로 읽히지 않아요. (카드사 이름 + 승인/취소 + 금액원이 있어야 해요)");
            } else {
                CardCategorizer.Pick p = engine.pick(t.merchant);
                out.setText((t.cancel ? "승인취소 " : "승인 ") + t.card + " " + CardEngine.won(t.amount)
                        + "\n가맹점: " + (t.merchant.isEmpty() ? "(없음 → '" + t.card + " 결제')" : t.merchant)
                        + "\n날짜: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date(t.when))
                        + "\n분류: " + p.large + (p.medium.isEmpty() ? "" : " › " + p.medium) + "  ("
                        + ("history".equals(p.source) ? "지난 기록에서" : "rule".equals(p.source) ? "가맹점 규칙" : "기본값") + ")");
            }
        });

        // 5) 최근 등록
        head("최근 등록");
        List<JSONObject> log = engine.store.log();
        int shown = 0;
        for (JSONObject e : log) {
            if (e.optBoolean("cancel", false)) continue;
            if (shown++ >= 10) break;
            String st = e.optString("status");
            String label = new SimpleDateFormat("M/d", Locale.KOREA).format(new Date(e.optLong("when"))) + "  "
                    + e.optString("merchant") + " " + CardEngine.won(e.optLong("amount")) + "\n"
                    + e.optString("large") + (e.optString("medium").isEmpty() ? "" : "›" + e.optString("medium"))
                    + " · " + e.optString("owner") + " · " + statusText(st);
            boolean canUndo = CardStore.REGISTERED.equals(st) || CardStore.PENDING.equals(st);
            final String id = e.optString("id");
            row(label, canUndo ? "되돌리기" : null, v -> engine.exec.execute(() -> {
                String msg = engine.undo(id);
                runOnUiThread(() -> {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    render();
                });
            }));
        }
        if (shown == 0) note("아직 없어요. 카드로 결제하면 1분 안에 여기에 보여요.");
        if (engine.hasPending()) row("인터넷이 연결되면 대기 중인 건을 보내요.", "지금 보내기", v -> engine.exec.execute(() -> {
            engine.flush();
            runOnUiThread(this::render);
        }));

        // 6) 감지했지만 쓰지 않은 앱
        List<String> detected = engine.store.detected();
        if (!detected.isEmpty()) {
            head("카드 알림처럼 보였지만 쓰지 않은 앱");
            para("카드사·월렛·문자 앱이 아니어서 쓰지 않았어요. 카드 알림을 보내는 앱이 맞으면 허용해 주세요.");
            PackageManager pm = getPackageManager();
            for (String pkg : detected) {
                String name = pkg;
                try {
                    name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) + " (" + pkg + ")";
                } catch (PackageManager.NameNotFoundException ignored) {
                    // 지운 앱
                }
                row(name, "허용", v -> {
                    engine.store.allow(pkg);
                    render();
                });
            }
        }

        // 7) 분류
        head("분류");
        para("가계부의 지난 3개월 기록에서 같은 가맹점을 어디에 넣었는지 배워 그대로 따르고, 처음 보는 가맹점은 "
                + "가맹점 이름으로 정해요 (예: 스타벅스·GS25·이마트 → 생활비, 주유소 → 주유비).");
        row("배운 가맹점: " + engine.learnedCount() + "곳", "다시 배우기", v -> engine.exec.execute(() -> {
            int n = engine.refreshLearned();
            runOnUiThread(() -> {
                Toast.makeText(this, n < 0 ? "가계부에 연결하지 못했어요" : "가맹점 " + n + "곳을 배웠어요",
                        Toast.LENGTH_SHORT).show();
                render();
            });
        }));
    }

    private static String statusText(String s) {
        switch (s) {
            case CardStore.REGISTERED: return "등록됨";
            case CardStore.PENDING: return "전송 대기";
            case CardStore.UNDONE: return "되돌림";
            case CardStore.CANCELLED: return "결제 취소로 지움";
            default: return s;
        }
    }

    private void openListenerSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, CardListenerService.class).flattenToString());
            if (startSafe(i)) return;
        }
        startSafe(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
    }

    private boolean startSafe(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            Toast.makeText(this, "설정 화면을 열 수 없어요", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();
    }

    // ---------- 그리기 도우미 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(22);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, 0, 0, dp(8));
        box.addView(v);
    }

    private void head(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(22), 0, dp(6));
        box.addView(v);
    }

    private TextView para(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setPadding(0, dp(2), 0, dp(6));
        box.addView(v);
        return v;
    }

    private void note(String t) {
        TextView v = para(t);
        v.setTextSize(13);
        v.setAlpha(0.75f);
    }

    private void row(String label, String action, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            Button b = new Button(this);
            b.setText(action);
            b.setAllCaps(false);
            b.setOnClickListener(click);
            r.addView(b);
        }
        box.addView(r);
    }
}
