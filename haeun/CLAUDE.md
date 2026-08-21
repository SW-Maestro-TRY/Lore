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

## 환경 관련 주의사항

`landing/pipeline.py` 와 두 테스트 스크립트가 `C:\lore\story-harness` /
`C:\lore\webtoon-harness` 경로를 하드코딩하고 있습니다 (예전 위치로 추정,
지금은 OneDrive 경로로 옮겨왔는데 코드는 안 바뀜). 윈도우에서는 아래처럼
정션(junction)을 만들어서 우회했습니다 — 관리자 권한 불필요, 실제 파일은
안 건드리는 링크라 삭제해도 안전합니다:

```
mklink /J C:\lore\story-harness   "<이 폴더 경로>\story-harness"
mklink /J C:\lore\webtoon-harness "<이 폴더 경로>\webtoon-harness"
```

**맥북에서는 이 정션이 안 먹습니다** (Windows 전용 기능). 맥에서 `landing/serve.py`를
켜거나 테스트 스크립트를 돌리려면 같은 문제가 날 수 있고, 그때는 mac용 심링크
(`ln -s`)로 같은 걸 만들거나, `pipeline.py`의 `STORY`/`WEBTOON` 상수와 두 테스트
스크립트의 `sys.path.insert` 줄을 실제 경로로 고치는 근본 수정이 필요합니다.
아직 안 고쳤습니다 — 다음에 맥에서 막히면 이 문서부터 보세요.

## 최근 작업 로그
- 2026-08-21 — `story-harness/docs/user_feedback_summary.md` P0 6건 전부 구현 +
  다른 장르(헌터·게이트/아이돌/마법학교/오컬트 미스터리/좀비 아포칼립스) 트로프
  라우팅 감사 + 무협 템플릿 신설 + 판타지/일상 landing UI 추가.
  자세한 내용: [`docs/p0-fixes-2026-08-21.md`](docs/p0-fixes-2026-08-21.md)
