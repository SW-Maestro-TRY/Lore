import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import lou
import loureview
import run



def main(argv):
    """run.main — 결과 파일 자리를 비우고 부른다(루 명령은 내용이 있는 결과 파일을 덮지 않는다)."""
    if "--lou-out" in argv:
        out = Path(argv[argv.index("--lou-out") + 1])
        if out.is_file():
            out.unlink()
    return run.main(argv)

class LouReviewTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.book = self.root / "episode"
        (self.book / "pages").mkdir(parents=True)
        for n in (1, 2, 3, 4):
            Image.new("RGB", (600, 1200), "white").save(self.book / "pages" / f"page{n:02}.png")
        Image.new("RGB", (300, 200), "pink").save(self.book / "sheet.png")
        (self.book / "sheets").mkdir()
        Image.new("RGB", (300, 200), "gray").save(self.book / "sheets" / "마바 사아.png")
        Image.new("RGB", (300, 200), "black").save(self.book / "sheets" / "안 나오는 사람.png")
        lou.write_json(self.book / "input.json", {"name": "가나 다라", "lorebook": {"progress": [
            {"title": "가나의 겉모습", "kind": "겉모습", "text": "[시트와 다름: 푸른 눈] 붕대", "ep": 3},
            {"title": "가나의 겉모습", "kind": "겉모습", "text": "옛 모습", "ep": 1},
            {"title": "어딘가", "kind": "지금", "text": "성 안", "ep": 3}]}})
        lou.write_json(self.book / "cast.json", [{"name": "마바 사아", "look": "은발"}])
        lou.write_json(self.book / "directions.json", [{"n": 1, "title": "제목", "body": "본문"}])
        lou.write_json(self.book / "scenes.json", {"scenes": [
            {"where": "성", "acting": "마바가 “지금 바로 간다.”라고 말한다."}, {"what": "가나 혼자"}, {"what": "둘"}]})

    def call(self, answer, seen=None):
        """answer(prompt) -> 모델 답. 장마다 동시에 묻으므로 순서가 아니라 질문 내용으로 답한다."""
        request = self.root / "req.json"
        out = self.root / "out.json"
        lou.write_json(request, {"lang": "ko"})
        prompts = []

        class Fake:
            def __init__(self, stage):
                self.provider, self.model = "fake", "fake"

            def __call__(self, prompt, images=None, temperature=None):
                prompts.append(prompt)
                reply = answer(prompt)
                return (reply if isinstance(reply, str) else json.dumps(reply, ensure_ascii=False)), {"stage": "LOU"}

        with patch.object(run, "RUNS_DIR", self.root), \
                patch.object(lou, "read_text", side_effect=lambda p: ((seen or {}).get(p.name[4:6], []), {"stage": "LOU"})), \
                patch.object(loureview.llm, "Call", Fake):
            main(["--run-id", "episode", "--lou-review", str(request), "--lou-out", str(out)])
        return lou.read(out), prompts

    @staticmethod
    def all_real(prompt, unless=()):
        """다시 확인 — 질문에 든 항목을 모두 진짜로(unless 에 든 번호만 아니라고)."""
        import re as _re
        ids = sorted(set(_re.findall(r'"id": "(r\d+)"', prompt)))
        return dict(checks=[dict(id=i, real=i not in unless, why="" if i not in unless else "지금 상태가 푸른 눈이에요.")
                            for i in ids])

    @staticmethod
    def kind(prompt):
        if "정말 그런지 확인한다" in prompt:
            return "verify"
        if "이번에 맡은 것은 「이해」" in prompt:
            return "story"
        return int(prompt.split('"target": ')[1].split(",")[0])

    def test_review_lists_checks_and_drops(self):
        def answer(prompt):
            what = self.kind(prompt)
            if what == 2:
                return dict(items=[dict(page=2, kind="bubble", sev="P1", where="1번째 컷", what="꼬리가 반대", method="keep",
                                        note="1번째 컷 말풍선 「간다.」의 꼬리를 오른쪽 은발 남자 쪽으로 돌린다."),
                                   dict(page=3, kind="panel", sev="P1", what="이웃 장 이야기", note="3장을 고친다")])
            if what == 3:
                return dict(items=[dict(kind="person", sev="P2", recommend=False, where="2번째 컷", what="눈이 푸름",
                                        cause="picture", note="눈을 붉게", basis=[2, 9]),
                                   dict(what="", note="빈 설명")])
            if what == "story":
                return dict(reply="두 장에서 찾았어요.", items=[dict(page=4, kind="understanding", sev="P2", what="소개 없음",
                                                                    note="1번째 컷 위에 상자 「성 안.」을 넣는다.")])
            if what == "verify":
                return self.all_real(prompt, unless=("r2",))
            return dict(items=[])
        result, prompts = self.call(answer)
        self.assertEqual("두 장에서 찾았어요.", result["reply"])
        # 2장 검토가 3장 번호를 댄 항목은 버린다
        self.assertEqual([(2, "P1"), (4, "P2")], [(it["page"], it["sev"]) for it in result["items"]])
        self.assertTrue(result["items"][0]["note"].endswith("나머지는 그대로."))
        self.assertEqual("r2", result["dropped"][0]["id"])
        self.assertEqual([2], result["dropped"][0]["basis"])           # 없는 장은 기준에서 뺀다
        self.assertIn("지금 상태", result["dropped"][0]["why"])
        page2 = next(p for p in prompts if '"target": 2,' in p)
        self.assertIn("[시트와 다름: 푸른 눈] 붕대", page2)             # 지금 인물 상태는 가장 최근 겉모습
        self.assertNotIn("옛 모습", page2)
        self.assertIn('"neighbors": [1, 3]', page2)
        self.assertIn('"sheets": ["마바 사아"]', page2)                  # 이 장에 나오는 사람의 시트만

    def test_wrong_letters_reach_the_page_review(self):
        def answer(prompt):
            return dict(checks=[]) if self.kind(prompt) == "verify" else dict(reply="없어요.", items=[])
        _result, prompts = self.call(answer, seen={"02": ["지금 바로 간다."]})
        self.assertIn('"text_diff": []', next(p for p in prompts if '"target": 2,' in p))
        _result, prompts = self.call(answer, seen={"02": ["지금 바로 긴다."]})
        self.assertIn('"seen": "지금 바로 긴다."', next(p for p in prompts if '"target": 2,' in p))

    def test_a_broken_page_does_not_sink_the_rest(self):
        def answer(prompt):
            what = self.kind(prompt)
            if what == 3:
                return "망가진 답"
            if what == 2:
                return dict(items=[dict(kind="panel", sev="P1", what="손가락 여섯", note="손가락을 다섯으로.")])
            return self.all_real(prompt) if what == "verify" else dict(reply="", items=[])
        result, _prompts = self.call(answer)
        self.assertEqual([2], [it["page"] for it in result["items"]])
        self.assertEqual([3], result["failed_pages"])

    def test_unverified_items_are_not_recommended(self):
        def answer(prompt):
            what = self.kind(prompt)
            if what == 2:
                return dict(items=[dict(kind="panel", sev="P1", what="손가락 여섯", note="손가락을 다섯으로.")])
            if what == "verify":
                return dict(checks=[dict(id="r1", why="real 칸이 없다")])
            return dict(reply="", items=[])
        result, _prompts = self.call(answer)
        self.assertEqual([(2, False, True)], [(it["page"], it["recommend"], it.get("unverified")) for it in result["items"]])
        self.assertEqual([2], result["failed_pages"])

    def test_plan_groups_by_page(self):
        request = self.root / "plan.json"
        out = self.root / "jobs.json"
        items = [dict(id="r1", page=2, method="keep", note="1번째 컷 꼬리를 돌린다. 나머지는 그대로.", basis=[]),
                 dict(id="r2", page=2, method="keep", note="3번째 컷 손가락을 다섯으로 나머지는 그대로", basis=[3]),
                 dict(id="r3", page=3, method="redraw", note="컷을 둘로 나눈다. 나머지는 그대로."),
                 dict(id="r4", page=3, method="keep", note="효과음 「쾅」을 지운다. 나머지는 그대로.")]
        lou.write_json(request, {"items": items})
        with patch.object(run, "RUNS_DIR", self.root):
            main(["--run-id", "episode", "--lou-plan", str(request), "--lou-out", str(out)])
        jobs = lou.read(out)["jobs"]
        self.assertEqual([(2, "keep", ["r1", "r2"], [3]), (3, "redraw", ["r3", "r4"], [])],
                         [(j["page"], j["method"], j["items"], j["basis"]) for j in jobs])
        self.assertEqual(1, jobs[0]["note"].count("나머지는 그대로"))
        self.assertIn("꼬리를 돌린다. 3번째 컷 손가락을 다섯으로.", jobs[0]["note"])
        lou.write_json(request, {"items": [dict(page=9, method="keep", note="x")]})
        with patch.object(run, "RUNS_DIR", self.root), self.assertRaises(ValueError):
            main(["--run-id", "episode", "--lou-plan", str(request), "--lou-out", str(out)])


if __name__ == "__main__":
    unittest.main()
