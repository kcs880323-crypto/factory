package app.jarvis.urlapp;

// '하이 자비스' 호출어 판정 (순수 자바 + org.json — PC 에서 시험할 수 있다. test/HotwordTests.java).
//
// 엔진(Vosk 한국어 small, 폰 안에서만)은 '문법'으로만 듣는다: [호출어 문장 × 반복] + [한 글자 채움말 수천 개].
//  - 채움말이 없으면 무슨 말을 해도 호출어로 풀려 버린다 (2026-11-20 실측: 9문장 중 9개가 '하이 자비스').
//  - 채움말에서 호출어의 글자(하·이·자·비·스 …)는 뺀다 — 호출어를 글자 조각으로 흉내 내지 못하게.
//  - 호출어 문장을 여러 번 넣을수록(반복) 엔진이 호출어 쪽으로 풀기 쉬워진다 = 민감도.
// 판정: 결과 낱말 줄에서 호출어 문장의 낱말이 차례로 나오고(사이에 채움말 1개까지 허용),
//       첫 낱말(인사말)·나머지(이름)의 신뢰도가 민감도별 기준 이상이면 호출.
//  중간 결과(partial, 신뢰도 없음)는 낱말이 붙어서 나올 때만 — 두 번 연속 같으면 (말을 이어 가도 빨리 뜨게).

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HotwordMatch {
    static final int LOW = 0, NORMAL = 1, HIGH = 2;
    static final String[] DEFAULT_PHRASES = {"하이 자비스", "헤이 자비스", "안녕 자비스"};

    final String[] phrases;   // 공백으로 나눈 낱말 줄 ("하이 자비스")
    final String[][] words;
    final int sensitivity;

    HotwordMatch(List<String> phraseList, int sensitivity) {
        List<String> clean = new ArrayList<>();
        for (String p : phraseList) {
            String c = normalize(p);
            if (!c.isEmpty() && !clean.contains(c)) clean.add(c);
        }
        if (clean.isEmpty()) for (String p : DEFAULT_PHRASES) clean.add(p);
        phrases = clean.toArray(new String[0]);
        words = new String[phrases.length][];
        for (int i = 0; i < phrases.length; i++) words[i] = phrases[i].split(" ");
        this.sensitivity = Math.max(LOW, Math.min(HIGH, sensitivity));
    }

    static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("[\\s,.!?~]+", " ").trim();
    }

    // ---------- 민감도 ----------

    int repeats() {
        return sensitivity == LOW ? 5 : sensitivity == HIGH ? 100 : 20;
    }

    double minFirstConf() {
        return sensitivity == LOW ? 0.75 : sensitivity == HIGH ? 0.5 : 0.6;
    }

    double minRestConf() {
        return sensitivity == LOW ? 0.9 : sensitivity == HIGH ? 0.7 : 0.8;
    }

    // ---------- 문법 ----------

    /** 호출어 글자들 (채움말에서 뺄 것). */
    Set<String> phraseSyllables() {
        Set<String> s = new HashSet<>();
        for (String[] ws : words)
            for (String w : ws)
                for (int i = 0; i < w.length(); i++) s.add(String.valueOf(w.charAt(i)));
        return s;
    }

    /** Vosk 문법 JSON: 호출어 × 반복 + 채움말 (호출어 글자·호출어 낱말과 같은 것은 뺌). */
    String grammar(List<String> fillers) {
        JSONArray a = new JSONArray();
        int r = repeats();
        for (int k = 0; k < r; k++)
            for (String p : phrases) a.put(p);
        Set<String> skip = phraseSyllables();
        Set<String> phraseWords = new HashSet<>();
        for (String[] ws : words)
            for (String w : ws) phraseWords.add(w);
        Set<String> seen = new LinkedHashSet<>();
        for (String f : fillers) {
            String w = f == null ? "" : f.trim();
            if (w.isEmpty() || w.contains(" ") || skip.contains(w) || phraseWords.contains(w)) continue;
            if (seen.add(w)) a.put(w);
        }
        return a.toString();
    }

    /** 채움말 파일 내용(한 줄에 하나) → 목록. '#' 줄은 설명. */
    static List<String> parseFillers(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String w = line.trim();
            if (!w.isEmpty() && !w.startsWith("#")) out.add(w);
        }
        return out;
    }

    // ---------- 판정 ----------

    static final class Hit {
        final String phrase;
        final double conf;   // 호출어 낱말 중 가장 낮은 신뢰도 (중간 결과는 -1)
        final String text;   // 들린 말 전체

        Hit(String phrase, double conf, String text) {
            this.phrase = phrase;
            this.conf = conf;
            this.text = text;
        }
    }

    /** 끝난 결과(Result/FinalResult JSON). 호출이 아니면 null. */
    Hit matchResult(String json) {
        JSONObject o = parse(json);
        if (o == null) return null;
        JSONArray rs = o.optJSONArray("result");
        String text = o.optString("text", "");
        if (rs == null || rs.length() == 0) return null;
        List<String> ws = new ArrayList<>();
        List<Double> cs = new ArrayList<>();
        for (int i = 0; i < rs.length(); i++) {
            JSONObject w = rs.optJSONObject(i);
            if (w == null) continue;
            ws.add(w.optString("word", ""));
            cs.add(w.optDouble("conf", 0));
        }
        for (int p = 0; p < words.length; p++) {
            String[] target = words[p];
            for (int start = 0; start < ws.size(); start++) {
                if (!ws.get(start).equals(target[0])) continue;
                double min = cs.get(start);
                if (min < minFirstConf()) continue;
                int at = start;
                boolean ok = true;
                double restMin = 1;
                for (int k = 1; k < target.length && ok; k++) {
                    int next = -1;
                    for (int j = at + 1; j <= at + 2 && j < ws.size(); j++) { // 사이에 채움말 1개까지
                        if (ws.get(j).equals(target[k])) {
                            next = j;
                            break;
                        }
                        if (isPhraseWord(ws.get(j))) break; // 다른 호출어 낱말이 끼면 아님
                    }
                    if (next < 0) {
                        ok = false;
                    } else {
                        restMin = Math.min(restMin, cs.get(next));
                        at = next;
                    }
                }
                if (ok && restMin >= minRestConf()) return new Hit(phrases[p], Math.min(min, restMin), text);
            }
        }
        return null;
    }

    private boolean isPhraseWord(String w) {
        for (String[] ws : words)
            for (String x : ws)
                if (x.equals(w)) return true;
        return false;
    }

    /** 중간 결과(PartialResult JSON)에 호출어가 붙어서 나오면 그 문장 (아니면 null). */
    String matchPartial(String json) {
        JSONObject o = parse(json);
        if (o == null) return null;
        String text = " " + normalize(o.optString("partial", "")) + " ";
        for (String p : phrases)
            if (text.contains(" " + p + " ")) return p;
        return null;
    }

    /** 결과·중간 결과에서 들린 말만 (시험 화면용). */
    static String heard(String json) {
        JSONObject o = parse(json);
        if (o == null) return "";
        String t = o.optString("text", "");
        if (t.isEmpty()) t = o.optString("partial", "");
        return t.trim();
    }

    private static JSONObject parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 앱 설정(urlapp.json 의 hotword.phrases) → 문장 목록. */
    static List<String> phrases(JSONObject hotwordCfg) {
        List<String> out = new ArrayList<>();
        JSONArray a = hotwordCfg == null ? null : hotwordCfg.optJSONArray("phrases");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** 화면에 보일 호출어 (첫 문장). */
    String display() {
        return phrases.length == 0 ? "" : phrases[0];
    }

    static String sensitivityName(int s) {
        return s == LOW ? "낮게" : s == HIGH ? "높게" : "보통";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "HotwordMatch(%d phrases, s=%d)", phrases.length, sensitivity);
    }
}
