"""작가가 확인한 고정 설정 + 진행 상태로 다음 회차를 만든다.

앞 화 본문/장면은 과거의 근거이며 이번 화의 재현 지시가 아니다.
진행 상태를 모델 출력으로 자동 덮어쓰지 않는다. 수정은 다음 생성 전 작가가 한다.
"""
from pathlib import Path
import json
import shutil

FILES = ("input.json", "persona.json", "cast.json", "sheet.png", "sheet_spec.json", "lore.json")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def prepare_input(char, packet):
    source = Path(packet["source_dir"])
    if not (source / "sheet.png").is_file():
        raise ValueError("앞 화의 캐릭터 시트가 없습니다")
    original = read(source / "input.json") if (source / "input.json").is_file() else {}
    merged = {**original, **{k: char[k] for k in ("story", "settings", "title", "episode")}}
    if "lorebook" in char:
        merged["lorebook"] = char["lorebook"] or {}
    # 카드/사진 없이 만들었던 오래된 작품도 원본 시트를 참조한다.
    merged["name"] = original.get("name") or char.get("name")
    merged["description"] = original.get("description") or char.get("description", "")
    merged["genre"] = original.get("genre") or char.get("genre", "")
    merged["photos"] = [str((source / "sheet.png").resolve())]
    return merged


def initialize(run_dir, packet):
    source = Path(packet["source_dir"])
    if source.resolve() == Path(run_dir).resolve():
        raise ValueError("앞 화와 다음 화의 저장 위치가 같습니다")
    # 시트/인물만 승계한다. 앞 화의 scenes/pick/directions는 새 결과에 덮지 않는다.
    for name in FILES:
        if name in ("input.json", "lore.json"):
            continue
        if (source / name).is_file():
            shutil.copy2(source / name, run_dir / name)
    if (source / "sheets").is_dir():
        (run_dir / "sheets").mkdir(exist_ok=True)
        for item in (source / "sheets").iterdir():
            if item.is_file() and (item.suffix == ".png" or item.name.endswith(".spec.json")):
                shutil.copy2(item, run_dir / "sheets" / item.name)
    existing = read(source / "cast.json") if (source / "cast.json").is_file() else []
    existing = existing if isinstance(existing, list) else []
    cast_pick = read(source / "cast_pick.json") if (source / "cast_pick.json").is_file() else {}
    if cast_pick.get("name") and not cast_pick.get("all"):
        existing = [c for c in existing if c.get("name") == cast_pick["name"] or c.get("from_input") is True]
    by_name = {str(c.get("name") or "").strip(): c for c in existing if isinstance(c, dict) and c.get("name")}
    scene_data = read(source / "scenes.json") if (source / "scenes.json").is_file() else {}
    protagonist = str((read(source / "input.json") if (source / "input.json").is_file() else {}).get("name") or "")
    for c in scene_data.get("cast", []):
        if not isinstance(c, dict):
            continue
        name = str(c.get("name") or "").strip()
        if name and name != protagonist and name not in by_name:
            by_name[name] = {**c, "look": c.get("appearance") or c.get("look") or ""}
    (run_dir / "cast.json").write_text(json.dumps(list(by_name.values()), ensure_ascii=False), encoding="utf-8")
    directions = read(source / "directions.json") if (source / "directions.json").is_file() else []
    pick = read(source / "pick.json").get("n", 1) if (source / "pick.json").is_file() else 1
    chosen = next((d for d in directions if d.get("n") == pick), {})
    packet = {**packet, "source_body": chosen.get("body") or chosen.get("raw") or ""}
    (run_dir / "continuation.json").write_text(json.dumps(packet, ensure_ascii=False, indent=2), encoding="utf-8")
    if (source / "lore.json").is_file():
        lore = read(source / "lore.json")
        choices = lore.get("directions", [])
        selected = choices[pick-1] if isinstance(pick, int) and 0 < pick <= len(choices) else []
        lore["directions"] = [selected]
        (run_dir / "lore.json").write_text(json.dumps(lore, ensure_ascii=False), encoding="utf-8")


def memory_block(run_dir):
    p = read(run_dir / "continuation.json")
    return "\n".join([
        f"이번 회차: {p['episode_number']}화",
        "[고정 설정 — 항상 지킬 사실·제약·정보 공개 규칙]", p.get("fixed_settings", ""),
        "[작가가 확인한 현재 진행 상태 — 이번 화 시작 시점]", p.get("progress_state", ""),
        "현재 시점·관계·완료 사건은 이 진행 상태를 따른다. 고정 설정의 불변 제약은 유지한다.",
        "설정은 인물의 선택과 결과에 반영한다. 설정집을 대사나 나레이션으로 나열하지 않는다.",
        "작가만 아는 비밀과 인물이 아는 정보, 독자에게 공개해도 되는 정보를 구분한다.",
    ])


def story_input(run_dir, char, note=""):
    import own
    p = read(run_dir / "continuation.json")
    cast = read(run_dir / "cast.json") if (run_dir / "cast.json").is_file() else []
    persona = read(run_dir / "persona.json") if (run_dir / "persona.json").is_file() else {}
    return "\n".join([
        own.input_block(char, continuation=True), *own.persona_lines(persona), *own.cast_lines(cast),
        "위 인물 카드의 과거 처지보다 아래의 확인한 현재 상태를 우선한다.", memory_block(run_dir),
        "[앞 화 본문 — 이미 일어난 일. 다시 시작하거나 재현하지 않는다]", p.get("source_body", ""),
        "[앞 화 확정 장면 — 작가가 장면을 수정했다면 본문보다 이 기록을 따른다]",
        *p.get("source_scenes", []),
        "[이번 화에서 새로 전개할 내용]", char.get("story", ""),
        "[이번 수정 요청]", note or "",
    ])


def stage_story(run_dir, char, dry_run, note="", lang="ko"):
    import run as R
    import llm
    prompt = R.compose("series/story_prompt", story_input(run_dir, char, note), lang=lang)
    R.write_text(run_dir / "story_prompt.txt", prompt)
    if dry_run:
        return None
    call = llm.Call("STORY")
    try:
        text, meta = call(prompt)
    except BaseException as exc:
        R.record_error(run_dir, "STORY", call.provider, call.model, exc)
        raise
    R.record(run_dir, meta)
    R.write_text(run_dir / "story.md", text)
    directions = R.parse_directions(text)
    if not directions:
        raise ValueError("다음 화 본문을 읽지 못했습니다")
    d = directions[0]
    d["n"] = 1
    d["genre"] = d.get("genre") or char.get("genre", "")
    R.write_json(run_dir / "directions.json", [d])
    R.write_json(run_dir / "pick.json", {"n": 1, "title": d.get("title", ""), "genre": d["genre"]})
    return d
