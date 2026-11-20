package app.jarvis.urlapp;

// URL 하나를 독립 앱처럼 띄우는 안드로이드 껍데기 (Hermit 의 "라이트 앱"과 같은 방식).
// 안드로이드 기본 API 만 쓴다 (androidx 등 외부 라이브러리 없음). 앱마다 다른 값은 모두
// assets/urlapp.json 에서 읽으므로 이 코드는 한 번만 컴파일해 모든 앱이 같이 쓴다.

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final String ACTION_HOME = "app.jarvis.urlapp.action.HOME";
    static final String ACTION_BROWSER = "app.jarvis.urlapp.action.BROWSER";
    static final String ACTION_CARDS = "app.jarvis.urlapp.action.CARDS"; // 카드 자동 등록 설정 (그 기능이 있는 앱만)
    static final String ACTION_UPDATE = "app.jarvis.urlapp.action.UPDATE"; // 업데이트 확인 (업데이트 주소가 있는 앱만)
    static final String ACTION_MIC = "app.jarvis.urlapp.action.MIC"; // 마이크 모드 (위젯의 마이크 단추·바로가기, --mic 앱만)
    static final String ACTION_HOTWORD = "app.jarvis.urlapp.action.HOTWORD"; // '하이 자비스' 설정 (--hotword 앱만)
    static final String ACTION_OPEN = "app.jarvis.urlapp.action.OPEN"; // 알림을 눌렀을 때: extra "path"(같은 사이트 경로)를 연다
    private static final int REQ_FILES = 41;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private JSONObject cfg;
    private String startUrl;
    private final ArrayList<String> ownHosts = new ArrayList<>();
    private boolean linksToBrowser;
    private boolean fullscreen;
    private int themeColor;
    private int bgColor;

    private FrameLayout root;
    private View statusBg;
    private FrameLayout content;
    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private boolean mainFrameError;
    private String failedUrl;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private Object backCallback; // API 33+ 의 OnBackInvokedCallback (옛 기기에서 클래스를 건드리지 않으려고 Object)
    private boolean backRegistered;

    // 페이지가 만든 파일(blob:, data:) 받기. 도우미 스크립트(assets/urlapp-download.js)는 자기 사이트의 맨 위 화면에만
    // 넣고, 앱과의 통신은 실행할 때마다 새로 만드는 토큰이 있어야 한다 (다른 사이트의 iframe 은 토큰을 모른다).
    private static final long MAX_DOWNLOAD = 200L * 1024 * 1024;
    private final String dlToken = newToken();
    private String downloadJs;
    private String widgetJs; // 위젯이 있는 앱만 (assets/urlapp-widget.js)

    // 음성 (--mic 로 만든 앱만): 웹 음성 기능을 폰의 음성 인식·읽어 주기로 대신하는 도우미(assets/urlapp-speech.js),
    // 사이트의 getUserMedia 마이크 허락, 마이크 모드(사이트의 urlappMic 또는 앱이 직접 하는 대화 VoiceChat + urlappAsk).
    private static final int REQ_MIC = 41;
    private String speechJs;
    private Voice voice;
    private VoiceChat chat;
    private boolean pageReady;  // 자기 사이트 페이지를 다 읽었다
    private boolean micPending; // 마이크 단추로 열렸다: 페이지가 뜨면 음성 대화를 시작한다
    private boolean micAsking;
    private boolean resumed;    // 앱이 앞에 보이는 중 (사이트의 듣기는 이때만)
    private final ArrayList<Runnable[]> micWaiters = new ArrayList<>(); // {허락되면, 거절되면}

    // 알림 (--notify 로 만든 앱만): 웹 알림(Notification)을 폰 알림으로 (assets/urlapp-notify.js), 15분마다 사이트 알림 확인(NotifyJob)
    private static final int REQ_NOTIFY = 42;
    private String notifyJs;
    private boolean notifyAsking;
    private final HashMap<String, PendingFile> pendingFiles = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = loadConfig();
        startUrl = cfg.optString("url");
        JSONArray hosts = cfg.optJSONArray("hosts");
        if (hosts != null) {
            for (int i = 0; i < hosts.length(); i++) ownHosts.add(hosts.optString(i).toLowerCase(Locale.ROOT));
        }
        linksToBrowser = !"app".equals(cfg.optString("external", "browser"));
        fullscreen = cfg.optBoolean("fullscreen", false);
        themeColor = parseColor(cfg.optString("theme_color"), Color.WHITE);
        bgColor = parseColor(cfg.optString("background_color"), Color.WHITE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night && Build.VERSION.SDK_INT >= 29) {
            // 안드로이드 10+ 다크 모드에서는 웹 화면도 어두운 화면을 요청하므로, 사이트가 준 다크용 색을 쓴다.
            themeColor = parseColor(cfg.optString("theme_color_dark"), themeColor);
        }
        if (cfg.optBoolean("keep_awake", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(bgColor);
        statusBg = new View(this);
        statusBg.setBackgroundColor(themeColor);
        root.addView(statusBg, new FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        web = new WebView(this);
        web.setBackgroundColor(bgColor);
        content.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setProgressTintList(ColorStateList.valueOf(accentColor()));
        progress.setVisibility(View.GONE);
        content.addView(progress, new FrameLayout.LayoutParams(MATCH, dp(4), Gravity.TOP));

        setContentView(root);
        setupSystemBars();
        setupWebView();

        if (state == null) {
            Context appCtx = getApplicationContext(); // 호출어를 뺀 버전이면 남은 음성 모델을 지운다 (뒤에서)
            new Thread(() -> HotwordModel.cleanupIfUnused(appCtx), "hotword-cleanup").start();
            Intent in = getIntent();
            if (!handleViewLink(in) && (!handleShortcut(in) || ACTION_CARDS.equals(in.getAction()))) web.loadUrl(startUrl);
            maybeOfferCards();
        } else if (web.restoreState(state) == null) { // 화면 회전·다크 모드 전환 뒤에는 보던 페이지를 되살린다
            web.loadUrl(startUrl);
        }
        updateBackHandling();
    }

    // ---------- 설정 ----------

    private JSONObject loadConfig() {
        try {
            return new JSONObject(readAsset("urlapp.json"));
        } catch (Exception e) {
            throw new IllegalStateException("assets/urlapp.json 을 읽을 수 없습니다", e);
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String readAssetOrNull(String name) {
        try {
            return readAsset(name);
        } catch (IOException e) {
            return null; // 이 앱에는 없는 기능
        }
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static int parseColor(String s, int fallback) {
        try {
            return s == null || s.isEmpty() ? fallback : Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static boolean isLight(int c) {
        double y = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return y > 160;
    }

    private int accentColor() {
        int explicit = parseColor(cfg.optString("accent"), 0);
        if (explicit != 0) return explicit;
        // 진행 막대는 웹 화면 위에 그려지므로 배경과 구분되는 색을 쓴다.
        if (Math.abs(luma(themeColor) - luma(bgColor)) > 60) return themeColor;
        return isLight(bgColor) ? Color.rgb(26, 115, 232) : Color.rgb(138, 180, 248);
    }

    private static double luma(int c) {
        return 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String t(String ko, String en) {
        return "ko".equals(Locale.getDefault().getLanguage()) ? ko : en;
    }

    // ---------- 상태 표시줄·내비게이션 바 ----------

    @SuppressWarnings("deprecation")
    private void setupSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 35 || (fullscreen && Build.VERSION.SDK_INT >= 30)) {
            // 안드로이드 15+ 는 화면 끝까지 그리기(edge-to-edge)가 강제된다. 막대 아래 영역은 직접 칠하고,
            // 웹 화면은 막대·노치·키보드 영역만큼 띄운다 (키보드가 올라오면 웹 화면이 줄어든다).
            w.setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int types = WindowInsets.Type.displayCutout() | WindowInsets.Type.ime();
                if (!fullscreen || customView != null) types |= WindowInsets.Type.systemBars();
                android.graphics.Insets in = insets.getInsets(types);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
                lp.setMargins(in.left, in.top, in.right, in.bottom);
                content.setLayoutParams(lp);
                ViewGroup.LayoutParams sp = statusBg.getLayoutParams();
                sp.height = in.top;
                statusBg.setLayoutParams(sp);
                return WindowInsets.CONSUMED;
            });
        } else {
            w.setStatusBarColor(themeColor);
            w.setNavigationBarColor(bgColor);
            if (fullscreen) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        applyBarAppearance();
        if (fullscreen) setBarsHidden(true);
    }

    @SuppressWarnings("deprecation")
    private void applyBarAppearance() {
        boolean lightStatus = isLight(themeColor);
        boolean lightNav = isLight(bgColor);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            int val = (lightStatus ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                    | (lightNav ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0);
            c.setSystemBarsAppearance(val, mask);
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            f = lightStatus ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (Build.VERSION.SDK_INT >= 26) {
                f = lightNav ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            d.setSystemUiVisibility(f);
        }
    }

    @SuppressWarnings("deprecation")
    private void setBarsHidden(boolean hide) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = getWindow().getDecorView();
            if (hide) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            } else {
                d.setSystemUiVisibility(0);
                applyBarAppearance();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (fullscreen || customView != null)) setBarsHidden(true);
    }

    // ---------- 웹 화면 ----------

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false); // target=_blank 링크는 같은 화면에서 연다
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        if (cfg.optBoolean("desktop", false)) s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));
        // --live: 사이트를 자주 고치는 앱. 저장(캐시)한 화면·스크립트를 쓰지 않고 늘 서버에서 새로 받는다 (고친 내용이 바로 보이게)
        if (cfg.optBoolean("live", false)) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if ("darken".equals(cfg.optString("dark")) && Build.VERSION.SDK_INT >= 33) {
            // 기기가 다크 모드일 때, 다크 모드를 지원하지 않는 사이트를 자동으로 어둡게 칠한다.
            s.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        downloadJs = readAssetOrNull("urlapp-download.js");
        widgetJs = readAssetOrNull("urlapp-widget.js");
        if (downloadJs != null || widgetJs != null) web.addJavascriptInterface(new Bridge(), "UrlAppBridge");
        speechJs = readAssetOrNull("urlapp-speech.js");
        if (speechJs != null) web.addJavascriptInterface(new SpeechBridge(), "UrlAppSpeech");
        notifyJs = readAssetOrNull("urlapp-notify.js");
        if (notifyJs != null) web.addJavascriptInterface(new NotifyBridge(), "UrlAppNotify");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return route(r.getUrl(), r.hasGesture() && !r.isRedirect());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                mainFrameError = false;
                pageReady = false;
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                injectSpeech(url); // 음성 도우미는 되도록 일찍 (사이트가 음성 기능을 찾기 전에)
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!mainFrameError && errorView != null) errorView.setVisibility(View.GONE);
                injectHelpers(url);
                updateBackHandling();
                pageReady = !mainFrameError && url != null && isOwnHost(Uri.parse(url).getHost());
                if (pageReady && micPending) startMicMode();
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                updateBackHandling();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    mainFrameError = true;
                    failedUrl = r.getUrl().toString();
                    showError();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // 웹 화면 프로세스가 죽어도 앱 전체가 꺼지지 않게 화면을 새로 만든다.
                if (v == web) {
                    content.removeView(web);
                    web.destroy();
                    web = null;
                    recreate();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                return openFileChooser(cb, p);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                enterCustomView(view, cb);
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                runOnUiThread(() -> webPermission(r));
            }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> download(url, ua, disposition, mime));
    }

    private static String desktopUserAgent(String ua) {
        return ua.replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)").replace(" Mobile", "");
    }

    private boolean isOwnHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String own : ownHosts) {
            if (h.equals(own) || h.endsWith("." + own)) return true;
        }
        return false;
    }

    /** true 를 돌려주면 웹 화면이 그 주소를 열지 않는다. */
    private boolean route(Uri uri, boolean userTapped) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "http":
            case "https":
                // Google 로그인 화면은 앱 안 웹 화면(WebView)에서 열면 Google 이 막는다 (403 disallowed_useragent).
                // 크롬(Custom Tab)에서 열고, 사이트가 로그인 결과를 앱으로 돌려보내면(아래 handleViewLink) 이어 간다.
                if (isGoogleSignIn(uri)) {
                    openSignInTab(uri);
                    return true;
                }
                // 사용자가 직접 누른 다른 사이트 링크만 브라우저로 보낸다. 로그인처럼 페이지가 스스로
                // 넘어가는 이동(리디렉션, 스크립트 이동)은 앱 안에 둔다.
                if (!linksToBrowser || !userTapped || isOwnHost(uri.getHost())) return false;
                openExternal(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            case "about":
            case "data":
            case "blob":
            case "javascript":
                return false;
            case "file":
            case "content":
                return true; // 웹 페이지가 기기 안 파일을 열지 못하게 막는다
            case "intent":
                try {
                    Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null); // 웹 페이지가 특정 앱의 내부 화면을 직접 부르지 못하게
                    i.setSelector(null);
                    if (!startSafely(i)) {
                        String fallback = i.getStringExtra("browser_fallback_url");
                        if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                            web.loadUrl(fallback);
                        } else {
                            toast(t("이 링크를 열 앱이 없어요.", "No app can open this link."));
                        }
                    }
                } catch (URISyntaxException e) {
                    toast(t("이 링크를 열 수 없어요.", "Can't open this link."));
                }
                return true;
            default: // tel:, mailto:, sms:, market:, 카카오톡 같은 앱 링크
                if (!startSafely(new Intent(Intent.ACTION_VIEW, uri))) {
                    toast(t("이 링크를 열 앱이 없어요.", "No app can open this link."));
                }
                return true;
        }
    }

    /** Google 계정 로그인·동의 화면 (WebView 에서는 Google 이 막는 곳). */
    static boolean isGoogleSignIn(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"accounts.google.com".equals(host)) return false;
        String path = uri.getPath() == null ? "" : uri.getPath();
        return !path.startsWith("/gsi/client") && !path.startsWith("/gsi/style"); // 스크립트·스타일은 그대로
    }

    /** 크롬 Custom Tab(없으면 기본 브라우저)으로 연다. 앱 안 화면은 그대로 남아 로그인 뒤 돌아올 자리가 된다. */
    private void openSignInTab(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        Bundle extras = new Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null); // Custom Tab 으로 열어 달라는 표시
        i.putExtras(extras);
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", themeColor);
        toast(t("Google 로그인은 크롬에서 열어요. 끝나면 앱으로 돌아와요.", "Google sign-in opens in Chrome, then returns here."));
        openExternal(i);
    }

    /**
     * 이 앱의 사이트 주소로 앱이 열렸을 때 (예: 크롬에서 Google 로그인을 마친 사이트가 "앱으로 돌아가기"로 부름).
     * ?urlapp_return=<원래 주소> 가 있으면 그 주소(해시의 로그인 결과 포함)를 연다. 자기 사이트(https) 주소만.
     */
    private boolean handleViewLink(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return false;
        Uri u = i.getData();
        if (!"https".equalsIgnoreCase(u.getScheme()) || !isOwnHost(u.getHost())) return false;
        String back = null;
        try {
            back = u.getQueryParameter("urlapp_return");
        } catch (RuntimeException e) {
            back = null;
        }
        String target = back != null ? back : u.toString();
        Uri b = Uri.parse(target);
        if (!"https".equalsIgnoreCase(b.getScheme()) || !isOwnHost(b.getHost())) return false;
        String cur = web.getUrl();
        if (cur != null && noFragment(cur).equals(noFragment(target)) && !cur.equals(target)) {
            // 같은 페이지에서 #… 만 다르면 웹 화면은 다시 읽지 않는다 → 주소를 바꾼 뒤 다시 읽어 사이트의 시작 코드가 #… 를 보게
            web.evaluateJavascript("(function(u){try{history.replaceState(history.state,'',u);location.reload();}"
                    + "catch(e){location.href=u;}})(" + JSONObject.quote(target) + ")", null);
        } else {
            web.loadUrl(target);
        }
        return true;
    }

    private static String noFragment(String url) {
        int h = url.indexOf('#');
        return h < 0 ? url : url.substring(0, h);
    }

    private boolean startSafely(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void openExternal(Intent i) {
        if (!startSafely(i)) toast(t("이 링크를 열 앱이 없어요.", "No app can open this link."));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------- 오류 화면 ----------

    private void showError() {
        if (errorView == null) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(32), dp(32), dp(32), dp(32));
            box.setBackgroundColor(bgColor);
            box.setClickable(true);
            int fg = isLight(bgColor) ? Color.rgb(32, 33, 36) : Color.rgb(232, 234, 237);

            TextView title = new TextView(this);
            title.setText(t("페이지를 열 수 없어요", "Can't open this page"));
            title.setTextSize(20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(fg);
            title.setGravity(Gravity.CENTER);
            box.addView(title);

            TextView msg = new TextView(this);
            msg.setText(t("인터넷 연결을 확인한 뒤 다시 시도해 주세요.", "Check your connection and try again."));
            msg.setTextSize(15);
            msg.setTextColor(fg);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(12), 0, dp(20));
            box.addView(msg);

            Button retry = new Button(this);
            retry.setText(t("다시 시도", "Try again"));
            retry.setOnClickListener(v -> {
                errorView.setVisibility(View.GONE);
                web.loadUrl(failedUrl != null ? failedUrl : startUrl);
            });
            box.addView(retry);

            errorView = box;
            content.addView(errorView, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        errorView.setVisibility(View.VISIBLE);
        progress.bringToFront();
    }

    // ---------- 파일 올리기 ----------

    private boolean openFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = cb;
        ArrayList<String> mimes = new ArrayList<>();
        String[] accept = p.getAcceptTypes();
        if (accept != null) {
            for (String raw : accept) {
                for (String part : raw.split(",")) {
                    String a = part.trim().toLowerCase(Locale.ROOT);
                    if (a.isEmpty()) continue;
                    if (a.startsWith(".")) {
                        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1));
                        if (m != null) mimes.add(m);
                    } else if (a.contains("/")) {
                        mimes.add(a);
                    }
                }
            }
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes.size() == 1) {
            i.setType(mimes.get(0));
        } else {
            i.setType("*/*");
            if (mimes.size() > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(Intent.createChooser(i, null), REQ_FILES);
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            cb.onReceiveValue(null);
        }
        return true;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQ_FILES || fileCallback == null) {
            super.onActivityResult(request, result, data);
            return;
        }
        Uri[] picked = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                picked = new Uri[clip.getItemCount()];
                for (int k = 0; k < picked.length; k++) picked[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[] {data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    // ---------- 다운로드 ----------

    private void download(String url, String ua, String disposition, String mime) {
        if (url != null && (url.startsWith("blob:") || url.startsWith("data:"))) {
            // 도우미가 클릭을 놓친 경우(문서에 붙지 않은 링크 등): 페이지 안에서 파일을 꺼내 저장한다.
            String js = "(function(){if(window.__urlappSave){window.__urlappSave(" + JSONObject.quote(url) + ","
                    + JSONObject.quote(nameFromDisposition(disposition)) + ");return 'ok';}return 'no';})()";
            web.evaluateJavascript(js, r -> {
                if (!"\"ok\"".equals(r)) {
                    toast(t("이 파일은 앱 안에서 받을 수 없어요. 브라우저에서 열어 주세요.",
                            "This file can't be downloaded in the app. Open it in a browser."));
                }
            });
            return;
        }
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            toast(t("이 파일은 앱 안에서 받을 수 없어요. 브라우저에서 열어 주세요.",
                    "This file can't be downloaded in the app. Open it in a browser."));
            return;
        }
        enqueueDownload(url, ua, URLUtil.guessFileName(url, disposition, mime), mime);
    }

    private void enqueueDownload(String url, String ua, String fileName, String mime) {
        String name = safeFileName(fileName, mime);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setMimeType(mimeFor(name, mime));
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast(t("내려받는 중: ", "Downloading: ") + name);
        } catch (RuntimeException e) {
            openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    /** 다운로드 도우미와 위젯 실행기는 자기 사이트의 맨 위 화면에만 넣는다 (evaluateJavascript 는 맨 위 화면에서만 돈다). */
    private void injectHelpers(String url) {
        if (url == null || web == null) return;
        if (!isOwnHost(Uri.parse(url).getHost())) return;
        String token = JSONObject.quote(dlToken);
        if (downloadJs != null) web.evaluateJavascript(downloadJs.replace("\"__URLAPP_TOKEN__\"", token), null);
        if (widgetJs != null) web.evaluateJavascript(widgetJs.replace("\"__URLAPP_TOKEN__\"", token), null);
        injectSpeech(url);
    }

    /** 음성·알림 도우미는 되도록 일찍 (사이트가 그 기능을 찾기 전에). 두 번 넣어도 한 번만 돈다. */
    private void injectSpeech(String url) {
        if (url == null || web == null || !isOwnHost(Uri.parse(url).getHost())) return;
        String token = JSONObject.quote(dlToken);
        if (speechJs != null) web.evaluateJavascript(speechJs.replace("\"__URLAPP_TOKEN__\"", token), null);
        if (notifyJs != null) web.evaluateJavascript(notifyJs.replace("\"__URLAPP_TOKEN__\"", token), null);
    }

    // ---------- 알림 (--notify) ----------

    /** 웹 알림의 권한 상태: granted / denied / default(아직 안 물음). */
    private String notifyState() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return "granted";
            return getSharedPreferences(NotifyJob.PREFS, MODE_PRIVATE).getBoolean("asked", false) ? "denied" : "default";
        }
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled() ? "granted" : "denied";
    }

    /** 알림 권한 묻기 (안드로이드 13+). force 가 아니면 처음 한 번만. 다른 권한을 묻는 중이면 다음에. */
    private void askNotify(boolean force) {
        if (notifyJs == null) return;
        if (Build.VERSION.SDK_INT < 33 || !"default".equals(notifyState()) && !force) {
            sendNotifyState();
            return;
        }
        if ("granted".equals(notifyState())) {
            sendNotifyState();
            return;
        }
        if (notifyAsking || micAsking) return;
        notifyAsking = true;
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void sendNotifyState() {
        if (web == null || notifyJs == null) return;
        String u = web.getUrl();
        if (u == null || !isOwnHost(Uri.parse(u).getHost())) return;
        web.evaluateJavascript("window.__urlappNotifyPermission&&window.__urlappNotifyPermission("
                + JSONObject.quote(notifyState()) + ")", null);
    }

    /** 알림 도우미(notify.js)만 부르는 통로. 모든 호출에 토큰이 맞아야 한다. */
    private final class NotifyBridge {
        @JavascriptInterface
        public String permission(String token) {
            return dlToken.equals(token) ? notifyState() : "denied";
        }

        @JavascriptInterface
        public void request(String token) {
            if (dlToken.equals(token)) runOnUiThread(() -> askNotify(true));
        }

        @JavascriptInterface
        public String show(String token, String title, String body, String tag, String path) {
            if (!dlToken.equals(token)) return "";
            String key = "w:" + (tag == null || tag.isEmpty() ? newToken().substring(0, 12) : tag);
            boolean ok = NotifyJob.show(getApplicationContext(), key, NotifyFeed.clip(title, 80), NotifyFeed.clip(body, 300),
                    NotifyFeed.safePath(path));
            return ok ? key : "";
        }

        @JavascriptInterface
        public void close(String token, String key) {
            if (dlToken.equals(token) && key != null && key.startsWith("w:")) NotifyJob.cancel(getApplicationContext(), key);
        }
    }

    // ---------- 음성 (--mic) ----------

    private Voice voice() {
        if (voice == null) voice = new Voice(getApplicationContext());
        return voice;
    }

    private VoiceChat chat() {
        if (chat == null) {
            chat = new VoiceChat(this, voice(), new VoiceChat.Host() {
                @Override
                public void ask(String id, String question) {
                    askSite(id, question);
                }

                @Override
                public void keepAwake(boolean on) {
                    if (cfg.optBoolean("keep_awake", false)) return;
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }, content, accentColor());
        }
        return chat;
    }

    /** 마이크 권한이 있으면 바로, 없으면 한 번 묻고 결과에 따라. */
    private void withMic(Runnable granted, Runnable denied) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        micWaiters.add(new Runnable[] {granted, denied});
        if (!micAsking && !notifyAsking) {
            micAsking = true;
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIFY) {
            notifyAsking = false;
            if (results.length > 0) getSharedPreferences(NotifyJob.PREFS, MODE_PRIVATE).edit().putBoolean("asked", true).apply();
            sendNotifyState();
            if (!micWaiters.isEmpty() && !micAsking) { // 알림 권한을 묻는 동안 기다린 마이크 요청
                micAsking = true;
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
            return;
        }
        if (code != REQ_MIC) return;
        micAsking = false;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        ArrayList<Runnable[]> w = new ArrayList<>(micWaiters);
        micWaiters.clear();
        for (Runnable[] r : w) (ok ? r[0] : r[1]).run();
    }

    /** 사이트의 getUserMedia 마이크 요청: 이 앱이 --mic 이고 자기 사이트(https)일 때만 소리만 허락한다. */
    private void webPermission(PermissionRequest r) {
        boolean audio = false;
        for (String res : r.getResources()) audio |= PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res);
        Uri o = r.getOrigin();
        if (speechJs == null || !audio || o == null || !"https".equalsIgnoreCase(o.getScheme()) || !isOwnHost(o.getHost())) {
            r.deny();
            return;
        }
        withMic(() -> r.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE}), r::deny);
    }

    /** 마이크 모드: 사이트의 urlappMic(사이트가 직접 하는 음성 모드)이 먼저, 없으면 urlappAsk 로 앱이 직접 대화. */
    private void startMicMode() {
        micPending = false;
        withMic(() -> probeVoice(0), () -> toast(t(
                "마이크 권한이 없어 음성 대화를 할 수 없어요. 설정 → 앱 → 이 앱 → 권한에서 마이크를 켜 주세요.",
                "Microphone permission is needed for voice mode.")));
    }

    private void probeVoice(int attempt) {
        if (web == null) return;
        String u = web.getUrl();
        if (u == null || !isOwnHost(Uri.parse(u).getHost())) return;
        web.evaluateJavascript("(function(){return typeof window.urlappMic==='function'?'mic':"
                + "(typeof window.urlappAsk==='function'?'ask':'none');})()", v -> {
            String kind = v == null ? "" : v.replace("\"", "");
            if ("mic".equals(kind)) {
                web.evaluateJavascript("try{window.urlappMic();}catch(e){}", null);
            } else if ("ask".equals(kind)) {
                chat().start();
            } else if (attempt < 16) { // 사이트가 아직 준비 중일 수 있다 (약 5초 기다림)
                web.postDelayed(() -> probeVoice(attempt + 1), 300);
            } else {
                toast(t("이 사이트에는 아직 음성 대화 기능이 없어요 (urlappMic 또는 urlappAsk).",
                        "This site has no voice mode yet (urlappMic or urlappAsk)."));
            }
        });
    }

    /** VoiceChat 의 질문을 사이트의 urlappAsk 에 넘긴다. 답(글, 또는 {text})은 통로의 answer 로 돌아온다. */
    private void askSite(String id, String question) {
        if (web == null) return;
        String js = "(function(q,id,tok){Promise.resolve().then(function(){return window.urlappAsk(q);})"
                + ".then(function(r){var s=r&&typeof r==='object'?(r.text||r.reply||r.answer||''):(r==null?'':r);"
                + "UrlAppSpeech.answer(tok,id,String(s).slice(0,20000),true);},"
                + "function(){UrlAppSpeech.answer(tok,id,'',false);});})("
                + JSONObject.quote(question) + "," + JSONObject.quote(id) + "," + JSONObject.quote(dlToken) + ")";
        web.evaluateJavascript(js, null);
    }

    /** 안드로이드 음성 결과를 음성 도우미(speech.js)에 넘긴다 (자기 사이트 페이지일 때만). */
    private void sendSpeech(JSONObject ev) {
        if (web == null) return;
        String u = web.getUrl();
        if (u == null || !isOwnHost(Uri.parse(u).getHost())) return;
        web.evaluateJavascript("window.__urlappSpeechEvent&&window.__urlappSpeechEvent(" + ev + ")", null);
    }

    /** 음성 도우미(speech.js)만 부르는 통로. 모든 호출에 토큰이 맞아야 한다. */
    private final class SpeechBridge {
        @JavascriptInterface
        public void listen(String token, String lang, boolean partial) {
            if (!dlToken.equals(token)) return;
            runOnUiThread(() -> {
                // 앱이 뒤에 있거나 음성 대화(VoiceChat)·위젯 대화 창이 마이크를 쓰는 동안은 사이트의 듣기(호출어 등)를 받지 않는다
                // (2026-11-20: 사이트의 호출어 듣기가 폰 음성 인식을 붙잡아 대화 판의 듣기를 계속 끊었다)
                if (!resumed || (chat != null && chat.active())) {
                    sendSpeech(Voice.ev("error").put2("error", "audio-capture"));
                    sendSpeech(Voice.ev("end"));
                    return;
                }
                withMic(() -> voice().listen(lang, partial, MainActivity.this::sendSpeech), () -> {
                    sendSpeech(Voice.ev("error").put2("error", "not-allowed"));
                    sendSpeech(Voice.ev("end"));
                });
            });
        }

        @JavascriptInterface
        public void stop(String token) {
            if (dlToken.equals(token)) runOnUiThread(() -> voice().stopListening());
        }

        @JavascriptInterface
        public void abort(String token) {
            if (dlToken.equals(token)) runOnUiThread(() -> voice().cancelListening(true));
        }

        @JavascriptInterface
        public void speak(String token, String id, String text, String lang, double rate, double pitch) {
            if (!dlToken.equals(token) || id == null || id.length() > 40) return;
            runOnUiThread(() -> voice().speak(id, text, lang, (float) rate, (float) pitch, MainActivity.this::sendSpeech));
        }

        @JavascriptInterface
        public void cancelSpeak(String token) {
            if (dlToken.equals(token)) runOnUiThread(() -> voice().stopSpeaking());
        }

        @JavascriptInterface
        public void answer(String token, String id, String text, boolean ok) {
            if (!dlToken.equals(token)) return;
            runOnUiThread(() -> {
                if (chat != null) chat.answer(id, text == null ? "" : text, ok);
            });
        }
    }

    private static final Pattern DISPOSITION_NAME = Pattern.compile(
            "filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)|filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    static String nameFromDisposition(String disposition) {
        if (disposition == null) return "";
        Matcher m = DISPOSITION_NAME.matcher(disposition);
        if (!m.find()) return "";
        String v = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            return Uri.decode(v.trim());
        } catch (RuntimeException e) {
            return v.trim();
        }
    }

    /** 폴더 구분자·제어 문자를 빼고, 확장자가 없으면 MIME 으로 붙인다. 비어 있으면 날짜 이름. */
    static String safeFileName(String name, String mime) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (n.isEmpty()) n = "download-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        if (n.lastIndexOf('.') <= 0 && mime != null && !mime.isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.split(";")[0].trim());
            if (ext != null) n += "." + ext;
        }
        return n;
    }

    static String mimeFor(String fileName, String mime) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            String byExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (byExt != null) return byExt;
        }
        if (mime != null && !mime.trim().isEmpty()) return mime.split(";")[0].trim();
        return "application/octet-stream";
    }

    private static final class PendingFile {
        final File tmp;
        final FileOutputStream out;
        final String name;
        final String mime;
        long written;

        PendingFile(File tmp, String name, String mime) throws IOException {
            this.tmp = tmp;
            this.out = new FileOutputStream(tmp);
            this.name = name;
            this.mime = mime;
        }

        void discard() {
            try {
                out.close();
            } catch (IOException ignored) {
                // 지우기 전 닫기 실패는 무시
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 다운로드 도우미(urlapp-download.js)만 부르는 통로. 모든 호출에 토큰이 맞아야 한다. 호출은 웹 화면의 별도 스레드에서 온다. */
    private final class Bridge {
        @JavascriptInterface
        public String begin(String token, String name, String mime, double size) {
            if (!dlToken.equals(token)) return "";
            if (size > MAX_DOWNLOAD) {
                runOnUiThread(() -> toast(t("파일이 너무 커서(200MB 초과) 앱에서 받을 수 없어요.",
                        "The file is too large (over 200MB) to save in the app.")));
                return "";
            }
            try {
                File dir = new File(getCacheDir(), "urlapp-downloads");
                if (!dir.isDirectory() && !dir.mkdirs()) return "";
                String id = newToken().substring(0, 16);
                PendingFile f = new PendingFile(new File(dir, id + ".part"), name, mime);
                synchronized (pendingFiles) {
                    pendingFiles.put(id, f);
                }
                return id;
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean chunk(String token, String id, String base64) {
            if (!dlToken.equals(token)) return false;
            PendingFile f;
            synchronized (pendingFiles) {
                f = pendingFiles.get(id);
            }
            if (f == null) return false;
            try {
                byte[] b = Base64.decode(base64, Base64.DEFAULT);
                f.written += b.length;
                if (f.written > MAX_DOWNLOAD) throw new IOException("too large");
                f.out.write(b);
                return true;
            } catch (IOException | IllegalArgumentException e) {
                drop(id);
                runOnUiThread(() -> toast(t("파일을 저장하지 못했어요.", "Couldn't save the file.")));
                return false;
            }
        }

        @JavascriptInterface
        public void end(String token, String id) {
            if (!dlToken.equals(token)) return;
            PendingFile f;
            synchronized (pendingFiles) {
                f = pendingFiles.remove(id);
            }
            if (f == null) return;
            try {
                f.out.close();
                String name = safeFileName(f.name, f.mime);
                String where = saveToDownloads(f.tmp, name, mimeFor(name, f.mime));
                runOnUiThread(() -> toast(t("다운로드 폴더에 저장했어요: ", "Saved to Downloads: ") + where));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> toast(t("파일을 저장하지 못했어요.", "Couldn't save the file.")));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.tmp.delete();
            }
        }

        @JavascriptInterface
        public void fail(String token, String message) {
            if (!dlToken.equals(token)) return;
            runOnUiThread(() -> toast(t("파일을 저장하지 못했어요.", "Couldn't save the file.")));
        }

        /** 다른 주소의 &lt;a download&gt;: 사이트가 준 이름으로 다운로드 관리자에 맡긴다. */
        @JavascriptInterface
        public void download(String token, String url, String name) {
            if (!dlToken.equals(token) || url == null) return;
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return;
            runOnUiThread(() -> {
                if (web == null) return;
                String fileName = name == null || name.isEmpty() ? URLUtil.guessFileName(url, null, null) : name;
                enqueueDownload(url, web.getSettings().getUserAgentString(), fileName, null);
            });
        }

        /** window.open(주소, "_blank") 대신: 자기 사이트면 앱 안에서, 다른 사이트면 브라우저로. */
        @JavascriptInterface
        public void openUrl(String token, String url) {
            if (!dlToken.equals(token) || url == null) return;
            if (!(url.startsWith("https://") || url.startsWith("http://"))) return;
            runOnUiThread(() -> {
                if (web == null) return;
                Uri u = Uri.parse(url);
                if (isOwnHost(u.getHost()) || !linksToBrowser) web.loadUrl(url);
                else openExternal(new Intent(Intent.ACTION_VIEW, u));
            });
        }

        /** 위젯 실행기가 읽은 요약 (작은 JSON). 저장하고 홈 화면 위젯을 다시 그린다. */
        @JavascriptInterface
        public void widget(String token, String json) {
            if (!dlToken.equals(token) || widgetJs == null || json == null || json.length() > 16000) return;
            try {
                new JSONObject(json); // 모양만 확인
            } catch (Exception e) {
                return;
            }
            WidgetProvider.save(getApplicationContext(), json);
        }

        private void drop(String id) {
            PendingFile f;
            synchronized (pendingFiles) {
                f = pendingFiles.remove(id);
            }
            if (f != null) f.discard();
        }
    }

    /** 임시 파일을 '다운로드' 폴더로 옮긴다. 안드로이드 10+ 는 권한 없이 MediaStore 로, 그 아래는 앱 전용 폴더로. */
    private String saveToDownloads(File src, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) return Api29.saveToDownloads(getContentResolver(), src, name, mime);
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(getFilesDir(), "Download");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no download folder");
        File out = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int k = 1; out.exists() && k < 1000; k++) {
            out = new File(dir, dot > 0 ? name.substring(0, dot) + " (" + k + ")" + name.substring(dot) : name + " (" + k + ")");
        }
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        return out.getAbsolutePath();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 안드로이드 10 에서 생긴 MediaStore.Downloads 는 여기서만 쓴다. */
    private static final class Api29 {
        static String saveToDownloads(ContentResolver cr, File src, String name, String mime) throws IOException {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("MediaStore open failed");
                copy(in, out);
            } catch (IOException | RuntimeException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        }
    }

    // ---------- 동영상 전체 화면 ----------

    private void enterCustomView(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = view;
        customCallback = cb;
        view.setBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.setVisibility(View.GONE);
        setBarsHidden(true);
        updateBackHandling();
    }

    private void exitCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        content.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setBarsHidden(fullscreen);
        root.requestApplyInsets();
        updateBackHandling();
    }

    // ---------- 뒤로 가기 ----------

    private boolean canHandleBack() {
        return customView != null || (web != null && web.canGoBack());
    }

    private void goBack() {
        if (customView != null) {
            exitCustomView();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        }
        updateBackHandling();
    }

    /** 안드로이드 13+ : 웹 화면에 뒤로 갈 곳이 있을 때만 뒤로 가기를 가로챈다 (없으면 시스템이 앱을 닫는다). */
    private void updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean want = canHandleBack();
        if (want == backRegistered) return;
        if (backCallback == null) backCallback = Api33.callback(this::goBack);
        if (want) Api33.register(this, backCallback);
        else Api33.unregister(this, backCallback);
        backRegistered = want;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { // 안드로이드 12 이하
        if (canHandleBack()) goBack();
        else super.onBackPressed();
    }

    /** 안드로이드 13 에서 생긴 클래스는 여기서만 쓴다 (옛 기기에서 MainActivity 를 불러올 때 문제를 피하려고). */
    private static final class Api33 {
        static Object callback(Runnable r) {
            return (android.window.OnBackInvokedCallback) r::run;
        }

        static void register(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) cb);
        }

        static void unregister(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) cb);
        }
    }

    // ---------- 앱 아이콘을 길게 눌렀을 때의 바로가기 ----------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleViewLink(intent)) handleShortcut(intent);
    }

    private boolean handleShortcut(Intent i) {
        String action = i == null ? null : i.getAction();
        if (ACTION_HOME.equals(action)) {
            web.loadUrl(startUrl);
            return true;
        }
        if (ACTION_CARDS.equals(action) && CardEngine.available(this)) {
            startActivity(new Intent(this, CardSettingsActivity.class));
            return true;
        }
        if (ACTION_OPEN.equals(action)) { // 알림을 눌렀다: 그 알림의 페이지(같은 사이트 경로), 없으면 보던 화면 그대로
            String path = NotifyFeed.safePath(i.getStringExtra("path"));
            Uri s = Uri.parse(startUrl);
            if (!path.isEmpty()) web.loadUrl(s.getScheme() + "://" + s.getEncodedAuthority() + path);
            else if (web.getUrl() == null) web.loadUrl(startUrl);
            return true;
        }
        if (ACTION_MIC.equals(action) && speechJs != null) {
            // 마이크 모드(앱 아이콘 길게 누르기)도 위젯과 같은 대화 창으로 (빅스비처럼 위에 떠서 바로 듣기)
            startActivity(new Intent(this, VoiceActivity.class));
            return false;
        }
        if (ACTION_HOTWORD.equals(action) && HotwordService.available(this)) {
            startActivity(new Intent(this, HotwordActivity.class));
            return false; // 보던 화면은 그대로 (처음 열 때면 시작 주소를 연다)
        }
        if (ACTION_UPDATE.equals(action)) {
            AppUpdater.check(this, true);
            return false; // 보던 화면은 그대로 (처음 열 때면 시작 주소를 연다)
        }
        if (ACTION_BROWSER.equals(action)) {
            String current = web.getUrl();
            openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(current != null ? current : startUrl)));
            if (current == null) web.loadUrl(startUrl);
            return true;
        }
        return false;
    }

    // ---------- 생명 주기 ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        resumed = true;
        // '하이 자비스': 앱 화면을 보는 동안은 마이크를 놓고(사이트가 스스로 듣는다), 켜 둔 것이면 듣기 서비스를 켠다
        // (안드로이드 14+ 는 앱이 앞에 있을 때만 마이크 서비스를 켤 수 있다)
        HotwordService.hold("app", true);
        HotwordService.appVisible(this);
        maybeLiveReload();
        if (widgetJs != null) {
            WidgetProvider.scheduleAuto(this); // 위젯 자동 새로고침 (켠 앱·위젯이 놓여 있을 때만)
            WidgetProvider.refresh(this);      // 혹시 남아 있는 "새로고침 중…"을 지운다
        }
        if (AppUpdater.enabled(this)) AppUpdater.check(this, false); // 새 버전 확인 (6시간에 한 번, 새 버전이면 묻는다)
        if (notifyJs != null && web != null) {
            NotifyJob.schedule(this); // 15분마다 사이트 알림 확인 (이미 걸려 있으면 그대로)
            web.postDelayed(() -> askNotify(false), 1500); // 알림 권한: 처음 한 번만 묻는다 (안드로이드 13+)
        }
        if (CardEngine.available(this)) {
            CardEngine.get(this).flushAsync(); // 못 보낸 카드 결제가 있으면 지금
            CardEngine.get(this).reportStatusAsync(false); // 사이트가 상태 주소를 주면 폰 상태 알리기 (30분에 한 번)
        }
    }

    /** 카드 자동 등록이 들어 있는 앱: 설정이 덜 끝났으면(담당자 없음, 알림 접근 꺼짐) 앱을 열 때 묻는다. */
    private void maybeOfferCards() {
        if (!CardEngine.available(this)) return;
        CardEngine engine = CardEngine.get(this);
        CardStore store = engine.store;
        boolean ownerMissing = store.owner() == null;
        boolean accessOff = "listener".equals(engine.via()) && !engine.listenerEnabled();
        if (!ownerMissing && !accessOff) return; // 다 됐으면 묻지 않는다
        if (store.neverAsk()) return;
        store.setPrompted();
        String why = ownerMissing ? t("이 폰의 담당자를 골라야 시작해요.", "Choose who uses this phone to start.")
                : t("알림 접근이 꺼져 있어서 카드 결제를 읽지 못하고 있어요.", "Notification access is off, so card payments are not read.");
        new AlertDialog.Builder(this)
                .setTitle(t("카드 결제 자동 등록", "Card payment auto-entry"))
                .setMessage(why + t("\n\n설정 화면은 앱 아이콘을 길게 눌러 '카드 자동 등록'으로도 열 수 있어요.",
                        "\n\nYou can also open it by long-pressing the app icon → 'Card auto-entry'."))
                .setPositiveButton(t("설정 열기", "Open settings"),
                        (d, w) -> startActivity(new Intent(this, CardSettingsActivity.class)))
                .setNegativeButton(t("나중에", "Later"), null)
                .setNeutralButton(t("다시 묻지 않기", "Don't ask again"), (d, w) -> store.setNeverAsk())
                .show();
    }

    /** --live: 30분 넘게 다른 앱에 있다 돌아오면 사이트를 새로 고친다 (그 사이 사이트를 고쳤으면 새 화면으로). */
    private static final long LIVE_RELOAD_AFTER_MS = 30 * 60_000L;
    private long pausedAt;

    private void maybeLiveReload() {
        long away = pausedAt == 0 ? 0 : SystemClock.elapsedRealtime() - pausedAt;
        pausedAt = 0;
        if (web == null || !cfg.optBoolean("live", false) || away < LIVE_RELOAD_AFTER_MS) return;
        String u = web.getUrl();
        if (u != null && u.startsWith("http")) web.reload();
    }

    @Override
    protected void onPause() {
        pausedAt = SystemClock.elapsedRealtime();
        resumed = false;
        HotwordService.hold("app", false);
        if (voice != null && (chat == null || !chat.active())) voice.cancelListening(true); // 위에 대화 창이 떠도 사이트가 마이크를 붙잡지 않게
        if (web != null) web.onPause();
        CookieManager.getInstance().flush(); // 로그인 상태가 바로 저장되게
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onStop() {
        // 앱을 떠나면 마이크·읽어 주기를 놓는다 (권한 묻는 창은 onPause 만 부르므로 여기서)
        if (chat != null) chat.stop();
        if (voice != null) {
            voice.cancelListening(true);
            voice.stopSpeaking();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (voice != null) voice.destroy();
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        synchronized (pendingFiles) {
            for (PendingFile f : pendingFiles.values()) f.discard();
            pendingFiles.clear();
        }
        if (web != null) {
            content.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
