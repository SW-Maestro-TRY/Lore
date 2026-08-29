#!/usr/bin/env python3
"""new_harness — 사진·설명·장르로 1화 이야기와 콘티, 그리고 캐릭터 시트를 만든다.

    입력  ->  이야기 후보 4개  ->  (사람이 하나 고름)  ->  콘티  ->  캐릭터 시트
            prompt/story_prompt              prompt/storyboard_prompt
                                                          prompt/sheet_prompt

이야기는 story-harness 를 거치지 않는다. prompt/ 안의 프롬프트가 전부다.
캐릭터 시트의 이미지 호출만 story-harness 것을 빌려 쓴다 (sheet.py 참고).

사용법
  python run.py --plan                                 # 어느 단계가 어느 모델인지
  python run.py --character ../landing/jobs/<id>/character.json
  python run.py --name 이하은 --photo a.png --desc "..." --genre 판타지
  python run.py --run-id <id> --pick 2                 # 후보 고르고 콘티까지
  python run.py --run-id <id> --sheet                  # 캐릭터 시트
  python run.py --name ... --photo a.png --all --pick 2   # 한 번에
  아무 명령에나 --dry-run 을 붙이면 프롬프트만 쓰고 호출은 안 한다 (0원).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import llm                                    # noqa: E402
import pages as pagemod                       # noqa: E402
import sheet as sheetmod                      # noqa: E402
from llm import story                         # noqa: E402
from pages import SIZES                       # noqa: E402

PROMPT_DIR = HERE / "prompt"
RUNS_DIR = HERE / "runs"

# landing 의 폼과 같은 칸. 여기 없는 칸은 설명 본문에 섞여 들어온다.
FIELD_KEYS = ("나이", "성별", "직업", "성격", "말투", "과거", "관계", "약점")

log, warn = story.log, story.warn


# --------------------------------------------------------------------- 입력

def read_character(path: Path) -> dict:
    """landing 이 쓰는 character.json 을 그대로 읽는다.

    폴더를 주면 그 안의 character.json 을 찾는다 — jobs/<id> 를 그대로
    넘길 수 있게 하기 위해서다.

    story(줄거리)는 **읽고 버린다.** story_prompt 가 "줄거리는 받지 않는다,
    네가 새로운 이야기를 만들어야 한다" 고 못 박고 있어서, 넘기면 프롬프트와
    입력이 서로 반대를 말하게 된다.
    """
    if path.is_dir():
        path = path / "character.json"
    if not path.exists():
        raise SystemExit(f"캐릭터 파일이 없습니다: {path}")
    doc = json.loads(path.read_text(encoding="utf-8"))

    photo = doc.get("photo")
    photos = [photo] if isinstance(photo, str) else list(photo or [])
    fields = doc.get("fields") if isinstance(doc.get("fields"), dict) else {}
    return normalize({
        "name": doc.get("name"),
        "description": doc.get("character"),
        "fields": {k: str(fields.get(k) or "").strip() for k in FIELD_KEYS},
        "genre": doc.get("genre"),
        "photos": photos,
        "photo_note": doc.get("photo_note"),
    })


def normalize(raw: dict) -> dict:
    """빈 칸은 빈 칸으로 둔다. 코드가 기본값을 채우면 작가가 준 것과 섞인다."""
    fields = {k: str(v).strip() for k, v in (raw.get("fields") or {}).items()
              if str(v or "").strip()}
    photos = []
    for p in raw.get("photos") or []:
        path = Path(p)
        if not path.exists():
            warn(f"사진을 찾지 못했습니다: {path}")
            continue
        photos.append(str(path.resolve()))
    return {
        "name": str(raw.get("name") or "").strip(),
        "description": str(raw.get("description") or "").strip(),
        "fields": fields,
        "genre": str(raw.get("genre") or "").strip(),
        "photos": photos,
        "photo_note": str(raw.get("photo_note") or "").strip(),
    }


def gate_input(char: dict) -> list[str]:
    """story_prompt 가 필수라고 적은 것만 본다 — 이름과 외관."""
    bad = []
    if not char["name"]:
        bad.append("캐릭터 이름이 없습니다 (필수).")
    if not char["photos"] and not char["description"] and not char["fields"]:
        bad.append("외관이 없습니다 — 사진이나 설명 중 하나는 있어야 합니다.")
    return bad


def input_block(char: dict, *, with_genre: bool = True) -> str:
    """프롬프트 뒤에 붙는 이번 입력."""
    lines = ["# 이번 입력", "", f"캐릭터 이름: {char['name']}"]

    if char["photos"]:
        n = len(char["photos"])
        note = f" ({char['photo_note']})" if char["photo_note"] else ""
        lines.append(f"외관: 첨부한 사진 {n}장을 보라{note}.")
    else:
        lines.append("외관: (사진 없음 — 아래 설명에서 읽는다)")

    if char["description"] or char["fields"]:
        lines += ["", "설명:"]
        if char["description"]:
            lines.append(char["description"])
        for k, v in char["fields"].items():
            lines.append(f"- {k}: {v}")
    else:
        lines += ["", "설명: (없음 — 네가 정한다)"]

    if with_genre:
        lines += ["", f"장르: {char['genre']}" if char["genre"]
                  else "장르: (없음 — 네가 정한다)"]
    return "\n".join(lines) + "\n"


def load_prompt(name: str) -> str:
    path = PROMPT_DIR / name
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit(f"프롬프트가 비어 있습니다: {path}")
    return text


def compose(prompt_name: str, block: str) -> str:
    """프롬프트 + 이번 입력. 입력은 **뒤**에 붙인다 — 모델은 뒤에 온 것을 더 세게 듣는다."""
    return f"{load_prompt(prompt_name)}\n\n---\n\n{block}"


# --------------------------------------------------------------------- 파싱
#
# 두 프롬프트는 사람이 읽는 마크다운을 낸다. JSON 으로 바꿔 달라고 덧붙이지
# 않는 이유: 프롬프트마다 형식과 최종 확인 목록이 이미 마크다운으로 못 박혀
# 있어서, 뒤에서 형식을 뒤집으면 그 목록 전체가 프롬프트와 어긋난다.
# 그래서 원문(.md)을 그대로 남기고, 골라야 하는 만큼만 여기서 잘라 읽는다.

# 줄 안의 공백만 허용한다 — \s 를 쓰면 줄바꿈까지 먹어서, 값이 빈 줄
# ("인물:" 처럼 뒤가 비는 줄)에서 **다음 줄을 값으로 집어간다.**
S = r"[ \t]*"

DIRECTION_RE = re.compile(rf"^##{S}방향{S}(\d+){S}[—–\-:]?{S}(.*)$", re.M)
SECTION_RE = re.compile(rf"^###{S}(.+?){S}$", re.M)
GENRE_RE = re.compile(rf"^{S}장르{S}[:：]{S}(.+?){S}$", re.M)
BULLET_RE = re.compile(rf"^{S}(?:[-*·]|\d+[.)]){S}(.+?){S}$", re.M)


def _sections(body: str) -> dict:
    """### 로 나뉜 토막들."""
    out, marks = {}, list(SECTION_RE.finditer(body))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(body)
        out[m.group(1).strip()] = body[m.end():end].strip()
    return out


def _bullets(text: str) -> list[str]:
    """번호나 - 로 시작하는 줄. 없으면 빈 줄로 나눈 문단."""
    hits = [m.group(1).strip() for m in BULLET_RE.finditer(text)]
    if hits:
        return hits
    return [ln.strip() for ln in text.splitlines() if ln.strip()]


def parse_directions(md: str) -> list[dict]:
    """story_prompt 의 응답에서 방향 4개를 잘라 읽는다."""
    marks = list(DIRECTION_RE.finditer(md))
    out = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(md)
        body = md[m.end():end]
        sec = _sections(body)
        genre = GENRE_RE.search(body.split("###")[0])
        out.append({
            "n": int(m.group(1)),
            "title": m.group(2).strip(),
            "genre": genre.group(1).strip() if genre else "",
            "plot": sec.get("줄거리", "").strip(),
            "scenes": _bullets(sec.get("장면 목록", "")),
            "hidden": _bullets(sec.get("밝히지 않은 것", "")),
            "raw": (m.group(0) + body).strip(),
        })
    return out


def _num(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _text(value) -> str:
    return str(value).strip() if value not in (None, "") else ""


def _dicts(value) -> list[dict]:
    return [v for v in (value or []) if isinstance(v, dict)]


def parse_board(text: str) -> dict:
    """storyboard_prompt 의 응답(JSON) -> {"cast": [...], "scenes": [...]}.

    모양만 맞추고 값은 안 고친다. 여기서 빈칸을 채우면 모델이 안 적은 것과
    코드가 지어낸 것이 섞이고, 그건 다음 단계(이미지 프롬프트)가 구별할 수
    없다. 없는 것은 없는 채로 내려보낸다.

    번호가 없으면 나온 순서로 매긴다 — 컷 순서는 이 뒤로 계속 쓰이는 값이라
    비워 두면 페이지 묶기부터 어긋난다.
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("콘티가 JSON 객체가 아닙니다.")

    cast = [{"name": _text(c.get("name")), "appearance": _text(c.get("appearance"))}
            for c in _dicts(obj.get("cast"))]

    scenes = []
    for si, scene in enumerate(_dicts(obj.get("scenes")), 1):
        cuts = []
        for ci, cut in enumerate(_dicts(scene.get("cuts")), 1):
            camera = cut.get("camera") if isinstance(cut.get("camera"), dict) else {}
            background = (cut.get("background")
                          if isinstance(cut.get("background"), dict) else {})
            cuts.append({
                "id": _num(cut.get("id"), ci),
                "size": _text(cut.get("size")).lower(),
                "camera": {k: _text(camera.get(k)) for k in ("shot", "angle", "facing")},
                "background": {k: _text(background.get(k)) for k in ("type", "desc")},
                "characters": _dicts(cut.get("characters")),
                # order 가 곧 읽는 순서다. 빠진 것은 나온 자리로 채워 뒤로 민다.
                "dialogue": sorted(_dicts(cut.get("dialogue")),
                                   key=lambda d: _num(d.get("order"), 10_000)),
                "sfx": _dicts(cut.get("sfx")),
                "forbid": [_text(f) for f in (cut.get("forbid") or []) if _text(f)],
                "note": _text(cut.get("note")),
            })
        scenes.append({
            "id": _num(scene.get("id"), si),
            "summary": _text(scene.get("summary")),
            "location": _text(scene.get("location")),
            "time": _text(scene.get("time")),
            "cuts": cuts,
        })
    return {"cast": cast, "scenes": scenes}


def gate_board(board: dict) -> list[str]:
    """그림으로 넘기기 전에 비면 안 되는 칸만 본다.

    콘티 프롬프트의 "내보내기 전에 확인" 중 **코드가 판정할 수 있는 것**만
    옮겼다. 좌우가 장면 안에서 유지됐는지 같은 것은 여기서 본다 — 사람이
    페이지를 다 그린 뒤에 발견하면 다시 그리는 값이 비싸다.
    """
    bad = []
    scenes = board.get("scenes") or []
    if not scenes:
        return ["장면이 하나도 없습니다."]

    for scene in scenes:
        where = f"장면 {scene['id']}"
        if not scene["location"]:
            bad.append(f"{where}: location 이 없습니다.")
        if not scene["cuts"]:
            bad.append(f"{where}: 컷이 없습니다.")

        seats = {}          # 이름 -> 좌우. 한 장면 안에서 안 바뀌어야 한다
        for cut in scene["cuts"]:
            spot = f"{where} 컷 {cut['id']}"
            if cut["size"] not in SIZES:
                bad.append(f"{spot}: size 가 '{cut['size']}' 입니다 "
                           f"({' / '.join(SIZES)}).")
            people = cut["characters"]
            for who in people:
                name = _text(who.get("name"))
                if not who.get("moment"):
                    bad.append(f"{spot}: {name or '이름 없음'} 에 moment 가 없습니다.")
                pos = _text(who.get("position"))
                if len(people) > 1 and not pos:
                    bad.append(f"{spot}: 인물이 둘 이상인데 {name} 에 position 이 "
                               "없습니다.")
                if pos and name:
                    if seats.setdefault(name, pos) != pos:
                        bad.append(f"{spot}: {name} 의 좌우가 장면 안에서 바뀝니다 "
                                   f"({seats[name]} -> {pos}).")
            for line in cut["dialogue"]:
                if not _text(line.get("text")):
                    bad.append(f"{spot}: 대사에 text 가 비어 있습니다.")
            for one in cut["sfx"]:
                if not _text(one.get("text")):
                    bad.append(f"{spot}: 효과음에 text 가 비어 있습니다.")
    return bad


# --------------------------------------------------------------------- run

def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def load_meta(run_dir: Path) -> dict:
    path = run_dir / "meta.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"run_id": run_dir.name, "calls": []}


def record(run_dir: Path, call_meta: dict) -> None:
    meta = load_meta(run_dir)
    meta["calls"].append(call_meta)
    write_json(run_dir / "meta.json", meta)
    cost = call_meta.get("cost") or {}
    log(f"  {call_meta['stage']}  {call_meta['provider']}:{call_meta['model']}  "
        f"{story.cost_text(cost.get('total'))}")


def read_input(run_dir: Path) -> dict:
    path = run_dir / "input.json"
    if not path.exists():
        raise SystemExit(f"{path} 가 없습니다. 먼저 이야기 단계를 돌리세요.")
    return json.loads(path.read_text(encoding="utf-8"))


def stage_story(run_dir: Path, char: dict, dry_run: bool) -> list[dict]:
    prompt = compose("story_prompt", input_block(char))
    write_text(run_dir / "story_prompt.txt", prompt)
    if dry_run:
        log(f"[이야기] 프롬프트만 썼습니다 -> {run_dir / 'story_prompt.txt'}")
        return []

    call = llm.Call("STORY")
    log(f"[이야기] {call.describe()} 로 후보 4개를 만듭니다…")
    text, meta = call(prompt, images=llm.load_images(char["photos"]))
    write_text(run_dir / "story.md", text)
    record(run_dir, meta)

    directions = parse_directions(text)
    if len(directions) != 4:
        warn(f"방향을 {len(directions)}개만 읽었습니다 (4개여야 합니다). "
             f"원문은 {run_dir / 'story.md'} 에 그대로 있습니다.")
    write_json(run_dir / "directions.json", directions)
    return directions


def show_directions(directions: list[dict]) -> None:
    for d in directions:
        genre = f"  [{d['genre']}]" if d["genre"] else ""
        print(f"\n── {d['n']}. {d['title']}{genre}")
        if d["plot"]:
            print(f"   {d['plot'].splitlines()[0]}")
        for s in d["scenes"]:
            print(f"     · {s}")


def choose(directions: list[dict], pick: int | None) -> dict:
    if pick is not None:
        for d in directions:
            if d["n"] == pick:
                return d
        raise SystemExit(f"방향 {pick} 이 없습니다 (있는 것: "
                         f"{[d['n'] for d in directions]}).")
    show_directions(directions)
    while True:
        answer = input("\n어느 방향으로 갈까요? 번호: ").strip()
        for d in directions:
            if answer == str(d["n"]):
                return d
        print("목록에 있는 번호를 넣으세요.")


def board_block(char: dict, direction: dict, run_dir: Path) -> str:
    """콘티 단계의 입력 — 장면 목록 · 캐릭터 정보 · 장르."""
    lines = ["# 이번 입력", "", "## 장면 목록", ""]
    lines += [f"{i}. {s}" for i, s in enumerate(direction["scenes"], 1)]

    lines += ["", "## 캐릭터 정보", "", f"이름: {char['name']}"]
    if char["photos"]:
        lines.append(f"외관: 첨부한 사진 {len(char['photos'])}장을 보라.")

    # 시트를 먼저 뽑았으면 그 사양을 같이 준다 — 콘티가 새 인물의 외관을
    # 확정할 때(storyboard_prompt 9번) 주인공만 다른 사람이 되는 것을 막는다.
    spec_path = run_dir / "sheet_spec.json"
    if spec_path.exists():
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        lines.append(f"외관(확정): {spec['appearance_en']}")
        for d in spec.get("design_details") or []:
            lines.append(f"- 고정 요소: {d}")
        for p in spec.get("props") or []:
            lines.append(f"- 소지품: {p}")

    if char["description"]:
        lines += ["", f"설명: {char['description']}"]
    for k, v in char["fields"].items():
        lines.append(f"- {k}: {v}")

    lines += ["", "## 장르", "", direction["genre"] or char["genre"] or "(정해진 것 없음)"]
    lines += ["", "## 줄거리 (배경 이해용 — 이대로 나누라는 뜻은 아니다)", "",
              direction["plot"]]
    if direction["hidden"]:
        lines += ["", "## 밝히지 않을 것 — 컷에서 답을 보여주지 마라", ""]
        lines += [f"- {h}" for h in direction["hidden"]]
    return "\n".join(lines) + "\n"


def stage_board(run_dir: Path, char: dict, direction: dict, dry_run: bool) -> None:
    write_json(run_dir / "pick.json", {"n": direction["n"], "title": direction["title"],
                                       "genre": direction["genre"]})
    prompt = compose("storyboard_prompt", board_block(char, direction, run_dir))
    write_text(run_dir / "board_prompt.txt", prompt)
    if dry_run:
        log(f"[콘티] 프롬프트만 썼습니다 -> {run_dir / 'board_prompt.txt'}")
        return

    call = llm.Call("BOARD")
    log(f"[콘티] {call.describe()} 로 방향 {direction['n']} 을 컷으로 나눕니다…")
    text, meta = call(prompt, images=llm.load_images(char["photos"]))
    # 원문을 먼저 남긴다. 아래에서 파싱이 죽어도 응답은 안 사라진다.
    write_text(run_dir / "board_raw.txt", text)
    record(run_dir, meta)

    board = parse_board(text)
    write_json(run_dir / "board.json", board)

    scenes = board["scenes"]
    total = sum(len(s["cuts"]) for s in scenes)
    log(f"  장면 {len(scenes)}개 · 컷 {total}개 -> {run_dir / 'board.json'}")

    # 게이트는 **멈추지 않고 알린다.** 좌우가 한 번 어긋난 것 때문에 콘티
    # 전체를 버리게 하면, 고쳐 쓰면 될 것을 다시 뽑느라 또 돈을 쓴다.
    bad = gate_board(board)
    if bad:
        warn(f"콘티에 손볼 곳이 {len(bad)}개 있습니다 (그리기 전에 보세요):")
        for one in bad:
            warn(f"  - {one}")
        write_json(run_dir / "board_issues.json", bad)

    pages = pagemod.group_pages(pagemod.flatten_cuts(scenes))
    write_json(run_dir / "pages.json", pages)
    log(f"  페이지 {len(pages)}장 -> {run_dir / 'pages.json'}")


def stage_sheet(run_dir: Path, char: dict, dry_run: bool) -> None:
    photos = char["photos"]
    prompt = compose("sheet_prompt", input_block(char))
    write_text(run_dir / "sheet_spec_prompt.txt", prompt)

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
        text, meta = call(prompt, images=llm.load_images(photos), temperature=0.4)
        record(run_dir, meta)
        spec = sheetmod.parse_spec(text)
        bad = sheetmod.gate_spec(spec)
        if bad:
            write_json(run_dir / "sheet_spec_rejected.json", spec)
            raise SystemExit("시트 사양이 모자랍니다 — 그리기 전에 멈춥니다:\n  - "
                             + "\n  - ".join(bad))
        write_json(spec_path, spec)

    image_prompt = sheetmod.build_prompt(spec)
    write_text(run_dir / "sheet_prompt.txt", image_prompt)
    if dry_run:
        log(f"[시트] 이미지 프롬프트만 썼습니다 -> {run_dir / 'sheet_prompt.txt'}")
        return

    out = run_dir / "sheet.png"
    if out.exists():
        log(f"[시트] {out} 가 이미 있습니다. 다시 뽑으려면 지우세요.")
        return
    log("[시트] 그리는 중…")
    meta = sheetmod.paint(image_prompt, out, photos=[Path(p) for p in photos])
    record(run_dir, dict(meta, stage="SHEET_IMAGE", cost=None))
    log(f"  -> {out}")


# --------------------------------------------------------------------- CLI

def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        description="new_harness — 이야기 후보 · 콘티 · 캐릭터 시트",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    p.add_argument("--character", type=Path,
                   help="landing 의 character.json (또는 jobs/<id> 폴더)")
    p.add_argument("--name", help="캐릭터 이름 (필수)")
    p.add_argument("--photo", action="append", default=[], help="사진 (여러 번 가능)")
    p.add_argument("--desc", default="", help="설명 (선택)")
    p.add_argument("--genre", default="", help="장르 (선택)")

    p.add_argument("--run-id", help="이어서 할 run")
    p.add_argument("--pick", type=int, help="고를 방향 번호 (없으면 물어본다)")
    p.add_argument("--sheet", action="store_true", help="캐릭터 시트만")
    p.add_argument("--all", action="store_true", help="이야기 -> 콘티 -> 시트를 한 번에")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    p.add_argument("--plan", action="store_true", help="단계별 모델만 보여준다")
    args = p.parse_args(argv)

    if args.plan:
        for row in llm.plan():
            print(f"  {row['stage']:<6} {row['provider']}:{row['model']}")
        image_provider = (llm.env("SHEET_IMAGE_PROVIDER")
                          or llm.env("NH_IMAGE_PROVIDER") or "gemini")
        print(f"  {'SHEET 이미지':<6} {image_provider}:"
              f"{llm.env('SHEET_IMAGE_MODEL') or '(provider 기본값)'}")
        return 0

    if args.run_id:
        run_dir = RUNS_DIR / args.run_id
        if not run_dir.exists():
            raise SystemExit(f"그런 run 이 없습니다: {run_dir}")
        char = read_input(run_dir)
        new_run = False
    else:
        char = (read_character(args.character) if args.character
                else normalize({"name": args.name, "description": args.desc,
                                "genre": args.genre, "photos": args.photo}))
        bad = gate_input(char)
        if bad:
            raise SystemExit("입력이 모자랍니다:\n  - " + "\n  - ".join(bad))
        run_dir = RUNS_DIR / story.new_run_id()
        write_json(run_dir / "input.json", char)
        new_run = True
        log(f"run: {run_dir}")

    if args.sheet and not args.all:
        stage_sheet(run_dir, char, args.dry_run)
        return 0

    directions = []
    if new_run or args.all:
        directions = stage_story(run_dir, char, args.dry_run)
        if args.dry_run:
            return 0
        if not args.all and args.pick is None:
            show_directions(directions)
            print(f"\n골랐으면:  python run.py --run-id {run_dir.name} --pick <번호>")
            return 0
    else:
        path = run_dir / "directions.json"
        if not path.exists():
            raise SystemExit(f"{path} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
        directions = json.loads(path.read_text(encoding="utf-8"))

    if not directions:
        raise SystemExit("고를 방향이 없습니다. story.md 를 보고 프롬프트를 확인하세요.")

    direction = choose(directions, args.pick)
    stage_board(run_dir, char, direction, args.dry_run)

    if args.all or args.sheet:
        stage_sheet(run_dir, char, args.dry_run)

    log(f"끝났습니다 -> {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
