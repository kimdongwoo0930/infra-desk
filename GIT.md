# Git 작업 가이드

이 저장소에서 브랜치를 만들고, 커밋하고, PR을 올리고, 머지하는 방법이다. 직접 작업할 때 보는 용도이고, 명령은 복사해서 그대로 쓸 수 있게 적었다.

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

## 5. 푸시와 PR

### 푸시

```bash
git push -u origin feat/내-작업     # 처음 올릴 때 (-u로 이후 git push만 써도 된다)
git push                           # 같은 브랜치에 커밋을 더 올릴 때
```

푸시하면 터미널에 PR을 만드는 링크가 나온다.

```
remote: Create a pull request for 'feat/내-작업' on GitHub by visiting:
remote:      https://github.com/kimdongwoo0930/infra-desk/pull/new/feat/내-작업
```

### PR 만들기

1. 위 링크를 연다 (또는 GitHub 저장소 → **Compare & pull request** 버튼).
2. **base를 `develop`으로 바꾼다.** 기본값이 `main`일 수 있다. (아래 [자주 하는 실수](#자주-하는-실수) 참고)
3. 제목과 설명을 쓴다.
4. **Create pull request**를 누른다.

PR 설명 예:

```
## 변경
- 무엇을 바꿨는지 (사용자 입장에서)

## 이유
- 왜 필요했는지 (필요할 때만)

## 테스트
- ./gradlew test 통과 (N개)
- 직접 확인한 것 / 아직 확인하지 못한 것

## 확인 필요
- 리뷰할 때 봐 줬으면 하는 부분
```

> 터미널에 `gh`(GitHub CLI)가 설치돼 있지 않아서 PR은 웹에서 만든다.

## 6. 머지

PR 화면 아래의 초록 버튼으로 합친다. **"Create a merge commit"** 을 쓴다. 지금까지 모든 PR이 이 방식이다(`Merge pull request #7 from …`가 생긴다). Squash나 Rebase는 쓰지 않는다. 브랜치의 커밋 기록이 그대로 남아야 나중에 이유를 추적하기 쉽다.

머지 전에 확인한다.
- 화면에 **"This branch has no conflicts with the base branch"** (초록색)가 떠 있다. "Can't automatically merge"면 [충돌 해결](#8-내-브랜치를-최신-develop으로-맞추기)을 먼저 한다.
- base가 의도한 브랜치(`develop`)이다.

머지가 끝나면 GitHub의 **Delete branch** 버튼으로 원격 브랜치를 지운다(내용은 이미 `develop`에 들어갔다).

### 머지 후 내 컴퓨터 정리

```bash
git switch develop
git pull origin develop                # 방금 머지된 내용 받기
git branch -d feat/내-작업             # 머지된 로컬 브랜치 삭제 (머지가 안 됐으면 거부해 준다)
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

**PR 만들기**
1. GitHub → **Pull requests → New pull request**
2. **base: `main`, compare: `develop`** 으로 설정한다.
3. 제목은 `릴리스: …`로 시작해서 주요 변경을 한 줄로 쓰고, 설명에 변경 요약과 "확인 필요"를 적는다.
4. 머지는 **"Create a merge commit"** 으로 한다. 머지 후 `develop` 브랜치는 **지우지 않는다**.

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

## 9. 실수했을 때

| 상황 | 방법 |
|---|---|
| 아직 **푸시 전**인데 마지막 커밋 메시지를 고치고 싶다 | `git commit --amend -m "새 메시지"` |
| 아직 푸시 전인데 마지막 커밋을 **취소**하고 파일은 남기고 싶다 | `git reset --soft HEAD~1` |
| 파일을 수정했는데 **마음에 안 들어서** 되돌리고 싶다 (커밋 전) | `git restore 파일` (⚠ 수정한 내용이 사라진다) |
| `git add`로 잘못 올린 파일을 빼고 싶다 | `git restore --staged 파일` |
| **엉뚱한 브랜치**(예: `develop`)에서 작업했다 (아직 커밋 전) | `git switch -c feat/내-작업` (변경이 그대로 따라온다) |
| 엉뚱한 브랜치에 **커밋까지** 했다 (푸시 전) | 새 브랜치를 만든 뒤 원래 브랜치를 되돌린다. 헷갈리면 **그대로 두고 물어본다** |
| 이미 **푸시한** 커밋에 문제가 있다 | 고친 내용을 **새 커밋**으로 만들어 푸시한다 (기록을 지우지 않는다) |
| **비밀값(키, IP 등)을 커밋**했다 | 푸시했다면 바로 그 키를 **폐기하고 새로 발급**한다. 기록에서 지우는 건 별도 작업이라 도움을 받는다 |

**하지 않는 것**
- `git push --force`(강제 푸시). 남의 커밋이나 이미 머지된 기록을 지울 수 있다.
- `develop`, `main`에 직접 푸시. 항상 PR을 거친다.
- 무엇이 지워지는지 모른 채 `git reset --hard`, `git clean -fd`를 쓰는 것. 작업 중인 변경이 영구히 사라진다.

## PR 전 체크리스트

- [ ] `./gradlew test` 통과
- [ ] 화면을 건드렸다면 `./gradlew runDemo`로 직접 확인, 필요하면 `./gradlew snapshot`
- [ ] 새 기능은 **데모 모드에서도 동작**한다(가짜 데이터 포함)
- [ ] `docs/DEVLOG.md`에 한 일·결정·남은 일을 적었다
- [ ] 구조가 바뀌었다면 `docs/architecture/`의 해당 모듈 문서를 고쳤다 (모듈 사이 연결이 바뀌면 `README.md` 개요도)
- [ ] 비밀값, 실제 IP·서버 이름·이메일이 없다 (`git diff origin/develop...HEAD`로 올라갈 내용을 한 번 훑는다)
- [ ] 커밋 메시지가 한국어로 무엇을 했는지 알아볼 수 있다
- [ ] PR의 **base가 `develop`** 이다

## 자주 하는 실수

| 실수 | 증상 | 해결 |
|---|---|---|
| PR의 base가 `main`으로 돼 있다 | 변경이 수십 개 파일로 보이거나 "Can't automatically merge" | PR 화면 위쪽 `base:`를 `develop`으로 바꾼다 |
| 최신 `develop`에서 브랜치를 만들지 않았다 | PR에 남의 커밋이나 오래된 변경이 섞여 보인다 | [8번](#8-내-브랜치를-최신-develop으로-맞추기)처럼 `develop`을 가져온다 |
| 푸시 때 "remote rejected"가 한 번 뜬다 | 푸시가 실패로 끝난다 | 같은 명령을 한 번 더 해 본다. 계속되면 오류 전체를 `git push 2>&1`로 보고 확인한다 |
| `git branch -d`가 거부한다 | `not fully merged` | 머지가 안 된 커밋이 있다는 뜻이다. `git log develop..브랜치`로 확인한다 |
| 커밋 후에 파일이 안 올라갔다 | PR에 변경이 안 보인다 | `git status`로 `add`를 빠뜨리지 않았는지, `git log origin/브랜치..브랜치`로 푸시 안 된 커밋이 있는지 본다 |

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
