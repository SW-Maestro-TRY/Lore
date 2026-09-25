# UI/UX 스킬 정책 (2026-09-17, quickstart v5 기반)

webtoon 화면을 설계·수정할 때 읽습니다. `apps/web` 에 UI/UX 자동화 스킬 세트가 설치돼
있습니다(`.claude/skills/`, gitignore 처리돼 팀 레포에는 안 올라감). 이 정책은
**webtoon 화면에만** 해당합니다. zzal·trailer 는 각 담당자가 정합니다.

## 1. 누구 판단이 이기나

- **Impeccable 이 우선입니다.** `apps/web/PRODUCT.md`(webtoon 범위로 작성)가 제품
  맥락이고, DESIGN.md 는 아직 없습니다(`/impeccable document` 로 나중에 만들 수 있음).
- `frontend-design` · `interface-design` 도 설치돼 있습니다. quickstart 문서는 이 둘을
  Impeccable 과 권한이 겹쳐 설치하지 말라고 권하지만, 멘토링 메모를 보고 설치했습니다.
  **셋이 다른 방향을 내면 Impeccable 판단이 이깁니다.** 화면 작업을 시킬 때 어느 스킬을
  쓸지 적어 주면 충돌이 줄어듭니다.
- DESIGN.md(생기면)와 실제 코드 토큰이 다르면 어느 쪽도 자동으로 덮어쓰지 말고
  Design Drift 로 보고합니다.

## 2. 리서치

`ux-researcher-designer` 는 실제 사용자 자료(인터뷰·analytics·세션 녹화·설문)가 있을
때만 씁니다. **지금은 그런 자료가 없습니다.** 가상 리서치를 만들지 말고, 필요하면
가설(Hypothesis)로 적습니다.

## 3. Taste 렌즈

- `design-taste-frontend` · `redesign-existing-projects` 는 검증 렌즈일 뿐 코드를 직접
  고치지 않습니다. 실행 기준은 `docs/ux/taste-lens.md` 입니다(webtoon 전용,
  DESIGN_VARIANCE 4 / MOTION_INTENSITY 2 / VISUAL_DENSITY 5 고정 — 대화 중에 추론하거나
  올리지 않습니다).
- 작은 작업과 조작 화면(위저드·편집실·마이페이지)의 taste 판단에는 적용하지 않습니다
  (조작 화면은 렌즈 §3-B~E 만).
- `/impeccable critique` 앞이나 `/impeccable polish` 뒤에 taste 를 두지 않습니다.

## 4. 독립 검증과 예산

- 중형·대형 UI 변경은 `ux-heuristics` 로 따로 평가합니다. `web-design-guidelines` 는
  릴리스 준비 때만 씁니다.
- 스킬 호출 예산: 소형 0 · 중형 1(ux-heuristics) · 대형 2(+shape). 자동 수정 반복은
  검증 실패를 합쳐 최대 3회이고, 넘기면 멈추고 사람에게 보여 줍니다.
- 조사·검증은 가능하면 서브에이전트로 돌리고 요약만 받습니다. 스킬 `references/` 는
  필요한 절만 읽습니다.

## 5. 산출물 기록

- `docs/ux-log/` 에 기능당 보고서 1개(`YYYY-MM-DD-<feature>-r<n>.md`). taste 산출물은
  `docs/ux-log/…-taste.md` 또는 `docs/ux/hypothesis-taste-*.md`.
- `.claude/hooks/skill-log.sh`(스킬 호출 기록) · `.claude/hooks/ux-report-guard.sh`
  (보고서만 쓰고 스킬을 안 부른 경우 되돌림)가 걸려 있습니다.

## 6. 생성 이미지

- 새 화면은 **코드 먼저** 만듭니다. 이미지 시안을 먼저 생성하지 않습니다(2026-09-17
  확정).
- 제품에 들어가는 생성 이미지(마스코트·배경 등)는 PRODUCT.md 의 브랜드 톤(바다
  팔레트)을 따르고, UI 프레임이나 화면 글자를 이미지에 굽지 않습니다.
- 화면을 고쳤으면 실제 앱에서 직접 눌러 보고 확인합니다. 별도 테스트 페이지나 로직
  검증으로 대신하지 않습니다.
