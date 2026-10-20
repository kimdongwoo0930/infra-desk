# provider: 클라우드와 실제로 통신하는 곳

[← 개요](README.md)

## 왜 있나

`core.CloudProvider`는 "서버 목록 줘", "정지해줘"라는 **약속**만 정한다. 실제로 OCI에 HTTP 요청을 보내고 응답을 번역하는 일은 `provider` 아래 구현체가 한다. 구현체는 두 가지다.

```
provider/
  oracle/   OCI Java SDK로 Oracle Cloud와 통신
  ssh/      SSH 포트에 TCP로 접속해 보고 상태만 판단 (직접 연결 기기용)
```

모두 `core`만 import한다. 화면(`ui`)이나 저장소(`storage`)를 모른다.

## 규칙

1. **SDK 타입은 이 패키지 안에서만 쓴다.** OCI의 `Instance`는 `OracleMapper`가 `core.Server`로 바꾼 뒤에야 밖으로 나간다.
2. **상태 문자열은 `ServerStatus`로 정규화한다.**
3. **한 `CloudProvider` 인스턴스는 계정 하나를 담당한다.** 만드는 일은 `CloudProviderFactory`가 하고, 어떤 팩토리를 쓸지는 `service.ProviderRegistry`가 `ProviderType`을 보고 고른다.

## provider/oracle

| 클래스 | 역할 |
|---|---|
| `OracleProviderFactory` | 계정 속성(테넌시·사용자 OCID, fingerprint)과 API 개인키로 SDK 클라이언트를 만든다 |
| `OracleProvider` | `CloudProvider` 구현. 인스턴스 조회·전원 동작·메트릭 |
| `OracleMapper` | OCI의 `Instance` 등 → `core.Server`, `ServerStatus` 변환 |
| `OracleMetrics` | OCI Monitoring에서 CPU·메모리·네트워크 읽기 (OCI는 1분 단위로 집계) |
| `OracleProperties` | `Account.properties`에서 쓰는 키 이름 |

### OCI → InfraDesk 대응표

| OCI | InfraDesk |
|---|---|
| Instance `Provisioning/Starting/Running/Stopping/Stopped/Terminating/Terminated` | 같은 이름의 `ServerStatus` |
| `Moving`, `CreatingImage` | `RUNNING` |
| 정지 / 재부팅 | `SOFTSTOP` / `SOFTRESET` |
| CPU / 메모리 | `oci_computeagent` `CpuUtilization` / `MemoryUtilization` `[1m].mean()` |
| 네트워크 | `NetworksBytesIn` / `NetworksBytesOut` `[1m].rate()` |
| 사이드바 CPU | `CpuUtilization[1m].groupBy(resourceId).mean()` 계정당 1회 |
| 401 / 403·404 / 409 / 429 | 인증 실패 / 권한·OCID 확인 / 다른 작업 중 / 요청 한도 |

## provider/ssh

클라우드 API가 없는 기기(집 서버, Tailscale 위의 Mac mini)를 "서버"로 보여 주기 위한 provider다.

- `SshHostProvider.listServers()`는 호스트·포트에 TCP 접속이 되면 `RUNNING`, 안 되면 `UNREACHABLE`인 서버 한 대를 돌려준다.
- `start`/`stop`/`reboot`는 지원하지 않아서 예외를 던진다. 그래서 UI는 `ProviderType.hasPowerControl()`을 보고 버튼을 숨긴다.
- 클라우드 메트릭도 없다(`Metrics.EMPTY`). 대신 `service.SshMetricsService`와 실시간 모드가 SSH로 직접 읽는다 → [service.md](service.md)
- 주소·포트는 `core.SshHostProperties`, 사용자·키는 기존 SSH 설정.
- **진짜 SSH 접속은 여기서 하지 않는다.** 터미널 등은 [ssh.md](ssh.md)의 `ssh` 패키지가 한다.

## 새 클라우드(AWS 등)를 추가하려면

1. `provider/aws/`에 `AwsProvider`(CloudProvider 구현)와 `AwsProviderFactory`를 만든다.
2. AWS 상태 문자열 → `ServerStatus` 변환을 `AwsMapper`에 둔다.
3. `app/InfraDeskApp.realService`에서 `registry.register(ProviderType.AWS, new AwsProviderFactory())`를 추가하고, `ProviderType.AWS`의 `supported`를 `true`로 바꾼다.

`ui`와 `service`는 고칠 필요가 없다.
