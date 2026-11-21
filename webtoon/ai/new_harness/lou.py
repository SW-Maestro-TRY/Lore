"""편집실의 루.

ask   — 작가가 짚은 곳과 한마디를 받아, 무엇을 가리키는지 · 왜 그런지 · 어떻게 고칠지 답한다.
check — 고친 장을 고치기 전과 견줘, 말한 것만 바뀌었는지 본다.

글자는 따로 읽는다. 장면 글을 아는 눈은 틀린 글자도 아는 대사대로 읽어 버려서(「났오」를 「났고」로),
장면 글을 주지 않은 호출이 먼저 그림 속 글자를 옮겨 적고(read) 그 결과를 코드가 대사와 견준다.
"""
import difflib
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from PIL import Image, ImageDraw, ImageFont

import llm
from pagecheck import record, write_json

PROMPTS = Path(__file__).parent / "prompt" / "lou"
METHODS = ("text", "keep", "redraw", "textless")
SCENE_KEYS = ("where", "what", "acting")      # 장면 글에서 루가 고쳐 쓸 수 있는 칸
PIN_SIDE = 1200
FALLBACK = {"en": "I couldn't work that out. Could you send it again?",
            "ja": "うまく確認できませんでした。もう一度送ってください。"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def require(condition):
    if not condition:
        raise ValueError("루 대화 데이터 형식이 올바르지 않습니다")


def percent(value):
    return type(value) in (int, float) and 0 <= value <= 100


def unwrap(raw):
    return json.loads(raw.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip())


def selection(req):
    """짚은 장(없으면 보고 있던 장). 한 번에 그림 세 장까지만 보고 나머지는 건너뛴 장으로 돌려준다."""
    pins = req.get("pins", [])
    require(isinstance(pins, list) and len(pins) <= 8)
    numbers = set()
    for pin in pins:
        require(type(pin["n"]) is int and pin["n"] > 0 and pin["n"] not in numbers)
        require(percent(pin["x"]) and percent(pin["y"]))
        numbers.add(pin["n"])
    pages = list(dict.fromkeys(pin["page"] for pin in pins)) or [req["active_page"]]
    require(all(type(p) is int and p > 0 for p in pages))
    return pages[:3], pages[3:]


def pictures(run, dest, page, pins):
    """장 전체(짚은 곳에 번호를 찍은 것)와 짚은 곳마다 번호 없이 자른 그림."""
    with Image.open(run / "pages" / f"page{page:02}.png") as original:
        full = original.convert("RGB")
        full.thumbnail((1536, 1536), Image.Resampling.LANCZOS)
        draw = ImageDraw.Draw(full)
        radius = max(12, min(full.size) // 40)
        font = ImageFont.load_default()
        paths = [dest / f"page{page:02}.png"]
        side = max(1, min(original.height, round(original.width * .4)))
        for pin in pins:
            fx, fy = pin["x"] / 100, pin["y"] / 100
            x = min(full.width-radius-1, max(radius, round(full.width*fx)))
            y = min(full.height-radius-1, max(radius, round(full.height*fy)))
            draw.ellipse((x-radius, y-radius, x+radius, y+radius), fill="red", outline="white")
            draw.text((x, y), str(pin["n"]), anchor="mm", font=font, fill="white")
            left = max(0, min(original.width-side, round(original.width*fx-side/2)))
            top = max(0, min(original.height-side, round(original.height*fy-side/2)))
            path = dest / f"pin{pin['n']}.png"
            piece = original.crop((left, top, left+side, top+side))
            if side < PIN_SIDE:          # 작은 글자와 손끝은 키워야 보인다
                piece = piece.resize((PIN_SIDE, PIN_SIDE), Image.Resampling.LANCZOS)
            piece.save(path)
            paths.append(path)
        full.save(paths[0])
        return paths


# ── 글자 ────────────────────────────────────────────────────────────────────

def read_text(path):
    """그림 한 장의 글자를 보이는 그대로 옮겨 적는다. (줄 목록, 호출 기록). 못 읽으면 빈 목록."""
    call = llm.Call("LOU")
    try:
        raw, meta = call((PROMPTS / "read_prompt").read_text(encoding="utf-8"),
                         images=llm.load_images([path]), temperature=0)
    except Exception as exc:
        return [], {"stage": "LOU", "provider": call.provider, "model": call.model,
                    "usage": None, "cost": {}, "error": type(exc).__name__}
    try:
        lines = unwrap(raw)["lines"]
        require(isinstance(lines, list))
        return [" ".join(s.split()) for s in lines if isinstance(s, str) and s.strip()], meta
    except (ValueError, KeyError, TypeError, AttributeError):
        print(f"[루] 글자 읽기 답을 못 읽음: {raw[:300]!r}", file=sys.stderr)
        return [], meta


def read_all(run, paths):
    """여러 그림을 동시에, 서로의 답을 못 보게 따로 읽힌다. 그림마다 줄 목록."""
    with ThreadPoolExecutor(8) as pool:
        done = list(pool.map(read_text, paths))
    for _lines, meta in done:
        record(run, meta)
    return [lines for lines, _meta in done]


def bands(path, dest, tag):
    """장을 위에서 아래로 겹치게 자르고 두 배로 키운 그림들.

    장 전체를 한 번에 읽히면 받침이나 모음 하나가 다른 글자(「물린」 → 「몰린」)를 아는 낱말로 읽어 버린다.
    같은 말풍선을 크게 잘라 주면 보이는 대로 읽는다.
    """
    out = []
    with Image.open(path) as original:
        page = original.convert("RGB")
    tall = min(page.height, round(page.width * .8))
    tops = list(range(0, max(1, page.height - tall + 1), max(1, round(tall * .75))))
    if tops[-1] + tall < page.height:
        tops.append(page.height - tall)
    for i, top in enumerate(tops):
        piece = page.crop((0, top, page.width, top + tall))
        piece = piece.resize((piece.width * 2, piece.height * 2), Image.Resampling.LANCZOS)
        out.append(dest / f"{tag}-band{i}.png")
        piece.save(out[-1])
    return out


def join_bands(found):
    """띠마다 읽은 줄을 위에서부터 잇는다. 겹친 자리에서 두 번 읽힌 줄은 하나로 친다."""
    return list({squash(s): s for band in found for s in band if squash(s)}.values())


def squash(text):
    return re.sub(r"[^0-9A-Za-z가-힣ぁ-んァ-ヶ一-龥]", "", text)


def spoken(scene):
    """장면 글에 적힌 대사와 나레이션."""
    text = " ".join(str(scene.get(k, "")) for k in ("what", "acting"))
    lines = [a or b for a, b in re.findall(r"“([^”]+)”|\"([^\"]+)\"", text)]
    narration = scene.get("narration")
    return lines + [s for s in (narration if isinstance(narration, list) else []) if isinstance(s, str)]


def near(seen, want):
    """seen 이 want 의 어느 대목과 얼마나 같은가(0~1). 한 대사가 말풍선 둘로 나뉘어 있어도 맞춘다."""
    a, b = squash(seen), squash(want)
    if not a or not b:
        return 0.0
    if a in b:
        return 1.0
    best = 0.0
    for size in {len(a) - 1, len(a), len(a) + 1}:
        for i in range(max(1, len(b) - size + 1)) if size >= 2 else ():
            best = max(best, difflib.SequenceMatcher(None, a, b[i:i+size]).ratio())
    return best


def text_diff(page, lines, scene):
    """옮겨 적은 줄 가운데 장면 글의 대사와 거의 같은데 글자가 다른 것."""
    out = []
    for seen in lines:
        if len(squash(seen)) < 4:
            continue
        score, want = max(((near(seen, w), w) for w in spoken(scene)), default=(0, ""))
        if .8 <= score < 1:
            out.append(dict(page=page, seen=seen, want=want))
    return out


def text_changed(before, after):
    """고치기 전에 있던 줄 가운데 고친 뒤에 똑같이 남아 있지 않은 것."""
    rest = {squash(s): s for s in after}
    out = []
    for line in before:
        key = squash(line)
        if len(key) < 2 or key in rest:
            continue
        score, twin = max(((difflib.SequenceMatcher(None, key, k).ratio(), s) for k, s in rest.items()),
                          default=(0, ""))
        out.append(dict(before=line, after=twin if score >= .6 else ""))
    return out


# ── 답 다듬기 ────────────────────────────────────────────────────────────────

def string(obj, key):
    return obj.get(key).strip() if isinstance(obj.get(key), str) else ""


def choice(value, allowed):
    return value if value in allowed.split() else "unknown"


def name_in(name, text):
    """「디온 아스칼라」는 장면 글에 「디온」으로 나온다 — 이름의 어느 토막이든 나오면 그 사람이다."""
    return name in text or any(len(part) >= 2 and part in text for part in name.split())


def clean_check(data):
    require(type(data.get("ok")) is bool)
    for key in ("kept", "changed", "unexpected"):
        require(isinstance(data.get(key), list) and all(isinstance(s, str) for s in data[key]))
    return {k: data[k] for k in ("ok", "reply", "kept", "changed", "unexpected")}


def clean_ask(data, pages, skipped, spots, scenes):
    """모델의 답에서 쓸 것만 남긴다. 찾은 곳은 틀린 항목만 버리고, 고치는 방법은 하나라도 틀리면 답 전체를 물린다."""
    require(isinstance(data.get("found"), list) and isinstance(data.get("plans"), list))
    found = []
    for f in data["found"]:
        if not isinstance(f, dict) or f.get("page") not in pages:
            continue
        pin = f.get("pin") if (f.get("pin"), f["page"]) in spots else 0
        found.append(dict(pin=pin, page=f["page"],
                          what=choice(f.get("what"), "person bubble text background object"),
                          **{k: string(f, k) for k in ("who", "where", "quote")}))
    plans = []
    for p in data["plans"]:
        method, targets = p["method"], p["pages"]
        require(method in METHODS and isinstance(targets, list) and targets
                and all(type(n) is int and n in pages for n in targets))
        # note 는 그림 그리는 쪽에 전해지는 지시다 — 그림을 건드리는 방법에만 꼭 있어야 한다.
        require(string(p, "changes") and (string(p, "note") or method != "keep"))
        fix = None
        if method == "text":
            f = p["text"]
            require(f["page"] in targets and percent(f["x"]) and percent(f["y"])
                    and isinstance(f["text"], str) and 0 < len(f["text"].strip()) <= 200)
            fix = dict(page=f["page"], pin=0, x=f["x"], y=f["y"], text=" ".join(f["text"].split()))
            spot = spots.get((f.get("pin"), f["page"]))
            if spot:                     # 작가가 짚은 자리가 모델이 어림한 자리보다 정확하다
                fix.update(pin=f["pin"], x=spot[0], y=spot[1])
        # 생김새를 고칠 때, 같은 사람이 나오는 이 화의 다른 장. 같은 문제가 있다는 뜻은 아니다 — 볼 후보다.
        names = {f["who"] for f in found if f["what"] == "person" and f["who"] and f["page"] in targets}
        more = [n for n, s in sorted(scenes.items()) if n not in targets and any(
            name_in(name, " ".join(str(s.get(k, "")) for k in ("what", "acting", "look"))) for name in names)
                ] if method == "keep" else []
        scene = p.get("scene") if isinstance(p.get("scene"), dict) else {}
        note = string(p, "note") or (string(p, "changes") if method == "redraw" else "")
        plans.append(dict(id=f"p{len(plans)+1}", method=method, pages=targets, more_pages=more,
                          changes=string(p, "changes"), keeps=string(p, "keeps"), note=note,
                          scene={k: (string(scene, k) or None) if method == "redraw" else None
                                 for k in SCENE_KEYS},
                          text=fix))
    cause = data.get("cause") if isinstance(data.get("cause"), dict) else {}
    return dict(reply=data["reply"].strip(), found=found, plans=plans, skipped_pages=skipped,
                cause=dict(layer=choice(cause.get("layer"), "picture scene character rule"),
                           text=string(cause, "text"), quote=string(cause, "quote")),
                ask_back=string(data, "ask_back"))


# ── 한 번의 대화 ─────────────────────────────────────────────────────────────

def process(run, kind, request, output, dry=False):
    req = read(request)
    require(run.is_dir())
    if kind == "ask" and req.get("sheet"):
        import lousheet
        return lousheet.ask(run, req, output, dry)
    with TemporaryDirectory() as tmp:
        dest = run / "lou" / output.name if dry else Path(tmp)
        dest.mkdir(parents=True, exist_ok=True)
        context = dict(req)
        extra = {}                       # 코드가 계산해 답에 그대로 싣는 것
        if kind == "ask":
            pages, skipped = selection(req)
            context["pins"] = [p for p in req.get("pins", []) if p["page"] in pages]
            spots = {(p["n"], p["page"]): (p["x"], p["y"]) for p in context["pins"]}
            source = run / "scenes.json"
            scenes = {i + 1: s for i, s in enumerate(read(source)["scenes"] if source.exists() else [], 1)}
            context["scenes"] = {p: scenes.get(p) for p in pages}
            hero = read(run / "input.json")
            context["hero"] = {k: hero.get(k) for k in ("name", "description", "fields")}
            context["cast"] = read(run / "cast.json") if (run / "cast.json").exists() else []
            context["sheets"] = ([hero["name"]] if (run / "sheet.png").exists() else []) + [
                p.stem for p in sorted((run / "sheets").glob("*.png"))]
            paths = [path for p in pages
                     for path in pictures(run, dest, p, [i for i in context["pins"] if i["page"] == p])]
            originals = [run / "pages" / f"page{p:02}.png" for p in pages]
            if not dry:
                crops = [(pin, dest / f"pin{pin['n']}.png") for pin in context["pins"]]
                cut = [bands(o, dest, f"read{p:02}") for p, o in zip(pages, originals)]
                found = read_all(run, [b for one in cut for b in one] + [c for _pin, c in crops])
                whole = [join_bands([found.pop(0) for _ in one]) for one in cut]
                close = found
                context["read"] = dict(zip(pages, whole))
                # 짚은 곳은 가장 크게 본 것이라 가장 믿을 만하다 — 번호를 달아 따로 준다.
                context["read_pins"] = {pin["n"]: lines for (pin, _c), lines in zip(crops, close)}
                diffs = [dict(d, pin=pin["n"]) for (pin, _c), lines in zip(crops, close)
                         for d in text_diff(pin["page"], lines, scenes.get(pin["page"]) or {})]
                seen = {squash(d["seen"]) for d in diffs}
                diffs += [dict(d, pin=0) for p, lines in zip(pages, whole)
                          for d in text_diff(p, lines, scenes.get(p) or {}) if squash(d["seen"]) not in seen]
                extra["text_diff"] = context["text_diff"] = diffs
        else:
            paths = [Path(context.pop(k)) for k in ("before", "after")]
            if not dry and context.get("method") == "keep":   # 새로 그린 장은 글자가 달라지는 것이 정상이다
                cut = [bands(path, dest, tag) for tag, path in zip(("before", "after"), paths)]
                found = read_all(run, cut[0] + cut[1])
                before, after = join_bands(found[:len(cut[0])]), join_bands(found[len(cut[0]):])
                extra["text_changed"] = context["text_changed"] = text_changed(before, after)
        prompt = (PROMPTS / f"{kind}_prompt").read_text(encoding="utf-8")
        prompt += "\n" + json.dumps(context, ensure_ascii=False) + "\n" + str([p.name for p in paths])
        if dry:
            if kind == "check":
                for name, path in zip(("before.png", "after.png"), paths):
                    copyfile(path, dest / name)
            (dest / "prompt.txt").write_text(prompt, encoding="utf-8")
            return
        images = llm.load_images(paths)
        call = llm.Call("LOU")
        for _ in range(2):
            try:
                raw, meta = call(prompt, images=images, temperature=.2)
            except Exception as exc:
                record(run, {"stage": "LOU", "provider": call.provider, "model": call.model,
                             "usage": None, "cost": {}, "error": type(exc).__name__})
                raise
            record(run, meta)
            try:
                data = unwrap(raw)
                require(isinstance(data, dict) and isinstance(data.get("reply"), str) and data["reply"].strip())
                result = clean_check(data) if kind == "check" else clean_ask(data, pages, skipped, spots, scenes)
                break
            except (ValueError, KeyError, TypeError, AttributeError) as exc:
                print(f"[루] 답을 못 씀({type(exc).__name__}): {raw[:600]!r}", file=sys.stderr)
                prompt += "\n출력 형식을 확인하고 필수 칸을 채워 JSON으로 다시 답하세요."
        else:
            reply = FALLBACK.get(req.get("lang"), "제가 잘 확인하지 못했어요. 한 번만 다시 보내 주세요.")
            result = (dict(reply=reply, found=[], plans=[], cause=dict(layer="unknown", text="", quote=""),
                           ask_back="", skipped_pages=skipped)
                      if kind == "ask" else dict(reply=reply, ok=False, kept=[], changed=[], unexpected=[]))
        write_json(output, {**result, **extra})
