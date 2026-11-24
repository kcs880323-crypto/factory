#!/usr/bin/env python3
"""자비스 자동발행 연동용 SEO 7단 제품 블로그 생성기 (표준 라이브러리 전용).

기능:
  1. docs/products.json 의 상품 번호(--product-no) 또는 직접 JSON(--input)으로 상품 정보 로드
  2. FIX-001 진실성 가드 검사(체험 사칭·근거 없는 1위/최저가/별점·대가성 문구·가격 일치) -> 위반 시 종료 코드 1
  3. 7단 리서치형 구매 가이드 구조로 Markdown(blog_post.md), 네이버 붙여넣기용 HTML(blog_post.html),
     메타데이터(blog_meta.json) 생성
  4. docs/products.json 에서 같은 카테고리 연관 상품 2~3개를 자동 매칭해 내부 링크 클러스터 구성

사용 예:
  python generate_blog_post.py --product-no 21 \
    --problem "좁은 싱크대에서 도마와 채반을 따로 쓰기 번거로울 때" \
    --facts "다 쓰면 납작하게 접히는 접이식 구조|도마·채반·물빠짐 바구니 일체형|대용량 7L(판매자 상품명 표기)" \
    --out-dir /tmp/blog-21
"""
from __future__ import annotations

import argparse
import html
import json
import pathlib
import re
import sys
from datetime import datetime
from typing import Any

REPO_ROOT = pathlib.Path(__file__).resolve().parents[4]
PRODUCTS_JSON = REPO_ROOT / "docs" / "products.json"
RECOMMEND_SITE_URL = "https://kcs880323-crypto.github.io/factory/"

COUPANG_DISCLOSURE = (
    "이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다."
)
NAVER_DISCLOSURE = (
    "이 포스팅은 네이버 쇼핑 커넥트 활동의 일환으로, 판매 발생 시 수수료를 제공받습니다."
)

# FIX-001 금지 표현 (체험 사칭 / 근거 없는 단정·순위)
BANNED_PATTERNS: list[tuple[str, str]] = [
    (r"내돈내산", "체험 사칭 금지('내돈내산')"),
    (r"(친구|지인)\s*집", "체험 사칭 금지('친구/지인 집')"),
    (r"직접\s*써\s*(보|봤|본)", "체험 사칭 금지('직접 써봄')"),
    (r"써\s*보니", "체험 사칭 금지('써 보니')"),
    (r"따라\s*산", "체험 사칭 금지('따라 산')"),
    (r"실구매\s*인증", "근거 없는 인증 배지('실구매 인증')"),
    (r"최저가", "단정적 가격 표현('최저가' -> '오늘 확인가' 사용)"),
    (r"추천\s*1위|판매\s*1위|랭킹\s*1위|\b1위\b", "근거 없는 순위 표현('1위')"),
    (r"★|별점\s*[0-9.]+|(실구매\s*)?(후기|리뷰)\s*[0-9,]+\s*\+?", "출처 없는 별점/후기 수 표기"),
]


def fmt_won(price: int) -> str:
    return f"{price:,}원"


def fmt_md_date(ymd: str) -> str:
    m = re.match(r"^(\d{4})-(\d{2})-(\d{2})$", (ymd or "").strip())
    return f"{int(m.group(2))}/{int(m.group(3))}" if m else (ymd or datetime.now().strftime("%m/%d"))


def validate_truthfulness(text: str, platform: str, disclosure: str) -> list[str]:
    errors: list[str] = []
    head = "\n".join(text.strip().splitlines()[:4])
    if disclosure not in head:
        errors.append(f"대가성 문구가 글 상단(4줄 이내)에 없습니다 ({platform}).")
    for pat, desc in BANNED_PATTERNS:
        if re.search(pat, text):
            errors.append(f"금지 표현 감지: {desc}")
    return errors


def extract_keywords(title: str, category: str, extra_tags: list[str]) -> list[str]:
    raw_words = re.split(r"[\s,/()·\[\]+…]+", title)
    stop = {"대용량", "혼합", "세트", "특가", "추천", "인기", "무료배송", "1개"}
    tags: list[str] = []
    for w in extra_tags + [category] + raw_words:
        clean = re.sub(r"[^0-9A-Za-z가-힣]", "", w or "")
        if 2 <= len(clean) <= 14 and clean not in stop and clean not in tags:
            tags.append(clean)
        if len(tags) >= 8:
            break
    return tags


def pick_related_products(products: list[dict[str, Any]], current_no: int | None, category: str) -> list[dict[str, Any]]:
    same_cat = [p for p in products if p.get("no") != current_no and p.get("category") == category and p.get("image")]
    others = [p for p in products if p.get("no") != current_no and p.get("category") != category and p.get("image")]
    return (same_cat + others)[:3]


def build_blog_bundle(
    product: dict[str, Any],
    problem: str,
    facts: list[str],
    good_for: list[str],
    cautions: list[str],
    related: list[dict[str, Any]],
    extra_tags: list[str],
) -> dict[str, Any]:
    title_raw = str(product.get("title", "")).strip().rstrip("…")
    url = str(product.get("url", "")).strip()
    price = int(product.get("price") or 0)
    price_date = str(product.get("priceDate") or datetime.now().strftime("%Y-%m-%d"))
    basis_md = fmt_md_date(price_date)
    category = str(product.get("category") or "생활필수품")
    platform = "naver" if product.get("aff") == "naver" or "naver.me" in url else "coupang"
    disclosure = NAVER_DISCLOSURE if platform == "naver" else COUPANG_DISCLOSURE
    platform_name = "네이버 브랜드 커넥트" if platform == "naver" else "쿠팡"

    clean_facts = [f.strip() for f in facts if f.strip()]
    if not clean_facts:
        clean_facts = [f"판매자 상품명 기준 주요 구성: {title_raw}"]

    if not good_for:
        good_for = [
            f"{problem}에 해당하는 분",
            "구매 전 핵심 스펙과 가격을 빠르게 비교해 보고 싶은 분",
            "과장된 광고 문구보다 실제 구성·크기 기준으로 고르고 싶은 분",
        ]

    if not cautions:
        cautions = [
            f"표기된 가격({fmt_won(price)})은 {basis_md} 확인 기준이며, 판매처 정책에 따라 변동될 수 있습니다.",
            "상세페이지에서 정확한 치수·옵션·배송 조건을 최종 확인한 뒤 구매하세요.",
        ]

    short_hook = problem.rstrip(".?!")
    seo_title = f"[광고] {short_hook}? {title_raw} 스펙·구매 전 체크 ({basis_md} 기준)"
    if len(seo_title) > 65:
        seo_title = f"[광고] {title_raw} — 스펙·장단점·구매 전 체크 ({basis_md} 기준)"

    price_str = f"{fmt_won(price)} ({basis_md} 확인가)" if price > 0 else "상세페이지 확인"
    tags = extract_keywords(title_raw, category, extra_tags)
    hashtags = " ".join(f"#{t}" for t in ["광고", *tags])

    # 7단 SEO 마크다운 조립
    md_lines: list[str] = [
        f"# {seo_title}",
        "",
        f"> ※ {disclosure}",
        "",
        "## 1. 3줄 핵심 요약",
        f"- **이런 고민에 적합:** {problem}",
        f"- **핵심 특징:** {' / '.join(clean_facts[:3])}",
        f"- **오늘 확인가:** {price_str} ({platform_name})",
        "",
        "## 2. 한눈에 보는 기본 스펙",
        "| 항목 | 확인된 내용 |",
        "|---|---|",
        f"| 상품명 | {title_raw} |",
        f"| 카테고리 | {category} |",
        f"| 오늘 확인가 | {price_str} |",
        f"| 판매처 | {platform_name} |",
        f"| 확인된 구성·특징 | {' · '.join(clean_facts)} |",
        "",
        f"👉 **[상품 상세 스펙·현재 가격 확인하기 ({platform_name})]({url})**",
        "",
        "## 3. 구매 전 짚어볼 핵심 포인트",
    ]

    for idx, fact in enumerate(clean_facts, 1):
        md_lines.extend(
            [
                f"### 포인트 {idx}. {fact}",
                f"판매자 상품 정보에서 확인되는 핵심 요소입니다. {problem} 상황에서 실제로 필요한 기능인지 본인 사용 환경과 대조해 보세요.",
                "",
            ]
        )

    md_lines.append("## 4. 이런 분께 추천 · 구매 전 확인할 점")
    md_lines.append("**이런 분께 잘 맞아요**")
    for g in good_for:
        md_lines.append(f"- {g}")
    md_lines.append("")
    md_lines.append("**구매 전 꼭 체크할 점**")
    for c in cautions:
        md_lines.append(f"- {c}")
    md_lines.append("")

    md_lines.extend(
        [
            "## 5. 자주 묻는 질문 (FAQ)",
            f"**Q1. 지금 가격이 변동될 수 있나요?**  \n네. 본 글의 {price_str}는 확인 시점 기준이며, 판매처 할인·재고에 따라 달라질 수 있어 아래 링크에서 최종 금액을 확인해 주세요.",
            "",
            f"**Q2. 실제 구성품이나 상세 치수는 어디서 확인하나요?**  \n옵션에 따라 구성·규격이 달라질 수 있으므로 구매 전 판매자 상세페이지의 스펙 표를 꼭 확인하는 것이 안전합니다.",
            "",
            f"**Q3. 비슷한 생활템도 한 번에 비교해 볼 수 있나요?**  \n[찰스의 추천템 모음 페이지]({RECOMMEND_SITE_URL})에서 카테고리별 상품과 최근 확인가를 함께 비교하실 수 있습니다.",
            "",
        ]
    )

    if related:
        md_lines.append("## 6. 함께 비교해 볼 만한 추천템")
        for r in related:
            r_no = r.get("no")
            r_title = str(r.get("title", "")).rstrip("…")
            r_price = fmt_won(int(r.get("price") or 0)) if r.get("price") else "가격 확인"
            md_lines.append(f"- **{r_no}번 {r_title}** ({r_price}) → [추천템 페이지에서 보기]({RECOMMEND_SITE_URL}#item-{r_no})")
        md_lines.append("")

    md_lines.extend(
        [
            "---",
            f"🔗 **[지금 {title_raw} 상세페이지 보러 가기]({url})**",
            "",
            f"{hashtags}",
        ]
    )

    markdown = "\n".join(md_lines).strip() + "\n"

    # FIX-001 진실성 하드 게이트 검사
    violations = validate_truthfulness(markdown, platform, disclosure)
    if violations:
        raise ValueError("FIX-001 진실성 가드 위반으로 블로그 생성 중단:\n- " + "\n- ".join(violations))

    # 네이버 블로그 스마트에디터 붙여넣기용 깔끔한 HTML 변환
    html_parts: list[str] = [
        '<div style="font-family:\'Pretendard\',\'Malgun Gothic\',sans-serif;line-height:1.8;color:#222;max-width:740px;">',
        f'<p style="background:#f5f7fa;border-left:4px solid #13305b;padding:12px 14px;font-size:13px;color:#444;">※ {html.escape(disclosure)}</p>',
        f'<h2>1. 3줄 핵심 요약</h2><ul><li><b>이런 고민에 적합:</b> {html.escape(problem)}</li>'
        f'<li><b>핵심 특징:</b> {html.escape(" / ".join(clean_facts[:3]))}</li>'
        f'<li><b>오늘 확인가:</b> {html.escape(price_str)} ({html.escape(platform_name)})</li></ul>',
        '<h2>2. 한눈에 보는 기본 스펙</h2>',
        '<table style="width:100%;border-collapse:collapse;margin:12px 0;font-size:14px;">'
        f'<tr><th style="border:1px solid #ddd;padding:8px;background:#f8f9fb;width:28%;">상품명</th><td style="border:1px solid #ddd;padding:8px;">{html.escape(title_raw)}</td></tr>'
        f'<tr><th style="border:1px solid #ddd;padding:8px;background:#f8f9fb;">카테고리</th><td style="border:1px solid #ddd;padding:8px;">{html.escape(category)}</td></tr>'
        f'<tr><th style="border:1px solid #ddd;padding:8px;background:#f8f9fb;">오늘 확인가</th><td style="border:1px solid #ddd;padding:8px;"><b>{html.escape(price_str)}</b></td></tr>'
        f'<tr><th style="border:1px solid #ddd;padding:8px;background:#f8f9fb;">확인된 특징</th><td style="border:1px solid #ddd;padding:8px;">{html.escape(" · ".join(clean_facts))}</td></tr>'
        '</table>',
        f'<p style="margin:16px 0;"><a href="{html.escape(url)}" target="_blank" rel="sponsored nofollow noopener" '
        'style="display:inline-block;background:#13305b;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none;font-weight:700;">'
        f'상품 상세 스펙 · 현재 가격 확인하기 ({html.escape(platform_name)})</a></p>',
        '<h2>3. 구매 전 짚어볼 핵심 포인트</h2>',
    ]
    for idx, fact in enumerate(clean_facts, 1):
        html_parts.append(f'<h3>포인트 {idx}. {html.escape(fact)}</h3>')
        html_parts.append(
            f'<p>판매자 상품 정보에서 확인되는 핵심 요소입니다. {html.escape(problem)} 상황에서 실제로 필요한 구성인지 본인 사용 환경과 대조해 보세요.</p>'
        )

    html_parts.append('<h2>4. 이런 분께 추천 · 구매 전 확인할 점</h2>')
    html_parts.append('<p><b>이런 분께 잘 맞아요</b></p><ul>' + ''.join(f'<li>{html.escape(g)}</li>' for g in good_for) + '</ul>')
    html_parts.append('<p><b>구매 전 꼭 체크할 점</b></p><ul>' + ''.join(f'<li>{html.escape(c)}</li>' for c in cautions) + '</ul>')

    if related:
        html_parts.append('<h2>5. 함께 비교해 볼 만한 추천템</h2><ul>')
        for r in related:
            r_no = r.get("no")
            r_title = str(r.get("title", "")).rstrip("…")
            r_price = fmt_won(int(r.get("price") or 0)) if r.get("price") else "가격 확인"
            html_parts.append(
                f'<li><a href="{RECOMMEND_SITE_URL}#item-{r_no}" target="_blank" rel="noopener">{r_no}번 {html.escape(r_title)} ({html.escape(r_price)})</a></li>'
            )
        html_parts.append('</ul>')

    html_parts.append(
        f'<p style="margin:20px 0;"><a href="{html.escape(url)}" target="_blank" rel="sponsored nofollow noopener" '
        'style="display:inline-block;background:#ffb81c;color:#13305b;padding:12px 20px;border-radius:8px;text-decoration:none;font-weight:800;">'
        f'{html.escape(title_raw)} 상세페이지 보러 가기</a></p>'
    )
    html_parts.append(f'<p style="color:#666;font-size:13px;">{html.escape(hashtags)}</p></div>')

    return {
        "title": seo_title,
        "platform": platform,
        "product_no": product.get("no"),
        "price": price,
        "price_date": price_date,
        "tags": tags,
        "hashtags": hashtags,
        "markdown": markdown,
        "html": "\n".join(html_parts) + "\n",
    }


def main() -> None:
    ap = argparse.ArgumentParser(description="자비스 자동발행 연동용 SEO 7단 블로그 생성기")
    ap.add_argument("--product-no", type=int, help="docs/products.json 상품 번호")
    ap.add_argument("--input", help="직접 상품 JSON 파일 경로")
    ap.add_argument("--problem", required=True, help="검색 의도/문제 상황 한 줄")
    ap.add_argument("--facts", required=True, help="확인된 사실 목록 ('|' 구분)")
    ap.add_argument("--good-for", default="", help="추천 대상 ('|' 구분, 선택)")
    ap.add_argument("--cautions", default="", help="구매 전 확인 사항 ('|' 구분, 선택)")
    ap.add_argument("--tags", default="", help="추가 검색 키워드 ('|' 구분, 선택)")
    ap.add_argument("--out-dir", help="결과 저장 폴더 (미지정 시 표준출력)")
    args = ap.parse_args()

    all_products: list[dict[str, Any]] = []
    if PRODUCTS_JSON.exists():
        all_products = json.loads(PRODUCTS_JSON.read_text(encoding="utf-8")).get("products", [])

    if args.input:
        product = json.loads(pathlib.Path(args.input).read_text(encoding="utf-8"))
    elif args.product_no is not None:
        matches = [p for p in all_products if p.get("no") == args.product_no]
        if not matches:
            raise SystemExit(f"오류: docs/products.json 에서 {args.product_no}번 상품을 찾을 수 없습니다.")
        product = matches[0]
    else:
        raise SystemExit("오류: --product-no 또는 --input 중 하나를 지정해야 합니다.")

    related = pick_related_products(all_products, product.get("no"), str(product.get("category") or ""))
    bundle = build_blog_bundle(
        product=product,
        problem=args.problem,
        facts=args.facts.split("|"),
        good_for=[x for x in args.good_for.split("|") if x.strip()],
        cautions=[x for x in args.cautions.split("|") if x.strip()],
        related=related,
        extra_tags=[x for x in args.tags.split("|") if x.strip()],
    )

    if args.out_dir:
        out = pathlib.Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "blog_post.md").write_text(bundle["markdown"], encoding="utf-8")
        (out / "blog_post.html").write_text(bundle["html"], encoding="utf-8")
        meta = {k: v for k, v in bundle.items() if k not in ("markdown", "html")}
        (out / "blog_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"생성 완료: {out / 'blog_post.md'}, {out / 'blog_post.html'}, {out / 'blog_meta.json'}")
    else:
        print(bundle["markdown"])


if __name__ == "__main__":
    try:
        main()
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
