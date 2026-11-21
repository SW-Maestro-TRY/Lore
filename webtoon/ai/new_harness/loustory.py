"""편집실의 루 — 그림 그리기 전에 이번 화 본문을 읽고 고칠 곳을 찾는다(글 검토). 고르면 그 문장만 바꾼다.

review — 이번 화 본문 · 정해 둔 이번 화 일 · 앞 화 끝 · 지난 사건 기록 · 지금 인물 상태 · 인물 설정을 보고 목록을 낸다.
         문장 하나로 고칠 것은 본문에서 그대로 옮긴 문장과 바꿀 글을 같이 낸다(옮긴 문장이 본문에 없으면 버린다).
fix    — 고른 문장들을 본문에서 바꾼다. 바꾸기 전 판을 남긴다. 이미 장면으로 나눈 화면 장면을 다시 나눠야 한다고 알린다.
화 전체를 다시 쓰는 것은 기존 다시 쓰기(--own-restory · 이어 쓰기)에 검토의 note 를 메모로 넘긴다.
"""
import json
import re
import shutil
import sys
from pathlib import Path

import llm
import lou
import lousheet
from pagecheck import record, write_json

KINDS = "listing passive_hero no_reaction unknown_name old_reference repeat voice plan_skip"
BODY_CHARS = 24000


def _chosen(run):
    """directions.json 과 그 안에서 고른 방향의 자리. 본문은 그 방향의 body 다."""
    path = run / "directions.json"
    lou.require(path.exists())
    directions = lou.read(path)
    pick = lou.read(run / "pick.json") if (run / "pick.json").exists() else {}
    at = next((i for i, d in enumerate(directions) if d.get("n") == pick.get("n")), 0)
    lou.require(directions and isinstance(directions[at].get("body"), str) and directions[at]["body"].strip())
    return path, directions, at


def material(run):
    _path, directions, at = _chosen(run)
    body = directions[at]["body"][:BODY_CHARS]
    cont = lou.read(run / "continuation.json") if (run / "continuation.json").exists() else {}
    inp = lou.read(run / "input.json") if (run / "input.json").exists() else {}
    book = inp.get("lorebook") if isinstance(inp.get("lorebook"), dict) else {}
    progress = [q for q in book.get("progress") or [] if isinstance(q, dict)]
    events = sorted((q for q in progress if q.get("kind") == "사건"), key=lambda q: q.get("ep") or 0, reverse=True)[:6]
    latest = {}
    for q in progress:
        if q.get("kind") in ("관계", "몸", "미해결", "지금", "겉모습") and str(q.get("text") or "").strip():
            key = (q.get("kind"), str(q.get("title") or ""))
            if key not in latest or (q.get("ep") or 0) >= (latest[key].get("ep") or 0):
                latest[key] = q
    state = "\n".join(f"- [{k}] {t}: {str(q['text'])[:300]}" for (k, t), q in latest.items())[:5000]
    return dict(body=body, plan=str(cont.get("request") or "")[:4000],
                previous=str(cont.get("source_body") or "")[-3000:], episode=cont.get("episode_number"),
                events=[dict(ep=q.get("ep"), text=str(q.get("text") or "")[:600]) for q in events], state=state,
                cast=[{k: c.get(k) for k in ("name", "role", "look", "voice")} for c in lousheet.cast_of(run)])


def clean(data, body):
    lou.require(isinstance(data.get("items"), list))
    out = []
    for it in data["items"]:
        if not isinstance(it, dict) or not lou.string(it, "what"):
            continue
        fix = it.get("fix") if isinstance(it.get("fix"), dict) else {}
        quote = lou.string(it, "quote")
        kind = fix.get("type") if fix.get("type") in ("sentence", "episode") else "episode"
        if kind == "sentence" and not (quote and body.count(quote) == 1 and lou.string(fix, "replace")):
            if not lou.string(fix, "note"):
                continue                              # 본문에 없는 문장을 바꾸자고 하면 버린다
            kind = "episode"
        out.append(dict(id=f"t{len(out) + 1}", kind=lou.choice(it.get("kind"), KINDS),
                        sev=it.get("sev") if it.get("sev") in ("P1", "P2") else "P2",
                        quote=quote if quote and quote in body else "", what=lou.string(it, "what"),
                        fix=dict(type=kind, replace=lou.string(fix, "replace") if kind == "sentence" else "",
                                 note=lou.string(fix, "note") if kind == "episode" else "")))
    return out


def review(run, request, output, dry=False):
    req = lou.read(request)
    lou.require(run.is_dir())
    context = dict(lang=req.get("lang") or "ko", **material(run))
    prompt = (lou.PROMPTS / "story_check_prompt").read_text(encoding="utf-8")
    prompt += "\n" + json.dumps(context, ensure_ascii=False)
    if dry:
        (run / "lou").mkdir(exist_ok=True)
        (run / "lou" / "story_prompt.txt").write_text(prompt, encoding="utf-8")
        return
    call = llm.Call("LOU_REVIEW")
    for _ in range(2):
        raw, meta = call(prompt, temperature=.2)
        record(run, meta)
        try:
            data = lou.unwrap(raw)
            lou.require(isinstance(data, dict) and lou.string(data, "reply"))
            result = dict(reply=data["reply"].strip(), items=clean(data, context["body"]))
            break
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            print(f"[루] 글 검토 답을 못 씀({type(exc).__name__}): {raw[:400]!r}", file=sys.stderr)
            prompt += "\n출력 형식을 확인하고 JSON으로 다시 답하세요."
    else:
        result = dict(reply=lou.FALLBACK.get(context["lang"], "제가 잘 확인하지 못했어요. 한 번만 다시 보내 주세요."), items=[])
    lou.safe_output(output)
    write_json(output, result)


def fix(run, request, output):
    """request: {items:[{quote, replace}]} — 고른 문장들을 본문에서 바꾼다. 하나라도 본문에 딱 한 번 나오지 않으면 아무것도 안 바꾼다."""
    req = lou.read(request)
    items = req.get("items")
    lou.require(isinstance(items, list) and 0 < len(items) <= 30)
    path, directions, at = _chosen(run)
    body = directions[at]["body"]
    for it in items:
        lou.require(isinstance(it, dict) and lou.string(it, "quote") and isinstance(it.get("replace"), str))
        lou.require(body.count(it["quote"]) == 1)
        body = body.replace(it["quote"], it["replace"].strip())
    have = [int(m.group(1)) for m in (re.fullmatch(r"directions\.v(\d+)\.json", f.name) for f in run.iterdir()) if m]
    shutil.copy2(path, run / f"directions.v{max(have, default=0) + 1}.json")
    directions[at]["body"] = body
    write_json(path, directions)
    lou.safe_output(output)
    write_json(output, dict(changed=len(items), scenes_stale=(run / "scenes.json").exists()))
