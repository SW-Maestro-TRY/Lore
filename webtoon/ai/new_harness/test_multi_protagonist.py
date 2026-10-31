"""주인공 여러 명 — 모델 호출 없이 도는 부분만(입력 조립 · 시트 자리 · 장 그림 참조 · 연재 승계).

    python3 test_multi_protagonist.py
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import continuation
import detailart
import own
import run as R

HERE = Path(__file__).resolve().parent
# 1x1 PNG
PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")


def _photo(d: Path, name: str) -> str:
    p = d / name
    p.write_bytes(PNG)
    return str(p)


def _char_json(d: Path, co: bool = True) -> Path:
    doc = {"name": "하윤", "character": "말보다 손이 먼저 나가는 정비공", "photo": _photo(d, "a.png"),
           "genre": "", "story": ""}
    if co:
        doc["co"] = [{"name": "서진", "character": "느긋하고 말이 많은 우편배달부",
                      "photo": _photo(d, "b.png"), "card": {"species": "사람", "world_label": "항구 도시"}}]
    path = d / "character.json"
    path.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    return path


def _run(args: list[str], runs: Path) -> None:
    env = {**os.environ, "NH_RUNS_DIR": str(runs)}
    subprocess.run([sys.executable, str(HERE / "run.py"), *args, "--dry-run"], check=True, env=env,
                   capture_output=True, text=True)


def test_single_has_no_co():
    d = Path(tempfile.mkdtemp())
    char = R.read_character(_char_json(d, co=False))
    assert "co" not in char
    assert R.co_input_lines(char) == [] and R.co_scene_lines(char) == []
    assert "함께 주인공" not in R.input_block(char)
    assert "첨부한 사진 1장을 보라" in R.input_block(char)


def test_co_is_read_and_capped():
    d = Path(tempfile.mkdtemp())
    path = _char_json(d)
    doc = json.loads(path.read_text(encoding="utf-8"))
    doc["co"] += [{"name": n, "character": "설명"} for n in ("도경", "리안", "넘침")]
    path.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    char = R.read_character(path)
    assert [c["name"] for c in char["co"]] == ["서진", "도경", "리안"]      # 주인공 넷까지(CO_MAX = 3)
    char["co"] = char["co"][:1]
    assert R.protagonist_names(char) == ["하윤", "서진"]
    assert len(R.all_photos(char)) == 2 and R.all_photos(char)[0].endswith("a.png")
    solo1 = R.solo(char, 1)
    assert solo1["name"] == "서진" and "co" not in solo1 and solo1["photos"][0].endswith("b.png")
    assert R.is_protagonist(char, "서진") and not R.is_protagonist(char, "다른 사람")


def test_gate_rejects_same_name_and_empty_co():
    char = R.normalize({"name": "하윤", "description": "정비공",
                        "co": [{"name": "하윤", "description": "같은 이름"}]})
    assert any("겹칩니다" in b for b in R.gate_input(char))
    char = R.normalize({"name": "하윤", "description": "정비공", "co": [{"name": "서진"}]})
    assert any("서진의 외관" in b for b in R.gate_input(char))


def test_story_prompt_has_both():
    d = Path(tempfile.mkdtemp())
    runs = d / "runs"
    _run(["--character", str(_char_json(d)), "--run-id", "t1", "--one"], runs)
    prompt = (runs / "t1" / "story_prompt.txt").read_text(encoding="utf-8")
    assert "하윤" in prompt and "서진" in prompt and "느긋하고 말이 많은 우편배달부" in prompt
    assert "이 웹툰의 주인공은 2명이다" in prompt
    assert "첨부한 사진 가운데 앞의 1장" in prompt and "2번째부터 2번째까지 1장" in prompt
    # 인물 단계도 두 주인공을 받는다(조연으로 다시 세우지 않게)
    assert "서진" in (runs / "t1" / "cast_prompt.txt").read_text(encoding="utf-8")
    # 페르소나는 첫 주인공 한 사람
    persona = (runs / "t1" / "persona_prompt.txt").read_text(encoding="utf-8")
    assert "하윤" in persona and "우편배달부" not in persona
    # 함께 주인공도 자기 카드 프롬프트가 따로 있다
    co_persona = (runs / "t1" / "personas" / "서진.prompt.txt").read_text(encoding="utf-8")
    assert "우편배달부" in co_persona and "정비공" not in co_persona


def test_story_ends_with_check_only_when_several():
    line = "주인공마다 이번 화에서 자기 행동으로 일을 바꿨는가"
    d = Path(tempfile.mkdtemp())
    runs = d / "runs"
    _run(["--character", str(_char_json(d)), "--run-id", "many", "--one"], runs)
    prompt = (runs / "many" / "story_prompt.txt").read_text(encoding="utf-8")
    assert line in prompt.rstrip().splitlines()[-1]             # 맨 끝 줄
    _run(["--character", str(_char_json(d, co=False)), "--run-id", "single", "--one"], runs)
    assert line not in (runs / "single" / "story_prompt.txt").read_text(encoding="utf-8")
    many, single = R.read_character(_char_json(d)), R.read_character(_char_json(d, co=False))
    assert any(line in x for x in own.check_lines(many)) and not any(line in x for x in own.check_lines(single))


def test_sheet_dry_run_writes_one_per_protagonist():
    d = Path(tempfile.mkdtemp())
    runs = d / "runs"
    _run(["--character", str(_char_json(d)), "--run-id", "t2", "--sheet"], runs)
    first = (runs / "t2" / "sheet_spec_prompt.txt").read_text(encoding="utf-8")
    second = (runs / "t2" / "sheets" / "서진.spec_prompt.txt").read_text(encoding="utf-8")
    assert "정비공" in first and "우편배달부" not in first
    assert "우편배달부" in second and "정비공" not in second and "캐릭터 이름: 서진" in second


def test_scene_input_and_pages():
    d = Path(tempfile.mkdtemp())
    char = R.read_character(_char_json(d))
    direction = {"n": 1, "title": "부두의 밤", "genre": "", "body": "하윤과 서진이 부두에서 만난다."}
    block = R.scene_input_block(char, direction)
    assert "서진 (함께 주인공)" in block and "이 웹툰의 주인공은 하윤, 서진이다" in block
    assert "서진" in R.narration_input_block(char, direction, "1. 장면")

    run_dir = d / "run"
    R.write_json(run_dir / "input.json", char)
    R.write_json(run_dir / "directions.json", [direction])
    R.write_json(run_dir / "pick.json", {"n": 1})
    R.write_json(run_dir / "scenes.json", {
        "plot": "", "fixed": [],
        "cast": [{"name": "서진", "appearance": "장면이 지은 외모"}, {"name": "선장", "appearance": "흰 수염"}],
        "scenes": [{"n": i, "prev": "", "where": "부두", "what": f"{i}일 — 하윤과 서진과 선장", "acting": "",
                    "look": "", "ends": f"{i}끝", "narration": []} for i in (1, 2, 3)]})
    (run_dir / "sheets").mkdir()
    (run_dir / "sheets" / "서진.png").write_bytes(PNG)
    R.write_json(run_dir / "sheets" / "서진.spec.json", {"name": "서진", "appearance_en": "tall courier"})
    (run_dir / "sheets" / "선장.png").write_bytes(PNG)
    # 주인공 시트는 조연 시트 목록에 안 든다
    assert list(detailart.cast_sheets_of(run_dir, char)) == ["선장"]
    detailart.draw_continue(run_dir, dry_run=True)
    page = (run_dir / "pages" / "page02.txt").read_text(encoding="utf-8")
    assert "주인공은 2명이다 — 하윤, 서진" in page and "tall courier" in page
    assert "서진 — 장면이 지은 외모" not in page          # 함께 주인공은 조연 줄로 다시 안 나온다
    assert "선장 — 흰 수염" in page
    cover = (run_dir / "pages" / "page01.txt").read_text(encoding="utf-8")
    assert "주인공은 2명이다" in cover
    # 조연 시트 자리는 주인공 시트 수만큼 준다(참조 넷 한도)
    assert detailart.cast_sheets_in_scene({"선장": Path("x"), "하윤": Path("y")},
                                          {"what": "하윤 선장"}, detailart.CAST_SHEET_LIMIT - 1) == ["선장"]


def test_sheet_plan_per_page():
    one = {"name": "하윤"}
    # 한 명이면 예전 그대로 — 첫 주인공 시트 + 조연 둘, 표지는 첫 주인공만
    assert detailart.sheet_plan(one, {"what": "아무도"}) == ([0], 2)
    assert detailart.sheet_plan(one, None) == ([0], 0)
    four = {"name": "하윤", "co": [{"name": n} for n in ("서진", "윤도경", "리안")]}
    assert detailart.sheet_plan(four, {"what": "서진이 혼자 걷는다"}) == ([1], 2)
    assert detailart.sheet_plan(four, {"what": "선장이 배를 띄운다"}) == ([0], 2)        # 이름이 없으면 첫 주인공
    assert detailart.sheet_plan(four, {"what": "도경과 하윤"}) == ([0, 2], 1)            # 성 뺀 이름도 찾는다, 주인공 순서
    assert detailart.sheet_plan(four, {"what": "하윤 서진 도경 리안 모두"}) == ([0, 1, 2], 0)   # 직전 장 자리를 남기고 셋
    assert detailart.sheet_plan(four, None) == ([0, 1, 2, 3], 0)                        # 표지는 전원(넷 한도)


def test_continuation_carries_co_sheet():
    d = Path(tempfile.mkdtemp())
    source = d / "src"
    char = R.read_character(_char_json(d))
    R.write_json(source / "input.json", char)
    (source / "sheet.png").write_bytes(PNG)
    packet = {"source_dir": str(source)}
    try:
        continuation.prepare_input({"story": "", "settings": "", "title": "", "episode": ""}, packet)
        raise AssertionError("함께 주인공 시트가 없는데 통과했다")
    except ValueError as exc:
        assert "서진" in str(exc)
    (source / "sheets").mkdir()
    (source / "sheets" / "서진.png").write_bytes(PNG)
    merged = R.normalize(continuation.prepare_input({"story": "", "settings": "", "title": "", "episode": ""}, packet))
    assert merged["co"][0]["photos"][0].endswith("sheets/서진.png")
    assert merged["photos"][0].endswith("sheet.png")


def test_own_input_has_both():
    d = Path(tempfile.mkdtemp())
    char = R.read_character(_char_json(d))
    char["story"] = "둘이 함께 밀수선을 쫓는다"
    block = own.input_block(char)
    assert "서진" in block and "이 웹툰의 주인공은 2명이다" in block
    assert "서진 (함께 주인공)" in "\n".join(own.character_lines(char))
    # 카드 자리가 곁·스쳐감이어도 주인공을 둘 골랐으면 「중심 인물은 따로 있다」를 안 붙인다
    char["card"] = {"role_tier": "스쳐감", "role": "손님"}
    assert own.side_role_lines(char) == [] and "중심 인물은 따로" not in own.input_block(char)
    assert own.side_role_lines(R.solo(char)) != []      # 한 명이면 예전 그대로


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
