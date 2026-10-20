# storage: 설정과 비밀값을 저장하는 곳

[← 개요](README.md)

## 왜 있나

앱이 껐다 켜도 계정·SSH 설정·저장된 명령어가 남아야 한다. 그중 **API 개인키, SSH 키, 웹훅 URL 같은 비밀값은 평문 파일에 두면 안 된다.** `storage`는 이 둘을 나눠서 저장한다.

| 종류 | 예 | 저장 방식 |
|---|---|---|
| **설정** (비밀 아님) | 계정 이름·리전·OCID, SSH 사용자·포트, 저장된 명령어 | JSON 파일 |
| **비밀값** | API 개인키, SSH 개인키·암호, 디스코드 웹훅 URL | 암호화된 금고 파일 + OS 키체인 |

## 인터페이스 + 구현

저장소마다 인터페이스가 있고 `service`는 인터페이스만 안다.

| 인터페이스 | 구현 | 저장하는 것 |
|---|---|---|
| `AccountStore` | `JsonAccountStore` | 계정 설정 |
| `SshSettingsStore` | `JsonSshSettingsStore` | 서버별 SSH 사용자·포트 |
| `SavedCommandStore` | `JsonSavedCommandStore` | 저장된 명령어 |
| `AlertSettingsStore` | `JsonAlertSettingsStore` | 알림 설정 |
| `SecretStore` | `VaultSecretStore` (+ `KeychainSecretStore`) | 비밀값 |

## 비밀값이 저장되는 방식

비밀값마다 OS 키체인 항목을 만들면, 서명되지 않은 앱에서는 macOS가 거의 클릭마다 로그인 비밀번호를 물었다. 그래서 구조를 바꿨다.

```
 비밀값들 ──(AES-256-GCM 암호화)──► secrets.vault   파일 하나, 권한 rw-------
                                        ▲
                       복호화 열쇠(256비트) ── OS 키체인 항목 단 하나 (vault.masterKey)
```

- **열쇠 하나만** OS 키체인(macOS 키체인 / Windows 자격 증명 관리자)에 있다. 서비스 이름 `InfraDesk`, 항목 `vault.masterKey`.
- 키체인은 **실행당 최대 한 번** 읽는다. 복호화한 값은 메모리에만 둔다.
- `VaultSecretStore`가 이 구조를 구현한다. `KeychainSecretStore`는 마스터 키를 꺼내고, 이전 버전(항목별 키체인)의 값을 옮겨 올 때 쓴다.
- `./gradlew run`으로 옛 항목을 한 번에 옮기려면 `--migrate-secrets` 옵션을 쓴다.

파일 형식: `"IDV1"` 매직 값, 12바이트 IV, JSON 맵의 AES-GCM 암호문(128비트 태그).

### 금고 안의 키 이름

| 금고 키 이름 | 내용 |
|---|---|
| `account.<accountId>.privateKey` | OCI API 개인키 |
| `server.<serverId>.sshKey` | SSH 개인키 |
| `server.<serverId>.sshPassphrase` | SSH 키 암호 (있을 때만) |
| `alerts.discordWebhook` | 디스코드 웹훅 URL |

키 이름은 `SecretStore.accountKey(...)`나 `TerminalService`의 `serverKey(...)` 같은 곳에서 만든다.

## 파일 위치

`AppPaths`가 OS별 위치를 알려준다.

| OS | 설정 디렉터리 |
|---|---|
| macOS | `~/Library/Application Support/InfraDesk/` |
| Windows | `%APPDATA%\InfraDesk\` |
| 기타 | `$XDG_CONFIG_HOME/infradesk` 또는 `~/.config/infradesk` |

| 파일 | 내용 |
|---|---|
| `accounts.json` | 계정 설정 (OCID, fingerprint, 리전) |
| `ssh-settings.json` | 서버별 SSH 사용자 이름·포트 |
| `secrets.vault` | 모든 비밀값 (암호화) |
| `commands.json` | 저장된 명령어 (이름, 명령) |
| `alerts.json` | 알림 켜기/끄기, 종류, CPU 기준 |
| `app-settings.json` | 업데이트 자동 확인 여부 |
| `known_hosts` | 신뢰한 SSH 호스트 키 (OpenSSH 형식) |

로그는 설정 디렉터리가 아니라 별도 위치에 쌓인다.

| OS | 로그 위치 |
|---|---|
| macOS | `~/Library/Logs/InfraDesk/infradesk-N.log` |
| Windows | `%LOCALAPPDATA%\InfraDesk\logs` |

로그에는 비밀값이 가려진 상태로 남는다(`app/Logging`).

JSON 파일은 임시 파일에 먼저 쓴 뒤 이름을 바꿔서 저장한다. 쓰는 도중에 앱이 꺼져도 파일이 깨지지 않는다.

## 주의

- 비밀값은 **절대 평문 파일, 로그, 테스트 코드에 넣지 않는다.** 테스트에는 가짜 값을 쓴다.
- 저장소가 `ssh`(`SshSettings`, `SavedCommand`)와 `alert`(`AlertSettings`)의 모델 클래스를 import한다. 모델이 쓰는 쪽 패키지에 있어서 생긴 구조다 → [개요의 설명](README.md#의존-방향)
