# 구조

## 계층

```
                 ┌──────────── app (InfraDeskApp: 조립, --demo 분기)
                 ▼
ui/ ─────► service/ ─────► core/ ◄───── provider/oracle/   (OCI SDK는 여기서만)
 │             │             ▲   ◄───── provider/demo/     (가짜 데이터)
 │             └──► storage/ ┘
 └──► ssh/  (MINA SSHD / 데모 셸)
```

- **ui**: Swing 화면. `service`와 `core` 타입만 안다. 네트워크 작업은 `Async`로 EDT 밖에서.
- **service**: `InventoryService`(계정·비밀값·provider 관리, 서버 조회), `ProviderRegistry`(`ProviderType` → 팩토리), `AccountValidation`.
- **core**: 공통 모델과 인터페이스. 다른 패키지에 의존하지 않는다.
- **provider/<cloud>**: `CloudProvider`·`CloudProviderFactory` 구현. SDK 타입을 `core` 모델로 변환해서 내보낸다.
- **storage**: 계정 설정(JSON), 비밀값(OS 키체인).
- **ssh**: 클라우드와 무관한 SSH 연결·세션. `ShellConnector`(MINA / 데모), `ShellSession`, `known_hosts` 검증.
- **alert**: 디스코드 알림. `AlertMonitor`(인벤토리·CPU 변화 → 알림, 순수 상태 기계), `DiscordNotifier`(웹훅 전송), `AlertService`(설정·웹훅 비밀값·전송 스레드).
- **app**: 진입점. 실제 모드와 데모 모드의 구성 요소를 조립한다. `AppIcon`(Dock/창 아이콘), `SelfTest`(`--self-test`), `InputDiagnostics`(`--debug-input`), `Logging`(로그 파일·가림), `BuildInfo`(버전·빌드·커밋), `LaunchAtLogin`(로그인 시 실행).

실행 옵션: `--demo`, `--minimized`(메뉴 막대에서만 시작), `--debug-input`, `--self-test`, `--migrate-secrets`.

## 실제 모드 vs 데모 모드

| | 실제 (`./gradlew run`) | 데모 (`./gradlew runDemo`) |
|---|---|---|
| 계정 저장 | `JsonAccountStore` | `InMemoryAccountStore` (가짜 계정 3개로 시작) |
| SSH 설정 | `JsonSshSettingsStore` | `InMemorySshSettingsStore` (없어도 `ubuntu`로 접속) |
| SSH 연결 | `MinaShellConnector` + `known_hosts` | `DemoShellConnector` (가짜 셸) |
| 저장된 명령어 | `JsonSavedCommandStore` | 목업 예시 4개 (메모리) |
| 알림 | `DiscordNotifier` (웹훅) | 토스트 미리보기 (전송 안 함) |
| 비밀값 | `VaultSecretStore` (키체인엔 마스터 키만) | `InMemorySecretStore` |
| provider | `OracleProviderFactory` | `DemoProviderFactory` (모든 ProviderType) |
| 네트워크 | OCI API | 없음 (지연 시간만 흉내) |

## 핵심 타입

| 타입 | 역할 |
|---|---|
| `core.CloudProvider` | `listServers`, `start`, `stop`, `reboot`, `getMetrics`(최근 1시간, 1분 단위), `currentCpu`(사이드바용), `close`. 계정 1개당 1개. 모든 메서드는 블로킹 |
| `core.CloudProviderFactory` | `create(Account, secrets)`, `regions()` |
| `core.Account` | id, 표시 이름, `ProviderType`, 리전, `properties`(비밀 아닌 설정). 비밀값 없음 |
| `core.Server` / `ServerStatus` / `Metrics` | 공통 모델. 상태는 정규화된 enum |
| `core.CloudProviderException` | SDK 예외를 감싼 공통 예외. 메시지는 사용자에게 보여줄 한국어 |
| `service.InventoryService` | `loadAll()` / `load(accounts)`(계정별 병렬, 실패는 계정 단위로 `AccountInventory.error`), `control`, `addAccount`, `removeAccount`, `testConnection` |
| `service.ServerAction` | `START`, `STOP`, `REBOOT`. 확인 창이 필요한지(`needsConfirmation`) 포함 |
| `service.TerminalService` | 서버별 SSH 설정·키 저장, 공인 IP로 셸 열기, 명령 실행(`run`: 제한 시간·출력 상한), SFTP 열기, 저장된 명령어 |
| `ssh.RemoteFiles` / `RemoteFile` | SFTP 목록·업로드·다운로드·삭제 (MINA `sshd-sftp` / 데모 메모리 트리) |
| `ssh.ShellConnector` / `ShellSession` | 셸 열기 / 입출력 스트림·크기 조정·종료 대기 |
| `ssh.DockerCommands` / `Container` | SSH로 실행할 Docker 명령(ID만 허용)과 결과 파싱 |
| `service.ContainerService` | 컨테이너 목록·시작/정지/재시작·로그 (SSH exec) |
| `ssh.HostFacts` | SSH 한 번으로 업타임·OS·루트 디스크·열린 포트 읽기 (서버 상세 정보 칸) |
| `ssh.SshConfig` | `~/.ssh/config` 읽기: 공인 IP → Host → 사용자·포트·키 파일 추천 |
| `ssh.ProcStats` | 원격 `/proc` 스냅샷 명령, 파싱, 두 스냅샷 사이 사용률 계산 |
| `service.LiveStats` | exec 세션 출력을 읽어 2초마다 `ProcStats.Sample` 전달 |
| `ssh.SshException` | 종류(`CONNECT`, `AUTH`, `KEY_FORMAT`, `HOST_KEY_REJECTED`, `HOST_KEY_CHANGED`)와 한국어 메시지 |
| `service.UpdateService` | GitHub `beta` 릴리스에서 최신 빌드 번호·다운로드 주소 확인, 자동 확인 설정 |
| `service.RefreshPolicy` | 다음 조회 시점과 대상 계정 결정 (평소 45초 전체, 전이·요청 직후 5초 해당 계정만) |
| `storage.SecretStore` | 키: `account.<accountId>.<secretName>` (예: `privateKey`) |

## 저장 위치

| OS | 설정 디렉터리 |
|---|---|
| macOS | `~/Library/Application Support/InfraDesk/accounts.json` |
| Windows | `%APPDATA%\InfraDesk\accounts.json` |
| 기타 | `$XDG_CONFIG_HOME/infradesk` 또는 `~/.config/infradesk` |

| 파일 | 내용 |
|---|---|
| `accounts.json` | 계정 설정 (OCID, fingerprint, 리전) |
| `ssh-settings.json` | 서버별 SSH 사용자 이름·포트 |
| `secrets.vault` | 모든 비밀값 (암호화) |
| `commands.json` | 저장된 명령어 (이름, 명령) |
| `alerts.json` | 알림 켜기/끄기, 종류, CPU 기준 |
| `app-settings.json` | 업데이트 자동 확인 여부 |

로그: macOS `~/Library/Logs/InfraDesk/infradesk-N.log`, Windows `%LOCALAPPDATA%\InfraDesk\logs` (가림 처리됨).
| `known_hosts` | 신뢰한 SSH 호스트 키 (OpenSSH 형식) |

비밀값(API 개인키, SSH 개인키·암호, 웹훅 URL)은 **`secrets.vault`**(AES-256-GCM 암호화, 권한 `rw-------`)에 저장되고, 그 **마스터 키 하나만** OS 키체인(서비스 이름 `InfraDesk`, 항목 `vault.masterKey`)에 있다. 키체인은 실행당 최대 한 번 읽는다. 아래 이름은 금고 안의 키 이름이다.

| 금고 키 이름 | 내용 |
|---|---|
| `account.<accountId>.privateKey` | OCI API 개인키 |
| `server.<serverId>.sshKey` | SSH 개인키 |
| `server.<serverId>.sshPassphrase` | SSH 키 암호 (있을 때만) |
| `alerts.discordWebhook` | 디스코드 웹훅 URL |

## OCI 매핑

| OCI | InfraDesk |
|---|---|
| Instance `Provisioning/Starting/Running/Stopping/Stopped/Terminating/Terminated` | 같은 이름의 `ServerStatus` |
| `Moving`, `CreatingImage` | `RUNNING` |
| 정지 / 재부팅 | `SOFTSTOP` / `SOFTRESET` |
| CPU / 메모리 | `oci_computeagent` `CpuUtilization` / `MemoryUtilization` `[1m].mean()` |
| 네트워크 | `NetworksBytesIn` / `NetworksBytesOut` `[1m].rate()` |
| 사이드바 CPU | `CpuUtilization[1m].groupBy(resourceId).mean()` 계정당 1회 |
| 401 / 403·404 / 409 / 429 | 인증 실패 / 권한·OCID 확인 / 다른 작업 중 / 요청 한도 |

## 제어 흐름

```
[정지] 클릭 → 확인 창 → Async: service.control(STOP)
          → policy.actionSent(account) → 해당 계정 즉시 재조회
          → RefreshPolicy가 5초 주기 유지 (전이 상태 또는 요청 후 20초)
          → 안정 상태가 되면 45초 전체 조회로 복귀
```

## SSH 연결 흐름

```
[SSH 열기] → 설정 없음? → SshSettingsDialog (키 → 키체인)
          → TerminalView 탭 추가 → Async: TerminalService.open(server)
             → MinaShellConnector: connect → 호스트 키 확인(known_hosts / HostKeyDialog)
               → 공개키 인증 → shell 채널(PTY xterm-256color)
          → ShellTtyConnector로 JediTerm에 연결 → 창 크기 변경 시 window-change
```

## 모니터링 흐름

```
서버 선택 → Async: service.metrics(account, id) → MetricsPanel.showHistory (1시간, 1분 점)
1분 타이머 → 선택 서버 메트릭 + service.currentCpu(inventory) → 사이드바 CPU%
[실시간 (SSH)] → LiveController → TerminalService.openStats (exec: ProcStats.COMMAND, 최대 310회)
             → LiveStats: '---' 블록마다 파싱 → 이전 스냅샷과 차이 → MetricsPanel.addLive (최근 5분)
터미널 화면 / 최소화 → LiveController.pause (연결 닫음) → 대시보드 복귀 → resume (다시 연결)
켠 지 10분 → 자동 꺼짐
```

## 알림 흐름

```
MainFrame.setInventory → AlertService.onInventory → AlertMonitor (상태 변화 비교)
1분 CPU 조회        → AlertService.onCpu        → AlertMonitor (연속 N회 기준 이상)
정지·재부팅 요청    → AlertService.expectChange (15분 동안 해당 서버 제외)
알림 → 전송 스레드 → DiscordNotifier (실제) / Toast 미리보기 (데모) → 실패 시 Toast
```

## UI 구성 (`com.infradesk.ui`)

| 클래스 | 역할 |
|---|---|
| `Theme`, `Icons` | DESIGN.md 색상, FlatLaf 설치, SVG 아이콘 |
| `MainFrame` | 메인 창. 조회 타이머, 부분 새로고침 병합, 제어 요청·확인 창, 선택 유지, 계정 추가/삭제 |
| `TitleBar` | 앱 이름, 서버·계정 수, 데모 배지, 마지막 새로고침 |
| `Sidebar`, `ServerListItem` | 검색, 계정별 그룹, 서버 행 |
| `ServerDetailPanel` | 서버 헤더, 시작/정지/재부팅 버튼(상태별 활성화), 요청 결과 한 줄, 정보 그리드 |
| `AddAccountDialog` | 계정 추가, 연결 테스트 |
| `Async` | 가상 스레드에서 작업 → 결과는 EDT로 |
| `SettingsDialog` | 설정 (디스코드 알림) |
| `TrayController` | 메뉴 막대(macOS)·알림 영역(Windows) 아이콘과 서버 상태 메뉴. 메뉴는 `Entry` 목록으로 한 번 만들고 macOS에서는 네이티브 AWT 메뉴, Windows에서는 `SwingTrayMenu`로 그림. 창을 닫으면 여기로 숨김 |
| `SwingTrayMenu` | Windows 트레이 메뉴. 커서 위치의 1px 투명 다이얼로그에 JPopupMenu를 띄우고, 포커스를 잃으면 닫음 |
| `IpPrivacy` | 공인 IP 가리기/보이기 (앱 전체, 기본 가림) |
| `components.Toast` | 창 오른쪽 아래 잠깐 뜨는 알림 |
| `metrics.MetricsPanel` | 모니터링 섹션: 카드 3개, 상태 문구, 실시간 토글 |
| `containers.ContainersPanel` | 서버 상세의 컨테이너 표와 동작 버튼 |
| `containers.ContainerLogsDialog` | 컨테이너 로그 창 |
| `metrics.LiveController` | 실시간 모드 상태(OFF/CONNECTING/RUNNING/PAUSED), 일시정지·재개, 10분 자동 꺼짐 |
| `metrics.MetricCard` | 스탯 타일: 현재값 + XChart 스파크라인 + 범례/설명 |
| `metrics.HoverChartPanel` | 스파크라인 위 마우스 커서: 세로선 + 가장 가까운 시점의 시리즈별 값 툴팁 |
| `terminal.TerminalView` | 세션 탭 모음, 새 세션 메뉴 |
| `terminal.TerminalPanel` | 탭 하나: 연결, JediTerm 위젯, 상태바, 실패 안내 |
| `terminal.TerminalSidePanel` | 오른쪽 패널: 저장된 명령어, 일괄 실행, SFTP 버튼 |
| `terminal.BatchResultDialog` | 일괄 실행 결과 (서버별 상태 + 출력) |
| `terminal.SftpDialog` | SFTP 파일 탐색기 |
| `terminal.SshSettingsDialog` | 서버별 사용자 이름·포트·SSH 키 등록 |
| `terminal.HostKeyDialog` | 처음 보는 호스트 키 확인 (어느 스레드에서든 호출 가능) |
| `components.*` | `Buttons`, `DashedButton`, `StatusDot`, `StatusBadge`, `RoundedPanel` |

## 빌드·실행

```bash
./gradlew run        # 앱 실행 (실제 모드)
./gradlew runDemo    # 데모 모드 (가짜 데이터, 키 불필요)
./gradlew test       # 테스트
./gradlew snapshot   # 데모 데이터로 화면을 build/snapshots/*.png로 렌더링 (개발용)
./gradlew dmg        # macOS: build/dist/InfraDesk-<버전>.dmg (패키징된 앱 자가 점검 포함)
./gradlew windowsZip # Windows: build/dist/InfraDesk-<버전>-windows.zip
./gradlew generateIcons  # 앱 아이콘 다시 그리기 (src/packaging, 런타임 아이콘)
```

## 패키징

```
installDist → appImage (jpackage + jlink 필요한 모듈만) → selfTestAppImage (앱 실행 파일로 --self-test)
           → dmg (macOS) / windowsZip (Windows)
```
- JVM 옵션: `build.gradle.kts`의 `appJvmArgs`(SerialGC, `-Xms16m -Xmx256m`, 빈 힙 반환, C1 전용). `run`·`runDemo`·jpackage가 함께 쓴다. 근거는 DEVLOG "메모리 줄이기"
- 아이콘: `src/packaging/macos/InfraDesk.icns`, `src/packaging/windows/InfraDesk.ico` (`tools.IconGenerator`가 생성)
- CI (`.github/workflows/build.yml`): `main` 푸시 때만 macOS·Windows에서 테스트 → 패키징(자가 점검) → **`beta` 시험판 릴리스**의 `InfraDesk-beta-macOS.dmg` / `InfraDesk-beta-windows.zip`을 교체. 설명은 `.github/release-notes.md`.
