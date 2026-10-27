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

`main` 푸시 때(또는 수동 실행) macOS·Windows에서 테스트 → 패키징(자가 점검) → **정식 릴리스**를 만든다.

- **버전:** `v1.0.0`부터 시작해서 푸시마다 마지막 `v*` 태그의 PATCH가 하나 올라간다(`v1.0.1`, `v1.0.2`, …). 1.1.0처럼 올리고 싶으면 Actions → build → Run workflow의 `version`에 직접 적는다. 정한 버전은 Gradle에 `-PappVersion`으로 넘어가 앱 안의 버전 표시와 `jpackage` 버전이 된다.
- **릴리스:** 제목 "InfraDesk <버전>", GitHub의 "latest". 앱은 `releases/latest`만 보고 **버전 번호**(1.0.9 < 1.0.10)로 새 버전을 판단한다.
- **파일(버전이 이름에 들어가지 않아 다운로드 주소가 고정):** `InfraDesk-macOS.dmg`(첫 설치용), `InfraDesk-macOS.zip`(앱 안 업데이트용), `InfraDesk-windows.zip`, `SHA256SUMS.txt`
- 이전 버전은 릴리스 목록에 그대로 남는다(보관용 릴리스를 따로 만들지 않는다). 변경 목록은 직전 `v*` 태그 이후 커밋이다.
- 릴리스 설명은 `.github/release-notes.md`.
- 새 푸시는 앞 실행이 끝난 뒤에 돈다(`cancel-in-progress: false`): 릴리스를 올리는 도중에 취소되면 반쯤 만들어진 릴리스가 남기 때문이다.

**첫 정식 릴리스(1.0.0)에서만:** 베타 때 설치된 앱은 롤링 `beta` 릴리스만 보므로, 같은 빌드를 `InfraDesk-beta-*` 이름으로 `beta` 릴리스에도 한 번 올린다(직전 beta는 `beta-<번호>`로 보관). 옛 앱이 평소처럼 업데이트하면서 정식 1.0.0이 되고, 그 뒤로는 정식 릴리스만 본다. 이 단계는 첫 릴리스(`v*` 태그가 하나도 없을 때)에만 돌고 이후에는 `beta`를 건드리지 않는다. 오래된 `beta-<번호>` 보관 릴리스는 `delete-beta-archives.yml`을 손으로 실행하면 지워진다(확인란에 `DELETE` 입력).

## 실행 옵션

`--minimized`(메뉴 막대에서만 시작), `--debug-input`, `--self-test`, `--migrate-secrets`

- `--self-test`: 패키징된 앱이 제대로 뜨는지 점검하고 종료한다. 앱 안 업데이트도 새 빌드에 이걸 먼저 돌려 본다.
- `--migrate-secrets`: 옛 항목별 키체인 비밀값을 금고로 옮긴다 → [architecture/storage.md](architecture/storage.md)
