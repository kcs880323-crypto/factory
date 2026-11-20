# app-releases — URL 앱 업데이트 배포용 브랜치

이 브랜치에는 **앱 안 업데이트**로 받는 최신 APK와 정보 파일만 둔다 (코드는 없다).
URL 앱(`jarvis-skills/skills/jarvis-url-app`)을 `--update-url https://raw.githubusercontent.com/kcs880323-crypto/factory/app-releases/<앱 id>.json`
으로 만들면, 앱이 열릴 때(6시간에 한 번, 또는 '업데이트 확인') 이 정보 파일을 읽고 새 버전이면 "업데이트"를 묻는다.

| 앱 | 정보 파일 |
|---|---|
| 우리집 가계부 | `app.jarvis.web.csjhth_ai_studio.json` |
| AJ GA HUB | `app.jarvis.web.corporate_hub_2025_1067647975588_us_west1_run_app.json` |
| 자비스 (암호화) | `app.jarvis.web.myjarvis.json` |

새 버전 올리기 (자비스):
1. `url_app.py build <URL>` (같은 서명 키로 버전이 하나 오른다)
2. `url_app.py publish <URL|id> <이 브랜치를 꺼낸 폴더> --notes "바뀐 점"` → `<id>.json`과 `<id>-v<버전>.apk`가 생기고 그 앱의 옛 APK는 지워진다
3. 이 브랜치에 커밋하고 올린다. 앱은 정보 파일의 `sha256`·`size`를 확인하고, 안드로이드는 같은 서명 키인 APK만 기존 앱 위에 설치한다.

APK에는 비밀번호·앱 연동 키가 들어 있지 않다 (각 폰의 앱 안에서 넣는다). 서명 키는 절대 올리지 않는다.

**암호화한 업데이트** (`--update-encrypt`, 주소를 숨겨야 하는 앱): APK 대신 `<id>-v<버전>.bin`(AES-256-GCM)을 둔다.
열쇠는 만든 컴퓨터(`~/.jarvis-url-app/update-keys/`)와 설치된 앱 안에만 있어서, 여기서 받아도 내용(사이트 주소 포함)을 볼 수 없다.
정보 파일의 `notes`는 공개되므로 일반적인 말만 쓴다.
