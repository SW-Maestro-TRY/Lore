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


if __name__ == "__main__":
    unittest.main()
