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

## 왜 호출을 둘로 가르는가

한 번에 물으면 **블라인드가 안 된다.** 「너는 오늘 처음 켠 독자다」 라고 적고
그림과 줄거리를 같은 요청에 붙이면, 모델은 이미 읽은 것을 안 읽은 척할 수
없다. 그러면 `read_as` 가 그림에서 읽은 것이 아니라 **붙여 준 줄거리의
요약**이 되고, 안 읽히는 화가 읽히는 것으로 기록된다. 결과가 그럴듯해서
아무도 눈치를 못 챈다.

그래서 두 번 부른다.

1. **블라인드 읽기**(`blind_read_prompt`) — 그림만. 제목도 장르도 줄거리도
   요청 안에 없다. 못 읽으면 못 읽었다고 나온다.
2. **견주기**(`episode_review_prompt`) — 1번의 보고와 되려던 것을 놓고
   무엇이 안 닿았는지 본다. **그림을 안 붙인다** — 글만 오가므로 값이
   거의 안 붙는다.

결과는 예전과 **같은 모양**으로 합쳐 쓴다(`episode_review.json`) — 화면과
run.py 는 아무것도 안 바뀐다.

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
STAGE = "EPISODE_REVIEW"          # 1) 그림만 보는 블라인드 읽기
MATCH_STAGE = "EPISODE_MATCH"     # 2) 되려던 것과 견주기 (글만, 그림 안 붙임)

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


def _prompt(name: str) -> str:
    path = PROMPT_DIR / name
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    return path.read_text(encoding="utf-8")


def build_blind_prompt() -> str:
    """1) 그림만 보는 쪽. **인자가 없다** — 그게 요점이다.

    여기에 무엇이든 끼워 넣을 수 있게 두면 언젠가 끼워진다. 줄거리가 한 줄
    들어가는 순간 이 검수는 다시 "이미 아는 사람" 이 되고, 그때는 결과가
    그럴듯해서 아무도 눈치를 못 챈다.
    """
    return _prompt("blind_read_prompt")


def build_match_prompt(blind: dict, direction: dict,
                       char: dict | None = None, cast=()) -> str:
    """2) 견주는 쪽. 블라인드 보고와 되려던 것만 넣는다 (그림은 안 붙인다)."""
    said = ["[읽은 대로] " + (_text(blind.get("read_as")) or "(말 못 함)"),
            "[주인공] " + (_text(blind.get("who")) or "(모르겠다)"),
            "[어디] " + (_text(blind.get("where")) or "(모르겠다)"),
            "[마지막에 남는 것] " + (_text(blind.get("ending")) or "(없음)")]
    if _text(blind.get("guessed")):
        said.append("[짐작으로 메운 것] " + _text(blind["guessed"]))
    found = blind.get("issues") or []
    if found:
        said.append("")
        said.append("[그 사람이 이미 적어 둔 문제 — 다시 적지 마라]")
        for one in found:
            where = f"{one['page']}페이지" if one.get("page") else "화 전체"
            said.append(f"- {where} [{one.get('severity')}] "
                        f"{one.get('kind')}: {one.get('what')}")
    return (_prompt("episode_review_prompt")
            .replace("{blind}", "\n".join(said))
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
            # 그림에 없는데 짐작으로 메운 것. 블라인드 읽기에서만 나온다 —
            # 짐작이 맞았더라도 그림이 말해 준 것이 아니므로 다음 독자는
            # 틀리게 짐작한다.
            "guessed": _text(obj.get("guessed")),
            "matched": _text(obj.get("matched")),
            "counts": counts,
            "issues": issues}


def merge(blind: dict, match: dict | None) -> dict:
    """블라인드 읽기와 견주기를 **예전과 같은 한 덩어리**로 합친다.

    화면(landing)과 run.py 는 이 모양만 안다 — 호출을 둘로 가른 것이
    바깥으로 새어 나가지 않게 한다. 견주기가 실패했으면 블라인드 것만
    쓴다: 그림은 이미 다 그렸고, 처음 읽은 사람이 말한 것은 그 자체로
    쓸모가 있다.
    """
    if not match:
        return blind
    issues = list(blind["issues"]) + list(match["issues"])
    issues.sort(key=lambda i: (SEVERITY.index(i["severity"]), i["page"]))
    counts = {s: sum(1 for i in issues if i["severity"] == s) for s in SEVERITY}
    return {**blind,
            "matched": match["matched"],
            "verdict": "주의" if counts["critical"] else "통과",
            "counts": counts,
            "issues": issues}


def summary(review: dict) -> str:
    n = review["counts"]
    return (f"{review['verdict']} — critical {n['critical']} · major {n['major']} "
            f"· minor {n['minor']}")


# -------------------------------------------------------------------- 호출 한 번

def _one_call(stage: str, prompt: str, images, run_dir: Path, stem: str, label: str):
    """한 번 부르고 (원문, 호출기록) 을 돌려준다. **예외를 밖으로 안 던진다.**

    그림은 이미 다 그려서 값을 치렀다 — 검수가 안 됐다고 화를 잃으면 안 된다.
    원문은 파싱 전에 먼저 쓴다: 파싱이 죽어도 그 호출에 쓴 돈이 안 사라진다.
    """
    call = llm.Call(stage)
    try:
        text, meta = call(prompt, images=images, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": stage, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None,
                "pages": len(images) if images else 0,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [{label}] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta
    write_text(run_dir / f"{stem}.txt", text)
    meta["pages"] = len(images) if images else 0
    return text, meta


def review_episode(run_dir: Path, *, direction: dict, char: dict | None = None,
                   cast=(), dry_run: bool = False) -> tuple[dict | None, list[dict]]:
    """이 화 전부를 검수한다. -> (판정, 호출기록 목록).

    **두 번 부른다** (머리말의 「왜 호출을 둘로 가르는가」 참고):
    먼저 그림만 보여 주고, 그 다음에 그 보고와 되려던 것을 견준다.

    호출기록이 **목록**인 것이 예전과 다르다. 부르는 쪽은 이제 하나가
    아니라 여럿을 meta.json 에 적어야 한다.
    """
    pages = pages_of(run_dir)
    if not pages:
        log("  [화 검수] 그려진 페이지가 없습니다 — 건너뜁니다")
        return None, []

    blind_prompt = build_blind_prompt()
    write_text(run_dir / "blind_read_prompt.txt", blind_prompt)
    if dry_run:
        # 견주기 프롬프트도 같이 써 둔다. 블라인드 보고가 아직 없으므로
        # 그 자리에는 무엇이 들어갈지만 적는다 — 0원으로 모양을 본다.
        write_text(run_dir / "episode_review_prompt.txt",
                   build_match_prompt({"read_as": "(1번 호출이 여기에 들어간다)"},
                                      direction, char, cast))
        log(f"[화 검수] 프롬프트만 썼습니다 -> {run_dir / 'blind_read_prompt.txt'}")
        return None, []

    if len(pages) > MAX_IMAGES:
        log(f"  [화 검수] 페이지가 {len(pages)}장이라 뒤 {MAX_IMAGES}장만 붙입니다")
        pages = pages[-MAX_IMAGES:]

    calls: list[dict] = []

    # ── 1) 블라인드 읽기 — 그림만. 줄거리는 이 요청 어디에도 없다 ──────
    images = llm.load_images(pages)
    log(f"[화 검수] {len(images)}장을 **아무 정보 없이** 처음부터 읽습니다…")
    text, meta = _one_call(STAGE, blind_prompt, images, run_dir,
                           "blind_read", "블라인드 읽기")
    calls.append(meta)
    if text is None:
        return None, calls
    try:
        blind = parse(text)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [블라인드 읽기] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, calls

    log(f"  [블라인드 읽기] 읽은 대로: {blind['read_as'] or '(말 못 함)'}")
    if blind["guessed"]:
        log(f"    짐작으로 메운 것: {blind['guessed']}")

    # ── 2) 견주기 — 글만. 그림을 안 붙이므로 값이 거의 안 붙는다 ───────
    match_prompt = build_match_prompt(blind, direction, char, cast)
    write_text(run_dir / "episode_review_prompt.txt", match_prompt)
    log("[화 검수] 되려던 것과 견줍니다…")
    text2, meta2 = _one_call(MATCH_STAGE, match_prompt, None, run_dir,
                             "episode_review", "견주기")
    calls.append(meta2)
    match = None
    if text2 is not None:
        try:
            match = parse(text2)
        except Exception as exc:                                      # noqa: BLE001
            meta2["error"] = f"{type(exc).__name__}: {exc}"
            log(f"  [견주기] 응답을 읽지 못했습니다 — {meta2['error']} "
                f"(블라인드 읽기만 씁니다)")

    review = merge(blind, match)
    write_json(run_dir / "episode_review.json", review)
    log(f"  [화 검수] {summary(review)}")
    if review["matched"]:
        log(f"    안 닿은 것: {review['matched']}")
    for one in review["issues"]:
        where = f"{one['page']}페이지 " if one["page"] else "화 전체 "
        log(f"    - {where}[{one['severity']}] {one['kind']}: {one['what']}")
    return review, calls


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
    review, calls = review_episode(run_dir, direction=direction, char=char,
                                   cast=cast, dry_run=dry_run)
    for meta in calls:
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
