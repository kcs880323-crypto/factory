package app.jarvis.urlapp;

// 가맹점 이름으로 가계부의 대분류·중분류를 고른다 (안드로이드 API 를 쓰지 않아 PC 자바로 시험할 수 있다).
// 1) 이 가계부의 지난 기록: 같은 가맹점(또는 같은 상호)을 전에 어느 분류에 넣었는지 (가장 많이 쓴 것)
// 2) 사이트별 규칙(키워드 → 분류): 처음 보는 가맹점
// 3) 기본 분류

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CardCategorizer {
    public static final class Rule {
        final String large;
        final String medium;
        final String[] keywords;

        public Rule(String large, String medium, String[] keywords) {
            this.large = large;
            this.medium = medium;
            this.keywords = keywords;
        }
    }

    /** 결과: large, medium, 어디서 정했는지(history/rule/default). */
    public static final class Pick {
        public final String large;
        public final String medium;
        public final String source;

        Pick(String large, String medium, String source) {
            this.large = large;
            this.medium = medium;
            this.source = source;
        }
    }

    private final List<Rule> rules;
    private final String defLarge;
    private final String defMedium;
    private final Map<String, String[]> byKey = new HashMap<>();
    private final Map<String, String[]> byBrand = new HashMap<>();

    public CardCategorizer(List<Rule> rules, String defLarge, String defMedium) {
        this.rules = rules == null ? new ArrayList<Rule>() : rules;
        this.defLarge = defLarge;
        this.defMedium = defMedium;
    }

    /** 지난 기록으로 배운다. rows: {세부항목(가맹점), 대분류, 중분류}. 같은 가맹점이 여러 분류면 많이 쓴 쪽. */
    public void learn(List<String[]> rows) {
        Map<String, Map<String, Integer>> k = new HashMap<>();
        Map<String, Map<String, Integer>> b = new HashMap<>();
        for (String[] r : rows) {
            if (r == null || r.length < 3 || r[0] == null || r[1] == null || r[1].isEmpty()) continue;
            String cat = r[1] + "\u0000" + (r[2] == null ? "" : r[2]);
            count(k, key(r[0]), cat);
            count(b, brandKey(r[0]), cat);
        }
        byKey.clear();
        byBrand.clear();
        top(k, byKey);
        top(b, byBrand);
    }

    public int learnedCount() {
        return byKey.size();
    }

    private static void count(Map<String, Map<String, Integer>> m, String key, String cat) {
        if (key.length() < 2) return;
        Map<String, Integer> c = m.get(key);
        if (c == null) m.put(key, c = new HashMap<>());
        Integer n = c.get(cat);
        c.put(cat, n == null ? 1 : n + 1);
    }

    private static void top(Map<String, Map<String, Integer>> in, Map<String, String[]> out) {
        for (Map.Entry<String, Map<String, Integer>> e : in.entrySet()) {
            String best = null;
            int bestN = -1;
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                if (c.getValue() > bestN || (c.getValue() == bestN && c.getKey().compareTo(best) < 0)) {
                    best = c.getKey();
                    bestN = c.getValue();
                }
            }
            if (best != null) out.put(e.getKey(), best.split("\u0000", -1));
        }
    }

    public Pick pick(String merchant) {
        String m = merchant == null ? "" : merchant;
        if (!m.isEmpty()) {
            String[] h = byKey.get(key(m));
            if (h == null) h = byBrand.get(brandKey(m));
            if (h != null) return new Pick(h[0], h[1], "history");
            String upper = m.toUpperCase(Locale.ROOT);
            String compact = upper.replaceAll("\\s+", "");
            for (Rule r : rules) {
                for (String kw : r.keywords) {
                    if (matches(upper, compact, kw)) return new Pick(r.large, r.medium, "rule");
                }
            }
        }
        return new Pick(defLarge, defMedium, "default");
    }

    /** 영문·숫자 키워드는 단어 경계로(CU 가 CUP 에 걸리지 않게), 한글이 섞이면 띄어쓰기 없이 포함 여부로. */
    static boolean matches(String upper, String compact, String keyword) {
        String kw = keyword.toUpperCase(Locale.ROOT).trim();
        if (kw.isEmpty()) return false;
        if (kw.matches("[A-Z0-9 &+\\-.'/]+")) {
            int from = 0;
            while (true) {
                int i = upper.indexOf(kw, from);
                if (i < 0) return false;
                boolean leftOk = i == 0 || !Character.isLetterOrDigit(upper.charAt(i - 1))
                        || isHangul(upper.charAt(i - 1));
                int end = i + kw.length();
                boolean rightOk = end >= upper.length() || !Character.isLetter(upper.charAt(end))
                        || isHangul(upper.charAt(end));
                if (leftOk && rightOk) return true;
                from = i + 1;
            }
        }
        return compact.contains(kw.replaceAll("\\s+", ""));
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    /** 가맹점 이름 정리: 법인 표시·지점명·기호를 빼고 대문자, 띄어쓰기 없이. "메가커피 강남점" → "메가커피". */
    static String key(String merchant) {
        String s = merchant.toUpperCase(Locale.ROOT)
                .replaceAll("\\(주\\)|㈜|주식회사|\\(유\\)|유한회사", " ")
                .trim();
        s = s.replaceAll("\\s+\\S*(점|지점|센터|매장|직영점|본점)$", "");
        return s.replaceAll("[\\s\\p{Punct}]+", "");
    }

    /** 첫 낱말 (같은 상호의 다른 지점까지 묶기): "스타벅스 역삼역점" → "스타벅스". */
    static String brandKey(String merchant) {
        String s = merchant.toUpperCase(Locale.ROOT).replaceAll("\\(주\\)|㈜|주식회사", " ").trim();
        String first = s.split("\\s+")[0];
        return first.replaceAll("[\\p{Punct}]+", "");
    }
}
