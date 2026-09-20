# char-harness — 이미지 생성 모델 캐릭터 일관성 비교 하네스

같은 캐릭터를 여러 장면에서 생성할 때, **레퍼런스를 어떻게 주느냐에 따라 일관성이
얼마나 유지되는지** 비교하는 실험 도구입니다.

| 조건 | 첨부 |
|---|---|
| A | 텍스트만 (레퍼런스 없음) |
| B | 얼굴 클로즈업 1장 |
| C | 턴어라운드 시트 |
| D | 턴어라운드 시트 + **직전 장면의 생성 결과** (순차 실행 필수) |

기본 provider 는 **Google Gemini** (`gemini-3-pro-image-preview`, 통칭 nano banana),
키 없이 파이프라인만 확인하는 **mock** provider 도 같이 들어 있습니다.

---

## 설치

```bash
pip install -r requirements.txt
cp .env.example .env      # Windows: copy .env.example .env
# .env 에 GEMINI_API_KEY=... 입력
```

레퍼런스 이미지를 `refs/` 에 넣습니다 (`refs/README.md` 참고).

## 실행

```bash
python run.py --condition A          # 조건 하나
python run.py -c A -c B              # 여러 개 (-c A,B 도 됨)
python run.py --all                  # 전부
python run.py --all --dry-run        # API 호출 없이 최종 프롬프트만 출력
python run.py --all --skip-existing  # 이미 만든 건 건너뛰고 이어서 (실패분 재시도용)
python run.py --report-only          # 기존 이미지로 리포트만 재생성
python run.py --all --yes            # 확인 프롬프트 건너뛰기
python run.py --style-test           # 그림체 비교 (아래 '그림체 비교' 참고)
```

실행하면 먼저 이렇게 물어봅니다:

```
모델: gemini:gemini-3-pro-image-preview
총 40회 호출, 예상 비용 7,504원 (약 $5.36). 재시도가 발생하면 그만큼 늘어납니다.
진행할까요? [y/N]
```

## 그림체(스타일) 비교 — `--style-test`

위 실험과 **변수가 반대인 별도 모드**입니다.

| | 고정 | 변수 |
|---|---|---|
| 캐릭터 일관성 (`--all`) | 스타일 문구 | 레퍼런스 방식 A~D |
| 그림체 (`--style-test`) | 캐릭터 1명, 장면 1개, 레퍼런스 없음 | 스타일 문구 6종 |

"얼굴이 같은 사람인가"와 "AI 티가 나는가"는 다른 변수라, 한 번에 재면 원인을 못 가립니다.
스타일을 먼저 확정하고 그 상태에서 일관성을 재는 순서가 맞습니다.

```bash
python run.py --style-test --dry-run   # 12개 프롬프트 조립만 출력 (API 호출 없음)
python run.py --style-test             # 6종 x 2회 = 12장 생성
python run.py --style-test --skip-existing   # 실패분만 재시도
python run.py --style-report           # 기존 이미지로 style_compare.html 만 재생성
```

설정은 `config.yaml` 의 `style_test` 섹션에 있습니다.

- `scene` : 모든 스타일이 공유하는 고정 장면 한 개
- `styles` : `id` / `label` / `prompt` 목록 (기본 `s1_base` ~ `s6_anti`)
- `repeats` : 스타일당 장수 (기본 2)
- `prompt_template` : `{character} {scene} {style}` 조립 순서

`character_prompt` 는 위 실험과 그대로 공유합니다. **`style_suffix` 는 일부러 쓰지 않습니다** —
고정 스타일 문구가 같이 들어가면 측정하려는 변수와 섞여서 비교가 무의미해집니다.

> **부정 지시 주의.** Midjourney 의 `--no A, B` 문법은 Gemini 가 부정으로 해석하지 못하고
> `A, B` 를 **그리라는 지시로 읽습니다.** `Do not produce any of the following: ...` 처럼
> 자연어 부정문으로 쓰세요 (`s6_anti` 참고).

판정은 `style_compare.html` 을 열고 딱 하나만 물으면 됩니다 — **"사람이 그린 웹툰이라고 하면
믿겠는가?"** 기준선보다 나은 게 하나도 없다면 그게 곧 프롬프트의 한계이고,
그 다음은 모델/LoRA 영역이라는 뜻입니다.

### 괜찮은 장 기록하기 — `outputs/style_picks.json`

어느 장이 괜찮았는지는 `log.jsonl`(생성 기록)에도 `score_sheet.csv`(일관성 채점)에도
안 맞아서, 별도 파일에 남깁니다. **HTML에서 고르거나, CLI로 적거나 둘 다 됩니다.**

**HTML에서** — `style_compare.html` 의 각 이미지 아래 `r번호` 버튼을 누르면 선택 표시가
켜지고, 카드 아래 칸에 메모를 적을 수 있습니다. 상단 바에 `f1 r2 r4 r6 | f2 r2 r3 ...`
요약이 실시간으로 갱신됩니다.

- 선택은 즉시 **브라우저에 임시 저장**(localStorage)되므로 새로고침해도 안 날아갑니다
- **`style_picks.json 저장`** 을 눌러 받은 파일을 `outputs/` 에 덮어쓰면 기록으로 확정됩니다
- **`요약 복사`** 는 `f1 r2 r4 r6` 형태로 복사 — 그대로 `--style-pick` 에 붙여넣을 수 있습니다
- **`파일 값으로 되돌리기`** 는 브라우저 임시 저장을 버리고 파일 내용으로 복원합니다

**CLI에서** — 적은 즉시 `style_picks.json` 과 `style_compare.html` 이 함께 갱신됩니다.

```bash
python run.py --style-pick "f1 r2 r4 r6" --style-pick "f2 r2 r3" \
              --style-pick "f3 # 존나 별로임" --style-pick "f6 r1 r4 # 이 정도인 것 같음"
```

- style id 는 **앞부분만 써도** 됩니다 (`f1` → `f1_s5base`). 여러 개에 걸리면 중단합니다
- `#` 뒤는 메모입니다
- r 번호를 하나도 안 쓰면 그 스타일의 선택을 **비웁니다** (메모는 남길 수 있음)
- 언급하지 않은 스타일의 기존 기록은 **건드리지 않습니다**

## 출력물

```
outputs/
  A/s1_cafe_r1.png ...        # {condition}/{scene_id}_r{n}.png
  log.jsonl                   # 호출 1건당 1행 (재시도도 각각 1행)
  compare.html                # 행=장면, 열=조건 그리드. 이미지 클릭 → 원본 확대
  score_sheet.csv             # 손으로 Y/N 채우는 빈 채점 템플릿

  style/f1_s5base_r1.png ...  # --style-test 결과. {style_id}_r{n}.png
  style_compare.html          # 스타일 카드 그리드 + 선택 UI. 이미지 클릭 → 원본 확대
  style_picks.json            # 괜찮았던 장 + 스타일별 메모 (직접 편집해도 됨)
```

`--style-test` 도 같은 `log.jsonl` 에 기록됩니다 (`condition` 필드가 `style`,
`scene_id` 필드가 style id).

`log.jsonl` 한 행에 들어가는 것: `condition, scene_id, repeat, attempt, prompt`(전문),
`attachments`(첨부 이미지 경로), `provider, model, duration_sec, ok, error,
est_cost_usd, est_cost_krw, output_path, provider_meta`.

`score_sheet.csv` 는 이미 있으면 **덮어쓰지 않고** `score_sheet.new.csv` 로 나옵니다
(채점한 내용 날아가지 않게).

## 조건/장면 바꾸기 — 코드 안 고침

전부 `config.yaml` 에 있습니다.

- `character_prompt` : 캐릭터 설명문 (모든 호출에 동일하게 들어감)
- `style_suffix` : 스타일 문구 (모든 호출에 동일하게 붙음)
- `scenes` : 장면 목록 (`id`, `prompt`) — 개수는 자유
- `conditions` : 조건별 `refs` / `use_previous_scene` / `sequential` / `extra`(조건별 지시문)
- `repeats` : 조건×장면당 반복 횟수
- `prompt_template` : `{character} {scene} {style} {extra}` 조립 순서

## 안전장치

- API 키는 `.env` 에서만 읽고, `.gitignore` 에 `.env` 와 `outputs/` 등록됨
- `limits.max_total_calls` 초과 시 **호출 전에** 예상 비용을 찍고 중단
- 실행 시작 시 총 호출 수 / 예상 비용 확인 프롬프트
- 실패는 그 건만 `retry.max_retries` 회(기본 2회) 재시도, 지수 백오프.
  400/401/403/404 나 안전 필터 차단처럼 재시도가 무의미한 오류는 즉시 건너뜀
- 조건 D 는 `sequential: true` 이고 실행 순서 자체가 `반복 → 장면 순서` 라 항상 순차.
  직전 장면이 실패해 파일이 없으면 그 건은 레퍼런스만 첨부하고 진행(경고 출력)

## 모델 교체

`providers/base.py` 의 `ImageProvider` 만 구현하면 됩니다.

```python
# providers/myapi.py
class MyProvider(ImageProvider):
    name = "myapi"
    def generate(self, req: GenRequest) -> GenResult:
        ...  # req.prompt, req.images(list[Path]) → GenResult(image_bytes=...)
```

`providers/__init__.py` 의 `REGISTRY` 에 `"myapi": MyProvider` 한 줄 추가하고,
`config.yaml` 의 `provider.name` 을 `myapi` 로 바꾸면 끝입니다.
`run.py` 는 provider 내부를 전혀 모릅니다.

### 비용 단가

`provider.cost_per_image_usd` 는 **안내용 추정치**입니다. 요금표가 바뀌면 이 값만 고치세요.
기본값 `0.134` 는 gemini-3-pro-image (1K/2K) 기준이고,
`gemini-2.5-flash-image` 로 바꾼다면 `0.039` 정도로 낮추고 `image_size` 옵션은 지우세요.
