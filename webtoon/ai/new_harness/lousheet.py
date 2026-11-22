"""편집실의 루 — 캐릭터 시트.

ask   — 작가가 시트를 짚고 한마디 하면, 무엇이 마음에 안 드는지 · 왜 그런지 · 어떻게 고칠지 답한다.
draft — 고르기 전의 후보 한 장을 그린다. 지금 시트는 그대로 둔다.
pick  — 고른 후보를 그 인물의 시트로 건다. 지금 시트는 지난 판으로 남기고, 생김새 설정이 바뀌면 같이 고친다.

시트만 바꾸면 그 뒤로 그리는 장은 설정 글(cast.json 의 look)과 새 시트가 어긋나 옛 모습으로 돌아가고(2026-11-20),
이미 그린 장은 옛 시트대로 남는다. 그래서 묻기는 생김새 설정을 고친 글과 이 화에서 그 인물이 나오는 장을 같이 돌려준다.
"""
import json
import re
import shutil
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image, ImageDraw, ImageFont

import imagegen
import llm
import lou
from pagecheck import record, write_json

METHODS = ("sheet_keep", "sheet_new")
SHOWN = 2                       # 작가의 말에 나온 다른 인물의 시트를 몇 장까지 같이 보내나


def stem(name):
    """시트 파일 이름 — own.sheet_file_name 과 같은 규칙(자바 RunArt 도 같다)."""
    return re.sub(r"[\\/]", "_", (name or "").strip())


def hero_name(run):
    path = run / "input.json"
    return str((lou.read(path) if path.exists() else {}).get("name") or "").strip()


def sheets_of(run):
    """(이름, 그림) — 첫 주인공 먼저. 첫 주인공은 sheet.png, 나머지는 sheets/<이름>.png."""
    out = []
    hero = hero_name(run)
    if hero and (run / "sheet.png").exists():
        out.append((hero, run / "sheet.png"))
    out += [(p.stem, p) for p in sorted((run / "sheets").glob("*.png"))]
    return out


def same_person(pairs, name):
    """이름이 같은 사람 하나. 정확히 같은 이름이 먼저다. 없으면 성이나 이름 한 낱말이 통째로 같은 사람이 딱 하나일 때만.

    「카를」이 「카를로스」로, 「로제로」가 같은 성의 다른 사람으로 가면 그 사람의 시트를 덮는다 — 그래서 글자 일부가 겹치는
    것으로는 맞추지 않고, 여럿이 걸리면 못 찾은 것으로 친다.
    """
    name = " ".join((name or "").split())
    if not name:
        return None
    exact = [pair for pair in pairs if pair[0] == name]
    if exact:
        return exact[0]
    words = set(name.split())
    loose = [pair for pair in pairs
             if len(name) >= 2 and (name in pair[0].split() or set(pair[0].split()) <= words)]
    return loose[0] if len(loose) == 1 else None


def find(run, name):
    """이름으로 시트를 찾는다. 성 없이 불러도 찾는다. 없거나 여럿이 걸리면 None."""
    return same_person(sheets_of(run), name)


def cast_of(run):
    path = run / "cast.json"
    cast = lou.read(path) if path.exists() else []
    return cast if isinstance(cast, list) else []


def person_of(run, name):
    hit = same_person([(str(p.get("name") or ""), p) for p in cast_of(run)], name)
    return hit[1] if hit else None


def appears(run, name):
    """이 화에서 그 인물이 장면 글에 나오는 장 번호."""
    source = run / "scenes.json"
    scenes = lou.read(source)["scenes"] if source.exists() else []
    return [i + 2 for i, s in enumerate(scenes)
            if lou.name_in(name, " ".join(str(s.get(k, "")) for k in ("where", "what", "acting", "look")))]


def marked(source, dest, pins):
    """시트 전체(짚은 곳에 번호)와 짚은 곳마다 크게 자른 그림. 시트는 가로로 넓어서 자르는 칸을 세로 기준으로 잡는다."""
    with Image.open(source) as original:
        full = original.convert("RGB")
        full.thumbnail((1536, 1536), Image.Resampling.LANCZOS)
        draw = ImageDraw.Draw(full)
        radius = max(12, min(full.size) // 40)
        font = ImageFont.load_default()
        paths = [dest / "sheet.png"]
        side = max(1, round(min(original.width, original.height) * .45))
        for pin in pins:
            fx, fy = pin["x"] / 100, pin["y"] / 100
            x = min(full.width - radius - 1, max(radius, round(full.width * fx)))
            y = min(full.height - radius - 1, max(radius, round(full.height * fy)))
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill="red", outline="white")
            draw.text((x, y), str(pin["n"]), anchor="mm", font=font, fill="white")
            left = max(0, min(original.width - side, round(original.width * fx - side / 2)))
            top = max(0, min(original.height - side, round(original.height * fy - side / 2)))
            piece = original.crop((left, top, left + side, top + side)).convert("RGB")
            piece = piece.resize((lou.PIN_SIDE, lou.PIN_SIDE), Image.Resampling.LANCZOS)
            paths.append(dest / f"pin{pin['n']}.png")
            piece.save(paths[-1])
        full.save(paths[0])
    return paths


def small(source, dest):
    with Image.open(source) as original:
        image = original.convert("RGB")
    image.thumbnail((1536, 1536), Image.Resampling.LANCZOS)
    image.save(dest)
    return dest


# ── 묻기 ─────────────────────────────────────────────────────────────────────

def clean(data, run, target_name, shown_appears):
    lou.require(isinstance(data.get("found"), list) and isinstance(data.get("plans"), list))
    names = {n for n, _p in sheets_of(run)} | {str(p.get("name") or "") for p in cast_of(run)}
    names.discard("")
    found = []
    for f in data["found"]:
        if isinstance(f, dict):
            found.append(dict(pin=f.get("pin") if type(f.get("pin")) is int else 0,
                              what=lou.choice(f.get("what"), "face hair eyes outfit expression body"),
                              **{k: lou.string(f, k) for k in ("where", "quote")}))
    plans = []
    for p in data["plans"]:
        method = p["method"]
        target = lou.string(p, "target") or target_name
        base = lou.string(p, "base")
        refs = [r for r in (p.get("refs") or []) if isinstance(r, str) and r.strip()]
        lou.require(method in METHODS and lou.string(p, "changes") and lou.string(p, "note"))
        lou.require(target in names or any(lou.name_in(n, target) for n in names))
        lou.require(all(sheet_named(run, r) for r in [*refs, *([base] if base else [])]))
        lou.require(method == "sheet_new" or sheet_named(run, base or target))
        plans.append(dict(id=f"p{len(plans) + 1}", method=method, target=target, base=base, refs=refs[:3],
                          **{k: lou.string(p, k) for k in ("changes", "keeps", "note", "look")},
                          more_pages=appears(run, target), follow_note=follow_note(target, lou.string(p, "changes"))))
    cause = data.get("cause") if isinstance(data.get("cause"), dict) else {}
    return dict(reply=data["reply"].strip(), found=found, plans=plans, appears=shown_appears,
                cause=dict(layer=lou.choice(cause.get("layer"), "picture character"),
                           text=lou.string(cause, "text"), quote=lou.string(cause, "quote")),
                ask_back=lou.string(data, "ask_back"))


def follow_note(name, changes):
    """시트를 바꾼 뒤 이미 그린 장을 맞출 때 쓰는 구도 유지 편집의 지시. 이름이 들어가야 그 사람의 새 시트가 붙는다."""
    return (f"새 캐릭터 시트대로 고칠 인물: {name}. 바뀐 점: {changes} "
            "이 인물의 자세와 표정, 다른 인물, 칸의 배치, 말풍선과 글자는 그대로 둔다.")


def ask(run, req, output, dry=False):
    hit = sheet_named(run, req.get("sheet"))      # 이름이 겹치는 다른 사람의 시트로 묻지 않게 온전한 이름으로
    lou.require(hit is not None)
    name, source = hit
    pins = req.get("pins") or []
    lou.require(isinstance(pins, list) and len(pins) <= 8)
    for pin in pins:
        lou.require(type(pin.get("n")) is int and lou.percent(pin.get("x")) and lou.percent(pin.get("y")))
    said = " ".join([str(req.get("text") or "")] + [str(h.get("text") or "") for h in req.get("history") or []])
    others = [(n, p) for n, p in sheets_of(run) if n != name and lou.name_in(n, said)][:SHOWN]
    context = {k: req.get(k) for k in ("text", "lang", "history")}
    context.update(pins=pins, sheet=name, person=person_of(run, name),
                   cast=[{k: c.get(k) for k in ("name", "role", "look")} for c in cast_of(run)],
                   hero={k: (lou.read(run / "input.json") if (run / "input.json").exists() else {}).get(k)
                         for k in ("name", "description")},
                   sheets=[n for n, _p in sheets_of(run)], shown=[n for n, _p in others],
                   appears=appears(run, name))
    with TemporaryDirectory() as tmp:
        dest = run / "lou" / output.name if dry else Path(tmp)
        dest.mkdir(parents=True, exist_ok=True)
        paths = marked(source, dest, pins) + [small(p, dest / f"other{i}.png") for i, (_n, p) in enumerate(others)]
        prompt = (lou.PROMPTS / "sheet_ask_prompt").read_text(encoding="utf-8")
        prompt += "\n" + json.dumps(context, ensure_ascii=False) + "\n" + str([p.name for p in paths])
        if dry:
            (dest / "prompt.txt").write_text(prompt, encoding="utf-8")
            return
        images = llm.load_images(paths)
        call = llm.Call("LOU")
        for _ in range(2):
            raw, meta = call(prompt, images=images, temperature=.2)
            record(run, meta)
            try:
                data = lou.unwrap(raw)
                lou.require(isinstance(data, dict) and lou.string(data, "reply"))
                result = clean(data, run, name, context["appears"])
                break
            except (ValueError, KeyError, TypeError, AttributeError) as exc:
                print(f"[루] 시트 답을 못 씀({type(exc).__name__}): {raw[:600]!r}", file=sys.stderr)
                prompt += "\n출력 형식을 확인하고 필수 칸을 채워 JSON으로 다시 답하세요."
        else:
            result = dict(reply=lou.FALLBACK.get(req.get("lang"), "제가 잘 확인하지 못했어요. 한 번만 다시 보내 주세요."),
                          found=[], plans=[], appears=context["appears"],
                          cause=dict(layer="unknown", text="", quote=""), ask_back="")
        lou.safe_output(output)
        write_json(output, {**result, "sheet": name})


# ── 후보 그리기 · 고르기 ─────────────────────────────────────────────────────

def _new_spec(run, plan):
    """설정 글(고친 look 과 note)로 시트 사양을 새로 쓴다 — 제품 조연 시트와 같은 지시문(own/cast_sheet_prompt)."""
    import run as R
    import sheet as sheetmod

    person = dict(person_of(run, plan["target"]) or {"name": plan["target"]})
    look = plan.get("look") or str(person.get("look") or "")
    inp = lou.read(run / "input.json") if (run / "input.json").exists() else {}
    lines = ["# 이번 입력", "", f"캐릭터 이름: {person.get('name') or plan['target']}",
             "외관: (사진 없음 — 아래 생김새가 전부다)", f"생김새·인상: {look}",
             f"작가의 그림 요청(생김새 글과 어긋나지 않는 선에서 따른다): {plan['note']}"]
    for key, label in (("gap", "갭"), ("voice", "말투")):
        if str(person.get(key) or "").strip():
            lines.append(f"{label}: {person[key]}")
    lines.append(f"장르: {inp.get('genre') or '(없음)'}")
    call = llm.Call("CAST_SHEET")
    text, meta = call(R.compose("own/cast_sheet_prompt", "\n".join(lines) + "\n"), temperature=0.4)
    record(run, meta)
    try:
        spec = sheetmod.parse_spec(text)
        bad = sheetmod.gate_spec(spec)
    except Exception as exc:                      # noqa: BLE001 — 모델 답이 사양 모양이 아니다
        raise SystemExit(f"시트 사양을 읽지 못했습니다({type(exc).__name__})") from exc
    if bad:
        raise SystemExit("시트 사양이 모자랍니다: " + " / ".join(bad))
    return spec, sheetmod.build_prompt(spec, from_photo=False)


def full_name(run, name):
    """짧게 부른 이름을 이 작품에서 쓰는 온전한 이름으로.

    정확히 같은 이름(시트든 인물 설정이든)이 먼저다. 「김 민수」와 「민수」가 따로 있으면 「김 민수」가 「민수」의 시트로
    미끄러지면 안 된다. 정확히 같은 이름이 없을 때만 짧게 부른 이름으로 찾는다.
    """
    name = " ".join((name or "").split())
    names = [n for n, _p in sheets_of(run)] + [str(p.get("name") or "") for p in cast_of(run)]
    if name in names:
        return name
    hit = find(run, name)
    person = person_of(run, name)
    return hit[0] if hit else str((person or {}).get("name") or name).strip()


def sheet_named(run, name):
    """그 사람의 시트 — 온전한 이름이 정확히 같은 것만. 짧은 이름으로 다른 사람의 시트를 집지 않는다."""
    full = full_name(run, name)
    return next(((n, png) for n, png in sheets_of(run) if n == full), None)


TITLES = ("황제", "황후", "황비", "황태자", "황자", "황녀", "대공", "공작", "후작", "백작", "자작", "남작", "영애", "영식", "부인",
          "재상", "대신", "대신관", "신관", "사제", "성녀", "교황", "기사", "단장", "근위대장", "시녀", "집사", "하인", "전령",
          "왕", "왕비", "왕자", "공주", "선생", "선배", "후배", "대표", "팀장", "사장", "의사", "형사", "경찰", "병사", "군의관")


def name_clashes(run, name, owner=""):
    """새 시트 이름에서 나오는 찾는 말(장면 글에서 이 사람을 찾는 말 — detailart._name_keys)이 다른 사람과 겹치는 곳.

    시트는 장면 글에 그 찾는 말이 나오면 붙는다. 「로제로 백작」의 「로제로」는 주인공 「엘리아나 드 로제로」에도, 직함 「재상」은
    재상이 된 다른 인물에도 걸려 엉뚱한 사람에게 시트가 붙을 뻔했다(2026-11-22). [{key, other, why}]
    - 시트가 있는 다른 사람의 더 긴 이름 안에 든 자리는 장 그림이 이미 그 사람으로 본다(detailart._named_in) — 겹침이 아니다.
    - 직함은 다른 사람도 그 직함으로 불릴 때만(이름이나 역할에 그 직함이 있을 때) 겹친다."""
    import detailart

    own = " ".join((name or "").split())
    sheeted = {p.stem for p in (run / "sheets").glob("*.png")}
    people = {}
    for c in cast_of(run):
        people[str(c.get("name") or "").strip()] = str(c.get("role") or "")
    for n in sheeted:
        people.setdefault(n, "")
    inp = lou.read(run / "input.json") if (run / "input.json").exists() else {}
    for who in [inp, *[o for o in inp.get("co") or [] if isinstance(o, dict)]]:
        people.setdefault(str(who.get("name") or "").strip(), str(who.get("role") or ""))
    owner = " ".join((owner or name or "").split())        # 같은 사람의 온전한 이름은 겹침이 아니다(「백작」은 「로제로 백작」 자신)
    people = {n: r for n, r in people.items() if n and n != owner}
    out = []
    if own != owner and own in people:
        out.append(dict(key=own, other=own, why="taken"))   # 다른 사람의 이름 그대로 — 그 사람의 시트 자리다
    words = lambda text: set(re.split(r"[\s·,]+", text or ""))
    for key in detailart._name_keys(own):
        for other, role in sorted(people.items()):
            if other == own:
                continue
            if key in TITLES:
                if key in words(other) or key in words(role):
                    out.append(dict(key=key, other=other, why="title"))
            elif key in other and other not in own and not (other in sheeted and len(other) > len(key)):
                out.append(dict(key=key, other=other, why="name"))
    return out


def safe_name(run, name):
    """겹치지 않는 시트 이름 하나(못 찾으면 빈 글) — 이름의 낱말 가운데 겹치지 않는 가장 긴 것.
    장면 글이 그 사람을 그 낱말로 부르므로 낱말을 붙여 새 말을 만들지는 않는다."""
    for cand in sorted({w for w in (name or "").split() if len(w) >= 2}, key=len, reverse=True):
        if not name_clashes(run, cand, name):
            return cand
    return ""


def drafts_dir(run):
    return run / "sheets" / "drafts"


def inside(path, folder):
    """path 가 folder 안의 파일인가(심볼릭 링크를 풀어서)."""
    try:
        Path(path).resolve().relative_to(Path(folder).resolve())
        return True
    except ValueError:
        return False


def in_drafts(run, path):
    """이 작품의 후보 자리 안인가. 후보 폴더 자체가 링크로 작품 밖을 가리키는 것도 막는다."""
    return Path(path).is_absolute() and inside(path, drafts_dir(run)) and inside(path, run)


def draft(run, request, out, dry=False):
    """후보 한 장. request: {method, target, base, refs, note, look} — 묻기가 돌려준 방법 하나.

    후보는 그 작품의 sheets/drafts/ 안에만 쓴다. 누구의 후보인지 옆에 남겨(.json) 고를 때 맞춰 본다.
    """
    plan = lou.read(request)
    lou.require(plan.get("method") in METHODS and lou.string(plan, "note") and lou.string(plan, "target"))
    lou.require(out.suffix == ".png" and in_drafts(run, out))
    found = [sheet_named(run, r) for r in plan.get("refs") or []]
    lou.require(all(found))
    base = (sheet_named(run, plan.get("base") or plan["target"])
            if plan["method"] == "sheet_keep" or plan.get("base") else None)
    lou.require(plan["method"] == "sheet_new" or base is not None)
    others = [(n, p) for n, p in found if not base or p != base[1]]
    if plan["method"] == "sheet_keep":
        line = (f"둘째 그림부터는 참고할 다른 인물의 시트다({', '.join(n for n, _p in others)}). "
                "「고칠 것」에서 가져오라고 한 부분만 가져온다.\n" if others else "")
        prompt = ((lou.PROMPTS / "sheet_keep_prompt").read_text(encoding="utf-8")
                  .replace("{refs}", line).replace("{note}", plan["note"]))
    else:
        if dry:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.with_suffix(".prompt.txt").write_text("(새로 그리기는 사양을 먼저 써야 해서 드라이런에서는 그림 지시를 못 만든다)\n"
                                                      + plan["note"], encoding="utf-8")
            return
        spec, prompt = _new_spec(run, plan)
        write_json(out.with_suffix(".spec.json"), spec)
        shown = ([base] if base else []) + others
        if shown:                # 다른 인물의 얼굴이나 옷을 가져올 때 — 사양 글만으로는 그 얼굴이 안 옮겨진다
            prompt += ("\n\n# 붙인 그림\n" + "\n".join(f"{i + 1}번째 그림: {n}의 캐릭터 시트" for i, (n, _p) in enumerate(shown))
                       + f"\n붙인 시트에서는 아래 요청이 가져오라고 한 것만 가져온다. 판형은 위 사양대로 그린다.\n{plan['note']}")
    refs = ([base[1]] if base else []) + [p for _n, p in others]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.with_suffix(".prompt.txt").write_text(prompt, encoding="utf-8")
    if dry:
        return
    meta = imagegen.paint("CAST_SHEET_IMAGE", prompt, out, refs=refs, kind=imagegen.SHEET_KIND)
    record(run, meta)
    write_json(out.with_suffix(".json"), {"target": full_name(run, plan["target"]), "method": plan["method"],
                                          "look": plan.get("look", "")})


def pick(run, request):
    """고른 후보를 시트로 건다. request: {target, from, look}. 지금 시트는 sheets/versions/ 에 지난 판으로 남긴다.

    from 은 이 작품의 sheets/drafts/ 안에서 같은 사람에게 그린 후보여야 한다. 두 사람이 동시에 골라도 지난 판 번호가
    겹치거나 그림과 설정 글이 서로 다른 후보에서 남지 않게, 지난 판 뜨기 · 걸기 · 설정 고치기를 한 잠금 안에서 한다.
    """
    import fcntl

    req = lou.read(request)
    source = Path(req.get("from") or "")
    lou.require(lou.string(req, "target") and source.suffix == ".png" and source.is_file() and in_drafts(run, source))
    made = source.with_suffix(".json")
    lou.require(made.is_file())
    # 후보를 그린 사람의 온전한 이름으로만 건다 — 고를 때 부른 짧은 이름이 다른 사람에게 미끄러지지 않게
    name = str(lou.read(made).get("target") or "")
    lou.require(name and full_name(run, req["target"]) == name)
    with Image.open(source) as image:
        image.verify()
    hit = next(((n, png) for n, png in sheets_of(run) if n == name), None)
    person = next((p for p in cast_of(run) if str(p.get("name") or "") == name), None)
    file_name = lou.string(req, "file_name")
    if hit is None:
        # 새 시트 — 파일 이름이 곧 장면 글에서 이 사람을 찾는 말이다. 다른 사람과 겹치면 다른 이름을 쓰게 한다.
        clashes = name_clashes(run, file_name or name, name)
        if clashes:
            hint = safe_name(run, name)
            raise SystemExit(f"시트 이름 「{file_name or name}」이 " + ", ".join(
                f"「{c['key']}」(「{c['other']}」에도 걸림)"
                for c in clashes) + (f" — 「{hint}」 같은 이름으로 다시 골라 주세요(file_name)." if hint else " — 다른 이름을 정해 주세요(file_name)."))
    dest = hit[1] if hit else run / "sheets" / f"{stem(file_name or name)}.png"
    hero = dest.resolve() == (run / "sheet.png").resolve()
    lock = run / "sheets" / ".pick.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    with open(lock, "w") as held:
        fcntl.flock(held, fcntl.LOCK_EX)
        # 새 시트가 다른 사람의 시트 파일을 덮지 않는다 — 잠금 안에서 본다(두 사람이 같은 새 이름을 동시에 골라도)
        lou.require(hit is not None or not dest.exists())
        if dest.exists():
            versions = run / "sheets" / "versions"          # 첫 주인공(sheet.png)의 지난 판도 같은 곳에
            versions.mkdir(parents=True, exist_ok=True)
            have = [int(m.group(1)) for m in (re.fullmatch(rf"{re.escape(dest.stem)}\.v(\d+)\.png", f.name)
                                               for f in versions.iterdir()) if m]
            shutil.copy2(dest, versions / f"{dest.stem}.v{max(have, default=0) + 1}.png")
        scratch = dest.with_name(dest.name + ".picking")
        shutil.copy2(source, scratch)
        scratch.replace(dest)
        spec = source.with_suffix(".spec.json")
        if spec.exists() and not hero:
            shutil.copy2(spec, dest.with_suffix(".spec.json"))
        look = lou.string(req, "look")
        if look and hero:                       # 첫 주인공의 생김새는 input.json 의 설명에 있다
            inp = lou.read(run / "input.json")
            inp["description"] = look
            write_json(run / "input.json", inp)
        elif look and person is not None:
            cast = cast_of(run)
            for one in cast:
                if one is not None and one.get("name") == person.get("name"):
                    one["look"] = look
            write_json(run / "cast.json", cast)
    return dest
