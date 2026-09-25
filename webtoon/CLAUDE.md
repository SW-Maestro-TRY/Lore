# webtoon/ — LORE 웹툰 도메인

이 파일은 `webtoon` 폴더에서 세션을 열 때마다 자동으로 읽힙니다. 규칙 본문은 주제별
문서(`docs/rules/`)로 나눠 두었습니다. **아래 「언제 무엇을 읽나」 표에 해당하는 순간이
오면 그 문서를 끝까지 읽고 나서 진행합니다.** 이 파일의 요약만 보고 판단하지 않습니다.

## 구조

```
webtoon/
  fe/    화면 (React). apps/web 의 /webtoon 라우트가 이걸 렌더링한다
  be/    API (Spring). 인증·크레딧·DB·공개여부·업로드 결과를 맡는다
  ai/    생성 파이프라인 (Python). 자바가 자식 프로세스로 부른다
  docs/  문서. 작업 규칙은 docs/rules/
```

```
webtoon/ai/
  new_harness/      지금 제품이 쓰는 이야기·그림 파이프라인
  story-harness/    new_harness 가 import 하는 파일만 남은 라이브러리 (완성본 취급)
  webtoon-harness/  new_harness·upload 가 빌려 쓰는 연출·단가표·이미지 제공자 (완성본 취급)
  upload/           다 그린 그림을 창고로 올리는 걸음
  assets/           기본 캐릭터 견본(samples) · 마스코트(lou) · 예시 작품(examples)
  work/             실행하며 쌓이는 것 (gitignore)
```

한 편이 만들어지는 길: `apps/web (/webtoon) → webtoon/fe → webtoon/be → python3 run.py (webtoon/ai)`

## 꼭 지킬 것

어기면 바로 사고가 나는 것만 적었습니다. 이유는 오른쪽 문서에 있습니다.

1. **push · 과금 실행 · `webtoon/`·`haeun/` 바깥 수정은 매번 먼저 묻습니다.** 로컬
   커밋은 묻지 않습니다. 한 번 승인이 다음 단계 승인이 아닙니다. → [dev.md](docs/rules/dev.md)
2. **화면 코드(`webtoon/fe`, `apps/web`)를 고쳤으면 커밋 전에
   `npm run build --workspace lore-web` 를 돌립니다.** PR 검사는 웹 빌드를 안 돌려서
   타입 오류가 머지된 뒤 dev 배포에서야 터집니다(2026-09-24, #397). → [dev.md](docs/rules/dev.md)
3. **커밋에 `Co-Authored-By` · `Claude-Session:` 을 붙이지 않습니다.** 형식은
   `[#이슈번호] 설명`. → [commit-pr.md](docs/rules/commit-pr.md)
4. **작업 하나에 이슈 하나 · `feature/` 브랜치 하나 · develop 으로 PR 하나.** 이슈·PR
   제목은 명사로 끝냅니다. → [issues.md](docs/rules/issues.md)
5. **파일을 고치는 작업은 워크트리를 파서 합니다.** 원본 폴더에서 바로 고치지 않습니다.
   → [worktree.md](docs/rules/worktree.md)
6. **`webtoon/ai` 를 고쳐도 서버를 다시 빌드·기동하기 전까지는 반영되지 않습니다.**
   → [dev.md](docs/rules/dev.md)
7. **배포가 실패하면 Actions 를 다시 돌리기 전에 로컬에서 같은 커밋을 빌드해 봅니다.**
   → [deploy-failure.md](docs/rules/deploy-failure.md)

## 언제 무엇을 읽나 — 작업 규칙 (`docs/rules/`)

| 이럴 때 | 읽을 문서 | 주요 내용 |
| --- | --- | --- |
| 이슈를 만들거나 정리할 때, push 뒤 이슈에 댓글 달 때 | [issues.md](docs/rules/issues.md) | 작업당 이슈 하나 · 제목은 명사형 · push 뒤 이슈별 커밋 목록 댓글(형식·기준 브랜치) · `gh` 사용 · 프로젝트 보드 `lore`(3) 칸과 정리 절차 |
| 커밋·push·PR 직전 | [commit-pr.md](docs/rules/commit-pr.md) | `feature/` 브랜치 → develop · 머지 뒤에도 브랜치 안 지움 · `[#번호] 설명` · 기능 수준으로 쓰기 · 공동 작성자 금지 · push 는 확인 후 · PR 은 Assignee 지정, 리뷰어 지정 금지 |
| 코드를 고치기 전 | [dev.md](docs/rules/dev.md) | 권한 범위 · 과금 승인 · 화면 수정 시 웹 빌드 · `webtoon/ai` 반영 경로 · 설정은 코드 기본값 · 파이썬 서버 금지 · 하네스 수정 규칙과 `test_imports.py` · 마이그레이션은 `scripts/new-migration.sh` · 그림 주소는 상대경로 |
| 파일을 고치는 작업을 시작할 때 | [worktree.md](docs/rules/worktree.md) | 언제 파는가 · 파는 명령 · `agent/` 브랜치 합치기 · 치우기 전 gitignore 결과물 옮기기 · `runs/` 심링크 금지 · `.env` 다루기 |
| 노트북에서 띄우거나 파이썬을 직접 돌릴 때, 로컬 그림이 안 뜰 때 | [local-run.md](docs/rules/local-run.md) | `./gradlew bootRun` · API 키는 루트 `.env` 의 `WEBTOON_API_KEY` 하나 · 환경별 그림 창고 · 로컬 그림 404 증상과 `.env` 네 줄 · rclone 으로 창고 띄우기 |
| 배포(GitHub Actions)가 실패했을 때 | [deploy-failure.md](docs/rules/deploy-failure.md) | 실패 SHA 확인 → 로컬 빌드 → 서버 build → 최신 run 하나만 재실행 · 원인을 알기 전에 하지 말 것 |
| webtoon 화면을 설계·수정할 때 | [ui-skills.md](docs/rules/ui-skills.md) | Impeccable 우선 · 리서치 자료 없음 · taste 렌즈 고정값 · 검증 예산 · 기록 위치 · 코드 먼저 · 실제 앱에서 확인 |

## 참고 문서 (`docs/`)

| 문서 | 무엇에 관한 문서인가 | 주요 내용 |
| --- | --- | --- |
| [server.md](docs/server.md) | 서버 구조와 사용 설명서 | dev·staging·prod 세 환경의 구성과 주소 · 배포 흐름과 브랜치 규칙 · 비밀값 넣는 곳(dev 는 박스 `.env` 와 compose `environment:` 둘 다 필요) · 버킷 · 서버 로그 보기 · staging 자동 전원 · DB 직접 접근 · 비용 |
| [env-diff.md](docs/env-diff.md) | 세 환경이 실제로 무엇이 다른가 | 대조표 · **dev 에서는 안 드러나고 승격 때 걸리는 것**(설정 주입 경로, venv 실패가 배포를 안 멈춤, 마이그레이션 순서, MinIO 의존 등) · 승격 점검표 |
| [images.md](docs/images.md) | 화면에 그림 넣는 법 | 정적 그림은 원본 폴더에 넣음(`apps/web/public/static/` 금지) · 예시 작품·예시 캐릭터는 부팅 때 한 번 심고 그 뒤로는 DB 가 원본 · 온보딩 목업 상수 · 둘러보기 공개 · 예시 `run_id` 하드코딩 자리 |
| [full-review-design.md](docs/full-review-design.md) | 완성된 화 전체를 다시 읽는 검수 설계 | 장 단위 검수로 못 잡는 장거리 문제 · 판정(`fullreview.py`)과 재생성 루프(`JobRunner.runFullReviewLoop`) 구현 상태 |
| [mentoring-followup-2026-09-19.md](docs/mentoring-followup-2026-09-19.md) | 0911 멘토링 후속 과제 진행 기록 | 과제 11개별로 한 것 → 실측 결과 → 남은 것 |
| [legal/](docs/legal/) | 이용약관·개인정보처리방침 작업본 | 법률 검토 전 초안 · 판 번호는 `user_agreement.version` 과 같아야 함 · 게시본은 `게시본-뽑기.py` 로 뽑음(작업본을 화면에 직접 쓰지 않음) |
| [backend.md](docs/backend.md) | **낡은 문서** | serve.py 프록시 시절 설명입니다. 그 구조는 2026-09-12에 지웠으니 지금 백엔드 설명으로 읽지 않습니다 |

`webtoon/ai/new_harness/STYLE_FINDINGS.md` — 그림체 실험 결과. 그림체를 다시 손대기
전에 읽습니다(이미 실패한 방법이 정리돼 있음).
