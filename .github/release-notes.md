## 설치

### macOS (Apple Silicon)
1. `InfraDesk-beta-macOS.dmg`를 받아 열고, **InfraDesk**를 **응용 프로그램** 폴더로 끌어다 놓습니다.
2. 서명·공증되지 않은 앱이라 처음 열 때 macOS가 막습니다. 터미널에서 한 번 실행하세요:
   ```
   xattr -dr com.apple.quarantine /Applications/InfraDesk.app
   ```
   또는 **시스템 설정 → 개인정보 보호 및 보안**에서 "그래도 열기".
3. Java는 앱에 들어 있어 따로 설치할 필요가 없습니다.

### Windows
1. `InfraDesk-beta-windows.zip`을 받아 원하는 곳에 압축을 풉니다.
2. `InfraDesk\InfraDesk.exe`를 실행합니다. SmartScreen 경고가 뜨면 **추가 정보 → 실행**.

## 업데이트
새 빌드가 나오면 앱이 알려 줘요. **메뉴 막대(트레이) 아이콘 → 새 베타 빌드 설치**, 또는 **설정 → 앱 정보 → 업데이트 확인**을 누르면 앱이 새 빌드를 직접 받습니다. 그다음 `SHA256SUMS.txt`와 대조하고, 새 버전으로 자가 점검을 한 뒤 교체하고 다시 엽니다. 앱이 직접 받은 파일이라 위의 `xattr` 명령은 처음 설치할 때 한 번만 필요해요. 업데이트 뒤 macOS가 키체인 암호를 한 번 물어볼 수 있어요. 빌드마다 임시 서명이 달라서예요.

## 참고
- 설정은 이 PC에만 저장됩니다 (macOS: `~/Library/Application Support/InfraDesk`, Windows: `%APPDATA%\InfraDesk`). 비밀값은 암호화 파일에, 그 키는 OS 키체인/자격 증명 관리자에 있습니다.
- 키 없이 둘러보려면 데모 모드: `InfraDesk --demo` (macOS: `/Applications/InfraDesk.app/Contents/MacOS/InfraDesk --demo`).
