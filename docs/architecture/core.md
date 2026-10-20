# core: 모두가 쓰는 공통 말

[← 개요](README.md)

## 왜 있나

`ui`, `service`, `provider`가 서로 직접 대화하면 "OCI의 Instance가 UI까지 올라오는" 일이 생긴다. `core`는 그걸 막으려고 **중간 언어**를 정해 둔 곳이다. OCI든 AWS든 직접 연결 기기든 결과는 모두 `core`의 타입으로 번역되어 올라온다.

`core`는 아무 모듈도 import하지 않는다. 그래서 다른 모듈이 바뀌어도 영향받지 않는다.

## 알아야 할 것 다섯 개

```
Account ─(provider 필드)─► ProviderType ─► CloudProviderFactory ─► CloudProvider
                                                                      │
                                                  listServers() ──► Server (status: ServerStatus)
                                                  getMetrics()  ──► Metrics
```

| 타입 | 한 줄 설명 |
|---|---|
| `Account` | 계정 하나. id, 표시 이름, `ProviderType`, 리전, 비밀 아닌 설정(`properties`). **비밀값은 들어 있지 않다** |
| `ProviderType` | `ORACLE`, `AWS`, `GCP`, `SSH`. 계정이 어디 소속인지. 이 값으로 어떤 `CloudProvider` 구현을 쓸지 고른다. 전원 제어·클라우드 메트릭이 있는지(`hasPowerControl`, `hasCloudMetrics`)도 여기서 알려줘서 UI가 버튼을 숨긴다 |
| `CloudProvider` | **가장 중요한 인터페이스.** 계정 하나와 대화하는 창구. `listServers`, `start`, `stop`, `reboot`, `getMetrics`, `currentCpu`, `close`. 모든 메서드가 네트워크를 기다리므로 EDT에서 부르면 안 된다 |
| `Server` | 서버 하나의 공통 모양(id, 이름, 상태, 리전, 사양, 공인·사설 IP 등) |
| `ServerStatus` | 클라우드마다 다른 상태 문자열을 하나로 맞춘 enum: `RUNNING`, `STOPPED`, `STARTING`, `STOPPING`, `REBOOTING`, `UNREACHABLE`(직접 연결 기기가 응답 없음), `UNKNOWN` 등 |

`CloudProvider`의 메트릭 메서드는 둘이다. `getMetrics(id)`는 최근 1시간(1분 단위), `getMetrics(id, range)`는 기간별 조회다. 긴 기록을 가진 provider만 `range`를 재정의하고, 기본 구현은 `range`를 무시한다. `currentCpu(servers)`는 사이드바의 CPU%용인데, 기본 구현은 서버마다 `getMetrics`를 부른다. 한 번에 가져올 수 있는 provider(OCI)는 재정의한다.

`ServerStatus`에는 UI와 폴링이 쓰는 판단도 들어 있다. `isTransitional()`(시작·정지처럼 변하는 중인가), `canStart()`, `canStop()`, `canReboot()`.

## 그 밖의 타입

| 타입 | 역할 |
|---|---|
| `CloudProviderFactory` | `create(Account, secrets)`로 `CloudProvider`를 만들고, `regions()`로 고를 수 있는 리전을 알려준다 |
| `Metrics` | 서버 하나의 CPU%, 메모리%, 네트워크 in/out 시계열. 비어 있을 수 있다 |
| `AccountSecrets` | 계정별 비밀값의 **이름**(예: `PRIVATE_KEY`). 값 자체는 `storage`의 금고에 있다 |
| `SshHostProperties` | `ProviderType.SSH` 계정의 주소·포트를 `Account.properties`에서 꺼내는 도우미 |
| `CloudProviderException` | provider에서 난 오류를 감싼 공통 예외. 메시지는 사용자에게 그대로 보여줄 한국어 |

## 규칙

- `core`는 외부 SDK 타입(OCI 등)을 갖지 않는다.
- 새 상태나 새 클라우드를 지원할 때는 이쪽 enum/인터페이스를 먼저 확인한다.
