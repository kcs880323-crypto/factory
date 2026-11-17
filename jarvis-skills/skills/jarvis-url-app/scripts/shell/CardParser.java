package app.jarvis.urlapp;

// 카드 승인·취소 알림 문구를 읽어 금액·가맹점·카드사·시각을 뽑는다 (안드로이드 API 를 쓰지 않아 PC 자바로 시험할 수 있다).
// 현대·삼성·롯데·NH농협·신한카드의 문자(SMS), 카드사 앱 알림, 삼성 월렛 알림, 카카오 알림톡의 흔한 모양을 다룬다.
// 카드사마다 문구가 다르고 바뀔 수 있어서, 정해진 틀 대신 "카드사 + 승인/취소 + 금액원"이 있는 알림만 받고
// 나머지에서 날짜·가맹점을 찾는다. 해외 결제(원화가 아닌 금액)는 받지 않는다.

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardParser {
    private CardParser() {}

    public static final class Txn {
        public String card;        // "현대카드" 등
        public boolean cancel;     // 승인취소
        public long amount;        // 원
        public long when;          // 결제 시각 (ms)
        public boolean whenFromText; // 알림 문구에 시각이 있었는지
        public String merchant;    // 가맹점 ("" 이면 모름)

        @Override
        public String toString() {
            return (cancel ? "취소 " : "승인 ") + card + " " + amount + "원 " + merchant;
        }
    }

    private static final String[][] CARDS = {
            {"현대카드", "현대\\s*카드|현대카드|현대\\s*\\d|HYUNDAI\\s*CARD"},
            {"삼성카드", "삼성\\s*카드|삼성\\s*\\d|삼성카드|SAMSUNG\\s*CARD"},
            {"롯데카드", "롯데\\s*카드|롯데\\s*\\d|LOTTE\\s*CARD|디지로카"},
            {"농협카드", "NH\\s*농협|NH\\s*카드|NH\\s*\\d|농협\\s*카드|농협\\s*\\d|NH\\s*pay|NH농협카드"},
            {"신한카드", "신한\\s*카드|신한\\s*\\d|신한카드|SHINHAN\\s*CARD|신한\\s*SOL"},
    };
    private static final Pattern[] CARD_PATTERNS = new Pattern[CARDS.length];

    static {
        for (int i = 0; i < CARDS.length; i++) CARD_PATTERNS[i] = Pattern.compile(CARDS[i][1], Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원");
    private static final Pattern NOT_PAYMENT_BEFORE = Pattern.compile("(누적|잔여|잔액|한도|포인트|적립|이용가능|결제예정|청구)\\s*[:：]?\\s*$");
    private static final Pattern DATETIME = Pattern.compile("(\\d{1,2})[/.\\-월]\\s*(\\d{1,2})일?\\s*(\\d{1,2})[:시]\\s*(\\d{2})분?");
    private static final Pattern APPROVE = Pattern.compile("승인|결제|사용");
    // 광고·안내(결제 예정, 할인, 이벤트 등)는 결제가 아니다
    private static final Pattern NOT_TXN = Pattern.compile(
            "\\(광고\\)|\\[광고\\]|이벤트|캐시백|혜택|무이자|청구\\s*예정|결제\\s*예정|결제일|명세서|청구서|한도\\s*상향|대출|리볼빙");
    private static final Pattern CANCEL = Pattern.compile("취소");
    // 가맹점이 아닌 조각들 (지운 뒤 남는 것이 가맹점 후보)
    private static final Pattern[] NOISE = {
            Pattern.compile("\\[\\s*(Web발신|국외발신|국제발신|광고)\\s*\\]"),
            Pattern.compile("(총)?누적\\s*[:：]?\\s*[\\d,]+\\s*원?"),
            Pattern.compile("(잔여|잔액|한도|이용가능)\\s*(한도|금액)?\\s*[:：]?\\s*[\\d,]+\\s*원?"),
            Pattern.compile("\\(?\\s*(일시불|할부\\s*\\d*\\s*개?월?|\\d{1,2}\\s*개월(\\s*할부)?|체크|신용)\\s*\\)?"),
            Pattern.compile("[가-힣A-Za-z]\\*+[가-힣A-Za-z]?\\s*님?"),           // 김*수, 홍*동님
            Pattern.compile("\\(?\\s*\\d[\\d*]{2,}\\d?\\s*\\)?"),                  // (1234), 1*2*
            Pattern.compile("승인\\s*취소|승인|취소|결제\\s*완료|결제했어요|결제|사용"),
    };

    /** 카드 결제 알림이면 Txn, 아니면 null. now 는 알림이 온 시각 (연도·시각이 없을 때 쓴다). */
    public static Txn parse(String title, String body, long now) {
        String text = ((title == null ? "" : title) + "\n" + (body == null ? "" : body))
                .replace('\r', '\n').replace('|', '\n').replace(' ', ' ');
        String card = null;
        int cardPos = Integer.MAX_VALUE;
        for (int i = 0; i < CARDS.length; i++) {
            Matcher m = CARD_PATTERNS[i].matcher(text);
            if (m.find() && m.start() < cardPos) {
                cardPos = m.start();
                card = CARDS[i][0];
            }
        }
        if (card == null) return null;
        if (NOT_TXN.matcher(text).find()) return null;
        boolean cancel = CANCEL.matcher(text).find();
        if (!cancel && !APPROVE.matcher(text).find()) return null;

        long amount = -1;
        Matcher am = AMOUNT.matcher(text);
        while (am.find()) {
            String before = text.substring(Math.max(0, am.start() - 8), am.start());
            if (NOT_PAYMENT_BEFORE.matcher(before).find()) continue;
            try {
                amount = Long.parseLong(am.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount > 0) break;
        }
        if (amount <= 0) return null; // 원화 금액이 없으면(해외 결제 등) 받지 않는다

        Txn t = new Txn();
        t.card = card;
        t.cancel = cancel;
        t.amount = amount;
        t.when = now;
        Matcher dt = DATETIME.matcher(text);
        int dtEnd = -1;
        int dtStart = -1;
        if (dt.find()) {
            int mo = Integer.parseInt(dt.group(1)), d = Integer.parseInt(dt.group(2));
            int h = Integer.parseInt(dt.group(3)), mi = Integer.parseInt(dt.group(4));
            if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31 && h <= 23 && mi <= 59) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(now);
                int year = c.get(Calendar.YEAR);
                if (mo > c.get(Calendar.MONTH) + 2) year--; // 1월에 받은 12월 결제
                c.set(year, mo - 1, d, h, mi, 0);
                c.set(Calendar.MILLISECOND, 0);
                t.when = c.getTimeInMillis();
                t.whenFromText = true;
                dtStart = dt.start();
                dtEnd = dt.end();
            }
        }
        t.merchant = merchant(text, dtStart, dtEnd);
        return t;
    }

    private static final Pattern LABELED = Pattern.compile("(가맹점명?|이용처|사용처|이용\\s*가맹점)\\s*[:：]\\s*(.+)");

    static String merchant(String text, int dtStart, int dtEnd) {
        Matcher lab = LABELED.matcher(text); // 알림톡처럼 "가맹점: …" 이 있으면 그것
        if (lab.find()) {
            String v = clean(lab.group(2));
            if (!v.isEmpty()) return v;
        }
        List<String> lines = new ArrayList<>();
        int afterIndex = -1;
        int pos = 0;
        for (String raw : text.split("\n")) {
            int lineStart = pos;
            pos += raw.length() + 1;
            String line = raw;
            // 시각이 이 줄 안에 있으면 시각 뒤만 본다 (신한 등 한 줄 문자: "… 11/17 12:34 GS25 역삼점 누적…")
            if (dtEnd > lineStart && dtEnd <= lineStart + raw.length()) {
                String after = clean(raw.substring(dtEnd - lineStart));
                if (!after.isEmpty()) return after;
                afterIndex = lines.size();
                continue;
            }
            if (dtStart >= lineStart && dtStart < lineStart + raw.length()) {
                afterIndex = lines.size();
            }
            lines.add(clean(line));
        }
        if (afterIndex >= 0) {
            for (int i = afterIndex; i < lines.size(); i++) if (!lines.get(i).isEmpty()) return lines.get(i);
        }
        for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isEmpty()) return lines.get(i);
        return "";
    }

    private static String clean(String s) {
        String r = s;
        for (String[] c : CARDS) r = r.replaceAll("(?i)(?:" + c[1] + ")\\S*", " ");
        r = NOISE[1].matcher(r).replaceAll(" "); // 누적·잔여 금액은 금액보다 먼저 지운다 ("누적" 글자가 남지 않게)
        r = NOISE[2].matcher(r).replaceAll(" ");
        r = AMOUNT.matcher(r).replaceAll(" ");
        for (Pattern p : NOISE) r = p.matcher(r).replaceAll(" ");
        r = DATETIME.matcher(r).replaceAll(" ");
        r = r.replaceAll("(?i)(삼성\\s*월렛|Samsung\\s*Wallet|삼성\\s*페이|Samsung\\s*Pay|알림톡|카드\\s*알림|안내)", " ");
        r = r.replaceAll("(금액|이용금액|결제금액|일시|일자|시간|카드|구분|승인번호|회원)\\s*[:：]", " ");
        r = r.replaceAll("[\\[\\]()]", " ");
        r = r.replaceAll("^[\\s,·:：\\-]+|[\\s,·:：\\-]+$", "").replaceAll("\\s+", " ").trim();
        r = r.replaceAll("(^|\\s)(을|를|이|가|은|는)(?=\\s|$)", " ").replaceAll("\\s+", " ").trim();
        r = r.replaceAll("에서의?$", "").trim(); // "메가커피에서" → "메가커피" ("현대카드로"는 위 카드 이름 지우기에서 빠진다)
        if (r.length() > 40) r = r.substring(0, 40).trim();
        // 이름·카드번호만 남은 짧은 조각, 기호뿐인 조각은 버린다
        if (r.matches("[\\p{Punct}\\s]*") || r.matches("(?i)(M|S|V|X|님|원|회원)")) return "";
        return r;
    }

    // ---------- 같은 결제가 여러 곳(월렛·카드사 앱·문자)에서 오면 하나로 ----------

    /** 같은 결제로 볼지: 승인/취소·금액·카드가 같고 시각이 5분 안. */
    public static boolean sameTxn(Txn a, Txn b) {
        return a.cancel == b.cancel && a.amount == b.amount && a.card.equals(b.card)
                && Math.abs(a.when - b.when) <= 5 * 60 * 1000L;
    }

    /** 둘 중 정보가 더 많은 것 (가맹점이 있는 것, 그다음 문구에 시각이 있는 것). */
    public static Txn richer(Txn a, Txn b) {
        int sa = (a.merchant.isEmpty() ? 0 : 2) + (a.whenFromText ? 1 : 0);
        int sb = (b.merchant.isEmpty() ? 0 : 2) + (b.whenFromText ? 1 : 0);
        return sb > sa ? b : a;
    }

    public static String ymd(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
    }
}
