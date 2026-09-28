# infra-desk
여러 클라우드 계정의 서버를 한 곳에서 관리하는 데스크톱 앱 — 상태 모니터링, 전원 제어, SSH 터미널

## 다운로드 (베타)

`main`에 푸시될 때마다 자동으로 빌드되어 [Beta 릴리스](https://github.com/kimdongwoo0930/infra-desk/releases/tag/beta)에 올라갑니다.

- macOS: [InfraDesk-beta-macOS.dmg](https://github.com/kimdongwoo0930/infra-desk/releases/download/beta/InfraDesk-beta-macOS.dmg)
- Windows: [InfraDesk-beta-windows.zip](https://github.com/kimdongwoo0930/infra-desk/releases/download/beta/InfraDesk-beta-windows.zip)

서명되지 않은 앱이라 처음 열 때 안내가 필요해요 — [릴리스 설명](https://github.com/kimdongwoo0930/infra-desk/releases/tag/beta) 참고.

## 직접 빌드 (macOS)

```bash
./gradlew dmg
open build/dist/InfraDesk-1.0.0.dmg   # InfraDesk를 응용 프로그램 폴더로 끌어다 놓기
```
Java 런타임이 들어 있어 따로 설치할 필요가 없습니다. Windows는 `./gradlew windowsZip` (Windows에서 실행).

## 개발 중 실행

Java 25가 필요합니다. Gradle은 Wrapper가 포함돼 있어 따로 설치하지 않아도 됩니다.

```bash
./gradlew runDemo    # 데모 모드: 키 없이 가짜 서버로 실행
./gradlew run        # 실제 모드: 등록한 클라우드 계정에 연결
./gradlew test       # 테스트
```

## 문서
- [구조](docs/ARCHITECTURE.md)
- [개발 기록](docs/DEVLOG.md)
- [디자인 스펙](docs/design/DESIGN.md)
