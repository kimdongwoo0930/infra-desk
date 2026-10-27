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
│  ┌ Sidebar ─┐ ┌ 서버 화면 (ServerScreen) ────────────────┐   │
│  │ 검색      │ │ ServerHeader  이름 · 배지 · 버튼         │   │
│  │ 계정별    │ │ TabBar        개요 / 모니터링 / …        │   │
│  │ 서버 목록 │ │ ───────────── (여기부터 스크롤) ──────── │   │
│  │ (CPU%)    │ │ 탭 본문: 개요(ServerDetailPanel)        │   │
│  │           │ │   · 모니터링(MetricsPanel)              │   │
│  └──────────┘ │   · 컨테이너(ContainersTab)              │   │
│               └─────────────────────────────────────────┘   │
│                         ── 또는 ──                           │
│  [터미널 화면]  TerminalView: 서버·컨테이너별 SSH 탭들         │
├─────────────────────────────────────────────────────────────┤
│ StatusBar  SSH 연결 · 실시간 상태 ·········· 조회 주기 · 버전 │  (두 화면 모두에 보인다)
└─────────────────────────────────────────────────────────────┘
```

- **사이드바 폭:** `MainFrame`이 사이드바와 본문을 가로 `JSplitPane`으로 나눈다. 손잡이를 끌어 폭을 바꾸고(최대 420), 200px 미만이면 이름만, 90px 미만으로 끌면 자동으로 닫힌다. 타이틀바의 버튼으로 접고 펼친다(펼치면 닫기 전 폭으로 돌아간다). 규칙은 `SidebarWidth`에 있다.
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
| `Theme`, `Icons` | DESIGN-v2.md 색상 토큰(색마다 이름을 붙여 화면은 이름만 씀), FlatLaf 설치, SVG 아이콘 |
| `TitleBar` | 앱 이름, 서버·계정 수, 마지막 새로고침, 사이드바 접기/펼치기 버튼 |
| `Sidebar`, `ServerListItem` | 검색, 계정별 그룹, 서버 행. 폭이 200px보다 좁아지면 이름만 보이는 모드로 바뀐다(`SidebarWidth`가 폭에 따른 규칙을 정한다) |
| `ServerScreen` | 서버를 골랐을 때의 화면 전체: 머리(`ServerHeader`) + 탭 줄(`TabBar`) + 스크롤되는 본문. `MainFrame`이 부르던 `ServerDetailPanel`의 메서드 이름을 그대로 머리와 본문에 나눠 넘긴다. 개요·모니터링·컨테이너·파일 탭이 동작하고 터미널·로그는 `ComingSoon`(디자인 v2 6~7단계에서 하나씩 연다). 파일 탭은 스크롤 창에 넣지 않고 `FilesHost`가 서버별로 `FilesTab`을 들고 있다 |
| `ServerHeader` | 즐겨찾기 별(준비 중), 서버 이름, 상태 배지, 계정·리전 한 줄, [SSH 열기] [재부팅] [정지/시작] [⋯(준비 중)], 시작/정지/재부팅 버튼의 상태별 활성화 규칙, 요청 결과 한 줄. IP 가림을 켜고 끌 때 부제의 IP도 같이 바뀐다 |
| `ServerDetailPanel` | 개요 탭 본문: 4×2 정보 그리드, 메트릭 차트, 컨테이너 표 |
| `AddAccountDialog`, `AddSshServerDialog` | 계정 추가, 연결 테스트 / 직접 연결 기기 추가 |
| `SettingsDialog` | 설정 (디스코드 알림은 "준비 중") |
| `TrayController`, `SwingTrayMenu` | 메뉴 막대(macOS)·알림 영역(Windows) 아이콘과 서버 상태 메뉴. macOS는 네이티브 AWT 메뉴, Windows는 `SwingTrayMenu`가 그림 |
| `IpPrivacy` | 공인 IP 가리기/보이기 (앱 전체, 기본 가림) |

### `ui.metrics` — 그래프

| 클래스 | 역할 |
|---|---|
| `MetricsPanel` | "모니터링" 탭 본문: 구간 선택(실시간·1시간·6시간·24시간·7일), 안내 문구, 큰 차트 카드 3개, 오른쪽 열. "실시간"은 구간 선택의 첫 칸이다(고르면 `onLiveToggle(true)`, 다른 기간으로 가면 `onLiveToggle(false)`, 기록 기간끼리 바꾸면 `onRangeChanged`). 직접 연결 서버는 1시간 외의 기간 칸이 비활성이다 |
| `MetricChartCard` | 큰 차트 카드: 제목, 지금 값(선이 둘이면 합), 범례, `ZoomChartPanel` |
| `MetricsSidePanel` | 오른쪽 열 카드 셋: 이 기간 요약, 디스크(/), 알림 기준(+ [설정에서 바꾸기]). 서버에 묻지 않고 받은 값만 보여 준다 |
| `MetricSummary` | "이 기간 요약" 계산(평균, 최대와 그 시각, 수신+송신 합). UI 없는 순수 계산이라 단위 테스트가 있다 |
| `MetricKind` | CPU/메모리/네트워크 정의(제목, 시리즈 색, 포맷, y 최댓값, `Metrics`에서 꺼내는 법) |
| `ZoomChartPanel`, `ZoomWindow` | 큰 차트: 휠 확대·축소, 드래그 이동, 두 번 눌러 초기화, 선 아래 채움, CPU 알림 기준선. `ZoomWindow`는 UI 없는 순수 계산 |
| `HoverSync`, `ChartTooltip` | 세 차트가 마우스가 가리키는 시각을 함께 쓰는 공유 상태와, 세로선·값 상자 그리기 |
| `LiveController` | 실시간 모드 상태(OFF/CONNECTING/RUNNING/PAUSED), 일시정지·재개, 10분 뒤 자동 꺼짐, 남은 시간(`remaining()`) |

### `ui.terminal` — SSH 터미널

| 클래스 | 역할 |
|---|---|
| `TerminalView` | 세션 탭 모음, 새 세션 메뉴 |
| `TerminalPanel` | 탭 하나: 연결, JediTerm 위젯, 상태바, 실패 안내와 "다시 연결" |
| `ShellTtyConnector` | `ssh.ShellSession`을 JediTerm이 쓰는 형태로 이어 주는 어댑터 |
| `TerminalSidePanel` | 오른쪽 패널: 저장된 명령어, 일괄 실행, SFTP 버튼(그 서버의 파일 탭을 연다) |
| `BatchResultDialog`, `CommandEditDialog` | 일괄 실행 결과 / 명령어 편집 |
| `SshSettingsDialog` | 서버별 사용자 이름·포트·SSH 키 등록 |
| `HostKeyDialog` | 처음 보는 호스트 키 확인. `ssh.HostKeyPrompt`를 구현하며 어느 스레드에서든 호출 가능 |

### `ui.files` — 파일 탭

서버 화면의 "파일" 탭이다(예전 SFTP 창을 탭으로 옮겼다). 왼쪽 **내 컴퓨터**, 오른쪽 **서버** 두 창이 카드 하나씩이고, 위에 편집 중 배너, 아래에 전송 목록이 붙는다.

| 클래스 | 역할 |
|---|---|
| `FilesHost` | 서버마다 `FilesTab`을 하나씩 만들어 들고 있다가 보고 있는 서버의 것을 보여 준다. 서버를 바꿨다 돌아와도 목록·전송·편집 상태가 그대로다. 탭은 그 서버의 파일 탭을 처음 열 때 만든다(서버를 고르기만 해서는 접속이 늘지 않는다). SSH 설정이 없거나 서버가 꺼져 있으면 탭 대신 안내와 [SSH 설정]. 앱을 끌 때 `closeAll()`, 목록에서 사라진 서버는 `retainOnly()`로 닫고, 전송 수는 서버 모두 합쳐 상태 막대에 준다 |
| `FilesTab` | 파일 탭 하나. 업로드·다운로드, 끌어서 놓기, 같은 이름 확인, 삭제 확인, 새 폴더·새 파일·이름 바꾸기(F2)·권한 변경, 서버 파일 편집(`EditSession`)을 한다. 두 창은 가운데 줄로 폭을 나누고(처음엔 반반), 아래에 결과 한 줄과 안내 문구, 그 아래에 `TransferPanel`이 있다 |
| `FilePane` | 창 하나(카드). 머리(제목, 위로·새로고침·새 폴더 아이콘, 서버 쪽은 `숨김 파일` 토글), 경로 입력, 칩 줄(`처음 경로`, 준비 중인 `＋ 경로 추가`), 파일 표(이름·크기·수정한 날짜, 서버 쪽은 권한), 선택 작업 막대(`N개 선택` + 버튼, 선택이 있을 때만), 요약 줄("폴더 1개 · 파일 3개 · 지우면 휴지통으로 가요" / "… · ubuntu 권한으로 접속 중")이 한 테두리 안에 든다. 내 컴퓨터와 서버가 같은 모양이고 목록·파일 작업을 하는 `Source`만 다르다. 작업은 EDT 밖에서 하고 한 번에 하나만 받는다. 영어 오류(`PERMISSION_DENIED` 등)는 한국어로 바꿔 보여준다 |
| `EditBanner` | 서버 파일을 편집하는 동안만 위쪽에 나오는 배너: "편집 중 1개 · 경로 — 편집기에서 저장하면 서버에 자동으로 올려요 · 마지막 업로드 15:28" + [편집기 열기] [편집 끝내기]. 글자와 버튼만 가지고, 세션 처리는 `FilesTab`이 한다 |
| `TransferPanel` | 아래 "전송" 목록: 머리에 "1개 진행 중 · 2개 끝남", [끝난 항목 지우기], 접기/펼치기. 행마다 방향 아이콘, 이름과 "서버 → ~/Downloads · 30.4 MB / 48.2 MB · 12.4 MB/s", 얇은 진행 막대(진행 파랑·끝남 초록·실패 빨강), 상태 글자("63% · 2초 남음", "끝남 · …", "실패 · …"), [취소]/[다시 시도]. 접으면 머리 줄만 남고 목록과 진행 중인 전송은 그대로이며, 새 전송이 시작되거나 실패가 생기면 저절로 펼쳐진다. 목록이 비면 패널이 숨는다. 행을 골라 Delete를 누르면 끝난 행 하나를 지운다 |
| `TransferRate` | 전송 속도와 남은 시간을 어림한다(큐는 받은 바이트 수만 준다). 진행을 받을 때마다 이전 값과 비교하고 최근 값에 가중을 둔 평균을 쓴다 |
| `PermissionDialog` | 서버 파일 권한 변경 창. 읽기·쓰기·실행 체크박스 9개와 8진수 입력(644)이 서로 맞춰지고, 폴더는 안의 모든 항목에 적용할 수 있다 |
| `ChangeWatch`, `ExternalEditor` | 서버 파일 편집. 임시 폴더에 받은 복사본을 내 편집기(`ExternalEditor`)로 열고, `ChangeWatch`가 저장이 끝났는지 알아본다 |

### 그 밖의 하위 패키지

| 패키지 | 내용 |
|---|---|
| `ui.containers` | `ContainersPanel`(서버 상세의 컨테이너 표와 동작 버튼), `ContainersTab`(위 표 / 아래 로그를 나누는 탭 본문), `ContainerLogView`(로그 패널), `LogTable`(로그 줄 표), `ContainerLogWindow`(로그 화면 전체를 담는 별도 창), `ContainerFormat`·`ContainerRowActions`(표 글자와 행 작업 아이콘 계산) |
| `ui.components` | 공용 부품: `Buttons`, `DashedButton`, `StatusDot`, `StatusBadge`(종류 5가지: 성공·경고·위험·정보·중립), `TabBar`(밑줄 탭 줄), `StatusBar`(아래 26px 상태 막대), `RoundedPanel`, `Toast`(오른쪽 아래 잠깐 뜨는 알림) 등. 디자인 v2 부품: `ToggleSwitch`(스위치), `FilterChip`(거르기 칩), `SegmentedControl`(구간 선택), `Meter`(사용률 막대), `ComingSoon`(기능이 아직 없는 요소를 비활성 + "준비 중이에요" 안내로 만드는 도구) |

## 파일 탭의 동작 규칙

- **소유자 변경 경고:** 덮어써서 서버 파일의 소유자가 이 계정으로 바뀌면, 전송 하나가 끝났을 때 **한 번만** 경고 창을 띄운다(어떤 파일인지 3개까지, 나머지는 개수로). 권한(rwx)은 유지되지만 소유자는 일반 계정으로 되돌릴 수 없다는 안내를 포함한다.
- **삭제는 신중하게:** 지우기 전에 폴더 안 항목 수를 세어(`COUNT_CAP` 5,000개에서 멈춤) 확인 창에 보여준다. 서버는 "되돌릴 수 없어요"라고 알리고, `/etc` 같은 시스템 폴더에는 따로 경고한다. 이 컴퓨터 쪽은 휴지통으로 보낸다. 폴더를 통째로 지우다 중간에 권한 오류가 나면 일부만 지워질 수 있어서, 오류가 나도 목록을 다시 읽어 실제 상태를 보여준다.
- **서버 파일 편집:** `편집` → 임시 폴더로 받기 → 편집기 열기 → 저장하면 자동으로 서버에 올린다. 올리기 전에 서버 쪽 파일이 그 사이 바뀌었는지(크기·수정 시각) 확인해서, 바뀌었으면 덮어쓸지 묻는다. 저장하는 도중의 파일은 올리지 않는다(`ChangeWatch`). 편집 중인 파일은 위쪽 배너에 보이고 [편집 끝내기]로 놓는다(못 올린 내용이 있으면 먼저 묻고, 진행 중인 업로드는 취소하고, 임시 복사본을 지운다). 앱을 끌 때도 못 올린 편집 내용이나 진행 중인 전송이 있으면 확인한다.
- **연결은 쓸 때만 쥔다:** 탭은 서버별로 계속 살아 있지만 SSH 연결을 계속 쥐고 있지는 않다. 탐색 연결(`ReconnectingFiles`)은 3분 동안 안 쓰면 풀에 돌려주고, 끊겼으면 다음에 쓸 때 다시 맺는다. 전송 연결은 큐가 비면 돌려주고, 전송이 실패하면 버려서 다시 시도할 때 새로 맺는다. 그래서 목록·전송 목록·편집 상태는 남아 있고 풀의 유휴 종료·12시간 수명 규칙도 그대로 적용된다([ssh.md](ssh.md)의 "오래 열어 두는 화면").
- **같은 이름 처리:** 현재 폴더로 보낼 때만 미리 묻는다(덮어쓰기 / 겹치는 항목 건너뛰기 / 취소). 폴더 행에 끌어다 놓은 경우는 덮어쓰지 않고 겹치는 파일을 건너뛴다.

## 흐름 예시

### 모니터링

```
서버 선택     → Async: service.metrics(account, id, 고른 기간)   → MetricsPanel.showHistory (기본 1시간, 1분 점)
기간 선택     → MetricsPanel.onRangeChanged → MainFrame.loadMetrics → 같은 흐름으로 그 기간을 읽는다
                  OCI 간격: 6시간 이하 1분, 24시간 이하 5분, 그 이상 15분
                  직접 연결 서버는 1시간 외 기간 칸이 비활성(앱이 켜져 있는 동안 모은 수집분만)
                  차트를 전체보다 더 축소하면 다음으로 긴 기간으로 넘어간다(`ZoomChartPanel.onZoomOutFull`)
1분 타이머    → 선택 서버의 고른 기간 + service.currentCpu(inventory) → 사이드바 CPU%
[● 실시간]    → MetricsPanel.onLiveToggle(true) → MainFrame.toggleLive → LiveController → TerminalService.openStats
                  (exec: ProcStats.COMMAND, 최대 310회) → LiveStats: '---' 블록마다 파싱 → 이전 스냅샷과 차이
                  → MetricsPanel.addLive (최근 5분) · 안내 문구에 남은 시간("8분 뒤 자동으로 꺼져요")
터미널 화면 / 최소화 → LiveController.pause (연결 닫음) → 대시보드 복귀 → resume (다시 연결, 10분이 새로 시작)
켠 지 10분    → 자동 꺼짐
서버 정보(HostFacts) → ServerScreen.setFacts → 개요의 정보 칸 + 모니터링 탭의 디스크 카드(같은 값)
알림 설정     → MetricsPanel.setAlert → "알림 기준" 카드 + CPU 차트의 기준선(전체 스위치와 "CPU가 높을 때" 둘 다 켜져 있을 때만)
```

### SSH 터미널 열기

```
[SSH 열기] → 설정 없음? → SshSettingsDialog (키 → 금고에 저장)
          → TerminalView 탭 추가 → Async: TerminalService.open(server)
             → MinaShellConnector: 연결 → 호스트 키 확인(known_hosts / HostKeyDialog)
               → 공개키 인증 → shell 채널(PTY xterm-256color)
          → ShellTtyConnector로 JediTerm에 연결 → 창 크기 변경 시 window-change
```
