#!/usr/bin/env python3
"""그린 뒤 검수 — **앞 장에서 이어지는가.**

## 왜 있는가

이어그리기(`detailart.draw_continue`)는 장면 하나를 주고 컷·구도·대사를
그림 모델이 스스로 정하게 둔다. 연출은 그래야 살아 있는데, 그 자유가
**이야기까지** 건드리는 일이 실측으로 나왔다 — 두 번째 호출이 다섯 번째
장면(결말)을 그려 버리는 식이다. 그리라고 준 장면 번호를 프롬프트에 못
박아 두는 것만으로는 안 잡혔다.

그래서 **그린 뒤에** 한 번 더 본다. 직전 그림과 지금 그림 두 장을 이야기와
함께 주고, 사람이 아니라 모델이 읽는다.

## 무엇을 보는가 — "장면이 맞는가" 가 아니다

한 장이 장면 하나를 정확히 담아야 하는 것이 아니다. 한 장면이 두 장에
걸쳐도 되고, 한 장이 앞 장면의 끝을 마저 그리고 다음 장면으로 넘어가도
된다. 다음 이야기의 복선을 미리 깔아도 된다.

보는 것은 하나다 — **직전 그림에서 지금 그림으로 이야기가 이어지는가.**
`이어짐` / `제자리`(같은 순간을 다시 그림) / `건너뜀`(안 그린 이야기를
통째로 지나침) / `되돌아감` 넷으로 판정한다.

연출(컷 수·구도·카메라·여백·대사)은 **판정 대상이 아니다.** 그것까지 보면
검수가 곧 컷 지정이 되어, 컷 대본을 없앤 이유가 사라진다.

## 어떻게 쓰는가

이어그리기가 한 장을 그릴 때마다 부른다(`NH_PAGE_REVIEW=0` 이면 안 부른다).
`critical` 이 하나라도 나오면 그 장을 지우고, 검수가 적어 준 것을 프롬프트
뒤에 붙여 **한 번만** 다시 그린다(`NH_PAGE_REVIEW_RETRY`).

    python3 pagecheck.py --run-id <id>            # 이미 그린 것을 검수만 (안 다시 그림)
    python3 pagecheck.py --run-id <id> --page 3   # 그 장만
    python3 pagecheck.py --run-id <id> --dry-run  # 프롬프트만 (0원)

결과는 `pages/pageNN.review.json` 에 쌓인다. 원문(`.review.txt`)은 파싱
**전에** 먼저 쓴다 — 파싱이 죽어도 그 호출에 쓴 돈이 사라지지 않는다.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import llm
from llm import story

HERE = Path(__file__).resolve().parent
RUNS_DIR = HERE / "runs"
PROMPT_DIR = HERE / "prompt"
PAGE_DIR = "pages"
STAGE = "PAGE_REVIEW"

SEVERITY = ("critical", "major", "minor")
KINDS = ("연속성", "제자리", "건너뜀", "되돌아감", "인물", "공간")
FLOWS = ("이어짐", "제자리", "건너뜀", "되돌아감")

# 다시 그리게 만드는 흐름. `이어짐` 만 통과다 — 나머지 셋은 독자가 그 장에서
# 이야기를 놓친다는 뜻이라 무게와 상관없이 잡는다.
BAD_FLOWS = ("제자리", "건너뜀", "되돌아감")


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
    """호출 하나를 run.py 의 meta.json 에 남긴다 — 그림 호출과 같은 자리.

    검수도 돈이 나가는 호출이다. 단독으로 돌렸을 때만 기록이 빠지면, 나중에
    이 run 에 얼마가 들었는지가 어디서 돌렸느냐에 따라 달라진다.
    """
    path = run_dir / "meta.json"
    meta = read_json(path) or {"run_id": run_dir.name, "calls": []}
    meta["calls"].append(call_meta)
    write_json(path, meta)


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


def _strs(x) -> list[str]:
    return [_text(v) for v in x if _text(v)] if isinstance(x, list) else []


def _ints(x) -> list[int]:
    out = []
    for v in (x if isinstance(x, list) else []):
        try:
            out.append(int(v))
        except (TypeError, ValueError):
            continue
    return out


# ------------------------------------------------------------------ 켜고 끄기

def enabled() -> bool:
    """기본은 **켜짐.** 끄려면 `.env` 에 `NH_PAGE_REVIEW=0`.

    켜 두는 쪽을 기본으로 잡은 이유는, 이 검수가 잡으라고 만든 문제(뒤
    장면으로 건너뛰기)가 제품에서 실제로 나오고 있어서다 — 기본이 꺼져
    있으면 켜는 것을 잊은 판이 그대로 나간다.
    """
    return _text(llm.env("NH_PAGE_REVIEW") or "").lower() not in ("0", "off", "false", "no")


def max_redraw() -> int:
    """한 장을 최대 몇 번까지 다시 그리는가. 기본 1.

    0 이면 검수는 하되 다시 그리지는 않는다 — 무엇이 걸리는지만 보고 싶을
    때 쓴다. 올려 잡지 않는 이유는 두 번째도 어긋나면 그 다음이 나아진다는
    근거가 없어서다(그림 값만 계속 나간다).
    """
    try:
        return max(0, int(_text(llm.env("NH_PAGE_REVIEW_RETRY") or "1")))
    except ValueError:
        return 1


# -------------------------------------------------------------------- 프롬프트

def people_block(char: dict | None, cast) -> str:
    """인물 — "다른 사람이 되었다" 를 판정할 기준.

    캐릭터 시트는 안 붙인다. 이 호출에 붙는 그림은 **직전 장과 지금 장**
    둘뿐이고, 셋째 장을 더하면 모델이 무엇을 검수 대상으로 보는지 흐려진다.
    같은 사람인지는 두 그림을 견주면 알 수 있다.
    """
    lines = []
    if char:
        who = _text(char.get("name"))
        desc = _text(char.get("description"))
        if who:
            lines.append(f"{who} (주인공) — {desc}" if desc else f"{who} (주인공)")
    for one in cast or []:
        if isinstance(one, dict) and _text(one.get("name")):
            lines.append(f"{one['name']} — {_text(one.get('appearance'))}")
    if not lines:
        return ""
    return "## 인물\n" + "\n".join(lines)


def story_block(direction: dict, scene_no: int) -> str:
    """전체 이야기 — 어디까지 왔는지를 견줄 자리.

    지금 장면 앞뒤를 다 준다. 뒤를 가리면 "건너뛰었다" 를 판정할 수가 없다 —
    무엇을 건너뛴 것인지 알아야 건너뛴 것을 안다.
    """
    scenes = [s for s in (direction.get("scenes") or []) if _text(s)]
    lines = ["## 전체 이야기"]
    title, genre = _text(direction.get("title")), _text(direction.get("genre"))
    if title or genre:
        lines.append(f"[작품] {title}" + (f" · {genre}" if genre else ""))
    if _text(direction.get("plot")):
        lines += ["", "[줄거리]", _text(direction["plot"])]
    if scenes:
        lines += ["", "[장면 — 순서대로 일어나는 사건들이다]"]
        for i, s in enumerate(scenes, 1):
            if i < scene_no:
                mark = "  (앞 장들에서 이미 그려진 자리)"
            elif i == scene_no:
                mark = "  ← 이 장을 그릴 때 준 장면"
            else:
                mark = "  (아직 안 그려진 자리)"
            lines.append(f"{i}. {_text(s)}{mark}")
    return "\n".join(lines)


def prev_block(direction: dict, scene_no: int, *, has_prev: bool,
               prev_is_cover: bool, next_from: str = "") -> str:
    """직전 그림이 무엇인지. 첨부 순서 1번을 글로 설명해 준다."""
    if not has_prev:
        return "## 직전 페이지\n없다. 이 화의 첫 장이다 — 첨부한 그림은 지금 페이지 하나뿐이다."
    if prev_is_cover:
        return ("## 직전 페이지\n표지다. 이야기의 한 순간이 아니라 이 작품을 대표하는 "
                "한 장이라, 지금 그림이 표지에서 '이어질' 필요는 없다. 인물이 같은 "
                "사람인지만 견준다.")
    scenes = [s for s in (direction.get("scenes") or []) if _text(s)]
    prev_no = scene_no - 1
    body = _text(scenes[prev_no - 1]) if 0 < prev_no <= len(scenes) else ""
    lines = ["## 직전 페이지",
             f"바로 앞 장이다. 그릴 때 준 장면은 {prev_no}번이었다."]
    if body:
        lines.append(f"\"{body}\"")
    if _text(next_from):
        lines += ["", f"[앞 장 검수가 적어 둔 것 — 여기서부터 이어 그리면 된다] {_text(next_from)}"]
    return "\n".join(lines)


def scene_block(direction: dict, scene_no: int, total: int) -> str:
    scenes = [s for s in (direction.get("scenes") or []) if _text(s)]
    body = _text(scenes[scene_no - 1]) if 0 < scene_no <= len(scenes) else ""
    return ("## 이 장을 그릴 때 준 지시\n"
            f"전체 {total}장면 중 {scene_no}번 장면을 그리라고 했다.\n"
            + (f"\"{body}\"\n" if body else "")
            + "\n이 지시는 **출발점**이지 채점표가 아니다. 이 장면을 다 담지 못했어도, "
              "앞 장면의 끝을 마저 그리고 있어도, 다음 장면으로 넘어가 있어도 된다 — "
              "직전 그림에서 이어져 앞으로 가고 있으면 통과다.")


def build_prompt(direction: dict, *, scene_no: int, char: dict | None = None,
                 cast=(), has_prev: bool = True, prev_is_cover: bool = False,
                 next_from: str = "") -> str:
    path = PROMPT_DIR / "page_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    total = len([s for s in (direction.get("scenes") or []) if _text(s)])
    return (path.read_text(encoding="utf-8")
            .replace("{people}", people_block(char, cast))
            .replace("{story}", story_block(direction, scene_no))
            .replace("{prev}", prev_block(direction, scene_no, has_prev=has_prev,
                                          prev_is_cover=prev_is_cover,
                                          next_from=next_from))
            .replace("{scene}", scene_block(direction, scene_no, total)))


# ------------------------------------------------------------------------ 파싱

def parse(text: str) -> dict:
    """검수 응답(JSON) -> 판정.

    `verdict` 는 모델에게 안 묻는다. **흐름과 무게에서 코드가 센다** —
    "critical 이면 다시 그린다" 같은 규칙을 모델이 지켰는지는 여기서 확인할
    수 있고, 어긋나면 센 쪽이 맞다(`run.parse_review` 와 같은 이유).
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("검수 결과가 JSON 객체가 아닙니다.")

    flow = _text(obj.get("flow"))
    if flow not in FLOWS:
        # 모르는 값은 통과로 보지 않는다 — 조용히 흘려보내면 검수가 있는
        # 것과 없는 것이 같아진다. 대신 `이어짐` 이 아닐 뿐이라 무게는
        # issues 가 정한다.
        flow = flow or "?"

    issues = []
    for one in obj.get("issues") or []:
        if not isinstance(one, dict):
            continue
        sev = _text(one.get("severity")).lower()
        kind = _text(one.get("kind"))
        issues.append({"kind": kind if kind in KINDS else (kind or "연속성"),
                       "severity": sev if sev in SEVERITY else "major",
                       "what": _text(one.get("what"))})
    issues.sort(key=lambda i: SEVERITY.index(i["severity"]))

    fail = any(i["severity"] == "critical" for i in issues) or flow in BAD_FLOWS
    return {"verdict": "재생성" if fail else "통과",
            "flow": flow,
            "drawn": _text(obj.get("drawn")),
            "covers": _text(obj.get("covers")),
            "scenes": _ints(obj.get("scenes")),
            "why": _text(obj.get("why")),
            "next_from": _text(obj.get("next_from")),
            "issues": issues,
            "redraw": _text(obj.get("redraw"))}


def summary(review: dict) -> str:
    n = {s: 0 for s in SEVERITY}
    for one in review.get("issues") or []:
        n[one["severity"]] += 1
    return (f"{review['verdict']} · 흐름 {review['flow']} — "
            f"critical {n['critical']} · major {n['major']} · minor {n['minor']}")


def redraw_block(review: dict) -> str:
    """다시 그릴 때 프롬프트 **뒤**에 붙이는 것.

    이야기만 짚고 연출은 안 건드린다 — 여기서 컷을 지시하면 다시 그린 장이
    검수를 통과하는 대신 평평해진다(컷 대본 단계를 없앤 이유와 같다).
    """
    lines = ["## 다시 그린다 — 앞서 그린 한 장이 이야기와 어긋났다", "",
             "이 장면으로 이미 한 번 그렸는데, 읽어 보니 아래가 걸렸다. "
             "**같은 자리를 다시 그린다.**", ""]
    if review.get("drawn"):
        lines.append(f"- 앞서 그린 것: {review['drawn']}")
    if review.get("flow") and review["flow"] != "이어짐":
        why = f" — {review['why']}" if review.get("why") else ""
        lines.append(f"- 직전 그림에서 이어지지 않는다 ({review['flow']}){why}")
    for one in review.get("issues") or []:
        if one["severity"] in ("critical", "major"):
            lines.append(f"- {one['what']}")
    if review.get("redraw"):
        lines += ["", "[바로잡을 것]", review["redraw"]]
    lines += ["", "**연출은 그대로 네가 정한다** — 컷 수·구도·카메라·여백·대사는 "
                  "앞서 그린 것을 따라 할 필요가 없다. 위에 적힌 것은 이야기 쪽이다. "
                  "직전 그림에서 자연스럽게 이어지는 다음 순간을 그린다."]
    return "\n".join(lines)


# -------------------------------------------------------------------- 호출 한 번

def review_page(run_dir: Path, page_no: int, *, scene_no: int, direction: dict,
                char: dict | None = None, cast=(), prev_is_cover: bool = False,
                next_from: str = "", dry_run: bool = False,
                suffix: str = "") -> tuple[dict | None, dict | None]:
    """한 장을 검수한다. -> (판정, 호출기록). dry-run 이면 (None, None).

    호출이 실패해도 **예외를 밖으로 안 던진다.** 그림은 이미 그려서 값을
    치렀다 — 검수가 안 됐다고 그 장을 잃으면 안 된다. 대신 기록에 사유를
    남기고 통과로 두지 않는다(판정 자체가 없음).
    """
    dest = run_dir / PAGE_DIR
    cur = dest / f"page{page_no:02d}.png"
    prev = dest / f"page{page_no - 1:02d}.png"
    has_prev = page_no > 1 and prev.exists()

    prompt = build_prompt(direction, scene_no=scene_no, char=char, cast=cast,
                          has_prev=has_prev, prev_is_cover=prev_is_cover,
                          next_from=next_from)
    write_text(dest / f"page{page_no:02d}.review{suffix}_prompt.txt", prompt)
    if dry_run:
        return None, None
    if not cur.exists():
        log(f"  [검수] {cur.name} 이 없습니다 — 건너뜁니다")
        return None, None

    call = llm.Call(STAGE)
    images = llm.load_images([p for p in ([prev] if has_prev else []) + [cur]])
    log(f"  [검수] {call.describe()} · 그림 {len(images)}장 …")
    try:
        text, meta = call(prompt, images=images, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None, "page": page_no,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [검수] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta

    write_text(dest / f"page{page_no:02d}.review{suffix}.txt", text)
    meta["page"] = page_no
    try:
        review = parse(text)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    write_json(dest / f"page{page_no:02d}.review{suffix}.json", review)
    log(f"  [검수] {summary(review)}")
    for one in review["issues"]:
        log(f"    - [{one['severity']}] {one['kind']}: {one['what']}")
    return review, meta


# -------------------------------------------------------------------- 단독 실행

def review_run(run_dir: Path, only=None, dry_run: bool = False,
               on_call=None) -> list[dict]:
    """이미 그려 둔 페이지를 검수만 한다 — **다시 그리지는 않는다.**

    무엇이 걸리는지 먼저 보고 싶을 때, 그리고 옛 run 을 나중에 훑을 때 쓴다.
    """
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
    scenes = [s for s in (direction.get("scenes") or []) if _text(s)]

    out = []
    next_from = ""
    for scene_no in range(1, len(scenes) + 1):
        page_no = scene_no + 1                     # 1페이지는 표지다
        if only and page_no not in only:
            continue
        review, meta = review_page(run_dir, page_no, scene_no=scene_no,
                                   direction=direction, char=char, cast=cast,
                                   prev_is_cover=(scene_no == 1),
                                   next_from=next_from, dry_run=dry_run)
        if meta and on_call:
            on_call(meta)
        if review:
            out.append({"page": page_no, **review})
            next_from = review.get("next_from") or ""
    if out:
        bad = [r for r in out if r["verdict"] == "재생성"]
        log(f"[검수] {len(out)}장 중 {len(bad)}장이 다시 그릴 것으로 나왔습니다"
            + (f" — {', '.join(str(r['page']) + '페이지' for r in bad)}" if bad else ""))
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--page", type=int, nargs="*", default=[],
                   help="이 페이지 번호만 (1 은 표지라 검수 대상이 아니다)")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    review_run(run_dir, only=args.page or None, dry_run=args.dry_run,
               on_call=lambda meta: record(run_dir, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
