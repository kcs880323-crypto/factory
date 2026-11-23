# 자비스 연동 요청: 파트너스 매니저 · 찰스의 추천템

> 이 파일은 찰스님 PC의 **자비스 폴더에서 일하는 Claude**에게 주는 작업 지시서입니다.
> 찰스님 요청: "내 자비스에 파트너스 매니저와 나의 웹사이트 연동해줘."
> 이 파일만 읽고 일할 수 있게 위치·데이터 형식·할 일·지킬 것·완료 기준을 모두 적었습니다.
> 자비스 저장소의 CLAUDE.md 규칙(약화 금지 항목, 발행 카드, 비밀 보관 등)이 이 문서보다 우선합니다.

---

## 0. 먼저 할 일: PC 코드를 GitHub에 올리기

자비스의 「제품 릴스·상품 글」 화면(탭: 내 제휴 상품 · 인기템 · 검색 · 만든 영상 · 키·연결, "자동 추천으로 넣기 — 브랜드 커넥트 화면 통째로 붙여 넣기")은 **PC에만 있고** GitHub `kcs880323-crypto/jarvis`의 `main`에는 아직 없습니다 (2026-11-17 기준 `main` 최신 커밋 `4e5cb18`, 11-13).

1. `git status`와 `git log origin/main..HEAD`로 아직 올리지 않은 변경·커밋을 확인합니다.
2. 올릴 파일 목록을 찰스님께 먼저 보여 드립니다. `.env`, `data\`, 비밀번호·토큰·키가 든 파일은 올리지 않습니다 (.gitignore 확인).
3. 확인을 받으면 커밋해서 `main`에 올립니다.
4. 그 위에서 아래 연동 작업을 하고, 끝나면 다시 `main`에 올립니다. 클라우드 세션도 `main`을 기준으로 일하므로 PC와 GitHub가 갈라지지 않게 합니다.

---

## 1. 연동할 두 가지가 무엇인지

### 1-1. 파트너스 매니저 (쿠팡 파트너스 운영 앱)

- 코드: 공개 저장소 <https://github.com/kcs880323-crypto/factory>의 `coupang-partners-app/` (Expo / React Native). 기본 브랜치는 `claude/jarvis-skills-delivery-75yvpx`.
- 하는 일: 파트너스 링크 보관함, 홍보 글 만들기(대가성 문구를 맨 앞에 자동으로 넣음), 상품 검색·실적 조회(쿠팡 API 키가 있을 때).
- 쓰는 곳: 휴대폰 앱(Expo Go), Claude 아티팩트 페이지(링크는 Claude 계정 개인 저장소에 저장), Vercel 웹사이트(배포한 경우).
- **링크 보관함은 각 기기 안에만 있고 서버가 없습니다.** 그래서 자비스가 직접 읽을 수 없습니다. 연동은 앱의 **내보내기(CSV)**를 자비스에 붙여 넣는 방식으로 합니다.
- 앱 내보내기 위치: 앱 **설정 → 링크 보관함 내보내기 (CSV)**. 공유 창이나 클립보드로 아래 형식의 글이 나옵니다.

```csv
이름,링크,가격,채널,메모,저장일
"캡형 물티슈 100매 10팩","https://link.coupang.com/a/abc123","15900","blog01","뚜껑 있어서 안 마름","2026-11-17"
"접이식 캠핑 의자","https://link.coupang.com/a/def456","","","","2026-11-16"
```

- 열 설명: 이름(상품명) · 링크(파트너스 링크) · 가격(원, 빈칸 가능) · 채널(쿠팡 파트너스 subId, 빈칸 가능) · 메모 · 저장일(YYYY-MM-DD).
- 모든 칸은 큰따옴표로 감싸고, 안의 큰따옴표는 `""`로 이스케이프합니다 (표준 CSV).
- 예시 데이터 모드에서 저장한 링크는 `https://example.com/demo-link/…` 주소입니다. **쿠팡 링크가 아니므로 반드시 걸러 냅니다.**

### 1-2. 찰스의 추천템 (쿠팡 파트너스에 등록한 공개 웹사이트)

- 주소: **<https://kcs880323-crypto.github.io/factory/docs/>** — 쿠팡 파트너스 "웹사이트 목록"에 이 주소로 등록돼 있습니다. 네이버 블로그 `blog.naver.com/charles_travel`도 함께 등록돼 있습니다.
- 코드: factory 저장소의 `docs/index.html`. 목록 데이터는 **`docs/products.json` 한 파일**입니다.
- 배포: GitHub Pages가 기본 브랜치의 **루트(/)**에서 배포합니다. `docs/products.json`을 바꿔 커밋하면 1~2분 뒤 사이트에 반영됩니다.
- ⚠️ **Pages 폴더 설정을 바꾸거나 `docs/` 폴더를 옮기지 마세요.** 쿠팡에 등록한 주소(`…/factory/docs/`)가 깨집니다.

`docs/products.json` 형식:

```json
{
  "site": {
    "name": "찰스의 추천템",
    "tagline": "알아두면 언젠가 쓴다! 생활에 쓸모 있는 물건을 모아 둡니다.",
    "links": [{ "label": "유튜브", "url": "https://..." }]
  },
  "products": [
    {
      "title": "캡형 물티슈 100매 10팩",
      "url": "https://link.coupang.com/a/abc123",
      "category": "생활",
      "note": "뚜껑이 있어서 끝까지 마르지 않아요.",
      "price": 15900,
      "added": "2026-11-17"
    }
  ]
}
```

- `title`, `url`은 꼭 넣습니다. `category`, `note`, `price`(숫자), `priceDate`(YYYY-MM-DD), `added`(YYYY-MM-DD, 한국 시간), `hidden`(true면 숨김)은 빼도 됩니다.
- 페이지는 `url`의 호스트가 `link.coupang.com` 또는 `coupa.ng`(https)인 항목만 보여 줍니다. 그 밖의 주소는 화면에 나오지 않습니다.
- 가격은 `added`(또는 `priceDate`) 날짜 기준으로 표시되고, 페이지 맨 위에 대가성 문구가 항상 보입니다.
- 지금은 `products`가 비어 있습니다.

---

## 2. 만들 것

### A. 먼저 파악하고 요약하기 (코드 고치기 전)

1. 「제품 릴스·상품 글」 화면의 코드 위치(서버 라우트, `static/` 화면 코드)와 **'내 제휴 상품'의 데이터 구조·저장 위치**를 찾아 찰스님께 짧게 요약합니다. 저장 위치는 파일인지, 어떤 키인지 확인합니다.
2. 기존 `coupang.py`(해외마켓 핫딜 → 쿠팡 **판매자** 상품 등록 파이프라인)와 `cp_*.py`는 **판매자용**입니다. 쿠팡 **파트너스**(제휴 링크)와 섞지 않습니다.
3. 「키·연결」 탭에 쿠팡 파트너스 API 키가 이미 있는지 확인합니다.

### B. 연동 ① 파트너스 매니저 → 자비스 '내 제휴 상품'

'내 제휴 상품' 탭에 **"파트너스 매니저에서 가져오기"**를 추가합니다.

- 붙여넣기 칸: 앱이 내보낸 CSV 글을 붙여 넣으면 읽습니다. `.csv` 파일 올리기도 받으면 좋습니다.
- 읽기 규칙:
  - 첫 줄 머리글(`이름,링크,가격,채널,메모,저장일`)을 확인하고, 표준 CSV로 읽습니다 (파이썬 `csv` 모듈).
  - 링크 호스트가 `link.coupang.com` / `coupa.ng`(https)인 줄만 받습니다. 나머지는 몇 줄을 왜 건넜는지 알려 줍니다 (예: 예시 링크 2개, 일반 쿠팡 주소 1개).
  - 이미 있는 상품과 **링크가 같으면 새로 넣지 않습니다.** 메모·가격이 비어 있던 것만 채웁니다.
  - 가격은 숫자로(쉼표 제거), 비어 있으면 없음. 채널(subId)·메모·저장일은 그대로 보관합니다.
  - 출처를 '파트너스 매니저'로 표시해서 브랜드 커넥트 등 다른 출처와 구분합니다.
- 가져온 상품도 기존 흐름(릴스·블로그 글 만들기, 발행 카드)을 그대로 탑니다.

### C. 연동 ② 자비스 → 찰스의 추천템 웹사이트

'내 제휴 상품'의 쿠팡 파트너스 상품마다 **"추천 페이지에 올리기"** 버튼을 답니다. 이미 올린 상품은 **"추천 페이지에서 빼기"**가 됩니다.

- 공개 웹사이트에 올리는 일이므로, **올리기 전에 반드시 찰스님 승인을 받습니다.** 자비스의 발행 카드 방식을 따르고, 카드에는 들어갈 이름·한 줄 소개·가격·링크를 보여 줍니다.
- 올리는 방법: GitHub Contents API로 factory 저장소의 `docs/products.json`을 고칩니다. 별도로 저장소를 받아 둘(clone) 필요가 없습니다.
  1. `GET https://api.github.com/repos/kcs880323-crypto/factory/contents/docs/products.json?ref=claude/jarvis-skills-delivery-75yvpx` → `content`(base64)와 `sha`.
  2. JSON을 읽어 `products`에 넣거나 뺍니다. 같은 `url`이 있으면 새로 넣지 않고 고칩니다.
  3. `PUT` 같은 주소에 `{"message": "추천 페이지: <상품명> 올리기", "content": <base64>, "sha": <1의 sha>, "branch": "claude/jarvis-skills-delivery-75yvpx"}`.
  4. 409·422(sha 충돌)이면 1부터 한 번만 다시 합니다.
  5. 끝나면 "1~2분 뒤 반영돼요: https://kcs880323-crypto.github.io/factory/docs/"라고 알립니다.
- JSON은 UTF-8, 2칸 들여쓰기, 한글 그대로(`ensure_ascii=False`), 끝에 줄바꿈 하나. `site` 부분은 건드리지 않습니다.
- 넣는 값:
  - `title`: 상품명.
  - `url`: 파트너스 링크만. 검사해서 아니면 올리지 않습니다.
  - `category`: 있으면.
  - `note`: 찰스님이 쓴 소개나 사실만 (아래 '지킬 것' 참고).
  - `price`: 숫자.
  - `added`: 오늘(한국 시간, YYYY-MM-DD).
- 토큰:
  - GitHub **fine-grained 토큰**을 쓰고, 저장소는 `kcs880323-crypto/factory` 하나, 권한은 **Contents: Read and write**만 줍니다.
  - 토큰은 찰스님이 직접 만들어 자비스 `.env`에 넣습니다 (예: `FACTORY_GITHUB_TOKEN`).
  - 토큰을 채팅·로그·저장소에 쓰지 않습니다. 없으면 버튼에서 만드는 방법을 안내합니다 (GitHub → Settings → Developer settings → Fine-grained tokens).
- 시험: 네트워크 없이 가짜 GitHub API 서버로 넣기, 빼기, 중복, sha 충돌 재시도, 파트너스 링크 아닌 것 거절, 토큰 없음 안내를 확인합니다 (`tests/`, 기존 `run_all.py` 방식).

### D. 연동 ③ 글·릴스에 추천 페이지 주소 넣기 (작게)

- 인스타그램 릴스·캡션은 링크가 눌리지 않습니다. "구매 링크는 프로필 링크에서"라고 쓸 때 프로필 링크로 이 추천 페이지를 쓰도록, 캡션 끝 안내문을 바꿀 수 있게 합니다. 찰스님이 인스타 프로필 링크를 이 주소로 바꿨는지 먼저 여쭤봅니다.
- 블로그 상품 글에는 지금처럼 상품별 파트너스 링크를 씁니다. 추천 페이지 주소는 넣지 않아도 됩니다.

### E. 쿠팡 파트너스 API를 직접 부를 때 (이미 있다면 확인만)

파트너스 매니저와 같은 규칙을 씁니다. 참고 코드: factory `coupang-partners-app/src/api/signature.ts`, `client.ts`, `src/lib/rateGuard.ts`.

- 주소: `https://api-gateway.coupang.com/v2/providers/affiliate_open_api/apis/openapi/v1` + `/products/search`, `/products/goldbox`, `/products/bestcategories/{id}`, `/products/coupangPL`, `POST /deeplink`, `/reports/clicks|orders|cancels|commission`.
- 서명: `Authorization: CEA algorithm=HmacSHA256, access-key=…, signed-date=yyMMddTHHmmssZ(UTC), signature=hex(HMAC-SHA256(secret, signed-date + 메서드 + 경로 + 쿼리('?' 빼고)))`.
- 응답이 200이어도 `rCode`가 `"0"`이 아니면 실패입니다. 예: 판매 종료 상품의 딥링크 → `rCode 400 "url convert failed"`.
- 오류 코드: 401은 키 오류나 PC 시계 어긋남, 403은 API 미승인이나 일시 정지, 429는 호출 초과입니다.
- **최근 1시간 호출이 30회에 닿으면 부르지 않습니다.** 짧은 시간에 몰리면 계정이 24시간 정지되고, 반복되면 영구 정지된다고 알려져 있습니다. 같은 조회는 1시간 저장해서 다시 씁니다.
- API 키는 쿠팡 파트너스 **최종 승인 뒤** 받을 수 있다고 안내돼 있습니다. 찰스님은 2026-11-17에 가입했으므로 아직 키가 없을 수 있습니다. 키가 없어도 B·C·D는 모두 동작해야 합니다.

---

## 3. 지킬 것 (약화 금지)

1. **대가성 문구**: 쿠팡 파트너스 링크가 들어간 모든 글·캡션의 **맨 앞(첫 부분)**에 넣습니다 (공정위 추천·보증 심사지침, 2024-12-01 시행).
   > 이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.
2. **써 본 척 금지**: 추천 페이지의 `note`와 글에는 찰스님이 직접 쓴 소개나 확인된 사실만 넣습니다. 지어낸 후기·별점은 넣지 않습니다. 비어 있으면 비워 둡니다.
3. **공개는 승인 뒤에만**: 추천 페이지에 올리기, 글·릴스 발행 모두 발행 카드에서 찰스님이 허용해야 합니다.
4. **비밀 보관**: GitHub 토큰과 쿠팡 키는 `.env`에만 둡니다. 채팅·로그·커밋·화면에 값을 보이지 않습니다.
5. **등록한 주소 유지**: factory 저장소의 `docs/` 위치와 GitHub Pages 설정을 바꾸지 않습니다.
6. **파트너스 링크만**: 일반 쿠팡 주소(`coupang.com/vp/products/…`)는 수익이 잡히지 않으므로 "파트너스 링크로 바꿔야 해요"라고 안내하고 올리지 않습니다.
7. **판매자용과 분리**: 쿠팡 판매자(Wing) 파이프라인 `coupang.py`와 파트너스 기능을 섞지 않습니다.

---

## 4. 끝났다고 말하기 전에

- [ ] 0번: PC 코드가 GitHub `main`에 올라갔다 (`.env`·`data` 제외 확인).
- [ ] B: 앱 CSV 예시(위 1-1의 두 줄 + 예시 링크 한 줄)를 붙여 넣으면 2개가 들어가고 1개는 이유와 함께 건너뛴다. 같은 CSV를 다시 넣어도 중복이 생기지 않는다.
- [ ] C: 가짜 GitHub 서버 시험이 통과한다. 실제로는 찰스님 승인을 받아 상품 하나를 올리고, 1~2분 뒤 <https://kcs880323-crypto.github.io/factory/docs/>에 보이는지 확인한다. 빼기도 확인한다.
- [ ] 대가성 문구가 글 맨 앞에 있는지 시험이 확인한다.
- [ ] 자비스 CLAUDE.md에 이 기능 절(무엇·어디·약화 금지·시험·미검증)을 기존 형식대로 추가한다.
- [ ] 변경을 `main`에 올리고, 찰스님께 사용법을 짧게 알린다: 어디를 누르면 무엇이 되는지, 토큰 만드는 법, 확인한 것, 확인하지 못한 것.

---

## 5. 참고 링크

- factory 저장소 (공개): <https://github.com/kcs880323-crypto/factory>
  - 파트너스 매니저 설명서: `coupang-partners-app/README.md`
  - 추천 페이지: `docs/index.html`, `docs/products.json`
- 파트너스 매니저 웹 (Claude 아티팩트, 찰스님 본인만 열 수 있음): <https://claude.ai/artifact/VnNDvs78rQ5umauu2Ggoqh>
- 쿠팡 파트너스: <https://partners.coupang.com/>
