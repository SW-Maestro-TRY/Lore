"""작가가 확인한 고정 설정 + 진행 상태로 다음 회차를 만든다.

앞 화 본문/장면은 과거의 근거이며 이번 화의 재현 지시가 아니다.
진행 상태를 모델 출력으로 자동 덮어쓰지 않는다. 수정은 다음 생성 전 작가가 한다.
"""
from pathlib import Path
import json
import re
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


def chosen_direction(source):
    """앞 화에서 고른 이야기. 후보 넷 중 하나를 고른 화(바로 만들기)든 하나뿐인 화든 같다."""
    source = Path(source)
    directions = read(source / "directions.json") if (source / "directions.json").is_file() else []
    pick = read(source / "pick.json").get("n", 1) if (source / "pick.json").is_file() else 1
    return pick, next((d for d in directions if isinstance(d, dict) and d.get("n") == pick), {})


def appeared_cast(source):
    """앞 화에 실제로 나온 사람만. 주인공은 뺀다.

    바로 만들기(후보 넷 중 고르기)의 cast.json 에는 **후보마다의 상대역이 다 들어 있다.**
    고른 이야기에 안 나온 사람까지 넘기면 다음 화가 그 사람을 이미 만난 인물로 쓴다
    (run 20261001T191238-4f6203: 1화에는 서이안만 나왔는데 루나·강도현·차유빈이 넘어갔다).
    그래서 고른 이야기의 상대역(lead), 본문에 이름이 나온 사람, 확정 장면의 등장인물만 남기고,
    같은 사람이 다른 이름(「루시앙 드 라비에」·「루시앙」)으로 두 번 들어가지 않게 합친다.
    """
    import run as R
    source = Path(source)
    original = read(source / "input.json") if (source / "input.json").is_file() else {}
    hero = str(original.get("name") or "").strip()
    _, chosen = chosen_direction(source)
    cast = read(source / "cast.json") if (source / "cast.json").is_file() else []
    cast = [c for c in cast if isinstance(c, dict) and str(c.get("name") or "").strip()] if isinstance(cast, list) else []
    cast_pick = read(source / "cast_pick.json") if (source / "cast_pick.json").is_file() else {}
    scene_cast = [c for c in ((read(source / "scenes.json") if (source / "scenes.json").is_file() else {}) or {}).get("cast", [])
                  if isinstance(c, dict) and str(c.get("name") or "").strip()]

    def same(a, b):
        return R._same_person(a, b) or R._same_person(b, a)

    def is_hero(name):
        # 한쪽으로만 본다 — 「한결 엄마」는 주인공 「한결」이 아니다.
        return bool(hero) and R._same_person(name, hero)

    keep = []
    def add(c):
        name = str(c.get("name") or "").strip()
        if is_hero(name):
            return
        hit = next((k for k in keep if same(k["name"], name)), None)
        if hit is None:
            keep.append({**c, "name": name, "look": c.get("look") or c.get("appearance") or ""})
        elif not hit.get("look") and (c.get("look") or c.get("appearance")):
            hit["look"] = c.get("look") or c.get("appearance")

    if cast_pick.get("name") and not cast_pick.get("all"):
        # 인물 단계에서 상대를 고른 화. 고른 사람과 사용자가 적은 사람만 이 화의 인물이다.
        for c in cast:
            if c.get("name") == cast_pick["name"] or c.get("from_input") is True:
                add(c)
    elif cast_pick.get("all") or (source / "continuation.json").is_file():
        # 2화 이후의 cast.json 은 앞 회차들에 이미 나온 사람만 모아 둔 것이다 — 이번 화에 안 나왔다고 빼지 않는다.
        for c in cast:
            add(c)
    else:
        lead = chosen.get("lead") if isinstance(chosen.get("lead"), dict) else {}
        text = " ".join(str(chosen.get(k) or "") for k in ("title", "intro", "body", "raw"))
        for c in cast:
            name = str(c.get("name")).strip()
            words = [w for w in re.split(r"[\s·]+", name) if len(w) >= 2]
            words += [w[1:] for w in words if len(w) == 3]      # 성을 뺀 이름
            if (lead.get("name") and same(name, str(lead["name"]))) or c.get("from_input") is True \
                    or any(same(str(k.get("name")), name) for k in scene_cast) or any(w in text for w in words):
                add(c)
    for c in scene_cast:
        add(c)
    return keep


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
    (run_dir / "cast.json").write_text(json.dumps(appeared_cast(source), ensure_ascii=False), encoding="utf-8")
    pick, chosen = chosen_direction(source)
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
    # 설정집(2층 로어북)으로 넘어온 연재는 고정 설정·진행 상태가 설정집 쪽에 있다 — 빈 칸은 안 적는다.
    lines = [f"이번 회차: {p['episode_number']}화"]
    if str(p.get("fixed_settings") or "").strip():
        lines += ["[고정 설정 — 항상 지킬 사실·제약·정보 공개 규칙]", p["fixed_settings"]]
    if str(p.get("progress_state") or "").strip():
        lines += ["[작가가 확인한 현재 진행 상태 — 이번 화 시작 시점]", p["progress_state"]]
    return "\n".join(lines + [
        "현재 시점·관계·완료 사건은 진행 상태를 따른다. 고정 설정과 항상 참고하는 설정의 제약은 유지한다.",
        "설정은 인물의 선택과 결과에 반영한다. 설정집을 대사나 나레이션으로 나열하지 않는다.",
        "작가만 아는 비밀과 인물이 아는 정보, 독자에게 공개해도 되는 정보를 구분한다.",
    ])


def world_lines(run_dir, char, p):
    """설정집 없이 넘어온 화(정리가 실패했거나 옛 화면)의 세계 — 1화 이야기 단계가 받던 것과 같은 것.

    설정집이 있으면 그쪽에 세계가 들어 있다(world_seed). 없으면 예전에는 2화 이야기 단계에 세계 설명도
    재료도 안 들어갔다.
    """
    if char.get("lorebook"):
        return []
    import run as R
    import lorebook
    lines = []
    world = R.world_text_for(char.get("genre", ""))
    if world:
        lines += ["## 이 세계의 배경 — 적힌 내용과 부딪히지 않는 곳에서만 쓴다", world]
    if lorebook.enabled():
        # initialize 가 앞 화에서 고른 방향의 배정만 1번 자리에 옮겨 둔다.
        lines += lorebook.scene_block(lorebook.scene_entries(run_dir, 1, p.get("source_body", "")))
    return lines


def story_input(run_dir, char, note=""):
    import own
    p = read(run_dir / "continuation.json")
    cast = read(run_dir / "cast.json") if (run_dir / "cast.json").is_file() else []
    persona = read(run_dir / "persona.json") if (run_dir / "persona.json").is_file() else {}
    return "\n".join([
        # 설정집 항목은 실험처럼 이번 요청 + 앞 화 원문에 걸린 키워드로 고른다.
        own.input_block(char, continuation=True, context="\n".join(
            [char.get("story", ""), p.get("source_body", ""), *p.get("source_scenes", [])])),
        *own.persona_lines(persona), *own.cast_lines(cast),
        "위 인물 카드의 과거 처지보다 아래의 확인한 현재 상태를 우선한다.", memory_block(run_dir),
        *world_lines(run_dir, char, p),
        "[앞 화 본문 — 이미 일어난 일. 다시 시작하거나 재현하지 않는다]", p.get("source_body", ""),
        "[앞 화 확정 장면 — 작가가 장면을 수정했다면 본문보다 이 기록을 따른다]",
        *p.get("source_scenes", []),
        "[이번 화에서 새로 전개할 내용]", char.get("story", "").strip()
        or "(작가가 비워 두었다 — 진행 상태의 아직 끝나지 않은 일에서 이어지는 다음 사건을 정한다.)",
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


# --------------------------------------------------------------------- 회차 정리
#
# 다음 화 버튼을 누르면 끝난 화를 읽고 2층 로어북을 갱신한다(orv-experiment 와 같은 방식, lore_extract).
# - 고정 로어북: 이 화 원문에서 항목·규칙을 뽑아, 이미 있는 것과 제목이 같지 않은 것만 「추가」로 붙인다.
# - 진행 상태: 지금까지의 진행 상태와 이 화 본문을 주고, 바뀐 것·지울 것만 받아 합친다.
# 사람은 틀린 줄을 빼기만 한다(화면). 이미 있는 항목·진행 상태를 모델이 통째로 다시 쓰지 않는다.

def digest_source(packet):
    """정리할 이번 화 원문 — 고른 본문과 확정 장면."""
    _, chosen = chosen_direction(packet["source_dir"])
    body = chosen.get("body") or chosen.get("raw") or ""
    scenes = [str(s) for s in packet.get("source_scenes") or []]
    if not scenes:
        data = read(Path(packet["source_dir"]) / "scenes.json") if (Path(packet["source_dir"]) / "scenes.json").is_file() else {}
        scenes = [json.dumps(s, ensure_ascii=False) for s in (data or {}).get("scenes", [])]
    return body, scenes


def episode_text(packet):
    n = packet.get("episode_number") or 1
    body, scenes = digest_source(packet)
    return "\n".join([f"[{n}화 본문]", body, "", f"[{n}화 확정 장면]", *scenes])


def world_seed(source):
    """1화가 서 있던 세계를 고정 로어북의 첫 항목으로 — 설정집을 안 붙이고 만든 1화만.

    설정집 없이 만든 1화는 장르의 세계 설명(worlds.json)과 고른 방향의 세계 재료(lorebook.json,
    lore.json 에 배정 기록)로 이야기·장면을 세웠다. 다음 화부터는 연재 설정집이 붙으므로 하네스가
    세계 설명·재료를 끈다(설정집이 세계를 대신한다는 규칙). 그대로 두면 2화에서 세계가 사라져서,
    1화가 실제로 쓴 것을 설정집에 옮겨 둔다 — 세계 설명은 항상 켜짐 세계 항목, 재료는 키워드 항목.
    사용자가 설정집을 붙여 만든 1화는 그 설정집이 세계라서 옮길 것이 없다.
    """
    import run as R
    import lorebook
    source = Path(source)
    original = read(source / "input.json") if (source / "input.json").is_file() else {}
    if original.get("lorebook"):
        return []
    pick, chosen = chosen_direction(source)
    genre = str(chosen.get("genre") or original.get("genre") or "").strip()
    out = []
    world = R.world_text_for(genre)
    if world:
        out.append({"id": "R000", "title": "세계 배경", "kind": "world", "keys": [], "content": world,
                    "always": True, "enabled": True})
    if lorebook.enabled():
        for e in lorebook.scene_entries(source, int(pick or 0), chosen.get("body") or chosen.get("raw") or ""):
            out.append({"id": "W-" + str(e["id"]), "title": e["name"], "kind": e.get("kind", ""),
                        "keys": list(e.get("keys") or []) or [e["name"]], "content": e["text"],
                        "always": False, "enabled": True})
    return out


def stage_digest(packet_path, out_path, dry_run, lang="ko"):
    import run as R
    import lore_extract as X
    packet = read(packet_path)
    out_path = Path(out_path)
    out_dir = out_path.parent
    previous = packet.get("previous") or {}
    kept = [e for e in previous.get("entries") or [] if isinstance(e, dict)]
    if not kept:
        kept = world_seed(packet["source_dir"])
    rows = X.progress_rows(previous.get("progress"))
    text = episode_text(packet)
    R.write_text(out_dir / "lore_book_prompt.txt", X.book_prompt(text, lang, kept))
    R.write_text(out_dir / "lore_progress_prompt.txt", X.progress_prompt(rows, text, lang))
    if dry_run:
        return None
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=2) as pool:
        added = pool.submit(X.extract, text, out_dir, lang, kept)
        progress = pool.submit(X.update_progress, rows, text, out_dir, lang)
        (rows_new, more, renamed), (merged, changes, removed) = added.result(), progress.result()
    result = {"entries": X.apply_book(kept, rows_new, more, renamed), "progress": merged,
              "progress_changes": changes, "progress_removed": removed}
    R.write_json(out_path, result)
    return result
