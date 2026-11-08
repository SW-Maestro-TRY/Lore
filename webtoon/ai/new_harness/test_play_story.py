"""세계관 체험 이야기 입력 — 상황이 「이번 사건」으로 들어가고 목표 한 단락이 붙는지. 모델 호출 없음.

    python3 test_play_story.py
"""
import os
import tempfile
from pathlib import Path

import run as R

BASE = {"name": "루나", "description": "말수가 적다", "genre": "판타지", "settings": "주인공이 결투에 불려 나간다.",
        "lorebook": {}, "fields": {}}


def test_play_marks_event_and_adds_goal():
    char = {**BASE, "play": True}
    block = R.one_input_block(char)
    assert "[이번 사건]\n주인공이 결투에 불려 나간다." in block and "[추가 설정]" not in block
    goal = R.play_goal_block(char)
    assert goal.startswith("## 이번 이야기의 중심") and "[캐릭터 설명]" in goal


def test_plain_run_is_unchanged():
    block = R.one_input_block(BASE)
    assert "[추가 설정]\n주인공이 결투에 불려 나간다." in block and "[이번 사건]" not in block
    assert R.play_goal_block(BASE) == ""
    assert R.play_goal_block({**BASE, "play": True, "settings": ""}) == ""


def test_play_turns_off_random_event_word():
    os.environ["NH_STORY_EVENTS"] = "1"
    try:
        d = Path(tempfile.mkdtemp())
        assert R.one_event_block(d, {**BASE, "play": True}) == ""
        assert "중심 사건:" in R.one_event_block(d, BASE)
    finally:
        os.environ.pop("NH_STORY_EVENTS", None)


def test_normalize_keeps_play_only_when_true():
    assert R.normalize({"name": "a", "play": True}).get("play") is True
    assert "play" not in R.normalize({"name": "a", "play": "yes"})
    assert "play" not in R.normalize({"name": "a"})


def test_canon_is_split_from_situation_and_joins_goal():
    raw = {**BASE, "play": True, "settings": "주인공이 결투에 불려 나간다.\n장소: 광장\n\n[원래 전개]\n1. 불려 나간다\n2. 진다"
           "\n[원래 결말] 결투에서 진다\n[갈래]\n- 결투에서 진다\n- 결투를 거절한다"}
    char = R.normalize(raw)
    assert char["settings"] == "주인공이 결투에 불려 나간다.\n장소: 광장"
    assert char["canon"] == ["불려 나간다", "진다"]
    assert char["canon_end"] == "결투에서 진다" and char["groups"] == ["결투에서 진다", "결투를 거절한다"]
    block = R.one_input_block(char)
    assert "[원래 전개]" not in block and "진다" not in block      # 독자가 볼 사건에는 안 들어간다
    goal = R.play_goal_block(char)
    assert "원래 전개" not in goal and "진다" not in goal     # 이야기에는 기준을 주지 않는다 — 견주는 데만 쓴다


def test_no_canon_keeps_old_goal():
    char = R.normalize({**BASE, "play": True})
    assert "canon" not in char
    assert R.play_goal_block(char) == R.load_prompt("play_goal")
    plain = R.normalize({**BASE, "settings": "a\n[원래 전개]\n1. b"})   # 체험이 아니면 건드리지 않는다
    assert "[원래 전개]" in plain["settings"] and "canon" not in plain


def test_branch_prompt_dry_run_has_canon_and_story():
    d = Path(tempfile.mkdtemp())
    char = R.normalize({**BASE, "play": True, "settings": "상황\n[원래 전개]\n1. 가\n2. 나\n[원래 결말] 나로 끝\n[갈래]\n- 나로 끝"})
    scenes = [{"what": "첫 장면"}, {"what": "둘째 장면"}]
    assert R.stage_branch(d, char, {"title": "제목", "body": "본문 글"}, dry_run=True, scenes=scenes) is None
    prompt = (d / "branch_prompt.txt").read_text(encoding="utf-8")
    assert "1. 가\n2. 나" in prompt and "본문 글" in prompt and "말수가 적다" in prompt
    assert "[원래 결말 이름]\n나로 끝" in prompt and "- 나로 끝" in prompt and "장면 2: 둘째 장면" in prompt
    assert R.stage_branch(d, R.normalize({**BASE, "play": True}), {"body": "x"}, dry_run=True) is None


def test_branch_input_file_gives_canon_to_old_runs():
    d = Path(tempfile.mkdtemp())
    R.write_json(d / "branch_input.json", {"canon": ["가", "나"], "canon_end": "끝", "groups": ["끝"]})
    got = R.branch_char(d, R.normalize({**BASE, "play": True}))
    assert got["canon"] == ["가", "나"] and got["canon_end"] == "끝" and got["groups"] == ["끝"]


def test_clean_branch_clamps_and_defaults_group():
    out = R.clean_branch({"at": 9, "scene": "2", "label": " 거절한다 ", "group": ""}, 4, 3, "진다")
    assert out["at"] == 4 and out["scene"] == 2 and out["label"] == "거절한다" and out["group"] == "거절한다"
    same = R.clean_branch({"at": 0, "scene": 0, "label": "x", "group": "다른 이름"}, 4, 3, "진다")
    assert same["group"] == "진다"           # 안 갈렸으면 원래 결말 갈래


def test_branch_groups_carry_their_ending():
    """서버가 「이름 | 결말」로 보낸 갈래는 이름만 groups 에, 결말은 group_ends 에 들어가고, 갈림 정리 입력에 결말이 붙는다(#989)."""
    import tempfile
    situation, canon, end, groups = R.split_canon("상황\n\n[원래 전개]\n1. 가\n2. 나\n[원래 결말] 끝\n[갈래]\n- 끝 | 나\n- 직접 끝낸다 | 가족을 직접 죽인다")
    assert groups == ["끝 | 나", "직접 끝낸다 | 가족을 직접 죽인다"]
    assert R.split_group(groups[1]) == ("직접 끝낸다", "가족을 직접 죽인다")
    assert R.split_group("이름만") == ("이름만", "")
    with tempfile.TemporaryDirectory() as t:
        d = Path(t)
        R.write_json(d / "branch_input.json", {"canon": ["가", "나"], "canon_end": "끝",
                                               "groups": ["직접 끝낸다"], "group_ends": {"직접 끝낸다": "가족을 직접 죽인다"}})
        got = R.branch_char(d, {"name": "x"})
        assert got["group_ends"] == {"직접 끝낸다": "가족을 직접 죽인다"}
        R.stage_branch(d, got, {"title": "t", "body": "b"}, dry_run=True)
        prompt = (d / "branch_prompt.txt").read_text(encoding="utf-8")
        assert "- 직접 끝낸다 — 이 갈래로 묶인 만화의 결말: 가족을 직접 죽인다" in prompt
        assert "이름의 낱말이 아니라" in prompt


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
