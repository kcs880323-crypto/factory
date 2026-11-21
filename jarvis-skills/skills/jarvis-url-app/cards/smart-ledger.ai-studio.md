# 우리집 가계부: AI Studio 에 붙여 넣을 요청 (앱 연동 유지 + 보안 강화)

우리집 가계부(csjhth.ai.studio)를 AI Studio 에서 고칠 때 붙여 넣는 요청 두 가지입니다.
**가. 연동 유지**는 사이트를 고쳐 재배포해도 안드로이드 앱의 카드 자동 등록·홈 화면 위젯이 계속 연결되게 하고,
**나. 보안 강화**는 지금 드러나 있는 비밀번호·Gemini 키·잠기지 않은 데이터를 고칩니다.
한 번에 하나씩 붙여 넣고, 바뀐 것을 확인한 뒤 재배포하세요. 재배포 후 자비스에게 "가계부 재배포했어"라고 하면
`url_app.py link-check https://csjhth.ai.studio/` 로 점검합니다 (사이트 코드와 /urlapp.json 만 보고 가계 데이터는 받지 않음).

## 순서 (끊기지 않게)

1. 두 폰의 우리집 가계부 앱을 **v4 이상**으로 업데이트 (앱 연동 키를 넣을 수 있는 버전).
2. AI Studio 에 **가. 연동 유지** 요청 → 재배포 → "가계부 재배포했어".
3. AI Studio 에 **나. 보안 강화** 요청 → 재배포.
4. 사이트에 로그인 → 설정의 **앱 연동 키**에서 "창수 폰", "지현 폰" 키를 하나씩 만들어 → 각 폰 앱의
   카드 자동 등록 설정 → "가계부 앱 연동 키"에 붙여 넣고 저장 (키는 만들 때 한 번만 보여요).
5. **비밀번호를 새로 정하고**, Google AI Studio 의 API 키 화면에서 **Gemini API 키를 새로 만들고 옛 키를 삭제**.
   옛 비밀번호와 옛 Gemini 키는 지금 사이트 코드에 그대로 보여서 이미 누가 봤을 수 있어요.
6. 자비스에게 "가계부 재배포했어" → 연동 점검.

## 가. 연동 유지

```text
기존 화면·기능·데이터·API 는 그대로 두고, 안드로이드 앱과의 연동을 위해 아래 두 가지만 추가해 줘.

1) 사이트 주소 뒤에 /urlapp.json 으로 접속하면 아래 JSON 이 그대로 보이게 해 줘.
   - public 폴더의 정적 파일(public/urlapp.json)로 두거나, 서버에 GET /urlapp.json 경로를 만들어도 돼.
   - Content-Type 은 application/json, 로그인 없이 읽을 수 있어야 해 (비밀 값은 들어 있지 않아).
   - 앞으로 지출 추가(/api/monthly-details/add-single), 월별 내역(/api/monthly-details?month=…),
     내역 삭제(/api/monthly-details/delete-single) API 의 주소나 칸 이름
     (date, type, category_large, category_medium, details, amount, earner_name, memo, id, source_table)을
     바꾸게 되면, 이 urlapp.json 도 같이 바꿔 줘. 이 규칙을 해당 API 코드 맨 위에 주석으로 남겨 줘.

{
  "_comment": "우리집 가계부 앱(안드로이드)과의 연동 약속. 지출 추가·월별 내역·삭제 API 의 주소나 칸 이름을 바꾸면 이 파일도 같이 바꿔 주세요. 앱은 이 파일을 읽어 따라갑니다.",
  "version": 1,
  "cards": {
    "add": {
      "method": "POST",
      "path": "/api/monthly-details/add-single",
      "body": {
        "date": "{date}",
        "type": "변동지출",
        "category_large": "{large}",
        "category_medium": "{medium}",
        "details": "{merchant}",
        "amount": "{amount}",
        "earner_name": "{owner}",
        "memo": "{card}"
      }
    },
    "history": {
      "path": "/api/monthly-details?month={month}",
      "fields": {
        "details": "details",
        "large": "category_large",
        "medium": "category_medium",
        "amount": "amount",
        "date": "date",
        "memo": "memo",
        "owner": "earner_name",
        "id": "id",
        "table": "source_table"
      }
    },
    "delete": {
      "method": "DELETE",
      "path": "/api/monthly-details/delete-single",
      "body": {
        "id": "{id}",
        "source_table": "{table}"
      }
    },
    "default_category": [
      "기타",
      "그 외"
    ]
  }
}


2) 프론트엔드에 전역 함수 window.urlappWidget 을 만들어 줘 (앱의 홈 화면 위젯이 부른다).
   - async 함수이고, 로그인 전에는 null 을 돌려줘.
   - 로그인 후에는 대시보드와 똑같은 계산으로 '이번 달, 전체 사용자'의 수입, 지출(고정+변동), 저축, 수입−지출을 구해서
     아래 모양으로 돌려줘. 금액은 "1,234,000원" 형식, 음수는 앞에 "-".
     { "title": "11월 우리집 가계부",
       "rows": [ {"label": "수입", "value": "5,000,000원", "tone": "pos"},
                 {"label": "지출", "value": "2,000,000원", "tone": "neg"},
                 {"label": "저축", "value": "1,000,000원", "tone": ""},
                 {"label": "수입 − 지출", "value": "3,000,000원", "tone": "pos 또는 음수면 neg"} ] }
   - 대시보드의 계산이나 데이터 API 가 바뀌면 이 함수도 같이 바꿔 줘.
```

## 나. 보안 강화

```text
보안을 강화해 줘. 화면 모양과 기존 데이터는 그대로 두고, 아래를 모두 지켜 줘.

1) 로그인을 서버에서 확인
   - 프론트엔드 코드에서 비밀번호 문자열과 비밀번호 비교 코드를 완전히 지워 줘 (지금은 브라우저 코드에 비밀번호가 보인다).
   - 서버에 POST /api/login 을 만들어: 받은 비밀번호를 서버에만 있는 값과 비교해. 비밀번호는 코드에 쓰지 말고
     서버 환경 변수(APP_PASSWORD)나, 처음 한 번 설정 화면에서 정한 값을 DB 에 해시(scrypt 또는 bcrypt)로만 저장해 줘.
   - 맞으면 무작위 세션 토큰을 만들어 HttpOnly, Secure, SameSite=Lax 쿠키로 보내고(유지 30일), 서버에는 토큰의 해시만 저장해.
     POST /api/logout 은 그 세션을 지워. GET /api/session 은 로그인 여부만 알려 줘.
   - 로그인 시도 제한: 같은 IP 에서 10분 안에 5번 틀리면 15분 동안 막아.
   - 화면은 시작할 때 GET /api/session 으로 로그인 여부를 확인해. 기존 sessionStorage 의 isAuthenticated 는
     로그인에 성공하면 계속 "true" 로 두고 로그아웃하면 지워 줘 (앱 위젯이 로그인 여부를 볼 때 쓴다). 실제 허가는 서버만 한다.

2) 모든 데이터 API 잠그기
   - /api/ 로 시작하는 모든 요청은 로그인 세션(쿠키)이 있어야만 처리하고, 없으면 401 과 {"success": false, "error": "unauthorized"}.
   - 예외(로그인 없이 허용): GET /urlapp.json, GET /api/health, POST /api/login, GET /api/session.
   - 백업 내보내기·가져오기, AI 분석, 엑셀 업로드 API 도 꼭 잠가 줘.

3) 안드로이드 앱용 '앱 연동 키'
   - 앱의 카드 자동 등록은 화면 밖에서 요청해서 쿠키가 없다. 그래서 X-App-Key 헤더로 들어오는 '앱 연동 키'도 받아 줘.
   - 로그인한 설정 화면에 '앱 연동 키' 관리: 이름(예: 창수 폰, 지현 폰)을 넣고 [만들기]를 누르면 32자 이상의 무작위 키를
     한 번만 보여 주고, 서버에는 해시(SHA-256)만 저장해. 목록에서 이름·만든 날짜·마지막 사용을 보고 지울 수 있게.
   - 서버는 X-App-Key 헤더 값을 해시해서 저장된 키와 비교해. 앱 연동 키로 허용하는 API 는 이 셋만:
     GET /api/monthly-details, POST /api/monthly-details/add-single, DELETE /api/monthly-details/delete-single.
   - /urlapp.json 의 "cards" 안에 "auth": {"header": "X-App-Key"} 를 추가해 줘. /urlapp.json 은 계속 로그인 없이 읽혀야 해.
   - 앱 연동 키(해시)는 가계부 데이터와 같은 영구 저장소(DB)에 저장해 줘. 서버 메모리·컨테이너 안 파일에 두면 서버가
     다시 시작되거나 재배포될 때마다 키가 사라진다. 키는 만료 없이, 사용자가 목록에서 지울 때만 없어져야 해.

4) Gemini API 키를 브라우저에서 빼기
   - 프론트엔드 코드에 들어 있는 Gemini API 키 문자열을 모두 지워 줘 (지금은 누구나 볼 수 있다).
   - AI 분석·AI 관련 호출은 서버 API 로 옮기고, 서버는 환경 변수 GEMINI_API_KEY 로 Gemini 를 불러. 이 API 도 로그인 필요.

5) 그 밖에
   - 입력값 검사: 금액은 숫자(0 이상, 너무 큰 값 거부), 날짜는 YYYY-MM-DD, 글자 칸은 길이 제한(예: 200자).
   - 오류 응답에 내부 정보(스택, SQL, 파일 경로)를 넣지 마.
   - 다른 사이트에서 이 API 를 부르지 못하게 CORS 는 같은 사이트만 허용해.
   - 보안 헤더(X-Content-Type-Options: nosniff, Referrer-Policy, Content-Security-Policy 의 frame-ancestors)를 붙여 줘.
     frame-ancestors 는 'self' https://aistudio.google.com https://*.aistudio.google.com https://*.google.com
     https://*.googleusercontent.com 으로 해 줘 (AI Studio 미리보기가 사이트를 틀 안에 띄우므로, 'self' 만 두면 미리보기가 막힌다).
   - 바꾼 뒤, 로그인 안 한 상태에서 /api/monthly-details?month=2026-11 을 열면 401 이 나오는지 확인해 줘.
```

## 다. 앱 연동 키·로그인 유지 (키가 자꾸 사라질 때)

앱에는 키가 그대로 있는데 "가계부가 앱 연동 키를 받지 않았어요"가 뜨고, 사이트의 '앱 연동 키 · 보안' 목록에서
폰 키가 없어졌다면 사이트 서버가 키를 잃어버린 것이다. AI Studio 에 아래를 그대로 붙여 넣는다.

```
앱 연동 키가 자꾸 사라진다 (목록에서 없어지고, 안드로이드 앱이 401 을 받는다). 아래처럼 고쳐 줘. 다른 기능·보안 설정은 그대로 둬.
1) 앱 연동 키 저장 위치를 확인해서 알려 주고, 서버 메모리(Map 등)나 컨테이너 안 파일이면 가계부 데이터와 같은 영구 DB 로 옮겨 줘.
   서버 재시작·재배포·인스턴스가 바뀌어도 키 목록(이름, SHA-256 해시, 만든 날짜, 마지막 사용)이 그대로 남아야 해.
2) 키는 만료되지 않게. 사용자가 목록에서 '키 삭제'를 누를 때만 없어져야 해. 서버가 켜질 때 키를 지우거나 새로 만들지 마.
3) 로그인 세션도 서버 메모리에만 두지 말고, 서버가 다시 시작돼도 유지되게 (DB 에 저장하는 세션, 또는 서버 비밀값으로
   서명한 쿠키). 세션 유지 기간은 30일, HttpOnly·Secure·SameSite=Lax 쿠키로. 비밀번호를 바꾸면 기존 세션은 끊어 줘.
4) X-App-Key 가 틀리면 지금처럼 401 을 주되, 응답 본문의 error 를 "unknown_app_key"(없는 키)로 구분해 줘.
5) 바꾼 뒤, 서버를 한 번 다시 시작(재배포)해도 키 목록과 로그인이 그대로인지 확인해 줘.
```

고친 뒤 오른쪽 위 **Publish**. 앞으로는 재배포해도 폰의 키를 다시 넣을 필요가 없어야 한다.

## 라. 폰 상태·활동 기록 보기 (설치된 앱이 잘 도는지 한곳에서)

우리집 가계부 앱(v13 이상)은 사이트가 받을 곳을 알려 주면 6시간마다(그리고 앱을 열 때 30분에 한 번) 폰 상태와
최근 활동 기록을 보낸다. 사이트의 '앱 연동 키 · 보안' 화면에서 폰별로(창수 폰·지현 폰) 정상 작동 여부를 볼 수 있다.
앱이 보내는 내용(JSON): {"type":"urlapp_card_status","version":1,"status":{ok, problems[], notes[], owner, access,
pending, pending_error, last_card_at, last_registered_at, key_ok_at, app_version, android, device, now …},
"journal":[{t, k, m} 최근 30개]} — 알림 원문·키 값은 없다 (기록에는 등록한 가맹점·금액이 있다).

```
안드로이드 앱이 폰 상태를 보내는 곳과, 그걸 보는 화면을 추가해 줘. 다른 기능·보안 설정은 그대로 둬.
1) POST /api/app-status : X-App-Key(앱 연동 키)로만 받는다. 키가 틀리면 401. 본문 JSON 은 20KB 까지, type 이
   "urlapp_card_status" 가 아니면 400. 받은 시각과 본문을 그 키(이름: 창수 폰 등)에 묶어 영구 DB 에 저장해 줘.
   키마다 최신 1개 + 지난 30개(받은 시각, status.ok, problems)만 남기고 오래된 것은 지워.
2) GET /api/app-status : 로그인한 사람만. 키마다 {키 이름, 받은 시각, status, journal, 지난 기록 요약} 목록.
3) '앱 연동 키 · 보안' 화면의 키 목록에 키마다 표시해 줘:
   - 상태: status.ok 면 초록 "정상", 아니면 빨강 "확인 필요" + problems 를 줄마다.
     마지막으로 받은 시각이 24시간보다 오래되면 회색 "연락 없음 (폰이 꺼졌거나 앱이 멈췄을 수 있음)".
   - 담당자(status.owner), 앱 버전, 안드로이드 버전·기기, 알림 접근, 마지막 카드 알림(last_card_at),
     마지막 등록(last_registered_at), 못 보낸 결제(pending 건 + pending_error) — 시각은 숫자(밀리초)를 한국 시간으로.
   - [활동 기록] 펼치기: journal 을 최근 것부터 "11/19 10:35 [등록] 메가커피 1,000원 …" 처럼.
   - 지난 30번의 정상/확인 필요를 작은 점들로 (언제부터 문제였는지 보이게).
4) /urlapp.json 의 "cards" 안에 "status": {"method": "POST", "path": "/api/app-status"} 를 추가해 줘.
5) 앱 연동 키로 허용하는 API 에 POST /api/app-status 를 추가해 줘 (지금 3개 + 이것). GET 은 로그인만.
6) 바꾼 뒤, 로그인 없이 GET /api/app-status 가 401 인지 확인해 줘.
```

고친 뒤 오른쪽 위 **Publish** → 자비스에게 "가계부 재배포했어" (`link-check` 가 status 주소도 확인한다).
앱 쪽은 사이트가 받을 곳을 알려 주기 전까지 아무것도 보내지 않는다. 폰 화면의 '상태'·'활동 기록'은 사이트와 상관없이 바로 보인다.

## 참고

- **AI Studio 미리보기에 "…에서 연결을 거부했습니다"가 뜨면**: 보안 헤더 frame-ancestors 가 'self' 만 허용해서 미리보기 틀이 막힌 것.
  AI Studio 에 "frame-ancestors 를 'self' https://aistudio.google.com https://*.aistudio.google.com https://*.google.com
  https://*.googleusercontent.com 으로 바꿔 줘. 다른 보안 설정은 그대로" 라고 요청한다 (2026-11-17 실제로 겪음).
- **고친 뒤 꼭 오른쪽 위 Publish(게시)** 를 눌러야 실제 주소(csjhth.ai.studio)에 반영된다. AI Studio 의 '검증 결과'는 미리보기 서버 기준이다.
- **보안 강화 뒤 AI Studio 가 "Enter your environment variable to continue" 로 NAS_URL·APP_PASSWORD 를 물으면**: 서버가 쓰는 비밀 값이다.
  NAS_URL 은 집 NAS 주소(View changes 의 지워진 줄, 또는 톱니바퀴 → Secrets). **이 가계부는 NAS 를 쓰지 않는다**(사용자 확인, /api/health 의 isNas:false 가 정상).
  그러면 AI Studio 에 "NAS 는 안 쓴다. NAS_URL 을 필수 값에서 빼고, 없으면 NAS 동기화를 끈 채로 동작하게 해 줘. 다른 보안 설정은 그대로" 라고 요청한다.
  APP_PASSWORD 는 사용자가 새로 정하는 가족 로그인 비밀번호.
  값은 그 칸에만 넣고 대화창·저장소에 쓰지 않는다. 이 값이 없으면 서버가 켜지지 않아 미리보기가 깨진 그림으로 보인다.
- **점검(읽기만)**: 로그인 없이 /api/monthly-details·/api/summary·/api/backup/export → 401, /api/session → JSON,
  /urlapp.json 에 "auth": {"header": "X-App-Key"}, 공개 스크립트에 AIza 키 없음, 다른 출처 Origin → 403. (2026-11-17 모두 확인)

- 가. 는 카드 자동 등록·위젯용, 나. 는 보안용입니다. 가. 가 없어도 앱은 앱에 넣어 둔 기본 설정·위젯 스크립트로 동작하고,
  있으면 사이트를 고쳐도 앱 업데이트 없이 따라갑니다.
- 앱은 /urlapp.json 을 6시간마다, 그리고 가계부가 등록을 거절할 때 바로 다시 읽습니다. 계속 거절되면
  "가계부와 연결이 끊겼어요"(주소가 바뀜) 또는 "가계부 앱 연동 키를 확인해 주세요"(키가 없거나 틀림) 알림을 띄우고,
  결제는 모아 두었다가 연결되면 보냅니다.
- 나. 를 적용한 뒤 앱 연동 키를 넣기 전까지는 카드 자동 등록이 대기 상태가 됩니다 (결제는 앱 안에 모여 있다가 키를 넣으면 보내져요).
  위젯과 앱 화면은 사이트 로그인(쿠키)으로 동작합니다.
