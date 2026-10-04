#!/usr/bin/env python3
"""표지 검수 — 그린 표지가 **표지 모양인가.**

## 왜 있는가

장마다 보는 검수(`pagecheck`)는 1페이지(표지)를 안 본다 — "앞 장에서 이어지는가"
를 묻는 검수라 표지에는 물을 것이 없다. 화 전체 검수(`fullreview`)는 표지를
읽기는 하지만 "표지인가" 를 묻지 않았다. 그래서 1장면을 네 컷 페이지로 그리고
제목도 없는 표지가 아무 데서도 안 걸리고 나갔다(2026-09-30, run
20260930T212420-43e5c0).

## 무엇을 보는가

셋 다 **셀 수 있는 것**만 본다.

- 칸이 하나인가 (만화 페이지처럼 나뉘지 않았는가)
- 말풍선·나레이션 상자·효과음이 없는가
- 제목이 적힌 글자 그대로 들어가 있는가

모델에게는 판정을 안 묻는다 — 개수와 보이는 글자만 받고, 통과인지는
`judge` 가 정한다(`pagecheck.parse` 와 같은 원칙). 같은 `judge` 를 화 전체
검수도 쓴다.

    python3 covercheck.py --run-id <id>            # 이미 그린 표지를 검수만
    python3 covercheck.py --run-id <id> --dry-run  # 프롬프트만 (0원)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import unicodedata
from pathlib import Path

import llm
import runmeta
import story

HERE = Path(__file__).resolve().parent
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))
PROMPT_DIR = HERE / "prompt"
PAGE_DIR = "pages"
STAGE = "COVER_REVIEW"


def log(msg: str) -> None:
    print(msg, flush=True)


def _text(x) -> str:
    return str(x).strip() if x is not None else ""


def _int(x, default: int = -1) -> int:
    try:
        return int(x)
    except (TypeError, ValueError):
        return default


def _norm(s: str) -> str:
    """제목 비교용 — 띄어쓰기·문장부호·대소문자 차이는 같은 제목으로 본다.

    이미지 모델은 줄바꿈이나 따옴표를 제멋대로 넣고, 검수 모델은 그것을
    공백으로 옮겨 적는다. 그 차이로 다시 그리면 헛돈이다. 글자 자체가
    다르거나 빠진 것만 잡는다.
    """
    s = unicodedata.normalize("NFC", s or "").lower()
    return "".join(ch for ch in s if ch.isalnum())


# -------------------------------------------------------------------- 판정

def judge(facts: dict, title: str) -> list[dict]:
    """센 값 -> 지적 목록. 비어 있으면 통과다.

    facts : {"panels", "boxes", "title_text"} — 검수 모델이 센 것.
    title : 이 작품의 제목(directions.json 의 고른 방향).

    못 센 값(-1)은 지적하지 않는다 — 판정 근거가 없는 것을 불합격으로 두면
    검수 응답 하나 흔들릴 때마다 그림값이 나간다.
    """
    issues = []
    panels = _int(facts.get("panels"))
    if panels > 1:
        issues.append({"kind": "칸", "what": f"표지가 칸 {panels}개로 나뉜 만화 페이지로 "
                       "그려졌다. 표지는 칸을 나누지 않은 그림 한 장이다."})
    boxes = _int(facts.get("boxes"))
    if boxes > 0:
        issues.append({"kind": "글상자", "what": f"표지에 말풍선·나레이션 상자·효과음이 "
                       f"{boxes}개 있다. 표지에는 제목 말고 글을 넣지 않는다."})
    want = _norm(title)
    got = _norm(_text(facts.get("title_text")))
    if want and not got:
        issues.append({"kind": "제목", "what": f"표지에 제목이 없다. 「{title}」를 "
                       "글자 그대로 넣어야 한다."})
    elif want and got != want:
        issues.append({"kind": "제목", "what": f"표지 제목이 「{_text(facts.get('title_text'))}」"
                       f"로 그려졌다. 「{title}」를 글자 그대로 써야 한다."})
    return issues


def parse(text: str, title: str) -> dict:
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("표지 검수 결과가 JSON 객체가 아닙니다.")
    facts = {"panels": _int(obj.get("panels")), "boxes": _int(obj.get("boxes")),
             "title_text": _text(obj.get("title_text")), "what": _text(obj.get("what"))}
    issues = judge(facts, title)
    return {"verdict": "재생성" if issues else "통과", **facts, "issues": issues}


def redraw_block(review: dict) -> str:
    """다시 그릴 때 표지 프롬프트 **뒤**에 붙이는 것."""
    lines = ["## 다시 그린다 — 앞서 그린 것이 표지 모양이 아니었다", ""]
    if review.get("what"):
        lines.append(f"- 앞서 그린 것: {review['what']}")
    lines += [f"- {one['what']}" for one in review.get("issues") or []]
    lines += ["", "위 「표지의 모양」을 전부 지킨 그림 한 장을 새로 그린다. 앞서 그린 "
                  "구도를 따라 할 필요는 없다."]
    return "\n".join(lines)


def latest_review(run_dir: Path) -> dict | None:
    """지금 있는 표지 그림을 **그린 뒤에** 나온 표지 검수 판정. 없으면 None.

    화 전체 검수가 표지를 다시 판정하지 않고 이것을 따르게 하려는 것이다 —
    같은 그림을 두 검수가 따로 세면 결과가 갈린다. 실제로 표지 검수는 통과를
    냈는데 화 전체 검수가 옷의 명찰을 글상자로 세어 1페이지를 세 번 더
    그리게 했다(2026-09-30, run 20260930T214233-674528).
    """
    dest = run_dir / PAGE_DIR
    cover = dest / "page01.png"
    if not cover.exists():
        return None
    drawn = cover.stat().st_mtime
    fresh = [p for p in dest.glob("page01.review*.json")
             if not p.name.endswith("_prompt.json") and p.stat().st_mtime >= drawn]
    if not fresh:
        return None
    latest = max(fresh, key=lambda p: p.stat().st_mtime)
    try:
        got = json.loads(latest.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return got if isinstance(got, dict) and got.get("verdict") else None


def title_of(run_dir: Path) -> str:
    """이 run 에서 고른 방향의 제목."""
    def read(name):
        p = run_dir / name
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
    pick = read("pick.json") or {}
    directions = read("directions.json") or []
    one = next((d for d in directions if d.get("n") == pick.get("n")), None) \
        or (directions[0] if directions else {})
    return _text(one.get("title"))


# -------------------------------------------------------------------- 호출 한 번

def review_cover(run_dir: Path, title: str, *, dry_run: bool = False,
                 suffix: str = "") -> tuple[dict | None, dict | None]:
    """표지를 검수한다. -> (판정, 호출기록). dry-run 이면 (None, None).

    호출이 실패해도 예외를 밖으로 안 던진다 — 그림은 이미 값을 치렀다.
    판정 없음으로 두고 사유를 기록에 남긴다(`pagecheck.review_page` 와 같다).
    """
    dest = run_dir / PAGE_DIR
    cover = dest / "page01.png"
    path = PROMPT_DIR / "cover_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    prompt = path.read_text(encoding="utf-8")
    dest.mkdir(parents=True, exist_ok=True)
    (dest / f"page01.review{suffix}_prompt.txt").write_text(prompt, encoding="utf-8")
    if dry_run:
        return None, None
    if not cover.exists():
        log(f"  [표지 검수] {cover.name} 이 없습니다 — 건너뜁니다")
        return None, None

    call = llm.Call(STAGE)
    log(f"  [표지 검수] {call.describe()} …")
    try:
        text, meta = call(prompt, images=llm.load_images([cover]), temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None, "page": 1,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [표지 검수] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta

    (dest / f"page01.review{suffix}.txt").write_text(text, encoding="utf-8")
    meta["page"] = 1
    try:
        review = parse(text, title)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [표지 검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    (dest / f"page01.review{suffix}.json").write_text(
        json.dumps(review, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"  [표지 검수] {review['verdict']} · 칸 {review['panels']} · 글상자 "
        f"{review['boxes']} · 제목 「{review['title_text']}」")
    for one in review["issues"]:
        log(f"    - {one['kind']}: {one['what']}")
    return review, meta


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    _, meta = review_cover(run_dir, title_of(run_dir), dry_run=args.dry_run)
    if meta:
        runmeta.append_call(run_dir, meta)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
