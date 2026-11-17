package app.jarvis.urlapp;

// 카드 승인·취소 알림 문구를 읽어 금액·가맹점·카드사·카드 상품명·시각을 뽑는다 (안드로이드 API 를 쓰지 않아 PC 자바로 시험).
// 문자(SMS), 카드사 앱 알림, 카카오톡 카드영수증·카드사 알림톡, 삼성 월렛 알림의 흔한 모양을 다룬다.
// 카드사마다(같은 카드사도 카드 상품마다) 문구가 다르고 바뀔 수 있어서, 정해진 틀 대신
//   - 카드사: 정식 이름("현대카드", "KB국민카드", "디지로카" …)은 어디에 있어도, 줄인 이름("현대", "삼성", "NH" …)은
//     줄 맨 앞에 따로 떨어져 있을 때만 (예: "현대 네이버 승인", "삼성1337승인". "롯데리아"·"현대백화점" 같은 가맹점과 헷갈리지 않게)
//   - 카드 상품명: 카드사 이름과 승인/취소/사용 사이의 말 (네이버, M, LOCA LIKIT, Deep Dream, 체크 …)
//   - 승인/취소 + 금액원 이 있는 알림만 받고, 나머지에서 날짜·가맹점을 찾는다.
// 삼성 월렛은 카드사 없이 "₩58,600 결제 완료" + 가맹점으로 오므로 월렛 앱에서 온 것만 따로 읽는다 (카드는 WALLET_CARD,
// 같은 결제의 카드사 알림이 오면 합칠 때 그 카드 이름을 쓴다). 해외 결제(원화가 아닌 금액)는 받지 않는다.

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardParser {
    private CardParser() {}

    public static final class Txn {
        public String card;        // "현대카드" 등 (카드사. 모르면 WALLET_CARD)
        public String product = ""; // 카드 상품명 ("네이버", "M", "LOCA LIKIT" …, 모르면 "")
        public boolean cancel;     // 승인취소
        public long amount;        // 원
        public long when;          // 결제 시각 (ms)
        public boolean whenFromText; // 알림 문구에 시각이 있었는지
        public String merchant;    // 가맹점 ("" 이면 모름)

        @Override
        public String toString() {
            return (cancel ? "취소 " : "승인 ") + label() + " " + amount + "원 " + merchant;
        }

        /** 메모에 쓰는 카드 이름: 카드사 + 상품명 (예: "현대카드 네이버"). */
        public String label() {
            return CardParser.label(card, product);
        }
    }

    /** 카드사를 모르는 삼성 월렛 결제의 카드 이름 (메모에 "카드"가 들어가게). */
    public static final String WALLET_CARD = "삼성월렛 카드";

    public static String label(String card, String product) {
        return product == null || product.isEmpty() ? card : card + " " + product;
    }

    // 카드사: {메모에 쓰는 이름, 정식 이름(어디에 있어도), 줄인 이름(줄 맨 앞에 따로 있을 때만, 없으면 "")}
    private static final String[][] CARDS = {
            {"현대카드", "현대\\s*카드|HYUNDAI\\s*CARD", "현대|HYUNDAI"},
            {"삼성카드", "삼성\\s*카드|SAMSUNG\\s*CARD", "삼성"},
            {"롯데카드", "롯데\\s*카드|LOTTE\\s*CARD|디지로카", "롯데|LOTTE"},
            {"농협카드", "NH\\s*농협(?:\\s*카드)?|농협\\s*카드|NH\\s*카드|NH\\s*pay", "NH|농협"},
            {"신한카드", "신한\\s*카드|SHINHAN\\s*CARD|신한\\s*SOL|신한\\s*pLay", "신한|SHINHAN"},
            {"KB국민카드", "KB\\s*국민(?:\\s*카드)?|국민\\s*카드|KB\\s*카드|KB\\s*Pay", "KB|국민"},
            {"하나카드", "하나\\s*카드|HANA\\s*CARD|하나\\s*Pay|하나\\s*페이", "하나|HANA"},
            {"우리카드", "우리\\s*카드|WOORI\\s*CARD|우리\\s*WON\\s*카드", "우리|WOORI"},
            {"BC카드", "BC\\s*카드|비씨\\s*카드|BC\\s*바로|바로\\s*카드|페이북", "BC|비씨"},
            {"씨티카드", "씨티\\s*카드|CITI\\s*CARD", "씨티|CITI"},
            {"IBK기업카드", "IBK\\s*기업(?:\\s*카드)?|기업\\s*카드|IBK\\s*카드", "IBK"},
            {"카카오뱅크카드", "카카오\\s*뱅크", "카뱅"},
            {"토스뱅크카드", "토스\\s*뱅크", ""},
            {"케이뱅크카드", "케이\\s*뱅크", ""},
            {"수협카드", "수협\\s*카드|Sh\\s*수협", "수협"},
            {"우체국카드", "우체국\\s*(?:체크\\s*)?카드", "우체국"},
            {"광주카드", "광주\\s*카드", ""},
            {"전북카드", "전북\\s*카드|JB\\s*카드", ""},
            {"제주카드", "제주\\s*카드", ""},
    };
    // 줄인 이름 뒤에 오면 카드가 아니라 다른 것 (가맹점·회사 이름)
    private static final String NOT_CARD_AFTER =
            "(?!\\s*(?:마트|백화점|시네마|리아|월드|홈쇼핑|하이마트|면세점|호텔|렌터카|오일|해상|자동차|아울렛|건설|증권|생명|화재|"
                    + "은행|전자|하나로|축산|몰|타워|프리미엄|정유|글로비스|모비스|물산|제과|칠성|푸드|웰푸드|이네오스))";
    private static final String CARD_WORD = "카드(?:(?=로|으로|는|가|를|에서|승인|취소|사용|결제)|(?![가-힣]))";
    private static final Pattern[] CARD_FULL = new Pattern[CARDS.length];
    private static final Pattern[] CARD_SHORT = new Pattern[CARDS.length];

    static {
        for (int i = 0; i < CARDS.length; i++) {
            // "카드" 바로 뒤에 다른 말이 붙으면 상품명의 일부 ("우리 카드의정석"). 조사(로·는 …)·승인·취소는 괜찮다.
            CARD_FULL[i] = Pattern.compile("(?:" + CARDS[i][1].replace("카드", CARD_WORD) + ")", Pattern.CASE_INSENSITIVE);
            // 줄 맨 앞(앞에 [Web발신] 같은 꼬리표가 있어도 됨), 뒤는 띄어쓰기·숫자·괄호·줄 끝
            CARD_SHORT[i] = CARDS[i][2].isEmpty() ? null : Pattern.compile(
                    "(?m)^[ \\t]*(?:\\[[^\\]\\n]{0,12}\\][ \\t]*)?(" + CARDS[i][2] + ")(?=[\\s\\d(]|$)" + NOT_CARD_AFTER
                            + "(?=[^\\n]{0,20}?(?:승인|취소|사용))", // 같은 줄 20자 안에 승인·취소·사용 ("우리 아이 …" 같은 말과 구별)
                    Pattern.CASE_INSENSITIVE);
        }
    }

    // 카드사 앱 (패키지 이름의 일부 → 카드사). 알림 문구에 카드사 이름이 없을 때 보낸 앱으로 정한다.
    private static final String[][] CARD_APPS = {
            {"hyundaicard", "현대카드"}, {"samsungcard", "삼성카드"}, {"lcacapp", "롯데카드"}, {"lottecard", "롯데카드"},
            {"nh.smart", "농협카드"}, {"nonghyup", "농협카드"}, {"shcard", "신한카드"}, {"shinhancard", "신한카드"},
            {"kbcard", "KB국민카드"}, {"hanaskcard", "하나카드"}, {"hanacard", "하나카드"}, {"wooricard", "우리카드"},
            {"bccard", "BC카드"}, {"citicard", "씨티카드"},
    };
    // 가려진 카드 번호 (1*2*, 9*0*): 이 앞의 말은 카드 상품명, 이 줄은 가맹점이 아니다
    private static final Pattern MASKED_NO = Pattern.compile("\\d[\\d*]*\\*|\\*[\\d*]*\\d");
    private static final Pattern PRODUCT_BEFORE_NO = Pattern.compile(
            "([가-힣A-Za-z][가-힣A-Za-z0-9&+. ]{0,15}?)\\s*\\(\\s*[\\d*]{3,}\\s*\\)");

    /** 보낸 앱(이름 또는 패키지)으로 정한 카드사. 모르면 null. */
    static String cardFromApp(String source, String appName) {
        for (String n : new String[] {appName, source}) {
            if (n == null || n.isEmpty()) continue;
            for (int i = 0; i < CARDS.length; i++) if (CARD_FULL[i].matcher(n).find()) return CARDS[i][0];
        }
        if (source != null) {
            String s = source.toLowerCase(Locale.ROOT);
            for (String[] a : CARD_APPS) if (s.contains(a[0])) return a[1];
        }
        return null;
    }

    /** 카드사 알림톡·카카오톡 카드영수증을 보낸 쪽 이름인지 (카카오톡은 이때만 믿는다). */
    public static boolean cardSender(CharSequence title) {
        if (title == null) return false;
        String t = title.toString();
        if (t.contains("카드영수증")) return true;
        for (Pattern p : CARD_FULL) if (p.matcher(t).find()) return true;
        return false;
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원");
    private static final Pattern NOT_PAYMENT_BEFORE = Pattern.compile("(누적|잔여|잔액|한도|포인트|적립|이용가능|결제예정|청구)\\s*[:：]?\\s*$");
    private static final Pattern DATETIME = Pattern.compile("(\\d{1,2})[/.\\-월]\\s*(\\d{1,2})일?\\s*(\\d{1,2})[:시]\\s*(\\d{2})분?");
    private static final Pattern APPROVE = Pattern.compile("승인|결제|사용");
    // 카드 상품명은 카드사 이름 뒤 여기까지 (승인·취소·사용·결제, 이름 가림 김*수, 금액, 날짜)
    private static final Pattern PRODUCT_END = Pattern.compile(
            "승인|취소|사용|결제|[가-힣A-Za-z]\\*|\\d{1,3}(?:,\\d{3})+\\s*원|\\d+\\s*원|\\d{1,2}[/.\\-]\\d{1,2}|\\n");
    // 삼성 월렛: "₩58,600 결제 완료" (카드사 없음)
    private static final Pattern WON_SIGN = Pattern.compile("[₩￦]\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)");
    private static final Pattern WALLET_PAID = Pattern.compile("결제\\s*(?:완료|됐|되었|했|승인)|승인\\s*완료|결제\\s*취소|취소\\s*(?:완료|됐|되었)");
    private static final Pattern WALLET_NOT = Pattern.compile("충전|송금|이체|환불|적립|입금|출금|교통");
    // 광고·안내(결제 예정, 할인, 이벤트 등)는 결제가 아니다
    private static final Pattern NOT_TXN = Pattern.compile(
            "\\(광고\\)|\\[광고\\]|이벤트|캐시백|혜택|무이자|청구\\s*예정|결제\\s*예정|결제일|명세서|청구서|한도\\s*상향|대출|리볼빙"
                    // 카드값 빠져나감·계좌 입출금은 결제가 아니다 (예: 디지로카 "결제대금 인출 안내 … 출금되었습니다")
                    + "|결제\\s*대금|이용\\s*대금|카드\\s*대금|청구\\s*금액|인출|출금|입금|자동\\s*이체|계좌\\s*이체|납부|연체|선결제");
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

    /** 카드 결제 알림이면 Txn, 아니면 null. now 는 알림이 온 시각 (연도·시각이 없을 때 쓴다). 월렛 모양은 읽지 않는다. */
    public static Txn parse(String title, String body, long now) {
        return parse(title, body, now, null);
    }

    /**
     * source: 알림을 보낸 앱 (패키지 이름, 또는 MacroDroid 가 준 앱 이름). 삼성 월렛에서 온 것만 카드사 없는
     * "₩금액 결제 완료" 모양을 읽는다. "*" 는 아무 앱이나 (설정 화면의 시험 칸).
     */
    public static Txn parse(String title, String body, long now, String source) {
        return parse(title, body, now, source, null);
    }

    /** appName: 보낸 앱의 이름 (예: "롯데카드"). 문구에 카드사 이름이 없으면 이것(또는 패키지)으로 카드사를 정한다. */
    public static Txn parse(String title, String body, long now, String source, String appName) {
        String text = ((title == null ? "" : title) + "\n" + (body == null ? "" : body))
                .replace('\r', '\n').replace('|', '\n').replace(' ', ' ');
        String card = null;
        int cardPos = Integer.MAX_VALUE;
        int cardEnd = -1;
        for (int i = 0; i < CARDS.length; i++) {
            Matcher m = CARD_FULL[i].matcher(text);
            if (m.find() && m.start() < cardPos) {
                cardPos = m.start();
                cardEnd = m.end();
                card = CARDS[i][0];
            }
            if (CARD_SHORT[i] != null) {
                Matcher sh = CARD_SHORT[i].matcher(text);
                if (sh.find() && sh.start(1) < cardPos) {
                    cardPos = sh.start(1);
                    cardEnd = sh.end(1);
                    card = CARDS[i][0];
                }
            }
        }
        boolean fromApp = false;
        if (card == null) {
            card = walletSource(source) && !"*".equals(source) ? null : cardFromApp(source, appName);
            if (card == null) return walletOnly(text, now, source);
            fromApp = true; // 예: 디지로카 "카페베네 문정점 / 10,700원 승인 / 라스베가스(9*0*)"
        }
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
        t.product = fromApp ? productBeforeNumber(text) : product(text, cardEnd);
        t.cancel = cancel;
        t.amount = amount;
        t.when = now;
        readWhenAndMerchant(t, text, now);
        return t;
    }

    /** 삼성 월렛처럼 카드사 없이 오는 결제 ("₩58,600 결제 완료" + 가맹점). 월렛 앱에서 온 것만. */
    private static Txn walletOnly(String text, long now, String source) {
        if (!walletSource(source)) return null;
        if (NOT_TXN.matcher(text).find() || WALLET_NOT.matcher(text).find()) return null;
        if (!WALLET_PAID.matcher(text).find()) return null;
        long amount = -1;
        Matcher w = WON_SIGN.matcher(text);
        if (w.find()) {
            amount = Long.parseLong(w.group(1).replace(",", ""));
        } else {
            Matcher am = AMOUNT.matcher(text);
            while (am.find()) {
                String before = text.substring(Math.max(0, am.start() - 8), am.start());
                if (NOT_PAYMENT_BEFORE.matcher(before).find()) continue;
                amount = Long.parseLong(am.group(1).replace(",", ""));
                break;
            }
        }
        if (amount <= 0) return null;
        Txn t = new Txn();
        t.card = WALLET_CARD;
        t.cancel = CANCEL.matcher(text).find();
        t.amount = amount;
        t.when = now;
        readWhenAndMerchant(t, text, now);
        return t;
    }

    /** 삼성 월렛(페이)에서 온 알림인지. "*" = 설정 화면 시험 칸 (어느 앱이든). */
    static boolean walletSource(String source) {
        if (source == null) return false;
        if ("*".equals(source)) return true;
        String s = source.toLowerCase(Locale.ROOT);
        return s.contains("spay") || s.contains("wallet") || s.contains("월렛") || s.contains("samsung pay")
                || s.contains("삼성페이") || s.contains("삼성 페이");
    }

    /** 카드사 이름 바로 뒤부터 승인/취소/사용(또는 이름·금액·날짜) 앞까지의 말 = 카드 상품명. 확실하지 않으면 "". */
    static String product(String text, int from) {
        if (from < 0 || from >= text.length()) return "";
        String rest = text.substring(from);
        Matcher end = PRODUCT_END.matcher(rest);
        String p = end.find() ? rest.substring(0, end.start()) : "";
        p = p.replaceAll("\\([^)]*\\)", " ")                  // (1234), (1*2*)
                .replaceAll("[\\d*]{2,}", " ")                 // 1337, 1*2*
                .replaceAll("(^|\\s)카드(?=\\s|$)", " ")         // 따로 있는 "카드"
                .replaceAll("^\\s*(으로|로)(?=\\s|$)", " ")      // "현대카드로 … 결제"
                .replaceAll("[\\[\\]<>:：,·]", " ")
                .replaceAll("\\s+", " ").trim();
        if (p.isEmpty() || p.length() > 16 || p.split(" ").length > 3) return "";
        if (p.matches("[\\p{Punct}\\s\\d]*")) return "";
        return p;
    }

    /** "라스베가스(9*0*)" 처럼 가려진 카드 번호 앞의 말 (문구에 카드사 이름이 없을 때의 상품명). */
    static String productBeforeNumber(String text) {
        Matcher m = PRODUCT_BEFORE_NO.matcher(text);
        if (!m.find()) return "";
        String p = m.group(1).replaceAll("\\s+", " ").trim();
        if (p.matches(".*(승인|취소|사용|결제|원).*") || p.length() > 16) return "";
        return p;
    }

    private static void readWhenAndMerchant(Txn t, String text, long now) {
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
            // 가려진 카드 번호가 있는 줄 ("라스베가스(9*0*)", "현대카드 M(1*2*)")은 카드 이름 줄이지 가맹점이 아니다
            lines.add(MASKED_NO.matcher(line).find() ? "" : clean(line));
        }
        if (afterIndex >= 0) {
            for (int i = afterIndex; i < lines.size(); i++) if (!lines.get(i).isEmpty()) return lines.get(i);
        }
        for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isEmpty()) return lines.get(i);
        return "";
    }

    private static String clean(String s) {
        String r = s;
        for (int i = 0; i < CARDS.length; i++) {
            r = r.replaceAll("(?i)(?:" + CARDS[i][1] + ")\\S*", " ");
            // 줄 맨 앞의 줄인 카드사 이름 + 상품명 (승인·취소·사용 앞까지): "현대 네이버 승인 …" → "승인 …"
            if (CARD_SHORT[i] != null) {
                Matcher sh = CARD_SHORT[i].matcher(r);
                if (sh.find()) {
                    Matcher end = Pattern.compile("승인|취소|사용").matcher(r);
                    int e = end.find(sh.end(1)) && end.start() - sh.end(1) <= 20 ? end.start() : sh.end(1);
                    r = r.substring(0, sh.start(1)) + " " + r.substring(e);
                }
            }
        }
        r = WON_SIGN.matcher(r).replaceAll(" ");
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
        r = r.replaceAll("(\\.{2,}|…)+$", "").trim(); // 알림에서 잘린 꼬리 "…"
        if (r.length() > 40) r = r.substring(0, 40).trim();
        // 이름·카드번호만 남은 짧은 조각, 기호뿐인 조각은 버린다
        if (r.matches("[\\p{Punct}\\s]*") || r.matches("(?i)(M|S|V|X|님|원|회원)")) return "";
        return r;
    }

    // ---------- 같은 결제가 여러 곳(월렛·카드사 앱·문자)에서 오면 하나로 ----------

    /** 같은 카드사로 볼지 (월렛처럼 카드사를 모르는 쪽은 어느 카드와도 같다고 본다). */
    public static boolean sameCard(String a, String b) {
        return a.equals(b) || WALLET_CARD.equals(a) || WALLET_CARD.equals(b);
    }

    /** 같은 결제로 볼지: 승인/취소·금액·카드사가 같고(상품명은 알림마다 달리 적혀 보지 않음) 시각이 5분 안. */
    public static boolean sameTxn(Txn a, Txn b) {
        return a.cancel == b.cancel && a.amount == b.amount && sameCard(a.card, b.card)
                && Math.abs(a.when - b.when) <= 5 * 60 * 1000L;
    }

    /**
     * 둘 중 정보가 더 많은 것 (가맹점, 문구의 시각, 아는 카드사 순). 고른 쪽에 없는 카드사·상품명·가맹점·시각은
     * 다른 쪽에서 채운다 (예: 월렛 "₩58,600 결제 완료" + 카톡 카드영수증 → 카드는 카톡의 "현대카드 네이버").
     */
    public static Txn richer(Txn a, Txn b) {
        int sa = (a.merchant.isEmpty() ? 0 : 4) + (a.whenFromText ? 2 : 0) + (WALLET_CARD.equals(a.card) ? 0 : 1);
        int sb = (b.merchant.isEmpty() ? 0 : 4) + (b.whenFromText ? 2 : 0) + (WALLET_CARD.equals(b.card) ? 0 : 1);
        Txn best = sb > sa ? b : a;
        Txn other = best == a ? b : a;
        if (WALLET_CARD.equals(best.card) && !WALLET_CARD.equals(other.card)) {
            best.card = other.card;
            best.product = other.product;
        } else if (best.product.isEmpty() && best.card.equals(other.card)) {
            best.product = other.product;
        }
        if (best.merchant.isEmpty()) best.merchant = other.merchant;
        if (!best.whenFromText && other.whenFromText) {
            best.when = other.when;
            best.whenFromText = true;
        }
        return best;
    }

    public static String ymd(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
    }
}
