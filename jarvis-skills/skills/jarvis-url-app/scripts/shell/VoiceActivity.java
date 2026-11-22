package app.jarvis.urlapp;

// 위젯의 마이크 단추 — 빅스비처럼 홈 화면 위에 작은 대화 창만 띄워 바로 듣는다 (--mic 앱).
// 앱 화면은 열지 않는다. 사이트는 뒤에서 보이지 않게(투명) 열어 window.urlappAsk(질문) 로 묻고, 답은 폰의 읽어 주기로 말한다.
// 이 숨은 화면에는 음성 도우미(speech.js)를 넣지 않는다 — 사이트의 호출어 듣기("안녕, 자비스")가 폰 음성 인식을 붙잡아
// 대화 창의 듣기를 계속 끊던 문제(2026-11-20 '다시 듣기·끝내기가 안 눌린다')를 피한다. 사이트의 마이크 요청도 거절한다.
// 듣기는 창이 뜨자마자 시작하고(사이트가 뜨는 동안 말해도 된다), 질문은 사이트가 준비되면 보낸다.
// 바깥을 누르거나 '끝내기', 두 번 조용하거나, 다른 앱으로 가면 닫힌다.
// '<앱 이름> 대화' 아이콘(--voice-icon)으로도 바로 열린다 — 빅스비 '빠른 명령어'가 이 아이콘을 열게 하면
// '하이 빅스비, 자비스' 처럼 부를 수 있다 (늘 켜 두는 호출어보다 배터리를 안 쓴다: 빅스비는 전용 저전력 칩으로 듣는다).
// '하이 자비스'(HotwordService)로 떴을 때는 화면을 켜고 잠금 화면 위에 뜨되, 잠겨 있으면 잠금 풀기를 먼저 요청한다
// (잠긴 채로는 자비스에 묻지 않는다 — 일정·메일 같은 개인 정보가 있으므로). 떠 있는 동안은 호출어 듣기가 마이크를 놓는다.

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;

public class VoiceActivity extends Activity {
    static final String EXTRA_HOTWORD = "hotword";
    static volatile long shownAt; // 마지막으로 뜬 때 (호출어 서비스가 창이 실제로 떴는지 확인)
    private static final int REQ_MIC = 51;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token = newToken();
    private JSONObject cfg;
    private JSONArray hosts;
    private FrameLayout root;
    private WebView web;
    private Voice voice;
    private VoiceChat chat;
    private boolean ready, failed, closing, unlocking;
    private String failWhy = "";
    private String[] pending; // {id, 질문} — 사이트가 준비되기 전에 나온 질문

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        shownAt = android.os.SystemClock.elapsedRealtime();
        HotwordService.hold("voice", true); // 호출어 듣기가 마이크를 놓는다 (이 창의 음성 인식이 쓰게)
        boolean fromHotword = getIntent().getBooleanExtra(EXTRA_HOTWORD, false);
        if (fromHotword) { // 화면이 꺼져 있거나 잠겨 있어도 보이게 (잠금은 아래에서 풀기를 요청)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }
        cfg = NotifyJob.config(this);
        hosts = cfg.optJSONArray("hosts");
        // 상태바·내비게이션 막대까지 투명하게 덮는다 (막대 위에 흰 띠가 생기지 않게)
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(120, 0, 0, 0));
        // 화면 끝까지 그리는 기기(안드로이드 15+)에서 대화 판이 내비게이션 막대 밑에 깔리지 않게
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(in.left, in.top, in.right, in.bottom);
            }
            return insets;
        });

        // 숨은 웹 화면 (맨 아래, 투명) — 앱과 같은 로그인 쿠키
        web = new WebView(this);
        web.setAlpha(0f);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 바깥 누르기 = 닫기 (웹 화면이 눌림을 받지 않게 그 위를 덮는다)
        View scrim = new View(this);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setupWeb();
        voice = new Voice(getApplicationContext());
        chat = new VoiceChat(this, voice, new VoiceChat.Host() {
            @Override
            public void ask(String id, String question) {
                askSite(id, question);
            }

            @Override
            public void keepAwake(boolean on) {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void ended() {
                close();
            }

            @Override
            public void quiet() {
                // 말이 없으면 잠깐 안내를 보인 뒤 닫는다 — 카드(진행 상황·원격 화면 등)를 보는 중이면 열어 둔다
                main.postDelayed(() -> {
                    if (chat == null || !chat.hasCard()) close();
                }, 2500);
            }

            @Override
            public void cardGone(View card) {
                if (card instanceof WebView) {
                    root.post(() -> {
                        ((WebView) card).stopLoading();
                        ((WebView) card).destroy();
                    });
                }
            }
        }, root, accent());

        if (fromHotword) unlockThenStart();
        else if (Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            // '자비스 대화' 아이콘(--voice-icon)·빅스비 빠른 명령어로 열렸다: 빅스비가 마이크를 놓을 틈을 잠깐 준다
            main.postDelayed(this::micThenStart, 700);
        } else micThenStart();
    }

    /** 잠긴 폰: 잠금 풀기(지문·PIN)를 먼저. 풀리면 듣기 시작, 취소하면 닫는다. */
    private void unlockThenStart() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked() || Build.VERSION.SDK_INT < 26) {
            micThenStart();
            return;
        }
        unlocking = true;
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                unlocking = false;
                micThenStart();
            }

            @Override
            public void onDismissCancelled() {
                unlocking = false;
                close();
            }

            @Override
            public void onDismissError() {
                unlocking = false;
                close();
            }
        });
    }

    private void micThenStart() {
        if (closing) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            chat.start();
        } else {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            chat.start();
        } else {
            Toast.makeText(this, "마이크 권한이 없어 음성 대화를 할 수 없어요. 설정 → 앱 → 이 앱 → 권한에서 마이크를 켜 주세요.",
                    Toast.LENGTH_LONG).show();
            close();
        }
    }

    // ---------- 숨은 웹 화면 ----------

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        if (cfg.optBoolean("desktop", false)) {
            s.setUserAgentString(s.getUserAgentString().replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                    .replace(" Mobile", ""));
        }
        CookieManager.getInstance().setAcceptCookie(true);
        // 답만 받는 통로 (듣기·읽어 주기 통로는 없다 — 사이트의 음성 기능이 마이크를 쓰지 않게)
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void answer(String t, String id, String text, boolean ok) {
                answer2(t, id, text, ok, "");
            }

            /** 답 + 덧붙임 {card: {path, height}, open: "/경로"} (urlappAsk 가 객체로 답할 때). */
            @JavascriptInterface
            public void answer2(String t, String id, String text, boolean ok, String extra) {
                if (!token.equals(t)) return;
                main.post(() -> onAnswer(id, text == null ? "" : text, ok, extra));
            }
        }, "UrlAppSpeech");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.deny(); // 숨은 화면은 마이크·카메라를 쓰지 않는다
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !own(r.getUrl()); // 사이트 밖으로는 가지 않는다
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!ready && !failed && own(Uri.parse(url))) probe(0);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) fail("자비스에 연결하지 못했어요. 인터넷(Tailscale) 연결을 확인해 주세요.");
            }
        });
        String url = cfg.optString("url", "");
        if (url.isEmpty()) fail("앱 설정에 주소가 없어요.");
        else web.loadUrl(url);
    }

    /** 사이트가 urlappAsk 를 준비했는지 (약 12초 기다림). 로그인 화면이면 준비되지 않는다. */
    private void probe(int attempt) {
        if (closing || web == null || ready || failed) return;
        web.evaluateJavascript("(function(){return typeof window.urlappAsk==='function'?'ask':'none';})()", v -> {
            if (closing || ready || failed) return;
            if (v != null && v.contains("ask")) {
                ready = true;
                if (pending != null) {
                    String[] p = pending;
                    pending = null;
                    askSite(p[0], p[1]);
                }
            } else if (attempt < 40) {
                main.postDelayed(() -> probe(attempt + 1), 300);
            } else {
                fail("자비스에 음성 대화가 준비되지 않았어요. 앱을 한 번 열어 로그인했는지 확인해 주세요.");
            }
        });
    }

    private void fail(String why) {
        if (failed) return;
        failed = true;
        failWhy = why;
        if (pending != null && chat != null) {
            pending = null;
            chat.fail(why);
        }
    }

    private void askSite(String id, String question) {
        if (closing) return;
        if (failed) {
            chat.fail(failWhy);
            return;
        }
        if (!ready) {
            pending = new String[] {id, question};
            return;
        }
        web.evaluateJavascript(askJs(question, id, token, ChatSession.current(this)), null);
    }

    /** 사이트의 urlappAsk 로 묻는 스크립트. 답이 객체면 text 와 함께 card·open 도 넘긴다. */
    static String askJs(String question, String id, String token, String session) {
        // 두 번째 인자 {session}: 15분 안의 질문은 같은 값 (ChatSession) — 사이트가 같은 대화로 이어 줄 수 있게. 안 보는 사이트는 무시한다
        return "(function(q,id,tok,ses){Promise.resolve().then(function(){return window.urlappAsk(q,ses?{session:ses}:undefined);})"
                + ".then(function(r){var o=r&&typeof r==='object'?r:null;"
                + "var s=o?(o.text||o.reply||o.answer||''):(r==null?'':r);var x='';"
                + "try{if(o&&(o.card||o.open))x=JSON.stringify({card:o.card||null,open:o.open||null});}catch(e){}"
                + "UrlAppSpeech.answer2(tok,id,String(s).slice(0,20000),true,String(x).slice(0,4000));},"
                + "function(){UrlAppSpeech.answer2(tok,id,'',false,'');});})("
                + JSONObject.quote(question) + "," + JSONObject.quote(id) + "," + JSONObject.quote(token) + ","
                + JSONObject.quote(session == null ? "" : session) + ")";
    }

    // ---------- 카드 (빅스비처럼: 날씨·진행 상황·원격 화면 …) ----------

    /** 답과 덧붙임을 처리한다: open 이면 앱을 그 경로로 열고 닫고, card 면 대화 창 안에 그 페이지를 보인다. */
    private void onAnswer(String id, String text, boolean ok, String extra) {
        if (chat == null || closing) return;
        JSONObject x = null;
        try {
            if (extra != null && !extra.isEmpty()) x = new JSONObject(extra);
        } catch (Exception e) {
            x = null;
        }
        String open = x == null ? "" : NotifyFeed.safePath(x.optString("open", ""));
        if (ok && !open.isEmpty() && chat.waitingFor(id)) {
            openInApp(open);
            return;
        }
        JSONObject c = x == null ? null : x.optJSONObject("card");
        String path = c == null ? "" : NotifyFeed.safePath(c.optString("path", ""));
        WebView cardView = ok && !path.isEmpty() && chat.waitingFor(id) ? cardWeb(path) : null;
        chat.answer(id, text, ok, cardView, c == null ? 0 : c.optInt("height", 0));
    }

    /** 앱을 이 사이트 경로로 연다 (예: 원격 화면을 크게). 대화 창은 닫는다. */
    private void openInApp(String path) {
        try {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_OPEN)
                    .putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "앱을 열지 못했어요.", Toast.LENGTH_SHORT).show();
        }
        close();
    }

    /** 카드: 보이는 웹 화면으로 같은 사이트 페이지 (같은 로그인 쿠키). 사이트 밖 이동·마이크·카메라는 막는다. */
    private WebView cardWeb(String path) {
        Uri base = Uri.parse(cfg.optString("url", ""));
        if (base.getScheme() == null || base.getEncodedAuthority() == null) return null;
        WebView v;
        try {
            v = new WebView(this);
        } catch (RuntimeException e) {
            return null;
        }
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 원격 화면 영상 등
        if (cfg.optBoolean("desktop", false)) {
            s.setUserAgentString(s.getUserAgentString().replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                    .replace(" Mobile", ""));
        }
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.deny();
            }
        });
        v.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                if (own(r.getUrl())) return false;
                return true; // 사이트 밖으로는 가지 않는다
            }
        });
        v.loadUrl(base.getScheme() + "://" + base.getEncodedAuthority() + path);
        return v;
    }

    private boolean own(Uri u) {
        String h = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String scheme = u == null || u.getScheme() == null ? "" : u.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        for (int i = 0; hosts != null && i < hosts.length(); i++) {
            String o = hosts.optString(i).toLowerCase(Locale.ROOT);
            if (!o.isEmpty() && (h.equals(o) || h.endsWith("." + o))) return true;
        }
        return false;
    }

    // ---------- 닫기 ----------

    private void close() {
        if (closing) return;
        closing = true;
        if (chat != null) chat.stop();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !unlocking) close(); // 다른 앱으로 가면 마이크를 놓고 닫는다 (잠금 푸는 중은 제외)
    }

    @Override
    protected void onDestroy() {
        closing = true;
        HotwordService.hold("voice", false);
        main.removeCallbacksAndMessages(null);
        if (voice != null) voice.destroy();
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        CookieManager.getInstance().flush();
        super.onDestroy();
    }

    private int accent() {
        try {
            return Color.parseColor(cfg.optString("accent", "#2DD4DA"));
        } catch (IllegalArgumentException e) {
            return Color.rgb(45, 212, 218);
        }
    }

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
