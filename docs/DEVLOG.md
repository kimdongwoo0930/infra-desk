# 개발 기록

단계별로 한 일, 결정한 것, 남은 일을 기록한다. 최신 항목이 위에 온다.

---

## 2026-09-28 · 앱 안에서 업데이트 설치

지금까지는 새 빌드 알림을 누르면 브라우저로 dmg를 받았고, macOS가 격리 표시(quarantine)를 붙여서 매번 `xattr` 명령을 쳐야 했다. 격리 표시는 브라우저처럼 스스로 붙이겠다고 설정한 앱만 붙이므로, 앱이 직접 받으면 이 단계가 필요 없다.

### 흐름
1. 트레이/메뉴 막대 → **새 베타 빌드 N 설치…** (또는 설정 → 업데이트 확인) → 확인 창
2. `UpdateInstaller.prepare`
   - 릴리스의 `SHA256SUMS.txt`를 먼저 받는다.
   - 업데이트 zip을 받으면서 SHA-256을 계산하고 진행률을 보여 준다(취소 가능).
   - 해시가 다르면 버린다.
   - 앱 바로 옆 `.infradesk-update/`에 푼다. macOS는 `ditto`로 풀어 심볼릭 링크·실행 권한·서명을 유지하고, Windows는 Java unzip에 zip slip 검사를 한다.
   - 새 앱의 `--self-test`가 통과해야 다음으로 넘어간다.
3. `startSwap`이 스크립트를 띄우고 앱이 종료된다.
4. 스크립트(`update-swap.sh` / `update-swap.ps1`)
   - 앱 프로세스가 끝날 때까지 기다린다.
   - 기존 앱을 `.previous`로 옮기고 새 앱을 그 자리로 옮긴 뒤 백업을 지운다. 새 앱을 옮기지 못하면 원래 앱을 되돌린다.
   - 앱을 다시 실행한다.
   - 기록은 로그 폴더의 `update.log`에 남는다.

### 결정
| 항목 | 결정 | 이유 |
|---|---|---|
| macOS 업데이트 파일 | dmg와 별도로 `.app`을 `ditto`로 압축한 `InfraDesk-beta-macOS.zip` | dmg는 `hdiutil`로 마운트해야 하고 실패할 경우가 많다. zip은 풀기만 하면 된다. dmg는 첫 설치용으로 남긴다 |
| 무결성 | CI가 `SHA256SUMS.txt`를 올리고 앱이 대조 | 정식 코드 서명이 없어서, 적어도 전송 중 손상이나 다른 파일로 바뀐 것은 막는다(HTTPS + GitHub 릴리스) |
| 풀 위치 | 설치된 앱 바로 옆(같은 볼륨) | 교체가 이름 바꾸기 한 번이라 빠르고 원자적이다. Windows에서는 다른 드라이브로 폴더를 옮길 수 없다(`Move-Item`) |
| 교체 전 점검 | 새 앱의 `--self-test` | 깨진 빌드가 동작하는 앱을 덮어쓰지 않게 한다 |
| 설치할 수 없을 때 | 이유를 보여 주고 다운로드 페이지 열기로 대신 | Gradle로 실행 중, dmg 안이나 App Translocation 위치, 폴더 쓰기 권한 없음, 업데이트 파일이 없는 예전 릴리스 |
| 다운로드 클라이언트 | 리디렉트를 따라가는 별도 HttpClient | GitHub 자산 주소는 CDN으로 302 리디렉트된다. 기본 HttpClient는 리디렉트를 따라가지 않는다 |

### 테스트
- `UpdateInstallerTest`는 로컬 HTTP 서버(302 리디렉트 포함)로 확인한다: 해시 불일치 거부, 취소, zip slip 거부, 설치 가능 여부 판단, Windows 폴더 구조.
- macOS에서는 가짜 `.app`을 `ditto`로 압축해 받고, 자가 점검을 거쳐 `update-swap.sh`로 실제로 교체하는 과정까지 돈다. 자가 점검에 실패하는 빌드는 설치되지 않는 것도 확인한다.
- Windows 교체 테스트(`powershell`)는 CI의 Windows에서 돈다.
- 실제 패키징한 앱을 `ditto`로 압축했다 풀어도 격리 표시가 없고, `codesign -v`와 `--self-test`가 통과했다.

### 실제 환경
- 빌드 7: 릴리스에 `InfraDesk-beta-macOS.zip`과 `SHA256SUMS.txt`가 올라갔고, CI Windows에서 교체 스크립트 테스트가 통과했다. 사용자가 dmg로 마지막 수동 설치를 했다(`/Applications`).
- 빌드 8: 빌드 7에서 앱 안 업데이트를 처음으로 실제로 시험한다.

### 남은 일
- 임시(ad-hoc) 서명은 빌드마다 달라서, 업데이트 뒤 키체인이 "항상 허용"을 한 번 다시 묻는다. CI에 고정된 자체 서명 인증서를 넣으면 해결된다.

## 2026-09-28 · Windows 트레이 메뉴와 작업 관리자의 "???"

Windows에서 오른쪽 아래 트레이 메뉴와 작업 관리자의 앱 이름이 "??? ???"로 표시됐다.

- **트레이 메뉴:** AWT 네이티브 메뉴(`PopupMenu`)는 Windows에서 논리 폰트와 문자 집합 변환을 거쳐 그린다. 시스템 로캘 설정에 따라 한글이 "???"가 되고, 이모지(🟢)는 어떤 경우에도 그리지 못한다. 그래서 메뉴를 `TrayController.Entry` 목록(Label / Item / Separator / ServerMenu)으로 한 번만 만들고, 그리는 쪽을 플랫폼마다 나눴다.
  - macOS: 기존처럼 네이티브 메뉴를 쓴다. 메뉴 막대 메뉴는 네이티브여야 자연스럽고, 한글·이모지도 잘 나온다.
  - Windows: `SwingTrayMenu`가 FlatLaf `JPopupMenu`로 그린다. 상태는 이모지 대신 `StatusDot` 아이콘과 글자로 표시한다. 팝업에는 부모 창이 필요해서 커서 위치에 1px 투명 다이얼로그를 띄우고, 그 다이얼로그가 포커스를 잃으면(다른 곳을 클릭하면) 메뉴를 닫는다.
  - Windows 관례에 맞춰 왼쪽 클릭은 앱 열기, 오른쪽 클릭은 메뉴로 했다.
- **트레이 아이콘 색:** 템플릿 이미지는 macOS에만 있는 기능이라, Windows에서는 검은 아이콘이 어두운 작업 표시줄에 묻힌다. 레지스트리 `SystemUsesLightTheme`를 읽어 흰색이나 어두운색으로 그리고, 크기는 16·20·24·32px로 준비했다(배율 100~200%).
- **작업 관리자:** 작업 관리자는 exe의 파일 설명(FileDescription)을 앱 이름으로 보여 준다. jpackage `--description`에 넣은 한글 문장이 "???"로 기록돼서, ASCII `InfraDesk`로 바꿨다.
- `./gradlew snapshot`이 `tray-menu-windows.png`(메인 메뉴 + 서버 하위 메뉴)도 그린다. macOS에서 렌더링한 결과라 실제 Windows에서 위치와 포커스 동작은 사용자 확인이 필요하다.

## 2026-09-28 · 메모리 줄이기

Windows에서 실제 실행과 서버 추가가 확인됐지만, 작업 관리자에 약 500MB가 표시됐다. macOS에서 데모 모드를 같은 방식으로 재 보니(`footprint`, NMT) 기본 설정일 때도 약 480MB였다.

### 어디에 쓰였나 (macOS, 데모, 실행 25초 뒤)
| 항목 | 기본값 | 조정 후 |
|---|---|---|
| JVM 전체 (NMT committed) | 236MB | 77MB |
| └ Java 힙 | 112MB (실제 사용 약 20MB) | 약 17MB |
| └ G1 GC 자료구조 | 55MB | 0MB (SerialGC) |
| 그래픽 (창 버퍼·Metal 텍스처) | 48~224MB | 같음 |
| 나머지 네이티브 (AWT, 폰트, 라이브러리 malloc) | 약 150MB | 같음 |

- 살아 있는 객체는 약 20MB뿐이다. 하지만 JVM 기본값(G1, 초기 힙 = RAM의 1/64, 최대 = 1/4)은 힙을 크게 잡아 두고 잘 돌려주지 않는다. RAM이 큰 Windows PC일수록 초기 힙과 young 영역이 커져서 작업 집합이 수백 MB까지 불어난다.
- OCI SDK는 첫 계정에서 힙 약 16MB와 클래스/코드 약 30MB를 한 번 쓰고, 이후 계정마다 1MB가 채 안 늘어난다(오프라인 측정, 계정 3개).
- 그래픽 메모리는 창이 보이는지 가려졌는지에 따라 48MB와 224MB 사이를 오간다. macOS 창 서버의 몫이라 JVM 옵션으로는 줄지 않는다. `swing.volatileImageBufferEnabled=false`는 macOS에서 31MB를 줄이지만, Windows에서는 VRAM에 있던 버퍼를 프로세스 메모리로 옮기는 셈이라 쓰지 않았다.

### 결정
| 옵션 | 이유 |
|---|---|
| `-XX:+UseSerialGC` | 힙이 작은 데스크톱 앱이다. G1의 영역별 자료구조(약 55MB)가 필요 없고, 멈춤 시간도 수 ms 수준이다 |
| `-Xms16m -Xmx256m` | 필요할 때만 커진다. 상한은 실측(데모 약 20MB, OCI 3계정 약 40MB)에 터미널 스크롤백(탭당 5000줄)과 일괄 실행 출력(최대 256KB)을 더해도 여유 있게 잡았다 |
| `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` | GC 뒤에 남는 빈 힙을 OS에 돌려준다 |
| `-XX:TieredStopAtLevel=1` | C1 JIT만 쓴다. 이 앱은 I/O 대기 위주라 C2 최적화가 필요 없고, 컴파일러 메모리와 코드 캐시가 약 10MB 준다 |

- `build.gradle.kts`의 `appJvmArgs` 목록 하나를 `run`, `runDemo`, `installDist` 실행 스크립트, jpackage `--java-options`가 함께 쓴다.
- 결과: 패키징된 앱(데모)의 footprint가 약 480MB에서 약 410MB(창이 보일 때)로, 그래픽을 뺀 나머지는 약 250MB에서 약 180MB로 줄었다. Windows에서는 기본 힙이 RAM에 비례해 컸던 만큼 효과가 더 클 것으로 보고, 사용자 측정을 기다린다.
- 참고로 JVM 기본값에서 옵션을 바꿔가며 잰 수치(데모, 25초): 기본 G1은 JVM 236MB, G1+`-Xmx192m`+주기적 GC는 141MB, SerialGC+`-Xmx160m`은 86MB, 여기에 C1 전용을 더하면 77MB.

## 2026-09-28 · Docker 컨테이너

- 사용자 질문 "도커 같은 건 못 가져오겠지?" → SSH로 가능 (CLAUDE.md 확장 계획, Docker TCP 2375는 절대 쓰지 않음).
- `ssh.DockerCommands`: 공통 서두가 `docker`가 없으면 `@@nodocker`, 권한이 없으면 비밀번호 없는 `sudo -n docker`로 재시도, 그것도 안 되면 `@@noaccess`. 목록은 `docker ps -a --format '{{json .}}'` + `docker stats --no-stream`을 한 번의 SSH exec로. 시작·정지·재시작·로그 명령에는 **16진수 컨테이너 ID만** 들어감(정규식 검사) → 서버에서 온 이름 등이 셸에 닿지 않음.
- `service.ContainerService`: `TerminalService.run` 위에서 목록·동작·로그. `CloudProvider`와 분리.
- UI: 서버 상세 아래 **"컨테이너"** 섹션(상태 점·이름·이미지·상태·CPU·메모리·포트, 포트는 `80→80`으로 정리), [로그] [재시작] [정지]/[시작] + 새로고침. 정지·재시작은 확인 창. 로그는 별도 창(최근 200/1000/5000줄). 서버 선택 시 불러오고 30초 캐시. 서버 상세는 스크롤 가능.
- 데모: 서버마다 가짜 컨테이너 4개, 시작·정지·재시작이 실제로 상태를 바꿈.
- 테스트 134개 통과 (파싱, Docker 없음/권한 없음, **ID 검사로 주입 차단**, 포트 정리, 데모 목록→정지→시작→로그).
- 다음 후보: 컨테이너가 예상치 않게 멈추면 디스코드 알림, 로그 실시간 따라가기.

---

## 2026-09-28 · 라이선스 · README

- **MIT 라이선스** (`LICENSE`, 사용자 선택).
- **오픈소스 고지 자동 생성** (`generateNotices`): 런타임 라이브러리 전부의 라이선스를 각 POM(부모 POM까지)에서 읽어 `THIRD-PARTY-NOTICES.txt`로 앱에 포함. 설정 → 앱 정보 → **오픈소스 라이선스**에서 MIT 전문과 함께 표시. 자가 점검에 포함 여부 추가.
  - 확인한 것: 대부분 Apache-2.0 / MIT / BSD / EPL-2.0(Jersey) / UPL(OCI SDK). JNA·Javassist·Jersey·HK2는 이중 라이선스라 허용적인 쪽. **JediTerm은 POM상 LGPL-3.0** → 수정 없이 별도 jar로 앱 폴더에 들어가 교체 가능하므로 조건 충족, 고지에 명시. 번들 JDK는 GPL-2.0+CE.
- **README 확장**: 아이콘, 기능 소개, 데모 스크린샷(`docs/images/`, 1280px, 가짜 데이터·가려진 IP만), 다운로드와 첫 실행(xattr/SmartScreen), 처음 설정(OCI API 키 → 계정 → SSH), 데모 모드, 보안과 데이터 위치, 개발 명령, 라이선스.

---

## 2026-09-28 · 로그 · 앱 정보/업데이트 · 로그인 시 실행

사용자가 고른 세 가지. 테스트는 `./gradlew run`으로.

### 로그 파일 + 오류 처리
- `app.Logging`: java.util.logging → `~/Library/Logs/InfraDesk/infradesk-N.log`(macOS, 콘솔 앱에서도 보임) / `%LOCALAPPDATA%\InfraDesk\logs`(Windows), 2 MB × 5개 순환, 콘솔에도 출력. 우리 코드는 INFO, 라이브러리는 WARNING 이상.
- `slf4j-nop` → `slf4j-jdk14`: 버려지던 OCI SDK·MINA SSHD 경고도 같은 로그로.
- **모든 줄을 가림 처리** (`Logging.redact`): 개인키(PEM, 잘린 것 포함), 디스코드 웹훅 URL, 공인 IPv4(`x.x.x.24`), OCID(`ocid1.instance.oc1.<region>.…123456`). 로그를 이슈에 붙여도 되게.
- `ui.ErrorReporter`: 처리되지 않은 예외(EDT 포함)를 스택과 함께 기록하고 30초에 한 번까지 토스트("설정 → 앱 정보 → 로그 폴더 열기").
- 기록하는 일: 시작(빌드·Java·OS·데모/최소화), 서버 제어 요청·결과, SSH 연결 성공·실패, 업데이트 확인, 자동 실행 변경, 종료.
- 실사용 로그 확인: 시작·업데이트 확인 기록 정상, 개인 정보 없음. 잡음이던 OCI SDK 안내 3줄(VPN 장비 설정 API의 스트림 안내, OCI 인스턴스 안에서만 의미 있는 IMDS 안내)은 해당 로거만 SEVERE로 낮춰 숨김. 이름 없는 가상 스레드는 `[virtual-<id>]`로 표시.

### 앱 정보 + 업데이트 확인
- Gradle `generateBuildInfo` → `build-info.properties`(version, build, commit). CI는 `-PbuildNumber=<run>` `-Pcommit=<sha>`, 로컬은 `build=dev` + git HEAD.
- 표시: "1.0.0 Beta (빌드 12 · f4102ce)" / "1.0.0 개발 빌드 (f4102ce)" — 설정 → 앱 정보, 메뉴 막대 메뉴 아래쪽.
- `service.UpdateService`: GitHub API로 `beta` 릴리스를 읽어 제목의 "빌드 N", 설명의 커밋, 이 OS용 파일 주소를 파싱. 시작 15초 뒤 + 6시간마다(설정에서 끌 수 있음, `app-settings.json`). 더 새 빌드면 메뉴 막대에 "⬇️ 새 베타 빌드 N 받기…"(릴리스 페이지 열기) + 한 번 토스트. 개발 빌드는 비교하지 않음. 데모 모드는 확인 안 함.
- 실제 릴리스로 확인: 빌드 2, 커밋 f4102ce, macOS dmg 주소 파싱 성공.

### 로그인 시 자동 실행
- `app.LaunchAtLogin`: macOS는 `~/Library/LaunchAgents/com.infradesk.app.launcher.plist`(`open -g -a <앱> --args --minimized`, RunAtLoad), Windows는 `HKCU\…\Run`에 `"InfraDesk.exe" --minimized`.
- 대상: 설치된 앱으로 실행 중이면 그 앱(`jpackage.app-path`), 아니면(`./gradlew run`) `/Applications/InfraDesk.app`. 없으면 비활성 + 안내.
- `--minimized`: 창 없이 메뉴 막대에서 시작 (트레이가 없으면 창을 띄움).
- 설정 창 "일반"에서 켜고 끔(저장 시 적용).

### CI 실패 (빌드 3)
- Windows 테스트 실패: `LaunchAtLoginTest`가 plist 안의 경로를 `/` 구분자로 가정 → Windows는 `\`. 테스트를 경로 문자열 그대로 비교하도록 수정 (앱 코드는 문제없음).
- 실패 원인을 로그인 없이 볼 수 있게, 테스트가 실패하면 JUnit 결과를 읽어 **Actions 오류 주석**으로 표시하는 단계 추가 (작업 로그 API는 로그인 필요).
- `actions/checkout`, `actions/setup-java` v5로 (Node 20 지원 종료 경고).

### 기타
- 안내 문구의 "OS 키체인에 저장" → "암호화해서 이 PC에만 저장"(금고 방식 반영).
- 테스트 128개 통과 (로그 가림 4, 자동 실행 3, 빌드 정보 2, 업데이트 3 등).

---

## 2026-09-28 · GitHub 공개 · 자동 Release

- 첫 푸시 전에 공개될 기록 전체를 검사 → DEVLOG·테스트에 적혀 있던 실제 서버 공인 IP·서버/계정 이름을 예시 값(203.0.113.x, my-server, 계정 A)으로, 커밋 작성자 이메일을 GitHub noreply로 바꿔 기록을 다시 씀(원격에는 첫 커밋만 있어 영향 없음). 재검사 결과 공인 IP·개인 이메일·홈 경로·개인키·OCID 0건.
- 첫 CI 실행: macOS·Windows **둘 다 첫 시도에 통과** (Windows에서도 테스트 116개 + 패키징된 `InfraDesk.exe` 자가 점검).
- **자동 베타 릴리스** (사용자 결정): 빌드는 **`main` 푸시(또는 수동 실행)에서만**. 빌드가 끝나면 하나뿐인 **`beta` 시험판(Pre-release)**에 `InfraDesk-beta-macOS.dmg`, `InfraDesk-beta-windows.zip`을 **덮어써서** 다운로드 링크가 항상 같음. `beta` 태그는 해당 커밋으로 이동. 제목 "InfraDesk 1.0.0 Beta (빌드 N)", 설명에 빌드 번호·커밋·설치 안내(`.github/release-notes.md`). 공개 저장소라 로그인 없이 누구나 받을 수 있음. 동시에 여러 푸시가 오면 최신 것만 (`concurrency`).
- 앱 내부 버전은 1.0.0 (macOS 번들 버전은 숫자만 허용). 정식 출시 때 번호 붙은 릴리스를 따로 추가할 예정. Gradle `-PappVersion`은 그대로 지원.
- 규칙: 공개 저장소이므로 문서·테스트에 실제 IP·서버 이름을 쓰지 않음.

---

## 2026-09-28 · 키체인 암호를 계속 묻는 문제 → 암호화 금고

### 문제
- 설치한 앱에서 "항상 허용"을 눌러도 **버튼마다 macOS 로그인 암호를 물음**.
- 원인 1: 비밀값마다 키체인 항목이 따로 있었고(API 키 2, SSH 키 여러 개, 웹훅), "항상 허용"은 **항목마다** 적용됨. 게다가 SSH 버튼·서버 선택·실시간 토글마다 `isConfigured`가 키체인을 읽었음.
- 원인 2: jpackage 앱은 **ad-hoc 서명**(`codesign`: `Signature=adhoc`)이라 macOS가 "항상 허용"을 안정적으로 기억하지 못함.

### 조치
- `storage.VaultSecretStore`: 모든 비밀값을 **AES-256-GCM 암호화 파일** `secrets.vault` 하나에 저장(JSON 맵 전체 암호화 → 이름도 안 보임, 12바이트 IV, 128비트 태그, 매직 헤더를 AAD로). 키체인에는 **무작위 256비트 마스터 키 하나**만.
- 키체인은 **실행당 최대 한 번** 읽고, 복호화한 값은 메모리에 유지.
- 기존 키체인 항목은 필요할 때 한 번 읽어 금고로 옮김(없는 이름도 한 번만 확인).
- 마스터 키가 없는데 금고 파일이 있으면 새 키를 만들지 않고 오류 (데이터 덮어쓰기 방지). 키가 틀리면 GCM 인증 실패로 오류.
- CLAUDE.md의 "AES-GCM 암호화" 방향과 일치. 파일 권한 `rw-------`.
- 테스트 115개 통과 (왕복, 키체인 1회 읽기, 암호화·권한, 잘못된 키, 이전).

### 2차: 이전할 때도 항목마다 물음 → 일괄 이전 명령
- 금고로 바꾼 뒤에도 옛 키체인 항목을 **처음 쓸 때마다** 하나씩 옮기느라, 설치한 앱이 서버마다 한 번씩 암호를 물음.
- `--migrate-secrets`: 계정·SSH 설정·웹훅에서 이름을 모아 옛 항목을 금고로 옮기고 **옛 항목 삭제**, 금고에 "이전 완료" 표시 → 이후 설치한 앱은 옛 저장소를 **전혀 조회하지 않음**.
- 옛 항목을 만든 JVM(`./gradlew run`)에서 실행하면 항목을 만든 프로그램이 읽는 것이라 macOS가 묻지 않음. 마스터 키를 설치한 앱이 만들었다면 그 한 항목만 한 번 물음.

### 남은 것
- ad-hoc 서명이라 **앱을 다시 빌드할 때마다** macOS가 마스터 키 접근을 한 번 더 물을 수 있음. 해결책: 로컬 자체 서명 인증서로 `jpackage --mac-sign` (서명 신원이 고정되어 "항상 허용"이 유지됨).
- 옛 키체인 항목은 지우지 않음(지울 때도 암호를 물을 수 있어서). 키체인 접근 앱에서 "InfraDesk" 항목 중 `vault.masterKey` 외에는 지워도 됨.

---

## 2026-09-28 · 7단계: 패키징 · 앱 아이콘

### 한 일
- **앱 아이콘** (`tools.IconGenerator`, `./gradlew generateIcons`): 코드로 그림. 어두운 둥근 사각형(macOS Big Sur 그리드, 가장자리 여백·그림자) + 서버 두 대, LED 초록(실행 중)·파랑(포인트 색). 16px까지 알아볼 수 있게 작은 크기에서는 세부(LED 번짐, 슬롯) 생략.
  - 산출물(커밋): `src/packaging/macos/InfraDesk.iconset` → `iconutil`로 `InfraDesk.icns`, `src/packaging/windows/InfraDesk.ico`(PNG 항목), 실행 중 Dock/창 아이콘용 `resources/com/infradesk/app/icon-*.png`, 미리보기 `src/packaging/InfraDesk-1024.png`.
  - `./gradlew run`에서도 Dock에 커피잔 대신 앱 아이콘 (`app.AppIcon`, `Taskbar`).
- **자가 점검** (`--self-test`): 창·설정·키체인·네트워크 없이 FlatLaf, SVG, 앱 아이콘, Jackson, OCI SDK(오프라인 서명 요청 준비), MINA SSHD + ed25519, SFTP, JediTerm, XChart, HTTP 클라이언트, 키체인 라이브러리, TLS, 한국어 로캘을 불러와 보고 결과 출력.
- **jpackage** Gradle 작업:
  - `appImage` → `build/jpackage/InfraDesk.app` (Java 런타임 포함, jlink로 필요한 모듈만, `--strip-debug --compress zip-6`)
  - `selfTestAppImage` → 패키징된 앱의 실행 파일로 `--self-test` (줄인 런타임에 빠진 모듈이 있으면 여기서 실패)
  - `dmg` → `build/dist/InfraDesk-1.0.0.dmg` (자가 점검 통과 후에만)
  - `windowsZip` → Windows 앱 이미지(InfraDesk.exe + 런타임) zip. 설치 프로그램(WiX) 없이 동작.
- **GitHub Actions** (`.github/workflows/build.yml`): macOS·Windows 매트릭스로 테스트 → 패키징(자가 점검 포함) → 산출물 업로드. 원격 저장소에 푸시해야 동작.
- 버전 1.0.0 (macOS 번들 버전은 첫 자리가 0이면 안 됨).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 런타임 모듈 | jdeps 결과 + `jdk.localedata`, `jdk.crypto.ec`, `jdk.charsets`, `jdk.accessibility`, `jdk.zipfs` 등 | jdeps는 로캘 데이터·암호 제공자·리플렉션 사용을 못 봄. 자가 점검으로 확인 |
| 압축 | `zip-6` | 설치 크기 144MB → 96MB. dmg는 68MB → 73MB로 조금 커지지만 설치 크기가 더 중요 |
| 아이콘 제작 | 코드로 생성 | 디자인 토큰(색) 그대로 쓰고, 크기별 세부를 조절하고, 다시 만들기 쉬움 |
| Windows | 앱 이미지 zip | 설치 프로그램은 WiX 설치가 필요. 우선 실행 가능한 형태로 |
| 서명 | 안 함 | 직접 빌드한 앱은 격리 속성이 없어 그냥 열림. 다른 사람에게 배포하려면 Apple Developer 서명·공증 필요 |

### 알아둘 것
- 키체인 항목은 `./gradlew run`(java)으로 만들었으므로, 설치한 앱이 처음 읽을 때 macOS가 "InfraDesk가 키체인의 기밀 정보를 사용하려고 함"이라고 물음 → "항상 허용".
- 설정 파일(`~/Library/Application Support/InfraDesk/`)은 그대로 공유되므로 등록한 계정이 그대로 보임.

---

## 2026-09-28 · 실제 OCI 계정 연동 확인

- 사용자가 실제 OCI 계정 1개(서버 1대)를 `./gradlew run`(실제 모드)으로 등록 → 연결 테스트와 서버 불러오기 성공.
- 확인된 것: API 키 서명·인증, 인스턴스 조회, OS 키체인 저장(`KeychainSecretStore` 쓰기), 계정 설정 파일 저장.
- **OCI 메트릭도 실환경 확인**: CPU 3%, 메모리 18%(OCI 에이전트 수집 정상), 네트워크 수신·송신 약 16 KB/s — `NetworksBytesIn/Out.rate()`가 초당 바이트로 나오는 것 확인. 사이드바 CPU% 정상.
- 실환경에서 발견: XChart 기본 커서 툴팁이 커서 근처 점을 모두 나열("18%, 18%, 18%")하고 시리즈 이름이 없으며 카드 밖으로 잘림 → `metrics.HoverChartPanel`로 교체 (가장 가까운 시점 하나, 시각 + 시리즈별 이름·값·색 견본, 카드 가장자리에서 반대쪽으로 뒤집힘). 스냅샷 `main-hover.png`.
- 아직 실환경 미확인: SSH 터미널·실시간 모드·SFTP·일괄 실행, 시작/정지/재부팅, 디스코드 알림.

### 한글 입력기에서 붙여넣기가 안 되고, 우클릭 붙여넣기 후 입력이 멈춤
- `--debug-input` 기록: 입력기 `ko_KR`에서 ⌘V를 누르면 **⌘ 누름만 Java에 오고 V는 오지 않음**. 한글 입력기가 ⌘V를 가져가서 입력칸의 단축키가 실행되지 않음.
- 우클릭 → 붙여넣기 후에는 클릭·키 입력이 **창에 전달되지 않음**. 그때 `jstack`(Java EDT)과 `sample`(macOS 메인 스레드)을 떠 보니 둘 다 교착 없이 다음 이벤트를 기다리는 중 → 입력이 창까지 안 옴. 전역 마우스 리스너에서 이벤트를 가로채 메뉴를 직접 띄우던 방식이 macOS 모달 대화 상자와 맞지 않은 것으로 판단.
- 조치:
  1. 영문만 들어가는 칸(OCID, fingerprint, SSH 포트, 웹훅 URL)은 `enableInputMethods(false)` → 입력기를 거치지 않으므로 ⌘V가 바로 도착해야 함. 한글 오입력도 막음.
  2. 우클릭 메뉴를 표준 `setComponentPopupMenu`로 교체 (입력칸이 추가될 때 붙임). 직접 띄우기·이벤트 소비 제거.
  3. macOS 메뉴 막대에 "편집" 메뉴(잘라내기·복사·붙여넣기·모두 선택). 입력칸과 터미널 모두에 동작. 대화 상자가 떠 있을 때도 보이도록 기본 메뉴 막대로도 등록.
- 2차 확인 (`--debug-input`): 입력기를 끈 OCID 칸에서는 이제 ⌘Z, ⇧⌘← 같은 단축키가 도착함 → 입력기 문제는 해결된 것으로 보임.
- 그런데 표준 우클릭 메뉴로 바꿔도 **메뉴를 여는 순간** 또 입력이 멈춤 (`MOUSE_PRESS popup=true` → `FOCUS_GAINED JRootPane` → 이후 입력 없음).
  - 원인: FlatLaf `FlatPopupFactory`는 macOS에서 `Popup.dropShadowPainted`가 켜져 있으면 **모든 팝업을 네이티브 창(heavyweight)으로 강제**함 (그림자·둥근 테두리용). 모달 대화 상자 위의 이 창이 입력을 잡고 놓지 않음.
  - 조치: `Popup.dropShadowPainted=false` → Swing 기본 규칙으로 창 안에 들어가면 창 내부(lightweight)에 그림. 같은 대화 상자의 리전 선택 상자 등 모든 팝업에 적용.
  - 입력칸 우클릭 메뉴는 한때 제거했다가, 사용자가 필요로 해서 **다시 추가**. 원인(네이티브 팝업 창)을 끈 상태에서, 팝업이 창 밖으로 나가면 Swing이 여전히 네이티브 창을 쓰므로 메뉴 위치를 항상 창 안으로 조정. 확인: 입력칸 왼쪽·오른쪽 끝을 눌러도 팝업이 같은 대화 상자 안(lightweight)에 그려짐.
- 사용자가 ⌘Z를 눌렀는데 Swing 입력칸엔 실행 취소가 없음 → `components.TextUndo`: 모든 입력칸에 실행 취소/다시 실행(⌘Z, ⇧⌘Z), 편집 메뉴에도 추가.

### 메뉴 막대 아이콘
- 사용자 요청. `ui.TrayController`: macOS 메뉴 막대(시스템 트레이) 아이콘 + 서버 상태 메뉴.
- 메뉴는 **네이티브 AWT PopupMenu**라 Swing 팝업 문제(모달 대화 상자 멈춤)와 무관.
  - 첫 줄: "InfraDesk · 실행 중 3 / 5대 · 변경 중 1 · 계정 오류 1 · 14:26 갱신" (마지막 서버 상태 조회 시각, 툴팁에도)
  - 계정별로 서버: "🟢 name — CPU 23%" / "⚪ name — 정지됨" / "🟡 name — 정지 중" (이모지와 글자로 상태, 색만으로 구분하지 않음)
  - 서버 하위 메뉴: 대시보드에서 보기, SSH 열기, 재부팅…, 정지…(실행 중) / 시작(정지됨). 정지·재부팅은 메인 창을 앞으로 가져와 기존 확인 창을 띄움.
  - 새로고침, InfraDesk 열기, 종료.
- 아이콘: 템플릿 이미지(`apple.awt.enableTemplateImages`)라 밝은/어두운 메뉴 막대에 맞춰 흑백 자동 전환. 템플릿은 색을 못 쓰므로 **주의 상태(변경 중, 계정 오류)는 뱃지 모양**으로 표시하고 내용은 첫 줄·툴팁 글자로.
- **창 닫기 = 메뉴 막대로 숨기기** (트레이가 있을 때). 첫 번째 닫기에 알림으로 안내. 완전히 종료는 메뉴의 "종료" 또는 ⌘Q(세션 정리 후 종료). Dock 아이콘 클릭으로 창 다시 열기. 숨긴 동안 실시간 모드 일시정지, 45초 조회·1분 CPU·알림은 계속.
- 실사용 확인: 계정 2개·서버 3대가 메뉴에 정상 표시.
- 스냅샷 도구는 `-Dinfradesk.noTray=true`로 실제 메뉴 막대에 아이콘을 만들지 않음.
- 테스트 110개 통과 (요약·주의 판단·메뉴 문구).

### 계정 설정 (수정)
- 사용자 요청: 서버·계정을 우클릭해 계정 설정을 열고 바꿀 수 있게.
- 우클릭 메뉴: 계정 이름 → "계정 설정…", "계정 삭제…" / 서버 → "SSH 설정…", "계정 설정…"(그 서버의 계정).
- 계정 추가 창을 편집 모드로 재사용: 기존 값 채움, 같은 id·같은 순서로 저장(`InventoryService.updateAccount`), 캐시된 provider를 닫아 다음 조회부터 새 설정 적용.
- API 키는 새 파일을 골랐을 때만 교체. 연결 테스트는 입력하지 않은 비밀값을 저장된 것으로 채워서 시도(`testConnection`이 저장된 키와 병합).
- 테스트 106개 통과, 스냅샷 `edit-account.png`.

### 공인 IP 가리기
- 사용자 제안: 공인 IP는 중요하니 버튼을 눌렀을 때만 보이게.
- `ui.IpPrivacy`: 앱 전체 상태, **켤 때마다 가린 상태로 시작**. 공인 IPv4는 `•••.•••.•••.104`(마지막 자리만)로 표시. 사설·예약 대역(10/8, 172.16/12, 192.168/16, 127/8, 100.64/10)은 그대로.
- 서버 상세 공인 IP 칸: 👁 보기/숨기기, 복사(가려져 있어도 실제 IP 복사).
- 같이 가려지는 곳: 터미널 상태바 주소, 연결 실패 안내, 정지·재부팅 확인 창, 호스트 키 확인 창, 모니터링 상태 줄, SFTP 오류, 일괄 실행 결과 오류. 보이기로 바꾸면 모두 즉시 갱신.
- 한계: 터미널 **안에** 서버가 출력하는 글자(예: `Last login … from <IP>`)는 가리지 않음.
- 테스트 104개 통과.

### 서버 정보 칸 채우기 (업타임 · OS · 부트 볼륨 · 열린 포트)
- 사용자 질문: "OCPU·메모리·IP·생성일밖에 안 보이는데 다른 건 못 가져와?" → 나머지 4칸은 서버 안에서 봐야 정확한 값이라 SSH로 한 번 읽음.
- `ssh.HostFacts`: POSIX sh 명령 하나로 `/proc/uptime`, `/etc/os-release`(없으면 `uname -sr`), `df -Pk /`, `ss -tln`(없으면 `netstat -tln`)을 섹션별로 출력하고 파싱. 포트는 localhost(127.x, ::1, `%lo`) 전용을 뺀 LISTEN 포트.
- 서버를 선택할 때 SSH 설정이 있으면 백그라운드로 읽고 **5분 캐시**. SSH 설정 저장 직후·서버가 다시 실행될 때 새로 읽음. 설정이 없으면 "SSH 설정 후 표시", 실패하면 "SSH로 읽지 못함".
- 표시: 업타임 "14일 6시간", 부트 볼륨 "사용 / 전체 GB"(툴팁에 %), 포트 4개까지 + "외 N개"(툴팁에 전체).
- 데모: 서버마다 다른 가짜 값.
- 테스트 101개 통과 (`ss`/`netstat` 형식, 빈 섹션, 데모 응답, 표시 형식).

### 대화 상자 문구 잘림
- 두 번째 계정(서버 second-server)을 추가하며 확인: `~/.ssh/config` 자동 채우기 동작, 하지만 안내 문구가 창 밖으로 잘림.
- 원인: JLabel에 HTML `width`를 줬는데 macOS에서 실제보다 넓게 계산됨. JTextArea 단어 줄바꿈은 한글 단어 중간("서/버")에서 끊음.
- `components.WrappingLabel`: 폭을 고정하고 **띄어쓰기에서만** 줄바꿈을 직접 계산 (한 단어가 줄보다 길면 글자 단위). SSH 설정·계정 추가·설정 창의 상태/안내 문구에 적용 → 긴 오류 메시지도 잘리지 않음.

### `~/.ssh/config`에서 SSH 설정 가져오기
- 사용자가 이미 `~/.ssh/config`에 서버별 Host(HostName·User·IdentityFile)를 정리해 두고 있어서, SSH 설정 창을 처음 열 때 **서버 공인 IP와 HostName이 같은 Host**를 찾아 사용자 이름·포트·키 파일을 미리 채움. 없으면 서버 이름과 같은 Host 별칭으로 찾음.
- `ssh.SshConfig`: OpenSSH처럼 블록을 순서대로 보며 **먼저 나온 값이 우선**, `Host *` 기본값, `*`/`?` 와일드카드, `!` 제외, `Key=Value`·따옴표·주석, IdentityFile의 `~`와 `%d %u %h %n %r %%` 확장, 여러 IdentityFile 중 실제로 있는 첫 파일. `Match`·`Include`는 무시.
- 키는 창에 미리 읽어 두기만 하고, **저장을 눌러야** 키체인에 들어감. 창에 "~/.ssh/config의 'my-server'에서 가져왔어요 (키: …)" 안내.
- 데모 모드에서는 실제 설정 파일을 읽지 않음.
- 실제 설정으로 확인: my-server(203.0.113.104) → `my-server`, `ubuntu`, 22, `my-server.key`. 키 내용은 읽지 않고 파일 이름만 확인.
- 테스트 91개 통과 (SSH config 6개 추가).
- 과정에서 나온 문제: 붙여넣기가 안 됨 → 우클릭 메뉴 추가, `--debug-input` 진단 모드 추가. 재실행 후 정상 (원인 기록은 못 남김).

---

## 2026-09-28 · 실사용 준비: 우클릭 복사·붙여넣기

- 실제 키로 시험하던 중 "붙여넣기가 안 돼" → Swing 입력칸은 단축키(⌘C/⌘V)는 있지만 **우클릭 메뉴가 없음**.
- `components.TextContextMenu`: 앱 전체 입력칸에 우클릭 메뉴(잘라내기·복사·붙여넣기·모두 선택). 전역 AWT 리스너 하나로 설치, 이미 자체 메뉴가 있는 컴포넌트는 건드리지 않음. 비밀번호 칸은 잘라내기·복사 비활성.
- 확인: 모든 입력칸에 `meta V`/`meta C` 바인딩이 있음 (macOS는 Ctrl이 아니라 ⌘).
- OCI SDK 오프라인 스모크 테스트 추가: 생성한 키로 서명한 요청을 닫힌 로컬 포트로 보내서, Java 25에서 키 파싱·서명·Jersey 클라이언트가 동작하는지 OCI에 접속하지 않고 확인.

---

## 2026-09-28 · 6단계: 편의 기능

### 6-1. 저장된 명령어 · 일괄 실행
- 터미널 화면 오른쪽 패널 (목업 `terminal.html`): 저장된 명령어 카드, 일괄 실행, 맨 아래 [SFTP 파일].
- **저장된 명령어**: 클릭하면 선택된 터미널 탭에 입력 후 실행. [+]로 추가, 우클릭으로 편집·삭제(삭제는 확인). `commands.json`에 저장 (비밀값 아님). 연결된 탭이 없으면 안내.
- **일괄 실행**: 실행 중 서버만 선택 가능 (정지된 서버는 "(정지됨)"으로 비활성). 실행 전에 명령어와 대상 서버를 보여주고 확인. 서버마다 PTY 없는 exec 채널로 병렬 실행, 60초 제한, 출력 256KB까지.
- **결과 창**: 서버별 성공/실패/종료 코드(색 점 + 글자), 선택한 서버의 출력. 결과가 도착하는 대로 갱신.
- 데모: 목업의 명령어 4개가 미리 저장돼 있고, 가짜 셸이 `docker restart/logs`, `apt`, `du`에도 응답.
- 테스트 64개 통과 (실행 결과·종료 코드, 연결 실패, 시간 초과, 명령어 저장).

| 결정 | 이유 |
|---|---|
| 저장된 명령어는 클릭 즉시 실행 (Enter 포함) | 목업의 "명령어 버튼" 용도. 사용자가 직접 저장한 명령어만 들어감 |
| 일괄 실행은 항상 확인 창 | 여러 서버에 동시에 영향을 주는 작업 |
| 명령어는 전체 공용 (서버별 아님) | 목업 구조. 필요해지면 서버 태그로 확장 |

### 6-2. SFTP 파일
- 오른쪽 패널 [SFTP 파일]: 선택된 탭의 서버로 열림 (탭이 없으면 실행 중 서버 메뉴). SSH 설정이 없으면 설정 창부터.
- 파일 탐색기 창 (여러 개 동시에 열 수 있음): 경로 입력·상위 폴더·새로고침, 폴더 먼저 정렬, 두 번 클릭(또는 Enter)으로 폴더 이동/파일 다운로드, 업로드·다운로드 진행률, 삭제(확인, 폴더는 비어 있어야 함). 덮어쓰기는 로컬·원격 모두 확인.
- `ssh.RemoteFiles` 인터페이스 + MINA SFTP 구현(`sshd-sftp`), 데모는 메모리 파일 트리(업로드도 메모리에 남음).
- 원격 이름을 로컬 파일 이름으로 쓸 때 `/ \ :`와 `..`를 치환 (`RemoteFiles.safeLocalName`).
- 테스트: **테스트 안에서 SFTP 서버를 띄워** 목록, 200KB 업로드·다운로드, 삭제 확인. 경로 도우미, 데모 트리.

### 6-3. 디스코드 웹훅 알림
- 사이드바 ⚙ → **설정 창**: 알림 사용, 웹훅 URL(키체인 저장, 입력칸은 가려짐), 알림 종류, CPU 기준(기본 90% · 5분), [테스트 메시지 보내기].
- **알림 종류** (`alert/AlertMonitor`, 문제마다 한 번 + 복구 알림 한 번):
  - 🔴 실행 중이던 서버가 정지·삭제로 바뀜 → 🟢 다시 실행 중. 앱에서 정지·재부팅한 서버는 15분 동안 제외.
  - ⚠️ 계정 연결 오류 → ✅ 복구.
  - 🔥 CPU가 기준 이상으로 N번(1분마다) 연속 → ✅ 정상. 중간에 한 번이라도 내려가면 다시 셈.
  - 앱을 켰을 때 이미 멈춰 있던 서버는 알리지 않음.
- **전송** (`alert/DiscordNotifier`): embed(제목·설명·색·시각), 429면 `retry_after`만큼 한 번 재시도(10초 이하일 때), 401/403/404는 "웹훅이 유효하지 않아요". 전송은 백그라운드 스레드 하나에서 순서대로. 실패하면 화면 구석 토스트.
- **데모**: 실제로 보내지 않고 화면 구석에 "디스코드 알림 미리보기 (데모 · 전송 안 함)" 토스트.
- 테스트 83개 통과 (알림 규칙 8개, 로컬 HTTP 서버로 전송·재시도·오류 5개, 설정·저장 2개).

| 결정 | 이유 |
|---|---|
| 웹훅 URL은 키체인 | URL만 있으면 누구나 그 채널에 글을 쓸 수 있는 비밀값. 오류 메시지·로그에도 넣지 않음 |
| URL 검증: `https://`(ptb/canary.)discord(app).com`/api/webhooks/<id>/<token>`만 허용 | 오타나 다른 주소로 서버 정보를 보내는 실수 방지 |
| 알림은 앱이 켜져 있을 때만 | 데스크톱 앱 구조. 상시 감시는 서버 쪽 에이전트나 OCI Alarms가 맞음 (문서에 안내) |
| 복구 알림은 문제 알림을 보낸 경우에만 | 짝이 맞는 알림만 보내 채널이 시끄럽지 않게 |

### 마침표 글리프 문제 해결
- SFTP 파일 이름(`README.md`가 "README md"로 보임) 때문에 보류했던 문제를 해결.
- `Theme.warmUpGlyphCache()`: macOS에서 LaF 설치 직후, UI 글꼴을 크기 9~24 × 보통/굵게 × 배율 1·2 × 안티앨리어싱 4가지로 ASCII 문장부호 문자열을 한 번씩 오프스크린에 그림. 한글 뒤 마침표가 처음 그려지기 전에 올바른 글리프가 캐시됨.
- 확인: "요."를 먼저 그려도 이후 `.`가 정상 (이전에는 사라짐). 스냅샷에서 `VM.Standard.A1.Flex`, `docker-compose.yml` 정상.

---

## 2026-09-28 · 5단계 보완: 실시간 모드 안전장치

사용자 질문 "계속 SSH로 읽어오는 게 괜찮아?"에서 나온 개선. 부하·보안은 문제없지만(2초마다 /proc 몇 줄, 연결 1개, 기존 키·포트만 사용) 켜 둔 채로 잊는 경우를 막음.

### 한 일
- **일시정지/재개**: 터미널 화면으로 가거나 창을 최소화하면 SSH 연결을 닫고, 대시보드가 다시 보이면 자동으로 다시 연결 (토글은 켜진 상태 유지). CLAUDE.md "서버 상세 화면을 보고 있을 때만"에 맞춤.
- **10분 자동 꺼짐**: 켠(또는 재개한) 뒤 10분이 지나면 끄고 "10분이 지나 실시간을 껐어요" 표시.
- **서버 쪽 루프 상한**: 원격 루프가 최대 310회(약 10분 20초) 돌고 스스로 끝남. 노트북 잠자기처럼 연결이 비정상적으로 끊겨 서버가 모르는 경우에도 루프가 남지 않음.
- 상태 로직을 `ui/metrics/LiveController`로 분리 (OFF → CONNECTING → RUNNING, PAUSED). 일시정지 중에 늦게 도착한 연결은 닫음.
- 테스트 57개 통과 (컨트롤러 8개, 루프 상한 1개 추가).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 재개 시 그래프 | 새로 시작 (이전 실시간 점은 버림) | 끊긴 동안의 공백을 선으로 이으면 없는 데이터를 있는 것처럼 보이게 함 |
| 자동 꺼짐 시간 | 10분, 재개할 때마다 다시 셈 | 잠깐 보는 용도에 충분하고, 잊어도 오래 돌지 않음 |
| 루프 상한 방식 | POSIX `sh` 카운터 (`timeout` 명령 안 씀) | `timeout`이 없는 최소 이미지(busybox 등)에서도 동작 |

---

## 2026-09-28 · 5단계: 모니터링

### 한 일
- **OCI Monitoring** (`provider/oracle/OracleMetrics`): `oci_computeagent` 네임스페이스에서 최근 1시간, 1분 해상도.
  - CPU `CpuUtilization.mean()`, 메모리 `MemoryUtilization.mean()`, 네트워크 `NetworksBytesIn/Out.rate()` (초당 바이트).
  - 사이드바용 CPU는 계정당 **한 번의 묶음 쿼리** `CpuUtilization[1m].groupBy(resourceId).mean()`.
- `CloudProvider.currentCpu(servers)` 추가 (기본 구현은 서버별 `getMetrics`, OCI는 묶음 쿼리로 재정의).
- **SSH 실시간 모드** (`ssh/ProcStats`, `service/LiveStats`): 서버 상세에서 [실시간 (SSH)]를 켜면 exec 채널로 `/proc/stat`, `/proc/meminfo`, `/proc/net/dev`를 2초마다 읽어 차이로 계산. 서버를 바꾸거나 정지되면 자동으로 끔.
  - CPU: busy/total jiffies 차이 (idle + iowait는 idle로 계산)
  - 메모리: (MemTotal − MemAvailable) / MemTotal
  - 네트워크: `lo` 제외 모든 인터페이스의 rx/tx 바이트 차이 ÷ 경과 시간 (카운터가 줄면 0)
- **UI**: 서버 상세 아래 "모니터링" 섹션 — CPU / 메모리 / 네트워크 스탯 타일 (현재값 + XChart 스파크라인 + 마우스 커서 십자선·툴팁). 사이드바 실행 중 서버 옆에 CPU%.
- 주기: 선택한 서버 메트릭과 사이드바 CPU는 1분마다 (OCI가 1분 단위로 집계). 실시간 모드는 2초.
- **데모**: 서버 id로 고정된 파형의 가짜 1시간 데이터, 실시간은 `/proc` 형식 가짜 출력 (실제와 같은 파서를 거침).
- 스냅샷 `main.png`(1시간 그래프), `main-live.png`(실시간).
- 테스트 48개 통과 (`/proc` 계산, 데모 실시간 스트림, MQL 생성·데이터 정리, 데모 메트릭, 계정별 CPU 실패 격리).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 차트 형태 | 스탯 타일 (현재값 + 스파크라인), 축·격자 없음 | 목업 그대로. 한 숫자 + 추세를 보여주는 용도 (dataviz 스킬 형태 선택) |
| 색 | CPU·메모리: 포인트 색 `#3B73E0` 한 가지. 네트워크: 수신 `#3B73E0`, 송신 `#D95926` | 카드 배경(`#2B2D30`) 기준 검증 통과: 색각 이상 ΔE 27.2, 일반 ΔE 32.9, 대비 3:1 이상 |
| 범례 | 네트워크만 (시리즈 2개). 범례 항목에 현재값을 함께 표시 | 시리즈 2개 이상이면 범례 필수, 색만으로 구분하지 않기. 글자는 텍스트 색, 색은 선 견본에만 |
| y축 | CPU·메모리는 0~100 고정, 네트워크는 0부터 자동 | 퍼센트는 높이 자체가 의미가 있어서 고정. 네트워크는 범위가 서버마다 다름 |
| 네트워크 큰 숫자 | 수신 + 송신 합계 | 목업 문구 "수신 · 송신 합계" |
| 실시간 방식 | PTY 없는 exec 채널에서 `while … sleep 2` 루프 한 번 | 2초마다 새 SSH 명령을 여는 것보다 연결·인증 비용이 한 번뿐 |
| 메모리 메트릭 없음 | "메모리 메트릭이 없어요 (OCI 에이전트 확인)" 안내 | OCI 메모리는 Compute Instance Monitoring 플러그인이 켜져 있어야 수집됨 |

### 추가한 라이브러리
- `oci-java-sdk-monitoring` 3.97.0 — OCI Monitoring API
- `xchart` 4.0.4 — 스파크라인 (CLAUDE.md 스택)

### 남은 일 / TODO
- 실제 OCI 메트릭 확인 (API 키 필요). 특히 `NetworksBytesIn.rate()` 단위가 초당 바이트인지 실데이터로 확인.
- 실시간 모드 켠 동안 사이드바 CPU%는 여전히 OCI 값 (1분 단위)이라 카드 값과 다를 수 있음.
- 업타임·OS·부트 볼륨·열린 포트는 SSH로 한 번 읽어서 채울 수 있음 (다음 후보).
- 창이 최소화돼도 1분 메트릭 갱신은 `isShowing()`으로 건너뜀. 백그라운드 알림(6단계 디스코드 웹훅)과 함께 다시 볼 것.

---

## 2026-09-28 · 4단계: SSH 터미널

### 한 일
- **ssh 계층** (클라우드와 무관): `ShellConnector`/`ShellSession` 인터페이스, `MinaShellConnector`(Apache MINA SSHD), `DemoShellConnector`(데모용 가짜 셸).
- **호스트 키 검증**: 설정 디렉터리의 `known_hosts` 사용. 처음 보는 서버는 지문(SHA256)을 보여주고 사용자에게 물어봄. 저장된 키와 다르면 **연결 거부** (중간자 공격 방지).
- **서버별 SSH 설정**: 사용자 이름·포트는 `ssh-settings.json`(소유자만 읽기), 개인키·키 암호는 OS 키체인 (`server.<id>.sshKey`).
- **터미널 화면** (목업 `terminal.html`): 타이틀바가 "← 대시보드 / SSH 터미널 · 열린 세션 N개"로 바뀜. 서버별 탭(연결 상태 점, 닫기, 가운데 클릭으로 닫기), [+]로 실행 중인 서버 골라 새 세션. 하단 상태바 "연결됨 · user@ip:22 · UTF-8 · 120×32".
- 연결 실패 시 이유(인증 실패, 시간 초과, 연결 거부, 호스트 키 변경, 공인 IP 없음)와 [다시 연결] [SSH 설정] 버튼.
- 서버 상세의 [SSH 열기]: 실행 중일 때만 켜짐. 설정이 없으면 SSH 설정 창부터. 이미 열린 세션이 있으면 그 탭으로 이동.
- 사이드바 서버 우클릭 → "SSH 설정…".
- 데모 모드: 키 없이 가짜 셸 (`help`, `whoami`, `uptime`, `free -h`, `df -h`, `ls`, `ps`, `docker ps`, `date`, `echo`, `clear`, `exit`).
- 스냅샷 `terminal.png`, `ssh-settings.png`.
- 테스트 39개 통과. 그중 SSH 6개는 **테스트 안에서 MINA SSH 서버를 띄워** 실제로 접속: RSA/ed25519 키 인증, 첫 접속 확인 후 known_hosts 기억, 호스트 키 거부, 호스트 키 변경 거부, 잘못된 키로 인증 실패, 잘못된 키 파일.

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 호스트 키 | TOFU(첫 접속 시 확인) + 변경 시 거부 | 일반 `ssh`와 같은 모델. 변경된 키를 자동 수락하면 중간자 공격에 열림 |
| 연결별 상태 전달 | MINA connection context 속성 | 호스트 키 확인이 MINA I/O 스레드에서 돌아서 ThreadLocal로는 전달 안 됨 |
| PTY | `xterm-256color`, 120×32로 열고 창 크기에 맞춰 `window-change` | JediTerm이 레이아웃 후 크기를 알려줌 |
| 기본 사용자 이름 | `ubuntu` (목록: ubuntu, opc, ec2-user, root) | 서버 OS를 아직 모름. SSH로 OS를 알아내면 추천값 개선 가능 |
| 오른쪽 패널(저장된 명령어·일괄 실행·SFTP) | 이번 단계에서 제외 | CLAUDE.md 6단계 기능 |
| 터미널 기본 스타일 | `getDefaultStyle()` 재정의 (폐기 예정 API) | JediTerm 3.76이 초기 스타일에 여전히 사용하고, 기본값이 흰 배경이라 색 없는 인사말 뒤에 흰 막대가 생김 |

### 추가한 라이브러리
- `sshd-core` 2.19.0 — SSH 클라이언트 (3.0은 아직 마일스톤)
- `eddsa` 0.3.0 (runtime) — MINA SSHD가 ed25519 키를 읽는 데 필요. 없으면 `id_ed25519` 키를 못 읽음
- `jediterm-core`, `jediterm-ui` 3.76 (JetBrains 저장소, Kotlin stdlib 포함) — 터미널 에뮬레이터

### 남은 일 / TODO
- 실제 서버 SSH 접속 확인 (서버 SSH 키 필요).
- 한글이 터미널에서 두 칸 폭이라 글자 사이가 넓어 보임 (Menlo에 한글 글리프가 없어 대체 글꼴 사용). 한글 지원 고정폭 글꼴(D2Coding 등, OFL) 번들 검토.
- 데모 상태바 주소가 `ubuntu@demo:web-server:22`로 보임 (데모 전용 표기).
- 대시보드의 미니 터미널(목업 4번 영역)은 아직 없음.
- 사설 IP / 배스천 경유 접속 없음.

---

## 2026-09-28 · 3단계: 제어·자동 새로고침

### 한 일
- 서버 상세의 [재부팅] [정지] 버튼 연결. 정지된 서버는 [정지] 대신 [시작]이 보임.
- 버튼은 상태에 따라 켜지고 꺼짐 (`ServerStatus.canStart/Stop/Reboot`). 요청 중이거나 전이 상태(정지 중 등)면 모두 꺼지고, 꺼진 이유를 툴팁으로 보여줌.
- 정지·재부팅은 실행 전에 확인 창 (서버 이름, 계정, IP, 영향 설명). 기본 선택은 "취소".
- 요청 결과를 헤더 아래 한 줄로 표시 ("정지 요청을 보냈어요…", 실패하면 빨간 글씨로 이유).
- 자동 새로고침 (`service.RefreshPolicy`):
  - 평소 45초마다 전체 계정.
  - 전이 상태 서버가 있거나 방금 요청을 보낸 계정은 5초마다, **그 계정만** 다시 조회.
  - 요청 직후 OCI가 잠깐 이전 상태를 돌려줄 수 있어서, 요청 후 20초는 상태와 관계없이 빠른 조회 유지.
  - 전이 상태가 10분 넘게 안 끝나면 평소 주기로 돌아감 (요청 한도 보호).
  - 타이머가 실패하면 조용히 넘어가고, 사용자가 누른 새로고침만 오류 창을 띄움.
- `InventoryService.control(account, serverId, action)`, `load(accounts)` 추가.
- 스냅샷 `main-stopping.png` 추가 (데모에서 discord-bot 정지 직후).
- 테스트 25개 통과 (새로고침 규칙 4개, 제어 전달·확인 필요 여부 2개 추가).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 평소 주기 | 45초 | CLAUDE.md의 30~60초 범위 중간 |
| 빠른 주기 | 5초, 해당 계정만 | CLAUDE.md 지침. 계정마다 인스턴스·VNIC 조회가 여러 번이라 전체를 5초마다 돌리면 요청 한도에 가까워짐 |
| 시작 확인 창 | 없음 | 시작은 되돌리기 쉽고 서비스 중단을 일으키지 않음. 정지·재부팅만 확인 |
| 타이머 | `javax.swing.Timer` 1회용, 조회가 끝날 때마다 다음 일정을 다시 잡음 | 조회가 겹치지 않고, 결과에 따라 주기를 바꾸기 쉬움 |

### 남은 일 / TODO
- 실제 OCI에서 시작/정지/재부팅 확인 (API 키 필요).
- 창이 최소화돼 있을 때 조회를 줄이는 건 아직 없음.

---

## 2026-09-28 · 2단계: 계정·서버 목록

### 한 일
- **데모 모드** (`./gradlew runDemo`, 또는 `--demo` 인자): 가짜 계정 3개·서버 5대 (계정 A는 한 계정에 서버 여러 대인 경우를 보여주려고 3대: discord-bot, api-gateway, batch-worker). 설정 파일·키체인·네트워크를 쓰지 않음. 타이틀바에 "데모 모드" 배지.
- **storage**: `JsonAccountStore`(계정 설정 JSON, 원자적 저장, POSIX에서 `rw-------`), `KeychainSecretStore`(OS 키체인), 데모·테스트용 인메모리 구현.
- **provider/oracle**: `OracleProvider` — 인스턴스 목록(페이지 처리), VNIC로 공인/사설 IP, 시작(START)/정지(SOFTSTOP)/재부팅(SOFTRESET) 호출, OCI 오류를 한국어 메시지로 변환. 메트릭은 5단계.
- **provider/demo**: `DemoProvider` — 지연 시간 흉내, 시작/정지/재부팅 시 8초간 전이 상태(정지 중 등)를 거쳐 안정.
- **service**: `ProviderRegistry`(ProviderType → 팩토리), `InventoryService`(계정 추가/삭제, 계정별 병렬 조회 — 한 계정 실패가 다른 계정에 영향 없음, 연결 테스트), `AccountValidation`.
- **UI**: 계정별 서버 목록(검색, 키보드 선택, 우클릭으로 계정 삭제), 서버 상세(헤더·상태 배지·4×2 정보 그리드), 계정 추가 다이얼로그(연결 테스트, 개인키 파일 선택), 새로고침.
- 네트워크 호출은 모두 `Async`(가상 스레드)에서 실행하고 결과만 EDT로 넘김.
- 스냅샷: `main.png`, `main-empty.png`, `add-account.png`.
- 테스트 19개 통과 (저장소, 서비스, 검증, 데모 전이, OCI 매핑).

### 결정한 것
| 항목 | 결정 | 이유 |
|---|---|---|
| 비밀값 저장 | API 개인키 **내용**을 OS 키체인에 저장 (`account.<id>.privateKey`) | 목업 문구는 "마스터 비밀번호 암호화"였지만 CLAUDE.md 우선순위(키체인 → 불가 시 AES-GCM)에 따름. 안내 문구도 키체인으로 수정 |
| 계정 설정 | `Account.properties`에 OCID·fingerprint (비밀 아님) | OCID는 커밋 금지 대상이지만 로컬 설정 파일은 괜찮음. 파일 권한은 소유자만 |
| 조회 범위 | 기본은 tenancy 루트 compartment. `compartmentOcid` 속성으로 바꿀 수 있음 | 계정당 서버 1대인 프리티어 구성에 맞춤 |
| IP 조회 | compartment의 VNIC 연결 목록을 한 번 조회 후 VNIC별 `getVnic` | 인스턴스마다 attachment를 조회하는 것보다 호출 수가 적음 |
| 정지/재부팅 | `SOFTSTOP`, `SOFTRESET` (OS에 종료 신호를 먼저 보냄) | 데이터 손상 위험이 적은 쪽 |
| `CloudProvider` | `AutoCloseable` 추가 (기본 no-op) | OCI SDK 클라이언트 해제 |
| 서비스 계층 | 새 패키지 `service/` | UI가 storage·provider를 직접 조립하지 않도록 |
| 로깅 | `slf4j-nop` | OCI SDK 로그가 콘솔에 쏟아지지 않게. 필요해지면 로거 연결 |

### 추가한 라이브러리
- `oci-java-sdk-core`, `oci-java-sdk-common-httpclient-jersey3` 3.97.0 — OCI API 호출 (SDK 3.x는 HTTP 클라이언트 구현을 따로 골라야 함)
- `jackson-databind`, `jackson-datatype-jsr310` 2.22.3 — 계정 설정 JSON
- `java-keyring` 1.0.4 — macOS 키체인 / Windows 자격 증명 관리자 접근
- `slf4j-nop` 2.0.20 — SDK 로그 끄기

### 확인한 것
- 이 Mac에서 키체인 접근 확인 (없는 항목 조회만, 아무것도 쓰지 않음).
- 실제 OCI 연결은 아직 확인하지 않음 (API 키 없음 → 0단계 이후 확인).

### 남은 일 / TODO
- ~~**알려진 문제**: 13px 글자에서 `.`와 `'`가 빈칸처럼 보임~~ → 6-2에서 `Theme.warmUpGlyphCache()`로 해결.
  - 원인: macOS JDK에서 어떤 글꼴 크기로 **처음** 그리는 문자열에 한글 바로 뒤의 `.`(예: "요.")가 있으면, 그 크기의 `.` 글리프가 빈 글리프로 캐시되어 이후 모든 텍스트에서 사라짐. 영문 문자열을 먼저 그리면 정상 (`OrderTest`로 확인: 첫 문자열 "VM.Standard" → 정상, "요." → 이후 전부 사라짐).
  - 해결 후보: 앱 시작 시 각 UI 글꼴 크기로 ASCII 문장부호를 한 번 오프스크린에 그려 글리프 캐시를 먼저 채우기. 또는 JDK 업데이트 후 재확인.
- 키체인을 쓸 수 없을 때 마스터 비밀번호(AES-GCM) 대체 저장소는 아직 없음.
- Windows 자격 증명 관리자는 값 길이 제한(약 2.5KB)이 있어 4096비트 RSA 키는 안 들어갈 수 있음 → Windows 빌드할 때 확인.
- 업타임·OS·부트 볼륨·열린 포트는 "—" (SSH 이후 단계에서 채움).
- 개인키 passphrase 입력칸 없음 (OCI 기본 키는 passphrase 없음).

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
- ~~"계정 추가" 버튼 키보드 포커스~~ → 2단계에서 `DashedButton`으로 해결.
- 실제 창에서 macOS 타이틀바 드래그·신호등 위치 눈으로 확인 필요 (사용자 확인).
