# 우리집 가계부: AI Studio 에 붙여 넣을 요청 (앱 연동 유지)

AI Studio 에서 우리집 가계부(csjhth.ai.studio)를 고치고 같은 주소로 재배포해도 안드로이드 앱의 **카드 자동 등록**과
**홈 화면 위젯**이 계속 연결되도록, 사이트에 "연동 약속"을 넣는 요청입니다. 아래 상자 안의 글을 AI Studio 채팅에
그대로 붙여 넣고, 바뀐 것을 확인한 뒤 재배포하세요. 재배포 후 자비스에게 "가계부 재배포했어"라고 하면
`url_app.py link-check https://csjhth.ai.studio/` 로 점검합니다 (사이트 코드와 /urlapp.json 만 보고 가계 데이터는 받지 않음).

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
   - async 함수이고, 로그인 전(sessionStorage 의 isAuthenticated 가 "true" 가 아님)에는 null 을 돌려줘.
   - 로그인 후에는 대시보드와 똑같은 계산으로 '이번 달, 전체 사용자'의 수입, 지출(고정+변동), 저축, 수입−지출을 구해서
     아래 모양으로 돌려줘. 금액은 "1,234,000원" 형식, 음수는 앞에 "-".
     { "title": "11월 우리집 가계부",
       "rows": [ {"label": "수입", "value": "5,000,000원", "tone": "pos"},
                 {"label": "지출", "value": "2,000,000원", "tone": "neg"},
                 {"label": "저축", "value": "1,000,000원", "tone": ""},
                 {"label": "수입 − 지출", "value": "3,000,000원", "tone": "pos 또는 음수면 neg"} ] }
   - 대시보드의 계산이나 데이터 API 가 바뀌면 이 함수도 같이 바꿔 줘.
```

- 1)은 카드 자동 등록용, 2)는 위젯용입니다. 둘 다 없어도 앱은 지금처럼 동작합니다 (앱에 넣어 둔 기본 설정·위젯 스크립트 사용).
  있으면 사이트를 고쳐도 앱 업데이트 없이 따라갑니다.
- 앱은 /urlapp.json 을 6시간마다, 그리고 가계부가 등록을 거절할 때 바로 다시 읽습니다. 그래도 거절되면
  "가계부와 연결이 끊겼어요" 알림을 띄우고 결제는 모아 두었다가 연결되면 보냅니다.
- 보안 참고: 지금 가계부 API 는 서버에 잠금이 없습니다. 이 요청은 그 상태를 바꾸지 않습니다. 잠금을 넣으려면 따로 요청하세요
  (앱에 키를 넣는 작업이 함께 필요합니다).
