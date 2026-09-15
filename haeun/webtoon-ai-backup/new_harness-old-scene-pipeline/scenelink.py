#!/usr/bin/env python3
"""디테일 단계 — 장면이 **어디서 끝나는지**를 그리기 전에 정해 둔다.

## 왜 있는가

이어그리기는 장면을 차례로 그리면서 이어짐을 두 가지로 붙들고 있었다.

1. 직전 장의 **그림**을 참조로 붙인다
2. 직전 장을 검수한 모델이 적어 준 「다음은 여기서부터」(`pagecheck.next_from`)

둘 다 **앞 장이 이미 그려져 있어야** 쓸 수 있다. 그래서 장면을 동시에 그릴
수가 없다 — 2번 장면은 1번이 끝날 때까지 기다린다. 한 편에 장면이 대여섯
개면 그림 값을 치르는 시간이 그대로 대여섯 배로 쌓인다.

이 단계는 그 둘을 **글 하나로 앞당긴다.** 그리기 전에 한 번, 장면 목록
전체를 놓고 "각 장면이 어디서 끝나는가"를 적어 둔다. 장면 N 의 마무리가 곧
장면 N+1 의 시작이라, N+1 을 그리는 쪽은 N 의 그림을 못 봐도 어디서 이어
그릴지 안다. 그러면 **모든 장면을 동시에 그려도 이야기가 이어진다.**

호출 한 번이다(글). 장면마다 따로 묻지 않는 이유는, 장면들의 이음새는 서로
맞물려야 하는 것이라 한자리에서 같이 정해야 어긋나지 않기 때문이다.

## 무엇을 정하지 않는가

컷·구도·카메라·대사는 **안 정한다.** 그것까지 정하면 컷 대본 단계를 되살린
것이 되고, 그림이 지시를 옮기기만 해서 평평해진다(그 단계를 지운 이유다).
정하는 것은 장면의 시작과 끝, 두 지점뿐이다.

## 쓰는 법

    python3 scenelink.py --run-id <id> --dry-run   # 프롬프트만 (0원)
    python3 scenelink.py --run-id <id>             # 실제 호출 (글 한 번)

결과는 `scene_link.json`. 이미 있으면 다시 부르지 않는다 — 장면을 동시에
그리는 여러 프로세스가 저마다 이 단계를 부르면 안 되기 때문이다.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import llm
import runmeta
from llm import story

HERE = Path(__file__).resolve().parent
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))
PROMPT_DIR = HERE / "prompt"
STAGE = "DETAIL"
LINK_FILE = "scene_link.json"


def log(msg: str) -> None:
    print(msg, flush=True)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


def scenes_of(direction: dict) -> list[str]:
    return [_text(s) for s in (direction.get("scenes") or []) if _text(s)]


# -------------------------------------------------------------------- 프롬프트

def input_block(direction: dict, char: dict | None) -> str:
    """이번 입력 — 작품·줄거리·장면 목록·인물."""
    scenes = scenes_of(direction)
    lines = ["# 이번 입력", ""]
    title, genre = _text(direction.get("title")), _text(direction.get("genre"))
    if title or genre:
        lines.append(f"[작품] {title}" + (f" · {genre}" if genre else ""))
    if _text(direction.get("plot")):
        lines += ["", "[줄거리]", _text(direction["plot"])]

    hero = _text((char or {}).get("name"))
    who = []
    if hero:
        desc = _text((char or {}).get("description"))
        who.append(f"{hero} (주인공)" + (f" — {desc}" if desc else ""))
    for one in direction.get("cast") or []:
        if isinstance(one, dict) and _text(one.get("name")) and _text(one.get("name")) != hero:
            who.append(f"{one['name']} — {_text(one.get('appearance'))}")
    if who:
        lines += ["", "[인물]"] + who

    lines += ["", f"[장면 목록 — 전부 {len(scenes)}개다. 이 개수와 순서를 그대로 쓴다]"]
    for i, s in enumerate(scenes, 1):
        lines.append(f"{i}. {s}")
    return "\n".join(lines) + "\n"


def build_prompt(direction: dict, char: dict | None) -> str:
    path = PROMPT_DIR / "detail_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit(f"프롬프트가 비어 있습니다: {path}")
    # 입력은 **뒤**에 붙인다 — 모델은 뒤에 온 것을 더 세게 듣는다(compose 와 같다).
    return f"{text}\n\n---\n\n{input_block(direction, char)}"


# ------------------------------------------------------------------------ 파싱

def parse(text: str, count: int) -> dict:
    """응답(JSON) -> {"start": str, "scenes": [{"n", "what", "ends"}]}."""
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("디테일 결과가 JSON 객체가 아닙니다.")
    got = obj.get("장면")
    out = []
    for i, one in enumerate(got if isinstance(got, list) else [], 1):
        if not isinstance(one, dict):
            continue
        try:
            n = int(one.get("n", i))
        except (TypeError, ValueError):
            n = i
        out.append({"n": n, "what": _text(one.get("무엇")), "ends": _text(one.get("마무리"))})
    out.sort(key=lambda s: s["n"])
    return {"start": _text(obj.get("시작")), "scenes": out, "count": count}


def gate(link: dict, count: int) -> list[str]:
    """모자란 것을 돌려준다. **막지는 않는다** — 부르는 쪽이 정한다.

    이 단계가 실패해도 그림은 그릴 수 있다(예전처럼 앞 그림을 참조로 쓰는
    길로 돌아간다). 그래서 여기서 멈추지 않고, 무엇이 비었는지만 알린다.
    """
    bad = []
    scenes = link.get("scenes") or []
    if len(scenes) != count:
        bad.append(f"장면이 {len(scenes)}개입니다 ({count}개여야 합니다).")
    for one in scenes:
        if not one.get("ends"):
            bad.append(f"{one.get('n')}번 장면에 마무리가 없습니다.")
    if not link.get("start"):
        bad.append("이 화가 열리는 자리(시작)가 없습니다.")
    return bad


# ------------------------------------------------------------------- 읽어 쓰기

def opens_at(link: dict | None, scene_no: int) -> str:
    """장면 `scene_no` 가 **시작하는 자리**. 1번은 「시작」, 그 뒤는 앞 장면의 마무리.

    이것이 이 단계의 전부다 — 앞 장 그림을 못 봐도 어디서부터 그릴지 아는 것.
    """
    if not link or scene_no < 1:
        return ""
    if scene_no == 1:
        return _text(link.get("start"))
    for one in link.get("scenes") or []:
        if one.get("n") == scene_no - 1:
            return _text(one.get("ends"))
    return ""


def scene_of(link: dict | None, scene_no: int) -> dict:
    for one in (link or {}).get("scenes") or []:
        if one.get("n") == scene_no:
            return one
    return {}


def load(run_dir: Path) -> dict | None:
    got = read_json(run_dir / LINK_FILE)
    return got if isinstance(got, dict) and got.get("scenes") else None


def plan(run_dir: Path, direction: dict, char: dict | None = None,
         dry_run: bool = False, on_call=None, force: bool = False) -> dict | None:
    """장면 이음새를 정해 `scene_link.json` 에 쓴다. 이미 있으면 그것을 쓴다.

    **이미 있으면 다시 부르지 않는 것이 중요하다** — 장면을 동시에 그리는
    프로세스들이 저마다 이 단계를 부르면 이음새가 프로세스마다 달라져서,
    애초에 이 단계로 없애려던 어긋남이 그대로 돌아온다. 그래서 그리기 전에
    한 번(`run.py --scene-link`) 만들어 두고, 그리는 쪽은 읽기만 한다.
    """
    scenes = scenes_of(direction)
    if not scenes:
        return None
    if not force:
        got = load(run_dir)
        if got:
            log(f"[디테일] 이미 있습니다 -> {run_dir / LINK_FILE} (재사용)")
            return got

    prompt = build_prompt(direction, char)
    (run_dir / "detail_prompt.txt").write_text(prompt, encoding="utf-8")
    if dry_run:
        log(f"[디테일] 프롬프트만 썼습니다 -> {run_dir / 'detail_prompt.txt'}")
        return None

    call = llm.Call(STAGE)
    log(f"[디테일] {call.describe()} 로 장면 {len(scenes)}개의 이음새를 정합니다…")
    try:
        text, meta = call(prompt, temperature=0.4)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        if on_call:
            on_call(meta)
        log(f"[디테일] 실패 — {meta['error']}")
        return None

    (run_dir / "detail.txt").write_text(text, encoding="utf-8")
    if on_call:
        on_call(meta)
    try:
        link = parse(text, len(scenes))
    except Exception as exc:                                          # noqa: BLE001
        log(f"[디테일] 응답을 읽지 못했습니다 — {type(exc).__name__}: {exc} "
            f"(원문은 {run_dir / 'detail.txt'} 에 있습니다)")
        return None

    bad = gate(link, len(scenes))
    if bad:
        # 막지 않는다 — 이음새가 모자란 장면은 그리는 쪽이 예전처럼 장면 한
        # 줄만 보고 그린다. 다만 무엇이 빠졌는지는 남긴다.
        for one in bad:
            story.warn(f"[디테일] {one}")
        link["incomplete"] = bad
    write_json(run_dir / LINK_FILE, link)
    log(f"  -> {run_dir / LINK_FILE}")
    return link


# -------------------------------------------------------------------- 단독 실행

def run(run_dir: Path, dry_run: bool = False, force: bool = False) -> int:
    pick = read_json(run_dir / "pick.json") or {}
    directions = read_json(run_dir / "directions.json") or []
    direction = (next((d for d in directions if d.get("n") == pick.get("n")), None)
                 or (directions[0] if directions else None))
    if not direction:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
    plan(run_dir, direction, read_json(run_dir / "input.json"),
         dry_run=dry_run, force=force,
         on_call=lambda meta: runmeta.append_call(run_dir, meta))
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    p.add_argument("--force", action="store_true",
                   help="이미 있어도 다시 정한다 (그리는 중에는 쓰지 마세요 — "
                        "이미 그린 장과 이음새가 어긋납니다)")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    return run(run_dir, dry_run=args.dry_run, force=args.force)


if __name__ == "__main__":
    raise SystemExit(main())
