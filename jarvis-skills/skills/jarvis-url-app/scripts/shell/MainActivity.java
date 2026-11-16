package app.jarvis.urlapp;

// URL 하나를 독립 앱처럼 띄우는 안드로이드 껍데기 (Hermit 의 "라이트 앱"과 같은 방식).
// 안드로이드 기본 API 만 쓴다 (androidx 등 외부 라이브러리 없음). 앱마다 다른 값은 모두
// assets/urlapp.json 에서 읽으므로 이 코드는 한 번만 컴파일해 모든 앱이 같이 쓴다.

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

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
        try (InputStream in = getAssets().open("urlapp.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("assets/urlapp.json 을 읽을 수 없습니다", e);
        }
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
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            toast(t("이 파일은 앱 안에서 받을 수 없어요. 브라우저에서 열어 주세요.",
                    "This file can't be downloaded in the app. Open it in a browser."));
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mime);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
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
        if (web != null) {
            content.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
