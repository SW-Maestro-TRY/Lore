"""글자 지키기 — 구도 유지로 고친 장에서, 고치라고 하지 않은 말풍선의 글자가 바뀌었으면 그 말풍선 안쪽만 고치기 전 그림에서 되살린다.

구도 유지 편집은 장 전체를 다시 그려서, 손대지 말라고 한 말풍선의 글자도 가끔 한 글자씩 바뀐다(「가겠다고」→「가져다고」,
「성화」→「성황」, 2026-11-22 실제 지시 13건 중 5건). 모델에게 더 세게 말해도 다시 그릴 때마다 새로 생기므로, 그린 뒤에 되돌린다.

1. 고치기 전 장에서 말풍선(테두리로 닫힌 밝은 안쪽에 글자가 든 곳 — 내레이션 상자 포함)을 찾는다.
2. 고친 뒤에도 같은 자리에 같은 모양으로 있는 말풍선만 본다. 꼬리를 돌리거나 지운 말풍선은 모양이 달라 건너뛴다.
3. 그 말풍선마다 고치기 전 · 후를 크게 잘라, 대사를 모르는 눈으로 동시에 옮겨 적어 견준다.
   지시가 「」로 바꾸라고 한 글, 지시에 적힌 글과 맞는 말풍선은 건너뛴다. 한쪽이라도 못 읽으면 건드리지 않는다.
4. 남은 말풍선은 읽은 글이 같아도 안쪽(글자 포함)을 고치기 전 그림으로 덮는다. 테두리와 꼬리는 고친 뒤 그림 그대로 둔다.
   읽기는 어느 말풍선인지 알아보는 데만 쓴다. 바뀌었는지는 읽기로 정하지 않는다 — 받침 ㄹ · ㅌ 처럼 한 획 다른 글자는
   한 자씩 크게 잘라 줘도 똑같이 읽는다(2026-11-22 실측). 글을 어떻게 바꾸라는지 「」 없이 적힌 지시면 어느 말풍선인지
   몰라서, 다르게 읽힌 것만 되살린다.
모델 호출은 글자 옮겨 적기뿐이다. 효과음처럼 말풍선 밖에 쓴 글자는 지키지 못한다.
"""
import re
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps, ImageStat

LIGHT = 215
SCALE = 2                     # 말풍선 찾기는 절반 크기로(파이썬 채우기가 느리다)
MIN_AREA, MAX_AREA = .002, .15
SAME_SHAPE = .85
# 말풍선 안쪽(글자 빼고)의 밝기 평균 · 흩어짐. 실제 장 6개에서 말풍선은 평균 252 · 흩어짐 7 이하, 그 밖의 밝은 곳(옷 · 소품 · 하늘)은
# 평균 238 이하였다(2026-11-22).
FLAT_MEAN, FLAT_SPREAD = 245, 9


def _light(image):
    small = image.convert("L").resize((max(1, image.width // SCALE), max(1, image.height // SCALE)))
    light = small.point(lambda v: 255 if v >= LIGHT else 0)
    padded = ImageOps.expand(light, 1, fill=255)
    ImageDraw.floodfill(padded, (0, 0), 0)           # 장 가장자리와 이어진 밝은 곳(여백 · 컷 사이)은 말풍선이 아니다
    return padded.crop((1, 1, padded.width - 1, padded.height - 1))


def _filled(mask):
    """밝은 안쪽 + 그 안에 갇힌 글자. 안쪽만 255, 나머지 0."""
    frame = ImageOps.expand(mask, 1, fill=0)
    ImageDraw.floodfill(frame, (0, 0), 128)
    frame = frame.crop((1, 1, frame.width - 1, frame.height - 1))
    return frame.point(lambda v: 0 if v == 128 else 255)


def bubbles(image):
    """말풍선 후보 [(전체 크기 상자, 전체 크기 안쪽 마스크(L), 씨앗 점)] — 밝고 닫힌 곳 가운데 글자가 든 것."""
    light = _light(image)
    gray = image.convert("L").resize(light.size)
    w, h = light.size
    seen = Image.new("L", light.size, 0)
    px, sp = light.load(), seen.load()
    out = []
    step = 6
    for y in range(0, h, step):
        for x in range(0, w, step):
            if px[x, y] != 255 or sp[x, y]:
                continue
            comp = light.copy()
            ImageDraw.floodfill(comp, (x, y), 128)
            mask = comp.point(lambda v: 255 if v == 128 else 0)
            seen.paste(255, mask=mask)
            box = mask.getbbox()
            area = mask.histogram()[255]
            if not box or not MIN_AREA * w * h <= area <= MAX_AREA * w * h:
                continue
            region = mask.crop(box)
            filled = _filled(region)
            ink = ImageChops.subtract(filled, region)        # 안쪽에 갇힌 어두운 점 = 글자
            if ink.histogram()[255] < area * .01:
                continue
            # 말풍선 안쪽은 거의 고르게 하얗다 — 흰 옷 · 하늘처럼 밝지만 명암이 진 곳은 말풍선이 아니다
            tone = ImageStat.Stat(gray.crop(box), mask=region)
            if tone.mean[0] < FLAT_MEAN or tone.stddev[0] > FLAT_SPREAD:
                continue
            full = Image.new("L", light.size, 0)
            full.paste(filled, box[:2])
            big = full.resize(image.size, Image.Resampling.NEAREST)
            out.append((tuple(v * SCALE for v in box), big, (x * SCALE, y * SCALE)))
    return out


def _shape_in(image, seed, box, light=None):
    """다른 그림에서 같은 씨앗 자리의 밝은 안쪽(글자 포함)."""
    light = (light or _light(image)).copy()
    x, y = seed[0] // SCALE, seed[1] // SCALE
    if not (0 <= x < light.width and 0 <= y < light.height) or light.getpixel((x, y)) != 255:
        return None
    ImageDraw.floodfill(light, (x, y), 128)
    mask = light.point(lambda v: 255 if v == 128 else 0)
    full = Image.new("L", light.size, 0)
    b = mask.getbbox()
    if not b:
        return None
    full.paste(_filled(mask.crop(b)), b[:2])
    return full.resize(image.size, Image.Resampling.NEAREST)


def _iou(a, b):
    both = ImageChops.multiply(a, b).histogram()[255]
    either = ImageChops.lighter(a, b).histogram()[255]
    return both / either if either else 0.0


def _changed_share(before, after, mask):
    """안쪽에서 픽셀이 뚜렷하게 달라진 몫. 0 이면 같은 그림이라 옮겨 적을 필요가 없다."""
    inner = mask.filter(ImageFilter.MinFilter(5))
    diff = ImageChops.difference(before.convert("L"), after.convert("L")).point(lambda v: 255 if v > 90 else 0)
    hit = ImageChops.multiply(diff, inner).histogram()[255]
    return hit / max(1, inner.histogram()[255])


# 글을 건드리라는 말
TEXT_WORDS = re.compile(r"말풍선|대사|문구|글자|글을|글씨|오타|말투|존댓말|반말|상자|내레이션|나레이션|자막|맞춤법|받침|낱말|단어|철자")
# 글을 바꾸는 것이 아니라는 게 분명한 말 — 새로 넣기 · 지우기 · 꼬리
NOT_REWRITE = re.compile(r"넣|추가|적어|써\s|쓴다|써서|꼬리|지우|지운|지워|없앤|없애")
# 「」 뒤에 「~로 · ~라고」가 붙으면 그 글로 바꾸라는 것이다
INTO = re.compile(r"」\s*(으로|로|(이)?라고)")


def unclear(note):
    """글을 건드리라면서 「」로 어느 글인지 안 적은 문장이 있는가. 그대로 두라는 문장은 아니다."""
    return any(TEXT_WORDS.search(s) and "「" not in s and not re.search(r"그대로|유지", s)
               for s in re.split(r"(?<=[.!?])\s+|\n", note or ""))


def unlocated(note, reads, quoted, squash, near):
    """글을 바꾸라면서 「」로 적은 것만으로는 어느 말풍선인지 못 짚는가. 짧은 낱말(「맡」)은 옆 말풍선에도 있고,
    읽는 눈이 옛 글자로 읽으면 고친 말풍선에서도 안 보인다. 효과음은 말풍선 밖이라 상관없다.
    바꾸라는 말(교체 · 정정 · …)을 세지 않는다 — 빠진 낱말 하나가 정확히 고친 글자를 되돌린다. 바꾸는 게 아닌 것이 분명할 때만 뺀다."""
    for sentence in re.split(r"(?<=[.!?])\s+|\n", note or ""):
        quotes = quoted(sentence)
        said = re.sub(r"「[^」]*」", "「」", sentence)           # 따옴표 안의 글(「넣」)은 시키는 말이 아니다
        if not quotes or (not INTO.search(said) and NOT_REWRITE.search(said)):
            continue
        if "효과음" in said and not re.search(r"말풍선|대사|상자", said):
            continue
        whole = [q for q in quotes if len(squash(q)) >= 4]
        if not any(near(r, q) >= .9 or near(q, r) >= .8 for q in whole for r in reads if squash(r)):
            return True
    return False


def _asked(read, quotes, squash, near):
    """지시가 「」로 가리킨 글이 이 말풍선의 글인가. 낱말만 적기도 한다(「말고」를 「맡고」로)."""
    for q in quotes:
        if near(read, q) >= .9 or (len(squash(q)) >= 4 and near(q, read) >= .8):
            return True
        if len(squash(q)) < 4 and squash(q) in squash(read):
            return True
    return False


def restore(before_path, after_path, note, read_many, squash, near, quoted):
    """after_path 를 고쳐 쓴다. read_many(paths) -> 그림마다 줄 목록(동시에 옮겨 적는다).
    되살린 말풍선 [(고치기 전 글, 고친 뒤 글)] 을 돌려준다.

    장 전체를 옮겨 적어 달라진 줄부터 찾으면, 한 글자 차이를 그 단계에서 놓쳐 아무것도 안 했다(2026-11-22).
    그래서 모양이 같은 말풍선마다 고치기 전 · 후를 크게 잘라 옮겨 적어 견준다."""
    with Image.open(before_path) as b, Image.open(after_path) as a:
        before, after = b.convert("RGB"), a.convert("RGB")
    if before.size != after.size:
        return []
    asked = quoted(note)
    by_reading = unclear(note)
    after_light = _light(after)
    found = []
    for box, mask, seed in bubbles(before):
        other = _shape_in(after, seed, box, after_light)
        if other is None or _iou(mask, other) < SAME_SHAPE:
            continue                                   # 꼬리를 돌렸거나 지운 말풍선 — 지시대로 바뀐 것
        if _changed_share(before, after, mask) == 0:
            continue
        found.append((box, mask))
    if not found:
        return []
    tmp = Path(after_path).with_suffix(".guard")
    tmp.mkdir(exist_ok=True)
    try:
        paths = []
        for i, (box, _mask) in enumerate(found):
            pad = 6
            crop = (max(0, box[0] - pad), max(0, box[1] - pad), min(before.width, box[2] + pad),
                    min(before.height, box[3] + pad))
            for tag, image in (("b", before), ("a", after)):
                piece = image.crop(crop)
                piece = piece.resize((piece.width * 2, piece.height * 2), Image.Resampling.LANCZOS)
                paths.append(tmp / f"{i}{tag}.png")
                piece.save(paths[-1])
        texts = read_many(paths)
    finally:
        for f in tmp.glob("*"):
            f.unlink()
        tmp.rmdir()
    by_reading = by_reading or unlocated(note, [" ".join(lines) for lines in texts], quoted, squash, near)
    restored = []
    for i, (_box, mask) in enumerate(found):
        old, new = (" ".join(texts[2 * i + j]) for j in (0, 1))
        if not squash(old) or not squash(new):
            continue                                   # 한쪽을 못 읽었다 — 모르면 건드리지 않는다
        if by_reading and squash(old) == squash(new):
            continue                                   # 어느 말풍선을 고치라는지 모른다 — 같게 읽혔으면 그대로 둔다
        if any(_asked(x, asked, squash, near) for x in (old, new)) or near(old, note) >= .8 or near(new, note) >= .8:
            continue                                   # 바꾸라고 한 말풍선 — 지시에 그 글이 (따옴표가 없어도) 적혀 있다
        after.paste(before, (0, 0), mask.filter(ImageFilter.MinFilter(3)))   # 테두리 한 겹은 고친 뒤 그림 그대로
        restored.append((old, new))
    if restored:
        scratch = Path(after_path).with_suffix(".guarded.png")
        after.save(scratch)
        scratch.replace(after_path)
    return restored
