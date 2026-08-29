# new_harness

사진·설명·장르를 받아 1화 이야기 후보를 만들고, 사람이 하나 고르면 그것을
콘티로 나누고, 캐릭터 시트를 뽑는다.

```
입력 ──> 이야기 후보 4개 ──> (사람이 하나 고름) ──> 콘티 ──> 캐릭터 시트
       prompt/story_prompt              prompt/storyboard_prompt
                                                     prompt/sheet_prompt
```

이야기는 `story-harness` 를 거치지 않는다. `prompt/` 안의 프롬프트가 전부다.
`story-harness` 에서 빌려 쓰는 것은 두 가지뿐이다 — 모델 호출 계층(`llm.py`)과
시트 이미지 생성(`sheet.py`). 둘 다 **읽기만** 한다: `story-harness` 는 한 줄도
고치지 않았다.

## 입력

`landing` 이 쓰는 `character.json` 을 그대로 읽는다.

```
python3 run.py --character ../landing/jobs/<job_id>/character.json
```

명령줄로 바로 줄 수도 있다.

```
python3 run.py --name 이하은 --photo a.png --photo b.png \
               --desc "겁이 많지만 결국 뛰어드는 대학생" --genre 판타지
```

- **캐릭터 이름** — 필수
- **외관** — 사진. 여러 장 가능
- **설명** — 선택. 없으면 모델이 사진에서 읽는다
- **장르** — 선택. 없으면 후보 4개가 서로 다른 장르로 나온다

`character.json` 의 `story`(줄거리) 칸은 읽고 버린다 — `story_prompt` 가
"줄거리는 받지 않는다, 네가 새로운 이야기를 만들어야 한다" 고 못 박고 있어서,
넘기면 프롬프트와 입력이 서로 반대를 말하게 된다.

## 실행

```
python3 run.py --plan                              # 어느 단계가 어느 모델인지
python3 run.py --name ... --photo a.png            # 이야기 후보 4개
python3 run.py --run-id <id> --pick 2              # 고르고 콘티까지
python3 run.py --run-id <id> --sheet               # 캐릭터 시트
python3 run.py --name ... --photo a.png --all --pick 2   # 한 번에
```

`--pick` 을 안 주면 후보를 보여주고 번호를 물어본다.
아무 명령에나 `--dry-run` 을 붙이면 프롬프트만 쓰고 호출은 안 한다 (0원).

## 결과

`runs/<run_id>/` 에 쌓인다.

| 파일 | 내용 |
| --- | --- |
| `input.json` | 정리한 입력 |
| `story_prompt.txt` `story.md` | 이야기 단계에 보낸 것과 받은 것 |
| `directions.json` | 후보 4개를 잘라 읽은 것 |
| `pick.json` | 고른 방향 |
| `board_prompt.txt` `board.md` | 콘티 단계에 보낸 것과 받은 것 |
| `cuts.json` | 장면 → 컷으로 잘라 읽은 것 |
| `sheet_spec_prompt.txt` `sheet_spec.json` | 시트 사양 |
| `sheet_prompt.txt` `sheet.png` | 시트 이미지 프롬프트와 결과 |
| `meta.json` | 호출마다 어느 모델이 얼마나 썼는지 |

`.md` 는 모델이 낸 원문 그대로다. 프롬프트에 "JSON 으로 내라" 를 덧붙이지
않았다 — 두 프롬프트 모두 형식과 최종 확인 목록이 이미 마크다운으로 못 박혀
있어서, 뒤에서 형식을 뒤집으면 그 목록 전체가 프롬프트와 어긋난다. 그래서
원문을 그대로 남기고, 골라야 하는 만큼만 코드에서 잘라 읽는다(`.json`).
잘라 읽기가 실패해도 원문은 남는다.

## 모델 — 단계마다 다르게

`.env` 만 고치면 바뀐다. `.env.example` 을 `.env` 로 복사해서 쓴다.

```
<단계>_PROVIDER / <단계>_MODEL   >   NH_PROVIDER / NH_MODEL   >   PROVIDER
```

단계 이름은 `STORY`(이야기 후보) · `BOARD`(콘티) · `SHEET`(시트 사양) 이고,
시트 이미지는 `SHEET_IMAGE_PROVIDER` / `SHEET_IMAGE_MODEL` 로 따로 고른다.

```
NH_PROVIDER=gemini
STORY_PROVIDER=openai
STORY_MODEL=gpt-5.1
SHEET_IMAGE_PROVIDER=openai
```

프로바이더는 `gemini` · `openai` · `anthropic`. 지금 무엇으로 도는지는
`python3 run.py --plan` 이 보여준다.

**API 키는 여기 안 적어도 된다.** `new_harness/.env` 를 먼저 읽고 그다음
`story-harness/.env` 를 읽는데 둘 다 "이미 있는 값은 안 덮어쓴다" 라서,
모델 선택은 여기서 하고 키는 저쪽 것을 그대로 물려받는다.

값 뒤에 주석을 붙이지 마라 — 주석까지 값으로 읽힌다(`story-harness/.env` 와
같은 파서다).

## 캐릭터 시트

`prompt/sheet_prompt` 이 사진과 설명에서 사양을 적고, `sheet.py` 가 그것을
이미지 프롬프트로 바꿔 한 장을 그린다. 영역은 다섯이다.

1. 4면도 (정면 · 3/4 · 측면 · 후면)
2. 표정 6종
3. 고정 요소 확대 (3~5개)
4. **소지품** — 인물이 늘 지니고 다니는 물건을 물건만 따로 그린다
5. 색상 칩 6개

소지품 영역이 `story-harness` 의 시트와 다른 점이다. 그래서 공통 지시도 따로
둔다 — 저쪽 `SHEET_COMMON_EN` 은 `no props` 라고 못 박고 있어서 그대로 쓰면
소지품 영역이 지워진다. 소지품이 없는 인물이면 그 영역 없이 네 영역으로 그린다
(없는 물건을 지어내게 만들지 않는다).

그리기 전에 사양을 검사한다(`sheet.gate_spec`). `appearance_en` 에 한글이
섞였거나, 고정 요소가 모자라거나, 팔레트에 hex 가 없으면 **호출 전에 멈춘다** —
사양 없이 이미지를 부르면 빈칸을 모델이 평균값으로 채우고, 그렇게 나온 시트는
"컷마다 다른 사람" 을 막지 못한다.

`sheet_spec.json` 과 `sheet.png` 는 이미 있으면 다시 안 만든다. 다시 뽑으려면
지운다.

## 컷을 페이지로 묶기

그림은 컷 단위로 부르지 않는다. `pages.py` 가 콘티의 컷 배열을 이미지 생성
단위로 묶는다.

```python
import pages
pages.group_pages(cuts, max_per_page=5)      # -> [[컷, 컷], [컷], ...]
pages.flatten_cuts(scenes)                   # cuts.json (장면 -> 컷) 을 편다
```

- `large` · `full` 은 혼자 한 페이지를 쓴다
- `tiny` · `small` · `normal` 은 순서대로 모으고, 도중에 `large`/`full` 을
  만나면 거기서 끊는다
- 한 페이지의 최대 컷 수는 `max_per_page` (기본 5)
- **컷 순서는 안 바뀐다.** 페이지를 이어 붙이면 원래 컷 배열이 그대로 나온다

크기 칸은 `size` 와 `크기` 를 둘 다 읽는다(콘티 파서가 한글 키로 저장한다).
모르는 값은 `normal` 로 본다 — 모델이 낸 것을 읽는 자리라 오타 하나로 멈추지
않고, 혼자 한 장을 차지하는 쪽보다 되돌리기 쉬운 실수다.

## 검사

```
python3 test_parse.py
```

호출을 안 하니 돈이 안 든다. 마지막 줄에 `ALL PASS` 가 찍혀야 한다.

## 아직 안 한 것

- 콘티(`cuts.json`)를 `webtoon-harness` 로 넘겨 컷 이미지를 그리는 연결
- `landing` 화면에서 이 하네스를 고르는 길 (지금은 명령줄만)
- 후보를 고르는 화면 (지금은 터미널에서 번호 입력)
