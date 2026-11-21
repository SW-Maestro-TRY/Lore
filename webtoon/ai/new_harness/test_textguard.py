import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

import lou
import textfix
import textguard


def page(second):
    """말풍선 둘 — 위는 하얀 안쪽, 아래는 살짝 회색 안쪽. 아래 말풍선의 글만 다르게 그린다."""
    image = Image.new("RGB", (600, 900), (90, 110, 150))
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(textfix.font_file(), 26)
    draw.ellipse((60, 60, 420, 260), fill=(255, 255, 255), outline="black", width=3)
    draw.text((240, 160), "위의 말풍선 글", font=font, fill=(20, 20, 20), anchor="mm")
    draw.ellipse((120, 420, 540, 640), fill=(232, 232, 232), outline="black", width=3)
    draw.text((330, 530), second, font=font, fill=(20, 20, 20), anchor="mm")
    return image


def reader_many(paths):
    return [reader(p) for p in paths]


def reader(path):
    """가짜 글자 옮겨 적기 — 안쪽 색으로 어느 말풍선인지, 파일 이름 끝(b/a)으로 고치기 전 · 후를 안다."""
    with Image.open(path) as im:
        corner = im.convert("RGB").getpixel((im.width // 2, im.height // 6))
    top = corner[0] > 245
    after = path.stem.endswith("a")
    if top:
        return ["위의 말풍선 글"]
    return ["가져다고 했어"] if after else ["가겠다고 했어"]


class TextGuardTest(unittest.TestCase):
    def test_finds_both_bubbles(self):
        found = textguard.bubbles(page("가겠다고 했어"))
        self.assertEqual(2, len(found))

    def test_drifted_bubble_is_restored_and_the_rest_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
            page("가겠다고 했어").save(before)
            changed = page("가져다고 했어")
            ImageDraw.Draw(changed).rectangle((5, 700, 80, 780), fill="red")      # 고치라고 한 곳(말풍선 밖)
            changed.save(after)
            done = textguard.restore(before, after, "왼쪽 아래 빨간 상자를 넣는다.", reader_many, lou.squash, lou.near,
                                     lou.quoted)
            self.assertEqual([("가겠다고 했어", "가져다고 했어")], done)
            with Image.open(before) as b, Image.open(after) as a:
                b, a = b.convert("RGB"), a.convert("RGB")
                self.assertIsNone(ImageChops.difference(b.crop((200, 500, 460, 560)), a.crop((200, 500, 460, 560))).getbbox())
                self.assertEqual((255, 0, 0), a.getpixel((40, 740)))

    def test_asked_bubble_is_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
            page("가겠다고 했어").save(before)
            page("가져다고 했어").save(after)
            done = textguard.restore(before, after, "아래 말풍선 글을 「가져다고 했어」로 바꾼다.", reader_many, lou.squash,
                                     lou.near, lou.quoted)
            self.assertEqual([], done)

    def test_unchanged_page_needs_no_reading(self):
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
            page("가겠다고 했어").save(before)
            page("가겠다고 했어").save(after)
            def boom(paths):
                raise AssertionError("같은 그림이면 옮겨 적지 않는다")
            self.assertEqual([], textguard.restore(before, after, "", boom, lou.squash, lou.near, lou.quoted))


if __name__ == "__main__":
    unittest.main()
