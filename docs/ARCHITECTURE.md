# 구조

## 계층

```
                 ┌──────────── app (InfraDeskApp: 조립, --demo 분기)
                 ▼
ui/ ─────► service/ ─────► core/ ◄───── provider/oracle/   (OCI SDK는 여기서만)
 │             │             ▲   ◄───── provider/ssh/      (직접 연결: TCP 응답 = 상태)
 │             │             ▲   ◄───── provider/demo/     (가짜 데이터)
 │             └──► storage/ ┘
 └──► ssh/  (MINA SSHD / 데모 셸)
```

- **ui**: Swing 화면. `service`와 `core` 타입만 안다. 네트워크 작업은 `Async`로 EDT 밖에서.
- **service**: 화면이 쓰는 기능 모음. 계정·서버 조회와 제어, 터미널, 업데이트 등.
- **core**: 공통 모델과 인터페이스. 다른 패키지에 의존하지 않는다.
- **provider/<cloud>**: `CloudProvider` 구현. SDK 타입을 `core` 모델로 변환해서 내보낸다.
- **provider/ssh**: 컴퓨터 한 대를 계정 하나로 다룬다. SSH 포트 TCP 접속 여부로 `RUNNING`/`UNREACHABLE`을 내고, 전원 제어와 클라우드 메트릭은 없다.
- **storage**: 계정 설정(JSON), 비밀값(OS 키체인 + 암호화 금고).
- **ssh**: 클라우드와 무관한 SSH 연결·세션·SFTP·`known_hosts` 검증.
- **alert**: 디스코드 알림. 상태 변화와 CPU 기준 초과를 감지해 웹훅으로 보낸다.
- **app**: 진입점. 실제 모드와 데모 모드의 구성 요소를 조립한다.

실행 옵션: `--demo`, `--minimized`, `--debug-input`, `--self-test`, `--migrate-secrets`.

## 핵심 타입

| 타입 | 역할 |
|---|---|
| `core.CloudProvider` | 클라우드 추상화. 계정 1개당 1개, 모든 메서드는 블로킹 |
| `core.CloudProviderFactory` | `Account`와 비밀값으로 provider 생성 |
| `core.Account` / `Server` / `ServerStatus` / `Metrics` | 공통 모델. 상태는 정규화된 enum, `Account`에는 비밀값 없음 |
| `service.InventoryService` | 계정별 병렬 조회(실패는 계정 단위), 제어, 계정 추가·삭제 |
| `service.RefreshPolicy` | 다음 조회 시점과 대상 결정 (평소 45초, 전이·요청 직후 5초) |
| `service.TerminalService` | 서버별 SSH 설정, 셸 열기, 명령 실행, SFTP |
| `ssh.ShellConnector` / `ShellSession` | 셸 열기 / 입출력 (MINA 구현, 데모 구현) |
| `service.UpdateService` / `UpdateInstaller` | 앱 업데이트 확인과 설치 (SHA-256 검증 후 교체) |
| `storage.SecretStore` | 비밀값 저장 |

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
| `known_hosts` | 신뢰한 SSH 호스트 키 (OpenSSH 형식) |

로그: macOS `~/Library/Logs/InfraDesk/infradesk-N.log`, Windows `%LOCALAPPDATA%\InfraDesk\logs` (비밀값은 가림 처리).

비밀값(API 개인키, SSH 개인키·암호, 웹훅 URL)은 **`secrets.vault`**(AES-256-GCM 암호화, 권한 `rw-------`)에 저장되고, 그 **마스터 키 하나만** OS 키체인(서비스 이름 `InfraDesk`, 항목 `vault.masterKey`)에 있다. 키체인은 실행당 최대 한 번 읽는다.

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
          → 해당 계정 즉시 재조회
          → 5초 주기로 전이 상태 추적 (요청 후 20초 또는 전이 상태인 동안)
          → 안정 상태가 되면 45초 전체 조회로 복귀
```

## SSH 연결 흐름

```
[SSH 열기] → 설정 없음? → SshSettingsDialog (키 → 금고)
          → 터미널 탭 추가 → Async: TerminalService.open(server)
             → connect → 호스트 키 확인(known_hosts / 확인 창)
               → 공개키 인증 → shell 채널(PTY) → JediTerm에 연결
```

## 모니터링 흐름

```
서버 선택 → 최근 1시간 메트릭 (1분 간격) → 그래프
카드 클릭 → 기간별 조회 → 확대 차트 (6시간 이하 1분, 24시간 이하 5분, 그 이상 15분)
1분 타이머 → 선택 서버 메트릭 + 사이드바 CPU%
[실시간 (SSH)] → exec로 /proc 스냅샷 반복 → 이전 스냅샷과 차이로 사용률 계산 → 그래프
             (터미널 화면에서는 연결을 닫고 복귀 시 재연결, 10분 후 자동 꺼짐)
```

## 알림 흐름

```
서버 목록 갱신 → 상태 변화 비교          ┐
1분 CPU 조회   → 연속 N회 기준 초과 확인  ├→ 전송 스레드 → 디스코드 웹훅 (데모: Toast 미리보기)
정지·재부팅 요청 → 해당 서버 15분 제외     ┘
```

## 패키징

```
installDist → appImage (jpackage + jlink) → 자가 점검(--self-test)
           → macOS: dmg + 업데이트용 zip / Windows: zip
```
- JVM 옵션: `build.gradle.kts`의 `appJvmArgs`. `run`·`runDemo`·jpackage가 함께 쓴다. 근거는 DEVLOG "메모리 줄이기".
- 아이콘: `src/packaging/macos/InfraDesk.icns`, `src/packaging/windows/InfraDesk.ico`
- CI: `main` 푸시 → macOS·Windows에서 테스트와 패키징 → `beta` 시험판 릴리스 갱신. 세부는 `.github/workflows/build.yml`.
