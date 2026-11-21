import unittest
from pathlib import Path

import detailart


class KeepPageTest(unittest.TestCase):
    def test_note_is_verbatim(self):
        template = (detailart.HERE / "prompt/keep_page_prompt").read_text()
        note = "  소매만\n{note}  "
        self.assertTrue(detailart.keep_prompt(template, note).endswith("고칠 것\n" + note))

    def test_refs_include_scene_and_note_with_existing_limit(self):
        source, hero = Path("page03.png"), Path("sheet.png")
        cast = {name: Path(name + ".png") for name in ("가람", "나래", "다온")}
        refs = detailart.keep_refs(source, {"acting": "가람"}, "나래와 다온", None, {0: hero}, cast)
        self.assertEqual(refs, [source, hero, cast["가람"], cast["나래"]])


if __name__ == "__main__":
    unittest.main()
