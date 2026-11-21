import json
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

    def test_draft_keeps_the_sheet_first(self):
        request = self.root / "plan.json"
        lou.write_json(request, dict(method="sheet_keep", target="마바", refs=["카렌"], note="눈만 검게"))
        with patch.object(lousheet.imagegen, "paint", return_value={"stage": "CAST_SHEET_IMAGE"}) as paint:
            lousheet.draft(self.book, request, self.root / "cand.png")
        prompt, out = paint.call_args.args[1:3]
        self.assertIn("눈만 검게", prompt)
        self.assertIn("카렌", prompt)
        self.assertEqual(["마바 사아.png", "카렌.png"], [p.name for p in paint.call_args.kwargs["refs"]])
        self.assertEqual(self.root / "cand.png", out)

    def test_pick_keeps_old_versions_and_updates_the_look(self):
        candidate = self.root / "cand.png"
        png(candidate, "red")
        request = self.root / "pick.json"
        lou.write_json(request, {"target": "마바", "from": str(candidate), "look": "검은 머리"})
        with patch.object(run, "RUNS_DIR", self.root):
            run.main(["--run-id", "episode", "--lou-sheet-pick", str(request)])
        self.assertTrue((self.book / "sheets" / "versions" / "마바 사아.v1.png").exists())
        with Image.open(self.book / "sheets" / "마바 사아.png") as now:
            self.assertEqual((255, 0, 0), now.convert("RGB").getpixel((0, 0)))
        self.assertEqual("검은 머리", lou.read(self.book / "cast.json")[0]["look"])
        lou.write_json(request, {"target": "가나 다라", "from": str(candidate), "look": "붉은 드레스"})
        lousheet.pick(self.book, request)
        self.assertTrue((self.book / "sheets" / "versions" / "sheet.v1.png").exists())
        self.assertEqual("붉은 드레스", lou.read(self.book / "input.json")["description"])

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
