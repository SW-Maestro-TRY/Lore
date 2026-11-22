import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import lou
import loufind
import loustory
import run


class Fake:
    """질문 내용으로 답하는 가짜 모델."""
    answer = staticmethod(lambda prompt: {})
    prompts = []

    def __init__(self, stage):
        self.provider = self.model = "fake"

    def __call__(self, prompt, images=None, temperature=None):
        Fake.prompts.append(prompt)
        reply = Fake.answer(prompt)
        return (reply if isinstance(reply, str) else json.dumps(reply, ensure_ascii=False)), {"stage": "LOU"}


def episode(root, name, scenes, body="본문"):
    book = root / name
    (book / "pages").mkdir(parents=True)
    (book / "sheets").mkdir()
    for n in range(1, len(scenes) + 2):
        Image.new("RGB", (300, 600), "white").save(book / "pages" / f"page{n:02}.png")
    Image.new("RGB", (100, 80), "gray").save(book / "sheets" / "레오 폴트.png")
    lou.write_json(book / "scenes.json", {"scenes": scenes})
    lou.write_json(book / "input.json", {"name": "주인공"})
    lou.write_json(book / "cast.json", [{"name": "레오 폴트", "look": "검은 머리"}, {"name": "디온", "look": "금발"}])
    lou.write_json(book / "directions.json", [{"n": 1, "body": body}])
    return book


class FindTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        episode(self.root, "ep01", [{"look": "레오 폴트는 망토", "acting": "디온이 웃는다"},
                                    {"look": "주인공 혼자", "acting": "“레오 폴트가 왔다”라고 말한다"}])
        episode(self.root, "ep02", [{"what": "디온과 주인공"}])
        Fake.prompts = []

    def go(self, req):
        path, out = self.root / "req.json", self.root / "out.json"
        lou.write_json(path, req)
        with patch.object(run, "RUNS_DIR", self.root), patch.object(loufind.llm, "Call", Fake):
            run.main(["--lou-find", str(path), "--lou-out", str(out)])
        return lou.read(out)

    def test_candidates_skip_names_only_in_speech_and_report_bad_pages(self):
        Fake.answer = staticmethod(lambda p: dict(bad=True, where="1번째 컷", what="바뀜", note="금발로")
                                   if '"what": "디온과 주인공"' in p else dict(bad=False))
        result = self.go(dict(text="둘이 바뀌었어", who="레오", also=["디온"], runs=["ep01", "ep02"]))
        self.assertEqual(2, result["checked"])                         # ep01 2장(대사 속 이름만 나온 3장은 빼고) + ep02 2장
        self.assertEqual([("ep02", 2)], [(f["run"], f["page"]) for f in result["found"]])
        self.assertTrue(result["found"][0]["note"].endswith("나머지는 그대로."))
        self.assertTrue(any('"sheets": ["레오 폴트"]' in p for p in Fake.prompts))

    def test_runs_must_stay_inside_the_runs_folder(self):
        for runs in (["../ep01"], ["없는 화"], [], "ep01"):
            with self.assertRaises(ValueError):
                self.go(dict(text="x", who="레오", runs=runs))


class StoryTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.body = "첫 문장이다. 그가 약속했다. 마지막 문장이다."
        self.book = episode(self.root, "ep01", [{"what": "a"}], body=self.body)
        lou.write_json(self.book / "continuation.json", {"request": "약속을 지킨다", "source_body": "앞 화 끝", "episode_number": 2})

    def test_review_keeps_only_sentences_found_in_the_body(self):
        Fake.answer = staticmethod(lambda p: dict(reply="읽었어요.", items=[
            dict(kind="unknown_name", sev="P1", quote="그가 약속했다.", what="누구인지 몰라요",
                 fix=dict(type="sentence", replace="디온이 약속했다.")),
            dict(kind="voice", quote="본문에 없는 문장", what="말투", fix=dict(type="sentence", replace="x")),
            dict(kind="listing", quote="마지막 문장이다.", what="나열", fix=dict(type="episode", note="다시 쓴다")),
            dict(kind="repeat", quote="본문에 없는 문장", what="되풀이", fix=dict(type="sentence", replace="y", note="흐름을 바꾼다"))]))
        req, out = self.root / "req.json", self.root / "out.json"
        lou.write_json(req, {"lang": "ko"})
        with patch.object(run, "RUNS_DIR", self.root), patch.object(loustory.llm, "Call", Fake):
            run.main(["--run-id", "ep01", "--lou-story", str(req), "--lou-out", str(out)])
        items = lou.read(out)["items"]
        self.assertEqual([("sentence", "그가 약속했다."), ("episode", "마지막 문장이다."), ("episode", "")],
                         [(i["fix"]["type"], i["quote"]) for i in items])
        self.assertIn('"plan": "약속을 지킨다"', Fake.prompts[-1])

    def test_fix_replaces_once_and_keeps_the_old_version(self):
        req, out = self.root / "fix.json", self.root / "out.json"
        lou.write_json(req, {"items": [{"quote": "그가 약속했다.", "replace": "디온이 약속했다."}]})
        with patch.object(run, "RUNS_DIR", self.root):
            run.main(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(out)])
        self.assertEqual("첫 문장이다. 디온이 약속했다. 마지막 문장이다.", lou.read(self.book / "directions.json")[0]["body"])
        self.assertEqual(self.body, lou.read(self.book / "directions.v1.json")[0]["body"])
        self.assertTrue(lou.read(out)["scenes_stale"])
        lou.write_json(req, {"items": [{"quote": "없는 문장", "replace": "x"}]})
        with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(ValueError):
            run.main(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(out)])


if __name__ == "__main__":
    unittest.main()


class GuardRailTest(unittest.TestCase):
    """2026-11-22 검토에서 나온 길 — 다른 사람 시트 · 작품 글 · 장 그림을 덮지 않는다."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.book = episode(self.root, "ep01", [{"what": "a"}], body="첫 문장. 둘째 문장. 셋째 문장.")

    def test_new_sheet_cannot_take_another_persons_file(self):
        import lousheet
        lou.write_json(self.book / "cast.json", [{"name": "하루"}, {"name": "나래"}, {"name": "백작", "role": ""},
                                                {"name": "시종", "role": "백작의 시종"}])
        Image.new("RGB", (100, 80), "blue").save(self.book / "sheets" / "나래.png")
        self.assertTrue(any(c["why"] == "taken" for c in lousheet.name_clashes(self.book, "나래", "하루")))
        self.assertEqual([], lousheet.name_clashes(self.book, "백작"))          # 「백작의 시종」은 백작이 아니다
        draft = self.book / "sheets" / "drafts" / "c.png"
        draft.parent.mkdir()
        Image.new("RGB", (100, 80), "red").save(draft)
        lou.write_json(draft.with_suffix(".json"), {"target": "하루"})
        req = self.root / "pick.json"
        lou.write_json(req, {"target": "하루", "from": str(draft), "file_name": "나래"})
        with self.assertRaises((ValueError, SystemExit)):
            lousheet.pick(self.book, req)
        with Image.open(self.book / "sheets" / "나래.png") as kept:
            self.assertEqual((0, 0, 255), kept.convert("RGB").getpixel((0, 0)))

    def test_story_fix_uses_the_original_body_only(self):
        req, out = self.root / "fix.json", self.root / "out.json"
        for items in ([{"quote": "첫 문장.", "replace": "새 문장."}, {"quote": "새 문장.", "replace": "끝."}],
                      [{"quote": "첫 문장. 둘째", "replace": "x"}, {"quote": "둘째 문장.", "replace": "y"}]):
            lou.write_json(req, {"items": items})
            with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(ValueError):
                run.main(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(out)])
        self.assertEqual("첫 문장. 둘째 문장. 셋째 문장.", lou.read(self.book / "directions.json")[0]["body"])
        lou.write_json(req, {"items": [{"quote": "셋째 문장.", "replace": "마지막."}, {"quote": "첫 문장.", "replace": "처음."}]})
        with patch.object(run, "RUNS_DIR", self.root):
            run.main(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(out)])
        self.assertEqual("처음. 둘째 문장. 마지막.", lou.read(self.book / "directions.json")[0]["body"])
        lou.write_json(self.book / "pick.json", {"n": 7})                     # 고른 방향이 없어졌으면 다른 방향을 고치지 않는다
        with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(ValueError):
            run.main(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(out)])

    def test_outputs_and_run_ids_are_checked_before_any_work(self):
        req = self.root / "fix.json"
        lou.write_json(req, {"items": [{"quote": "첫 문장.", "replace": "x"}]})
        bad = [(["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", str(self.book / "directions.json")]),
               (["--run-id", "ep01", "--lou-story-fix", str(req), "--lou-out", "relative.json"]),
               (["--run-id", "../ep01", "--lou-story-fix", str(req), "--lou-out", str(self.root.parent / "o.json")]),
               (["--run-id", str(self.book), "--lou-story-fix", str(req), "--lou-out", str(self.root.parent / "o.json")])]
        for argv in bad:
            with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(SystemExit):
                run.main(argv)
        self.assertEqual("첫 문장. 둘째 문장. 셋째 문장.", lou.read(self.book / "directions.json")[0]["body"])

    def test_find_does_not_mix_works(self):
        other = episode(self.root, "x01", [{"what": "a"}])
        lou.write_json(other / "input.json", {"name": "다른 주인공"})
        path, out = self.root / "req.json", self.root.parent / "find-out.json"
        lou.write_json(path, dict(text="x", who="레오", runs=["ep01", "x01"]))
        with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(ValueError):
            run.main(["--lou-find", str(path), "--lou-out", str(out)])

    def test_plan_picks_the_most_used_basis(self):
        import loureview
        for n in (3, 4, 5):
            Image.new("RGB", (300, 600), "white").save(self.book / "pages" / f"page{n:02}.png")
        req, out = self.root / "plan.json", self.root.parent / "plan-out.json"
        lou.write_json(req, {"items": [dict(id="r1", page=2, method="keep", note="옷은 3장처럼.", basis=[3]),
                                       dict(id="r2", page=2, method="keep", note="빛은 5장처럼.", basis=[5]),
                                       dict(id="r3", page=2, method="keep", note="왕관은 5장처럼.", basis=[5])]})
        loureview.plan(self.book, req, out)
        job = lou.read(out)["jobs"][0]
        self.assertEqual(([5], [3]), (job["basis"], job["basis_text_only"]))

    def test_proxy_stage_model_has_its_own_name(self):
        import llm
        with patch.object(llm, "CODEX_PROXY_ENABLED", True), \
                patch.dict("os.environ", {"LOU_REVIEW_MODEL": "새어 들어온 값", "CODEX_PROXY_TEXT_MODEL": "기본"}, clear=False):
            self.assertEqual("기본", llm.model_for("LOU_REVIEW", "openai"))
            with patch.dict("os.environ", {"CODEX_PROXY_LOU_REVIEW_MODEL": "고른 값"}):
                self.assertEqual("고른 값", llm.model_for("LOU_REVIEW", "openai"))
