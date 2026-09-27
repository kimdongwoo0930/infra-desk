# CLAUDE.md

## 프로젝트 개요
여러 클라우드 계정의 서버를 한 곳에서 관리하는 크로스플랫폼 데스크톱 앱.
주 사용 환경은 macOS(Apple Silicon)이고, Windows도 지원한다.
지금은 Oracle Cloud(OCI) 계정 3개, 계정마다 서버 1대씩 총 3대를 관리한다.
나중에 AWS, GCP를 추가할 수 있게 설계한다.

주요 기능: 서버 상태 모니터링, 시작/정지/재부팅, 내장 SSH 터미널.

## 기술 스택
- Java 25 (LTS), Gradle 9.1 이상
- UI: Swing + FlatLaf (다크 테마, macOS는 `FlatMacDarkLaf`)
- SSH: Apache MINA SSHD
- 터미널 UI: JediTerm
- 클라우드: OCI Java SDK (core, monitoring)
- 차트: XChart
- 설정 저장: 로컬 JSON. 비밀값은 OS 키체인(macOS 키체인, Windows 자격 증명 관리자)에 저장하고, 불가능하면 마스터 비밀번호 기반 AES-GCM 암호화
- 배포: jpackage (macOS `.dmg`, Windows `.exe`). OS별 빌드는 GitHub Actions 매트릭스로

라이브러리 버전과 Maven 좌표는 추측하지 말고 최신 공식 문서에서 확인한다.

## 패키지 구조
```
ui/               화면 (Swing). core 타입만 사용한다
core/             공통 모델과 인터페이스: Server, ServerStatus, Metrics, Account, CloudProvider
provider/oracle/  OracleProvider (OCI SDK 사용은 이 패키지 안에서만)
ssh/              SSH 연결, 세션 관리, 명령 실행
storage/          계정·서버 설정 저장, 암호화
```

## 설계 원칙 (반드시 지킬 것)
1. **클라우드 추상화**: UI와 서비스 코드는 `CloudProvider` 인터페이스만 호출한다.
   ```java
   interface CloudProvider {
       List<Server> listServers();
       void start(String serverId);
       void stop(String serverId);
       void reboot(String serverId);
       Metrics getMetrics(String serverId);
   }
   ```
   구현체가 `OracleProvider` 하나뿐이어도 이 구조를 유지한다.
2. **OCI 타입 격리**: OCI SDK의 `Instance` 등은 `provider/oracle` 밖으로 내보내지 않는다. 공통 모델(`Server`, `ServerStatus`)로 변환해서 넘긴다.
3. **상태값 정규화**: 클라우드별 상태 문자열을 공통 enum `ServerStatus`(RUNNING, STOPPED, STARTING, STOPPING, UNKNOWN 등)로 변환한다.
4. **계정에 provider 필드**: `Account`는 `provider`(ORACLE, AWS, ...) 값을 가지고, 이 값으로 구현체를 선택한다.
5. **SSH는 클라우드와 무관**: SSH와 이후 Docker 관리는 `CloudProvider`와 분리된 계층이다.
6. **UI 스레드**: 네트워크 호출은 절대 EDT에서 하지 않는다. `SwingWorker`나 별도 executor를 쓰고 결과만 EDT로 넘긴다.

## 보안 규칙
- API 개인키(.pem), SSH 개인키, OCID, fingerprint, 계정 설정 파일은 **절대 커밋하지 않는다**. `.gitignore`에 포함시킨다.
- 비밀값은 평문으로 저장하지 않는다. 로그에도 출력하지 않는다.
- 정지, 재부팅 같은 변경 작업은 실행 전에 확인 다이얼로그를 띄운다.
- 테스트 코드에 실제 키나 IP를 넣지 않는다. 가짜 값이나 목(mock)을 쓴다.

## 인증 구조
- OCI API 키는 계정(테넌시)마다 1개. 계정 3개면 키 3개.
- 필요한 값: tenancy OCID, user OCID, fingerprint, region, API 개인키 파일.
- SSH 키는 서버마다 등록한다 (같은 키를 여러 서버에 써도 된다).

## 조회 주기
- 서버 상태: 30~60초 폴링. 시작/정지 직후에는 상태가 안정될 때까지 5초 간격.
- 메트릭: OCI Monitoring 1분 간격 (OCI가 1분 단위로 집계함).
- 서버 상세 화면을 보고 있을 때만 선택적으로 SSH로 `/proc/stat`, `/proc/meminfo`를 2~5초마다 읽는 실시간 모드.
- OCI API 요청 제한을 고려해 과도한 폴링을 하지 않는다.

## 개발 단계
0. 준비: 계정 3개 API 키 발급, SSH 키 정리 (사용자가 직접)
1. 뼈대: Gradle 프로젝트, FlatLaf 창, core 모델과 `CloudProvider` 정의
2. 계정·서버 목록: 계정 등록 화면, OCI에서 인스턴스 조회, 상태 표시
3. 제어: 시작/정지/재부팅, 상태 자동 새로고침
4. SSH 터미널: JediTerm 연결, 서버별 탭
5. 모니터링: CPU/메모리/네트워크 그래프
6. 편의 기능: 저장된 명령어 버튼, 여러 서버 일괄 실행, SFTP, 디스코드 웹훅 알림
7. 패키징: jpackage로 macOS `.dmg` (이후 Windows `.exe`)

1~4단계가 MVP다. 한 단계씩 끝내고 동작을 확인한 뒤 다음으로 넘어간다.

### 이후 확장 (지금은 구현하지 않음)
- `AwsProvider` 등 다른 클라우드
- Docker 관리: SSH로 `docker ps --format '{{json .}}'` 등 실행 → `ContainerService` 계층. Docker TCP 포트(2375)는 절대 열지 않는다.
- AI 에이전트 (tool calling, 로컬/클라우드 LLM 전환)

## 디자인
`docs/design/DESIGN.md`에 색상, 타이포, 화면 구성을 정리했다.
`docs/design/*.html`은 목업이다. 브라우저로 열어서 참고하고, Swing으로 최대한 비슷하게 구현한다. HTML 자체를 앱에 넣는 게 아니다.

## 작업 방식
- 코드와 커밋 메시지는 영어, 설명과 대화는 한국어.
- 경로, 줄바꿈, 단축키(Cmd/Ctrl) 등 OS 의존 코드는 macOS와 Windows 둘 다 고려한다.
- 변경 전에 무엇을 바꿀지 짧게 말하고 진행한다.
- 새 라이브러리를 추가할 때는 이유를 한 줄로 설명한다.

### 진행·커밋 규칙
- 진행 과정을 중간중간 사용자에게 짧게 보고한다.
- 각 단계를 끝내면(빌드·테스트 통과, 화면 확인 후) `main`에 커밋한다. 커밋 메시지는 영어. 푸시는 사용자가 요청할 때만 한다.
- 단계를 끝낼 때마다 `docs/DEVLOG.md`(한 일, 결정한 것과 이유, 남은 일)를 갱신하고, 구조가 바뀌면 `docs/ARCHITECTURE.md`도 고쳐서 같은 커밋에 넣는다.

### 데모 모드 (가짜 데이터)
- 사용자에게 보여주거나 화면을 확인할 때는 실제 키 없이 **데모 모드**로 실행한다: `./gradlew runDemo`
- 데모 모드는 가짜 `CloudProvider` 구현(`provider/demo`)을 쓴다. 새 기능을 만들면 데모 모드에서도 동작하도록 가짜 데이터를 같이 만든다.
- 데모 모드는 실제 설정 파일과 키체인을 읽거나 쓰지 않는다.
- 화면 확인은 `./gradlew snapshot`(데모 데이터로 오프스크린 렌더링 → `build/snapshots/*.png`)으로 한다.