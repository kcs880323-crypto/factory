package app.jarvis.urlapp;

// 앱이 갑자기 꺼질 때 그 이유(오류 이름·위치 앞부분)를 앱 안 파일에 남기고, 다음에 앱을 열 때 보여 준다 (폰·워치 같은 코드).
// 1.18·1.19 는 폰 앱이 열자마자 꺼지고 워치 타일이 검게만 보였는데 이유를 볼 길이 없어 부품을 하나하나 뒤져야 했다
// (2026-11-20 사용자 신고) — 이제는 다음에 앱을 열면 화면에 이유가 뜨고 '복사'로 그대로 전할 수 있다.
// 기록은 이 기기 앱 안에만 있고 밖으로 보내지 않는다. 프로세스가 시작될 때 CrashLog$Init (ContentProvider) 가 가장 먼저
// 걸어 두고, 기록한 뒤에는 원래 처리기(시스템의 '앱이 중단됨')로 그대로 넘긴다.

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLog {
    static final String FILE = "urlapp-crash.txt";
    static final int MAX_CHARS = 3000, MAX_FRAMES = 12, MAX_CAUSES = 3;
    private static volatile boolean installed;

    private CrashLog() {
    }

    static void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        Context c = ctx.getApplicationContext();
        final Context app = c != null ? c : ctx;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String version = "";
                try {
                    version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
                } catch (Throwable ignored) {
                    // 버전 없이
                }
                byte[] b = describe(t == null ? "" : t.getName(), e, System.currentTimeMillis(), version)
                        .getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    out.write(b);
                }
            } catch (Throwable ignored) {
                // 기록을 못 해도 원래 처리는 그대로
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** 보여 줄 글 (순수 자바 — PC 에서 시험한다). */
    static String describe(String thread, Throwable e, long at, String version) {
        StringBuilder s = new StringBuilder();
        s.append("앱이 갑자기 꺼졌어요 (")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(at)));
        if (version != null && !version.isEmpty()) s.append(", 버전 ").append(version);
        if (thread != null && !thread.isEmpty()) s.append(", ").append(thread);
        s.append(")\n");
        Throwable cur = e;
        for (int depth = 0; cur != null && depth <= MAX_CAUSES; depth++) {
            if (depth > 0) s.append("원인: ");
            s.append(cur.getClass().getName());
            String m = cur.getMessage();
            if (m != null && !m.isEmpty()) s.append(": ").append(m.length() > 400 ? m.substring(0, 400) + "…" : m);
            s.append('\n');
            StackTraceElement[] st = cur.getStackTrace();
            int n = Math.min(st.length, depth == 0 ? MAX_FRAMES : 4);
            for (int i = 0; i < n; i++) s.append("  at ").append(st[i]).append('\n');
            if (st.length > n) s.append("  … ").append(st.length - n).append("줄 더\n");
            if (cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) + "…" : s.toString();
    }

    static boolean has(Context ctx) {
        return new File(ctx.getFilesDir(), FILE).isFile();
    }

    /** 남은 기록 (없으면 null). 읽으면 지운다 (한 번만 보여 준다). */
    static String take(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 16_000)];
            int got = 0, n;
            while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
            return new String(b, 0, got, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 프로세스가 시작될 때 (활동·서비스·위젯·타일보다 먼저) 처리기를 건다. 아무것도 내주지 않는다. */
    public static final class Init extends ContentProvider {
        @Override
        public boolean onCreate() {
            install(getContext());
            return false;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }
}
