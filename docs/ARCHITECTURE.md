# 구조

## 계층

```
ui/  ──────────►  core/  ◄──────────  provider/oracle/
 │                  ▲                        (OCI SDK는 여기서만)
 │                  │
 └──► ssh/          └──  storage/  (계정 설정 JSON, 키체인)
```

- **ui**: Swing 화면. `core` 타입과 `CloudProvider` 인터페이스만 안다.
- **core**: 공통 모델과 인터페이스. 다른 패키지에 의존하지 않는다.
- **provider/<cloud>**: `CloudProvider` 구현체. SDK 타입을 `core` 모델로 변환해서 내보낸다.
- **ssh**: 클라우드와 무관한 SSH 연결·세션. 이후 Docker 관리도 이 위에 얹는다.
- **storage**: 계정·서버 설정 저장, 비밀값 암호화.
- **app**: 진입점(`InfraDeskApp`). 각 계층을 조립한다.

## 핵심 타입 (`com.infradesk.core`)

| 타입 | 역할 |
|---|---|
| `CloudProvider` | `listServers`, `start`, `stop`, `reboot`, `getMetrics`. 계정 1개당 인스턴스 1개. 모든 메서드는 네트워크를 타므로 EDT 밖에서 호출 |
| `Account` | 계정 (id, 표시 이름, `ProviderType`, 리전). 비밀값 없음 |
| `ProviderType` | `ORACLE`, `AWS`, `GCP`. `isSupported()`로 구현 여부 표시 |
| `Server` | 서버 정보 (상태, shape, CPU/메모리, IP, 생성일) |
| `ServerStatus` | 정규화된 상태. `isTransitional()`, `canStart/Stop/Reboot()` |
| `Metrics` | CPU·메모리·네트워크 시계열 |
| `CloudProviderException` | SDK 예외를 감싸는 공통 예외 |

## UI 구성 (`com.infradesk.ui`)

| 클래스 | 역할 |
|---|---|
| `Theme` | DESIGN.md 색상 상수, FlatLaf 설치 |
| `Icons` | `resources/com/infradesk/ui/icons/*.svg` 로드 + 색 입히기 |
| `MainFrame` | 메인 창. full window content 모드 |
| `TitleBar` | 40px 상단 바 (앱 이름, 서버·계정 수, 마지막 새로고침, 새로고침 버튼) |
| `Sidebar` | 260px 왼쪽 (검색, 계정별 서버 목록, 계정 추가, 설정) |
| `components.Buttons` | primary / secondary / danger / icon 버튼 |
| `components.RoundedPanel` | 둥근 모서리 + 실선/점선 테두리 패널 |

## 빌드·실행

```bash
./gradlew run        # 앱 실행
./gradlew test       # 테스트
./gradlew snapshot   # 메인 창을 build/snapshots/main.png로 렌더링 (개발용)
```
