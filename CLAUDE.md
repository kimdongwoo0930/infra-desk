# CLAUDE.md

## 프로젝트 개요
여러 클라우드 계정과 직접 연결한 서버를 한 곳에서 관리하는 크로스플랫폼 데스크톱 앱.
주 사용 환경은 macOS(Apple Silicon)이고, Windows도 지원한다.
대상은 대규모 서비스가 아니라 개인·소규모(1인) 서버 운영이다.

제품 요구사항(기능, 조회 주기, 비기능 요구사항, 현재 상태와 계획)은 `docs/PRD.md`를 따른다.

## 기술 스택
- Java 25 (LTS), Gradle 9.1 이상
- UI: Swing + FlatLaf (다크 테마, macOS는 `FlatMacDarkLaf`)
- SSH/SFTP: Apache MINA SSHD
- 터미널 UI: JediTerm
- 클라우드: OCI Java SDK (core, monitoring)
- 차트: XChart
- 설정 저장: 로컬 JSON. 비밀값(API 개인키, SSH 키, 웹훅)은 `secrets.vault`에 AES-256-GCM으로 암호화해 저장하고, 암호화 키만 OS 키체인(macOS 키체인, Windows 자격 증명 관리자)에 둔다
- 배포: jpackage (macOS `.dmg`, Windows `.zip`). OS별 빌드는 GitHub Actions로

라이브러리 버전과 Maven 좌표는 추측하지 말고 최신 공식 문서에서 확인한다.

## 패키지 구조
```
ui/               화면 (Swing). service, core 타입만 사용한다
service/          화면이 쓰는 기능: 조회, 제어, 터미널, 컨테이너, 업데이트
core/             공통 모델과 인터페이스: Server, ServerStatus, Metrics, Account, CloudProvider
provider/oracle/  OracleProvider (OCI SDK 사용은 이 패키지 안에서만)
provider/ssh/     SSH 직접 연결 provider / provider/demo/ 데모용 가짜 provider
ssh/              SSH 연결, 세션 관리, 명령 실행, SFTP
storage/          계정·서버 설정 저장, 암호화
alert/            디스코드 알림
app/              진입점, 구성 요소 조립
```
계층 관계와 흐름은 `docs/ARCHITECTURE.md`(목차)와 `docs/architecture/`를 따른다.

## 설계 원칙 (반드시 지킬 것)
1. **클라우드 추상화**: UI와 서비스 코드는 `core/CloudProvider` 인터페이스만 호출한다. 메서드 시그니처는 그 파일을 따른다(여기에 복사해 두지 않는다). 구현체가 적어도 이 구조를 유지한다.
2. **OCI 타입 격리**: OCI SDK의 `Instance` 등은 `provider/oracle` 밖으로 내보내지 않는다. 공통 모델(`Server`, `ServerStatus`)로 변환해서 넘긴다.
3. **상태값 정규화**: 클라우드별 상태 문자열을 공통 enum `ServerStatus`(RUNNING, STOPPED, STARTING, STOPPING, UNKNOWN 등)로 변환한다.
4. **계정에 provider 필드**: `Account`는 `provider`(ORACLE, AWS, ...) 값을 가지고, 이 값으로 구현체를 선택한다.
5. **SSH는 클라우드와 무관**: SSH, SFTP, Docker 관리는 `CloudProvider`와 분리된 계층이다.
6. **SSH 연결 규칙**: 서버 접속은 `ssh/SessionPool`을 통해서만 한다. 새 코드는 `Purpose`(QUERY/COMMAND/FILES/TRANSFER)를 밝혀서 연결을 빌리고, 호출마다 새로 접속하지 않는다. 서버를 바꾸는 명령(COMMAND)은 자동으로 다시 시도하지 않는다. 터미널 탭만 풀 밖에서 따로 열고 닫는다. 자세한 규칙은 `docs/architecture/ssh.md`의 "접속 풀".
7. **UI 스레드**: 네트워크 호출은 절대 EDT에서 하지 않는다. `SwingWorker`나 별도 executor를 쓰고 결과만 EDT로 넘긴다.

## 보안 규칙
- API 개인키(.pem), SSH 개인키, OCID, fingerprint, 계정 설정 파일은 **절대 커밋하지 않는다**. `.gitignore`에 포함시킨다.
- 비밀값은 평문으로 저장하지 않는다. 로그에도 출력하지 않는다.
- 정지, 재부팅, 삭제, 덮어쓰기 같은 되돌리기 어려운 작업은 실행 전에 확인 다이얼로그를 띄운다.
- 테스트 코드에 실제 키나 IP를 넣지 않는다. 가짜 값이나 목(mock)을 쓴다.
- Docker TCP 포트(2375)는 절대 열지 않는다. Docker 관리는 SSH로만 한다.

## 조회 주기
폴링 주기는 `docs/PRD.md`의 "조회 주기"를 따른다. OCI API 요청 제한을 고려해 그보다 과도하게 폴링하지 않는다.
주기를 바꾸면 PRD와 README를 같이 고친다.

## 현재 상태와 계획
구현 현황과 다음 계획은 `docs/PRD.md`의 "현재 상태"와 노션 「아이디어」 페이지(아래 "아이디어 논의")를 따른다.
PRD에 "계획"으로만 적힌 기능은 사용자가 요청할 때만 구현한다.

## 디자인
`docs/design/DESIGN.md`에 색상, 타이포, 화면 구성을 정리했다.
`docs/design/*.html`은 목업이다. 브라우저로 열어서 참고하고, Swing으로 최대한 비슷하게 구현한다. HTML 자체를 앱에 넣는 게 아니다.

## 작업 방식
- 코드와 커밋 메시지는 한국어, 설명과 대화는 한국어.
- 경로, 줄바꿈, 단축키(Cmd/Ctrl) 등 OS 의존 코드는 macOS와 Windows 둘 다 고려한다.
- 변경 전에 무엇을 바꿀지 짧게 말하고 진행한다.
- 새 라이브러리를 추가할 때는 이유를 한 줄로 설명한다.

### 진행·커밋 규칙
- 진행 과정을 중간중간 사용자에게 짧게 보고한다.
- 새 기능은 새 브랜치를 만들어 작업하고 커밋한다. **푸시와 PR은 사용자가 요청할 때만 한다.** PR 대상은 `develop`이다.
- 작업을 끝낼 때(빌드·테스트 통과, 화면 확인 후) `docs/DEVLOG.md`(한 일, 결정한 것과 이유, 남은 일)를 갱신하고, 구조가 바뀌면 `docs/architecture/`의 해당 모듈 문서(모듈 사이 연결이 바뀌면 `README.md` 개요도)를 고쳐서 같은 커밋에 넣는다. `docs/ARCHITECTURE.md`는 목차다.
- `main`에 PR은 개발자가 직접 한다.

### 사용자가 직접 해야 하는 테스트 (`USER_TEST_LIST.md`)
- 내가 확인하지 못한 것(화면 클릭, 실제 서버·Windows에서의 동작, 서버 로그 확인 등)이 생기면 루트의 `USER_TEST_LIST.md`에 정리한다. 작업을 끝내고 보고할 때 이 파일에 추가한 항목이 있다고 알려 준다.
- 형식은 짧은 기능 이름을 `## 기능명`으로 쓰고, 그 아래에 테스트를 `- [ ]` 체크박스 목록으로 쓴다.
- 테스트는 사용자가 그대로 따라 할 수 있게 **동작과 기대하는 결과를 한 줄**로 쓴다. 기능이 이미 사용자에게 확인된 것은 넣지 않는다.
- **체크는 사용자가 한다.** 내가 대신 체크하지 않는다. 사용자가 결과를 알려 주면(성공/실패) 그에 맞게 항목을 고치거나 지운다.
- 이 저장소는 공개이므로 테스트에 실제 서버 IP, 서버 이름, 키 같은 값을 쓰지 않는다.

### 데모 모드 (가짜 데이터)
- 사용자에게 보여주거나 화면을 확인할 때는 실제 키 없이 **데모 모드**로 실행한다: `./gradlew runDemo`
- 데모 모드는 가짜 `CloudProvider` 구현(`provider/demo`)을 쓴다. 새 기능을 만들면 데모 모드에서도 동작하도록 가짜 데이터를 같이 만든다.
- 데모 모드는 실제 설정 파일과 키체인을 읽거나 쓰지 않는다.
- 화면 확인은 `./gradlew snapshot`(데모 데이터로 오프스크린 렌더링 → `build/snapshots/*.png`)으로 한다.

## 아이디어 논의및 구현전

- 사용자가 "아이디어 얘기하자", "노션 아이디어 보자", "이제 뭐해야할까" 등을 말하면, 먼저 아래 노션 페이지를 읽는다.
  InfraDesk 「아이디어」 페이지: https://determined-date-3aa.notion.site/4010b2c80b4681c89351f13c4ba05f5c
  **노션 연결 도구 `notion-fetch`(`mcp__claude_ai_Notion__notion-fetch`)로 읽는다.** `id`에 위 주소를 그대로 넣는다.
  도구가 아직 불러와지지 않았으면 먼저 `ToolSearch`로 `select:mcp__claude_ai_Notion__notion-fetch`를 불러온다.
  `WebFetch`는 쓰지 않는다. 노션 공개 페이지는 브라우저가 스크립트로 화면을 그려 넣는 방식이라 `WebFetch`로는 본문이 비어서 나온다(2026-10-22 확인).
  노션 연결로도 안 읽히거나 내용이 비어 있으면 추측하지 말고 사용자에게 알리고, 체크 안 된 항목을 붙여넣어 달라고 요청한다.
- 체크 안 된 항목(`- [ ]`)을 기준으로 논의한다. 체크된 항목(`- [x]`)은 이미 끝난 것이므로 건드리지 않는다.
- 논의 방식:
  1. 항목을 먼저 요약하고, 지금 코드(이 저장소)의 어느 부분과 관련되는지 확인해서 알려준다.
  2. 장단점과 위험(보안, 서버 접속 수, 사용자 실수 가능성)을 짚는다.
  3. 구현 방향을 제안하되, 사용자가 정할 부분은 질문한다.
- 노션은 읽기 전용이다. 읽기 도구(`notion-fetch`, `notion-search`)만 쓰고, 페이지·데이터베이스를 만들거나 수정·삭제하는 노션 도구는 쓰지 않는다. 논의 결론은 사용자에게 정리해서 말로 주고, 노션 반영(체크, 「패치 노트」)은 사용자가 직접 한다.
- 이 페이지는 공개 주소이므로 서버 IP, 계정 정보, 키 같은 민감한 내용을 노션에 적자고 제안하지 않는다.
- 코드를 바꾸기 전에는 반드시 사용자에게 먼저 확인받는다. 논의만 하자는 요청이면 코드를 수정하지 않는다.