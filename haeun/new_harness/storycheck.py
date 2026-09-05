#!/usr/bin/env python3
"""이야기 후보 검수 — **고르기 전에 구멍을 보여준다.**

## 왜 있는가

지금 흐름은 이야기 후보 4개를 만들고(`stage_story`), 사람이 하나를 고르면
곧장 그림으로 간다. 그 사이에 아무 검사도 없다 — 후보를 4개 읽었는지만
센다(`run.parse_directions`).

`stage_review`(구체화 검수)가 그 자리를 하던 때가 있었는데, 그것은
`detail.json` 을 읽는다. 2026-09-02부터 구체화 단계를 안 도니 파일이 안
생기고, 그래서 그 검수는 지금 만들어지는 run 에서는 **부를 방법 자체가
없다.** 이건 그 검수를 재료가 실제로 있는 자리(방향 후보)로 옮겨 놓은
것이다.

## 왜 그리기 전인가

장면 한 줄이 그림 한 장이 된다. 컷을 나누고 대사를 쓰는 단계가 사이에
없으니, 후보에 난 구멍은 뒤에서 안 메워진다. 그리고 나서 알면 이미 장당
그림값을 다 치른 뒤다 — 여기서 잡으면 글 호출 한 번 값이다.

## 무엇을 보는가

"처음 읽는 독자가 주어진 것만으로 따라갈 수 있는가" 하나다. 어느 후보가
더 재미있는지는 **안 본다** — 그건 사람이 고르는 자리이고, 모델이 순위를
매기면 사람이 그 순위를 읽고 고르게 된다.

여기에 둘이 더 붙는다.

**한 줄이 그림 한 장이 되는가** — 한 줄에 사건이 여럿 들어 있거나 화면에
보이는 것이 없으면 그리면서 무너진다(`kind` 가 `한장`).

**마지막에 다음 화가 궁금해지는가** — 1화 마지막에 관계·상황·목표 중
하나가 달라져 있고, 그 변화 때문에 질문 하나가 남는가(`kind` 가 `마무리`).
이건 취향이 아니다. 어떤 결말이 좋은지가 아니라 **달라진 것과 남는 질문이
있는지를 세는 것**이라 셀 수 있다. 그리고 이 기준은 새로 만든 것이 아니라
`story_prompt` 의 「10. 마지막 장면에는 다음을 기대하게 만드는 변화가
있어야 한다」를 그대로 가져온 것이다 — 만들 때 요구한 것을 볼 때도 그대로
쓴다. 다른 잣대를 대면 검수가 후보를 떨어뜨리는 것이 아니라 후보와 다른
이야기를 요구하게 된다.

## 어떻게 쓰는가

이야기 단계가 후보를 다 쓴 뒤에 자동으로 한 번 부른다
(`NH_STORY_REVIEW=0` 이면 안 부른다). **아무것도 막지 않는다** — 판정은
사람이 고르는 화면에 같이 붙어 보일 뿐이고, critical 이 나와도 그 후보를
못 고르게 하지 않는다.

    python3 storycheck.py --run-id <id>            # 이미 만든 후보를 검수만
    python3 storycheck.py --run-id <id> --dry-run  # 프롬프트만 (0원)

결과는 `story_review.json` 에 쌓인다. 원문(`story_review.txt`)은 파싱
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
STAGE = "STORY_REVIEW"

SEVERITY = ("critical", "major", "minor")
KINDS = ("인과", "지식", "신규", "연속성", "인물", "한장", "마무리")

# `ending` 에서 "비었다" 를 뜻하는 값. 모델이 프롬프트가 시킨 대로 적으면
# 이 낱말들이 온다 — 코드가 이것을 보고 직접 문제를 세운다(아래 _ending).
EMPTY = ("없음", "없다", "none", "-", "")
ONLY_LAST = "마지막 줄뿐"

# 모델이 `kind` 에 **칸 이름**을 적어 넣는 일이 실측으로 나왔다(`why`).
# 프롬프트가 "issues 는 위 칸에서 나온다" 라고 시키니, 어느 칸에서 나왔는지를
# 종류라고 쓴 것이다 — 틀린 말은 아니지만 이 값은 화면에 라벨로 그대로
# 붙는 자리라, 한글 목록으로 되돌린다. 모르는 낱말을 통째로 버리지는
# 않는다(pagecheck 과 같은 규칙) — 아는 것만 옮긴다.
FIELD_KIND = {"why": "인과", "new": "신규", "gap": "연속성", "knows": "지식",
              "events": "한장", "shows": "한장", "ending": "마무리",
              "conflicts": "연속성", "actions": "인과"}


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
    """호출 하나를 run.py 의 meta.json 에 남긴다 — 다른 호출과 같은 자리.

    검수도 돈이 나가는 호출이다. 단독으로 돌렸을 때만 기록이 빠지면,
    나중에 이 run 에 얼마가 들었는지가 어디서 돌렸느냐에 따라 달라진다
    (pagecheck.record 와 같다).
    """
    path = run_dir / "meta.json"
    meta = read_json(path) or {"run_id": run_dir.name, "calls": []}
    meta["calls"].append(call_meta)
    write_json(path, meta)


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


def _strs(x) -> list[str]:
    return [_text(v) for v in x if _text(v)] if isinstance(x, list) else []


def _int(x, default: int = 0) -> int:
    try:
        return int(x)
    except (TypeError, ValueError):
        return default


# ------------------------------------------------------------------ 켜고 끄기

def enabled() -> bool:
    """기본은 **켜짐.** 끄려면 `.env` 에 `NH_STORY_REVIEW=0`.

    켜 두는 쪽이 기본인 이유는 pagecheck 과 같다 — 기본이 꺼져 있으면
    켜는 것을 잊은 판이 그대로 나간다. 값은 글 호출 한 번이고, 잡는 것이
    그림 여섯 장보다 앞이라 되돌리는 값이 가장 싼 자리다.
    """
    return _text(llm.env("NH_STORY_REVIEW") or "").lower() not in ("0", "off", "false", "no")


# -------------------------------------------------------------------- 프롬프트

def character_block(char: dict | None) -> str:
    """캐릭터 — "설명에 있는데 이야기에서 한 번도 안 쓰였다" 를 볼 기준."""
    char = char or {}
    lines = ["## 캐릭터 정보", ""]
    if _text(char.get("name")):
        lines.append(f"이름: {_text(char['name'])}")
    if _text(char.get("description")):
        lines.append(f"설명: {_text(char['description'])}")
    if _text(char.get("genre")):
        lines.append(f"장르: {_text(char['genre'])}")
    for k, v in (char.get("fields") or {}).items():
        if _text(v):
            lines.append(f"- {k}: {_text(v)}")
    return "\n".join(lines)


def direction_block(directions: list[dict]) -> str:
    """후보 전부를 한 번에. 호출을 후보마다 나누지 않는 이유는, 넷이
    같은 캐릭터에서 나온 것이라 앞 후보를 읽은 것이 뒤 후보를 읽는 데
    도움이 되고(같은 설정을 다시 설명 안 해도 된다), 호출이 하나면 값도
    하나이기 때문이다.
    """
    lines = ["## 이야기 후보 — 이번에 읽을 것", ""]
    for d in directions:
        n = _int(d.get("n"))
        lines.append(f"### 후보 {n}. {_text(d.get('title'))}"
                     + (f" [{_text(d.get('genre'))}]" if _text(d.get("genre")) else ""))
        if _text(d.get("plot")):
            lines += ["", "[줄거리]", _text(d["plot"])]
        cast = [c for c in (d.get("cast") or []) if isinstance(c, dict) and _text(c.get("name"))]
        if cast:
            lines += ["", "[등장인물]"]
            for c in cast:
                who = _text(c.get("name"))
                look = _text(c.get("appearance"))
                lines.append(f"- {who}" + (f" — {look}" if look else ""))
        scenes = _strs(d.get("scenes"))
        if scenes:
            lines += ["", "[장면 목록 — 한 줄이 그림 한 장이 된다]"]
            for i, s in enumerate(scenes, 1):
                lines.append(f"{i}. {s}")
        hidden = _strs(d.get("hidden"))
        if hidden:
            lines += ["", "[밝히지 않은 것 — 안 밝혀진 것은 문제가 아니다]"]
            lines += [f"- {h}" for h in hidden]
        lines.append("")
    return "\n".join(lines)


def build_prompt(char: dict | None, directions: list[dict]) -> str:
    path = PROMPT_DIR / "story_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    return (path.read_text(encoding="utf-8")
            .replace("{character}", character_block(char))
            .replace("{directions}", direction_block(directions)))


# ------------------------------------------------------------------------ 파싱

def _ending(one: dict, last_scene: int) -> tuple[dict, list[dict]]:
    """`ending` 칸 -> (읽은 값, 코드가 세운 문제).

    모델에게 "이건 문제니까 issues 에도 적어라" 를 시켜 놓았지만, 그것을
    지켰는지는 **여기서 확인할 수 있다.** 안 옮겼으면 코드가 옮긴다 —
    조용히 흘려보내면 마무리가 빈 후보가 「걸리는 곳 없음」 으로 나간다.

    같은 것을 두 번 세지 않으려고, 이미 `마무리` 문제가 적혀 있으면
    코드는 더하지 않는다.
    """
    raw = one.get("ending")
    if not isinstance(raw, dict):
        # `ending` 칸이 통째로 없다. **안 물어본 것을 못 지켰다고 셀 수는
        # 없다** — 이 칸을 요구하기 전에 만든 판정 파일이 그렇다. 빈 값을
        # "없음" 으로 읽으면 옛 판정이 전부 주의로 뒤집힌다.
        return {"changed": "", "question": "", "from": ""}, []
    ending = {"changed": _text(raw.get("changed")),
              "question": _text(raw.get("question")),
              "from": _text(raw.get("from"))}

    said = [i for i in (one.get("issues") or [])
            if isinstance(i, dict) and _text(i.get("kind")) == "마무리"]
    if said:
        return ending, []

    made = []
    def add(sev: str, what: str) -> None:
        made.append({"scene": last_scene, "kind": "마무리",
                     "severity": sev, "what": what, "where": ""})

    if ending["changed"].lower() in EMPTY:
        add("critical", "마지막 장면이 끝나도 관계·상황·목표가 처음 그대로다 — "
                        "달라진 것이 없다")
    if ending["question"].lower() in EMPTY:
        add("critical", "다 읽고 나서 다음 화에 무엇이 궁금한지 댈 것이 없다")
    elif ending["from"] == ONLY_LAST:
        add("major", "다음이 궁금해지는 것이 마지막 줄에만 붙어 있다 — "
                     "앞에 깔린 것이 없다")
    return ending, made


def parse(text: str, expect: list[int] | None = None) -> dict:
    """검수 응답(JSON) -> 후보별 판정.

    `verdict` 는 모델에게 안 묻는다. **무게에서 코드가 센다** — pagecheck 의
    parse 와 같은 이유다. 규칙을 모델이 지켰는지는 여기서 확인할 수 있고,
    어긋나면 센 쪽이 맞다.

    `expect` 를 주면 그 번호들이 전부 있는지 본다. 안 온 후보는 판정
    자체가 없는 채로(`verdict: "없음"`) 남긴다 — 조용히 통과로 두면 검수가
    있는 것과 없는 것이 같아진다.
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("검수 결과가 JSON 객체가 아닙니다.")

    got: dict[int, dict] = {}
    for one in obj.get("candidates") or []:
        if not isinstance(one, dict):
            continue
        n = _int(one.get("n"))
        if not n:
            continue
        issues = []
        for i in one.get("issues") or []:
            if not isinstance(i, dict):
                continue
            sev = _text(i.get("severity")).lower()
            kind = _text(i.get("kind"))
            kind = FIELD_KIND.get(kind.lower(), kind)
            issues.append({"scene": _int(i.get("scene")),
                           "kind": kind if kind in KINDS else (kind or "인과"),
                           "severity": sev if sev in SEVERITY else "major",
                           "what": _text(i.get("what")),
                           "where": _text(i.get("where"))})
        # 마무리는 `ending` 칸에서 코드가 직접 센다 — 모델이 그것을 issues 로
        # 옮겼는지에 기대지 않는다(pagecheck 이 flow 를 그렇게 다루는 것과 같다).
        last_scene = max((i["scene"] for i in issues), default=0)
        for sc in (one.get("scenes") or []):
            if isinstance(sc, dict):
                last_scene = max(last_scene, _int(sc.get("id")))
        ending, forced = _ending(one, last_scene)
        issues += forced

        issues.sort(key=lambda i: (SEVERITY.index(i["severity"]), i["scene"]))
        counts = {s: sum(1 for i in issues if i["severity"] == s) for s in SEVERITY}
        got[n] = {"n": n,
                  "verdict": "주의" if counts["critical"] else "통과",
                  "read_as": _text(one.get("read_as")),
                  "ending": ending,
                  "counts": counts,
                  "issues": issues}

    out = []
    for n in (expect if expect else sorted(got)):
        out.append(got.get(n) or {"n": n, "verdict": "없음", "read_as": "",
                                  "ending": {"changed": "", "question": "", "from": ""},
                                  "counts": {s: 0 for s in SEVERITY}, "issues": []})
    return {"candidates": out}


def summary(review: dict) -> str:
    parts = []
    for c in review.get("candidates") or []:
        n = c["counts"]
        parts.append(f"{c['n']}번 {c['verdict']}"
                     + (f"(c{n['critical']}·m{n['major']}·n{n['minor']})"
                        if any(n.values()) else ""))
    return " · ".join(parts) if parts else "판정 없음"


# -------------------------------------------------------------------- 호출 한 번

def review_directions(run_dir: Path, char: dict | None, directions: list[dict],
                      *, dry_run: bool = False) -> tuple[dict | None, dict | None]:
    """후보 전부를 한 번에 검수한다. -> (판정, 호출기록).

    호출이 실패해도 **예외를 밖으로 안 던진다.** 후보는 이미 만들어서 값을
    치렀다 — 검수가 안 됐다고 그 후보를 잃으면 안 된다. 대신 기록에 사유를
    남기고, 판정 파일은 안 쓴다(있는데 빈 것과 아예 없는 것은 다르다).
    """
    directions = [d for d in (directions or []) if isinstance(d, dict)]
    if not directions:
        return None, None

    prompt = build_prompt(char, directions)
    write_text(run_dir / "story_review_prompt.txt", prompt)
    if dry_run:
        log(f"[이야기 검수] 프롬프트만 썼습니다 -> {run_dir / 'story_review_prompt.txt'}")
        return None, None

    call = llm.Call(STAGE)
    log(f"[이야기 검수] {call.describe()} 로 후보 {len(directions)}개를 독자의 눈으로 읽습니다…")
    try:
        # 검수는 발상이 아니라 대조다. 온도를 낮춘다 (stage_review 와 같다).
        text, meta = call(prompt, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [이야기 검수] 실패 — {meta['error']} (후보는 그대로 둡니다)")
        return None, meta

    write_text(run_dir / "story_review.txt", text)
    try:
        review = parse(text, expect=[_int(d.get("n")) for d in directions if _int(d.get("n"))])
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [이야기 검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    write_json(run_dir / "story_review.json", review)
    log(f"  [이야기 검수] {summary(review)}")
    for c in review["candidates"]:
        q = (c.get("ending") or {}).get("question")
        if q:
            log(f"    {c['n']}번 마지막에 남는 질문: {q}")
    for c in review["candidates"]:
        for one in c["issues"]:
            if one["severity"] in ("critical", "major"):
                log(f"    - {c['n']}번 장면 {one['scene']} [{one['severity']}] "
                    f"{one['kind']}: {one['what']}")
    return review, meta


# -------------------------------------------------------------------- 단독 실행

def review_run(run_dir: Path, dry_run: bool = False, on_call=None) -> dict | None:
    directions = read_json(run_dir / "directions.json") or []
    if not directions:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
    char = read_json(run_dir / "input.json")
    review, meta = review_directions(run_dir, char, directions, dry_run=dry_run)
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
