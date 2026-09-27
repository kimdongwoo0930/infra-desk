# infra-desk
여러 클라우드 계정의 서버를 한 곳에서 관리하는 데스크톱 앱 — 상태 모니터링, 전원 제어, SSH 터미널

## 실행

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
