#!/usr/bin/env python3
"""본편 clauses.json 앞뒤에 찰스의 고정 오프닝·엔딩을 붙여 전체 clauses.json 을 만든다.

  python make_clauses.py <본편 clauses.json> <출력 clauses.json>

본편은 "line" 1~N 의 절만 쓴다 (id 는 op*, en* 를 쓰지 않는다). 오프닝/엔딩 문구는 고정이다:
  오프닝 "30초만 주면, 지식 하나 드릴게요. 찰스입니다!"
  엔딩   "알아두면 언젠가 쓴다! 오늘의 지식공유가 유익했다면 좋아요와 구독, 찰스였습니다!"
"""
import json, pathlib, sys

ref = pathlib.Path(__file__).resolve().parent.parent / "reference" / "charles"
body = json.load(open(sys.argv[1], encoding="utf-8"))
for c in body:
    if c["id"].startswith(("op", "en")) or not 1 <= c["line"] <= 98:
        sys.exit(f"본편 절 id/line 이 겹친다: {c['id']} (id 는 op/en 으로 시작하지 않고, line 은 1~98)")
    c.setdefault("part", "body")
body[0].setdefault("gap_before", 1.0)   # 오프닝의 "찰스입니다!" 화면이 충분히 머물도록 본편 앞에 간격을 더 둔다
full = json.load(open(ref / "opening.json", encoding="utf-8")) + body + json.load(open(ref / "ending.json", encoding="utf-8"))
json.dump(full, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"절 {len(full)}개 (오프닝 2 + 본편 {len(body)} + 엔딩 5) → {sys.argv[2]}")
