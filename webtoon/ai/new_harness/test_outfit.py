"""사진 속 옷을 세계에 맞추는 옵션(NH_OUTFIT) — 모델 호출 없이 도는 부분만.

    python3 test_outfit.py
"""
import json
import os
import tempfile
from pathlib import Path

import own
import run as R
import sheet as sheetmod

PHOTO = Path(tempfile.mkdtemp()) / "photo.png"
PHOTO.write_bytes(b"\x89PNG")


def _char(**kw) -> dict:
    base = {"name": "이현", "description": "약속에 늘 십 분 늦는다", "fields": {}, "genre": "로맨스 판타지",
            "photos": [str(PHOTO)], "photo_note": "", "story": "", "card": {}, "lorebook": {},
            "settings": "", "title": "", "episode": ""}
    return {**base, **kw}


def _spec(fits=None) -> dict:
    spec = {"name": "이현", "species": "사람", "species_en": "", "appearance_en": "A young man in a coat",
            "design_details": ["왼쪽 가슴에 은색 브로치"], "props": [],
            "color_palette": {k: "white (#FFFFFF)" for k in sheetmod.PALETTE_KEYS},
            "expression_set": ["평온 — 힘 뺀 얼굴"] * 6}
    if fits is not None:
        spec["outfit"] = {"photo": "흰 셔츠", "fits": fits, "why": "이유", "worn": "긴 모직 코트"}
    return spec


def _env(value):
    if value is None:
        os.environ.pop("NH_OUTFIT", None)
    else:
        os.environ["NH_OUTFIT"] = value


def test_mode_defaults_to_keep():
    _env(None)
    assert R.outfit_mode(_char()) == "keep"


def test_mode_from_env_and_input():
    _env("world")
    assert R.outfit_mode(_char()) == "world"
    assert R.outfit_mode(_char(outfit="keep")) == "keep"        # 입력이 환경변수보다 먼저
    _env(None)
    assert R.outfit_mode(_char(outfit="world")) == "world"


def test_world_needs_photo_and_world():
    _env("world")
    assert R.outfit_mode(_char(photos=[])) == "keep"
    assert R.outfit_mode(_char(genre="")) == "keep"
    assert R.outfit_mode(_char(genre="", lorebook={"books": [{"name": "좀비"}]})) == "world"
    _env(None)


def test_unknown_mode_is_keep():
    _env("fancy")
    assert R.outfit_mode(_char()) == "keep"
    _env(None)


def test_normalize_keeps_outfit_only_when_given():
    got = R.normalize({"name": "a", "outfit": " World "})
    assert got["outfit"] == "world"
    assert "outfit" not in R.normalize({"name": "a"})


def test_spec_block_only_in_world():
    _env(None)
    assert R.spec_block(_char(), "입력") == "입력"
    got = R.spec_block(_char(outfit="world"), "입력")
    assert got.startswith("입력") and "\"outfit\"" in got and "사진 속 옷" in got


def test_parse_spec_keeps_outfit():
    text = json.dumps({**_spec(), "outfit": {"photo": "흰 셔츠", "fits": False, "why": "현대옷", "worn": "코트"}})
    got = sheetmod.parse_spec(text)
    assert got["outfit"]["fits"] is False and got["outfit"]["worn"] == "코트"
    assert "outfit" not in sheetmod.parse_spec(json.dumps(_spec()))
    # fits 가 참·거짓이 아니면 판단이 없는 것으로 본다
    assert "outfit" not in sheetmod.parse_spec(json.dumps({**_spec(), "outfit": {"fits": "몰라"}}))


def test_sheet_prompt_drops_photo_outfit_only_when_changed():
    keep = sheetmod.build_prompt(_spec(), from_photo=True)
    assert "outfit and material texture" in keep and "NOT the one from the source photo" not in keep
    fits = sheetmod.build_prompt(_spec(fits=True), from_photo=True)
    assert fits == keep
    changed = sheetmod.build_prompt(_spec(fits=False), from_photo=True)
    assert "NOT the one from the source photo" in changed and "outfit and material texture" not in changed


def _fake(monkey_specs, checks):
    calls = {"spec": 0, "check": 0, "prompts": []}
    old = (R._spec_once, R.outfit_check)

    def spec_once(run_dir, prompt, photos, paths):
        calls["prompts"].append(prompt)
        calls["spec"] += 1
        return monkey_specs[calls["spec"] - 1]

    def check(run_dir, char, spec):
        calls["check"] += 1
        return checks[calls["check"] - 1]

    R._spec_once, R.outfit_check = spec_once, check
    return calls, old


def _restore(old):
    R._spec_once, R.outfit_check = old


def test_keep_calls_spec_once_without_check():
    _env(None)
    d = Path(tempfile.mkdtemp())
    calls, old = _fake([_spec()], [])
    try:
        R.make_spec(d, _char(), "프롬프트", R.sheet_paths(d))
    finally:
        _restore(old)
    assert calls["spec"] == 1 and calls["check"] == 0
    assert not (d / "sheet_outfit.json").exists()


def test_world_rewrites_once_when_check_fails():
    _env(None)
    d = Path(tempfile.mkdtemp())
    bad = {"fits": False, "wrong": "청바지", "fix": "모직 바지"}
    good = {"fits": True, "wrong": "", "fix": ""}
    calls, old = _fake([_spec(fits=False), _spec(fits=False)], [bad, good])
    try:
        R.make_spec(d, _char(outfit="world"), "프롬프트", R.sheet_paths(d))
    finally:
        _restore(old)
    assert calls["spec"] == 2 and calls["check"] == 2
    assert "청바지" in calls["prompts"][1] and "모직 바지" in calls["prompts"][1]
    saved = R.read_json(d / "sheet_outfit.json")
    assert saved["first"]["check"] == bad and saved["second"]["check"] == good
    assert (d / "sheet_spec_prompt.2.txt").exists()


def test_world_stops_after_one_rewrite():
    _env(None)
    d = Path(tempfile.mkdtemp())
    bad = {"fits": False, "wrong": "청바지", "fix": "모직 바지"}
    calls, old = _fake([_spec(fits=False), _spec(fits=False)], [bad, bad])
    try:
        R.make_spec(d, _char(outfit="world"), "프롬프트", R.sheet_paths(d))
    finally:
        _restore(old)
    assert calls["spec"] == 2 and calls["check"] == 2


def test_world_passes_when_check_fails_to_answer():
    _env(None)
    d = Path(tempfile.mkdtemp())
    calls, old = _fake([_spec(fits=True)], [None])
    try:
        R.make_spec(d, _char(outfit="world"), "프롬프트", R.sheet_paths(d))
    finally:
        _restore(old)
    assert calls["spec"] == 1
    assert R.read_json(d / "sheet_outfit.json")["first"]["check"] is None


def test_dry_run_prompts_carry_world_block():
    """실제 길(run · own) 그대로 dry-run — 사양 프롬프트 파일에 옷 칸이 실렸는가."""
    _env(None)
    d = Path(tempfile.mkdtemp())
    R._sheet_attempt(d, _char(outfit="world"), True, False, "", "")
    assert "사진 속 옷이 이 세계의 시대" in (d / "sheet_spec_prompt.txt").read_text(encoding="utf-8")
    d2 = Path(tempfile.mkdtemp())
    own._sheet_attempt(d2, _char(outfit="world", mode="own"), True, "", "")
    assert "사진 속 옷이 이 세계의 시대" in (d2 / "sheet_spec_prompt.txt").read_text(encoding="utf-8")
    d3 = Path(tempfile.mkdtemp())
    R._sheet_attempt(d3, _char(), True, False, "", "")
    assert "사진 속 옷이 이 세계의 시대" not in (d3 / "sheet_spec_prompt.txt").read_text(encoding="utf-8")


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
