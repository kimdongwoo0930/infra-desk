# 개발 기록

단계별로 한 일, 결정한 것, 남은 일을 기록한다. 최신 항목이 위에 온다.

---

## 2026-09-27 · 1단계: 뼈대

### 한 일
- Gradle 9.8.0 Wrapper, Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`) 구성. Java 25 툴체인.
- `.gitignore`: OS·IDE·빌드 산출물, 그리고 비밀값 패턴(`*.pem`, `*.key`, `id_rsa*`, `accounts.json`, `.oci/` 등).
- `core` 모델: `Account`, `Server`, `ServerStatus`, `Metrics`, `ProviderType`, `CloudProvider`, `CloudProviderException`.
- UI 뼈대: FlatLaf 다크 테마(`Theme`), 선형 SVG 아이콘(`Icons`), 타이틀바·사이드바·빈 상태 화면(`MainFrame`).
- 개발용 스냅샷 도구: `./gradlew snapshot` → `build/snapshots/main.png`.
- 테스트 5개 (`ServerStatusTest`, `ModelTest`) 통과.

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 루트 패키지 | `com.infradesk` | 짧고 중립적 |
| LaF | macOS `FlatMacDarkLaf`, 그 외 `FlatDarculaLaf` | CLAUDE.md 지침. 색상은 `FlatLaf.setGlobalExtraDefaults`로 DESIGN.md 값으로 덮어씀 |
| 창 제목 표시줄 | FlatLaf full window content 모드 | 우리 타이틀바(40px)가 곧 창 제목 표시줄이 되어 목업과 같은 모양. macOS 신호등·Windows 캡션 버튼 자리는 placeholder로 확보 |
| 아이콘 | 목업의 SVG 경로를 `resources/.../icons/*.svg`로 옮기고 `FlatSVGIcon`으로 색 입힘 | 목업과 동일한 선형 아이콘 세트 유지 |
| 폰트 | 시스템 기본(macOS: Apple SD Gothic Neo, Windows: 맑은 고딕), 고정폭은 Menlo/Consolas | 폰트 번들은 라이선스·용량 검토 후 나중에 |
| 상태 enum | `PROVISIONING, STARTING, RUNNING, STOPPING, STOPPED, REBOOTING, TERMINATING, TERMINATED, UNKNOWN` | OCI lifecycle 상태를 모두 담을 수 있게. `isTransitional()`로 빠른 폴링 여부 판단 |
| `Metrics` | 시계열 4개(CPU%, 메모리%, 네트워크 in/out B/s) | 카드의 "현재값 + 최근 1시간 그래프"를 한 번에 그릴 수 있게 |
| `Account` | 비밀값 없음 (id, 표시 이름, provider, 리전) | 비밀값은 storage 계층이 키체인에서 따로 관리 |

### 확인된 라이브러리 버전 (Maven Central, 2026-09-27)
| 라이브러리 | 버전 | 쓰는 단계 |
|---|---|---|
| FlatLaf / flatlaf-extras | 3.7.2 | 1 |
| JUnit | 6.1.3 | 1 |
| OCI Java SDK (core, monitoring, httpclient-jersey3) | 3.97.0 | 2 |
| Apache MINA SSHD | 2.19.0 (3.0은 아직 마일스톤) | 4 |
| JediTerm (JetBrains 저장소) | 3.76 | 4 |
| XChart | 4.0.4 | 5 |

### 참고
- 이 환경에서는 화면 녹화 권한이 없어 `screencapture`가 실패함 → 오프스크린 렌더링 스냅샷으로 UI 확인.
- FlatLaf가 네이티브 라이브러리를 로드하므로 `--enable-native-access=ALL-UNNAMED` JVM 옵션을 붙임.

### 남은 일 / TODO
- "계정 추가" 버튼이 지금은 패널이라 키보드 포커스가 안 됨 → 2단계에서 다이얼로그 붙일 때 버튼으로 교체.
- 실제 창에서 macOS 타이틀바 드래그·신호등 위치 눈으로 확인 필요 (사용자 확인).
