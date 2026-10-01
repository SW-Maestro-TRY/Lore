"""own 길(#548)의 파일 다루기 — 모델 호출 없이 도는 부분만.

    python3 test_own.py
"""
import json
import tempfile
from pathlib import Path

import own
import run as R


def _run_dir(scenes: int = 3) -> Path:
    d = Path(tempfile.mkdtemp())
    R.write_json(d / "directions.json", [{"n": 1, "title": "처음 제목", "genre": "현대 로맨스",
                                          "intro": "", "body": "본문", "raw": ""}])
    R.write_json(d / "pick.json", {"n": 1, "title": "처음 제목", "genre": "현대 로맨스"})
    if scenes:
        R.write_json(d / "scenes.json", {"plot": "", "cast": [], "fixed": [], "scenes": [
            {"n": i, "prev": f"{i - 1}끝", "where": f"{i}장소", "what": f"{i}일", "acting": f"{i}행동",
             "look": "시트 그대로", "ends": f"{i}끝", "narration": []} for i in range(1, scenes + 1)]})
    return d


def test_save_edits_before_scenes():
    """이야기 확인 자리 — 장면이 아직 없어도 본문·제목은 적힌다."""
    d = _run_dir(scenes=0)
    own.save_edits(d, {"scenes": [], "body": "고친 본문", "title": "고친 제목"})
    got = R.read_json(d / "directions.json")[0]
    assert got["body"] == "고친 본문" and got["title"] == "고친 제목"
    assert R.read_json(d / "pick.json")["title"] == "고친 제목"
    assert not (d / "scenes.json").exists()


def test_rescene_input_has_neighbours_and_reasons():
    d = _run_dir()
    char = {"name": "몽이", "description": "강아지", "genre": "현대 로맨스", "photos": [], "fields": {},
            "story": "적은 내용", "mode": "own"}
    text = own.rescene_input(d, char, 2, ["stranger", "nope", "pacing"], "메모")
    assert "앞 장면(1번)이 끝난 상태" in text and "1끝" in text
    assert "뒤 장면(3번)이 시작하는 상태" in text and "2끝" in text
    assert "지금 2번 장면" in text and "장면 2:" in text
    assert own.RESCENE_REASONS["stranger"] in text and own.RESCENE_REASONS["pacing"] in text
    assert "nope" not in text                       # 모르는 코드는 버린다
    assert text.rstrip().endswith("적은 내용")      # 적은 내용이 가장 뒤
    first = own.rescene_input(d, char, 1, [], "")
    assert "없음 — 이 장면이 첫 장면이다" in first
    last = own.rescene_input(d, char, 3, [], "")
    assert "없음 — 이 장면이 마지막 장면이다" in last


def test_rescene_replaces_only_that_scene(monkeypatch=None):
    """모델 답을 흉내 내서 n번만 바뀌고 앞뒤가 이어지는지."""
    d = _run_dir()
    char = {"name": "몽이", "description": "", "genre": "", "photos": [], "fields": {}, "story": ""}
    answer = ("장면 2:\n직전 상태: 아무거나\n장소와 상황: 새 장소\n벌어지는 일: 새 일\n"
              "인물의 행동과 표정: 새 행동\n겉모습·소지품·동행: 시트 그대로\n끝나는 상태: 새 끝\n나레이션: 없음\n")

    class FakeCall:
        provider = model = "fake"

        def __init__(self, stage):
            pass

        def describe(self):
            return "fake"

        def __call__(self, prompt, **kw):
            return answer, {"calls": []}

    real = own.llm.Call
    own.llm.Call = FakeCall
    real_record = R.record
    R.record = lambda run_dir, meta: None
    try:
        own.rescene(d, char, 2, ["awkward"], "", dry_run=False)
    finally:
        own.llm.Call = real
        R.record = real_record
    scenes = R.read_json(d / "scenes.json")["scenes"]
    assert [s["n"] for s in scenes] == [1, 2, 3]
    assert scenes[0]["what"] == "1일" and scenes[2]["what"] == "3일"
    assert scenes[1]["where"] == "새 장소" and scenes[1]["prev"] == "1끝"   # 앞 장면이 끝난 자리에서
    assert scenes[2]["prev"] == "새 끝"                                     # 뒤 장면은 새 끝에서
    assert "user_text" not in scenes[1]


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
