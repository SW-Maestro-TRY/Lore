import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

import lou
import textfix
import textguard


def page(second):
    """말풍선 둘 — 위는 하얀 안쪽, 아래는 아주 살짝 회색 안쪽. 아래 말풍선의 글만 다르게 그린다."""
    image = Image.new("RGB", (600, 900), (90, 110, 150))
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(textfix.font_file(), 26)
    draw.ellipse((60, 60, 420, 260), fill=(255, 255, 255), outline="black", width=3)
    draw.text((240, 160), "위의 말풍선 글", font=font, fill=(20, 20, 20), anchor="mm")
    draw.ellipse((120, 420, 540, 640), fill=(248, 248, 248), outline="black", width=3)
    draw.text((330, 530), second, font=font, fill=(20, 20, 20), anchor="mm")
    return image


def reader_many(paths):
    return [reader(p) for p in paths]


def reader(path):
    """가짜 글자 옮겨 적기 — 안쪽 색으로 어느 말풍선인지, 파일 이름 끝(b/a)으로 고치기 전 · 후를 안다."""
    with Image.open(path) as im:
        corner = im.convert("RGB").getpixel((im.width // 2, im.height // 6))
    top = corner[0] > 251
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

    def test_light_clothes_are_not_bubbles(self):
        image = page("가겠다고 했어")
        draw = ImageDraw.Draw(image)
        for y in range(700, 860):                                     # 명암이 진 흰 셔츠 + 가슴 무늬
            draw.line((80, y, 260, y), fill=(225 + (y % 25), 225 + (y % 25), 225 + (y % 25)))
        draw.rectangle((80, 700, 260, 860), outline="black", width=3)
        draw.text((170, 780), "무늬", fill=(20, 20, 20), anchor="mm")
        self.assertEqual(2, len(textguard.bubbles(image)))

    def test_text_change_without_quotes_is_left_alone(self):
        """지시가 따옴표 없이 바꿀 글을 적었어도, 고친 글이 지시에 적혀 있으면 되돌리지 않는다."""
        def read_many(paths):
            return [(["내일 만나"] if p.stem.endswith("a") and not reader(p) == ["위의 말풍선 글"] else reader(p)) for p in paths]
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
            page("가겠다고 했어").save(before)
            page("내일 만나").save(after)
            for note in ("아래 말풍선 글을 내일 만나로 바꿔", "둘째 줄을 내일 만나로 바꿔"):
                self.assertEqual([], textguard.restore(before, after, note, read_many, lou.squash, lou.near, lou.quoted))
            # 지시가 말풍선을 말하지만 글을 바꾸라는 것이 아니면 지키기는 그대로 돈다
            self.assertEqual([("가겠다고 했어", "내일 만나")],
                             textguard.restore(before, after, "옷만 고치고 말풍선은 그대로", read_many, lou.squash, lou.near,
                                               lou.quoted))

    def test_one_stroke_change_is_restored_even_if_read_the_same(self):
        """읽는 눈은 한 획 차이를 못 본다 — 똑같이 읽혀도, 고치라고 하지 않은 말풍선이면 원본 픽셀로 되돌린다."""
        def same(paths):
            return [["위의 말풍선 글"] if reader(p) == ["위의 말풍선 글"] else ["일을 맡고 싶어"] for p in paths]
        cases = (("왼쪽 남자의 옷을 흰 셔츠로 바꾼다. 나머지는 그대로.", True),
                 ("지도의 목적지 옆에 「수도」를 적어 주세요. 나머지는 그대로.", True),
                 ("효과음 「의자 소리..」를 「드르륵」으로 바꾼다.", True),
                 ("「세라.」 말풍선의 꼬리를 오른쪽 여자 쪽으로 돌린다.", True),
                 ("옷만 바꾸고 말풍선은 그대로 둔다.", True),
                 ("말풍선의 「일을 맡고 싶어」로 글을 고친다.", False),      # 고치라고 한 말풍선
                 ("「맡고」로 낱말을 고친다.", False),                      # 낱말만 적어도
                 ("아래 말풍선 글을 존댓말로 바꾼다.", False))              # 어느 글인지 안 적었다
        for note, restored in cases:
            with tempfile.TemporaryDirectory() as tmp:
                before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
                page("일을 맡고 싶어").save(before)
                page("일을 말고 싶어").save(after)
                done = textguard.restore(before, after, note, same, lou.squash, lou.near, lou.quoted)
                self.assertEqual(restored, bool(done), note)

    def test_asked_one_stroke_fix_survives_being_read_as_the_old_letter(self):
        """정확히 고쳤는데 읽는 눈이 둘 다 옛 글자로 읽어도 고친 것을 되돌리지 않는다."""
        def old_letter(paths):
            return [["위의 말풍선 글"] if reader(p) == ["위의 말풍선 글"] else ["일을 말고 싶어"] for p in paths]
        for note in ("아래 말풍선에서 한 글자를 「맡」으로 고친다. 나머지는 그대로.", "「말」을 「맡」으로 바꿔 주세요.",
                     "아래 말풍선에서 한 글자를 「맡」으로 교체한다.", "「말」 대신 「맡」.", "첫 음절이 「넣」이 되게 바로잡는다.",
                     "「효과음」이라는 낱말이 되게 바로잡는다.", "「맡」이라고 적어 주세요.",
                     "받침을 ㅌ으로 교체한다.",
                     "아래 말풍선을 「일을 맡고 싶어」로 바꾼다."):
            with tempfile.TemporaryDirectory() as tmp:
                before, after = Path(tmp) / "b.png", Path(tmp) / "a.png"
                page("일을 말고 싶어").save(before)
                page("일을 맡고 싶어").save(after)
                self.assertEqual([], textguard.restore(before, after, note, old_letter, lou.squash, lou.near, lou.quoted), note)

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
