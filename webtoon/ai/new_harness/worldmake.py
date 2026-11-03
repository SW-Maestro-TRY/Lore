"""세계관 만들기 — 사용자가 적은 칸과 참고할 설정집으로 세계 하나(규칙·인물·사건 셋·사건마다 예시 주인공 둘)를 짓는다.

글 호출 한 번이다. 예시 주인공의 그림은 여기서 그리지 않는다 — 서버가 지은 이름·설명으로 캐릭터 만들기
(character.py)를 불러 그린다. 웹툰 만들기의 캐릭터와 같은 길이라 그림 규칙이 두 벌이 되지 않는다.

    python3 run.py --world-make input.json --out result.json [--dry-run]

input.json
    {"draft": {"name","genre","one","rule","cast":[{"name","role","desc"}],
               "events":[{"title","line","place","situation","spot","examples":[캐릭터 id]}]},
     "books": [설정집 사본…], "note": "", "previous": {앞서 지은 결과} | null}
"""
import json
from pathlib import Path

import llm
import lang as lang_mod
import run as R

STAGE = "WORLD_MAKE"
EVENTS = 3
EXAMPLES = 2


def _s(value) -> str:
    return str(value or "").strip()


def _draft_block(draft: dict) -> str:
    d = draft if isinstance(draft, dict) else {}
    cast = [c for c in d.get("cast") or [] if isinstance(c, dict) and (_s(c.get("name")) or _s(c.get("role")))]
    events = [e for e in d.get("events") or [] if isinstance(e, dict) and (_s(e.get("title")) or _s(e.get("situation")))]
    shown = {
        "name": _s(d.get("name")), "genre": _s(d.get("genre")), "one": _s(d.get("one")),
        "rules": _s(d.get("rule")), "spot": _s(d.get("spot")),
        "cast": [{"name": _s(c.get("name")), "role": _s(c.get("role")), "desc": _s(c.get("desc"))} for c in cast],
        "events": [{**{k: _s(e.get(k)) for k in ("title", "line", "place", "situation", "spot")},
                    "examples_kept": len([x for x in e.get("examples") or [] if _s(x)][:EXAMPLES])} for e in events],
    }
    return "[사용자가 적은 것]\n" + json.dumps(shown, ensure_ascii=False, indent=1) + "\n"


def _books_block(books) -> str:
    rows = []
    for b in books or []:
        if not isinstance(b, dict):
            continue
        head = f"## {_s(b.get('name')) or '설정집'}"
        body = [_s(b.get("fixed_settings"))]
        for e in b.get("entries") or []:
            if isinstance(e, dict) and _s(e.get("title")) and e.get("enabled") is not False:
                body.append(f"- {_s(e.get('title'))}: {_s(e.get('content') or e.get('text'))}")
        rows.append("\n".join([head, *[x for x in body if x]]))
    return "\n[참고할 설정집]\n" + ("\n\n".join(rows) if rows else "(없음)") + "\n"


def prompt(data: dict, lang: str = "ko") -> str:
    text = R.load_prompt("world/make_prompt").replace("{{LANGUAGE_LINE}}", lang_mod.instruction(lang))
    text += "\n\n" + _draft_block(data.get("draft") or {}) + _books_block(data.get("books"))
    note = _s(data.get("note"))
    if note:
        text += "\n[이번에 다시 만들며 바라는 것]\n" + note + "\n"
    prev = data.get("previous")
    if isinstance(prev, dict) and prev:
        keep = {k: prev.get(k) for k in ("name", "one", "cast", "events", "examples") if prev.get(k)}
        text += "\n[앞서 지은 것]\n" + json.dumps(keep, ensure_ascii=False, indent=1) + "\n"
    return text


def clean(raw: dict, draft: dict | None = None) -> dict:
    """모델 답을 서버가 받을 모양으로. 사용자가 적은 칸은 모델이 바꿨어도 사용자 것으로 되돌린다."""
    raw = raw if isinstance(raw, dict) else {}
    d = draft if isinstance(draft, dict) else {}
    rules = raw.get("rules")
    rules = [_s(r) for r in rules] if isinstance(rules, list) else [_s(rules)]
    user_rules = [line.strip() for line in _s(d.get("rule")).splitlines() if line.strip()]
    out = {
        "name": _s(d.get("name")) or _s(raw.get("name")),
        "genre": _s(d.get("genre")) or _s(raw.get("genre")),
        "one": _s(d.get("one")) or _s(raw.get("one")),
        "rules": user_rules or [r for r in rules if r],
        "spot": _s(d.get("spot")) or _s(raw.get("spot")),
        "cast": [{"name": _s(c.get("name")), "role": _s(c.get("role")), "desc": _s(c.get("desc"))}
                 for c in raw.get("cast") or [] if isinstance(c, dict) and _s(c.get("name"))],
        "events": [],
    }
    mine_cast = [c for c in d.get("cast") or [] if isinstance(c, dict) and _s(c.get("name"))]
    if mine_cast:
        known = {_s(c["name"]) for c in mine_cast}
        out["cast"] = [{"name": _s(c.get("name")), "role": _s(c.get("role")), "desc": _s(c.get("desc"))}
                       for c in mine_cast] + \
                      [c for c in out["cast"] if c["name"] not in known]
    mine_events = [e for e in d.get("events") or [] if isinstance(e, dict) and _s(e.get("situation"))]
    got = [e for e in raw.get("events") or [] if isinstance(e, dict) and _s(e.get("situation"))]
    for i in range(max(len(mine_events), min(EVENTS, len(got)))):
        e = got[i] if i < len(got) else {}
        m = mine_events[i] if i < len(mine_events) else {}
        situation = _s(m.get("situation")) or _s(e.get("situation"))
        out["events"].append({
            "title": _s(m.get("title")) or _s(e.get("title")),
            "line": _s(m.get("line")) or _s(e.get("line")) or situation.splitlines()[0],
            "place": _s(m.get("place")) or _s(e.get("place")),
            "situation": situation,
            "spot": _s(m.get("spot")) or _s(e.get("spot")),
            "examples": _examples(e.get("examples")),
        })
    return out


def _examples(raw) -> list[dict]:
    return [{"name": _s(x.get("name")), "description": _s(x.get("description"))}
            for x in raw or [] if isinstance(x, dict) and _s(x.get("name")) and _s(x.get("description"))][:EXAMPLES]


def missing(world: dict, draft: dict | None = None) -> list[str]:
    """다 못 지은 것. 예시 주인공은 사용자가 이미 고른 수를 빼고 사건마다 모자라면 못 지은 것이다."""
    bad = []
    if not world["name"]:
        bad.append("세계 이름")
    if not world["events"]:
        bad.append("사건")
    mine = [e for e in (draft or {}).get("events") or [] if isinstance(e, dict) and _s(e.get("situation"))]
    for i, e in enumerate(world["events"]):
        kept = len([x for x in (mine[i].get("examples") or []) if _s(x)]) if i < len(mine) else 0
        if len(e["examples"]) + kept < EXAMPLES:
            bad.append(f"{i + 1}번 사건의 예시 주인공")
    return bad


def make(input_path: Path, out_path: Path, dry_run: bool = False, lang: str = "ko") -> dict | None:
    data = json.loads(Path(input_path).read_text(encoding="utf-8"))
    out_path = Path(out_path)
    out_dir = out_path.parent
    text = prompt(data, lang)
    R.write_text(out_dir / "world_make_prompt.txt", text)
    if dry_run:
        return None
    call = llm.Call(STAGE)
    R.log(f"[세계] {call.describe()} 로 세계를 짓습니다…")
    try:
        answer, meta = call(text, temperature=0.9)
    except BaseException as exc:                                    # noqa: BLE001 — 실패도 원가 장부에 남긴다
        R.record_error(out_dir, STAGE, call.provider, call.model, exc)
        raise
    meta["stage"] = STAGE
    R.record(out_dir, meta)
    R.write_text(out_dir / "world_make.txt", answer)
    t = answer[answer.find("{"):answer.rfind("}") + 1]
    world = clean(json.loads(t) if t else {}, data.get("draft"))
    bad = missing(world, data.get("draft"))
    if bad:
        raise SystemExit("세계를 다 짓지 못했습니다: " + ", ".join(bad))
    R.write_json(out_path, world)
    return world
