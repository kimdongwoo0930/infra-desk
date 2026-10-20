# 구조

구조 문서는 [`docs/architecture/`](architecture/README.md)로 나뉘어 있다. 처음이면 **개요부터** 읽는다.

| 문서 | 내용 |
|---|---|
| [개요](architecture/README.md) | 모듈 일곱 개, 의존 방향, [정지] 버튼 한 번을 따라가기, 실제 모드 vs 데모 모드 |
| [core](architecture/core.md) | 공통 모델과 `CloudProvider` 인터페이스 |
| [service](architecture/service.md) | 업무 계층: 서버 조회·제어, 조회 주기, 터미널 서비스, 업데이트 |
| [provider](architecture/provider.md) | OCI / 직접 연결 / 데모 구현, OCI 대응표, 새 클라우드 추가 방법 |
| [ssh](architecture/ssh.md) | SSH 접속, 셸·SFTP·Docker |
| [storage](architecture/storage.md) | 설정 파일, 암호화 금고, 파일 위치 |
| [alert](architecture/alert.md) | 디스코드 알림 (현재 꺼짐) |
| [ui](architecture/ui.md) | 화면 구성, `MainFrame`, 클래스 안내 |
| [BUILD](BUILD.md) | 빌드, 실행, 패키징, CI |

구조가 바뀌면 해당 모듈 문서를 고친다. 모듈 사이의 연결이 바뀌면 개요도 같이 고친다.
