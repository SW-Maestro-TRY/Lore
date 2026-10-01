#!/usr/bin/env python3
"""기준선(base)과 이 실험 판(new)을 같은 run.py 로 돌린다.

다른 것은 프롬프트 두 개(story_prompt · scene_prompt)뿐이다. new 일 때만
이 폴더의 prompt/ 에 있는 파일을 먼저 읽고, 없으면 기준선 것을 읽는다.
run.py 의 나머지(인물 단계·파싱·기록)는 한 글자도 안 바꾼다.

    python run_arm.py base --name 소이 --desc "..." --genre "현대 로맨스" --no-story-review
    python run_arm.py new  --run-id <id> --cast-pick 2 --no-story-review
    python run_arm.py new  --run-id <id> --pick 1 --scenes

run 은 out/<arm>/ 에 쌓인다. 인물 단계를 두 쪽이 똑같이 쓰려면 한쪽에서
만든 run 폴더를 다른 쪽 out/ 으로 복사한 뒤 --cast-pick 부터 돌린다.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
HARNESS = ROOT / "webtoon" / "ai" / "new_harness"
OVERRIDES = {"new": HERE / "prompt", "A": HERE / "prompt-A"}  # A = 2dea7e53 직전 develop

arm = sys.argv[1] if len(sys.argv) > 1 else ""
if arm not in ("base", "new", "A"):
    raise SystemExit("첫 인자는 base · new · A")

os.environ["NH_RUNS_DIR"] = str(HERE / "out" / arm)
sys.path.insert(0, str(HARNESS))

import llm  # noqa: E402

llm.load_dotenv(ROOT / ".env")   # 키는 저장소 루트 .env 에 있다 (setdefault 라 덮지 않는다)
if os.environ.get("WEBTOON_API_KEY") and not os.environ.get("OPENAI_API_KEY"):
    os.environ["OPENAI_API_KEY"] = os.environ["WEBTOON_API_KEY"]

import run  # noqa: E402

# 이야기 단계는 장르 견본 카드를 무작위로 뽑는다(samples.exemplars_fresh). 두 쪽이
# 다른 카드를 받으면 프롬프트 차이인지 카드 차이인지 못 가린다 — RB_CARDS 에
# 먼저 돌린 쪽 story_cards.json 의 id 를 주면 그 카드를 그 순서대로 쓴다.
_fixed = [i for i in (os.environ.get("RB_CARDS") or "").split(",") if i]
if _fixed:
    samples = run.samples

    def exemplars_fresh(genre, avoid_ids=None, **_):
        cards = {c.get("id"): c for c in samples.load(genre)}
        chosen = [cards[i] for i in _fixed]
        text = "\n\n".join(samples._fmt_card(c, n) for n, c in enumerate(chosen, 1))
        return text, _fixed

    samples.exemplars_fresh = exemplars_fresh

if arm in OVERRIDES:
    OVERRIDE = OVERRIDES[arm]
    _base_load = run.load_prompt

    def load_prompt(name: str) -> str:
        path = OVERRIDE / name
        if path.exists():
            return path.read_text(encoding="utf-8").strip()
        return _base_load(name)

    run.load_prompt = load_prompt

    # 그림 단계(detailart)는 load_prompt 를 안 거치고 자기 PROMPT_DIR 에서 바로
    # 읽는다. 기준선 prompt/ 를 통째로 링크한 겹친 폴더를 만들고 이 판의 파일만
    # 덮어, detailart 가 그 폴더를 보게 한다.
    import detailart  # noqa: E402
    overlay = HERE / "out" / f".overlay-{arm}"
    if overlay.exists():
        import shutil
        shutil.rmtree(overlay)
    overlay.mkdir(parents=True)
    for src in run.PROMPT_DIR.iterdir():
        (overlay / src.name).symlink_to(src)
    for src in OVERRIDE.iterdir():
        (overlay / src.name).unlink(missing_ok=True)
        (overlay / src.name).symlink_to(src)
    detailart.PROMPT_DIR = overlay

raise SystemExit(run.main(sys.argv[2:]))
