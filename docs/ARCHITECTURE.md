# 구조

## 계층

```
                 ┌──────────── app (InfraDeskApp: 조립, --demo 분기)
                 ▼
ui/ ─────► service/ ─────► core/ ◄───── provider/oracle/   (OCI SDK는 여기서만)
 │             │             ▲   ◄───── provider/demo/     (가짜 데이터)
 │             └──► storage/ ┘
 └──► ssh/  (4단계)
```

- **ui**: Swing 화면. `service`와 `core` 타입만 안다. 네트워크 작업은 `Async`로 EDT 밖에서.
- **service**: `InventoryService`(계정·비밀값·provider 관리, 서버 조회), `ProviderRegistry`(`ProviderType` → 팩토리), `AccountValidation`.
- **core**: 공통 모델과 인터페이스. 다른 패키지에 의존하지 않는다.
- **provider/<cloud>**: `CloudProvider`·`CloudProviderFactory` 구현. SDK 타입을 `core` 모델로 변환해서 내보낸다.
- **storage**: 계정 설정(JSON), 비밀값(OS 키체인).
- **ssh**: 클라우드와 무관한 SSH 연결·세션 (4단계).
- **app**: 진입점. 실제 모드와 데모 모드의 구성 요소를 조립한다.

## 실제 모드 vs 데모 모드

| | 실제 (`./gradlew run`) | 데모 (`./gradlew runDemo`) |
|---|---|---|
| 계정 저장 | `JsonAccountStore` | `InMemoryAccountStore` (가짜 계정 3개로 시작) |
| 비밀값 | `KeychainSecretStore` | `InMemorySecretStore` |
| provider | `OracleProviderFactory` | `DemoProviderFactory` (모든 ProviderType) |
| 네트워크 | OCI API | 없음 (지연 시간만 흉내) |

## 핵심 타입

| 타입 | 역할 |
|---|---|
| `core.CloudProvider` | `listServers`, `start`, `stop`, `reboot`, `getMetrics`, `close`. 계정 1개당 1개. 모든 메서드는 블로킹 |
| `core.CloudProviderFactory` | `create(Account, secrets)`, `regions()` |
| `core.Account` | id, 표시 이름, `ProviderType`, 리전, `properties`(비밀 아닌 설정). 비밀값 없음 |
| `core.Server` / `ServerStatus` / `Metrics` | 공통 모델. 상태는 정규화된 enum |
| `core.CloudProviderException` | SDK 예외를 감싼 공통 예외. 메시지는 사용자에게 보여줄 한국어 |
| `service.InventoryService` | `loadAll()` / `load(accounts)`(계정별 병렬, 실패는 계정 단위로 `AccountInventory.error`), `control`, `addAccount`, `removeAccount`, `testConnection` |
| `service.ServerAction` | `START`, `STOP`, `REBOOT`. 확인 창이 필요한지(`needsConfirmation`) 포함 |
| `service.RefreshPolicy` | 다음 조회 시점과 대상 계정 결정 (평소 45초 전체, 전이·요청 직후 5초 해당 계정만) |
| `storage.SecretStore` | 키: `account.<accountId>.<secretName>` (예: `privateKey`) |

## 저장 위치

| OS | 설정 디렉터리 |
|---|---|
| macOS | `~/Library/Application Support/InfraDesk/accounts.json` |
| Windows | `%APPDATA%\InfraDesk\accounts.json` |
| 기타 | `$XDG_CONFIG_HOME/infradesk` 또는 `~/.config/infradesk` |

API 개인키는 파일이 아니라 OS 키체인(서비스 이름 `InfraDesk`)에 저장된다.

## OCI 매핑

| OCI | InfraDesk |
|---|---|
| Instance `Provisioning/Starting/Running/Stopping/Stopped/Terminating/Terminated` | 같은 이름의 `ServerStatus` |
| `Moving`, `CreatingImage` | `RUNNING` |
| 정지 / 재부팅 | `SOFTSTOP` / `SOFTRESET` |
| 401 / 403·404 / 409 / 429 | 인증 실패 / 권한·OCID 확인 / 다른 작업 중 / 요청 한도 |

## 제어 흐름

```
[정지] 클릭 → 확인 창 → Async: service.control(STOP)
          → policy.actionSent(account) → 해당 계정 즉시 재조회
          → RefreshPolicy가 5초 주기 유지 (전이 상태 또는 요청 후 20초)
          → 안정 상태가 되면 45초 전체 조회로 복귀
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
| `components.*` | `Buttons`, `DashedButton`, `StatusDot`, `StatusBadge`, `RoundedPanel` |

## 빌드·실행

```bash
./gradlew run        # 앱 실행 (실제 모드)
./gradlew runDemo    # 데모 모드 (가짜 데이터, 키 불필요)
./gradlew test       # 테스트
./gradlew snapshot   # 데모 데이터로 화면을 build/snapshots/*.png로 렌더링 (개발용)
```
