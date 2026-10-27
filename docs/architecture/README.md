# InfraDesk 구조 한눈에 보기

이 문서만 읽어도 "코드가 어떻게 나뉘어 있고, 버튼 하나를 누르면 어디를 거쳐 가는지" 알 수 있게 쓴다.
세부 내용은 모듈별 문서에 있다.

## 앱이 하는 일

여러 곳에 흩어진 서버(Oracle Cloud 계정 여러 개, 집 서버 같은 직접 연결 기기)를 **한 창**에서 본다.

- 서버가 켜져 있는지 보기 (주기적으로 확인)
- 시작 / 정지 / 재부팅
- 서버에 SSH로 들어가는 터미널, SFTP, Docker 컨테이너 관리
- CPU·메모리·네트워크 그래프

## 모듈 일곱 개

코드는 `com.infradesk` 아래 패키지로 나뉜다. 각 모듈이 **맡은 일**과 **일부러 모르는 것**을 같이 적었다. 모르는 게 많을수록 바꾸기 쉽다.

| 모듈 | 맡은 일 | 모르는 것 | 문서 |
|---|---|---|---|
| `ui` | 화면을 그리고 버튼 입력을 받는다 | 클라우드가 뭔지, OCI 같은 SDK 타입 | [ui.md](ui.md) |
| `service` | "서버 목록 가져오기", "정지하기" 같은 **업무**를 한다. 화면과 나머지 모듈 사이의 중간 다리 | 화면이 어떻게 생겼는지, OCI가 어떻게 통신하는지 | [service.md](service.md) |
| `core` | 모두가 공유하는 **공통 말**: `Server`, `ServerStatus`, `Account`, `CloudProvider` 같은 모델과 인터페이스 | 다른 모듈 전부 (아무것도 import하지 않는다) | [core.md](core.md) |
| `provider` | 클라우드(또는 직접 연결 기기)와 실제로 통신해서 `core`의 말로 번역한다 | 화면, 저장소 | [provider.md](provider.md) |
| `ssh` | 서버에 SSH로 접속해서 셸·명령·파일(SFTP)을 다룬다 | 서버가 어느 클라우드에 있는지 | [ssh.md](ssh.md) |
| `storage` | 설정 파일(JSON)과 비밀값(암호화 금고)을 읽고 쓴다 | 그 값이 무슨 뜻인지 | [storage.md](storage.md) |
| `alert` | 서버 상태 변화를 감지해서 디스코드로 알린다 (현재 꺼져 있음) | 화면 | [alert.md](alert.md) |

이 밖에 `demo`(데모 모드용 가짜 부품을 모아 둔 패키지. [demo.md](demo.md))와 `app`이 있다. 시작할 때 위 모듈들을 **조립**하는 `main`이다. 어떤 저장소·provider·SSH 연결을 쓸지는 여기서 정한다. 다만 `MainFrame`이 `ContainerService`와 `SshMetricsService`를 직접 만드는 예외가 있다.

### `app`에 있는 것

| 클래스 | 역할 |
|---|---|
| `InfraDeskApp` | 진입점. 옵션을 읽고 부품을 조립해 `MainFrame`을 띄운다 |
| `Logging` | 로그 파일 설정, 비밀값 가림 |
| `BuildInfo` | 버전·빌드 번호·커밋 |
| `AppIcon` | Dock/작업 표시줄/창 아이콘 |
| `LaunchAtLogin` | 로그인할 때 자동 실행 |
| `SelfTest` | `--self-test`: 패키징된 앱이 제대로 뜨는지 점검 |
| `InputDiagnostics` | `--debug-input`: 키 입력 진단 |

## 의존 방향

화살표는 "~를 import한다"는 뜻이다. 소스의 import 문을 세어서 그렸다.

| 모듈 | import하는 모듈 |
|---|---|
| `core` | 없음 |
| `ssh` | 없음 |
| `provider` | `core`만 |
| `storage` | `core`, `ssh`(SSH 설정 모델), `alert`(알림 설정 모델) |
| `service` | `core`, `ssh`, `storage`, `demo`(데모 모드의 가짜 로컬 파일 한 곳) |
| `alert` | `core`, `service`, `storage` |
| `ui` | `core`, `service`, `ssh`, `alert` |
| `demo` | `core`, `ssh` (인터페이스를 구현할 뿐, 서로 다른 모듈은 `demo`를 모른다. 단 `app`과 `service/TerminalService`가 쓴다) |
| `app` | 전부 (조립하는 곳) |

위에서 아래로 읽으면 대체로 "의존이 적은 모듈 → 많은 모듈" 순서다. `core`와 `ssh`는 아무것도 모르니 가장 안전하게 읽을 수 있다.

핵심 규칙은 둘이다.

1. **`service`와 `provider`는 서로 모른다.** `service`는 `core.CloudProvider` 인터페이스만 부르고, 어떤 구현체를 쓸지는 `app`이 `ProviderRegistry`에 등록해서 정한다(`ProviderType.ORACLE` → `OracleProviderFactory`). 그래서 AWS를 추가해도 `ui`와 `service`는 바뀌지 않는다.
2. **OCI SDK 타입은 `provider/oracle` 밖으로 못 나간다.** 나갈 때는 `Server`, `ServerStatus` 같은 `core` 타입으로 번역된다.

**알려진 어색한 점:** `alert`와 `storage`가 서로를 import한다(`AlertSettings`는 `alert`에, 그 저장소 `AlertSettingsStore`는 `storage`에 있기 때문). `storage`도 SSH 설정·저장된 명령어 모델을 `ssh`에서 가져온다. 모델 클래스가 쓰는 쪽 패키지에 있어서 생긴 일이고, 지금 동작에 문제는 없다. 나중에 모델을 `core`로 옮기면 정리된다.

## 헷갈리기 쉬운 것: `ssh`가 두 개다

| | 하는 일 | 언제 쓰나 |
|---|---|---|
| `provider/ssh` | 서버의 SSH 포트에 **TCP로 접속이 되는지만** 확인한다. 되면 `RUNNING`, 안 되면 `UNREACHABLE` | 클라우드 API가 없는 **직접 연결 기기**(집 서버 등)의 상태 표시 |
| `ssh` | 진짜 SSH 접속. 로그인해서 셸, 명령 실행, SFTP | 터미널, 컨테이너 관리, 실시간 모니터링 |

이름이 같은 이유는 "SSH로 닿는 기기"라는 같은 대상을 다루기 때문이다. 하지만 `provider/ssh`는 `CloudProvider`를 구현한 **상태 확인용**이고, `ssh`는 **접속용**이다. 서로 코드를 공유하지 않는다.

## 예시로 따라가기: [정지] 버튼을 누르면

서버 상세 화면의 [정지]를 눌렀을 때 일어나는 일이다. 어느 모듈이 무엇을 하는지 순서대로 본다.

```
 ① ui       MainFrame.runAction(STOP)
 │            └ 정지는 위험한 동작이라 "정말 정지할까요?" 확인 창을 띄운다
 ▼
 ② ui       Async.run(...)  ← 별도 스레드에서 실행
 │            └ 네트워크를 기다리는 동안 화면이 얼어붙으면 안 되기 때문
 ▼
 ③ service  InventoryService.control(account, 서버id, STOP)
 │            └ 이 계정용 CloudProvider를 꺼낸다 (없으면 ProviderRegistry가 만든다)
 ▼
 ④ provider OracleProvider.stop(서버id)
 │            └ OCI SDK로 "SOFTSTOP" 요청을 보낸다
 ▼
 ⑤ 다시 ui   요청이 받아들여지면 화면 스레드로 돌아온다
 │            └ "정지 요청을 보냈어요" 표시, RefreshPolicy에 "이 계정에 동작을 보냈다"고 알림
 ▼
 ⑥ service  해당 계정만 즉시 다시 조회 → OracleProvider.listServers()
 │            └ OCI의 Instance를 core.Server로 번역 (상태 문자열 → ServerStatus)
 ▼
 ⑦ ui       화면 갱신. 상태가 "정지 중"이면 5초 뒤에 다시 조회, 안정되면 30초 주기로 복귀
```

여기서 볼 점:

- **① → ⑤까지 `ui`는 OCI를 한 번도 언급하지 않는다.** `service.control(...)`만 부른다.
- **③에서 `service`는 OCI도 모른다.** `CloudProvider`라는 인터페이스의 `stop()`만 부른다. 계정이 AWS였다면 ④만 `AwsProvider`로 바뀐다.
- **④만 OCI SDK를 쓴다.** 결과는 항상 `core` 타입으로 돌려준다.
- **②의 별도 스레드가 중요하다.** Swing은 화면을 그리는 스레드(EDT) 하나로 돌아가는데, 여기서 네트워크를 기다리면 창이 멈춘다. 그래서 네트워크 호출은 모두 `Async.run(작업, 성공 시, 실패 시)`를 거친다. "성공 시/실패 시" 부분은 EDT로 돌아와서 실행된다.

## 용어

| 용어 | 뜻 |
|---|---|
| EDT | Swing의 화면 전용 스레드. 화면을 건드리는 코드는 여기서만, 오래 걸리는 일은 여기서 하면 안 된다 |
| Provider | 서버가 사는 곳(Oracle, AWS, 직접 연결)과 통신하는 객체. 계정 하나당 하나 |
| 인벤토리 | 계정별로 조회한 서버 목록 (`AccountInventory`: 계정 + 서버들 + 실패했으면 오류) |
| PTY | 터미널처럼 동작하는 셸. 화면 크기를 알고 색과 커서 이동을 지원한다 |
| 금고 (vault) | 모든 비밀값을 암호화해서 넣어 둔 파일 하나. 열쇠 하나만 OS 키체인에 있다 |

## 실행 옵션

`--minimized`(메뉴 막대에서만 시작), `--debug-input`, `--self-test`, `--migrate-secrets`

빌드·실행·패키징은 [../BUILD.md](../BUILD.md).
