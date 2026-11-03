"""세계관 만들기(--world-make) — 모델 호출 없이 도는 부분만.

    python3 test_worldmake.py
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import worldmake as W

HERE = Path(__file__).resolve().parent

BOOK = {"name": "잿빛 항구", "fixed_settings": "안개가 걷히지 않는 항구 도시.",
        "entries": [{"title": "등대지기 조합", "content": "밤마다 불을 켜는 사람들.", "enabled": True},
                    {"title": "꺼 둔 항목", "content": "안 보여야 한다", "enabled": False}]}


def _answer(**kw):
    base = {"name": "모델 이름", "genre": "모델 장르", "one": "모델 소개", "rules": ["규칙 하나", "규칙 둘"],
            "spot": "모델 자리", "cast": [{"name": "모델 인물", "role": "역할"}],
            "events": [{"title": f"사건 {i}", "line": f"한 줄 {i}", "situation": f"상황 {i}", "spot": "",
                        "examples": [{"name": f"가{i}", "description": "겉모습"}, {"name": f"나{i}", "description": "겉모습"},
                                     {"name": f"다{i}", "description": "남는 사람"}]}
                       for i in range(1, 5)]}
    return {**base, **kw}


def test_prompt_carries_draft_books_note_previous():
    text = W.prompt({"draft": {"name": "내 세계", "rule": "밤엔 나가지 않는다"}, "books": [BOOK],
                     "note": "더 어둡게", "previous": {"name": "지난 세계"}})
    assert "내 세계" in text and "밤엔 나가지 않는다" in text
    assert "등대지기 조합" in text and "안 보여야 한다" not in text
    assert "더 어둡게" in text and "지난 세계" in text
    assert "{{LANGUAGE_LINE}}" not in text


def test_prompt_without_extras():
    text = W.prompt({"draft": {}})
    assert "[참고할 설정집]\n(없음)" in text
    tail = text.split("[참고할 설정집]\n(없음)")[1]
    assert "[이번에 다시 만들며 바라는 것]" not in tail and "[앞서 지은 것]" not in tail


def test_clean_keeps_user_fields_and_caps():
    draft = {"name": "내 세계", "one": "", "rule": "첫 규칙\n둘째 규칙",
             "cast": [{"name": "내 인물", "role": "내 역할"}],
             "events": [{"title": "내 사건", "situation": "내가 적은 상황"}]}
    got = W.clean(_answer(), draft)
    assert got["name"] == "내 세계" and got["one"] == "모델 소개"
    assert got["rules"] == ["첫 규칙", "둘째 규칙"]
    assert got["cast"][0] == {"name": "내 인물", "role": "내 역할", "desc": ""} and got["cast"][1]["name"] == "모델 인물"
    assert len(got["events"]) == W.EVENTS
    assert got["events"][0]["title"] == "내 사건" and got["events"][0]["situation"] == "내가 적은 상황"
    assert got["events"][1]["situation"] == "상황 2"
    assert [x["name"] for x in got["events"][0]["examples"]] == ["가1", "나1"]
    assert W.missing(got, draft) == []


def test_missing_examples_is_reported_per_event():
    answer = _answer()
    answer["events"][1]["examples"] = [{"name": "가", "description": ""}]
    got = W.clean(answer, {})
    assert W.missing(got, {}) == ["2번 사건의 예시 주인공"]
    # 사용자가 이미 둘을 고른 사건은 짓지 않아도 된다
    answer["events"][0]["examples"] = []
    draft = {"events": [{"title": "t", "situation": "상황 1", "examples": ["c1", "c2"]}]}
    assert "1번 사건의 예시 주인공" not in W.missing(W.clean(answer, draft), draft)


def test_prompt_tells_how_many_examples_are_kept():
    text = W.prompt({"draft": {"events": [{"title": "t", "situation": "s", "examples": ["c1"]}]}})
    assert '"examples_kept": 1' in text


def test_cli_dry_run_writes_prompt_only():
    d = Path(tempfile.mkdtemp())
    (d / "input.json").write_text(json.dumps({"draft": {"name": "내 세계"}, "books": [BOOK]}, ensure_ascii=False),
                                  encoding="utf-8")
    r = subprocess.run([sys.executable, "run.py", "--world-make", str(d / "input.json"), "--out",
                        str(d / "result.json"), "--dry-run"], cwd=HERE, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert "등대지기 조합" in (d / "world_make_prompt.txt").read_text(encoding="utf-8")
    assert not (d / "result.json").exists()


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
