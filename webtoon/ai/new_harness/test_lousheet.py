import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import lou
import lousheet
import run


def png(path, colour):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (300, 200), colour).save(path)


class LouSheetTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.book = self.root / "episode"
        lou.write_json(self.book / "input.json", {"name": "가나 다라", "description": "분홍 머리"})
        lou.write_json(self.book / "cast.json", [{"name": "마바 사아", "look": "은발"}, {"name": "자차", "look": ""}])
        lou.write_json(self.book / "scenes.json", {"scenes": [{"what": "마바가 들어온다"}, {"what": "가나 혼자"},
                                                              {"acting": "마바 사아가 웃는다"}]})
        png(self.book / "sheet.png", "pink")
        png(self.book / "sheets" / "마바 사아.png", "gray")
        png(self.book / "sheets" / "카렌.png", "black")

    def test_find_by_short_name(self):
        self.assertEqual("sheet.png", lousheet.find(self.book, "가나")[1].name)
        self.assertEqual("마바 사아", lousheet.find(self.book, "마바")[0])
        self.assertIsNone(lousheet.find(self.book, "자차"))
        self.assertEqual([2, 4], lousheet.appears(self.book, "마바 사아"))

    def test_clean_checks_names_and_adds_scope(self):
        plan = dict(method="sheet_keep", target="마바 사아", base="", refs=["카렌"], changes="머리를 검게",
                    keeps="얼굴", note="모든 칸에서 머리를 검게", look="검은 머리")
        data = dict(reply="네", found=[], plans=[plan])
        out = lousheet.clean(data, self.book, "마바 사아", [2, 4])
        self.assertEqual([2, 4], out["plans"][0]["more_pages"])
        self.assertIn("마바 사아", out["plans"][0]["follow_note"])
        with self.assertRaises(ValueError):
            lousheet.clean(dict(data, plans=[dict(plan, refs=["없는 사람"])]), self.book, "마바 사아", [])
        with self.assertRaises(ValueError):           # 시트가 없는 사람의 시트에서 시작할 수는 없다
            lousheet.clean(dict(data, plans=[dict(plan, target="자차", refs=[])]), self.book, "자차", [])
        new = lousheet.clean(dict(data, plans=[dict(plan, method="sheet_new", target="자차", base="카렌", refs=[])]),
                             self.book, "자차", [])
        self.assertEqual("카렌", new["plans"][0]["base"])

    def drafted(self, target, colour="red", name="cand.png"):
        out = self.book / "sheets" / "drafts" / name
        png(out, colour)
        lou.write_json(out.with_suffix(".json"), {"target": lousheet.full_name(self.book, target)})
        return out

    def test_draft_keeps_the_sheet_first(self):
        request = self.root / "plan.json"
        lou.write_json(request, dict(method="sheet_keep", target="마바", refs=["카렌"], note="눈만 검게"))
        out = self.book / "sheets" / "drafts" / "cand.png"
        with patch.object(lousheet.imagegen, "paint", return_value={"stage": "CAST_SHEET_IMAGE"}) as paint:
            lousheet.draft(self.book, request, out)
            with self.assertRaises(ValueError):          # 후보는 그 작품의 drafts 안에만
                lousheet.draft(self.book, request, self.root / "cand.png")
        prompt, written = paint.call_args.args[1:3]
        self.assertIn("눈만 검게", prompt)
        self.assertIn("카렌", prompt)
        self.assertEqual(["마바 사아.png", "카렌.png"], [p.name for p in paint.call_args.kwargs["refs"]])
        self.assertEqual(out, written)
        self.assertEqual("마바 사아", lou.read(out.with_suffix(".json"))["target"])

    def test_pick_keeps_old_versions_and_updates_the_look(self):
        candidate = self.drafted("마바")
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "마바", "from": str(candidate), "look": "검은 머리"})
        with patch.object(run, "RUNS_DIR", self.root):
            run.main(["--run-id", "episode", "--lou-sheet-pick", str(request)])
        self.assertTrue((self.book / "sheets" / "versions" / "마바 사아.v1.png").exists())
        with Image.open(self.book / "sheets" / "마바 사아.png") as now:
            self.assertEqual((255, 0, 0), now.convert("RGB").getpixel((0, 0)))
        self.assertEqual("검은 머리", lou.read(self.book / "cast.json")[0]["look"])
        hero = self.drafted("가나 다라", name="hero.png")
        lou.write_json(request, {"target": "가나 다라", "from": str(hero), "look": "붉은 드레스"})
        lousheet.pick(self.book, request)
        self.assertTrue((self.book / "sheets" / "versions" / "sheet.v1.png").exists())
        self.assertEqual("붉은 드레스", lou.read(self.book / "input.json")["description"])

    def test_pick_refuses_other_files_and_other_people(self):
        request = self.root / "pick.json"
        outside = self.root / "elsewhere.png"
        png(outside, "red")
        lou.write_json(outside.with_suffix(".json"), {"target": "마바"})
        for source, target in ((outside, "마바"), (self.drafted("카렌"), "마바")):
            lou.write_json(request, {"target": target, "from": str(source)})
            with self.assertRaises(ValueError):
                lousheet.pick(self.book, request)
        with Image.open(self.book / "sheets" / "마바 사아.png") as now:
            self.assertEqual((128, 128, 128), now.convert("RGB").getpixel((0, 0)))

    def test_drafts_folder_pointing_outside_is_refused(self):
        outside = self.root / "elsewhere"
        outside.mkdir()
        shutil.rmtree(self.book / "sheets" / "drafts", ignore_errors=True)
        (self.book / "sheets" / "drafts").symlink_to(outside, target_is_directory=True)
        png(outside / "cand.png", "red")
        lou.write_json(outside / "cand.json", {"target": "마바 사아"})
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "마바", "from": str(self.book / "sheets" / "drafts" / "cand.png")})
        with self.assertRaises(ValueError):
            lousheet.pick(self.book, request)
        plan = self.root / "plan.json"
        lou.write_json(plan, dict(method="sheet_keep", target="마바", note="눈"))
        with self.assertRaises(ValueError):
            lousheet.draft(self.book, plan, self.book / "sheets" / "drafts" / "new.png", dry=True)

    def test_short_name_cannot_pick_for_someone_else(self):
        png(self.book / "sheets" / "민수.png", "blue")
        lou.write_json(self.book / "cast.json", [{"name": "김 민수", "look": ""}, {"name": "민수", "look": ""}])
        candidate = self.drafted("김 민수")
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "민수", "from": str(candidate)})
        with self.assertRaises(ValueError):
            lousheet.pick(self.book, request)
        lou.write_json(request, {"target": "김 민수", "from": str(candidate)})
        self.assertEqual(self.book / "sheets" / "김 민수.png", lousheet.pick(self.book, request))
        with Image.open(self.book / "sheets" / "민수.png") as other:
            self.assertEqual((0, 0, 255), other.convert("RGB").getpixel((0, 0)))

    def test_draft_does_not_start_from_a_namesakes_sheet(self):
        png(self.book / "sheets" / "민수.png", "blue")
        lou.write_json(self.book / "cast.json", [{"name": "김 민수", "look": ""}, {"name": "민수", "look": ""}])
        plan = self.root / "plan.json"
        lou.write_json(plan, dict(method="sheet_keep", target="김 민수", note="머리"))
        with patch.object(lousheet.imagegen, "paint") as paint, self.assertRaises(ValueError):
            lousheet.draft(self.book, plan, self.book / "sheets" / "drafts" / "c.png")
        paint.assert_not_called()
        data = dict(reply="네", found=[], plans=[dict(method="sheet_keep", target="김 민수", changes="머리", note="머리")])
        with self.assertRaises(ValueError):
            lousheet.clean(data, self.book, "김 민수", [])

    def test_sheet_question_for_a_namesake_without_a_sheet_is_refused(self):
        png(self.book / "sheets" / "민수.png", "blue")
        lou.write_json(self.book / "cast.json", [{"name": "김 민수", "look": ""}, {"name": "민수", "look": ""}])
        request = self.root / "ask.json"
        lou.write_json(request, dict(text="머리", sheet="김 민수", pins=[], lang="ko"))
        with patch.object(lousheet.llm, "Call") as factory, self.assertRaises(ValueError):
            lousheet.ask(self.book, lou.read(request), self.root / "out.json")
        factory.assert_not_called()
        self.assertEqual("민수", lousheet.sheet_named(self.book, "민수")[0])
        self.assertEqual("마바 사아", lousheet.sheet_named(self.book, "마바")[0])

    def test_new_sheet_names_are_checked_against_other_people(self):
        lou.write_json(self.book / "input.json", {"name": "엘리아나 드 로제로", "description": "분홍 머리"})
        lou.write_json(self.book / "cast.json", [{"name": "로제로 백작", "role": "주인공의 아버지"},
                                                {"name": "재상 카를로스", "role": "재상"}, {"name": "재상 보좌", "role": "재상의 비서"},
                                                {"name": "마바 사아", "look": "은발"}])
        # 제품의 찾는 말에는 세 글자 낱말의 성을 뺀 꼴(「제로」)도 들어간다 — 그것도 겹친다
        self.assertEqual([("로제로", "엘리아나 드 로제로"), ("제로", "엘리아나 드 로제로")],
                         [(c["key"], c["other"]) for c in lousheet.name_clashes(self.book, "로제로 백작")])
        self.assertEqual("백작", lousheet.safe_name(self.book, "로제로 백작"))       # 이 작품에 백작은 하나뿐
        self.assertIn(("재상", "재상 보좌"), [(c["key"], c["other"]) for c in lousheet.name_clashes(self.book, "재상 카를로스")])
        self.assertEqual("카를로스", lousheet.safe_name(self.book, "재상 카를로스"))
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "로제로 백작", "from": str(self.drafted("로제로 백작"))})
        with self.assertRaises(SystemExit) as stop:
            lousheet.pick(self.book, request)
        self.assertIn("「백작」", str(stop.exception))
        lou.write_json(request, {"target": "로제로 백작", "from": str(self.drafted("로제로 백작")), "file_name": "백작"})
        self.assertEqual(self.book / "sheets" / "백작.png", lousheet.pick(self.book, request))

    def test_names_do_not_slide_to_someone_else(self):
        png(self.book / "sheets" / "카를로스.png", "blue")
        png(self.book / "sheets" / "sheet.png", "green")            # 이름이 sheet 인 조연
        lou.write_json(self.book / "cast.json", [{"name": "카를로스", "look": "붉은 머리"}, {"name": "카를", "look": ""},
                                                {"name": "sheet", "look": ""}])
        self.assertIsNone(lousheet.find(self.book, "카를"))
        self.assertEqual("카를", lousheet.person_of(self.book, "카를")["name"])
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "카를", "from": str(self.drafted("카를")), "look": "검은 머리"})
        self.assertEqual(self.book / "sheets" / "카를.png", lousheet.pick(self.book, request))
        with Image.open(self.book / "sheets" / "카를로스.png") as carlos:
            self.assertEqual((0, 0, 255), carlos.convert("RGB").getpixel((0, 0)))
        self.assertEqual(["붉은 머리", "검은 머리"], [c["look"] for c in lou.read(self.book / "cast.json")[:2]])
        lou.write_json(request, {"target": "sheet", "from": str(self.drafted("sheet", name="s.png")), "look": "조연"})
        lousheet.pick(self.book, request)
        self.assertEqual("분홍 머리", lou.read(self.book / "input.json")["description"])

    def test_sheet_question_goes_to_the_sheet_prompt(self):
        request = self.root / "ask.json"
        out = self.root / "out.json"
        lou.write_json(request, dict(text="카렌이랑 너무 닮았어", sheet="마바", pins=[dict(n=1, x=10, y=10)], lang="ko"))
        answer = dict(reply="머리를 바꿔요.", found=[dict(pin=1, what="hair")], cause=dict(layer="character"),
                      plans=[dict(method="sheet_keep", target="마바 사아", changes="머리", keeps="얼굴", note="머리")])
        with patch.object(run, "RUNS_DIR", self.root), patch.object(lousheet.llm, "Call") as factory:
            factory.return_value.return_value = (json.dumps(answer, ensure_ascii=False), {"stage": "LOU"})
            run.main(["--run-id", "episode", "--lou-ask", str(request), "--lou-out", str(out)])
            prompt = factory.return_value.call_args.args[0]
        result = lou.read(out)
        self.assertIn("캐릭터 시트", prompt)
        self.assertIn('"shown": ["카렌"]', prompt)
        self.assertEqual("마바 사아", result["sheet"])
        self.assertEqual([2, 4], result["plans"][0]["more_pages"])


if __name__ == "__main__":
    unittest.main()
