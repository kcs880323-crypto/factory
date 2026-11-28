package app.jarvis.urlapp;

// 네이티브 알람의 목록 읽기 시험 (PC 자바, 안드로이드 없이).
//   javac -cp json.jar:android.jar -d out shell/AlarmPlan.java shell/test/AlarmTests.java
//   (실행: json.jar 를 android.jar 보다 앞에)

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class AlarmTests {
    static int ok = 0, fail = 0;

    static void check(String label, boolean cond, Object detail) {
        if (cond) ok++;
        else fail++;
        System.out.println("  [" + (cond ? "통과" : "실패") + "] " + label + (cond ? "" : " — " + detail));
    }

    static List<String> ids(List<AlarmPlan.Alarm> l) {
        List<String> out = new ArrayList<>();
        for (AlarmPlan.Alarm a : l) out.add(a.id);
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("알람 목록");
        long now = 1_795_750_000_000L;
        String body = new JSONObject().put("alarms", new JSONArray()
                .put(new JSONObject().put("id", "late").put("label", "나중").put("fire_ms", now + 3_600_000).put("repeat", "daily"))
                .put(new JSONObject().put("id", "a1b2c3d4").put("label", "가락시장 출발").put("time", "12:40").put("hour", 12)
                        .put("minute", 40).put("fire_ms", now + 800_000).put("repeat", "once"))
                .put(new JSONObject().put("id", "past").put("label", "지난 것").put("fire_ms", now - 1000))
                .put(new JSONObject().put("id", "bad id!").put("label", "x").put("fire_ms", now + 1000))
                .put(new JSONObject().put("id", "a1b2c3d4").put("label", "같은 id").put("fire_ms", now + 5000))
                .put(new JSONObject().put("id", "far").put("fire_ms", now + 500L * 86_400_000L))
                .put(new JSONObject().put("id", "nofire").put("label", "시각 없음"))
                .put(new JSONObject().put("id", "ctl").put("label", "줄\n바꿈\t이름  ").put("fire_ms", now + 2000).put("repeat", "<b>")))
                .toString();
        List<AlarmPlan.Alarm> l = AlarmPlan.parse(body, now);
        check("서버 모양 그대로: 앞으로 울릴 것만, 빠른 순, 같은 id 는 처음 것, 지난 것·이상한 id·1년 넘게 먼 것·시각 없는 것 뺌",
                l != null && ids(l).equals(Arrays.asList("ctl", "a1b2c3d4", "late")), l == null ? null : ids(l));
        AlarmPlan.Alarm g = l == null ? null : l.get(1);
        check("이름·시각·반복 그대로", g != null && g.label.equals("가락시장 출발") && g.fireMs == now + 800_000 && g.repeat.equals("once"), g);
        AlarmPlan.Alarm c = l == null ? null : l.get(0);
        check("이름의 줄바꿈·탭은 빈칸 하나로, 반복 값이 이상하면 once", c != null && c.label.equals("줄 바꿈 이름") && c.repeat.equals("once"),
                c == null ? null : c.label + "|" + c.repeat);
        check("이름이 없으면 '알람', 60자까지", AlarmPlan.cleanLabel("").equals("알람") && AlarmPlan.cleanLabel(null).equals("알람")
                && AlarmPlan.cleanLabel(new String(new char[90]).replace('\0', '가')).length() == 60, "");
        check("배열만 줘도 읽음", ids(AlarmPlan.parse(new JSONArray().put(new JSONObject().put("id", "x").put("fire_ms", now + 1)).toString(), now))
                .equals(Arrays.asList("x")), "");
        check("모양이 틀리면 null (지금 예약을 건드리지 않게) — 빈 글·HTML·alarms 없음",
                AlarmPlan.parse("", now) == null && AlarmPlan.parse("<html>로그인</html>", now) == null
                        && AlarmPlan.parse("{\"items\":[]}", now) == null && AlarmPlan.parse("12", now) == null, "");
        check("알람이 없으면 빈 목록 (모두 취소)", AlarmPlan.parse("{\"alarms\":[]}", now) != null
                && AlarmPlan.parse("{\"alarms\":[]}", now).isEmpty(), "");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 45; i++) many.put(new JSONObject().put("id", "m" + i).put("fire_ms", now + 60_000L * (45 - i)));
        List<AlarmPlan.Alarm> m = AlarmPlan.parse(new JSONObject().put("alarms", many).toString(), now);
        check("30개까지 (가까운 것부터)", m.size() == 30 && m.get(0).id.equals("m44") && m.get(29).id.equals("m15"), m.size());

        System.out.println("예약 번호·취소");
        check("id 검사: 영문·숫자·_-.: 1~64자", AlarmPlan.validId("a1b2c3d4") && AlarmPlan.validId("2026-11-28:alarm_1.x")
                && !AlarmPlan.validId("") && !AlarmPlan.validId("한글") && !AlarmPlan.validId("a b") && !AlarmPlan.validId(null)
                && !AlarmPlan.validId(new String(new char[65]).replace('\0', 'a')), "");
        HashSet<Integer> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(AlarmPlan.requestCode("alarm" + i));
            codes.add(AlarmPlan.snoozeCode("alarm" + i));
        }
        check("같은 id 는 늘 같은 번호, 원래 알람과 '5분 뒤'는 다른 번호 (200개 겹치지 않음)",
                AlarmPlan.requestCode("a1") == AlarmPlan.requestCode("a1") && AlarmPlan.requestCode("a1") != AlarmPlan.snoozeCode("a1")
                        && codes.size() == 400, codes.size());
        check("목록에서 빠진 id 만 취소", AlarmPlan.removed(Arrays.asList("late", "gone", "a1b2c3d4", "gone"), l)
                .equals(Arrays.asList("gone")), AlarmPlan.removed(Arrays.asList("late", "gone", "a1b2c3d4"), l));
        List<AlarmPlan.Alarm> back = AlarmPlan.fromArray(AlarmPlan.toArray(l), now);
        check("저장했다 다시 읽어도 같음 (재부팅 뒤 다시 걸기)", ids(back).equals(ids(l)) && back.get(1).label.equals("가락시장 출발"), ids(back));
        check("저장한 것 중 그사이 지난 것은 다시 걸지 않음", ids(AlarmPlan.fromArray(AlarmPlan.toArray(l), now + 900_000))
                .equals(Arrays.asList("late")), "");

        System.out.println("alarm 시험: " + ok + "개 통과, " + fail + "개 실패");
        if (fail > 0) System.exit(1);
    }
}
