package app.jarvis.urlapp;

// 카드 알림 읽기·분류·가계부 통신 시험 (PC 자바, 안드로이드 없이). 알림 문구는 카드사별로 흔한 모양을 본뜬 예시다.
//   javac -cp json.jar -d out shell/CardParser.java shell/CardCategorizer.java shell/LedgerClient.java shell/test/CardTests.java
//   java -cp out:json.jar app.jarvis.urlapp.CardTests

import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CardTests {
    static int ok = 0, fail = 0;

    static void check(String label, boolean cond, Object detail) {
        if (cond) ok++;
        else fail++;
        System.out.println("  [" + (cond ? "통과" : "실패") + "] " + label + (cond ? "" : " — " + detail));
    }

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static void txn(String label, String title, String body, String card, boolean cancel, long amount, String merchant,
                    long when) {
        CardParser.Txn t = CardParser.parse(title, body, at(2026, 11, 17, 12, 59));
        boolean good = t != null && t.card.equals(card) && t.cancel == cancel && t.amount == amount
                && t.merchant.equals(merchant) && (when < 0 || t.when == when);
        check(label, good, t == null ? "null" : t + " when=" + new java.util.Date(t.when));
    }

    /** 보낸 앱(source·appName)과 카드 상품명까지 보는 시험. */
    static CardParser.Txn txn2(String label, String title, String body, String source, String appName, String card,
                               String product, boolean cancel, long amount, String merchant, long when) {
        CardParser.Txn t = CardParser.parse(title, body, at(2026, 11, 17, 12, 59), source, appName);
        boolean good = t != null && t.card.equals(card) && t.product.equals(product) && t.cancel == cancel
                && t.amount == amount && t.merchant.equals(merchant) && (when < 0 || t.when == when);
        check(label, good, t == null ? "null" : t + " [" + t.product + "] when=" + new java.util.Date(t.when));
        return t;
    }

    static void none(String label, String title, String body) {
        CardParser.Txn t = CardParser.parse(title, body, at(2026, 11, 17, 12, 59));
        check(label, t == null, t);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("1) 알림 읽기");
        txn("현대 문자 (여러 줄)", "", "[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:30\n메가커피 강남점\n누적1,234,567원",
                "현대카드", false, 12500, "메가커피 강남점", at(2026, 11, 17, 12, 30));
        txn("삼성 문자 (시각 뒤 가맹점)", "", "[Web발신]\n삼성1234승인 김*수\n15,000원 일시불\n11/17 12:31 스타벅스\n누적158,000원",
                "삼성카드", false, 15000, "스타벅스", at(2026, 11, 17, 12, 31));
        txn("롯데 문자 (괄호 일시불)", "", "[Web발신]\n롯데카드(1*2*)승인\n김*수\n33,000원(일시불)\n11/17 12:32\n이마트 성수점\n누적 500,000원",
                "롯데카드", false, 33000, "이마트 성수점", at(2026, 11, 17, 12, 32));
        txn("농협 문자 (총누적)", "", "[Web발신]\nNH카드3*4*승인\n김*수\n21,500원 일시불\n11/17 12:33\n쿠팡\n총누적1,000,000원",
                "농협카드", false, 21500, "쿠팡", at(2026, 11, 17, 12, 33));
        txn("신한 문자 (한 줄)", "", "[Web발신]\n신한카드(1234)승인 김*수 42,000원(일시불)11/17 12:34 GS25 역삼점 누적1,234,000원",
                "신한카드", false, 42000, "GS25 역삼점", at(2026, 11, 17, 12, 34));
        txn("할부", "", "[Web발신]\n현대카드 M 승인\n김*수\n600,000원 03개월\n11/17 13:00\n하이마트 강남점\n누적1,834,567원",
                "현대카드", false, 600000, "하이마트 강남점", at(2026, 11, 17, 13, 0));
        txn("현대 승인취소", "", "[Web발신]\n현대카드 M 승인취소\n김*수\n12,500원 일시불\n11/17 13:10\n메가커피 강남점",
                "현대카드", true, 12500, "메가커피 강남점", at(2026, 11, 17, 13, 10));
        txn("신한 취소 (한 줄)", "", "[Web발신]\n신한카드(1234)취소 김*수 42,000원 11/17 12:40 GS25 역삼점",
                "신한카드", true, 42000, "GS25 역삼점", -1);
        txn("카카오 알림톡 (항목 이름)", "현대카드", "[현대카드] 승인 안내\n김*수님\n카드: 현대카드M(1*2*)\n금액: 12,500원(일시불)\n일시: 11/17 12:30\n가맹점: 메가커피 강남점\n누적: 1,234,567원",
                "현대카드", false, 12500, "메가커피 강남점", at(2026, 11, 17, 12, 30));
        txn("카드사 앱 알림 (| 구분)", "삼성카드", "15,000원 일시불 승인 | 스타벅스 | 11/17 12:31",
                "삼성카드", false, 15000, "스타벅스", -1);
        txn("삼성 월렛 (가맹점 없음)", "Samsung Wallet", "현대카드로 12,500원 결제했어요",
                "현대카드", false, 12500, "", at(2026, 11, 17, 12, 59));
        txn("삼성 월렛 (가맹점 있음)", "삼성 월렛", "메가커피에서 현대카드로 12,500원을 결제했어요",
                "현대카드", false, 12500, "메가커피", -1);
        txn("롯데 앱 알림 (디지로카)", "디지로카", "롯데카드 승인 8,900원 일시불 11/17 12:10 배달의민족",
                "롯데카드", false, 8900, "배달의민족", at(2026, 11, 17, 12, 10));

        System.out.println("1-2) 카카오톡 카드영수증·카드사별 카드 상품명 (줄인 카드사 이름 + 상품명 + 승인)");
        String kakao = "com.kakao.talk", receipt = "카카오톡 카드영수증";
        long t1157 = at(2026, 11, 17, 11, 57);
        CardParser.Txn kr = txn2("카톡 카드영수증: 현대 네이버", receipt,
                "현대 네이버 승인 김*수 12,300원 일시불 11/17 11:57 행복분식문정점 누적123,456원", kakao, "카카오톡",
                "현대카드", "네이버", false, 12300, "행복분식문정점", t1157);
        txn2("삼성 taptap O", receipt, "삼성 taptap O 승인 김*수 8,000원 일시불 11/17 12:01 GS25 문정점 누적45,000원", kakao, null,
                "삼성카드", "taptap O", false, 8000, "GS25 문정점", at(2026, 11, 17, 12, 1));
        txn2("롯데 LOCA LIKIT", receipt, "롯데 LOCA LIKIT 승인 김*수 15,000원 일시불 11/17 12:05 스타벅스 문정점 누적99,000원",
                kakao, null, "롯데카드", "LOCA LIKIT", false, 15000, "스타벅스 문정점", -1);
        txn2("신한 Deep Dream", receipt, "신한 Deep Dream 승인 김*수 4,500원 일시불 11/17 12:06 메가커피 문정점 누적9,000원",
                kakao, null, "신한카드", "Deep Dream", false, 4500, "메가커피 문정점", -1);
        txn2("NH농협 채움", receipt, "NH농협 채움 승인 김*수 30,000원 일시불 11/17 12:07 하나로마트 송파점 누적60,000원",
                kakao, null, "농협카드", "채움", false, 30000, "하나로마트 송파점", -1);
        txn2("KB국민 체크", receipt, "KB국민 체크 승인 김*수 7,700원 11/17 12:08 다이소 문정점", kakao, null,
                "KB국민카드", "체크", false, 7700, "다이소 문정점", -1);
        txn2("하나 1Q", receipt, "하나 1Q 승인 김*수 5,000원 일시불 11/17 12:09 CU 문정점", kakao, null,
                "하나카드", "1Q", false, 5000, "CU 문정점", -1);
        txn2("우리 카드의정석", receipt, "우리 카드의정석 승인 김*수 6,000원 일시불 11/17 12:10 이디야 문정점", kakao, null,
                "우리카드", "카드의정석", false, 6000, "이디야 문정점", -1);
        txn2("BC 바로", receipt, "BC 바로 승인 김*수 9,900원 일시불 11/17 12:11 올리브영 문정점", kakao, null,
                "BC카드", "", false, 9900, "올리브영 문정점", -1);
        txn2("카톡 카드영수증 승인취소", receipt, "현대 네이버 승인취소 김*수 12,300원 일시불 11/17 12:12 행복분식문정점", kakao, null,
                "현대카드", "네이버", true, 12300, "행복분식문정점", -1);
        txn2("문자: 현대카드 M (상품명 M)", "", "[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:30\n메가커피 강남점",
                "com.samsung.android.messaging", "메시지", "현대카드", "M", false, 12500, "메가커피 강남점", -1);
        check("카톡은 카드영수증·카드사 채널만 믿음", CardParser.cardSender("카카오톡 카드영수증") && CardParser.cardSender("현대카드")
                && CardParser.cardSender("KB국민카드") && CardParser.cardSender("NH농협카드") && !CardParser.cardSender("이성희 대리님")
                && !CardParser.cardSender("엄마") && !CardParser.cardSender(null), null);
        none("\"우리 아이 …\" 같은 말은 카드사가 아님", "", "[Web발신]\n우리 아이 학원비 150,000원 결제 부탁드려요");
        none("\"현대 백화점 …\"·\"롯데 시네마 …\"는 카드사가 아님", "", "[Web발신]\n현대 백화점 상품권 50,000원 사용 완료");
        none("\"롯데 시네마 …\"는 카드사가 아님", "", "[Web발신]\n롯데 시네마 예매 취소 15,000원 환불 예정");

        System.out.println("1-3) 삼성 월렛 (카드사 없음: ₩금액 결제 완료)·카드사 앱 (문구에 카드사 없음)");
        String spay = "com.samsung.android.spay";
        CardParser.Txn wr = txn2("월렛: ₩ 금액 + 가맹점", "₩12,300 결제 완료", "행복분식문정점", spay, "Samsung Wallet",
                CardParser.WALLET_CARD, "", false, 12300, "행복분식문정점", at(2026, 11, 17, 12, 59));
        check("월렛만 온 결제도 메모에 '카드'", CardParser.WALLET_CARD.contains("카드"), null);
        check("월렛 모양은 월렛 앱에서 온 것만", CardParser.parse("₩12,300 결제 완료", "행복분식문정점", t1157) == null
                && CardParser.parse("₩12,300 결제 완료", "행복분식문정점", t1157, "com.samsung.android.messaging") == null, null);
        txn2("월렛: 롯데리아는 가맹점 (롯데카드 아님)", "₩8,000 결제 완료", "롯데리아 강남점", spay, null,
                CardParser.WALLET_CARD, "", false, 8000, "롯데리아 강남점", -1);
        txn2("월렛: 현대백화점은 가맹점 (현대카드 아님)", "₩30,000 결제 완료", "현대백화점 판교점", spay, null,
                CardParser.WALLET_CARD, "", false, 30000, "현대백화점 판교점", -1);
        check("월렛: 충전은 결제가 아님", CardParser.parse("₩10,000 충전 완료", "교통카드", t1157, spay) == null, null);
        CardParser.Txn lotte = txn2("디지로카 앱: 제목이 가맹점, 카드사는 앱 이름, 상품명은 번호 앞",
                "행복카페 문정점", "10,700원 승인\n라스베가스(9*0*)", "com.lcacApp", "롯데카드",
                "롯데카드", "라스베가스", false, 10700, "행복카페 문정점", at(2026, 11, 17, 12, 59));
        txn2("디지로카 앱: 잘린 꼬리(…)·앱 이름 없이 패키지로", "행복카페 문정점", "10,700원 승인\n라스베가스(9*0*)...", "com.lcacApp", null,
                "롯데카드", "라스베가스", false, 10700, "행복카페 문정점", -1);
        txn2("디지로카 앱: 시각이 있어도 가맹점은 제목", "행복카페 문정점", "10,700원 승인\n라스베가스(9*0*)\n일시불 11/17 12:34",
                "com.lcacApp", "롯데카드", "롯데카드", "라스베가스", false, 10700, "행복카페 문정점", at(2026, 11, 17, 12, 34));
        check("카드사 아닌 앱은 앱 이름으로 카드사를 정하지 않음",
                CardParser.parse("행복카페 문정점", "10,700원 승인", t1157, "com.example.shop", "쇼핑") == null, null);

        System.out.println("1-4) 같은 결제의 월렛 + 카톡/카드사 앱 알림 → 한 건");
        CardParser.Txn w2 = CardParser.parse("₩12,300 결제 완료", "행복분식문정점", t1157, spay);
        CardParser.Txn k2 = CardParser.parse(receipt, "현대 네이버 승인 김*수 12,300원 일시불 11/17 11:57 행복분식문정점 누적123,456원",
                t1157, kakao);
        check("월렛(카드사 모름)과 카톡은 같은 결제", CardParser.sameTxn(w2, k2), w2 + " / " + k2);
        CardParser.Txn mk = CardParser.richer(w2, k2);
        check("합치면 카드 이름은 카톡의 '현대카드 네이버'", mk.label().equals("현대카드 네이버") && mk.merchant.equals("행복분식문정점")
                && mk.whenFromText, mk + " [" + mk.label() + "]");
        CardParser.Txn w3 = CardParser.parse("₩10,700 결제 완료", "행복카페 문정점", at(2026, 11, 17, 12, 34), spay);
        CardParser.Txn l3 = CardParser.parse("행복카페 문정점", "10,700원 승인\n라스베가스(9*0*)", at(2026, 11, 17, 12, 34), "com.lcacApp", "롯데카드");
        CardParser.Txn m3 = CardParser.sameTxn(w3, l3) ? CardParser.richer(l3, w3) : null;
        check("월렛 + 디지로카 → '롯데카드 라스베가스' 한 건", m3 != null && m3.label().equals("롯데카드 라스베가스")
                && m3.merchant.equals("행복카페 문정점"), m3);
        CardParser.Txn hc = CardParser.parse("", "[Web발신]\n현대카드 M 승인\n김*수\n12,300원 일시불\n11/17 11:57\n행복분식문정점",
                t1157, "com.samsung.android.messaging");
        check("다른 카드사끼리는 합치지 않음", !CardParser.sameTxn(hc, CardParser.parse("", "[Web발신]\n삼성1234승인 김*수\n12,300원 일시불\n11/17 11:57 행복분식문정점", t1157)), null);
        check("상품명이 알림마다 달라도(문자 'M' / 카톡 '네이버') 같은 카드사면 같은 결제", CardParser.sameTxn(hc, kr), null);

        none("해외 결제 (원화 금액 없음)", "", "[Web발신]\n신한카드(1234)승인 김*수 USD 12.34 11/17 12:34 AMAZON 누적1,234,000원");
        none("카드와 무관한 메시지", "엄마", "오늘 점심 12,000원 나눠 내자");
        none("카드 광고", "", "[Web발신](광고)현대카드 이벤트 결제 시 최대 50,000원 캐시백");
        none("결제 예정 안내", "", "[Web발신]\n신한카드 11월 결제예정금액 1,234,000원 결제일 25일");
        CardParser.Txn y = CardParser.parse("", "[Web발신]\n현대카드 M 승인\n김*수\n9,000원 일시불\n12/31 23:50\n편의점",
                at(2027, 1, 2, 9, 0));
        check("연말 결제를 새해에 받으면 작년 날짜", y != null && CardParser.ymd(y.when).equals("2026-12-31"),
                y == null ? null : CardParser.ymd(y.when));

        System.out.println("2) 같은 결제 합치기");
        CardParser.Txn wallet = CardParser.parse("Samsung Wallet", "현대카드로 12,500원 결제했어요", at(2026, 11, 17, 12, 31));
        CardParser.Txn sms = CardParser.parse("", "[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:30\n메가커피 강남점",
                at(2026, 11, 17, 12, 31));
        check("월렛·문자는 같은 결제", CardParser.sameTxn(wallet, sms), wallet + " / " + sms);
        check("가맹점 있는 쪽을 고름", CardParser.richer(wallet, sms) == sms && CardParser.richer(sms, wallet) == sms, null);
        CardParser.Txn other = CardParser.parse("", "[Web발신]\n현대카드 M 승인\n김*수\n12,500원 일시불\n11/17 12:50\n메가커피 강남점",
                at(2026, 11, 17, 12, 51));
        check("20분 뒤 같은 금액은 다른 결제", !CardParser.sameTxn(sms, other), null);

        System.out.println("3) 분류");
        List<CardCategorizer.Rule> rules = new ArrayList<>();
        JSONObject cfg = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(args.length > 0 ? args[0] : "../../cards/smart-ledger.json")), StandardCharsets.UTF_8));
        JSONArray rj = cfg.getJSONArray("rules");
        for (int i = 0; i < rj.length(); i++) {
            JSONObject r = rj.getJSONObject(i);
            JSONArray kws = r.getJSONArray("keywords");
            String[] k = new String[kws.length()];
            for (int j = 0; j < k.length; j++) k[j] = kws.getString(j);
            rules.add(new CardCategorizer.Rule(r.getString("large"), r.getString("medium"), k));
        }
        JSONArray def = cfg.getJSONArray("default_category");
        CardCategorizer cat = new CardCategorizer(rules, def.getString(0), def.getString(1));
        String[][] expect = {
                {"스타벅스 역삼역점", "생활비"}, {"GS칼텍스 역삼주유소", "주유비"}, {"GS25 역삼점", "생활비"},
                {"CGV 강남", "취미"}, {"온누리약국", "병원"}, {"배달의민족", "식비"}, {"쿠팡", "쇼핑"},
                {"이마트 성수점", "생활비"}, {"SK텔레콤", "통신비"}, {"카카오T 택시", "교통비"}, {"야놀자", "여행비"},
                {"CUPID LOUNGE", "기타"}, {"", "기타"}, {"하이마트 강남점", "쇼핑"},
        };
        for (String[] e : expect) {
            CardCategorizer.Pick p = cat.pick(e[0]);
            check("규칙: " + (e[0].isEmpty() ? "(가맹점 없음)" : e[0]) + " → " + e[1], p.large.equals(e[1]),
                    p.large + "›" + p.medium + " (" + p.source + ")");
        }
        cat.learn(Arrays.asList(
                new String[] {"메가커피 강남점", "식비", "커피"}, new String[] {"메가커피 역삼점", "식비", "커피"},
                new String[] {"메가커피", "생활비", "마트/식재료/편의점/카페 등"}, new String[] {"하이마트 강남점", "쇼핑", "가전"}));
        CardCategorizer.Pick p1 = cat.pick("메가커피 선릉점");
        check("기록: 같은 상호 다른 지점은 많이 쓴 분류", p1.large.equals("식비") && p1.medium.equals("커피")
                && p1.source.equals("history"), p1.large + "›" + p1.medium + " " + p1.source);
        CardCategorizer.Pick p2 = cat.pick("하이마트 강남점");
        check("기록이 규칙보다 먼저 (사용자가 만든 분류도)", p2.large.equals("쇼핑") && p2.medium.equals("가전"), p2.large);
        check("분류 기본값", cat.pick("처음보는가게").source.equals("default"), null);

        System.out.println("4) 사이트 연동 파일 (/urlapp.json)");
        JSONObject m1 = LinkConfig.merge(cfg, "{\"cards\":{\"add\":{\"method\":\"POST\",\"path\":\"/api/v2/expense\","
                + "\"body\":{\"when\":\"{date}\",\"price\":\"{amount}\"}}}}");
        check("사이트 파일의 add 가 앱 기본값을 덮음 (나머지는 그대로)", m1.getJSONObject("add").getString("path").equals("/api/v2/expense")
                && m1.getJSONObject("delete").getString("path").equals("/api/monthly-details/delete-single")
                && m1.optBoolean("_from_site") && m1.getJSONArray("owners").length() == 2, m1.optJSONObject("add"));
        check("다른 사이트 주소는 거부 (기본값 그대로)", LinkConfig.merge(cfg,
                "{\"cards\":{\"add\":{\"path\":\"//evil.example/x\",\"body\":{}}}}") == cfg, null);
        check("http 주소도 거부", LinkConfig.merge(cfg,
                "{\"cards\":{\"add\":{\"path\":\"/x?u=https://evil.example\",\"body\":{}}}}") == cfg, null);
        JSONObject m2 = LinkConfig.merge(cfg, "{\"cards\":{\"auth\":{\"header\":\"X-App-Key\"}}}");
        check("사이트 파일의 auth(앱 연동 키 이름)를 읽음", m2.getJSONObject("auth").getString("header").equals("X-App-Key"), m2);
        check("이상한 키 이름은 거부", LinkConfig.merge(cfg, "{\"cards\":{\"auth\":{\"header\":\"X: y\"}}}") == cfg, null);
        check("모양이 이상하면 기본값", LinkConfig.merge(cfg, "<!doctype html><html>") == cfg
                && LinkConfig.merge(cfg, "{\"cards\":{\"default_category\":[\"기타\"]}}") == cfg
                && LinkConfig.merge(cfg, null) == cfg, null);

        System.out.println("5) 가계부 사이트와 주고받기 (시험 서버)");
        final List<String[]> seen = new ArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final boolean[] failAdd = {false};
        final boolean[] requireKey = {false};
        srv.createContext("/", ex -> {
            String body;
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                body = new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
            String path = ex.getRequestURI().toString();
            seen.add(new String[] {ex.getRequestMethod(), path, body});
            if (requireKey[0] && !"k1-secret".equals(ex.getRequestHeaders().getFirst("X-App-Key"))) {
                byte[] no = "{\"success\":false,\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(401, no.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(no);
                }
                return;
            }
            String resp;
            int code = 200;
            if (path.startsWith("/api/monthly-details/add-single")) {
                resp = failAdd[0] ? "{\"success\":false,\"error\":\"x\"}" : "{\"success\":true}";
            } else if (path.startsWith("/api/monthly-details?month=2026-11")) {
                resp = "{\"variable\":[{\"id\":77,\"source_table\":\"variable_expenses\",\"date\":\"2026-11-17\","
                        + "\"details\":\"메가커피 강남점\",\"amount\":12500,\"earner_name\":\"창수\",\"memo\":\"현대카드\","
                        + "\"category_large\":\"식비\",\"category_medium\":\"커피\"}],"
                        + "\"fixed\":[{\"id\":5,\"source_table\":\"fixed_expenses\",\"date\":\"2026-11-01\",\"details\":\"관리비\","
                        + "\"amount\":200000,\"category_large\":\"공과금\",\"category_medium\":\"관리비\"}]}";
            } else if (path.startsWith("/api/monthly-details/delete-single")) {
                resp = "{\"success\":true}";
            } else {
                code = 500;
                resp = "{}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/";
        try {
            LedgerClient client = new LedgerClient(base, cfg);
            Map<String, Object> v = new HashMap<>();
            v.put("date", "2026-11-17");
            v.put("large", "식비");
            v.put("medium", "커피");
            v.put("merchant", "메가커피 강남점");
            v.put("amount", "12500");
            v.put("owner", "창수");
            v.put("card", "현대카드");
            check("추가 성공", client.add(v), seen);
            String[] last = seen.get(seen.size() - 1);
            JSONObject sent = new JSONObject(last[2]);
            check("추가 요청 모양 (사이트의 수동 추가와 같게)", last[0].equals("POST")
                    && last[1].equals("/api/monthly-details/add-single") && sent.getString("type").equals("변동지출")
                    && sent.getString("details").equals("메가커피 강남점") && sent.getString("amount").equals("12500")
                    && sent.getString("earner_name").equals("창수") && sent.getString("memo").equals("현대카드")
                    && sent.getString("category_large").equals("식비") && sent.getString("date").equals("2026-11-17"),
                    last[2]);
            failAdd[0] = true;
            check("사이트가 success:false 면 실패로", !client.add(v), null);
            check("실패 이유: success:false 의 오류 문구", client.lastReason().equals("HTTP 200 · x"), client.lastReason());
            failAdd[0] = false;
            List<JSONObject> rows = client.history("2026-11");
            check("기록 읽기 (변동·고정 모두)", rows.size() == 2, rows);
            boolean del = client.deleteMatching("2026-11-17", "메가커피 강남점", 12500, "창수", "현대카드");
            String[] d = seen.get(seen.size() - 1);
            check("되돌리기: 찾아서 지움 (id 는 숫자 그대로)", del && d[0].equals("DELETE")
                    && d[1].equals("/api/monthly-details/delete-single")
                    && new JSONObject(d[2]).get("id") instanceof Integer
                    && new JSONObject(d[2]).getString("source_table").equals("variable_expenses"), Arrays.toString(d));
            check("다른 금액은 지우지 않음", !client.deleteMatching("2026-11-17", "메가커피 강남점", 12000, "창수", "현대카드"), null);
            requireKey[0] = true; // 사이트에 보안(앱 연동 키)을 켠 뒤
            check("보안 켠 뒤: 키 없으면 401 (앱은 '키를 확인해 주세요')", !client.add(v) && client.lastCode() == 401, client.lastCode());
            check("실패 이유: HTTP 401 과 사이트 문구", client.lastReason().equals("HTTP 401 · unauthorized"), client.lastReason());
            LedgerClient keyed = new LedgerClient(base, cfg).withAuth("X-App-Key", "k1-secret");
            check("보안 켠 뒤: 맞는 키를 붙이면 등록·기록 읽기 됨", keyed.add(v) && keyed.history("2026-11").size() == 2, keyed.lastCode());
            check("성공하면 실패 이유가 비어 있음", keyed.lastReason().isEmpty(), keyed.lastReason());
            LedgerClient off = new LedgerClient("http://127.0.0.1:1/", cfg);
            boolean threw = false;
            try {
                off.add(v);
            } catch (java.io.IOException ex) {
                threw = true;
            }
            check("실패 이유: 연결 못 함", threw && off.lastCode() == 0 && off.lastReason().startsWith("연결 실패 · "), off.lastReason());
            check("실패 이유: HTML 오류 쪽은 글자만 짧게",
                    LedgerClient.shortMessage("<html><body><h1>502</h1> Bad   Gateway</body></html>").equals("502 Bad Gateway")
                            && LedgerClient.shortMessage("{\"message\":\"" + "가".repeat(80) + "\"}").length() == 61,
                    LedgerClient.shortMessage("<html><body><h1>502</h1> Bad   Gateway</body></html>"));
            check("키 이름이 이상하면 붙이지 않음", !new LedgerClient(base, cfg).withAuth("X-App-Key\r\nEvil", "k1-secret").add(v), null);
            requireKey[0] = false;
        } finally {
            srv.stop(0);
        }

        System.out.println("카드 시험: " + ok + "개 통과, " + fail + "개 실패");
        System.exit(fail == 0 ? 0 : 1);
    }
}
