import json
import tempfile
import unittest
import unittest.mock
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

import run
import textfix


def page():
    """색이 있는 그림 위에 말풍선 둘이 붙어 있는 장. 아래 풍선의 글자가 틀렸다."""
    image = Image.new("RGB", (600, 800), (90, 110, 150))
    draw = ImageDraw.Draw(image)
    draw.ellipse((60, 80, 380, 260), fill="white", outline="black", width=3)
    draw.ellipse((90, 230, 420, 430), fill="white", outline="black", width=3)
    draw.ellipse((110, 236, 360, 256), fill="white")          # 두 풍선의 안쪽이 이어져 있다
    font = ImageFont.truetype(textfix.font_file(), 24)
    draw.text((220, 170), "위쪽 풍선의 글", font=font, fill=(20, 20, 20), anchor="mm")
    for i, line in enumerate(("새벽엔 총소리도", "났오.")):
        draw.text((255, 310 + i * 36), line, font=font, fill=(20, 20, 20), anchor="mm")
    return image


class TextFixTest(unittest.TestCase):
    def test_only_the_pointed_block_changes(self):
        before = page()
        after = textfix.patch(before, 255, 320, "새벽엔 총소리도 났고.")
        left, top, right, bottom = ImageChops.difference(before, after).getbbox()
        self.assertGreaterEqual(top, 280)                      # 위쪽 풍선의 글은 그대로
        self.assertTrue(120 <= left and right <= 400 and bottom <= 400)
        self.assertEqual((90, 110, 150), after.getpixel((500, 600)))

    def test_side_by_side_bubbles(self):
        """안쪽이 이어진 말풍선이 좌우로 붙어 있으면 짚은 쪽 글만 바뀐다."""
        image = Image.new("RGB", (800, 1200), (90, 110, 150))
        draw = ImageDraw.Draw(image)
        draw.ellipse((20, 60, 420, 340), fill="white", outline="black", width=3)
        draw.ellipse((380, 60, 780, 340), fill="white", outline="black", width=3)
        draw.rectangle((380, 180, 420, 220), fill="white")
        font = ImageFont.truetype(textfix.font_file(), 26)
        draw.text((200, 200), "왼쪽 글자", font=font, fill=(20, 20, 20), anchor="mm")
        draw.text((600, 200), "오른쪽 글자", font=font, fill=(20, 20, 20), anchor="mm")
        after = textfix.patch(image, 200, 200, "왼쪽 고친 글")
        left, _top, right, _bottom = ImageChops.difference(image, after).getbbox()
        self.assertLess(right, 420)

    def test_side_by_side_bubbles_with_a_narrow_gap(self):
        """두 글이 붙다시피 해도 말풍선 사이 잘록한 목에서 가른다."""
        image = Image.new("RGB", (800, 1200), (90, 110, 150))
        draw = ImageDraw.Draw(image)
        draw.ellipse((20, 60, 420, 340), fill="white", outline="black", width=3)
        draw.ellipse((380, 60, 780, 340), fill="white", outline="black", width=3)
        draw.rectangle((380, 180, 420, 220), fill="white")
        font = ImageFont.truetype(textfix.font_file(), 26)
        draw.text((394, 200), "왼쪽 글자", font=font, fill=(20, 20, 20), anchor="rm")
        draw.text((406, 200), "오른쪽 글자", font=font, fill=(20, 20, 20), anchor="lm")
        after = textfix.patch(image, 380, 200, "왼쪽 고친 글")
        _left, _top, right, _bottom = ImageChops.difference(image, after).getbbox()
        self.assertLessEqual(right, 402)

    def test_long_lines_wrap_quickly(self):
        import time
        words = [f"낱말{i}" for i in range(30)]
        started = time.monotonic()
        lines = textfix._wrap(words, [120] * 8, lambda s: len(s) * 10.0)
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(8, len(lines))
        self.assertEqual(" ".join(words), " ".join(lines))

    def test_no_bubble_under_the_pin(self):
        with self.assertRaises(textfix.NoBubble):
            textfix.patch(page(), 520, 700, "글")

    def test_command_keeps_the_old_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            book = Path(tmp) / "episode" / "pages"
            book.mkdir(parents=True)
            page().save(book / "page02.png")
            request = Path(tmp) / "fix.json"
            request.write_text(json.dumps({"items": [{"x": 42, "y": 40, "text": "새벽엔 총소리도 났고."}]}), encoding="utf-8")
            with unittest.mock.patch.object(run, "RUNS_DIR", Path(tmp)):
                self.assertEqual(0, run.main(["--run-id", "episode", "--page", "2", "--fix-text", str(request)]))
                self.assertTrue((book / "versions" / "page02.v1.png").exists())
                with Image.open(book / "page02.png") as now, Image.open(book / "versions" / "page02.v1.png") as old:
                    self.assertIsNotNone(ImageChops.difference(now.convert("RGB"), old.convert("RGB")).getbbox())
                request.write_text(json.dumps({"items": [{"x": 90, "y": 90, "text": "글"}]}), encoding="utf-8")
                with self.assertRaises(SystemExit):
                    run.main(["--run-id", "episode", "--page", "2", "--fix-text", str(request)])
                self.assertFalse((book / "versions" / "page02.v2.png").exists())


if __name__ == "__main__":
    unittest.main()
