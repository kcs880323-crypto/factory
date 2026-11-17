// 우리집 가계부(Smart Ledger, csjhth.ai.studio) 위젯: 이번 달 수입·지출·저축·(수입 − 지출) + 사람별 카드 사용 합계.
// 위 4줄: 사이트가 window.urlappWidget 을 주면 그 요약을 그대로 쓰고(사이트를 고쳐도 따라감), 없으면 아래 계산
//         (대시보드와 같은 요청 /api/summary, 전체 사용자, 총 지출 = 고정 + 변동).
// 아래 2줄: 이번 달 상세 변동지출 중 메모에 "카드"가 있는 것(카드 자동 등록이 카드사 이름을 메모에 넣고, 사이트의
//         엑셀 양식도 메모에 "현대카드"·"현금"을 적는다)을 담당자(창수·지현)별로 더한 값. 주소와 칸 이름은 사이트의
//         /urlapp.json(카드 연동 약속)을 따르고, 없으면 지금 사이트의 이름을 쓴다.
// 사이트에서 비밀번호를 입력한 뒤에만 읽는다 (사이트가 sessionStorage 의 isAuthenticated 로 로그인을 표시).
// url_app.py build … --widget widgets/smart-ledger.js
// urlapp:rows 6
async function (site) {
  if (sessionStorage.getItem("isAuthenticated") !== "true") return null;
  const OWNERS = ["창수", "지현"];
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth() + 1;
  const p2 = (n) => String(n).padStart(2, "0");
  const num = (v) => (typeof v === "number" ? v : parseFloat(String(v == null ? "" : v).replace(/,/g, ""))) || 0;
  const won = (n) => (n < 0 ? "-" : "") + Math.round(Math.abs(n)).toLocaleString("ko-KR") + "원";

  async function ownSummary() {
    const last = new Date(y, m, 0).getDate();
    const r = await fetch(`/api/summary?startDate=${y}-${p2(m)}-01&endDate=${y}-${p2(m)}-${p2(last)}&earnerId=all`);
    if (!r.ok) return null;
    const s = await r.json();
    const income = num(s.income);
    const spend = num(s.fixed) + num(s.variable);
    const left = income - spend;
    return {
      title: `${m}월 우리집 가계부`,
      rows: [
        { label: "수입", value: won(income), tone: "pos" },
        { label: "지출", value: won(spend), tone: "neg" },
        { label: "저축", value: won(num(s.savings)) },
        { label: "수입 − 지출", value: won(left), tone: left >= 0 ? "pos" : "neg" },
      ],
    };
  }

  async function cardRows() {
    let path = "/api/monthly-details?month={month}";
    let f = {};
    try {
      const r = await fetch("/urlapp.json", { cache: "no-store" });
      const link = r.ok ? await r.json() : null;
      const h = link && link.cards && link.cards.history;
      if (h && typeof h.path === "string" && h.path.startsWith("/") && !h.path.startsWith("//")) path = h.path;
      if (h && h.fields && typeof h.fields === "object") f = h.fields;
    } catch (e) { /* 연동 파일이 없으면 기본 이름 */ }
    const key = (name, def) => (typeof f[name] === "string" && f[name] ? f[name] : def);
    const kOwner = key("owner", "earner_name"), kMemo = key("memo", "memo"), kAmount = key("amount", "amount");
    const kId = key("id", "id"), kTable = key("table", "source_table");
    const r = await fetch(path.replace("{month}", `${y}-${p2(m)}`));
    if (!r.ok) return null;
    const data = await r.json();
    const rows = [];
    const seen = new Set();
    (function collect(o, depth) {
      if (!o || depth > 6) return;
      if (Array.isArray(o)) { o.forEach((x) => collect(x, depth + 1)); return; }
      if (typeof o !== "object") return;
      if (kAmount in o && kOwner in o) {
        const id = o[kId] != null ? `${o[kTable] || ""}#${o[kId]}` : null;
        if (id && seen.has(id)) return;
        if (id) seen.add(id);
        rows.push(o);
        return;
      }
      Object.keys(o).forEach((k) => collect(o[k], depth + 1));
    })(data, 0);
    const sum = {};
    OWNERS.forEach((n) => { sum[n] = 0; });
    rows.forEach((o) => {
      const owner = String(o[kOwner] == null ? "" : o[kOwner]).trim();
      if (!(owner in sum) || !String(o[kMemo] == null ? "" : o[kMemo]).includes("카드")) return;
      sum[owner] += num(o[kAmount]);
    });
    return OWNERS.map((n) => ({ label: `${n} 카드`, value: won(sum[n]) }));
  }

  let base = null;
  if (typeof site === "function") {
    try { base = await site(); } catch (e) { base = null; }
  }
  if (!base || typeof base !== "object") base = await ownSummary();
  if (!base) return null;
  let cards = null;
  try { cards = await cardRows(); } catch (e) { cards = null; }
  const top = Array.isArray(base.rows) ? base.rows.slice(0, 4) : [];
  return { title: base.title, note: base.note, rows: cards ? top.concat(cards) : top };
}
