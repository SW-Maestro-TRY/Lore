#!/usr/bin/env python3
"""후보 비교 검수 — **넷이 서로 다른 이야기인가.**

## 왜 있는가

이야기 단계는 후보 4개를 만들고, 사람이 그중 하나를 고른다. 그래서 이
단계의 성패는 후보 하나가 잘 쓰였는가가 아니라 **넷이 서로 다른가**에
걸려 있다 — 넷이 비슷하면 고를 것이 하나뿐인 화면이 된다.

그런데 지금까지 그것을 보는 자가 없었다. `storycheck` 는 후보 하나하나를
따로 읽고(`story_review_prompt` 에 "후보끼리 견주지 마라" 라고 적혀 있다),
`run.py` 는 방향마다 다른 값을 박아 넣기만 할 뿐 그 값이 실제로 다른
이야기를 만들었는지는 안 본다.

그 결과 **축을 고쳐도 좋아졌는지 확인할 방법이 눈으로 읽는 것뿐이었다.**
2026-09-15 축 On/Off 비교도 판정 파일만 보면 양쪽 다 전부 "통과" 라 차이가
안 보인다. 이 파일은 그 자리를 채운다 — 축·구조·엔진을 바꿔 가며 돌릴 때
**바꾸기 전과 후를 견줄 숫자**를 남기는 것이 목적이다.

## 무엇을 세는가

후보를 둘씩 짝지어(넷이면 여섯 짝) 다섯 칸을 본다 — 무대 · 인물 · 갈등 ·
방식 · 도착. **새로 만든 잣대가 아니다.** 앞의 넷은 `story_prompt` 의
「2단계. 그중 서로 완전히 다른 판 4개를 고른다 — 무대, 주변 인물, 갈등의
종류, 이야기가 굴러가는 방식이 모두 달라야 한다」를 그대로 가져왔고,
`도착` 은 `run.py` 의 방향별 지시문(「넷의 도착점이 서로 비슷해도
실패다」)에서 가져왔다. 만들 때 요구한 것을 볼 때도 그대로 쓴다.

칸마다 `같다/다르다` 둘 중 하나만 묻는다. 비슷한 정도를 점수로 매기게
하면 "2점" 이 무슨 뜻인지 아무도 모르는 숫자가 쌓인다. **셀 수 있는
것만 센다** — 여섯 짝 중 몇 짝이 겹쳤는가, 그리고 어느 칸이 안 갈렸는가.

칸별 숫자가 실은 더 쓸모 있다. "인물 5/6" 이 나오면 관계 축이 방향마다
달라도 이야기에서는 같은 자리를 만들고 있다는 뜻이라, 어느 축을 손볼지가
바로 나온다.

## 아무것도 막지 않는다

판정은 파일로만 남는다. 후보를 떨어뜨리지도, 다시 뽑지도 않는다 —
이것은 게이트가 아니라 **자**다. 그리고 `run.py` 에도 안 붙였다: 제품
run 마다 글 호출 한 번이 더 붙을 이유가 없고, 축을 실험하는 자리에서만
필요하다. 필요해지면 그때 붙인다.

## 어떻게 쓰는가

    python3 storydiff.py --run-id <id>              # 이미 만든 후보를 견준다
    python3 storydiff.py --run-id <id> --dry-run    # 프롬프트만 (0원)
    python3 storydiff.py --run-id <a> --run-id <b>  # 두 run 을 차례로, 한 줄씩

결과는 run 마다 `story_diff.json` 에 쌓인다. 원문(`story_diff.txt`)은
파싱 **전에** 먼저 쓴다 — 파싱이 죽어도 그 호출에 쓴 돈이 사라지지 않는다
(`storycheck` 과 같다).
"""

from __future__ import annotations

import argparse
import itertools
import os
from pathlib import Path

import llm
import storycheck as sc
from llm import story

HERE = Path(__file__).resolve().parent
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))
PROMPT_DIR = HERE / "prompt"
STAGE = "STORY_DIFF"

# 보는 칸. 앞의 넷은 story_prompt 2단계, 도착은 run.py 의 방향별 지시문에서
# 그대로 가져온 것이다 (위 문서 참고).
DIMS = ("stage", "cast", "conflict", "mode", "arrival")
DIM_LABEL = {"stage": "무대", "cast": "인물", "conflict": "갈등",
             "mode": "방식", "arrival": "도착"}

# 다섯 칸 중 몇 칸이 같으면 그 짝을 "겹쳤다" 고 세는가.
#
# 셋으로 둔 이유: 무대·인물·갈등·방식·도착 중 **과반이 같으면** 나란히 놓고
# 읽는 사람에게 같은 이야기로 보인다. 둘로 내리면 서로 다른 이야기인데 톤만
# 비슷한 짝까지 겹침으로 세고, 넷으로 올리면 거의 안 걸려서 자가 눈금 없는
# 막대가 된다. 바꾸면 예전 판정과 견줄 수 없게 되니, 바꿀 때는 예전 run 을
# 다시 돌려서 같이 옮긴다.
OVERLAP_AT = 3


def log(msg: str) -> None:
    print(msg, flush=True)


def pairs_of(ns: list[int]) -> list[tuple[int, int]]:
    """짝 목록. 넷이면 여섯."""
    return list(itertools.combinations(sorted(set(ns)), 2))


# -------------------------------------------------------------------- 프롬프트

def build_prompt(char: dict | None, directions: list[dict]) -> str:
    path = PROMPT_DIR / "story_diff_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    # 후보를 적는 방식은 storycheck 과 같은 것을 쓴다. 두 검수가 서로 다른
    # 것을 읽고 있으면 판정이 어긋났을 때 어느 쪽이 덜 읽었는지부터 봐야 한다.
    return (path.read_text(encoding="utf-8")
            + "\n\n---\n\n" + sc.character_block(char)
            + "\n\n" + sc.direction_block(directions))


# ------------------------------------------------------------------------ 파싱

def parse(text: str, expect: list[tuple[int, int]] | None = None) -> dict:
    """비교 응답(JSON) -> 짝별 판정.

    `겹침` 은 모델에게 안 묻는다 — **칸에서 코드가 센다**(storycheck 의
    parse 와 같은 이유). 모델이 칸을 채우면 그 다음은 셈이고, 셈은 코드가
    하는 쪽이 매번 같다.

    `expect` 를 주면 그 짝들이 전부 왔는지 본다. 안 온 짝은 판정 없이
    (`판정: "없음"`) 남기고 **겹침에도 안 센다** — 조용히 "안 겹침" 으로
    두면 응답이 반쯤 온 판이 가장 좋은 판으로 보인다.
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("비교 결과가 JSON 객체가 아닙니다.")

    got: dict[tuple[int, int], dict] = {}
    for one in obj.get("pairs") or []:
        if not isinstance(one, dict):
            continue
        a, b = sc._int(one.get("a")), sc._int(one.get("b"))
        if not a or not b or a == b:
            continue
        key = (min(a, b), max(a, b))
        dims = {}
        for dim in DIMS:
            item = one.get(dim)
            if not isinstance(item, dict):
                continue
            same = item.get("same")
            if isinstance(same, str):
                same = same.strip().lower() in ("true", "yes", "같다", "1")
            if same is None:
                continue
            dims[dim] = {"same": bool(same), "why": sc._text(item.get("why"))}
        same_count = sum(1 for d in dims.values() if d["same"])
        got[key] = {"a": key[0], "b": key[1],
                    "판정": "겹침" if same_count >= OVERLAP_AT else "다름",
                    "같은_칸": same_count,
                    "칸": dims}

    out = []
    for key in (expect if expect else sorted(got)):
        key = (min(key), max(key))
        out.append(got.get(key) or {"a": key[0], "b": key[1], "판정": "없음",
                                    "같은_칸": 0, "칸": {}})

    judged = [p for p in out if p["판정"] != "없음"]
    by_dim = {dim: sum(1 for p in judged if (p["칸"].get(dim) or {}).get("same"))
              for dim in DIMS}
    closest = obj.get("closest") if isinstance(obj.get("closest"), dict) else {}
    return {"pairs": out,
            "겹친_짝": sum(1 for p in judged if p["판정"] == "겹침"),
            "판정된_짝": len(judged),
            "칸별_같음": by_dim,
            "가장_비슷한_짝": {"a": sc._int(closest.get("a")),
                          "b": sc._int(closest.get("b")),
                          "why": sc._text(closest.get("why"))}}


def summary(diff: dict) -> str:
    """'6짝 중 2짝 겹침 · 무대1 인물4 갈등2 방식3 도착1' — 판을 견주는 한 줄.

    이 한 줄이 이 파일의 산출물이다. 축을 고치기 전과 후에 같은 자리에서
    이 줄을 뽑아 견준다.
    """
    dims = " ".join(f"{DIM_LABEL[d]}{diff['칸별_같음'][d]}" for d in DIMS)
    return f"{diff['판정된_짝']}짝 중 {diff['겹친_짝']}짝 겹침 · {dims}"


# -------------------------------------------------------------------- 호출 한 번

def diff_directions(run_dir: Path, char: dict | None, directions: list[dict],
                    *, dry_run: bool = False) -> tuple[dict | None, dict | None]:
    """후보 전부를 한 번에 견준다. -> (판정, 호출기록).

    호출이 실패해도 **예외를 밖으로 안 던진다**(storycheck 과 같다) — 후보는
    이미 값을 치르고 만든 것이라, 자가 없다고 그것을 잃으면 안 된다.
    """
    directions = [d for d in (directions or []) if isinstance(d, dict)]
    ns = [sc._int(d.get("n")) for d in directions if sc._int(d.get("n"))]
    if len(ns) < 2:
        log("  [후보 비교] 견줄 후보가 둘이 안 됩니다 — 건너뜁니다")
        return None, None

    # 본문 없이 제목만 보내면 모델은 "못 읽겠다" 고 하지 않고 그럴듯한
    # 이야기를 지어내 견준다. 지어낸 넷은 언제나 서로 다르다 — 자가 없는
    # 것보다 나쁘다(2026-09-19 에 storycheck 이 실제로 그렇게 통과시켰다).
    empty = [d for d in directions
             if not (sc._text(d.get("body")) or sc._text(d.get("plot"))
                     or sc._strs(d.get("scenes")))]
    if empty:
        raise SystemExit(
            f"이야기 후보 {len(empty)}개에 본문이 없습니다 "
            f"(후보 {', '.join(str(sc._int(d.get('n'))) for d in empty)}). "
            "견줄 것이 없어 멈춥니다 — story 단계 산출물을 확인하세요.")

    prompt = build_prompt(char, directions)
    sc.write_text(run_dir / "story_diff_prompt.txt", prompt)
    if dry_run:
        log(f"[후보 비교] 프롬프트만 썼습니다 -> {run_dir / 'story_diff_prompt.txt'}")
        return None, None

    call = llm.Call(STAGE)
    log(f"[후보 비교] {call.describe()} 로 후보 {len(ns)}개를 "
        f"{len(pairs_of(ns))}짝으로 견줍니다…")
    try:
        # 발상이 아니라 대조다. 온도를 낮춘다 (storycheck 과 같다).
        text, meta = call(prompt, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [후보 비교] 실패 — {meta['error']} (후보는 그대로 둡니다)")
        return None, meta

    sc.write_text(run_dir / "story_diff.txt", text)
    try:
        diff = parse(text, expect=pairs_of(ns))
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [후보 비교] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    sc.write_json(run_dir / "story_diff.json", diff)
    log(f"  [후보 비교] {summary(diff)}")
    for p in diff["pairs"]:
        if p["판정"] == "겹침":
            same = [DIM_LABEL[d] for d in DIMS if (p["칸"].get(d) or {}).get("same")]
            log(f"    {p['a']}·{p['b']}번이 겹칩니다 — {' '.join(same)}")
            for d in DIMS:
                cell = p["칸"].get(d) or {}
                if cell.get("same") and cell.get("why"):
                    log(f"      {DIM_LABEL[d]}: {cell['why']}")
    return diff, meta


# -------------------------------------------------------------------- 단독 실행

def diff_run(run_dir: Path, dry_run: bool = False, on_call=None) -> dict | None:
    directions = sc.read_json(run_dir / "directions.json") or []
    if not directions:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
    char = sc.read_json(run_dir / "input.json")
    diff, meta = diff_directions(run_dir, char, directions, dry_run=dry_run)
    if meta and on_call:
        on_call(meta)
    return diff


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True, action="append",
                   help="견줄 run. 여러 번 주면 차례로 돌리고 마지막에 한 줄씩 모아 보여준다")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)

    lines = []
    for run_id in args.run_id:
        run_dir = RUNS_DIR / run_id
        if not run_dir.exists():
            raise SystemExit(f"run 이 없습니다: {run_dir}")
        diff = diff_run(run_dir, dry_run=args.dry_run,
                        on_call=lambda meta, d=run_dir: sc.record(d, meta))
        if diff:
            lines.append(f"{run_id}  {summary(diff)}")
    # 판을 견주려고 만든 것이라, 여럿을 돌렸으면 마지막에 나란히 보여준다.
    if len(lines) > 1:
        log("")
        for line in lines:
            log(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
