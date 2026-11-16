package app.jarvis.urlapp;

// URL 하나를 독립 앱처럼 띄우는 안드로이드 껍데기 (Hermit 의 "라이트 앱"과 같은 방식).
// 안드로이드 기본 API 만 쓴다 (androidx 등 외부 라이브러리 없음). 앱마다 다른 값은 모두
// assets/urlapp.json 에서 읽으므로 이 코드는 한 번만 컴파일해 모든 앱이 같이 쓴다.

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
            if (!handleShortcut(getIntent())) web.loadUrl(startUrl);
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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return route(r.getUrl(), r.hasGesture() && !r.isRedirect());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                mainFrameError = false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!mainFrameError && errorView != null) errorView.setVisibility(View.GONE);
                injectHelpers(url);
                updateBackHandling();
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
            if (!dlToken.equals(token) || widgetJs == null || json == null || json.length() > 4000) return;
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
        handleShortcut(intent);
    }

    private boolean handleShortcut(Intent i) {
        String action = i == null ? null : i.getAction();
        if (ACTION_HOME.equals(action)) {
            web.loadUrl(startUrl);
            return true;
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
    }

    @Override
    protected void onPause() {
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
    protected void onDestroy() {
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
