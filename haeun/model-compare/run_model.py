#!/usr/bin/env python3
"""같은 하네스·같은 프롬프트로 모델만 바꿔 이야기·장면 단계를 돌린다.

OpenRouter 는 OpenAI 와 같은 chat completions 주소를 준다. 하네스의 openai
프로바이더에 주소(OPENAI_BASE_URL)와 키만 바꿔 끼우면 코드는 그대로 돈다.

    python run_model.py qwen  --run-id <id> --cast-pick 1 --no-story-review
    python run_model.py gemma --run-id <id> --pick 1 --scenes

run 은 out/<모델 별칭>/ 에 쌓인다. 인물 단계는 돌리지 않는다 — romance-beats 에서
만든 run(같은 소이·하도윤·재료)을 복사해 와서 --cast-pick 부터 돌린다.
견본 카드는 RB_CARDS 로 고정한다(romance-beats/run_arm.py 와 같은 방식).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
HARNESS = ROOT / "webtoon" / "ai" / "new_harness"

MODELS = {
    "qwen": "qwen/qwen3.8-27b:free",
    "gemma": "google/gemma-4-31b-it:free",
    "nemotron": "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nemotron-paid": "nvidia/nemotron-3-ultra-550b-a55b",
    "venice": "cognitivecomputations/dolphin-mistral-24b-venice-edition",
    "cydonia": "thedrummer/cydonia-24b-v4.1",
    "aion2": "aion-labs/aion-2.0",
    "minimax-her": "minimax/minimax-m2-her",
    "hermes4": "nousresearch/hermes-4-405b",
}

alias = sys.argv[1] if len(sys.argv) > 1 else ""
if alias not in MODELS:
    raise SystemExit(f"첫 인자는 {' · '.join(MODELS)}")

os.environ["NH_RUNS_DIR"] = str(HERE / "out" / alias)
sys.path.insert(0, str(HARNESS))

import llm  # noqa: E402

llm.load_dotenv(ROOT / ".env")
key = os.environ.get("OPENROUTER_API_KEY")
if not key:
    raise SystemExit("OPENROUTER_API_KEY 가 없습니다 (저장소 루트 .env)")

# 이 프로세스의 openai 호출은 전부 OpenRouter 로 간다. 인물 단계는 안 돌리므로
# 실제로 나가는 호출은 이야기(STORY)·장면(SCENE) 하나씩이다.
os.environ["OPENAI_BASE_URL"] = "https://openrouter.ai/api/v1"
os.environ["OPENAI_API_KEY"] = key
for stage in ("STORY", "SCENE"):
    os.environ[f"{stage}_PROVIDER"] = "openai"
    os.environ[f"{stage}_MODEL"] = MODELS[alias]

import run  # noqa: E402

# 프롬프트 판을 바꿔 끼운다: NH_PROMPT_OVERRIDE=<폴더> 면 그 폴더에 있는 파일을 먼저 읽는다.
_ov = os.environ.get("NH_PROMPT_OVERRIDE")
if _ov:
    _ovdir = (HERE / _ov).resolve()
    _base_load = run.load_prompt

    def _load(name):
        f = _ovdir / name
        return f.read_text(encoding="utf-8").strip() if f.exists() else _base_load(name)

    run.load_prompt = _load

# OpenRouter 는 공급자 쪽 오류를 HTTP 200 에 choices 없이 돌려주기도 한다(무료 풀에
# 동시에 여러 개를 보냈을 때). 하네스는 그걸 TypeError 로 죽으니, 여기서 오류를
# 찍고 잠깐 쉬었다가 다시 보낸다.
import time  # noqa: E402

from openai.resources.chat.completions import Completions  # noqa: E402

_raw_create = Completions.create


def _retrying(self, *a, **kw):
    for attempt in range(4):
        resp = _raw_create(self, *a, **kw)
        if getattr(resp, "choices", None):
            return resp
        err = getattr(resp, "error", None) or getattr(resp, "model_extra", {}).get("error")
        print(f"  !! OpenRouter 가 빈 응답을 돌려줬습니다 ({attempt + 1}/4): {str(err)[:200]}", flush=True)
        time.sleep(20 * (attempt + 1))
    return resp


Completions.create = _retrying

# 생각하는 모델(qwen)은 출력 한도를 생각에 다 쓰고 본문을 비워 돌려준다
# (첫 실행: 출력 16000 토큰, 본문 0자, stop=max_tokens). 하네스는 이 옵션을
# 안 보내므로 여기서 요청마다 끼워 넣는다.
if alias in ("qwen",):
    from openai.resources.chat.completions import Completions

    _create = Completions.create

    def create(self, *a, **kw):
        body = dict(kw.pop("extra_body", None) or {})
        body.setdefault("reasoning", {"enabled": False})
        return _create(self, *a, extra_body=body, **kw)

    Completions.create = create

_fixed = [i for i in (os.environ.get("RB_CARDS") or "").split(",") if i]
if _fixed:
    samples = run.samples

    def exemplars_fresh(genre, avoid_ids=None, **_):
        cards = {c.get("id"): c for c in samples.load(genre)}
        chosen = [cards[i] for i in _fixed]
        text = "\n\n".join(samples._fmt_card(c, n) for n, c in enumerate(chosen, 1))
        return text, _fixed

    samples.exemplars_fresh = exemplars_fresh

raise SystemExit(run.main(sys.argv[2:]))
