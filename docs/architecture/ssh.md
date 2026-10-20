# ssh: 서버에 SSH로 들어가는 계층

[← 개요](README.md)

## 왜 있나

터미널, SFTP, Docker 관리, 실시간 모니터링은 모두 "서버에 SSH로 로그인해서 뭔가 하기"다. 이 일은 **서버가 Oracle에 있든 집에 있든 똑같다.** 그래서 `ssh`는 클라우드(`core.CloudProvider`)와 완전히 분리되어 있고, 다른 `com.infradesk` 모듈을 아무것도 import하지 않는다.

> `provider/ssh`와 헷갈리지 말 것. 그쪽은 포트가 열려 있는지만 확인하는 상태 표시용이다 → [개요](README.md#헷갈리기-쉬운-것-ssh가-두-개다)

## 구조: 인터페이스 + 구현

```
ShellConnector (인터페이스)           ShellSession (열린 연결)
 └─ MinaShellConnector                 └─ MINA SSHD 채널
```

`ShellConnector`가 SSH 연결의 입구다. 네 가지를 할 수 있다.

| 메서드 | 하는 일 | 쓰는 곳 |
|---|---|---|
| `open(target, prompt, cols, rows)` | PTY가 있는 **대화형 셸** | 터미널 탭 |
| `open(..., command)` | 같은 PTY로 **명령 하나**를 실행(`docker exec -it`) | 컨테이너 셸 탭 |
| `exec(target, prompt, command)` | PTY 없이 명령을 실행하고 출력 읽기 | 일괄 실행, 컨테이너 목록, 실시간 모니터링 |
| `sftp(target, prompt)` | 파일 목록·업로드·다운로드 (`RemoteFiles`) | SFTP 창 |

`service.TerminalService`가 이 인터페이스를 쓰고, 어떤 구현을 쓸지는 `app`이 정한다.

## 접속 흐름 (`MinaShellConnector`)

```
SshTarget(호스트, 포트, 사용자, 개인키)
   │
   ├─ 1. TCP 연결
   ├─ 2. 호스트 키 확인
   │      known_hosts에 있다 → 일치하면 통과, 다르면 거부(HOST_KEY_CHANGED)
   │      처음 본다         → HostKeyPrompt로 사용자에게 묻고, 신뢰하면 known_hosts에 저장
   ├─ 3. 공개키 인증 (개인키, 있으면 암호도)
   └─ 4. 셸 채널 열기 (PTY xterm-256color)
```

`HostKeyPrompt`는 "이 서버 지문을 신뢰할까요?"를 묻는 **콜백 인터페이스**다. `ssh`는 화면을 모르기 때문에 질문을 UI에 위임한다(실제 구현은 `ui.terminal.HostKeyDialog`). 어느 스레드에서든 호출될 수 있다.

실패는 `SshException`으로 올라온다. 종류(`CONNECT`, `AUTH`, `KEY_FORMAT`, `HOST_KEY_REJECTED`, `HOST_KEY_CHANGED`)와 사용자에게 보여줄 한국어 메시지가 담겨 있다.

## 클래스 안내

### 접속 정보와 설정

| 클래스 | 역할 |
|---|---|
| `SshTarget` | 어디에 어떻게 연결할지(호스트, 포트, 사용자, 개인키, 암호). 개인키는 연결하는 동안만 메모리에 두고 로그에 남기지 않는다 |
| `SshSettings` | 서버 하나의 비밀이 아닌 설정(서버 id, 사용자, 포트). 개인키는 `storage`의 금고에 있다 |
| `SshConfig` | `~/.ssh/config`를 읽어 공인 IP → Host → 사용자·포트·키 파일을 추천한다 |
| `SavedCommand` | 터미널 사이드 패널의 "저장된 명령어" 버튼 하나 |

### 연결과 실행

| 클래스 | 역할 |
|---|---|
| `ShellConnector`, `ShellSession` | 위에서 설명한 입구와 열린 연결 (입출력 스트림, 창 크기 조정, 종료 대기) |
| `ExecResult` | 명령 실행 결과(종료 코드, 출력, 실패 메시지) |
| `HostKeyPrompt` | 처음 보는 호스트 키 확인 콜백 |
| `SshException` | 종류별 SSH 실패 |

### 서버에서 읽어 오는 것

| 클래스 | 역할 |
|---|---|
| `HostFacts` | SSH 한 번으로 업타임·OS·루트 디스크·열린 포트 읽기 (서버 상세의 정보 칸) |
| `ProcStats` | 원격 `/proc` 스냅샷 명령, 파싱, 두 스냅샷 사이 사용률 계산 (실시간 모드) |
| `DockerCommands`, `Container` | SSH로 실행할 Docker 명령(컨테이너 ID는 16진수만 허용)과 결과 파싱. `docker exec -it`로 컨테이너 셸·DB 콘솔도 연다 |
| `RemoteFiles`, `RemoteFile`, `Transfers` | SFTP 목록·업로드·다운로드·삭제와 진행률 |
| `LocalFiles` | 이 컴퓨터의 폴더 목록(SFTP 창의 왼쪽 패널). 서버 쪽과 같은 표를 쓰도록 항목을 `RemoteFile` 모양으로 돌려준다. OS의 경로 표기를 그대로 쓴다 |

## 보안 규칙

- Docker TCP 포트(2375)는 절대 열지 않는다. 모든 Docker 작업은 SSH 안에서 `docker` 명령으로 한다.
- 개인키·암호는 로그에 남기지 않는다.
- 호스트 키가 바뀌면 연결을 거부한다.
