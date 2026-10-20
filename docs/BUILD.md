# 빌드·실행·패키징

코드 구조는 [architecture/README.md](architecture/README.md).

## 명령

```bash
./gradlew run        # 앱 실행
./gradlew test       # 테스트
./gradlew dmg        # macOS: build/dist/InfraDesk-<버전>.dmg (패키징된 앱 자가 점검 포함)
./gradlew windowsZip # Windows: build/dist/InfraDesk-<버전>-windows.zip
./gradlew generateIcons  # 앱 아이콘 다시 그리기 (src/packaging, 런타임 아이콘)
```

## 패키징 파이프라인

```
installDist → appImage (jpackage + jlink 필요한 모듈만) → selfTestAppImage (앱 실행 파일로 --self-test)
           → dmg + macUpdateZip (macOS) / windowsZip (Windows)
```

- JVM 옵션: `build.gradle.kts`의 `appJvmArgs`(SerialGC, `-Xms16m -Xmx256m`, 빈 힙 반환, C1 전용). `run`·jpackage가 함께 쓴다. 근거는 DEVLOG "메모리 줄이기"
- 아이콘: `src/packaging/macos/InfraDesk.icns`, `src/packaging/windows/InfraDesk.ico` (`tools.IconGenerator`가 생성)

## CI (`.github/workflows/build.yml`)

`main` 푸시 때만 macOS·Windows에서 테스트 → 패키징(자가 점검) → **`beta` 시험판 릴리스**를 갱신한다.

- 교체되는 파일: `InfraDesk-beta-macOS.dmg`(첫 설치용), `InfraDesk-beta-macOS.zip`(앱 안 업데이트용), `InfraDesk-beta-windows.zip`, `SHA256SUMS.txt`
- 같은 파일을 빌드별 보관 릴리스 `beta-<번호>`(직전 빌드 이후 커밋 목록 포함)로도 올린다.
- 보관 릴리스는 `delete-beta-archives.yml`을 손으로 실행해야 지워진다(확인란에 `DELETE` 입력).
- 릴리스 설명은 `.github/release-notes.md`.

## 실행 옵션

`--minimized`(메뉴 막대에서만 시작), `--debug-input`, `--self-test`, `--migrate-secrets`

- `--self-test`: 패키징된 앱이 제대로 뜨는지 점검하고 종료한다. 앱 안 업데이트도 새 빌드에 이걸 먼저 돌려 본다.
- `--migrate-secrets`: 옛 항목별 키체인 비밀값을 금고로 옮긴다 → [architecture/storage.md](architecture/storage.md)
