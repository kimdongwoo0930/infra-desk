# 개발 기록

단계별로 한 일, 결정한 것, 남은 일을 기록한다. 최신 항목이 위에 온다.

---

## 2026-09-28 · 5단계: 모니터링

### 한 일
- **OCI Monitoring** (`provider/oracle/OracleMetrics`): `oci_computeagent` 네임스페이스에서 최근 1시간, 1분 해상도.
  - CPU `CpuUtilization.mean()`, 메모리 `MemoryUtilization.mean()`, 네트워크 `NetworksBytesIn/Out.rate()` (초당 바이트).
  - 사이드바용 CPU는 계정당 **한 번의 묶음 쿼리** `CpuUtilization[1m].groupBy(resourceId).mean()`.
- `CloudProvider.currentCpu(servers)` 추가 (기본 구현은 서버별 `getMetrics`, OCI는 묶음 쿼리로 재정의).
- **SSH 실시간 모드** (`ssh/ProcStats`, `service/LiveStats`): 서버 상세에서 [실시간 (SSH)]를 켜면 exec 채널로 `/proc/stat`, `/proc/meminfo`, `/proc/net/dev`를 2초마다 읽어 차이로 계산. 서버를 바꾸거나 정지되면 자동으로 끔.
  - CPU: busy/total jiffies 차이 (idle + iowait는 idle로 계산)
  - 메모리: (MemTotal − MemAvailable) / MemTotal
  - 네트워크: `lo` 제외 모든 인터페이스의 rx/tx 바이트 차이 ÷ 경과 시간 (카운터가 줄면 0)
- **UI**: 서버 상세 아래 "모니터링" 섹션 — CPU / 메모리 / 네트워크 스탯 타일 (현재값 + XChart 스파크라인 + 마우스 커서 십자선·툴팁). 사이드바 실행 중 서버 옆에 CPU%.
- 주기: 선택한 서버 메트릭과 사이드바 CPU는 1분마다 (OCI가 1분 단위로 집계). 실시간 모드는 2초.
- **데모**: 서버 id로 고정된 파형의 가짜 1시간 데이터, 실시간은 `/proc` 형식 가짜 출력 (실제와 같은 파서를 거침).
- 스냅샷 `main.png`(1시간 그래프), `main-live.png`(실시간).
- 테스트 48개 통과 (`/proc` 계산, 데모 실시간 스트림, MQL 생성·데이터 정리, 데모 메트릭, 계정별 CPU 실패 격리).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 차트 형태 | 스탯 타일 (현재값 + 스파크라인), 축·격자 없음 | 목업 그대로. 한 숫자 + 추세를 보여주는 용도 (dataviz 스킬 형태 선택) |
| 색 | CPU·메모리: 포인트 색 `#3B73E0` 한 가지. 네트워크: 수신 `#3B73E0`, 송신 `#D95926` | 카드 배경(`#2B2D30`) 기준 검증 통과: 색각 이상 ΔE 27.2, 일반 ΔE 32.9, 대비 3:1 이상 |
| 범례 | 네트워크만 (시리즈 2개). 범례 항목에 현재값을 함께 표시 | 시리즈 2개 이상이면 범례 필수, 색만으로 구분하지 않기. 글자는 텍스트 색, 색은 선 견본에만 |
| y축 | CPU·메모리는 0~100 고정, 네트워크는 0부터 자동 | 퍼센트는 높이 자체가 의미가 있어서 고정. 네트워크는 범위가 서버마다 다름 |
| 네트워크 큰 숫자 | 수신 + 송신 합계 | 목업 문구 "수신 · 송신 합계" |
| 실시간 방식 | PTY 없는 exec 채널에서 `while … sleep 2` 루프 한 번 | 2초마다 새 SSH 명령을 여는 것보다 연결·인증 비용이 한 번뿐 |
| 메모리 메트릭 없음 | "메모리 메트릭이 없어요 (OCI 에이전트 확인)" 안내 | OCI 메모리는 Compute Instance Monitoring 플러그인이 켜져 있어야 수집됨 |

### 추가한 라이브러리
- `oci-java-sdk-monitoring` 3.97.0 — OCI Monitoring API
- `xchart` 4.0.4 — 스파크라인 (CLAUDE.md 스택)

### 남은 일 / TODO
- 실제 OCI 메트릭 확인 (API 키 필요). 특히 `NetworksBytesIn.rate()` 단위가 초당 바이트인지 실데이터로 확인.
- 실시간 모드 켠 동안 사이드바 CPU%는 여전히 OCI 값 (1분 단위)이라 카드 값과 다를 수 있음.
- 업타임·OS·부트 볼륨·열린 포트는 SSH로 한 번 읽어서 채울 수 있음 (다음 후보).
- 창이 최소화돼도 1분 메트릭 갱신은 `isShowing()`으로 건너뜀. 백그라운드 알림(6단계 디스코드 웹훅)과 함께 다시 볼 것.

---

## 2026-09-28 · 4단계: SSH 터미널

### 한 일
- **ssh 계층** (클라우드와 무관): `ShellConnector`/`ShellSession` 인터페이스, `MinaShellConnector`(Apache MINA SSHD), `DemoShellConnector`(데모용 가짜 셸).
- **호스트 키 검증**: 설정 디렉터리의 `known_hosts` 사용. 처음 보는 서버는 지문(SHA256)을 보여주고 사용자에게 물어봄. 저장된 키와 다르면 **연결 거부** (중간자 공격 방지).
- **서버별 SSH 설정**: 사용자 이름·포트는 `ssh-settings.json`(소유자만 읽기), 개인키·키 암호는 OS 키체인 (`server.<id>.sshKey`).
- **터미널 화면** (목업 `terminal.html`): 타이틀바가 "← 대시보드 / SSH 터미널 · 열린 세션 N개"로 바뀜. 서버별 탭(연결 상태 점, 닫기, 가운데 클릭으로 닫기), [+]로 실행 중인 서버 골라 새 세션. 하단 상태바 "연결됨 · user@ip:22 · UTF-8 · 120×32".
- 연결 실패 시 이유(인증 실패, 시간 초과, 연결 거부, 호스트 키 변경, 공인 IP 없음)와 [다시 연결] [SSH 설정] 버튼.
- 서버 상세의 [SSH 열기]: 실행 중일 때만 켜짐. 설정이 없으면 SSH 설정 창부터. 이미 열린 세션이 있으면 그 탭으로 이동.
- 사이드바 서버 우클릭 → "SSH 설정…".
- 데모 모드: 키 없이 가짜 셸 (`help`, `whoami`, `uptime`, `free -h`, `df -h`, `ls`, `ps`, `docker ps`, `date`, `echo`, `clear`, `exit`).
- 스냅샷 `terminal.png`, `ssh-settings.png`.
- 테스트 39개 통과. 그중 SSH 6개는 **테스트 안에서 MINA SSH 서버를 띄워** 실제로 접속: RSA/ed25519 키 인증, 첫 접속 확인 후 known_hosts 기억, 호스트 키 거부, 호스트 키 변경 거부, 잘못된 키로 인증 실패, 잘못된 키 파일.

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 호스트 키 | TOFU(첫 접속 시 확인) + 변경 시 거부 | 일반 `ssh`와 같은 모델. 변경된 키를 자동 수락하면 중간자 공격에 열림 |
| 연결별 상태 전달 | MINA connection context 속성 | 호스트 키 확인이 MINA I/O 스레드에서 돌아서 ThreadLocal로는 전달 안 됨 |
| PTY | `xterm-256color`, 120×32로 열고 창 크기에 맞춰 `window-change` | JediTerm이 레이아웃 후 크기를 알려줌 |
| 기본 사용자 이름 | `ubuntu` (목록: ubuntu, opc, ec2-user, root) | 서버 OS를 아직 모름. SSH로 OS를 알아내면 추천값 개선 가능 |
| 오른쪽 패널(저장된 명령어·일괄 실행·SFTP) | 이번 단계에서 제외 | CLAUDE.md 6단계 기능 |
| 터미널 기본 스타일 | `getDefaultStyle()` 재정의 (폐기 예정 API) | JediTerm 3.76이 초기 스타일에 여전히 사용하고, 기본값이 흰 배경이라 색 없는 인사말 뒤에 흰 막대가 생김 |

### 추가한 라이브러리
- `sshd-core` 2.19.0 — SSH 클라이언트 (3.0은 아직 마일스톤)
- `eddsa` 0.3.0 (runtime) — MINA SSHD가 ed25519 키를 읽는 데 필요. 없으면 `id_ed25519` 키를 못 읽음
- `jediterm-core`, `jediterm-ui` 3.76 (JetBrains 저장소, Kotlin stdlib 포함) — 터미널 에뮬레이터

### 남은 일 / TODO
- 실제 서버 SSH 접속 확인 (서버 SSH 키 필요).
- 한글이 터미널에서 두 칸 폭이라 글자 사이가 넓어 보임 (Menlo에 한글 글리프가 없어 대체 글꼴 사용). 한글 지원 고정폭 글꼴(D2Coding 등, OFL) 번들 검토.
- 데모 상태바 주소가 `ubuntu@demo:web-server:22`로 보임 (데모 전용 표기).
- 대시보드의 미니 터미널(목업 4번 영역)은 아직 없음.
- 사설 IP / 배스천 경유 접속 없음.

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
