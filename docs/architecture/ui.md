# ui: 화면 (Swing)

[← 개요](README.md)

## 왜 있나

사용자가 보고 누르는 모든 것. 디자인은 `docs/design/DESIGN.md`와 `docs/design/*.html` 목업을 Swing으로 최대한 비슷하게 구현한 것이다.

`ui`가 import하는 모듈은 `core`, `service`, `ssh`, `alert`뿐이다. `provider`와 `storage`는 모른다. **업무는 `service`에 시키고, 결과를 받아 그리기만 한다.**

## 화면의 큰 구조

창 하나(`MainFrame`)가 두 "화면"을 번갈아 보여 준다(`CardLayout`).

```
┌─ TitleBar ──────────────────────────────────────────────────┐  앱 이름, 서버·계정 수, 마지막 새로고침, (터미널일 때) 뒤로가기
├─────────────────────────────────────────────────────────────┤
│  [대시보드 화면]                                              │
│  ┌ Sidebar ─┐ ┌ 서버 상세 ──────────────────────────────┐   │
│  │ 검색      │ │ ServerDetailPanel                       │   │
│  │ 계정별    │ │   헤더 · 시작/정지/재부팅 · 정보 그리드   │   │
│  │ 서버 목록 │ │   MetricsPanel   (CPU·메모리·네트워크)   │   │
│  │ (CPU%)    │ │   ContainersPanel (Docker 컨테이너)     │   │
│  └──────────┘ └─────────────────────────────────────────┘   │
│                         ── 또는 ──                           │
│  [터미널 화면]  TerminalView: 서버·컨테이너별 SSH 탭들         │
└─────────────────────────────────────────────────────────────┘
```

- **대시보드 → 터미널:** 상세의 "SSH" 버튼 → `MainFrame.openSsh()` → `showTerminal()`.
- **터미널 → 대시보드:** 타이틀바 뒤로가기, 또는 탭이 모두 닫히면.
- 창을 닫으면 종료가 아니라 메뉴 막대(macOS)/알림 영역(Windows) 아이콘으로 숨는다(`TrayController`).

## `MainFrame`이 하는 일 (약 1,150줄, 가장 큰 클래스)

화면 전체의 **지휘자**다. 지금은 이 클래스가 많은 일을 직접 한다.

| 일 | 어디서 |
|---|---|
| 서버 목록 조회 타이머, 결과를 인벤토리에 합치기 | `poll()`, `reload()`, `merge()`, `setInventory()` |
| 시작/정지/재부팅 요청과 확인 창 | `runAction()`, `confirm()` |
| 메트릭 조회(1분 타이머), 사이드바 CPU | `metricsTick()`, `refreshCpu()` |
| 실시간 모드(SSH로 `/proc` 읽기) 켜기·끄기 | `LiveHooks`, `LiveController` |
| 컨테이너·서버 정보 불러오기 | `loadContainers()`, `loadFacts()` |
| 업데이트 확인 | `updateTimer` (6시간마다) |
| 계정 추가/삭제, 설정 | `AddAccountDialog`, `SettingsDialog` |

조회가 실패해도 서버 목록을 지우지 않는다. 마지막으로 알던 서버를 `UNREACHABLE`로 남겨서 잠깐의 네트워크 끊김에 화면이 비지 않게 한다(`keepLastKnownServers`).

> `MainFrame`이 `SshMetricsService`, `ContainerService`를 직접 만든다. 나머지 서비스는 `app`에서 받는다. 조립이 한 곳에서만 이뤄지지는 않는다.

## 규칙: 네트워크는 EDT 밖에서

Swing의 화면 전용 스레드(EDT)에서 네트워크를 기다리면 창이 멈춘다. 그래서 `service`를 부르는 곳은 항상 `Async.run`을 거친다.

```java
Async.run(
    () -> service.control(account, id, STOP),   // 가상 스레드에서 실행 (EDT 아님)
    ok  -> { ... },                              // 성공: EDT로 돌아와 화면 갱신
    err -> { ... Async.message(err) ... });      // 실패: EDT에서 한국어 메시지 표시
```

화면 코드를 읽다가 `service.`나 `terminalService.` 호출이 보이면 근처에 `Async.run`이 있어야 한다.

## 패키지 안내

### 최상위 `ui`

| 클래스 | 역할 |
|---|---|
| `MainFrame` | 위에서 설명한 지휘자 |
| `Async` | 가상 스레드에서 작업 → 결과는 EDT로 |
| `Theme`, `Icons` | DESIGN.md 색상, FlatLaf 설치, SVG 아이콘 |
| `TitleBar` | 앱 이름, 서버·계정 수, 마지막 새로고침 |
| `Sidebar`, `ServerListItem` | 검색, 계정별 그룹, 서버 행 |
| `ServerDetailPanel` | 서버 헤더, 시작/정지/재부팅 버튼(상태별 활성화), 요청 결과 한 줄, 정보 그리드 |
| `AddAccountDialog`, `AddSshServerDialog` | 계정 추가, 연결 테스트 / 직접 연결 기기 추가 |
| `SettingsDialog` | 설정 (디스코드 알림은 "준비 중") |
| `TrayController`, `SwingTrayMenu` | 메뉴 막대(macOS)·알림 영역(Windows) 아이콘과 서버 상태 메뉴. macOS는 네이티브 AWT 메뉴, Windows는 `SwingTrayMenu`가 그림 |
| `IpPrivacy` | 공인 IP 가리기/보이기 (앱 전체, 기본 가림) |

### `ui.metrics` — 그래프

| 클래스 | 역할 |
|---|---|
| `MetricsPanel` | 모니터링 섹션: 카드 3개, 상태 문구, 실시간 토글 |
| `MetricCard` | 스탯 타일: 현재값 + XChart 스파크라인 + 범례/설명 |
| `MetricKind` | CPU/메모리/네트워크 정의(제목, 시리즈 색, 포맷, y 최댓값, `Metrics`에서 꺼내는 법). 카드와 확대 창이 같이 씀 |
| `MetricChartDialog` | 카드를 클릭하면 열리는 확대 차트. 기간 버튼(1시간/6시간/24시간/7일), 1분마다 재조회 |
| `ZoomChartPanel`, `ZoomWindow` | 휠 확대·축소, 드래그 이동. `ZoomWindow`는 UI 없는 순수 계산이라 단위 테스트가 있다 |
| `ChartTooltip`, `HoverChartPanel` | 마우스를 올렸을 때 세로선과 값 툴팁 |
| `LiveController` | 실시간 모드 상태(OFF/CONNECTING/RUNNING/PAUSED), 일시정지·재개, 10분 뒤 자동 꺼짐 |

### `ui.terminal` — SSH 터미널

| 클래스 | 역할 |
|---|---|
| `TerminalView` | 세션 탭 모음, 새 세션 메뉴 |
| `TerminalPanel` | 탭 하나: 연결, JediTerm 위젯, 상태바, 실패 안내와 "다시 연결" |
| `ShellTtyConnector` | `ssh.ShellSession`을 JediTerm이 쓰는 형태로 이어 주는 어댑터 |
| `TerminalSidePanel` | 오른쪽 패널: 저장된 명령어, 일괄 실행, SFTP 버튼 |
| `BatchResultDialog`, `SftpDialog`, `CommandEditDialog` | 일괄 실행 결과 / SFTP 탐색기 / 명령어 편집 |
| `SshSettingsDialog` | 서버별 사용자 이름·포트·SSH 키 등록 |
| `HostKeyDialog` | 처음 보는 호스트 키 확인. `ssh.HostKeyPrompt`를 구현하며 어느 스레드에서든 호출 가능 |

### 그 밖의 하위 패키지

| 패키지 | 내용 |
|---|---|
| `ui.containers` | `ContainersPanel`(서버 상세의 컨테이너 표와 동작 버튼), `ContainerLogsDialog`(로그 창) |
| `ui.components` | 공용 부품: `Buttons`, `DashedButton`, `StatusDot`, `StatusBadge`, `RoundedPanel`, `Toast`(오른쪽 아래 잠깐 뜨는 알림) 등 |

## 흐름 예시

### 모니터링

```
서버 선택     → Async: service.metrics(account, id)          → MetricsPanel.showHistory (1시간, 1분 점)
카드 클릭     → MetricChartDialog → Async: service.metrics(account, id, range) → ZoomChartPanel
                  OCI 간격: 6시간 이하 1분, 24시간 이하 5분, 그 이상 15분
                  직접 연결 서버는 기간 선택 없이 수집분만
1분 타이머    → 선택 서버 메트릭 + service.currentCpu(inventory) → 사이드바 CPU%
[실시간 (SSH)] → LiveController → TerminalService.openStats (exec: ProcStats.COMMAND, 최대 310회)
                  → LiveStats: '---' 블록마다 파싱 → 이전 스냅샷과 차이 → MetricsPanel.addLive (최근 5분)
터미널 화면 / 최소화 → LiveController.pause (연결 닫음) → 대시보드 복귀 → resume (다시 연결)
켠 지 10분    → 자동 꺼짐
```

### SSH 터미널 열기

```
[SSH 열기] → 설정 없음? → SshSettingsDialog (키 → 금고에 저장)
          → TerminalView 탭 추가 → Async: TerminalService.open(server)
             → MinaShellConnector: 연결 → 호스트 키 확인(known_hosts / HostKeyDialog)
               → 공개키 인증 → shell 채널(PTY xterm-256color)
          → ShellTtyConnector로 JediTerm에 연결 → 창 크기 변경 시 window-change
```
