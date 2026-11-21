package app.jarvis.urlapp;

// '하이 자비스' 호출어 판정 시험 (PC 자바, 안드로이드·모델 없이). 결과 JSON 은 Vosk 가 내는 모양
// ({"result":[{"conf","end","start","word"}],"text"}) 이고, 낱말·신뢰도는 2026-11-20 실제 모델로 잰 값을 본떴다.
//   javac -cp json.jar -d out shell/HotwordMatch.java shell/test/HotwordTests.java
//   java -cp out:json.jar app.jarvis.urlapp.HotwordTests [grammar-out.json]   (인자: 문법을 파일로 내보내기)

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HotwordTests {
    static int ok = 0, fail = 0;

    static void check(String label, boolean cond, Object detail) {
        if (cond) ok++;
        else fail++;
        System.out.println("  [" + (cond ? "통과" : "실패") + "] " + label + (cond ? "" : " — " + detail));
    }

    /** "하이:0.9 자비스:1.0" → Vosk 결과 JSON */
    static String res(String spec) {
        JSONArray a = new JSONArray();
        StringBuilder text = new StringBuilder();
        double t = 0;
        for (String part : spec.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            String[] wc = part.split(":");
            JSONObject w = new JSONObject();
            w.put("word", wc[0]);
            w.put("conf", Double.parseDouble(wc[1]));
            w.put("start", t);
            w.put("end", t + 0.3);
            t += 0.3;
            a.put(w);
            if (text.length() > 0) text.append(' ');
            text.append(wc[0]);
        }
        return new JSONObject().put("result", a).put("text", text.toString()).toString();
    }

    static String partial(String p) {
        return new JSONObject().put("partial", p).toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("호출어 판정");
        HotwordMatch n = new HotwordMatch(new ArrayList<String>(), HotwordMatch.NORMAL);
        HotwordMatch lo = new HotwordMatch(new ArrayList<String>(), HotwordMatch.LOW);
        HotwordMatch hi = new HotwordMatch(new ArrayList<String>(), HotwordMatch.HIGH);
        check("설정이 없으면 기본 호출어 3개", n.phrases.length == 3 && "하이 자비스".equals(n.display()), Arrays.toString(n.phrases));

        // 문법
        List<String> fillers = HotwordMatch.parseFillers("# 설명\n가\n하\n이\n자\n비\n스\n헤\n안\n녕\n쪄\n빟\n하이\n가\n\n두 글\n");
        JSONArray g = new JSONArray(n.grammar(fillers));
        Set<String> items = new HashSet<>();
        for (int i = 0; i < g.length(); i++) items.add(g.getString(i));
        int phraseCount = 0;
        for (int i = 0; i < g.length(); i++) if (g.getString(i).equals("하이 자비스")) phraseCount++;
        check("문법: 호출어를 민감도만큼 반복 (보통 20번)", phraseCount == 20, phraseCount);
        check("문법: 채움말은 한 번씩, '#' 설명·빈 줄·공백 낱말은 뺌", items.contains("가") && items.contains("쪄") && items.contains("빟")
                && !items.contains("두 글") && !items.contains("# 설명"), items);
        boolean noPhraseSyllables = true;
        for (String s : new String[] {"하", "이", "자", "비", "스", "헤", "안", "녕", "하이"}) if (items.contains(s)) noPhraseSyllables = false;
        check("문법: 호출어 글자·낱말은 채움말에서 뺌 (글자 조각으로 흉내 못 내게)", noPhraseSyllables, items);
        int dup = 0;
        for (int i = 0; i < g.length(); i++) if (g.getString(i).equals("가")) dup++;
        check("문법: 같은 채움말은 한 번만", dup == 1, dup);
        check("민감도별 반복: 낮게 5 · 보통 20 · 높게 100", lo.repeats() == 5 && n.repeats() == 20 && hi.repeats() == 100,
                lo.repeats() + "/" + n.repeats() + "/" + hi.repeats());

        // 끝난 결과
        check("'하이 자비스' (확신 높음) → 호출", n.matchResult(res("하이:1.0 자비스:1.0")) != null, "");
        check("'헤이 자비스'·'안녕 자비스' → 호출", n.matchResult(res("헤이:0.9 자비스:0.95")) != null
                && n.matchResult(res("안녕:0.8 자비스:0.9")) != null, "");
        check("말 앞뒤에 다른 말이 있어도 → 호출 ('음 하이 자비스 오늘')",
                n.matchResult(res("음:0.5 하이:0.9 자비스:0.9 오:0.4")) != null, "");
        HotwordMatch.Hit h = n.matchResult(res("하이:1.0 쪄:0.31 자비스:1.0")); // 실측: 사이에 채움말 하나가 끼는 경우
        check("사이에 채움말 1개 → 호출 (실측 모양 '하이 쪄 자비스')", h != null && "하이 자비스".equals(h.phrase) && h.conf == 1.0, h);
        check("사이에 채움말 2개 → 아님", n.matchResult(res("하이:1.0 쪄:0.3 빟:0.2 자비스:1.0")) == null, "");
        check("인사말 확신이 낮으면 아님 (실측 '사비스 서비스' → 하이:0.45 자비스 자비스)",
                n.matchResult(res("하이:0.45 자비스:1.0 자비스:1.0")) == null && hi.matchResult(res("하이:0.45 자비스:1.0")) == null, "");
        check("이름 확신이 낮으면 보통에서는 아님, 높게에서는 0.7 이상이면 호출",
                n.matchResult(res("하이:1.0 자비스:0.75")) == null && hi.matchResult(res("하이:1.0 자비스:0.75")) != null, "");
        check("낮게: 인사 0.75·이름 0.9 이상만", lo.matchResult(res("하이:0.8 자비스:0.85")) == null
                && lo.matchResult(res("하이:0.8 자비스:0.95")) != null, "");
        check("'자비스'만 → 아님", n.matchResult(res("자비스:1.0 자비스:1.0")) == null, "");
        check("'하이'만 (실측 '아이 좋아' → 하이 쫗) → 아님", n.matchResult(res("하이:1.0 쫗:0.43")) == null, "");
        check("순서가 거꾸로 ('자비스 하이') → 아님", n.matchResult(res("자비스:1.0 하이:1.0")) == null, "");
        check("사이에 다른 호출어 낱말이 끼면 아님 ('하이 헤이 자비스'는 '헤이 자비스'로)",
                "헤이 자비스".equals(n.matchResult(res("하이:0.9 헤이:0.9 자비스:0.9")).phrase), "");
        check("빈 결과·깨진 JSON·결과 없음 → 아님", n.matchResult("") == null && n.matchResult("{oops") == null
                && n.matchResult("{\"text\":\"\"}") == null, "");

        // 중간 결과
        check("중간 결과에 붙어서 나오면 그 문장", "하이 자비스".equals(n.matchPartial(partial("하이 자비스 오늘 일정"))), "");
        check("중간 결과에서 떨어져 있으면 아님 ('하이 쪄 자비스')", n.matchPartial(partial("하이 쪄 자비스")) == null, "");
        check("다른 낱말의 일부면 아님 ('아하이 자비스')", n.matchPartial(partial("아하이 자비스")) == null, "");

        // 설정·글
        HotwordMatch custom = new HotwordMatch(Arrays.asList("하이, 자비스!", " 헤이  자비스 ", "하이 자비스", ""), HotwordMatch.NORMAL);
        check("설정 문장 정리: 문장부호·공백 정리, 같은 문장 한 번", custom.phrases.length == 2
                && "하이 자비스".equals(custom.phrases[0]) && "헤이 자비스".equals(custom.phrases[1]), Arrays.toString(custom.phrases));
        JSONObject cfg = new JSONObject().put("phrases", new JSONArray().put("헤이 찰스").put("안녕 찰스"));
        HotwordMatch charles = new HotwordMatch(HotwordMatch.phrases(cfg), HotwordMatch.NORMAL);
        check("다른 이름도 (설정의 phrases)", charles.matchResult(res("헤이:0.9 찰스:0.9")) != null
                && charles.matchResult(res("하이:0.9 자비스:0.9")) == null, Arrays.toString(charles.phrases));
        check("들린 말 (결과·중간 결과)", "하이 자비스".equals(HotwordMatch.heard(res("하이:1 자비스:1")))
                && "하이 자".equals(HotwordMatch.heard(partial("하이 자"))) && HotwordMatch.heard(null).isEmpty(), "");
        check("민감도 범위 밖은 끝값으로", new HotwordMatch(new ArrayList<String>(), 9).sensitivity == HotwordMatch.HIGH
                && new HotwordMatch(new ArrayList<String>(), -3).sensitivity == HotwordMatch.LOW, "");

        if (args.length > 0) { // 실제 모델 시험용: 앱과 똑같은 문법을 파일로
            String all = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
            for (int s = HotwordMatch.LOW; s <= HotwordMatch.HIGH; s++) {
                String out = args[0].replaceAll("\\.txt$", "") + "-grammar-" + s + ".json";
                Files.write(Paths.get(out), new HotwordMatch(new ArrayList<String>(), s).grammar(HotwordMatch.parseFillers(all))
                        .getBytes(StandardCharsets.UTF_8));
                System.out.println("  문법 내보냄: " + out);
            }
        }

        System.out.println("\n결과: 통과 " + ok + " / 실패 " + fail);
        if (fail > 0) System.exit(1);
    }
}
