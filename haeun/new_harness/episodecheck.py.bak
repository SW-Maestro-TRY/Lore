#!/usr/bin/env python3
"""다 그린 뒤 검수 — **처음부터 끝까지 읽어 본다.**

## 왜 있는가

장마다 보는 검수(`pagecheck`)는 **직전 장과 지금 장 두 장**만 본다. 그
자리에서 잡을 수 있는 것은 "여기서 저기로 건너뛰었다" 까지다. 인접한 두
장이 전부 매끄러워도, 다 읽고 나서 무슨 이야기였는지 말할 수 없는 일은
그대로 통과한다 — 이어짐은 이야기가 있다는 뜻이 아니다.

그 자리를 컷 대본 검수(`stage_cutscript_fix`)가 하고 있었다. "사전 정보가
하나도 없는 사람이 화면만 보고 이해하는가" 를 컷 대본 위에서 봤다.
2026-09-02부터 컷 대본 단계를 안 도니 그 검수도 같이 죽었다. 이건 그
관점을 **글이 아니라 다 그려진 그림 위로** 옮겨 놓은 것이다. 글에서 보던
때보다 늦지만, 대신 진짜 나가는 것을 본다 — 컷 대본에서는 말이 되는데
그림이 되고 나서 안 읽히는 일이 원래 문제였다.

## 다시 그리지 않는다

판정만 하고 아무것도 안 고친다. 화 전체가 걸렸을 때 고치는 길은 한 장을
다시 그리는 것이 아니라 이야기를 다시 고르는 것인데, 그건 사람이 정할
일이다. 값도 그렇다 — 한 장 다시 그리기와 달리 여기서 "다시" 는 화
전체다.

**그래서 이 검수는 장마다 보는 검수가 일부러 안 보는 것도 본다** — 깨진
글자가 대표다. pagecheck 은 그것 때문에 다시 그리면 손해라서 안 보지만,
여기서는 다시 그릴 일이 없으니 사람에게 알려 주기만 하면 된다.

## 어떻게 쓰는가

이어그리기가 이 화를 다 그린 뒤에 한 번 부른다 (`NH_EPISODE_REVIEW=0`
이면 안 부른다). 한 장만 다시 그릴 때(`--page 3`)는 안 부른다 — 화
전체를 보는 검수라 일부만 새로 그린 상태에서는 볼 것이 못 된다.

    python3 episodecheck.py --run-id <id>            # 이미 그린 것을 검수만
    python3 episodecheck.py --run-id <id> --dry-run  # 프롬프트만 (0원)

결과는 `episode_review.json` 에 쌓인다. 원문(`episode_review.txt`)은 파싱
**전에** 먼저 쓴다 — 파싱이 죽어도 그 호출에 쓴 돈이 사라지지 않는다.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import llm
from llm import story

HERE = Path(__file__).resolve().parent
RUNS_DIR = HERE / "runs"
PROMPT_DIR = HERE / "prompt"
PAGE_DIR = "pages"
STAGE = "EPISODE_REVIEW"

SEVERITY = ("critical", "major", "minor")
KINDS = ("이해", "인물", "공간", "흐름", "글자", "마무리")

# 한 호출에 붙이는 그림의 상한. 지금 한 화는 표지+장면 5~7장이라 안 걸리는
# 값인데, 장면이 늘어난 화가 조용히 값만 키우는 것을 막아 둔다. 넘으면
# 앞에서부터 자른다(뒤를 자르면 마무리를 못 본다 — 그건 이 검수의 핵심이다).
MAX_IMAGES = 12


def log(msg: str) -> None:
    print(msg, flush=True)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def record(run_dir: Path, call_meta: dict) -> None:
    """호출 하나를 meta.json 에 남긴다 (pagecheck.record 와 같다)."""
    path = run_dir / "meta.json"
    meta = read_json(path) or {"run_id": run_dir.name, "calls": []}
    meta["calls"].append(call_meta)
    write_json(path, meta)


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


def _int(x, default: int = 0) -> int:
    try:
        return int(x)
    except (TypeError, ValueError):
        return default


# ------------------------------------------------------------------ 켜고 끄기

def enabled() -> bool:
    """기본은 **켜짐.** 끄려면 `.env` 에 `NH_EPISODE_REVIEW=0`."""
    return _text(llm.env("NH_EPISODE_REVIEW") or "").lower() not in ("0", "off", "false", "no")


# -------------------------------------------------------------------- 프롬프트

def pages_of(run_dir: Path) -> list[Path]:
    """이 화의 페이지 그림 전부, 순서대로."""
    dest = run_dir / PAGE_DIR
    if not dest.is_dir():
        return []
    got = []
    for p in dest.glob("page*.png"):
        m = re.fullmatch(r"page(\d+)", p.stem)
        if m:
            got.append((int(m.group(1)), p))
    return [p for _, p in sorted(got)]


def story_block(direction: dict, char: dict | None = None, cast=()) -> str:
    """다 읽은 뒤에 견줄 것. 「읽는 동안 참고하지 마라」 는 프롬프트가 말한다.

    장면 목록까지 준다 — 안 주면 "그림만 본 사람이 놓치는 것"(matched)을
    셀 기준이 없다.
    """
    lines = ["## 이 화가 되려던 것", "",
             "**먼저 그림만 다 넘겨 본 뒤에 읽어라.**", ""]
    title, genre = _text(direction.get("title")), _text(direction.get("genre"))
    if title or genre:
        lines.append(f"[작품] {title}" + (f" · {genre}" if genre else ""))
    if _text(direction.get("plot")):
        lines += ["", "[줄거리]", _text(direction["plot"])]

    who = []
    if char and _text(char.get("name")):
        desc = _text(char.get("description"))
        who.append(f"{_text(char['name'])} (주인공)" + (f" — {desc}" if desc else ""))
    for one in cast or []:
        if isinstance(one, dict) and _text(one.get("name")):
            look = _text(one.get("appearance"))
            who.append(f"{_text(one['name'])}" + (f" — {look}" if look else ""))
    if who:
        lines += ["", "[인물]"] + [f"- {w}" for w in who]

    scenes = [s for s in (direction.get("scenes") or []) if _text(s)]
    if scenes:
        lines += ["", "[장면 — 한 줄이 그림 한 장이 될 예정이었다. 표지가 1장이므로 "
                      "장면 1번이 2페이지다]"]
        for i, s in enumerate(scenes, 1):
            lines.append(f"{i}. {_text(s)}  (→ {i + 1}페이지)")

    hidden = [h for h in (direction.get("hidden") or []) if _text(h)]
    if hidden:
        lines += ["", "[일부러 안 밝힌 것 — 안 밝혀진 것은 문제가 아니다]"]
        lines += [f"- {_text(h)}" for h in hidden]
    return "\n".join(lines)


def build_prompt(direction: dict, char: dict | None = None, cast=()) -> str:
    path = PROMPT_DIR / "episode_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    return (path.read_text(encoding="utf-8")
            .replace("{story}", story_block(direction, char, cast)))


# ------------------------------------------------------------------------ 파싱

def parse(text: str) -> dict:
    """검수 응답(JSON) -> 판정. `verdict` 는 코드가 센다 (pagecheck 과 같다)."""
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("검수 결과가 JSON 객체가 아닙니다.")

    issues = []
    for one in obj.get("issues") or []:
        if not isinstance(one, dict):
            continue
        sev = _text(one.get("severity")).lower()
        kind = _text(one.get("kind"))
        issues.append({"page": _int(one.get("page")),
                       "kind": kind if kind in KINDS else (kind or "이해"),
                       "severity": sev if sev in SEVERITY else "major",
                       "what": _text(one.get("what"))})
    issues.sort(key=lambda i: (SEVERITY.index(i["severity"]), i["page"]))
    counts = {s: sum(1 for i in issues if i["severity"] == s) for s in SEVERITY}
    return {"verdict": "주의" if counts["critical"] else "통과",
            "read_as": _text(obj.get("read_as")),
            "who": _text(obj.get("who")),
            "where": _text(obj.get("where")),
            "ending": _text(obj.get("ending")),
            "matched": _text(obj.get("matched")),
            "counts": counts,
            "issues": issues}


def summary(review: dict) -> str:
    n = review["counts"]
    return (f"{review['verdict']} — critical {n['critical']} · major {n['major']} "
            f"· minor {n['minor']}")


# -------------------------------------------------------------------- 호출 한 번

def review_episode(run_dir: Path, *, direction: dict, char: dict | None = None,
                   cast=(), dry_run: bool = False) -> tuple[dict | None, dict | None]:
    """이 화 전부를 한 번에 검수한다. -> (판정, 호출기록).

    호출이 실패해도 **예외를 밖으로 안 던진다.** 그림은 이미 다 그려서 값을
    치렀다 — 검수가 안 됐다고 화를 잃으면 안 된다.
    """
    pages = pages_of(run_dir)
    if not pages:
        log("  [화 검수] 그려진 페이지가 없습니다 — 건너뜁니다")
        return None, None

    prompt = build_prompt(direction, char, cast)
    write_text(run_dir / "episode_review_prompt.txt", prompt)
    if dry_run:
        log(f"[화 검수] 프롬프트만 썼습니다 -> {run_dir / 'episode_review_prompt.txt'}")
        return None, None

    if len(pages) > MAX_IMAGES:
        log(f"  [화 검수] 페이지가 {len(pages)}장이라 뒤 {MAX_IMAGES}장만 붙입니다")
        pages = pages[-MAX_IMAGES:]

    call = llm.Call(STAGE)
    images = llm.load_images(pages)
    log(f"[화 검수] {call.describe()} 로 {len(images)}장을 처음부터 읽습니다…")
    try:
        text, meta = call(prompt, images=images, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None, "pages": len(images),
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [화 검수] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta

    write_text(run_dir / "episode_review.txt", text)
    meta["pages"] = len(images)
    try:
        review = parse(text)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [화 검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    write_json(run_dir / "episode_review.json", review)
    log(f"  [화 검수] {summary(review)}")
    if review["read_as"]:
        log(f"    읽은 대로: {review['read_as']}")
    for one in review["issues"]:
        where = f"{one['page']}페이지 " if one["page"] else "화 전체 "
        log(f"    - {where}[{one['severity']}] {one['kind']}: {one['what']}")
    return review, meta


# -------------------------------------------------------------------- 단독 실행

def review_run(run_dir: Path, dry_run: bool = False, on_call=None) -> dict | None:
    pick = read_json(run_dir / "pick.json") or {}
    directions = read_json(run_dir / "directions.json") or []
    direction = (next((d for d in directions if d.get("n") == pick.get("n")), None)
                 or (directions[0] if directions else None))
    if not direction:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다.")

    char = read_json(run_dir / "input.json")
    hero = _text((char or {}).get("name"))
    cast = [c for c in (direction.get("cast") or [])
            if isinstance(c, dict) and _text(c.get("name")) and _text(c.get("name")) != hero]
    review, meta = review_episode(run_dir, direction=direction, char=char,
                                  cast=cast, dry_run=dry_run)
    if meta and on_call:
        on_call(meta)
    return review


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    review_run(run_dir, dry_run=args.dry_run,
               on_call=lambda meta: record(run_dir, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
