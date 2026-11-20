package app.jarvis.urlapp;

// 앱 안 업데이트: 새 버전 정보 파일(<앱 id>.json)을 읽고 믿을 수 있는지 확인한다 (PC 자바로 시험할 수 있다).
// 파일 모양 (url_app.py publish 가 만든다):
//   {"package": "app.jarvis.web.…", "version_code": 15, "version_name": "1.15",
//    "apk": "https://…/app.jarvis.web.…-v15.apk", "sha256": "64자", "size": 284826, "notes": "바뀐 점"}
// 받는 주소는 https 이고 정보 파일과 같은 곳(호스트)이어야 한다. 실제 설치는 안드로이드가 같은 서명 키인지 한 번 더 막는다.

import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;

public final class UpdateInfo {
    public final long versionCode;
    public final String versionName;
    public final String apkUrl;
    public final String sha256;
    public final long size;
    public final String notes;

    private UpdateInfo(long code, String name, String apk, String sha, long size, String notes) {
        this.versionCode = code;
        this.versionName = name;
        this.apkUrl = apk;
        this.sha256 = sha;
        this.size = size;
        this.notes = notes;
    }

    /** 정보 파일을 읽는다. 다른 앱 것이거나, 주소·해시 모양이 이상하면 예외 (그 이유를 담아). */
    public static UpdateInfo parse(String json, String expectedPackage, String manifestUrl) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("업데이트 정보를 읽지 못했어요");
        }
        if (!expectedPackage.equals(o.optString("package"))) throw new IllegalArgumentException("다른 앱의 업데이트 정보예요");
        long code = o.optLong("version_code", 0);
        if (code <= 0) throw new IllegalArgumentException("버전 번호가 없어요");
        String apk = o.optString("apk", "");
        if (!sameHttpsHost(apk, manifestUrl)) throw new IllegalArgumentException("받을 주소가 정보 파일과 다른 곳이에요");
        String sha = o.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("검사값(SHA-256)이 없어요");
        long size = o.optLong("size", 0);
        if (size <= 0 || size > 100L * 1024 * 1024) throw new IllegalArgumentException("파일 크기가 이상해요");
        String notes = o.optString("notes", "");
        if (notes.length() > 300) notes = notes.substring(0, 300) + "…";
        return new UpdateInfo(code, o.optString("version_name", String.valueOf(code)), apk, sha, size, notes);
    }

    public boolean newerThan(long installedCode) {
        return versionCode > installedCode;
    }

    static boolean sameHttpsHost(String a, String b) {
        try {
            URI x = new URI(a), y = new URI(b);
            return "https".equalsIgnoreCase(x.getScheme()) && "https".equalsIgnoreCase(y.getScheme())
                    && x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost()) && x.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }
}
