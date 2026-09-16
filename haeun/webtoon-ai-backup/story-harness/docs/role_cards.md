# 파이프라인 역할 카드

각 단계가 실제로 무엇을 받고, 무엇을 내놓고, 뭘 기준으로 통과시키는지 — 코드를
안 읽고도 알 수 있게 정리한다. `.txt` 프롬프트 파일 자체에는 이 정보를 넣지
않는다 (그 파일은 `render()`가 그대로 모델에 보내므로, 메타데이터를 넣으면
프롬프트 노이즈가 된다). 여기가 그 대신이다.

줄번호는 2026-08-23 기준이며 코드가 바뀌면 어긋날 수 있다 — 함수명으로 다시
찾는 게 안전하다.

## story-harness/story.py — 이야기 설계

| 단계 | 함수 | 프롬프트 | 입력 | 출력 | 게이트 | 실패 시 |
|---|---|---|---|---|---|---|
| LOOK | `look_at_photos` (5548) | `look.txt` | 캐릭터 사진 | 외형 서술 → `look.json`, 이후 `row["character"]` 맨 앞에 병합 | 없음 | 사진 없으면 아예 안 부름(비용 절감) |
| SEED | `seed_missing` (5599) | `seed.txt` | 장르/세계관/한 줄 중 빈 칸 | 채운 값 → `seed.json` | 없음 | 장르가 끝까지 비어 있으면 `ParseFailure` |
| P1 (카드) | `call_p1` (2976) | `p1.txt` | 캐릭터 입력, 장르 템플릿, 예시 카드 | 캐릭터 카드 json | `gate_p1`(1693, 내부에서 `gate_gender`·`gate_supporting_cast`·`gate_card`·`gate_visual`·`gate_name` 호출) | 게이트 실패 → 피드백 붙여 재호출, `max_gate_retries` 소진돼도 P1 자체는 그 결과를 그대로 반환(에스컬레이션은 상위 P3 경로에서만) |
| P2 (프리미스) | `run_pipeline` 내부 while (3033, P2 부분 ~3130) | `p2.txt` | p1 json, 장르/스토리 템플릿 | 이야기 뼈대 json | `gate_p2`(2369) + `check_borrowed_titles` | 게이트 실패 → 피드백 재주입 재호출, `max_gate_retries` 소진 시 `STATUS_HUMAN` |
| P3 (심사) | 동일 while, P3 부분 (~3160) | `p3.txt` | p1+p2 json, 샘플 인트로 | 통과/탈락 판정 (`summarize_p3`) | 없음(LLM 판정 자체가 게이트) | 탈락 → `target_stage`에 따라 P1 또는 P2로 되돌려 재실행, `max_p3_retries` 소진 시 `STATUS_HUMAN`. 전부 통과했어도 verdict가 "보통"이면 `STATUS_HUMAN` |
| SCENE | 동일 함수, `result.status == STATUS_OK`일 때만 (~3200) | `scene.txt` | p1+p2 json, one_line | 장면 목록 | `check_scenes`(2702, 8항목) | `SCENE_BLOCKING_CHECKS`(설정 증발·출처 단일)에 걸리면 `max_scene_fixes` 소진 시 `STATUS_HUMAN`, 그 외 항목은 메모만 남기고 통과 |
| CONTROL(대조군) | `run_control` (3352) | `control.txt` | character, genre | 장면 목록 | 없음 | 없음 — 실험 대조군이라 게이트 자체가 없음 |
| 캐릭터시트 그림 | `run_charsheet` | — | p1 appearance | 시트 이미지 후보 → 사람이 pick | `gate_charsheet_source`(3950) | 후보 여러 장이면 사람에게 위임(`--pick` 대기) |

공통 유틸: `render()`(407, `{var}` 치환 — 매칭 안 되면 그대로 둔다), `load_prompts()`
(531, `prompts/*.txt` 로딩), `resolve_genre_templates()`(5322, 장르 substring 매칭),
`_load_json_file()`(5296, 상대경로+env override 로더 — `GENRE_TEMPLATE_FILE` 패턴).

상태값(`meta.json`의 `status`): `STATUS_OK="ok"`, `STATUS_HUMAN="사람확인필요"`,
`STATUS_PARSE_FAIL="실패(파싱)"`, `STATUS_API_FAIL="실패(API)"` (302-305).

## story-harness/webtoon.py — 콘티 · 컷 분해

한 작품(run_id)을 여러 화로 확장하는 단계. `build_engine_card()`(623, 주인공·
세계관·룰 카드 — 매 단계에 원문 그대로 삽입, 화 전체에 항상 관련 있어서 선택
없이 통째로 넣는 게 맞는 설계)와 `Ledger` 클래스(749, 화 사이 떡밥/상환 질문
추적, `<run>/webtoon/ledger.json`에 영속화)가 모든 단계에 공유된다.

| 단계 | 함수 | 프롬프트 | 입력 | 출력 | 게이트 | 실패 시 |
|---|---|---|---|---|---|---|
| W4 (Arc) | `run_webtoon` 내부 (~4083) | `w4.txt` | engine_card, ledger_snapshot | 아크(큰 줄거리) json → `arc{N}_episodes.json`(작품당 1회, 캐시) | 있음 | `max_retries` 소진 시 `Stopped(reason, STATUS_HUMAN)` |
| W5 (화 스케치) | `run_webtoon` 내부 (~4157) | `w5.txt` | engine_card, arc_json, ledger_snapshot | 화 단위 장면/에피소드 json | 있음 | 소진 시 `Stopped(STATUS_HUMAN)` |
| W6 (화 검수) | `run_webtoon` 내부 (~4195) | `w6.txt` | engine_card, arc_json, ledger_snapshot, episodes_json | 통과/탈락 판정 | 있음 | `result.status = STATUS_HUMAN` 직접 세팅 |
| W7 (컷 분해) | `solve_cuts` (3333) | `w7.txt`(1028줄 — 컷 구성 규칙 등 장르 불문 보편 지침) | engine_card, arc_json, episode_json, ledger_snapshot, zones_block | 컷 목록 json | 있음(`regen_stage7`) | 소진 시 `Stopped(STATUS_HUMAN)` |
| W8 (대사 다시쓰기) | `solve_text` (3598) | `w8.txt` | engine_card, episode_json, ledger_snapshot, cuts_json | 컷별 대사/화면 텍스트 | 있음 | **유일하게 다름** — 소진돼도 `Stopped` 안 올리고 W7 결과로 조용히 폴백(이미 통과한 컷 한 벌을 버리지 않으려는 의도적 설계, 3602 주석) |

전체 오케스트레이션: `run_webtoon()`(4018, W4→W5→W6→W7→W8을 `episode_target`
편만큼 순차 반복) / `run_cuts_only()`(4405, W4~W6 건너뛰고 W7·W8만 재실행,
`--cuts-only`). 둘 다 끝나면 `<run>/webtoon/meta.json`에 `status`/`note`를
남긴다 — 단 CLI `main()`은 그 값과 무관하게 항상 `return 0`이라, 상위
오케스트레이터(`make_episode.py`, `landing/pipeline.py`)는 exit code가 아니라
이 meta.json을 직접 읽어야 한다(`make_episode.py`의 `stage_status()`,
`landing/pipeline.py`의 `_meta_status()` 참고).

## webtoon-harness/run.py — 이미지 생성

| 단계 | 함수 | 프롬프트 | 입력 | 출력 |
|---|---|---|---|---|
| cut_split | (847 부근) | `cut_split.txt` | episode_title, episode_summary, cut_count | 컷 분할 json |
| prompt_gen | (969 부근) | `prompt_gen.txt` | episode_title, cut_count, cuts_json | 이미지 생성용 프롬프트 |

선택적 첨부(정확 매칭 RAG의 축소판): `supporting.block()`(webtoon-harness/
supporting.py:321, 컷 서술에 이름이 등장할 때만 그 조연 설명을 붙임),
`conditions` 프리셋(config.yaml:1165, S/S+/C 등 — 운영자가 고르는 고정 첨부
세트), `resolve_attachments()`(run.py:1521, `use_previous_cut` 조건일 때 직전
컷 이미지를 동적으로 붙임).
