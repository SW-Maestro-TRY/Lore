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


if __name__ == "__main__":
    unittest.main()
