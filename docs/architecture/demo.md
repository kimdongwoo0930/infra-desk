# 데모 모드

[← 개요](README.md)

## 무엇이고 왜 있나

실제 API 키나 서버 없이 앱 화면과 흐름을 확인하려는 **개발용 모드**다. 같은 코드를 실제 부품 대신 가짜 부품으로 조립해서 실행한다. 화면을 사용자에게 보여 주거나 확인할 때 쓴다.

- 실제 설정 파일과 키체인을 **읽지도 쓰지도 않는다.**
- 네트워크를 쓰지 않는다(지연 시간만 흉내 낸다).
- 앱 구조를 이해하는 데는 필요 없다. 다른 구조 문서는 실제 모드만 설명한다.

## 실행

```bash
./gradlew runDemo    # 데모 모드로 앱 실행 (가짜 데이터, 키 불필요)
./gradlew snapshot   # 데모 데이터로 화면을 오프스크린 렌더링 → build/snapshots/*.png
```

앱 옵션으로는 `--demo`(또는 `-Dinfradesk.demo=true`)다.

## 어떻게 바꿔 끼우나

`ui`와 `service`는 인터페이스(`SecretStore`, `ShellConnector`, `CloudProvider` 등)만 보고 일한다. 그래서 `app/InfraDeskApp`이 시작할 때 구현체만 바꿔 조립하면 같은 화면이 가짜 데이터로 돈다.

| | 실제 (`./gradlew run`) | 데모 (`./gradlew runDemo`) |
|---|---|---|
| 계정 저장 | `JsonAccountStore` | `InMemoryAccountStore` (가짜 계정 3개로 시작) |
| SSH 설정 | `JsonSshSettingsStore` | `InMemorySshSettingsStore` (없어도 `ubuntu`로 접속) |
| SSH 연결 | `MinaShellConnector` + `known_hosts` | `DemoShellConnector` (가짜 셸) |
| 저장된 명령어 | `JsonSavedCommandStore` | 목업 예시 4개 (메모리) |
| 알림 | `DiscordNotifier` (웹훅) | 토스트 미리보기 (전송 안 함) |
| 비밀값 | `VaultSecretStore` (키체인엔 마스터 키만) | `InMemorySecretStore` |
| provider | `OracleProviderFactory`, `SshHostProviderFactory` | `DemoProviderFactory` (클라우드), SSH는 항상 응답하는 가짜 연결 확인 |
| 네트워크 | OCI API | 없음 (지연 시간만 흉내) |

## 가짜 부품들이 있는 곳

| 위치 | 내용 |
|---|---|
| `provider/demo/` | `DemoProvider`: 가짜 클라우드. 네트워크 지연과 상태 전이(시작 → 시작 중 → 실행 중)를 흉내 낸다. `DemoData`는 가짜 계정·서버·저장된 명령어, `DemoMetrics`는 가짜 그래프 데이터, `DemoProviderFactory`는 팩토리 |
| `ssh/Demo*` | `DemoShellConnector`(미리 준비한 출력으로 답하는 가짜 셸), `DemoContainerShell`(`docker exec -it` 응답), `DemoDocker`(가짜 컨테이너. 시작/정지/재시작에 따라 상태가 바뀜), `DemoRemoteFiles`(메모리 파일 트리), `DemoLocalFiles`(SFTP 창 왼쪽의 "내 컴퓨터". 실제 홈 폴더 대신 가짜 파일이 든 임시 폴더만 보이고 그 밖으로는 못 나감) |
| `storage/InMemory*` | 비영속 저장소. 데모와 테스트에서 쓴다 |
| `app/InfraDeskApp` | `demoService()`, `demoTerminalService()`, `demoAlertService()`가 가짜 부품으로 조립한다. 데모의 알림은 기기 밖으로 나가지 않고 "디스코드 알림 미리보기 (데모 · 전송 안 함)" 토스트로만 보인다 |
| `ui/TitleBar` | 데모일 때 "데모" 배지를 보여 준다 |

## 규칙

새 기능을 만들면 데모 모드에서도 동작하도록 가짜 데이터를 같이 만든다.
