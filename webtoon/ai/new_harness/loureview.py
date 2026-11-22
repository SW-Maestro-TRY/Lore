"""편집실의 루 — 화 하나를 먼저 검토하고, 고를 것만 묶어 고친다.

review — 시트 · 장 그림 · 본문과 장마다 대본 · 지금 인물 상태 · 그림 속 글자를 한 번에 보고 고칠 것 목록을 낸다.
         목록이 나오면 장마다 크게 다시 보며 정말 그런지 한 번 더 거른다(설정 · 진행 상태와 맞는 것을 문제로 잡는 일이 있었다).
plan   — 작가가 체크한 항목을 장마다 고치기 한 번으로 묶는다(모델 호출 없음). 한 장에 지시가 여럿이면 한 지시로 합친다.

목록은 「권함」(독자가 바로 이상하다고 느끼는 것)과 「선택」을 나눈다. 검토 지적을 그대로 다 고치면 멀쩡한 장까지 건드린다.
"""
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image

import llm
import lou
import lousheet
from pagecheck import record, write_json

KINDS = "person bubble action panel understanding"
CAUSES = "picture scene character progress continuity rule"
SHEETS_SHOWN = 8              # 한 번에 보내는 시트 수
PAGES_SHOWN = 16              # 한 번에 보내는 장 수(표지 포함)
PAGE_WIDTH = 900              # 검토에 보내는 장의 폭(가로) — 사람 검토도 이 폭으로 봤다
STATE_CHARS = 4000
BODY_CHARS = 12000
KEEP_TAIL = "나머지는 그대로."


def page_paths(run):
    return sorted((int(p.stem[4:]), p) for p in (run / "pages").glob("page[0-9][0-9].png"))


def shrink(source, dest, width):
    with Image.open(source) as original:
        image = original.convert("RGB")
    if image.width > width:
        image = image.resize((width, round(image.height * width / image.width)), Image.Resampling.LANCZOS)
    image.save(dest, quality=85)
    return dest


def state_of(run):
    """지금 인물 상태 — 설정집 진행 상태의 「겉모습」 줄(사람마다 가장 최근 것)."""
    path = run / "input.json"
    book = (lou.read(path) if path.exists() else {}).get("lorebook") or {}
    books = [b for b in book.get("books") or [] if isinstance(b, dict)] or [book]
    latest = {}
    for b in books:
        for q in b.get("progress") or [] if isinstance(b.get("progress"), list) else []:
            if isinstance(q, dict) and q.get("kind") == "겉모습" and str(q.get("text") or "").strip():
                title = str(q.get("title") or "").strip()
                ep = q.get("ep") if isinstance(q.get("ep"), int) else -1
                if title and (title not in latest or ep >= latest[title][0]):
                    latest[title] = (ep, str(q["text"]).strip())
    text = "\n".join(f"- {t}: {v}" for t, (_e, v) in latest.items())
    return text[:STATE_CHARS]


def script_of(run):
    """이 화 본문과 장마다 대본. 1장은 표지, 2장부터 장면."""
    import run as R

    direction = R.direction_of(run) or {}
    body = str(direction.get("body") or direction.get("raw") or "")[:BODY_CHARS]
    source = run / "scenes.json"
    scenes = lou.read(source).get("scenes", []) if source.exists() else []
    pages = {i + 2: {k: s.get(k) for k in ("where", "what", "acting", "look", "narration") if s.get(k)}
             for i, s in enumerate(scenes)}
    return {"title": direction.get("title", ""), "body": body, "pages": pages}, scenes


def sheets_for(run, scenes):
    """이 화에 나오는 사람의 시트 — 첫 주인공은 늘, 나머지는 장면 글에 이름이 나온 사람."""
    text = " ".join(str(s.get(k, "")) for s in scenes for k in ("where", "what", "acting", "look"))
    pairs = lousheet.sheets_of(run)
    hero = pairs[:1] if (run / "sheet.png").exists() else []
    rest = [(n, p) for n, p in pairs if (n, p) not in hero and lou.name_in(n, text)]
    return (hero + rest)[:SHEETS_SHOWN]


# ── 다듬기 ───────────────────────────────────────────────────────────────────

def clean_items(data, pages, every=None):
    """pages — 항목이 가리킬 수 있는 장, every — 기준 장으로 쓸 수 있는 장(이 화의 모든 장)."""
    lou.require(isinstance(data.get("items"), list))
    out = []
    for i, it in enumerate(data["items"]):
        if not isinstance(it, dict) or it.get("page") not in pages:
            continue
        method = it.get("method") if it.get("method") in ("keep", "redraw") else "keep"
        note = lou.string(it, "note")
        what = lou.string(it, "what")
        if not note or not what:
            continue
        sev = it.get("sev") if it.get("sev") in ("P1", "P2") else "P2"
        basis = [b for b in it.get("basis") or [] if type(b) is int and b in (every or pages) and b != it["page"]][:2]
        out.append(dict(id=f"r{len(out) + 1}", page=it["page"],
                        x=it["x"] if lou.percent(it.get("x")) else 50, y=it["y"] if lou.percent(it.get("y")) else 50,
                        kind=lou.choice(it.get("kind"), KINDS), sev=sev,
                        recommend=bool(it.get("recommend")) if sev == "P2" else True,
                        where=lou.string(it, "where"), what=what,
                        cause=lou.choice(it.get("cause"), CAUSES), method=method,
                        note=note if note.endswith(KEEP_TAIL) else f"{note} {KEEP_TAIL}", basis=basis))
    return out


def clean_settings(raw, pages):
    """설정 층 제안 — 같은 문제가 두 장 이상에서 되풀이될 때만. 고치지는 않는다(작가가 고르면 서버가 설정을 고친다)."""
    out = []
    for s in raw if isinstance(raw, list) else []:
        if not isinstance(s, dict) or s.get("target") not in ("cast", "progress", "name"):
            continue
        where = [p for p in s.get("pages") or [] if type(p) is int and p in pages]
        if len(set(where)) < 2 or not lou.string(s, "change") or not lou.string(s, "who"):
            continue
        out.append(dict(id=f"s{len(out) + 1}", target=s["target"], who=lou.string(s, "who"), now=lou.string(s, "now"),
                        change=lou.string(s, "change"), why=lou.string(s, "why"), pages=sorted(set(where))))
    return out


# ── 검토 ─────────────────────────────────────────────────────────────────────

def _verify_page(run, dest, page, items, script, state, lang):
    """한 장의 항목을 크게 보고 다시 판단한다. (id → (real, why, fixed_note), 호출 기록)."""
    source = dict(page_paths(run))[page]
    paths = [shrink(source, dest / f"verify{page:02}.png", 1536)]
    basis = sorted({b for it in items for b in it["basis"]})
    pages = dict(page_paths(run))
    paths += [shrink(pages[b], dest / f"basis{page:02}-{b:02}.png", PAGE_WIDTH) for b in basis if b in pages]
    named = []
    for name, png in lousheet.sheets_of(run):
        if any(lou.name_in(name, it["note"]) for it in items) and len(named) < 2:
            named.append(png)
    paths += named
    context = dict(lang=lang, items=[{k: it[k] for k in ("id", "where", "what", "note", "basis")} for it in items],
                   script=script["pages"].get(page), state=state)
    prompt = (lou.PROMPTS / "verify_prompt").read_text(encoding="utf-8")
    prompt += "\n" + json.dumps(context, ensure_ascii=False) + "\n" + str([p.name for p in paths])
    call = llm.Call("LOU_REVIEW")
    try:
        raw, meta = call(prompt, images=llm.load_images(paths), temperature=0)
    except Exception as exc:                          # noqa: BLE001 — 한 장 확인이 실패해도 목록은 남긴다
        return {}, {"stage": "LOU", "provider": call.provider, "model": call.model, "usage": None,
                    "cost": {}, "error": type(exc).__name__}
    try:
        checks = lou.unwrap(raw)["checks"]
        got = {c["id"]: (c["real"], lou.string(c, "why"), lou.string(c, "fixed_note"))
               for c in checks if isinstance(c, dict) and isinstance(c.get("id"), str) and isinstance(c.get("real"), bool)}
    except (ValueError, KeyError, TypeError, AttributeError):
        print(f"[루] 확인 답을 못 읽음 {page}장: {raw[:300]!r}", file=sys.stderr)
        got = {}
    return got, meta


def _ask(prompt, paths, context, stage="LOU_REVIEW"):
    """한 번 묻고 JSON 을 받는다. 형식이 틀리면 한 번 더. (답 또는 None, 호출 기록들)."""
    call = llm.Call(stage)
    text = prompt + "\n" + json.dumps(context, ensure_ascii=False) + "\n" + str([p.name for p in paths])
    metas = []
    for _ in range(2):
        try:
            raw, meta = call(text, images=llm.load_images(paths), temperature=.2)
        except Exception as exc:                          # noqa: BLE001 — 한 장이 실패해도 나머지 장은 남긴다
            metas.append({"stage": stage, "provider": call.provider, "model": call.model, "usage": None,
                          "cost": {}, "error": type(exc).__name__})
            return None, metas
        metas.append(meta)
        try:
            data = lou.unwrap(raw)
            lou.require(isinstance(data, dict) and isinstance(data.get("items"), list))
            return data, metas
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            print(f"[루] 검토 답을 못 씀({type(exc).__name__}): {raw[:400]!r}", file=sys.stderr)
            text += "\n출력 형식을 확인하고 JSON으로 다시 답하세요."
    return None, metas


def _page_review(run, dest, n, numbers, paths_by_page, sheets, script, state, cast, read, diffs, lang):
    """한 장을 크게 — 앞뒤 장과 그 장에 나오는 사람의 시트를 같이 보고 고칠 것을 찾는다."""
    neighbors = [m for m in (n - 1, n + 1) if m in paths_by_page and m >= 1]
    scene_text = json.dumps(script["pages"].get(n) or {}, ensure_ascii=False)      # 시트는 맡은 장에 나오는 사람만
    people = [(name, png) for name, png in sheets if lou.name_in(name, scene_text)][:3] if n > 1 else sheets[:3]
    paths = [shrink(paths_by_page[n], dest / f"target{n:02}.png", 1536)]
    paths += [shrink(paths_by_page[m], dest / f"near{n:02}-{m:02}.jpg", 800) for m in neighbors]
    paths += [lousheet.small(png, dest / f"sheet{n:02}-{i}.png") for i, (_name, png) in enumerate(people)]
    context = dict(lang=lang, target=n, neighbors=neighbors, sheets=[name for name, _p in people],
                   script={m: script["pages"].get(m) for m in [n, *neighbors]} | {"title": script["title"]},
                   state=state, cast=cast, read=read.get(n, []), text_diff=[d for d in diffs if d["page"] == n])
    data, metas = _ask((lou.PROMPTS / "review_page_prompt").read_text(encoding="utf-8"), paths, context)
    # 맡은 장 말고 이웃 장 번호를 대면 그 장의 지시가 이 장으로 오므로 버린다(번호를 안 댄 것은 맡은 장으로 본다)
    raw = [it for it in (data or {}).get("items", []) if isinstance(it, dict) and it.get("page") in (None, n)]
    items = clean_items(dict(items=[dict(it, page=n) for it in raw]), [n], numbers)
    return items, metas, data is None


def review(run, request, output, dry=False):
    req = lou.read(request)
    lou.require(run.is_dir())
    lang = req.get("lang") or "ko"
    pages = page_paths(run)
    lou.require(pages)
    script, scenes = script_of(run)
    state = state_of(run)
    sheets = sheets_for(run, scenes)
    cast = [{k: c.get(k) for k in ("name", "role", "look")} for c in lousheet.cast_of(run)]
    shown = pages[:PAGES_SHOWN]
    numbers = [n for n, _p in shown]
    paths_by_page = dict(shown)
    with TemporaryDirectory() as tmp:
        dest = run / "lou" / output.name if dry else Path(tmp)
        dest.mkdir(parents=True, exist_ok=True)
        if dry:
            (dest / "prompt.txt").write_text((lou.PROMPTS / "review_page_prompt").read_text(encoding="utf-8")
                                             + "\n" + json.dumps(dict(script=script, state=state), ensure_ascii=False),
                                             encoding="utf-8")
            return
        # 그림 속 글자는 대사를 모르는 눈이 먼저 옮겨 적는다(lou.py 와 같은 이유)
        cut = [lou.bands(p, dest, f"read{n:02}") for n, p in shown]
        found = lou.read_all(run, [b for one in cut for b in one])
        read = {n: lou.join_bands([found.pop(0) for _ in one]) for (n, _p), one in zip(shown, cut)}
        scene_of = {i + 2: s for i, s in enumerate(scenes)}
        diffs = [d for n, lines in read.items() for d in lou.text_diff(n, lines, scene_of.get(n) or {})]
        # 1) 장마다 따로, 동시에
        with ThreadPoolExecutor(6) as pool:
            done = list(pool.map(lambda n: _page_review(run, dest, n, numbers, paths_by_page, sheets, script, state,
                                                        cast, read, diffs, lang), numbers))
        items, failed = [], []
        for n, (got, metas, broke) in zip(numbers, done):
            for meta in metas:
                record(run, meta)
            items += got
            if broke:
                failed.append(n)
        # 2) 화 전체 — 이해
        story_paths = [shrink(p, dest / f"story{n:02}.jpg", 700) for n, p in shown]
        context = dict(lang=lang, pages=numbers, script=script, state=state, cast=cast,
                       found=[{k: it[k] for k in ("page", "what")} for it in items])
        data, metas = _ask((lou.PROMPTS / "review_story_prompt").read_text(encoding="utf-8"), story_paths, context)
        for meta in metas:
            record(run, meta)
        items += clean_items(dict(items=(data or {}).get("items", [])), numbers)
        settings = clean_settings((data or {}).get("settings"), numbers)
        reply = lou.string(data or {}, "reply") or (f"{len(items)}곳을 찾았어요." if items else "고칠 곳을 찾지 못했어요.")
        for i, it in enumerate(items):
            it["id"] = f"r{i + 1}"
        # 3) 한 번 더 — 장마다 크게 보고 거른다
        by_page = {}
        for it in items:
            by_page.setdefault(it["page"], []).append(it)
        with ThreadPoolExecutor(4) as pool:
            checked = list(pool.map(lambda kv: _verify_page(run, dest, kv[0], kv[1], script, state, lang),
                                    by_page.items()))
        verdict = {}
        for (page, its), (got, meta) in zip(by_page.items(), checked):
            record(run, meta)
            mine = {it["id"] for it in its}                 # 다른 장의 번호로 온 판정 · 지시는 받지 않는다
            verdict.update({k: v for k, v in got.items() if k in mine})
        kept, dropped = [], []
        for it in items:
            if it["id"] not in verdict:
                # 다시 확인을 못 했다 — 목록에는 남기되 권하지 않고 표시한다(확인 없이 한꺼번에 고치지 않게)
                kept.append(dict(it, recommend=False, unverified=True))
                if it["page"] not in failed:
                    failed.append(it["page"])
                continue
            real, why, fixed = verdict[it["id"]]
            if fixed:
                it["note"] = fixed if fixed.endswith(KEEP_TAIL) else f"{fixed} {KEEP_TAIL}"
            (kept if real else dropped).append(it if real else dict(it, why=why))
        order = {"P1": 0, "P2": 1}
        kept.sort(key=lambda it: (not it["recommend"], order[it["sev"]], it["page"]))
        lou.safe_output(output)
        write_json(output, dict(reply=reply, items=kept, dropped=dropped, settings=settings, pages=numbers, failed_pages=failed,
                                skipped_pages=[n for n, _p in pages[PAGES_SHOWN:]]))


# ── 묶기 ─────────────────────────────────────────────────────────────────────

def plan(run, request, output):
    """체크한 항목 → 장마다 고치기 하나. 새로 그리기가 하나라도 있으면 그 장은 새로 그리기로 합친다."""
    req = lou.read(request)
    items = req.get("items")
    lou.require(isinstance(items, list) and items)
    pages = dict(page_paths(run))
    jobs = {}
    for it in items:
        lou.require(isinstance(it, dict) and it.get("page") in pages and lou.string(it, "note")
                    and it.get("method") in ("keep", "redraw"))
        job = jobs.setdefault(it["page"], dict(page=it["page"], method="keep", notes=[], basis=[], items=[]))
        if it["method"] == "redraw":
            job["method"] = "redraw"
        note = re.sub(r"\s*나머지는 그대로\.?\s*$", "", it["note"].strip())
        if note not in job["notes"]:
            job["notes"].append(note)
        job["basis"] += [b for b in it.get("basis") or [] if type(b) is int and b in pages and b != it["page"]]
        job["items"].append(it.get("id", ""))
    out = []
    for page in sorted(jobs):
        job = jobs[page]
        # 기준 장은 하나만 붙인다(참조 한도). 여러 항목이 서로 다른 장을 기준으로 삼으면 가장 많이 쓴 장을 붙이고,
        # 나머지는 지시의 글(「몇 장과 같게」)로만 간다 — 그런 장이 있었다는 것을 남긴다.
        counts = {b: job["basis"].count(b) for b in job["basis"]}
        best = max(counts, key=lambda b: (counts[b], -job["basis"].index(b))) if counts else None
        out.append(dict(page=page, method=job["method"], items=job["items"], basis=[best] if best else [],
                        basis_text_only=sorted(b for b in counts if b != best),
                        note=" ".join(n.rstrip(".") + "." for n in job["notes"]) + " " + KEEP_TAIL))
    lou.safe_output(output)
    write_json(output, {"jobs": out})
