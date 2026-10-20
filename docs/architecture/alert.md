# alert: 상태 변화를 디스코드로 알리기

[← 개요](README.md)

> **현재 꺼져 있다.** `AlertService.AVAILABLE = false`라서 설정 화면에 "준비 중"으로 표시되고, `MainFrame`은 인벤토리와 CPU 값을 알림 쪽에 넘기지 않는다(`expectChange`만 항상 호출한다). 실제 디스코드 채널에서 시험하기 전까지 켜지 않기로 했다. 코드와 테스트는 그대로 남아 있고, 값을 `true`로 바꾸면 켜진다.

## 왜 있나

서버가 갑자기 꺼지거나 CPU가 계속 높을 때, 앱 창을 보고 있지 않아도 알 수 있게 하려는 기능이다.

## 구성: 감지와 전송을 분리

```
   MainFrame ──► AlertService ──► AlertMonitor ──► (알림 목록) ──► 전송 스레드 ──► Notifier
   (인벤토리·CPU)   설정·전송 관리     감지만 한다                       한 줄로 순서대로     │
                                    (순수 상태 기계)                                      ├ DiscordNotifier (실제)
                                                                                         └ 토스트 미리보기 (데모)
```

| 클래스 | 역할 |
|---|---|
| `AlertMonitor` | **감지만 한다.** 인벤토리와 CPU를 받아 "무엇이 달라졌나"를 비교해서 `Alert` 목록을 돌려준다. 네트워크·파일을 쓰지 않는 순수한 상태 기계라서 시계만 주입하면 테스트할 수 있다 |
| `AlertService` | 알림 설정 저장, 웹훅 URL 비밀값 관리, 전송을 **단일 백그라운드 스레드**에서 한다. 그래서 알림은 순서대로 나가고 UI를 막지 않는다 |
| `Notifier` | 알림을 내보내는 인터페이스 |
| `DiscordNotifier` | 웹훅으로 embed를 보낸다. 웹훅 URL은 보낼 때마다 비밀값 저장소에서 읽고, 로그나 오류 메시지에 넣지 않는다 |
| `Alert`, `AlertSettings`, `AlertException` | 알림 하나, 설정(어떤 알림을 보낼지), 전송 실패 예외 |

## 어떤 알림이 가나

| 종류 | 조건 | 복구 알림 |
|---|---|---|
| 서버 다운 | 실행 중이던 서버가 **앱의 요청 없이** 멈춤 | 돌아오면 |
| 계정 오류 | 계정에 연결할 수 없음 | 복구되면 |
| CPU 높음 | CPU가 기준(%) 이상인 상태가 연속 N회 확인됨 | 내려가면 |

같은 문제에 대해 알림은 **한 번만** 가고, 짝이 되는 "복구" 알림이 간다.

사용자가 앱에서 정지·재부팅한 서버는 `AlertService.expectChange(serverId)`로 알려 두면 15분 동안 "서버 다운"으로 취급하지 않는다. 내가 끈 것을 문제로 알리지 않기 위해서다.

## 데이터가 들어오는 길

```
MainFrame.setInventory ─► AlertService.onInventory ─► AlertMonitor (상태 변화 비교)
1분 CPU 조회           ─► AlertService.onCpu       ─► AlertMonitor (연속 N회 기준 이상)
정지·재부팅 요청         ─► AlertService.expectChange (15분 동안 해당 서버 제외)
알림 ─► 전송 스레드 ─► DiscordNotifier (실제) / Toast 미리보기 (데모) ─► 실패 시 Toast
```

## 데모 모드

데모에서는 디스코드로 보내지 않고 화면에 "디스코드 알림 미리보기 (데모 · 전송 안 함)" 토스트만 띄운다(`app/InfraDeskApp.demoAlertService`).
