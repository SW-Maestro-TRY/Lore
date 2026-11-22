"""편집실의 루 — 작품 전체(여러 화)에서 한 가지 문제를 찾는다. 「이 인물이 나오는 장면 다 고쳐」.

1. 화마다 장면 글로 후보 장을 고른다 — 그 인물이나 함께 헷갈리는 인물이 그 장에 있는 장. 대사 속 이름(보고 · 소문 · 호칭)은
   그 장에 없는 사람이라 빼고, 생김새 칸 · 장소 · 행동 글을 본다(장 그림이 조연 시트를 붙일 때와 같은 기준).
2. 후보 장마다 그 장을 크게 보고, 그 사람들의 시트와 견줘 작가가 말한 문제가 정말 있는지 본다(동시에).
3. 문제가 있는 장만 「몇 화 몇 장 — 무엇」과 구도 유지로 고칠 지시로 돌려준다. 고치는 것은 작가가 고른 뒤다.
"""
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory

import llm
import lou
import loureview
import lousheet
from pagecheck import record, write_json

MAX_RUNS = 60
MAX_PAGES = 400
SAID = re.compile(r"“[^”“]{0,200}”|「[^」「]{0,200}」|『[^』『]{0,200}』|‘[^’‘]{0,200}’|\"[^\"\n]{0,200}\"")


def present(scene, names):
    """이 장면에 실제로 있는 사람 — 생김새 · 장소 · 행동(대사는 빼고). 생김새 칸이 없는 옛 장면은 글 전체에서 대사만 빼고."""
    look = str(scene.get("look") or "")
    keys = ("where", "acting") if look.strip() else ("where", "what", "acting")
    hay = look + " " + " ".join(SAID.sub(" ", str(scene.get(k) or "")) for k in keys)
    return [n for n in names if lou.name_in(n, hay)]


def candidates(run, names):
    """후보 장. 표지(1장)는 그 화 장면 어디에든 그 사람이 있으면 넣는다 — 표지에도 인물이 그려진다."""
    source = run / "scenes.json"
    scenes = lou.read(source).get("scenes", []) if source.exists() else []
    pages = dict(loureview.page_paths(run))
    out = [(i + 2, s) for i, s in enumerate(scenes) if i + 2 in pages and present(s, names)]
    if out and 1 in pages:
        out.insert(0, (1, {"what": "표지 — 이 화의 주요 인물이 그려진다"}))
    return out


def same_work(dirs):
    """같은 작품의 화들인가. 이어 쓴 화는 continuation.json 의 앞 화(source_run)로 이어진다 — 둘째 화부터 모두 앞 화가
    이 목록 안에 있어야 한다. 이어 쓰기 기록이 없는 작품은 첫 주인공 이름이 같은지로 본다(약한 판정)."""
    links = {}
    for run in dirs:
        cont = run / "continuation.json"
        links[run.name] = str(lou.read(cont).get("source_run") or "") if cont.exists() else None
    names = set(links)
    if all(v is not None for v in list(links.values())[1:]):
        heads = [r for r, prev in links.items() if not prev or prev not in names]
        return len(heads) == 1
    heroes = {lousheet.hero_name(run) for run in dirs}
    return len(heroes) == 1 and "" not in heroes


def _check(run, dest, page, scene, req, people, state, cast):
    source = dict(loureview.page_paths(run))[page]
    paths = [loureview.shrink(source, dest / f"{run.name}-p{page:02}.png", 1536)]
    shown = []
    for name in people:
        hit = lousheet.sheet_named(run, name)
        if hit and len(shown) < 3:
            shown.append(hit[0])
            paths.append(lousheet.small(hit[1], dest / f"{run.name}-p{page:02}-s{len(shown)}.png"))
    context = dict(lang=req.get("lang") or "ko", problem=req["text"], who=req["who"], also=req.get("also") or [],
                   sheets=shown, scene=scene, state=state, cast=cast)
    prompt = (lou.PROMPTS / "find_prompt").read_text(encoding="utf-8")
    prompt += "\n" + json.dumps(context, ensure_ascii=False) + "\n" + str([p.name for p in paths])
    call = llm.Call("LOU_REVIEW")
    try:
        raw, meta = call(prompt, images=llm.load_images(paths), temperature=0)
    except Exception as exc:                          # noqa: BLE001 — 한 장이 실패해도 나머지는 본다
        return None, {"stage": "LOU_REVIEW", "provider": call.provider, "model": call.model, "usage": None,
                      "cost": {}, "error": type(exc).__name__}
    try:
        data = lou.unwrap(raw)
        lou.require(isinstance(data, dict) and isinstance(data.get("bad"), bool))
    except (ValueError, KeyError, TypeError, AttributeError):
        print(f"[루] 찾기 답을 못 읽음 {run.name} {page}장: {raw[:300]!r}", file=sys.stderr)
        return None, meta
    if not data["bad"] or not lou.string(data, "note"):
        return dict(bad=False), meta
    note = lou.string(data, "note")
    return dict(bad=True, where=lou.string(data, "where"), what=lou.string(data, "what"),
                note=note if note.endswith(loureview.KEEP_TAIL) else f"{note} {loureview.KEEP_TAIL}"), meta


def find(runs_root, request, output, dry=False):
    """request: {text, who, also:[이름], runs:[작품 번호 — 이 작품의 화들, 앞에서부터], lang}."""
    req = lou.read(request)
    lou.require(lou.string(req, "text") and lou.string(req, "who"))
    runs = req.get("runs")
    lou.require(isinstance(runs, list) and 0 < len(runs) <= MAX_RUNS)
    root = Path(runs_root).resolve()
    dirs = []
    for rid in runs:
        lou.require(isinstance(rid, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,120}", rid) is not None)
        run = (root / rid).resolve()
        lou.require(run.is_dir() and run.parent == root)
        dirs.append(run)
    lou.require(same_work(dirs))
    people = [req["who"], *[a for a in req.get("also") or [] if isinstance(a, str) and a.strip()]][:3]
    jobs = []
    for run in dirs:
        names = [lousheet.full_name(run, n) for n in people]
        state = loureview.state_of(run)
        cast = [{k: c.get(k) for k in ("name", "role", "look")} for c in lousheet.cast_of(run)]
        for page, scene in candidates(run, names):
            jobs.append((run, page, {k: scene.get(k) for k in ("where", "what", "acting", "look")}, names, state, cast))
    jobs = jobs[:MAX_PAGES]
    if dry:
        lou.safe_output(output)
        write_json(output, dict(checked=[f"{r.name}:{p}" for r, p, *_ in jobs]))
        return
    with TemporaryDirectory() as tmp:
        dest = Path(tmp)
        with ThreadPoolExecutor(6) as pool:
            done = list(pool.map(lambda j: (j, _check(j[0], dest, j[1], j[2], req, j[3], j[4], j[5])), jobs))
    found, failed = [], []
    for (run, page, *_rest), (got, meta) in done:
        record(run, meta)
        if got is None:
            failed.append(dict(run=run.name, page=page))
        elif got["bad"]:
            found.append(dict(id=f"f{len(found) + 1}", run=run.name, page=page, method="keep",
                              **{k: got[k] for k in ("where", "what", "note")}))
    lou.safe_output(output)
    write_json(output, dict(who=req["who"], text=req["text"], checked=len(jobs), found=found, failed=failed))
