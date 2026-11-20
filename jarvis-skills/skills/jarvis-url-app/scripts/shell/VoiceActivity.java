package app.jarvis.urlapp;

// 위젯의 마이크 단추 — 빅스비처럼 홈 화면 위에 작은 대화 창만 띄워 바로 듣는다 (--mic 앱).
// 앱 화면은 열지 않는다. 사이트는 뒤에서 보이지 않게(투명) 열어 window.urlappAsk(질문) 로 묻고, 답은 폰의 읽어 주기로 말한다.
// 이 숨은 화면에는 음성 도우미(speech.js)를 넣지 않는다 — 사이트의 호출어 듣기("안녕, 자비스")가 폰 음성 인식을 붙잡아
// 대화 창의 듣기를 계속 끊던 문제(2026-11-20 '다시 듣기·끝내기가 안 눌린다')를 피한다. 사이트의 마이크 요청도 거절한다.
// 듣기는 창이 뜨자마자 시작하고(사이트가 뜨는 동안 말해도 된다), 질문은 사이트가 준비되면 보낸다.
// 바깥을 누르거나 '끝내기', 두 번 조용하거나, 다른 앱으로 가면 닫힌다.

import android.Manifest;
import android.app.Activity;
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
    private static final int REQ_MIC = 51;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token = newToken();
    private JSONObject cfg;
    private JSONArray hosts;
    private FrameLayout root;
    private WebView web;
    private Voice voice;
    private VoiceChat chat;
    private boolean ready, failed, closing;
    private String failWhy = "";
    private String[] pending; // {id, 질문} — 사이트가 준비되기 전에 나온 질문

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = NotifyJob.config(this);
        hosts = cfg.optJSONArray("hosts");
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
                main.postDelayed(() -> close(), 2500); // 말이 없으면 잠깐 안내를 보인 뒤 닫는다
            }
        }, root, accent());

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
                if (!token.equals(t)) return;
                main.post(() -> {
                    if (chat != null) chat.answer(id, text == null ? "" : text, ok);
                });
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
        String js = "(function(q,id,tok){Promise.resolve().then(function(){return window.urlappAsk(q);})"
                + ".then(function(r){var s=r&&typeof r==='object'?(r.text||r.reply||r.answer||''):(r==null?'':r);"
                + "UrlAppSpeech.answer(tok,id,String(s).slice(0,20000),true);},"
                + "function(){UrlAppSpeech.answer(tok,id,'',false);});})("
                + JSONObject.quote(question) + "," + JSONObject.quote(id) + "," + JSONObject.quote(token) + ")";
        web.evaluateJavascript(js, null);
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
        if (!isChangingConfigurations()) close(); // 다른 앱으로 가면 마이크를 놓고 닫는다
    }

    @Override
    protected void onDestroy() {
        closing = true;
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
