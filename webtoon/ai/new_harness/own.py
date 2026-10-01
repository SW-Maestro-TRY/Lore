"""「만들고 싶은 내용이 있어요」 — 사용자가 적은 내용을 그대로 웹툰으로 (#548).

지금 흐름(`run.py` 의 이야기 후보 4개 → 고르기 → 시트 → 장면 → 그림)은 이야기가
없는 사람의 것이다. 이 모듈은 이야기를 이미 가진 사람의 길이다 — 후보도 고르기도
없고, 적은 내용이 바로 본문·장면이 된다. 사용자는 그림이 그려지기 전에 장면을 보고
자유롭게 고친다(`save_edits`). 적은 것은 그대로 따르고, 안 적은 것은 AI 가 정한다.

**프롬프트와 단계는 기존 것과 따로 둔다** (`prompt/own/*`). 기존 단계를 고쳐 같이 쓰면
어느 길이 어느 규칙을 타는지 헷갈려서다. 공유하는 것은 바닥뿐이다 — 모델 호출,
파일 쓰기, 응답 파서(`parse_directions`·`parse_scenes`), 시트 그리기 배관.

한 호출(`run.py --own`)로 아래를 한다.

    PERSONA ∥ CAST ∥ STORY ∥ SHEET(사양 + 그림)     ← 동시에
                     ↓ (CAST·STORY 가 끝나면)
                  SCENES
    → persona.json · cast.json · directions.json(후보 1개) · pick.json(1번)
      · scenes.json · sheet.png  (기존과 같은 이름 — 자바와 그림 단계가 그대로 읽는다)

결과 파일 이름이 같으므로 그 뒤(그림·검수·업로드)는 기존 코드가 그대로 돈다.
"""
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import run as R                                   # noqa: E402 — 바닥(배관)만 빌린다
import llm                                        # noqa: E402
import imagegen                                   # noqa: E402
import sheet as sheetmod                          # noqa: E402
import failure                                    # noqa: E402
import charcard                                   # noqa: E402
from llm import story                             # noqa: E402

log, warn = R.log, R.warn

# 장면 하나를 사람이 읽는 한 글로 합칠 때의 칸 순서. 자바(JobView)도 같은 순서로
# 합친다 — 바꾸면 두 곳을 같이 바꾼다.
SCENE_TEXT_KEYS = ("where", "what", "acting", "look", "ends")


# --------------------------------------------------------------------- 입력

def settings_block(char: dict) -> list[str]:
    """「설정 더 적기」. 없으면 []."""
    text = str(char.get("settings") or "").strip()
    if not text:
        return []
    return ["", "## 사용자가 더 적은 설정 — 적힌 것이다, 바꾸지 않는다", "", text]


def content_block(char: dict) -> list[str]:
    """사용자가 적은 내용. 프롬프트 **맨 뒤**에 둔다 — 모델은 뒤에 온 것을 더 세게 듣는다."""
    lines = ["", "## 사용자가 적은 내용 — 이 웹툰의 중심", ""]
    title = str(char.get("title") or "").strip()
    if title:
        lines += [f"제목: {title}", ""]
    lines.append(R.user_story(char))
    return lines


def base_block(char: dict) -> list[str]:
    """주인공 입력 + 더 적은 설정. 네 단계가 공통으로 받는 앞부분."""
    return [R.input_block(char).rstrip("\n"), *settings_block(char)]


def cast_lines(cast: list[dict]) -> list[str]:
    out = ["", "## 적은 내용에서 읽어낸 인물들 — 이름·생김새·말투를 바꾸지 않는다"]
    if not cast:
        return out + ["(주인공 말고는 없다)"]
    for c in cast:
        out += ["", str(c.get("name")).strip(), *R._cast_lines(c)]
    return out


def persona_lines(p: dict | None) -> list[str]:
    if not p:
        return []
    out = ["", "## 주인공 카드 — 적힌 것으로 세운 것이다", "", f"주인공: {str(p.get('name')).strip()}"]
    for key, label in (("gender", "성별"), ("look", "생김새·인상"), ("personality", "성격"),
                       ("situation", "처지·관계·설정"), ("voice", "말투")):
        v = str(p.get(key) or "").strip()
        if v:
            out.append(f"- {label}: {v}")
    return out


# --------------------------------------------------------------------- 단계

def stage_persona(run_dir: Path, char: dict, dry_run: bool, lang: str = "ko") -> dict | None:
    """주인공 카드. 확인 화면에 보여 주기만 한다(생성에는 안 넣는다, #534 와 같다)."""
    path = run_dir / "persona.json"
    prompt = R.compose("own/persona_prompt", "\n".join(base_block(char) + content_block(char)) + "\n", lang=lang)
    R.write_text(run_dir / "persona_prompt.txt", prompt)
    if dry_run:
        log(f"[페르소나] 프롬프트만 썼습니다 -> {run_dir / 'persona_prompt.txt'}")
        return None
    call = llm.Call("PERSONA")
    log(f"[페르소나] {call.describe()} 로 주인공 카드를 세웁니다…")
    try:
        text, meta = call(prompt, images=llm.load_images(char.get("photos") or []))
    except BaseException as exc:                                      # noqa: BLE001
        R.record_error(run_dir, "PERSONA", call.provider, call.model, exc)
        warn(f"주인공 카드를 못 만들었습니다 — 없이 갑니다 ({exc})")
        return None
    R.write_text(run_dir / "persona_raw.txt", text)
    R.record(run_dir, meta)
    try:
        obj = story.extract_json(text)
    except Exception as exc:                                          # noqa: BLE001
        warn(f"주인공 카드 응답을 못 읽었습니다 — 없이 갑니다 ({exc})")
        return None
    if not isinstance(obj, dict) or not str(obj.get("name") or "").strip():
        return None
    kept, dropped = R.persona_details(obj.get("details"), R.persona_source_text(char))
    obj["details"] = kept
    if dropped:
        obj["dropped_details"] = dropped
    R.write_json(path, obj)
    return obj


def stage_cast(run_dir: Path, char: dict, dry_run: bool, lang: str = "ko") -> list[dict]:
    """적은 내용에서 주인공 아닌 인물을 읽어낸다. 0~4명. 새 인물은 코드에서도 뺀다."""
    prompt = R.compose("own/cast_prompt", "\n".join(base_block(char) + content_block(char)) + "\n", lang=lang)
    R.write_text(run_dir / "cast_prompt.txt", prompt)
    if dry_run:
        log(f"[인물] 프롬프트만 썼습니다 -> {run_dir / 'cast_prompt.txt'}")
        return []
    call = llm.Call("CAST")
    log(f"[인물] {call.describe()} 로 적은 내용의 인물을 읽어냅니다…")
    try:
        text, meta = call(prompt)
    except BaseException as exc:                                      # noqa: BLE001
        R.record_error(run_dir, "CAST", call.provider, call.model, exc)
        warn(f"인물을 못 읽어냈습니다 — 인물 없이 갑니다 ({exc})")
        return []
    R.write_text(run_dir / "cast_raw.txt", text)
    R.record(run_dir, meta)
    try:
        obj = story.extract_json(text)
        cast = [c for c in (obj.get("cast") or []) if isinstance(c, dict)
                and str(c.get("name") or "").strip()]
    except Exception as exc:                                          # noqa: BLE001
        warn(f"인물 응답을 못 읽었습니다 — 인물 없이 갑니다 ({exc})")
        return []
    seen: set[str] = set()
    cast = [c for c in cast if not (str(c.get("name")).strip() in seen
                                    or seen.add(str(c.get("name")).strip()))]
    # 이 길에서는 적힌 사람만이다. 모델이 더한 사람은 뺀다.
    extra = [c["name"] for c in cast if not R.from_input(c)]
    if extra:
        log(f"[인물] 적은 내용에 없는 인물은 뺍니다: {', '.join(extra)}")
    cast = [c for c in cast if R.from_input(c)][:4]
    R.write_json(run_dir / "cast.json", cast)
    return cast


def stage_story(run_dir: Path, char: dict, dry_run: bool, lang: str = "ko") -> dict | None:
    """적은 내용을 바꾸지 않고 1화 본문으로. 후보 1개를 directions.json 에, pick.json 은 1번."""
    prompt = R.compose("own/story_prompt", "\n".join(base_block(char) + content_block(char)) + "\n", lang=lang)
    R.write_text(run_dir / "story_prompt.txt", prompt)
    if dry_run:
        log(f"[이야기] 프롬프트만 썼습니다 -> {run_dir / 'story_prompt.txt'}")
        return None
    call = llm.Call("STORY")
    log(f"[이야기] {call.describe()} 로 적은 내용을 1화 본문으로 세웁니다…")
    try:
        text, meta = call(prompt, images=llm.load_images(char.get("photos") or []))
    except BaseException as exc:                                      # noqa: BLE001
        R.record_error(run_dir, "STORY", call.provider, call.model, exc)
        raise
    R.write_text(run_dir / "story.md", text)
    R.record(run_dir, meta)
    directions = R.parse_directions(text)
    if not directions:
        raise SystemExit(f"본문을 못 읽었습니다. 원문은 {run_dir / 'story.md'} 에 있습니다.")
    d = directions[0]
    d["n"] = 1
    title = str(char.get("title") or "").strip()
    if title:
        d["title"] = title                      # 사용자가 적은 제목이 먼저다
    if not d.get("genre"):
        d["genre"] = char.get("genre") or ""
    R.write_json(run_dir / "directions.json", [d])
    R.write_json(run_dir / "pick.json", {"n": 1, "title": d["title"], "genre": d["genre"]})
    return d


def stage_sheet(run_dir: Path, char: dict, dry_run: bool, note: str = "") -> None:
    """캐릭터 시트. 안전 검사에 걸리면 한 번은 사양부터 다시 쓴다(run.stage_sheet 와 같은 뼈대)."""
    failure.clear(run_dir)
    missing = [str(p) for p in char["photos"] if not Path(p).is_file()]
    if missing and not dry_run:
        failure.write(run_dir, "SHEET", "photo_missing", "사진 파일이 없습니다: " + ", ".join(missing))
        raise SystemExit("사진 파일이 없습니다: " + ", ".join(missing))
    safety = ""
    for attempt in (1, 2):
        try:
            _sheet_attempt(run_dir, char, dry_run, note, safety)
            return
        except BaseException as exc:                                  # noqa: BLE001
            cats = failure.refusal_categories(exc)
            if cats is None:
                failure.write(run_dir, "SHEET", "error", f"{type(exc).__name__}: {exc}")
                raise
            if attempt == 1:
                warn(f"[시트] 안전 검사에 걸렸습니다({', '.join(cats) or '분류 미상'}) — 사양부터 다시 씁니다")
                for name in ("sheet.png", "sheet_spec.json"):
                    (run_dir / name).unlink(missing_ok=True)
                safety = failure.safety_note(cats)
                continue
            failure.write(run_dir, "SHEET_IMAGE", "image_safety", f"{type(exc).__name__}: {exc}", cats)
            raise SystemExit(f"시트가 두 번 연속 안전 검사에 걸렸습니다({', '.join(cats)})") from exc


def _sheet_attempt(run_dir: Path, char: dict, dry_run: bool, note: str, safety: str) -> None:
    photos = char["photos"]
    block = R.input_block(char)
    note = (note or "").strip()
    if note:
        block += f"\n\n## 이번 시도에 추가로 반영할 것\n사용자가 방금 다시 만들기를 요청하며 남긴 말이다. 가능한 한 반영한다:\n{note}"
    if safety:
        block += "\n\n" + safety
    prompt = R.compose("own/sheet_prompt", block)
    R.write_text(run_dir / "sheet_spec_prompt.txt", prompt)

    spec_path = run_dir / "sheet_spec.json"
    if spec_path.exists():
        log(f"[시트] 사양이 이미 있습니다 -> {spec_path} (재사용)")
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
    elif dry_run:
        log(f"[시트] 사양 프롬프트만 썼습니다 -> {run_dir / 'sheet_spec_prompt.txt'}")
        return
    else:
        call = llm.Call("SHEET")
        log(f"[시트] {call.describe()} 로 사양을 적습니다…")
        try:
            text, meta = call(prompt, images=llm.load_images(photos), temperature=0.4)
        except BaseException as exc:                                  # noqa: BLE001
            R.record_error(run_dir, "SHEET", call.provider, call.model, exc)
            raise
        R.record(run_dir, meta)
        spec = sheetmod.parse_spec(text)
        bad = sheetmod.gate_spec(spec)
        if bad:
            R.write_json(run_dir / "sheet_spec_rejected.json", spec)
            raise SystemExit("시트 사양이 모자랍니다 — 그리기 전에 멈춥니다:\n  - " + "\n  - ".join(bad))
        R.write_json(spec_path, spec)

    image_prompt = sheetmod.build_prompt(spec, from_photo=bool(photos))
    R.write_text(run_dir / "sheet_prompt.txt", image_prompt)
    if dry_run:
        log(f"[시트] 이미지 프롬프트만 썼습니다 -> {run_dir / 'sheet_prompt.txt'}")
        return
    out = run_dir / "sheet.png"
    if out.exists():
        log(f"[시트] {out} 가 이미 있습니다. 다시 뽑으려면 지우세요.")
        return
    log("[시트] 그리는 중… (사진 없이 사양만)")
    provider, model, _q = imagegen.backend_for("SHEET_IMAGE")
    try:
        meta = sheetmod.paint(image_prompt, out)
    except BaseException as exc:                                      # noqa: BLE001
        R.record_error(run_dir, "SHEET_IMAGE", provider, model, exc)
        raise
    R.record(run_dir, meta)
    log(f"  -> {out}")


def scenes_input(run_dir: Path, char: dict, note: str = "") -> str:
    direction = R.direction_of(run_dir) or {}
    cast = R.read_json(run_dir / "cast.json") if (run_dir / "cast.json").exists() else []
    persona = R.read_json(run_dir / "persona.json") if (run_dir / "persona.json").exists() else None
    lines = ["# 이번 입력", "", "[본문 — 적은 내용을 1화로 세운 것]", direction.get("title", ""), "",
             direction.get("body") or direction.get("raw", "")]
    lines += ["", "[캐릭터]", f"{char['name']} — {char.get('description') or ''}".rstrip(" —")]
    if charcard.short(char.get("card") or {}):
        lines.append(f"- 고른 캐릭터 카드: {charcard.short(char['card'])} (원래 설명과 다르면 카드를 따른다)")
    for k, v in (char.get("fields") or {}).items():
        lines.append(f"- {k}: {v}")
    lines += persona_lines(persona if isinstance(persona, dict) else None)
    lines += cast_lines(cast if isinstance(cast, list) else [])
    lines += settings_block(char)
    genre = (direction.get("genre") or char.get("genre") or "").strip()
    if genre:
        lines += ["", f"[장르] {genre}"]
        world = R.world_text_for(genre)
        if world:
            lines += ["", "## 이 세계의 배경 — 적힌 내용과 부딪히지 않는 곳에서만 쓴다", "", world]
    note = (note or "").strip()
    if note:
        lines += ["", "## 이번에 다시 나누며 반영할 것 — 사용자가 남긴 말", note]
    lines += content_block(char)
    return "\n".join(lines) + "\n"


def stage_scenes(run_dir: Path, char: dict, dry_run: bool, note: str = "", lang: str = "ko") -> dict | None:
    """본문 + 인물 + 원문 → 장면. scenes.json 에 쓴다(기존과 같은 모양)."""
    prompt = R.compose("own/scene_prompt", scenes_input(run_dir, char, note), lang=lang)
    R.write_text(run_dir / "scene_prompt.txt", prompt)
    if dry_run:
        log(f"[장면] 프롬프트만 썼습니다 -> {run_dir / 'scene_prompt.txt'}")
        return None
    call = llm.Call("SCENE")
    log(f"[장면] {call.describe()} 로 적은 내용을 장면으로 나눕니다…")
    try:
        text, meta = call(prompt)
    except BaseException as exc:                                      # noqa: BLE001
        R.record_error(run_dir, "SCENE", call.provider, call.model, exc)
        raise
    R.write_text(run_dir / "scene.md", text)
    R.record(run_dir, meta)
    parsed = R.parse_scenes(text)
    if len(parsed["scenes"]) < 4:
        warn(f"장면을 {len(parsed['scenes'])}개만 읽었습니다 (4개 이상이어야 합니다). "
             f"원문은 {run_dir / 'scene.md'} 에 그대로 있습니다.")
    R.write_json(run_dir / "scenes.json", parsed)
    return parsed


# --------------------------------------------------------------------- 묶음

def run_own(run_dir: Path, char: dict, dry_run: bool, lang: str = "ko") -> None:
    """PERSONA ∥ CAST ∥ STORY ∥ SHEET → SCENES. 자바가 `--own` 한 번으로 부른다."""
    if not R.user_story(char):
        raise SystemExit("적은 내용이 없습니다 — 「만들고 싶은 내용이 있어요」는 내용이 있어야 합니다.")
    log("[내 내용으로] 주인공 카드·인물·본문·시트를 동시에 만듭니다…")
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = {
            "persona": pool.submit(stage_persona, run_dir, char, dry_run, lang),
            "cast": pool.submit(stage_cast, run_dir, char, dry_run, lang),
            "story": pool.submit(stage_story, run_dir, char, dry_run, lang),
            "sheet": pool.submit(stage_sheet, run_dir, char, dry_run),
        }
        errors: dict[str, BaseException] = {}
        for name, fut in jobs.items():
            try:
                fut.result()
            except BaseException as exc:                              # noqa: BLE001
                errors[name] = exc
    # 본문·시트가 없으면 뒤를 못 간다. 카드·인물은 없어도 간다(위에서 경고만).
    for name in ("story", "sheet"):
        if name in errors:
            raise errors[name]
    if dry_run:
        # 본문이 없어서 장면 프롬프트를 못 짠다 — 프롬프트 파일만 보려는 길이다.
        (run_dir / "directions.json").exists() or R.write_json(
            run_dir / "directions.json", [{"n": 1, "title": "", "genre": char.get("genre", ""),
                                           "intro": "", "body": R.user_story(char), "raw": ""}])
        (run_dir / "pick.json").exists() or R.write_json(run_dir / "pick.json", {"n": 1, "title": "", "genre": ""})
    stage_scenes(run_dir, char, dry_run, lang=lang)


def scene_text(scene: dict) -> str:
    """장면 하나를 사람이 읽는 한 글로. 사용자가 고친 글이 있으면 그것."""
    if (scene.get("user_text") or "").strip():
        return scene["user_text"].strip()
    parts = [str(scene.get(k) or "").strip() for k in SCENE_TEXT_KEYS]
    parts = [p for p in parts if p and p not in ("시트 그대로", "시트 그대로.", "없음", "없음.")]
    narration = scene.get("narration")
    if narration:
        parts.append("나레이션: " + " / ".join(str(n) for n in narration))
    return "\n".join(parts)


def save_edits(run_dir: Path, edits: dict) -> None:
    """사용자가 고친 장면 글(과 본문·제목)을 적는다. `{scenes:[{n,text}], body?, title?}`.

    고친 글은 `user_text` 로 남고, 그림 단계가 그것을 「장면 내용」으로 쓴다
    (`detailart.build_continue_prompt`). 안 적힌 것은 그대로 AI 가 정한다. 글의 마지막
    줄을 이 장면의 「끝나는 상태」와 다음 장면의 「직전 상태」로 맞춘다 — 앞뒤 장이
    이어지는 자리는 거기뿐이다.
    """
    path = run_dir / "scenes.json"
    if not path.exists():
        raise SystemExit(f"장면이 없습니다: {path}")
    parsed = R.read_json(path)
    scenes = parsed.get("scenes") or []
    by_n = {s.get("n"): s for s in scenes}
    changed = 0
    for one in edits.get("scenes") or []:
        n = one.get("n")
        text = str(one.get("text") or "").strip()
        s = by_n.get(n)
        if s is None:
            warn(f"[저장] {n}번 장면이 없습니다 — 건너뜁니다")
            continue
        if not text or text == scene_text({**s, "user_text": ""}):
            s.pop("user_text", None)        # 비우거나 원래대로 돌렸다 — AI 글로
            changed += 1
            continue
        s["user_text"] = text
        last = [ln.strip() for ln in text.splitlines() if ln.strip()][-1]
        s["ends"] = last
        nxt = by_n.get((n or 0) + 1)
        if nxt is not None:
            nxt["prev"] = last
        changed += 1
    R.write_json(path, parsed)
    body = str(edits.get("body") or "").strip()
    title = str(edits.get("title") or "").strip()
    if body or title:
        dpath = run_dir / "directions.json"
        directions = R.read_json(dpath) if dpath.exists() else []
        if directions:
            if body:
                directions[0]["body"] = body
            if title:
                directions[0]["title"] = title
                ppath = run_dir / "pick.json"
                pick = R.read_json(ppath) if ppath.exists() else {"n": 1, "genre": directions[0].get("genre", "")}
                pick["title"] = title
                R.write_json(ppath, pick)
            R.write_json(dpath, directions)
    log(f"[저장] 장면 {changed}개 반영 -> {path}"
        + (" · 본문도 바꿨습니다" if body else "") + (" · 제목도 바꿨습니다" if title else ""))


def rescenes(run_dir: Path, char: dict, dry_run: bool, note: str = "", lang: str = "ko") -> None:
    """본문·인물·시트는 두고 장면만 다시 나눈다. 고친 글(user_text)은 사라진다."""
    stage_scenes(run_dir, char, dry_run, note=note, lang=lang)
