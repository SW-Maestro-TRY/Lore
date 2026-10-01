# model-compare — 같은 프롬프트, 다른 모델

LORE 하네스와 프롬프트(PR #566 판)는 그대로 두고, 글 모델과 그림 모델만 바꿔 로맨스·성인
수위를 시험한 기록이다. 제품에 반영한 것은 없다. 결과는 `비교.html` 한 장에 있다
(`out/` 의 원본은 저장소 `.gitignore` 에 걸려 올라가지 않는다).

## 무엇을 봤나 (2026-10-01, 모두 1~4회씩)

- **글 모델** — gpt-5.1 · Nemotron 3 Ultra 550B · Qwen 3.8 27B · Venice · Cydonia ·
  Aion 2.0 · MiniMax M2-her · Hermes 4 405B (OpenRouter).
  - Nemotron 은 로맨스 맛이 가장 좋지만 주인공 성격을 바꾸고 형식 규칙을 넘긴다. 유료판은 14.7초.
  - Venice·Cydonia(24B)·MiniMax 는 한국어가 무너진다.
  - 성인 요청을 실제로 노골적으로 쓰는 건 Aion 2.0 뿐이고, 그러면 이야기가 사라진다.
    Hermes 4 는 동의 없는 장면을 썼다.
- **장면만 성인 모델로** — 이야기 본문에 그 순간이 없으면 성인 장면이 나오지 않는다.
- **그림** — 소이 시트를 참조로 붙여 「상의 탈의」·「이불로 가린 베드신」을 요청했다.
  제품 모델 OpenAI gpt-image-2 는 베드신을 거절(`moderation_blocked`), Grok Imagine ·
  Seedream 4.5 · Qwen Image 3 은 모두 그렸다.

## 돌리는 법

- `run_model.py <별칭> ...` — 하네스 `run.py` 를 그대로 부르되 STORY·SCENE 모델만 OpenRouter 로.
  `NH_PROMPT_OVERRIDE=prompt-adult` 면 그 폴더의 프롬프트를 먼저 읽는다.
- `gen_images.py` · `gen_openai.py` — 시트를 붙인 그림 시험.
- `make_page.py` — `비교.html` 을 다시 만든다(호출 0회).

키는 저장소 루트 `.env` 의 `OPENROUTER_API_KEY` 를 읽는다.

`비교.html` 에는 성인 수위의 글과 그림이 들어 있다.

## 백로그 — OpenAI 그림 거절 시 Grok 으로 넘기기 (2026-10-01, 미착수)

OpenAI gpt-image-2 가 안전 검사로 거절한 장을 Grok Imagine 으로 다시 그리는 장치. 넣을 자리는
`webtoon/ai/new_harness/detailart.py` 의 안전 검사 재시도(#531, 696~704줄)와 `run.py` 의 시트 단계.
걸리는 것:

- Grok 은 프롬프트 8,000자 제한 — 지금 페이지 프롬프트 약 2만 7천 자, 시트 약 2만 1천 자라 짧은 판이 따로 필요.
- 하네스의 그림 제공자는 gemini·openai 뿐(`story.py:666`) — OpenRouter 그림 API 를 새로 붙여야 함.
- 거절된 장만 그림체가 바뀐다. 한글 말풍선을 제대로 쓰는지 모른다.
- Grok 도 「가린 노출」까지만 그린다 — 넘겨서 살아나는 건 「OpenAI 거절 · Grok 허용」 구간뿐.
- 순서 결정 남음: 거절 즉시 Grok / OpenAI 에서 수위 낮춰 한 번 더 그린 뒤 Grok.
