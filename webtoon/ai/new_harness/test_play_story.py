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


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
