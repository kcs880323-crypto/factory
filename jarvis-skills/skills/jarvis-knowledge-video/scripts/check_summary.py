#!/usr/bin/env python3
"""hyperframes check --json 의 결과를 구역별로 요약한다.  python check_summary.py <check.json>"""
import json, sys
raw = open(sys.argv[1], encoding="utf-8").read()
d = json.loads(raw[raw.find("{"):])
print("ok:", d.get("ok"))
bad = 0
for sec in ("lint", "runtime", "layout", "motion", "contrast", "craft"):
    v = d.get(sec)
    if not isinstance(v, dict):
        continue
    print(f"== {sec} errors {v.get('errorCount')} warnings {v.get('warningCount')} info {v.get('infoCount')}")
    bad += v.get("errorCount") or 0
    for it in v.get("findings", []):
        print("  ", (it.get("severity") or "?")[:1].upper(), it.get("code"), it.get("time", ""), it.get("selector", ""), "|", (it.get("message") or "")[:170])
sys.exit(1 if bad or not d.get("ok") else 0)
