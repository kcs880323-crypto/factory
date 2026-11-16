// 우리집 가계부(Smart Ledger, csjhth.ai.studio) 위젯: 이번 달 수입·지출·저축과 (수입 − 지출).
// 사이트 대시보드와 같은 요청(/api/summary, 전체 사용자)과 같은 계산(총 지출 = 고정 + 변동)을 쓴다.
// 사이트에서 비밀번호를 입력한 뒤에만 읽는다 (사이트가 sessionStorage 의 isAuthenticated 로 로그인을 표시).
// url_app.py build … --widget widgets/smart-ledger.js
async function () {
  if (sessionStorage.getItem("isAuthenticated") !== "true") return null;
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth() + 1;
  const p2 = (n) => String(n).padStart(2, "0");
  const last = new Date(y, m, 0).getDate();
  const r = await fetch(`/api/summary?startDate=${y}-${p2(m)}-01&endDate=${y}-${p2(m)}-${p2(last)}&earnerId=all`);
  if (!r.ok) return null;
  const s = await r.json();
  const num = (v) => (typeof v === "number" ? v : parseFloat(v)) || 0;
  const income = num(s.income);
  const spend = num(s.fixed) + num(s.variable);
  const saving = num(s.savings);
  const left = income - spend;
  const won = (n) => (n < 0 ? "-" : "") + Math.round(Math.abs(n)).toLocaleString("ko-KR") + "원";
  return {
    title: `${m}월 우리집 가계부`,
    rows: [
      { label: "수입", value: won(income), tone: "pos" },
      { label: "지출", value: won(spend), tone: "neg" },
      { label: "저축", value: won(saving) },
      { label: "수입 − 지출", value: won(left), tone: left >= 0 ? "pos" : "neg" },
    ],
  };
}
