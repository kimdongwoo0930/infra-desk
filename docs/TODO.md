# 해야 할 일

지금 시점에 남은 일을 모은 목록이다. 한 일의 기록은 [DEVLOG.md](DEVLOG.md)에 있고, 끝낸 항목은 여기서 지운다.
크기는 대략적인 추정이다: **S** 반나절 이내, **M** 하루 안팎, **L** 며칠.

## 코드 구조 개선

우선순위 순서. 위쪽일수록 다른 작업을 막거나 실수를 부르기 쉽다.

### 1. SFTP 화면 코드 나누기 (M)

SFTP 작업으로 두 클래스가 한 번에 너무 많은 일을 하게 됐다. 줄 수는 2026-10-20 기준.

| 클래스 | 줄 수 | 지금 하는 일 | 나눌 방향 |
|---|---|---|---|
| `SftpDialog` | 797 | 전송 결정(덮어쓰기 묻기), 삭제·이름 바꾸기·권한·새 파일/폴더 흐름, **서버 파일 편집 상태 기계**, 끌어서 놓기 연결, 우클릭 메뉴, 창 닫기 확인 | 편집(`EditSession`, `checkEdits` 등 약 150줄)을 `RemoteEditController`로, 파일 작업 흐름(삭제·이름·권한)을 별도 클래스로 |
| `FilePane` | 776 | 표 화면, 끌어서 놓기, `Source` 구현 2개(익명 클래스), 오류 문구 변환, 권한 안내 문구 | `Source`와 두 구현을 `FileSources`로, 오류·안내 문구를 `FileErrors`로, 끌어서 놓기를 별도 클래스로 |

- **왜 먼저 하나:** `SftpDialog`는 `JDialog`라서 헤드리스 테스트가 안 된다. 그래서 전송 결정·편집 흐름·삭제 확인 같은 **가장 위험한 로직이 자동 테스트 없이** 스냅샷으로만 확인된다. Swing을 모르는 컨트롤러로 빼면 `./gradlew test`로 검증할 수 있다.
- 이름 검사(`SftpDialog.nameProblem`)처럼 화면과 무관한 정적 함수도 같이 뺀다.

### 2. 같은 오류 변환이 두 군데 (S)

`PERMISSION_DENIED` → "권한이 없어요" 같은 변환이 `FilePane.friendly`(ui)와 `TransferQueue.describe`(ssh)에 **따로** 있다. 한쪽만 고치면 문구가 어긋난다.

- `ssh` 패키지에 `SftpErrors` 하나를 두고 ui가 가져다 쓴다(`ssh`는 아무것도 import하지 않는 규칙을 지킨다).
- macOS 개인정보 보호 안내(`FilePane.permissionHint`)는 화면 문구이므로 ui에 남긴다.

### 3. `MainFrame` 나누기 (L)

1,148줄. 서버 조회 타이머·제어 요청·메트릭·실시간 모드·컨테이너·업데이트·계정 관리를 직접 한다. [구조 문서](architecture/ui.md)에 일 목록이 정리돼 있다.

- 조회와 제어(`poll`, `reload`, `runAction`)를 컨트롤러로 분리해서 타이머·병합 규칙을 테스트 가능하게 한다.
- `SshMetricsService`와 `ContainerService`를 `MainFrame`이 직접 만든다(114, 117행). 다른 서비스처럼 `app`에서 만들어 받는다.
- 한 번에 다 하지 말고, 이번 SFTP처럼 기능 단위로 하나씩 뺀다.

### 4. 모듈 사이 순환·잘못된 위치의 모델 (M)

[개요](architecture/README.md)에 "알려진 어색한 점"으로 기록한 것.

- `alert` ↔ `storage`가 서로 import한다(`AlertSettings`는 `alert`에, `AlertSettingsStore`는 `storage`에 있어서).
- `storage`가 `ssh`의 `SshSettings`·`SavedCommand`, `alert`의 `AlertSettings`를 import한다.
- 해결: 세 모델을 `core`로 옮긴다. 옮기면 `core`가 아무것도 import하지 않는 규칙은 그대로고, 순환이 사라진다.

### 5. 작은 정리 (S)

- `DemoRemoteFiles.upload`의 이어올리기 합치기 코드가 불필요하게 복잡하다(`merged`/`result` 배열 두 번 복사). 한 줄로 줄일 수 있다.
- `ExternalEditor.launcherOverride`는 스냅샷이 편집기를 흉내 내려고 리플렉션으로 바꾸는 연결점이다. 컨트롤러로 나눌 때(1번) 생성자로 주입하는 방식으로 바꾸면 리플렉션이 필요 없다.

## 확인이 필요한 가정

구조 개선은 아니지만, 코드가 **확인하지 못한 것에 기대고** 있어서 적어 둔다.

- **한 SFTP 연결을 탐색과 전송이 동시에 써도 안전한지** 확인하지 못했다. 그래서 전송 대기열이 별도 연결을 쓴다(`TransferQueue`). MINA `SftpClient`의 스레드 안전성을 확인하면 연결 하나로 줄일 수 있다.
- 서버 파일 편집은 macOS `open -t`, Windows `Desktop.edit` → `notepad`, Linux `xdg-open`을 쓴다. 실제 편집기에서 저장했을 때의 감지는 직접 써 보고 확인해야 한다.

## 그 밖에 남은 일 (구조 개선 아님)

| 항목 | 상태 |
|---|---|
| 디스코드 알림 켜기 | `AlertService.AVAILABLE = false`. 실제 채널에서 시험한 뒤 켠다 |
| Windows 서버 지원 | 보류(사용자: "추후에") |
| 설정 내보내기/가져오기 | 후보. 계정·서버·SSH 키를 비밀번호로 암호화한 파일 하나로 |
| SFTP 직접 확인 | F2·Delete, 삭제 확인 창, 권한 창, 실제 편집기 저장, Windows, 실제 서버 전송 |
| 앱 안 에디터 | 후보. 외부 편집기 대신 앱 안에서 편집(문법 강조는 새 라이브러리 필요) |
| PRD 갱신 | "파일 직접 접근"과 Docker 관리는 이미 구현됐는데 "미래/이후 확장"에 있다 |
