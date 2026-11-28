# URL 앱에서 Google 로그인 (WebView 에서 막히는 문제)

## 왜 막히나

URL 앱은 사이트를 앱 안 웹 화면(안드로이드 WebView)에서 연다. **Google 은 WebView 안의 Google 로그인·동의 화면을
막는다** ("이 브라우저 또는 앱은 안전하지 않을 수 있습니다", `403 disallowed_useragent`). 팝업 방식(Google Identity
Services `initTokenClient`, Firebase `signInWithPopup`)도, 리다이렉트 방식도 마찬가지다. 같은 사이트를 크롬에서 열면 된다.

## 앱이 하는 일 (v6 이후의 모든 URL 앱)

1. 사이트가 `https://accounts.google.com/…`(로그인·동의 화면)으로 가려고 하면 앱은 그 화면을 **크롬(Custom Tab)** 으로 연다.
   앱 안 화면은 그대로 남아 돌아올 자리가 된다 (`MainActivity.isGoogleSignIn`, `openSignInTab`).
2. 앱은 **자기 사이트 주소(https)로 열릴 수 있다** (매니페스트의 BROWSABLE 링크, 도메인 자동 확인 없음 → 다른 링크를
   가로채지 않고, 패키지를 지정한 `intent://` 링크로만 열린다).
3. `https://<사이트>/?urlapp_return=<원래 주소>` 로 열리면 `<원래 주소>`(자기 사이트 https 만)를 앱 안 화면에서 연다.
   같은 페이지에서 `#…` 만 다르면 주소를 바꾼 뒤 다시 읽어 사이트의 시작 코드가 `#…`(로그인 결과)를 보게 한다.
   앱 화면은 하나(`singleTask`)라 로그인 전에 사이트가 `sessionStorage` 에 넣어 둔 state 가 그대로 남아 있다.

## 사이트(AI Studio)가 해야 하는 일

Google 은 로그인 결과(redirect_uri)를 **크롬**으로 돌려보낸다. 사이트가 그 크롬 화면에서 "앱으로 돌아가기"로 결과를
앱에 넘겨야 한다. AJ GA HUB(https://ajgahub.ai.studio, 앱 패키지 `app.jarvis.web.corporate_hub_2025_1067647975588_us_west1_run_app`)
에 붙여 넣을 요청 (이미 요청한 "안드로이드 앱(WebView) 대응 — 리다이렉트 OAuth" 에 덧붙이는 것):

```
[추가 2: 안드로이드 앱에서 Google 로그인 결과 돌려받기]
안드로이드 앱(WebView) 안에서는 Google 이 로그인 화면을 막아서(403 disallowed_useragent), 앱이 accounts.google.com 화면을
크롬에서 연다. 그래서 Google 이 redirect_uri(https://ajgahub.ai.studio/#access_token=…)로 돌려보내는 곳은 앱이 아니라 크롬이다.
아래를 추가해 줘. 앞서 요청한 리다이렉트 방식(redirect_uri = window.location.origin + '/')은 그대로 둬.
1) WebView 모드에서 리다이렉트 OAuth 를 시작할 때 state 를 "urlapp." 으로 시작하게 만들어 sessionStorage 에 저장해 줘
   (예: "urlapp." + 랜덤 32자). PC 의 팝업 대체 경로는 지금처럼.
2) WebView 모드의 prompt 는 'select_account' 로 해 줘 (처음 한 번은 'consent select_account'). 사용자가 한 번 누르는
   화면이 있어야 크롬이 앱으로 넘겨줄 수 있다.
3) 허브 시작 시 location.hash 에 access_token 이 있고 state 가 "urlapp." 으로 시작하는데, 지금 화면이 WebView 가 아니고
   (navigator.userAgent 에 '; wv)' 가 없음) 이 브라우저의 sessionStorage 에 그 state 가 없으면 = 앱이 크롬으로 넘긴 로그인.
   이때는 토큰을 여기서 쓰거나 저장하거나 서버로 보내지 말고 '앱으로 돌아가기' 화면만 보여 줘:
   - const back = 'intent://' + location.host + '/?urlapp_return=' + encodeURIComponent(location.href)
       + '#Intent;scheme=https;package=app.jarvis.web.corporate_hub_2025_1067647975588_us_west1_run_app;end';
     (location.href 는 #access_token… 까지 포함한 전체 주소)
   - 큰 버튼 "앱으로 돌아가기 (Google 연결 완료)" → location.href = back. 화면이 뜨면 자동으로 한 번 시도하고, 막히면 버튼.
   - 버튼을 누른 뒤에는 history.replaceState 로 이 크롬 화면의 주소에서 #… (토큰)를 지우고 "이 창은 닫아도 돼요" 표시.
4) 앱으로 돌아오면 앱이 https://ajgahub.ai.studio/#access_token=…&state=urlapp.… 을 앱 안 화면에서 다시 연다.
   그때는 WebView 의 sessionStorage 에 state 가 있으니 원래 처리(state 검증 → 저장 → 해시 지우기 → 보험 앱 탭 다시 열기 →
   AJ_AUTH_TOKEN 전송)가 그대로 돌면 된다.
5) 완료 후 Google Cloud Console 에 등록할 값(승인된 JavaScript 원본, 승인된 리디렉션 URI)을 origin 별로 알려 줘.
```

## Google Cloud Console 에 등록할 값 (허브의 OAuth 클라이언트 = 지금 GIS 가 쓰는 웹 클라이언트)

- 승인된 JavaScript 원본: `https://ajgahub.ai.studio` (PC 에서 run.app 주소도 쓰면 `https://corporate-hub-2025-1067647975588.us-west1.run.app`)
- 승인된 리디렉션 URI: `https://ajgahub.ai.studio/` (run.app 도 쓰면 `https://corporate-hub-2025-1067647975588.us-west1.run.app/`)
- 끝의 `/` 까지 정확히 같아야 한다 (`redirect_uri_mismatch`).

## 확인 순서

1. AI Studio 에 위 요청 → Publish → 자비스에게 "허브 재배포했어" (공개 코드로 `urlapp.`·`urlapp_return`·`initTokenClient` 확인).
2. AJ GA HUB 앱(v6 이상) 설치 → 보험 및 인허가 → "Google 계정 연결" → 크롬이 열려 계정 선택·동의 →
   "앱으로 돌아가기" → 앱의 보험 및 인허가 탭이 연결된 상태로 열림.
3. 안 되면 어디서 멈췄는지: 크롬이 안 열림(사이트가 아직 팝업 방식) / 크롬에 `redirect_uri_mismatch`(콘솔 등록) /
   돌아가기 버튼이 없음(3번 미적용) / 앱으로 왔는데 연결 실패(state 가 없음 = 그사이 앱이 종료됨, 다시 시도).

## 한계

- 사이트가 위 3번(돌아가기)을 넣지 않으면 로그인은 크롬에서 끝나고 앱으로 오지 않는다 (크롬에서 그 사이트를 쓰면 된다).
- Firebase `signInWithRedirect` 를 쓰는 사이트도 같은 방식의 돌아가기가 필요하다 (결과가 크롬 쪽 저장소에 남으므로).
- 크롬이 아닌 브라우저가 기본이면 Custom Tab 대신 그 브라우저가 열린다. `intent://` 를 지원하는 브라우저여야 돌아올 수 있다.
- 실제 기기에서는 아직 시험하지 못했다. 같은 페이지 다시 읽기(#… 만 다를 때)는 크로미움에서 확인했다.
