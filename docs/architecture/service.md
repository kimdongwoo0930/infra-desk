# service: 화면과 나머지 사이의 업무 계층

[← 개요](README.md)

## 왜 있나

화면 코드가 "계정에서 비밀값을 꺼내서 provider를 만들고 서버 목록을 가져와서…"까지 하면 화면이 너무 많은 것을 알게 된다. `service`는 그 **업무 단위**를 맡는다. 화면은 `inventory.load()`, `terminal.open()`처럼 업무 이름만 부르면 된다.

- 모든 메서드가 네트워크나 파일을 기다릴 수 있다(블로킹). **EDT에서 부르지 말고 `Async.run`으로 감싼다.**
- `service`는 `provider`를 import하지 않는다. `core.CloudProvider` 인터페이스만 쓰고, 구현체는 `app`이 `ProviderRegistry`에 넣어 준다.

## 서비스가 둘로 나뉜 이유

| 갈래 | 무엇을 | 클라우드 관련? |
|---|---|---|
| **인벤토리** (`InventoryService`) | 계정 관리, 서버 목록, 시작/정지/재부팅, 메트릭 | 예. `CloudProvider`를 통한다 |
| **터미널** (`TerminalService` 등) | SSH 설정, 셸 열기, 명령 실행, SFTP, Docker | 아니오. 서버의 IP만 있으면 된다 |

클라우드가 AWS로 바뀌어도 SSH 쪽은 그대로다. 이 둘을 섞지 않는 것이 설계 원칙이다.

## 인벤토리 쪽

### `InventoryService` (가장 중요)
계정과 provider를 관리한다.

- `loadAll()` / `load(accounts)`: 계정별로 **병렬** 조회한다(가상 스레드). 한 계정이 실패해도 다른 계정은 영향이 없고, 실패는 `AccountInventory.error`에 담겨 돌아온다. 예외로 던지지 않는다.
- `control(account, serverId, action)`: 시작/정지/재부팅. provider가 요청을 받아들이면 반환한다.
- `metrics(...)`, `currentCpu(...)`: 그래프와 사이드바 CPU.
- `provider(account)`: 계정당 하나씩 만들어서 캐시한다. 처음 요청할 때 금고에서 비밀값을 꺼내 `ProviderRegistry`로 만든다.
- `addAccount` / `updateAccount` / `removeAccount`: 계정 설정은 `AccountStore`에, 비밀값은 `SecretStore`에 따로 저장하고 캐시된 provider를 닫는다.
- `testConnection` / `preview`: 아직 저장하지 않은 설정으로 연결해 보기(계정 추가 화면의 "연결 테스트").

### 주변 클래스

| 클래스 | 역할 |
|---|---|
| `ProviderRegistry` | `ProviderType` → `CloudProviderFactory` 표. `Account.provider()`로 어떤 구현을 쓸지 고른다 |
| `AccountInventory` | 계정 + 그 서버들 + (실패했다면) 오류 메시지 |
| `ServerAction` | `START`, `STOP`, `REBOOT`. 확인 창이 필요한 동작인지(`needsConfirmation`)도 들고 있다. 정지·재부팅만 확인을 받는다 |
| `AccountValidation` | 계정 추가 폼의 형식 검사 (한국어 메시지) |
| `RefreshPolicy` | 다음에 언제, 어느 계정을 다시 조회할지 결정 (아래) |

### `RefreshPolicy`: 얼마나 자주 조회하나

OCI API 요청 제한을 지키려고 평소엔 느리게, 필요할 때만 빠르게 조회한다.

| 상황 | 주기 | 대상 |
|---|---|---|
| 평소 | **30초** | 모든 계정 |
| 서버가 시작·정지 중이거나 방금 동작을 보냈다 | **5초** | 해당 계정만 |
| 동작을 보낸 뒤 상태가 아직 안 바뀌어 보일 때 | 20초 동안 빠른 모드 유지 | 해당 계정만 |
| 빠른 모드가 너무 길어질 때 | 10분 뒤에는 빠른 모드 중단 | |

`MainFrame`이 타이머를 돌리면서 `policy.next(인벤토리)`를 물어본다. `RefreshPolicy`는 시계(`Clock`)를 주입받는 순수 로직이라 단위 테스트가 쉽다.

## 터미널 쪽

| 클래스 | 역할 |
|---|---|
| `TerminalService` | 서버별 SSH 설정·키 저장, 셸 열기(`open`), 명령 한 번 실행(`run`: 제한 시간·출력 상한), SFTP 열기(`openFiles`), 이 컴퓨터 폴더(`localFiles`), 저장된 명령어, 서버 정보 읽기(`facts`). 실제 SSH 연결은 `ssh.ShellConnector`에 맡긴다 |
| `ContainerService` | 컨테이너 목록·시작/정지/재시작·로그. `TerminalService.run`으로 `docker` 명령을 실행한다 |
| `LiveStats` | 실시간 모드. SSH exec 세션의 출력을 읽어 2초마다 `ProcStats.Sample`을 알린다 |
| `SshMetricsService` | 클라우드 메트릭이 없는 직접 연결 서버용. 분당 한 번 짧은 SSH 명령으로 CPU·메모리를 재서 최근 1시간을 메모리에 보관한다. 접속 횟수는 `SamplingGate`가 제한한다(아래) |

`TerminalService.target(server)`가 SSH 접속 정보(`SshTarget`: 호스트, 포트, 사용자, 개인키)를 만든다. 호스트는 서버의 공인 IP, 사용자·포트는 `SshSettingsStore`, 개인키는 `SecretStore`에서 가져온다. 하나라도 없으면 한국어 안내 메시지가 담긴 `SshException`을 던진다.

### 자동 SSH 접속 규칙 (`SamplingGate`)

SSH 접속은 서버의 인증 로그에 남고, 인증이 계속 실패하면 서버의 보안 도구(fail2ban 등)가 이쪽 IP를 차단할 수 있다. 사용자가 시키지 않았는데 앱이 스스로 접속하는 작업(직접 연결 서버의 분당 측정)은 아래 규칙을 지킨다. 시계만 있으면 되는 순수 로직이라 `SamplingGateTest`로 검증한다.

| 상황 | 동작 |
|---|---|
| 호스트 키를 믿지 않음 / 호스트 키가 바뀜 / 키 파일을 못 읽음 | **첫 실패에 자동 접속을 멈춘다.** 사용자가 고치기 전에는 몇 번을 시도해도 성공할 수 없다 |
| 인증 실패 | 첫 실패는 2분 기다리고, **두 번 연속이면 멈춘다** |
| 연결 끊김, 시간 초과, 결과를 못 읽음 | 멈추지 않고 점점 길게 기다린다: 2분 → 5분 → 15분(이후 15분) |
| 앱 창이 숨어 있거나 최소화됨 | 서버당 **5분에 한 번**만 접속 |
| 어떤 경우든 | 같은 서버에 **15초 안에 두 번** 접속하지 않는다(여러 곳에서 동시에 요청해도 한쪽만 통과) |
| 성공 | 실패 기록을 모두 지운다 |

- **멈춘 서버는 화면에 이유가 나온다**("SSH 키나 사용자 이름이 맞지 않아요" 등). 처음 멈출 때 로그에 한 번만 남긴다.
- **다시 시도하는 때:** SSH 설정을 저장했을 때, 사용자가 직접 새로고침했을 때, 서버 정보를 SSH로 읽는 데 성공했을 때. 새로고침을 연타해도 15초 간격은 지킨다.
- 사용자가 직접 연 터미널·SFTP·컨테이너 목록 같은 접속은 이 규칙의 대상이 아니다(사용자가 시킨 일이다).

## 앱 업데이트

| 클래스 | 역할 |
|---|---|
| `UpdateService` | GitHub `beta` 릴리스에서 최신 빌드 번호·다운로드·`SHA256SUMS.txt` 주소 확인, 자동 확인 설정 저장 |
| `UpdateInstaller` | 앱 안 업데이트: 받기(리디렉트 따라감) → SHA-256 대조 → 앱 옆 `.infradesk-update`에 풀기(macOS `ditto`) → 새 앱 `--self-test` → `update-swap.sh`/`.ps1`가 앱 종료를 기다려 교체하고 다시 실행(실패하면 원래 앱 복원) |
