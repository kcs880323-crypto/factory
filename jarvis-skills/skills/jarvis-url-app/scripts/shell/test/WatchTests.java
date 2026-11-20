package app.jarvis.urlapp;

// 워치 타일에 보일 요약 만들기·읽어 줄 글 다듬기 시험 (PC 자바, 안드로이드·워치 없이).
//   javac -cp json.jar:android.jar -d out watch/WatchStore.java watch/WatchAskActivity.java … shell/test/WatchTests.java
//   (실행: json.jar 를 android.jar 보다 앞에)

import org.json.JSONArray;
import org.json.JSONObject;

public class WatchTests {
    static int ok = 0, fail = 0;

    static void check(String label, boolean cond, Object detail) {
        if (cond) ok++;
        else fail++;
        System.out.println("  [" + (cond ? "통과" : "실패") + "] " + label + (cond ? "" : " — " + detail));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("워치 타일 요약");
        long now = 1_763_600_000_000L;
        String json = new JSONObject().put("title", "자비스 · 진행 중 1")
                .put("rows", new JSONArray()
                        .put(new JSONObject().put("label", "음악 요청 #30").put("value", "진행 12분").put("tone", "pos"))
                        .put(new JSONObject().put("label", "스탠리 퀜처").put("value", "확인 필요").put("tone", "neg"))
                        .put(new JSONObject().put("label", "").put("value", ""))
                        .put(new JSONObject().put("label", "발행 대기").put("value", "허용 필요").put("tone", "odd"))
                        .put(new JSONObject().put("label", "넷째 줄").put("value", "안 보임")))
                .put("note", "확인 필요 1").toString();
        WatchStore.Summary s = WatchStore.parse(json, now - 5 * 60_000, now, "자비스");
        check("제목·줄 3개까지(빈 줄 건너뜀)·tone 은 pos/neg 만", !s.empty && "자비스 · 진행 중 1".equals(s.title) && s.rows.size() == 3
                && "pos".equals(s.rows.get(0).tone) && "neg".equals(s.rows.get(1).tone) && "".equals(s.rows.get(2).tone)
                && "발행 대기".equals(s.rows.get(2).label), s.rows.size());
        check("아래 줄: 메모 · 받은 지 얼마", "확인 필요 1 · 5분 전".equals(s.note), s.note);
        WatchStore.Summary none = WatchStore.parse(null, 0, now, "자비스");
        check("받은 것이 없으면 앱 이름과 안내", none.empty && "자비스".equals(none.title) && none.note.contains("폰의 앱"), none.note);
        WatchStore.Summary broken = WatchStore.parse("{oops", now, now, "자비스");
        check("깨진 JSON 도 안내 (앱이 꺼지지 않게)", broken.empty, broken.note);
        WatchStore.Summary noTitle = WatchStore.parse("{\"rows\":[]}", now - 30_000, now, "자비스");
        check("제목이 없으면 앱 이름, 방금", "자비스".equals(noTitle.title) && "방금".equals(noTitle.note), noTitle.note);
        check("시간 표시: 2시간 전 · 날짜", "2시간 전".equals(WatchStore.ago(now - 2 * 3600_000, now))
                && WatchStore.ago(now - 3L * 24 * 3600_000, now).matches("\\d{1,2}/\\d{1,2} \\d\\d:\\d\\d"), "");
        check("긴 글은 줄이고 제어 문자는 지움", WatchStore.clean("가\u202e나\n다" + "라".repeat(50), 10).equals("가 나 다라라라라…")
                && WatchStore.clean("가\u202e나\n다" + "라".repeat(50), 10).length() == 10, WatchStore.clean("가\u202e나\n다" + "라".repeat(50), 10));

        System.out.println("읽어 줄 글");
        check("링크·표 기호를 빼고 공백 정리", "날씨는 맑음 자세히 끝".equals(WatchAskActivity.spoken("**날씨**는 | 맑음\n자세히 https://x.y/z 끝")),
                WatchAskActivity.spoken("**날씨**는 | 맑음\n자세히 https://x.y/z 끝"));
        check("너무 길면 1500자까지", WatchAskActivity.spoken("가".repeat(3000)).length() == 1500, "");

        System.out.println("\n결과: 통과 " + ok + " / 실패 " + fail);
        if (fail > 0) System.exit(1);
    }
}
