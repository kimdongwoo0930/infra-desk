# Git 작업 가이드

이 저장소에서 브랜치를 만들고, 커밋하고, PR을 올리고, 머지하는 방법이다. 직접 작업할 때 보는 용도이고, 명령은 복사해서 그대로 쓸 수 있게 적었다. PR과 머지는 GitHub CLI(`gh`)로 한다.

> 이 저장소는 **공개(public)** 이다. 커밋하기 전에 실제 서버 IP, 키, 계정 정보, 개인 이메일이 들어가지 않았는지 꼭 확인한다 ([PR 전 체크리스트](#pr-전-체크리스트)).

## 1. 한눈에 보기

```
feat/xxx ─┐
fix/xxx  ─┼─► PR ─► develop ─► PR(직접) ─► main ─► CI가 beta 릴리스 갱신
docs/xxx ─┘
```

| 브랜치 | 역할 | 규칙 |
|---|---|---|
| `main` | 배포용. 여기에 들어가면 CI가 베타 릴리스를 갱신한다 | **`develop`에서만** PR로 들어온다. 직접 푸시하지 않는다 |
| `develop` | 개발 통합. 모든 작업이 여기로 모인다 | 직접 푸시하지 않는다. PR로만 합친다 |
| `feat/…`, `fix/…` 등 | 작업 하나당 브랜치 하나 | **항상 최신 `develop`에서 만든다.** 끝나면 `develop`으로 PR |

## 2. 브랜치 이름

형식: `종류/짧은-설명`

| 종류 | 언제 | 예 |
|---|---|---|
| `feat/` | 새 기능 | `feat/file-sftp`, `feat/ssh-session-pool` |
| `fix/` | 버그 수정 | `fix/ssh-polling-limits` |
| `docs/` | 문서만 바꿀 때 | `docs/todo`, `docs/git-guide` |
| `chore/` | 기능과 무관한 정리(주석, 설정, 의존성, 동기화) | `chore/korean-comments`, `chore/sync-main` |
| `refactor/` | 동작은 그대로 두고 구조만 바꿀 때 | `refactor/split-main-frame` |
| `test/` | 테스트만 추가·수정 | `test/transfer-queue` |

규칙
- **영어 소문자와 하이픈(`-`)만** 쓴다. 공백, 한글, 대문자, 밑줄은 쓰지 않는다. (`feat/fileSTMP` 같은 오타는 이름을 바꾸기 번거롭다. 만들기 전에 한 번 더 본다.)
- 3~4단어 이내로 **무엇을 하는지** 보이게 쓴다. `feat/new-stuff`, `fix/bug` 같은 이름은 피한다.
- 초기에 `feature/…`도 썼지만 앞으로는 **`feat/`** 로 통일한다.
- 한 브랜치에는 **한 가지 일**만 한다. 기능을 만들다 발견한 다른 버그는 `fix/…` 브랜치를 따로 판다. (그래야 PR이 작고 읽기 쉽다.)

## 3. 작업 시작: 브랜치 만들기

```bash
git switch develop            # develop으로 이동
git pull origin develop       # 최신으로 받기 (이 단계를 빼먹지 않는다)
git switch -c feat/내-작업    # 새 브랜치를 만들고 이동
```

작업 폴더에 커밋하지 않은 변경이 있으면 브랜치를 옮기기 전에 먼저 커밋하거나 정리한다. 지금 어느 브랜치인지 헷갈리면:

```bash
git branch --show-current     # 현재 브랜치 이름
git status                    # 바뀐 파일 목록
```

## 4. 커밋

```bash
git add 파일1 파일2           # 올릴 파일만 골라서 (git add . 은 확인 없이 쓰지 않는다)
git status                    # 올라갈 파일이 맞는지 확인
git commit -m "SFTP 창을 내 컴퓨터/서버 2창 구조로 변경"
```

### 커밋 메시지

- **한국어**로 쓴다.
- 첫 줄은 **무엇을 했는지** 한 줄로(약 50자). "수정", "업데이트"처럼 뭉뚱그리지 않는다.
- 이유가 필요하면 한 줄 비우고 본문에 **왜** 바꿨는지, 눈에 띄는 변경을 적는다.

```
직접 연결 서버에 자동으로 SSH 접속하는 횟수 제한

분당 측정이 1분마다 새 SSH 접속을 맺어서 서버 인증 로그에 기록이 쌓였다.
실패해도 물러서지 않아 fail2ban에 차단될 수 있었다.

- SamplingGate 추가: 설정 문제는 첫 실패에 자동 접속을 멈춘다
- 창이 숨으면 서버당 5분에 한 번만 접속한다
```

### 커밋 단위

- 의미가 있는 단위로 나눠서 자주 커밋한다. 한 커밋은 **빌드가 되고 테스트가 통과하는 상태**로 둔다.
- 서로 다른 일(기능 + 무관한 오타 수정)은 다른 커밋으로 나눈다.

### 커밋하면 안 되는 것

- API 개인키(`.pem`), SSH 개인키, OCID, fingerprint, 계정 설정 파일, 웹훅 URL
- 실제 서버 IP·서버 이름·개인 이메일 (문서와 테스트에는 `203.0.113.x` 같은 예시 주소와 `my-server` 같은 가짜 이름을 쓴다)
- `.mcp.json` (이미 `.gitignore`에 있다), `build/` 폴더

## 5. 푸시와 PR (gh)

PR은 GitHub CLI(`gh`)로 만든다.

### gh 설치와 로그인 (처음 한 번)

```bash
brew install gh                    # macOS
winget install --id GitHub.cli     # Windows (설치 후 터미널을 다시 연다)
gh --version                       # 설치 확인
gh auth login                      # GitHub.com → HTTPS → 브라우저로 로그인
gh auth status                     # 로그인됐는지 확인
```

이 저장소 폴더 안에서 `gh`를 실행하면 저장소(`kimdongwoo0930/infra-desk`)를 알아서 인식한다. 못 찾는다는 오류가 나면 한 번만 `gh repo set-default kimdongwoo0930/infra-desk`를 실행한다.

### 푸시

```bash
git push -u origin feat/내-작업     # 처음 올릴 때 (-u로 이후 git push만 써도 된다)
git push                           # 같은 브랜치에 커밋을 더 올릴 때
```

### PR 만들기

> ⚠ **이 저장소의 기본 브랜치는 `main`이다.** `gh pr create`는 `--base`를 주지 않으면 **`main`을 대상으로** PR을 만든다. 작업 PR에는 **항상 `--base develop`** 을 붙인다. 빠뜨리면 변경이 수십 개 파일로 보이고 "Can't automatically merge"가 뜬다.

```bash
gh pr create --base develop \
  --title "SFTP: 끌어서 놓기와 서버 파일 편집" \
  --body-file - <<'EOF'
## 변경
- 무엇을 바꿨는지 (사용자 입장에서)

## 이유
- 왜 필요했는지 (필요할 때만)

## 테스트
- ./gradlew test 통과 (N개)
- 직접 확인한 것 / 아직 확인하지 못한 것

## 확인 필요
- 리뷰할 때 봐 줬으면 하는 부분
EOF
```

현재 브랜치가 아직 푸시되지 않았으면 `gh`가 푸시할지 물어본다. 성공하면 PR 주소가 출력되고, 끝의 숫자가 PR 번호다(`…/pull/12` → 12번).

| 옵션 | 뜻 |
|---|---|
| `--base`, `-B` | PR을 받을 브랜치. **`develop`** (배포 PR은 `main`) |
| `--head`, `-H` | 합칠 브랜치. 생략하면 현재 브랜치 |
| `--title`, `-t` | 제목 |
| `--body`, `-b` | 설명(짧을 때 한 줄로) |
| `--body-file`, `-F` | 설명을 파일에서 읽음. `-`이면 위 예시처럼 표준입력(heredoc) |
| `--fill` | 커밋 메시지로 제목·설명을 자동으로 채움. 커밋이 하나일 때 쓸 만하다 |
| `--draft`, `-d` | 초안 PR. 아직 머지하면 안 될 때 |
| `--web`, `-w` | 터미널 대신 브라우저에서 PR 작성 화면을 연다 |

짧은 PR은 이렇게 한 줄로도 된다.

```bash
gh pr create --base develop --title "docs: Git 가이드 추가" --body "GIT.md 추가"
```

`--base`를 매번 치기 번거로우면 별칭을 만들어 둘 수 있다. (안 되면 `--base`를 직접 붙인다.)

```bash
gh alias set prd 'pr create --base develop'     # 한 번만
gh prd --title "제목" --body "설명"              # 이후 이렇게
```

### PR 확인과 수정

```bash
gh pr view                         # 현재 브랜치의 PR 내용 (--web 을 붙이면 브라우저로)
gh pr list                         # 열려 있는 PR 목록
gh pr status                       # 내 PR과 현재 브랜치 PR 상태
gh pr diff                         # PR에 포함된 변경 내용
gh pr checks                       # CI 결과 (작업 PR에는 CI가 없어서 "no checks reported"가 정상이다)
gh pr view --json mergeable,mergeStateStatus     # MERGEABLE이면 충돌 없음, CONFLICTING이면 충돌
```

| 하고 싶은 일 | 명령 |
|---|---|
| PR에 변경을 더 올리기 | 같은 브랜치에 커밋하고 `git push` (PR이 자동으로 갱신된다) |
| 제목·설명 고치기 | `gh pr edit --title "새 제목" --body "새 설명"` |
| **base를 잘못 지정했다** | `gh pr edit --base develop` |
| 초안을 정식 PR로 | `gh pr ready` |
| PR 닫기(머지 없이) | `gh pr close` |
| 코멘트 남기기 | `gh pr comment --body "내용"` |
| 다른 PR의 브랜치로 이동 | `gh pr checkout 12` |

## 6. 머지 (gh)

머지는 **머지 커밋**으로 한다. 지금까지 모든 PR이 이 방식이다(`Merge pull request #7 from …`가 생긴다). Squash나 Rebase는 쓰지 않는다. 브랜치의 커밋 기록이 그대로 남아야 나중에 이유를 추적하기 쉽다.

**머지 전에 확인한다.**
- `gh pr view --json mergeable,mergeStateStatus`에서 `MERGEABLE`이다. `CONFLICTING`이면 [충돌 해결](#8-내-브랜치를-최신-develop으로-맞추기)을 먼저 한다.
- `gh pr view`에서 **base가 의도한 브랜치**(`develop`)이다.

```bash
gh pr merge --merge --delete-branch        # 현재 브랜치의 PR을 머지 커밋으로 합치고 브랜치를 지운다
gh pr merge 12 --merge --delete-branch     # PR 번호로 지정
```

- **`--merge`를 꼭 붙인다.** 옵션 없이 실행하면 방식을 고르는 메뉴가 뜨는데, 이때 반드시 "Create a merge commit"을 고른다.
- `--delete-branch`는 머지된 작업 브랜치를 원격과 로컬에서 지운다. 지금 그 브랜치에 있었다면 `gh`가 기본 브랜치(`main`)로 옮겨 놓을 수 있으니, **머지 뒤에는 아래처럼 `develop`으로 돌아간다.**
- **`develop` → `main` 배포 PR에는 `--delete-branch`를 붙이지 않는다.** `develop` 브랜치가 지워진다. ([7번](#7-develop--main-배포))

### 머지 후 내 컴퓨터 정리

```bash
git switch develop
git pull origin develop                # 방금 머지된 내용 받기
git branch -d feat/내-작업             # 머지된 로컬 브랜치 삭제 (--delete-branch를 썼다면 이미 지워져 있다)
git fetch --prune                      # 원격에서 지워진 브랜치 정보 정리
git branch -vv                         # 남은 로컬 브랜치 확인 ([gone]이면 원격에서 지워진 것)
```

`-d`가 거부하면 아직 머지되지 않은 커밋이 있다는 뜻이다. 확실하지 않으면 `-D`(강제 삭제)를 쓰지 말고 먼저 `git log develop..브랜치이름`으로 어떤 커밋이 남았는지 본다.

## 7. develop → main (배포)

`main`으로 보내는 PR은 **직접** 한다. `main`에 머지되면 CI가 동작해서 **베타 릴리스(`beta`)를 갱신**한다. 설치된 앱의 업데이트 확인이 이 릴리스를 보므로, 머지는 곧 사용자에게 나가는 배포다.

**전에 확인할 것**
- `develop`에 올릴 기능이 모두 머지됐고 `./gradlew test`가 통과한다.
- `main`과 충돌이 없다 (PR 화면에 초록색 "Able to merge").
- `docs/DEVLOG.md`에 이번에 나가는 변경이 정리돼 있다. (앱 버전은 빌드 번호로 자동 계산되므로 손댈 필요가 없다.)

**PR 만들기와 머지**

```bash
gh pr create --base main --head develop \
  --title "릴리스: SFTP 파일 관리와 문서 정비" \
  --body-file - <<'EOF'
## 주요 변경
- ...

## 테스트
- ./gradlew test 통과 (N개)

## 확인 필요
- ...
EOF

gh pr view --json mergeable,mergeStateStatus   # MERGEABLE인지 확인
gh pr merge 번호 --merge                        # 머지 커밋으로 합치기. --delete-branch는 붙이지 않는다!
```

- 제목은 `릴리스: …`로 시작해서 주요 변경을 한 줄로 쓴다.
- `--merge`로 머지 커밋을 만든다. **`develop` 브랜치는 지우지 않는다**(`--delete-branch` 금지).
- 머지하면 CI가 동작한다. 진행을 보려면 `gh run list --workflow build.yml --limit 3`, 실시간으로 보려면 `gh run watch`.

**`main`에는 `develop`을 거치지 않고 다른 브랜치를 직접 합치지 않는다.** 이전에 `feature/update-message`를 `main`에 바로 머지해서 `main`에만 있는 커밋이 생겼고, 그 뒤 `develop` → `main` PR이 "Can't automatically merge"가 됐다. 그때는 `main`의 변경을 `develop`으로 가져와서(`chore/sync-main`) 해결했다. 급한 수정(hotfix)도 `develop`에서 `fix/…`로 만들어 `develop`을 거쳐서 올린다.

## 8. 내 브랜치를 최신 develop으로 맞추기

작업하는 동안 `develop`에 다른 변경이 들어갔으면 PR 전에 가져와서 맞춘다. **`merge`를 쓴다**(`rebase`나 강제 푸시는 쓰지 않는다. 이미 푸시한 브랜치의 기록을 바꾸면 안 된다).

```bash
git fetch origin
git merge origin/develop       # develop의 변경을 내 브랜치로 가져오기
```

충돌이 없으면 끝이다. 충돌이 나면 다음 순서로 푼다.

```bash
git status                     # 충돌한 파일 확인 ("both modified")
```

1. 충돌한 파일을 열면 이런 표시가 있다.
   ```
   <<<<<<< HEAD
   내 브랜치의 내용
   =======
   develop의 내용
   >>>>>>> origin/develop
   ```
2. **둘 중 맞는 쪽을 고르거나 둘을 합쳐서** 최종 내용만 남기고 `<<<<<<<`, `=======`, `>>>>>>>` 줄은 모두 지운다.
3. 푼 파일을 올리고 마무리한다.
   ```bash
   git add 푼-파일
   ./gradlew test               # 합친 결과가 정상인지 확인
   git commit                   # 편집기가 열리면 기본 메시지 그대로 저장하거나 설명을 고친다
   git push
   ```

충돌 표시가 남은 채로 커밋하지 않도록 `grep -rn '<<<<<<<' .`로 한 번 확인한다. 풀다가 엉켰으면 `git merge --abort`로 합치기 전 상태로 돌아갈 수 있다.


## 자주 쓰는 명령 모음

| 하고 싶은 일 | 명령 |
|---|---|
| 현재 브랜치 | `git branch --show-current` |
| 변경된 파일 | `git status` |
| 내가 바꾼 내용 보기 | `git diff` (커밋 전), `git diff origin/develop...HEAD` (develop 대비 전체) |
| 최근 커밋 | `git log --oneline -10` |
| 브랜치별 상태(원격과 차이 포함) | `git branch -vv` |
| 아직 푸시하지 않은 커밋 | `git log origin/브랜치..브랜치 --oneline` |
| develop에 아직 없는 내 커밋 | `git log origin/develop..HEAD --oneline` |
| 다른 브랜치로 이동 | `git switch 브랜치이름` |
| 원격 최신 정보 받기(내 파일은 안 바뀜) | `git fetch origin` |
| 두 브랜치의 충돌 미리 보기 | `git merge-tree --write-tree --name-only origin/main origin/develop` |
| PR 만들기(작업) | `gh pr create --base develop --title "제목" --body "설명"` |
| PR 상태·충돌 확인 | `gh pr view --json mergeable,mergeStateStatus` |
| PR 머지 + 브랜치 삭제 | `gh pr merge --merge --delete-branch` |
| 열린 PR 목록 | `gh pr list` |
| 머지 후 CI 진행 보기 | `gh run list --workflow build.yml --limit 3` |
