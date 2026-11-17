package app.jarvis.urlapp;

// 카드 자동 등록 설정 화면 (코드로 그린다, 외부 라이브러리 없음).
// 담당자 고르기, 켜고 끄기, 필요한 권한(알림 접근·알림 표시·배터리) 상태와 여는 단추, 알림 문구 시험,
// 최근 등록과 되돌리기, 감지했지만 쓰지 않은 앱 허용, 분류 학습 상태.

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
    private static final String SAMPLE = "[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:30\n메가커피 강남점";
    private static final String MACRODROID = "com.arlosoft.macrodroid";
    private String testText = SAMPLE;

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
        boolean viaIntent = "intent".equals(engine.via());
        para("카드 결제 알림(문자, 카드사 앱, 삼성 월렛, 카드사 카카오 알림톡)을 " + (viaIntent ? "MacroDroid 가 넘겨주면 " : "읽어 ")
                + "가계부에 바로 등록해요. 결제 알림만 다루고, 날짜·금액·가맹점·카드만 가계부로 보내요. 알림 내용은 이 폰에 저장하지 않아요.");

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

        // 3) 카드 알림 받기
        if (viaIntent) renderMacroDroid();
        head("필요한 설정");
        boolean listener = viaIntent || engine.listenerEnabled();
        if (!viaIntent) row((listener ? "✔ " : "✖ ") + "알림 접근 (필수)", listener ? null : "설정 열기", v -> openListenerSettings());
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
        para("아래 예시를 그대로 읽어 보거나, 받은 카드 알림 문구로 바꿔 넣고 '읽어 보기'를 누르세요. 가계부에는 등록하지 않아요.");
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        in.setGravity(Gravity.TOP);
        in.setText(testText);
        box.addView(in);
        TextView out = new TextView(this);
        row("", "읽어 보기", v -> {
            testText = in.getText().toString();
            CardParser.Txn t = CardParser.parse("", testText, System.currentTimeMillis());
            out.setTypeface(Typeface.DEFAULT_BOLD);
            if (t == null) {
                out.setTextColor(Color.rgb(225, 29, 72));
                out.setText(testText.trim().isEmpty() ? "칸이 비어 있어요. 카드 알림 문구를 넣어 주세요."
                        : "카드 결제 알림으로 읽히지 않아요. (카드사 이름 + 승인/취소 + 금액원이 있어야 해요)");
            } else {
                out.setTextColor(Color.rgb(79, 70, 229));
                CardCategorizer.Pick p = engine.pick(t.merchant);
                out.setText((t.cancel ? "승인취소 " : "승인 ") + t.card + " " + CardEngine.won(t.amount)
                        + "\n가맹점: " + (t.merchant.isEmpty() ? "(없음 → '" + t.card + " 결제')" : t.merchant)
                        + "\n날짜: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date(t.when))
                        + "\n분류: " + p.large + (p.medium.isEmpty() ? "" : " › " + p.medium) + "  ("
                        + ("history".equals(p.source) ? "지난 기록에서" : "rule".equals(p.source) ? "가맹점 규칙" : "기본값") + ")");
            }
        });
        out.setTextSize(15);
        out.setPadding(0, dp(4), 0, dp(8));
        box.addView(out); // 결과는 단추 아래에

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
                    + " · " + e.optString("owner") + " · " + statusText(st)
                    + (CardStore.PENDING.equals(st) && e.has("error")
                            ? "\n⚠ " + CardEngine.failHint(e.optInt("code")) + " (" + e.optString("error") + ")" : "");
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
        if (engine.hasPending()) row("인터넷이 연결되면 대기 중인 건을 보내요.", "지금 보내기", v -> {
            Toast.makeText(this, "보내는 중…", Toast.LENGTH_SHORT).show();
            engine.exec.execute(() -> {
                String result = engine.flush();
                runOnUiThread(() -> {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                    render();
                });
            });
        });

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

        // 7) 사이트 연동
        head("사이트 연동");
        renderAppKey();
        long checked = engine.store.linkChecked();
        para((engine.fromSite() ? "✔ 사이트의 연동 파일(/urlapp.json)을 따라요." : "△ 사이트에 연동 파일(/urlapp.json)이 없어 앱 기본 설정을 써요.")
                + (checked > 0 ? " (확인 " + new SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(new Date(checked)) + ")" : ""));
        if (engine.store.linkErrors() > 0) {
            TextView err = para("최근 가계부가 등록을 거절했어요 (" + engine.store.linkErrorWhy() + "). 사이트를 고친 뒤 데이터 주소가 "
                    + "바뀌었을 수 있어요. 못 보낸 결제는 모아 두었어요.");
            err.setTextColor(Color.rgb(225, 29, 72));
        }
        row("", "다시 확인", v -> engine.exec.execute(() -> {
            boolean found = engine.refreshLink();
            engine.flush();
            runOnUiThread(() -> {
                Toast.makeText(this, found ? "사이트의 연동 파일을 읽었어요" : "연동 파일이 없거나 연결하지 못했어요",
                        Toast.LENGTH_SHORT).show();
                render();
            });
        }));

        // 8) 분류
        head("분류");
        para("가계부의 지난 3개월 기록에서 같은 가맹점을 어디에 넣었는지 배워 그대로 따르고, 처음 보는 가맹점은 "
                + "가맹점 이름으로 정해요 (예: 스타벅스·GS25·이마트 → 생활비, 주유소 → 주유비).");
        row("배운 가맹점: " + engine.learnedCount() + "곳", "다시 배우기", v -> engine.exec.execute(() -> {
            int n = engine.refreshLearned();
            runOnUiThread(() -> {
                String why = engine.lastReason();
                Toast.makeText(this, n < 0 ? "가계부에 연결하지 못했어요" + (why.isEmpty() ? "" : " (" + why + ")")
                        : "가맹점 " + n + "곳을 배웠어요", Toast.LENGTH_LONG).show();
                render();
            });
        }));
    }

    /** MacroDroid 로 받기: 상태, 키(복사), 넣을 값, 설치 단추. */
    private void renderMacroDroid() {
        head("MacroDroid 연결");
        long card = engine.store.receivedAt("rx_card_at");
        long bad = engine.store.receivedAt("rx_bad_token_at");
        long any = engine.store.receivedAt("rx_at");
        SimpleDateFormat f = new SimpleDateFormat("M/d HH:mm", Locale.KOREA);
        if (card > 0) para("✔ 마지막으로 받은 카드 알림: " + f.format(new Date(card)));
        else if (any > 0) para("△ MacroDroid 에서 알림을 받았지만 아직 카드 결제 알림은 없어요 (" + f.format(new Date(any)) + ")");
        else note("아직 MacroDroid 에서 받은 알림이 없어요. 아래대로 매크로를 만들어 주세요.");
        if (bad > 0 && bad >= card) {
            TextView w = para("✖ 키가 맞지 않는 요청이 있었어요 (" + f.format(new Date(bad)) + "). MacroDroid 의 token 값을 아래 키로 고쳐 주세요.");
            w.setTextColor(Color.rgb(225, 29, 72));
        }
        boolean installed;
        try {
            getPackageManager().getPackageInfo(MACRODROID, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        row((installed ? "✔ " : "✖ ") + "MacroDroid 설치 (플레이 스토어, 무료)", installed ? null : "설치", v -> {
            if (!startQuiet(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + MACRODROID)))) {
                startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + MACRODROID)));
            }
        });
        String token = engine.store.token();
        row("이 폰의 키: " + token, "복사", v -> copy("키", token));
        row("받는 곳(Action): " + CardIntentReceiver.ACTION, "복사", v -> copy("Action", CardIntentReceiver.ACTION));
        row("앱(Package): " + getPackageName(), "복사", v -> copy("Package", getPackageName()));
        note("MacroDroid 에서 새 매크로를 만들어요.\n"
                + "1) 트리거: 알림 → '알림 수신'(Notification Received) → 앱 선택: 삼성 월렛, 메시지, 쓰는 카드사 앱들"
                + " (카카오 알림톡으로 받으면 카카오톡도) → MacroDroid 에 알림 접근을 허용.\n"
                + "2) 동작: 앱 → '인텐트 보내기'(Send Intent) → 대상: 브로드캐스트(Broadcast), Action 과 Package 는 위 값을 붙여 넣기.\n"
                + "3) 추가 데이터(Extras) 4개: token = 위 키 / title = 알림 제목 / text = 알림 내용 / app = 알림 보낸 앱 이름. "
                + "제목·내용·앱 이름은 값 칸 옆 '…'(매직 텍스트) 단추에서 골라요.\n"
                + "4) 저장하고 켜기. 카드로 결제하면 위 '마지막으로 받은 카드 알림'에 시각이 떠요.");
    }

    /** 가계부 앱 연동 키: 사이트에 보안을 켜면 화면 밖(카드 자동 등록) 요청에 필요. */
    private void renderAppKey() {
        String key = engine.store.appKey();
        boolean needed = engine.authRequired() || engine.store.authErrors() > 0;
        TextView t = para((key.isEmpty() ? (needed ? "✖ " : "△ ") : "✔ ") + "가계부 앱 연동 키: "
                + (key.isEmpty() ? (needed ? "필요해요 — 사이트 설정에서 만든 키를 넣어 주세요" : "없음 (사이트에 보안을 켜기 전에는 필요 없어요)")
                : "넣음 (" + key.substring(0, Math.min(4, key.length())) + "…)"));
        if (key.isEmpty() && needed) t.setTextColor(Color.rgb(225, 29, 72));
        if (!key.isEmpty() && engine.store.authErrors() > 0) {
            TextView bad = para("✖ 최근 가계부가 이 키를 받지 않았어요. 사이트의 '앱 연동 키 · 보안'에서 키를 새로 만들어 "
                    + "다시 붙여 넣어 주세요.");
            bad.setTextColor(Color.rgb(225, 29, 72));
        }
        EditText k = new EditText(this);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        k.setHint("앱 연동 키 붙여 넣기");
        box.addView(k);
        row("", "저장", v -> {
            String value = k.getText().toString().trim();
            engine.exec.execute(() -> {
                engine.setAppKey(value);
                boolean had = engine.hasPending();
                String result = engine.flush(); // 모아 둔 결제를 새 키로 바로 보낸다
                runOnUiThread(() -> {
                    Toast.makeText(this, (value.isEmpty() ? "키를 지웠어요" : "키를 저장했어요") + (had ? ". " + result : ""),
                            Toast.LENGTH_LONG).show();
                    render();
                });
            });
        });
    }

    private void copy(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + " 을(를) 복사했어요", Toast.LENGTH_SHORT).show();
    }

    private boolean startQuiet(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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
