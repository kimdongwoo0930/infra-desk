# 개발 기록

단계별로 한 일, 결정한 것, 남은 일을 기록한다. 최신 항목이 위에 온다.

---

## 2026-09-28 · 3단계: 제어·자동 새로고침

### 한 일
- 서버 상세의 [재부팅] [정지] 버튼 연결. 정지된 서버는 [정지] 대신 [시작]이 보임.
- 버튼은 상태에 따라 켜지고 꺼짐 (`ServerStatus.canStart/Stop/Reboot`). 요청 중이거나 전이 상태(정지 중 등)면 모두 꺼지고, 꺼진 이유를 툴팁으로 보여줌.
- 정지·재부팅은 실행 전에 확인 창 (서버 이름, 계정, IP, 영향 설명). 기본 선택은 "취소".
- 요청 결과를 헤더 아래 한 줄로 표시 ("정지 요청을 보냈어요…", 실패하면 빨간 글씨로 이유).
- 자동 새로고침 (`service.RefreshPolicy`):
  - 평소 45초마다 전체 계정.
  - 전이 상태 서버가 있거나 방금 요청을 보낸 계정은 5초마다, **그 계정만** 다시 조회.
  - 요청 직후 OCI가 잠깐 이전 상태를 돌려줄 수 있어서, 요청 후 20초는 상태와 관계없이 빠른 조회 유지.
  - 전이 상태가 10분 넘게 안 끝나면 평소 주기로 돌아감 (요청 한도 보호).
  - 타이머가 실패하면 조용히 넘어가고, 사용자가 누른 새로고침만 오류 창을 띄움.
- `InventoryService.control(account, serverId, action)`, `load(accounts)` 추가.
- 스냅샷 `main-stopping.png` 추가 (데모에서 discord-bot 정지 직후).
- 테스트 25개 통과 (새로고침 규칙 4개, 제어 전달·확인 필요 여부 2개 추가).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 평소 주기 | 45초 | CLAUDE.md의 30~60초 범위 중간 |
| 빠른 주기 | 5초, 해당 계정만 | CLAUDE.md 지침. 계정마다 인스턴스·VNIC 조회가 여러 번이라 전체를 5초마다 돌리면 요청 한도에 가까워짐 |
| 시작 확인 창 | 없음 | 시작은 되돌리기 쉽고 서비스 중단을 일으키지 않음. 정지·재부팅만 확인 |
| 타이머 | `javax.swing.Timer` 1회용, 조회가 끝날 때마다 다음 일정을 다시 잡음 | 조회가 겹치지 않고, 결과에 따라 주기를 바꾸기 쉬움 |

### 남은 일 / TODO
- 실제 OCI에서 시작/정지/재부팅 확인 (API 키 필요).
- 창이 최소화돼 있을 때 조회를 줄이는 건 아직 없음.

---

## 2026-09-28 · 2단계: 계정·서버 목록

### 한 일
- **데모 모드** (`./gradlew runDemo`, 또는 `--demo` 인자): 가짜 계정 3개·서버 5대 (계정 A는 한 계정에 서버 여러 대인 경우를 보여주려고 3대: discord-bot, api-gateway, batch-worker). 설정 파일·키체인·네트워크를 쓰지 않음. 타이틀바에 "데모 모드" 배지.
- **storage**: `JsonAccountStore`(계정 설정 JSON, 원자적 저장, POSIX에서 `rw-------`), `KeychainSecretStore`(OS 키체인), 데모·테스트용 인메모리 구현.
- **provider/oracle**: `OracleProvider` — 인스턴스 목록(페이지 처리), VNIC로 공인/사설 IP, 시작(START)/정지(SOFTSTOP)/재부팅(SOFTRESET) 호출, OCI 오류를 한국어 메시지로 변환. 메트릭은 5단계.
- **provider/demo**: `DemoProvider` — 지연 시간 흉내, 시작/정지/재부팅 시 8초간 전이 상태(정지 중 등)를 거쳐 안정.
- **service**: `ProviderRegistry`(ProviderType → 팩토리), `InventoryService`(계정 추가/삭제, 계정별 병렬 조회 — 한 계정 실패가 다른 계정에 영향 없음, 연결 테스트), `AccountValidation`.
- **UI**: 계정별 서버 목록(검색, 키보드 선택, 우클릭으로 계정 삭제), 서버 상세(헤더·상태 배지·4×2 정보 그리드), 계정 추가 다이얼로그(연결 테스트, 개인키 파일 선택), 새로고침.
- 네트워크 호출은 모두 `Async`(가상 스레드)에서 실행하고 결과만 EDT로 넘김.
- 스냅샷: `main.png`, `main-empty.png`, `add-account.png`.
- 테스트 19개 통과 (저장소, 서비스, 검증, 데모 전이, OCI 매핑).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 비밀값 저장 | API 개인키 **내용**을 OS 키체인에 저장 (`account.<id>.privateKey`) | 목업 문구는 "마스터 비밀번호 암호화"였지만 CLAUDE.md 우선순위(키체인 → 불가 시 AES-GCM)에 따름. 안내 문구도 키체인으로 수정 |
| 계정 설정 | `Account.properties`에 OCID·fingerprint (비밀 아님) | OCID는 커밋 금지 대상이지만 로컬 설정 파일은 괜찮음. 파일 권한은 소유자만 |
| 조회 범위 | 기본은 tenancy 루트 compartment. `compartmentOcid` 속성으로 바꿀 수 있음 | 계정당 서버 1대인 프리티어 구성에 맞춤 |
| IP 조회 | compartment의 VNIC 연결 목록을 한 번 조회 후 VNIC별 `getVnic` | 인스턴스마다 attachment를 조회하는 것보다 호출 수가 적음 |
| 정지/재부팅 | `SOFTSTOP`, `SOFTRESET` (OS에 종료 신호를 먼저 보냄) | 데이터 손상 위험이 적은 쪽 |
| `CloudProvider` | `AutoCloseable` 추가 (기본 no-op) | OCI SDK 클라이언트 해제 |
| 서비스 계층 | 새 패키지 `service/` | UI가 storage·provider를 직접 조립하지 않도록 |
| 로깅 | `slf4j-nop` | OCI SDK 로그가 콘솔에 쏟아지지 않게. 필요해지면 로거 연결 |

### 추가한 라이브러리
- `oci-java-sdk-core`, `oci-java-sdk-common-httpclient-jersey3` 3.97.0 — OCI API 호출 (SDK 3.x는 HTTP 클라이언트 구현을 따로 골라야 함)
- `jackson-databind`, `jackson-datatype-jsr310` 2.22.3 — 계정 설정 JSON
- `java-keyring` 1.0.4 — macOS 키체인 / Windows 자격 증명 관리자 접근
- `slf4j-nop` 2.0.20 — SDK 로그 끄기

### 확인한 것
- 이 Mac에서 키체인 접근 확인 (없는 항목 조회만, 아무것도 쓰지 않음).
- 실제 OCI 연결은 아직 확인하지 않음 (API 키 없음 → 0단계 이후 확인).

### 남은 일 / TODO
- **알려진 문제 (보류, 사용자 판단: 띄어쓰기처럼 보여도 괜찮음)**: 13px 글자에서 `.`와 `'`가 빈칸처럼 보임. 실제 창에서도 재현됨.
  - 원인: macOS JDK에서 어떤 글꼴 크기로 **처음** 그리는 문자열에 한글 바로 뒤의 `.`(예: "요.")가 있으면, 그 크기의 `.` 글리프가 빈 글리프로 캐시되어 이후 모든 텍스트에서 사라짐. 영문 문자열을 먼저 그리면 정상 (`OrderTest`로 확인: 첫 문자열 "VM.Standard" → 정상, "요." → 이후 전부 사라짐).
  - 해결 후보: 앱 시작 시 각 UI 글꼴 크기로 ASCII 문장부호를 한 번 오프스크린에 그려 글리프 캐시를 먼저 채우기. 또는 JDK 업데이트 후 재확인.
- 키체인을 쓸 수 없을 때 마스터 비밀번호(AES-GCM) 대체 저장소는 아직 없음.
- Windows 자격 증명 관리자는 값 길이 제한(약 2.5KB)이 있어 4096비트 RSA 키는 안 들어갈 수 있음 → Windows 빌드할 때 확인.
- 업타임·OS·부트 볼륨·열린 포트는 "—" (SSH 이후 단계에서 채움).
- 개인키 passphrase 입력칸 없음 (OCI 기본 키는 passphrase 없음).

---

## 2026-09-27 · 1단계: 뼈대

### 한 일
- Gradle 9.8.0 Wrapper, Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`) 구성. Java 25 툴체인.
- `.gitignore`: OS·IDE·빌드 산출물, 그리고 비밀값 패턴(`*.pem`, `*.key`, `id_rsa*`, `accounts.json`, `.oci/` 등).
- `core` 모델: `Account`, `Server`, `ServerStatus`, `Metrics`, `ProviderType`, `CloudProvider`, `CloudProviderException`.
- UI 뼈대: FlatLaf 다크 테마(`Theme`), 선형 SVG 아이콘(`Icons`), 타이틀바·사이드바·빈 상태 화면(`MainFrame`).
- 개발용 스냅샷 도구: `./gradlew snapshot` → `build/snapshots/main.png`.
- 테스트 5개 (`ServerStatusTest`, `ModelTest`) 통과.

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 루트 패키지 | `com.infradesk` | 짧고 중립적 |
| LaF | macOS `FlatMacDarkLaf`, 그 외 `FlatDarculaLaf` | CLAUDE.md 지침. 색상은 `FlatLaf.setGlobalExtraDefaults`로 DESIGN.md 값으로 덮어씀 |
| 창 제목 표시줄 | FlatLaf full window content 모드 | 우리 타이틀바(40px)가 곧 창 제목 표시줄이 되어 목업과 같은 모양. macOS 신호등·Windows 캡션 버튼 자리는 placeholder로 확보 |
| 아이콘 | 목업의 SVG 경로를 `resources/.../icons/*.svg`로 옮기고 `FlatSVGIcon`으로 색 입힘 | 목업과 동일한 선형 아이콘 세트 유지 |
| 폰트 | 시스템 기본(macOS: Apple SD Gothic Neo, Windows: 맑은 고딕), 고정폭은 Menlo/Consolas | 폰트 번들은 라이선스·용량 검토 후 나중에 |
| 상태 enum | `PROVISIONING, STARTING, RUNNING, STOPPING, STOPPED, REBOOTING, TERMINATING, TERMINATED, UNKNOWN` | OCI lifecycle 상태를 모두 담을 수 있게. `isTransitional()`로 빠른 폴링 여부 판단 |
| `Metrics` | 시계열 4개(CPU%, 메모리%, 네트워크 in/out B/s) | 카드의 "현재값 + 최근 1시간 그래프"를 한 번에 그릴 수 있게 |
| `Account` | 비밀값 없음 (id, 표시 이름, provider, 리전) | 비밀값은 storage 계층이 키체인에서 따로 관리 |

### 확인된 라이브러리 버전 (Maven Central, 2026-09-27)
| 라이브러리 | 버전 | 쓰는 단계 |
|---|---|---|
| FlatLaf / flatlaf-extras | 3.7.2 | 1 |
| JUnit | 6.1.3 | 1 |
| OCI Java SDK (core, monitoring, httpclient-jersey3) | 3.97.0 | 2 |
| Apache MINA SSHD | 2.19.0 (3.0은 아직 마일스톤) | 4 |
| JediTerm (JetBrains 저장소) | 3.76 | 4 |
| XChart | 4.0.4 | 5 |

### 참고
- 이 환경에서는 화면 녹화 권한이 없어 `screencapture`가 실패함 → 오프스크린 렌더링 스냅샷으로 UI 확인.
- FlatLaf가 네이티브 라이브러리를 로드하므로 `--enable-native-access=ALL-UNNAMED` JVM 옵션을 붙임.

### 남은 일 / TODO
- ~~"계정 추가" 버튼 키보드 포커스~~ → 2단계에서 `DashedButton`으로 해결.
- 실제 창에서 macOS 타이틀바 드래그·신호등 위치 눈으로 확인 필요 (사용자 확인).
