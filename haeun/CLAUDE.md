# haeun/ — LORE 웹툰 스튜디오

이 파일은 haeun 폴더에서 세션을 열 때마다 자동으로 읽힙니다 (Claude Code 컨벤션).
OneDrive로 동기화되는 실제 파일이라 기기(윈도우 노트북/맥북)와 무관하게 항상 적용됩니다.
상세 내용은 `docs/`를 참고하세요.

## 구조
- `story-harness/` — 캐릭터·이야기·콘티(대본) 파이프라인
- `webtoon-harness/` — 이미지 생성 파이프라인
- `char-harness/` — 캐릭터 레퍼런스 관련
- `landing/` — 위 하네스들을 감싸는 제품(웹 서버 + 프론트)

## 지켜야 할 규칙

**story-harness / webtoon-harness 는 "완성본"으로 취급합니다 (harness-is-final).**
이 두 폴더 안의 파일을 고칠 때는:
1. 고치기 직전 `<파일>.bak` 백업을 남긴다 (기존 `run.py.bak` 등과 같은 방식).
2. 기존 동작이 그대로 재현되게, **순수 추가**로만 고친다 (기본값 변경 금지 —
   예: 새 게이트 함수는 새 입력 필드가 없으면 항상 통과시켜서, 예전 run을 다시
   돌려도 결과가 안 바뀌게 한다).
3. 고친 뒤 반드시 테스트를 돌려 `ALL PASS` 확인한다:
   - `cd story-harness && python test_gates.py`
   - `cd webtoon-harness && python test_charsheet.py`
   (둘 다 pytest 아님 — 그냥 스크립트, 마지막 줄에 ALL PASS 또는 FAILED: ... 가 찍힘)

`landing/` 은 제품 레이어라 이 제약이 없습니다 — 자유롭게 고쳐도 됩니다.

## 병렬 에이전트 작업 (worktree)

**Claude Code 세션을 2개 이상 동시에 띄워 각자 다른 미션을 맡길 때는, 같은
워킹트리(`haeun/` 폴더 그 자체)를 공유하지 말고 세션마다 `git worktree`로
독립된 작업공간을 준다.**

2026-08-23에 이걸 안 지켜서 실제로 사고가 날 뻔했다 — 세션 A(이 정비 작업)와
세션 B(그림체 추가)가 같은 폴더에서 동시에 돌면서, `config.yaml`·
`pipeline.py`·`index.html`에 서로의 커밋 안 된 변경이 섞일 뻔했다. 다행히
실시간으로 확인하면서 넘어갔지만, 매번 사람/에이전트가 촘촘히 확인해야만
피할 수 있는 구조였다.

### 왜 "파일별"이 아니라 "미션별"로 나누는가

이 프로젝트에서 에이전트를 나누는 기준은 보통 **담당 폴더가 아니라 감사
관점**이다 — "너는 사용자 피드백이 실제로 반영됐는지 확인하고 안 됐으면
고쳐", "너는 그림체 구현을 확인하고 필요하면 고쳐", "너는 비용 트래킹이
빠짐없이 되는지 확인하고 고쳐" 같은 식. 각 에이전트가 프로젝트 전체를 보고
필요하면 어디든 고치므로, "story 담당/UI 담당"처럼 파일로 경계를 나눌 수가
없다. 그래서 worktree를 "누가 어느 폴더를 만지는지" 나누는 용도가 아니라
**"누구의 미커밋 변경이 누구와도 안 섞이게" 나누는 용도로만** 쓴다 — 각
worktree 안에는 프로젝트 전체가 그대로 있고, 에이전트는 그 안에서 자유롭게
조사·수정·테스트·커밋한다.

### 쓰는 법

```
# 세션마다, haeun 브랜치 최신에서 새 브랜치+worktree를 판다
git worktree add ../agent-<미션명> -b agent/<미션명> haeun

# 예: 사용자 피드백 반영 확인 담당 세션
git worktree add ../agent-feedback -b agent/feedback haeun
```

- 브랜치명은 `agent/<미션명>` (예: `agent/feedback`, `agent/image-review`,
  `agent/cost-tracking`) — 담당 파일이 아니라 **미션**을 이름으로 쓴다.
- 세션 하나 = worktree 하나 = 브랜치 하나. 같은 브랜치를 두 worktree에서
  동시에 체크아웃할 수 없으니 자연히 강제된다.
- 작업이 끝나면(테스트 통과 확인 후) `haeun`으로 병합한다:
  ```
  git checkout haeun
  git merge agent/<미션명>          # 충돌 나면 그때 해결
  git worktree remove ../agent-<미션명>
  git branch -d agent/<미션명>
  ```
  여러 미션을 합칠 때는 하나씩 순서대로 병합한다 — 한 번에 다 합치려 하지
  않는다. 진짜 충돌(같은 부분을 다르게 고친 경우)은 병합하는 사람이 그때
  판단해서 푼다. 이건 worktree로도 못 없앤다 — worktree가 없애는 건 "충돌이
  나기도 전에 미커밋 상태로 섞여서 뭐가 누구 것인지도 모르게 되는" 사고다.

### git이 안 보는 것 (`runs/`, `outputs/`, `jobs/`, `.env`)

`story-harness/runs/`, `webtoon-harness/outputs/`, `landing/jobs/`는 전부
gitignore돼 있어서 `git worktree add`가 자동으로 복사해 주지 않는다 —
새 worktree는 이 폴더들이 **비어서 시작한다.** 이건 기본적으로 안전한
방향이다(격리가 저절로 됨, 두 에이전트가 같은 run_id 폴더에 동시에 못 씀).

- **`.env`(API 키)는 매 worktree마다 심링크로 연결한다** — 읽기 전용 값이라
  공유해도 쓰기 충돌이 안 나고, 안 하면 새 worktree에서 story.py/run.py가
  키 없음으로 바로 죽는다:
  ```
  ln -s "$(pwd)/haeun/story-harness/.env" ../agent-<미션명>/story-harness/.env
  ln -s "$(pwd)/haeun/webtoon-harness/.env" ../agent-<미션명>/webtoon-harness/.env
  ```
- **`runs/`·`outputs/`·`jobs/`는 심링크로 공유하지 않는다** — 공유하는 순간
  오늘 겪은 것과 같은 동시쓰기 충돌이 git 밖에서 재현된다. 각 worktree가
  자기 것을 새로 만들게 둔다. 기존 run을 참고해서 테스트해야 하면 필요한
  run_id 폴더 하나만 **복사**해서 쓴다(심링크 아님).
- 랜딩 서버(`serve.py`)를 여러 worktree에서 동시에 띄우면 포트가 겹친다 —
  `python serve.py --port <다른 포트>`로 worktree마다 다른 포트를 쓴다.

## 작업 권한 범위

- **`haeun/` 폴더 내부**(이 폴더와 그 하위 전부)는 파일 편집·Bash 명령을 **허락 없이
  자유롭게** 실행합니다. `haeun/.claude/settings.local.json`에 흔한 git/python/npm
  명령을 자동 허용으로 등록해 뒀습니다 (force push · reset --hard · rm -rf 같은
  파괴적 명령은 제외 — 이런 건 항상 확인받습니다).
- **`haeun/` 바깥**(Lore 저장소의 `comic/`, `common/`, `infra/`, `story/`,
  `trailer/`, `apps/`, `build/`, 루트 파일 등)을 편집하거나 그 경로를 대상으로
  하는 작업은 **항상 먼저 물어봅니다.** 자동 허용 목록에 일부러 안 넣었습니다.
  (참고: Bash 권한 규칙은 명령어 문자열 접두사로만 매칭돼서 "haeun 안에서만"을
  기계적으로 강제할 수는 없습니다 — 이 경계는 결국 매 세션 이 문서를 읽고
  지키는 것과, `haeun/.claude/settings.local.json`의 `autoMode` 힌트에
  기댑니다.)
- `git push`처럼 원격에 반영되는 명령은 haeun 안이라도 매번 확인받습니다 —
  Claude Code 자체의 기본 정책(공유 상태에 영향을 주는 행동은 항상 확인)이라
  이 프로젝트 설정과 무관하게 그렇습니다. 커밋(로컬)까지는 자동, push는 항상 확인.
- git은 Claude가 별도 API로 올리는 게 아니라, 항상 **터미널의 일반 `git` 명령**을
  이 컴퓨터에 이미 설정된 haeun 계정 정보로 그대로 씁니다 (다른 경로 없음).
- **실제 과금이 걸리는 실행은 haeun 안이라도 항상 먼저 승인받습니다.**
  `story.py`/`webtoon.py`/`run.py`를 `--mock`·`--dry-run` 없이 진짜로 돌려서
  텍스트·이미지 모델을 호출하는 것 전부 해당(캐릭터 생성 1건, 컷 이미지 여러
  장 등). 게이트·경로 점검처럼 `--mock`/`--dry-run`으로 되는 건 과금이 없으니
  자유롭게 하되, 실제 생성으로 넘어가기 직전엔 반드시 확인받습니다. 위
  "haeun 안에서는 자유롭게" 규칙의 예외입니다.

## 이슈 번호 매핑

**Lore 공유 저장소** (`comic/`, `common/`, `infra/`, `story/`, `trailer/`,
`apps/`) 작업 시: `common/docs/git-convention.md`의 컨벤션을 그대로 따릅니다 —
항상 관련 GitHub 이슈 번호를 정하고, 커밋 메시지를 `[#이슈번호] Type: 내용`
형식으로 남깁니다 (예: `[#14] Feat: 로그인 기능 추가`). 브랜치명은
`[이슈타입]/#이슈번호` (예: `feat/#14`). 이슈가 없는 잡무만 예외
(`Chore: 내용`).

**haeun/ 작업 시** (2026-08-21부터 적용) — 위 컨벤션을 그대로 안 쓰고 haeun
전용으로 단순화합니다:
- 브랜치는 `haeun` 하나로 통일 (이슈별로 안 나눔), `develop`에서 분기했습니다.
- 커밋 메시지는 `[#이슈번호] 설명` — 공유 저장소 컨벤션의 `Type:` 부분은
  생략합니다.
- 한 파일이 여러 이슈에 걸치면(예: `webtoon.py`처럼 한 파일에 여러 파이프라인
  단계가 섞여 있는 경우) 이슈 번호를 전부 태그합니다
  (`[#19][#21][#25][#26] ...`).
- 수정 전/후를 나눠서 추적하고 싶은 파일(`.bak` 백업이 있는 경우)은 `.bak`
  내용을 먼저 커밋해 원래 기능의 이슈에 붙이고, 지금 내용을 다음 커밋으로
  나눠 그 수정이 해결한 이슈에 붙입니다 (예: `story.py` 원본 → `#4`, 이름
  검증 게이트 추가분 → `#26`).
- push는 커밋마다 하지 않고 모아뒀다가 확인받은 뒤 한 번에 합니다.
- **push할 때마다, 별도 요청 없이 자동으로** 이번에 새로 push된 커밋들을
  이슈번호별로 분류해서 관련 이슈마다 댓글을 추가/갱신합니다 (사용자가 나중에
  이슈 페이지만 열어도 그 이슈에 연결된 커밋을 바로 볼 수 있게 하는 게 목적).
  이미 그 이슈에 단 댓글이 있으면 새로 하나 더 달지 말고 기존 댓글을 최신
  커밋까지 포함하도록 수정(PATCH)합니다.
- 이슈에 관련 커밋 목록을 댓글로 남길 땐 `` [`sha`](커밋URL) `커밋 메시지` `` 형식을
  씁니다 — sha는 마크다운 링크로 감싸 클릭하면 그 커밋으로 이동하게 하고, 커밋
  메시지는 백틱(inline code)으로 감쌉니다. 메시지 안에 `[#18][#19]...`처럼 이슈
  번호가 여러 개 있으면, 백틱 없이 그냥 쓸 경우 GitHub이 그 번호들을 전부
  "이슈 전체 제목" 링크로 자동 확장해버려서 댓글이 못 알아보게 지저분해집니다
  (실제로 겪음, 2026-08-21). 이 저장소엔 `gh` CLI가 없어서, 댓글은
  `git credential fill`로 이 컴퓨터에 저장된 토큰을 꺼내 GitHub REST API
  (`POST /repos/{owner}/{repo}/issues/{n}/comments`)로 직접 올립니다.

## 환경 관련 주의사항

~~`landing/pipeline.py` 와 두 테스트 스크립트가 `C:\lore\...` 경로를
하드코딩하고 있어서 맥에서 막히던 문제~~ — **2026-08-23에 근본 수정 완료**
(이슈 [#86](https://github.com/SW-Maestro-TRY/Lore/issues/86)). `pipeline.py`의
`STORY`/`WEBTOON`, `make_episode.py`의 `STORY`, `run.py`의 `story_runs_root`,
`test_gates.py`/`test_charsheet.py`의 `sys.path.insert` 전부 파일 위치 기준
상대경로(`HERE.parent`, `__file__.resolve().parent`)로 바꿨습니다. 윈도우
정션(`mklink /J`)은 이제 안 만들어도 되고, 이미 만들어 둔 것도 지워도 됩니다
(둘 다 잘 동작하지만 정션이 더 이상 필요조건이 아님).

## 최근 작업 로그
- 2026-08-21 — `story-harness/docs/user_feedback_summary.md` P0 6건 전부 구현 +
  다른 장르(헌터·게이트/아이돌/마법학교/오컬트 미스터리/좀비 아포칼립스) 트로프
  라우팅 감사 + 무협 템플릿 신설 + 판타지/일상 landing UI 추가.
  자세한 내용: [`docs/p0-fixes-2026-08-21.md`](docs/p0-fixes-2026-08-21.md)
- 2026-08-23 — 같은 문서의 **P1 7건 · P2 6건** 구현 (시드 재현성 / 나이대 반영 /
  SD·MD·LD 등신 비율 / 종이 위 글자 / 톤 전환 빌드업 / 대사 구어체 / 컷 앵글
  연속성 / 배경 변주 / 스티커 평면화 / 포즈 / 장르별 톤 상한 / 작화 사고 검수 /
  마스코트 로딩 UX) + **이미지 모델 거절(안전 필터) 기록·표시** 신설.
  새 config 값은 전부 기본이 꺼짐이라 예전 run 은 프롬프트가 안 바뀐다.
  자세한 내용: [`docs/p1-p2-fixes-2026-08-23.md`](docs/p1-p2-fixes-2026-08-23.md)
- 2026-08-23 — 오케스트레이터/스킬/컨텍스트/RAG 정비 (이슈
  [#86](https://github.com/SW-Maestro-TRY/Lore/issues/86)): `C:/lore` 하드코딩
  근본 수정(위 참고) · `make_episode.py`/`landing`이 `meta.json`의 `status`를
  직접 읽도록 보강해 게이트 소진(`사람확인필요`)을 놓치지 않게 함 · 콘티
  단계까지 승인 UI 확장 + 승인 화면에 실패 사유 직접 표시 · role card 문서화
  (`docs/role_cards.md`) · 다음 프롬프트 재주입 JSON 압축(pretty→compact, ~26%
  절감) · 연출 지식 선택적 주입(RAG) — `docs/*.md` 리서치를 105개 청크로 나눠
  `knowledge/directing/`에 저장, story-harness/webtoon-harness 둘 다 태그
  매칭으로 재사용 · `Ledger`에 확정 사실(`facts`) 필드 확장(하위호환).
  이 세션 중 다른 세션(그림체 3종 추가, 이슈 #59/#63/#68)과 같은 폴더에서
  동시에 작업하다 미커밋 변경이 섞일 뻔한 경험 → 위 "병렬 에이전트 작업" 절
  신설의 계기가 됨.
