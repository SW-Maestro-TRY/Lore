import unittest
from pathlib import Path

import detailart


class KeepPageTest(unittest.TestCase):
    def template(self):
        return (detailart.HERE / "prompt/keep_page_prompt").read_text(encoding="utf-8")

    def test_note_is_verbatim(self):
        note = "  소매만\n{note} {sheets}  "
        self.assertTrue(detailart.keep_prompt(self.template(), note).endswith("고칠 것\n" + note))

    def test_prompt_names_attached_sheets_only_when_attached(self):
        self.assertNotIn("캐릭터 시트", detailart.keep_prompt(self.template(), "소매만"))
        self.assertIn("캐릭터 시트다(가람, 나래)", detailart.keep_prompt(self.template(), "소매만", ["가람", "나래"]))

    def test_refs_follow_the_note_not_the_scene(self):
        source, hero = Path("page03.png"), Path("sheet.png")
        cast = {name: Path(name + ".png") for name in ("가람", "나래", "다온")}
        char = {"name": "하늘"}
        # 메모에 이름이 없으면 지금 장만 — 주인공 시트도 붙이지 않는다
        self.assertEqual(detailart.keep_refs(source, "머리색을 밝게", char, {0: hero}, cast), ([source], []))
        # 메모에 나온 사람만, 주인공 먼저
        refs, names = detailart.keep_refs(source, "나래의 겉옷을 바꾸고 하늘은 그대로", char, {0: hero}, cast)
        self.assertEqual((refs, names), ([source, hero, cast["나래"]], ["하늘", "나래"]))

    def test_names_inside_quoted_lines_do_not_attach_sheets(self):
        cast = {name: Path(name + ".png") for name in ("가람", "나래")}
        refs, names = detailart.keep_refs(Path("p.png"), "가람의 말풍선 「나래에게도 맡기지 않았어」의 꼬리를 돌린다", None, {}, cast)
        self.assertEqual(names, ["가람"])

    def test_refs_stay_within_the_reference_limit(self):
        cast = {name: Path(name + ".png") for name in ("가람", "나래", "다온", "라온")}
        refs, names = detailart.keep_refs(Path("p.png"), "가람 나래 다온 라온", None, {}, cast)
        self.assertEqual(len(refs), detailart.REF_LIMIT)
        self.assertEqual(names, ["가람", "나래", "다온"])

    def test_basis_page_is_attached_last_and_named(self):
        cast = {name: Path(name + ".png") for name in ("가람", "나래", "다온")}
        basis = Path("page05.png")
        refs, names = detailart.keep_refs(Path("p.png"), "가람 나래 다온, 5장과 같은 왕관", None, {}, cast, (basis,))
        self.assertEqual(refs[-1], basis)
        self.assertEqual(len(refs), detailart.REF_LIMIT)
        self.assertEqual(names, ["가람", "나래"])              # 기준 장 자리를 위해 시트 하나를 덜 붙인다
        prompt = detailart.keep_prompt(self.template(), "왕관", names, [5])
        self.assertIn("마지막 그림은 같은 화의 5장이다", prompt)
        self.assertNotIn("마지막 그림", detailart.keep_prompt(self.template(), "왕관", names))

    def test_keep_prompt_allows_bubble_edits_but_guards_the_rest(self):
        text = self.template()
        self.assertIn("꼬리를 다른 사람 쪽으로 돌리기", text)
        self.assertIn("「」 안의 글은 한 글자도 다르지 않게", text)
        self.assertIn("적히지 않은 인물, 말풍선, 글자는", text)


class CommandLineTest(unittest.TestCase):
    """run.py 가 구도 유지 인자를 그림 함수까지 넘기는지 — 앞 단계 함수가 새 인자를 안 받아 다시 그리기가 전부 멈춘 적이 있다(2026-11-22)."""

    def test_detail_pages_reach_the_drawer_with_and_without_new_flags(self):
        import tempfile
        from unittest.mock import patch
        import run
        import json
        with tempfile.TemporaryDirectory() as tmp:
            book = Path(tmp) / "episode"
            (book / "pages").mkdir(parents=True)
            source = book / "pages" / "page02.png"
            source.write_bytes(b"x")
            for name, value in (("input.json", {"name": "가"}), ("scenes.json", {"scenes": [{"what": "가"}]}),
                                ("directions.json", [{"n": 1, "body": "본문"}])):
                (book / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            for extra, basis in (([], ()), (["--keep-basis", "3"], (3,))):
                with patch.object(run, "RUNS_DIR", Path(tmp)), \
                        patch.object(detailart, "draw_continue", return_value=[]) as drawer:
                    self.assertEqual(0, run.main(["--run-id", "episode", "--detail-pages", "--page", "2",
                                                  "--keep-from", str(source), "--note", "손만", *extra]))
                    self.assertTrue(drawer.called, extra)
                    self.assertEqual(basis, drawer.call_args.kwargs.get("keep_basis"))
                    self.assertEqual("손만", drawer.call_args.kwargs.get("note"))


if __name__ == "__main__":
    unittest.main()
