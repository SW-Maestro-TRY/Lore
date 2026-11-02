"""제목 자리 표시를 베낀 후보를 고치는 일 — 모델 호출 없이 도는 부분만.

    python3 test_titles.py
"""
import tempfile
from pathlib import Path

import llm
import run as R


def test_placeholder_is_missing():
    for title in ("제목", " **제목** ", "<제목>", "「소제목」", "이번 화 소제목", "Title", "タイトル", "标题",
                  "이 이야기에 지어 붙인 제목", ""):
        assert R.title_missing(title), title
    for title in ("제목 없는 편지", "달빛 시장의 제목 도둑"):
        assert not R.title_missing(title), title


def test_parse_drops_angle_brackets_the_model_copied():
    md = "## 방향 1 — <달빛 시장의 약속>\n장르: 판타지\n\n소개:\n소개.\n\n본문:\n본문.\n"
    assert R.parse_directions(md)[0]["title"] == "달빛 시장의 약속"


def _with_call(reply):
    made = []

    class Fake:
        provider, model = "fake", "fake"

        def __init__(self, stage):
            made.append(stage)

        def describe(self):
            return "fake"

        def __call__(self, prompt, images=None):
            made.append(prompt)
            if isinstance(reply, Exception):
                raise reply
            return reply, {"calls": []}

    llm.Call, old = Fake, llm.Call
    R.record = lambda run_dir, meta: None
    return made, old


def test_fills_only_placeholder():
    made, old = _with_call("「달빛 시장」\n")
    try:
        with tempfile.TemporaryDirectory() as tmp:
            ds = [{"n": 1, "title": "제목", "intro": "소개", "body": "본문"},
                  {"n": 2, "title": "지어 둔 제목입니다", "intro": "소개", "body": "본문"}]
            R.fill_titles(Path(tmp), ds)
    finally:
        llm.Call = old
    assert ds[0]["title"] == "달빛 시장"
    assert ds[1]["title"] == "지어 둔 제목입니다"
    assert sum(1 for m in made if m == "STORY") == 1
    assert "## 이야기\n소개\n\n본문" in made[1]


def test_failure_leaves_blank_and_keeps_going():
    for reply in (RuntimeError("막힘"), "제목\n", ""):
        _, old = _with_call(reply)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                ds = [{"n": 1, "title": "제목", "intro": "소개", "body": "본문"}]
                R.fill_titles(Path(tmp), ds)
        finally:
            llm.Call = old
        assert ds[0]["title"] == ""


if __name__ == "__main__":
    test_placeholder_is_missing()
    test_parse_drops_angle_brackets_the_model_copied()
    test_fills_only_placeholder()
    test_failure_leaves_blank_and_keeps_going()
    print("ok")
