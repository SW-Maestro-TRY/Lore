"""말풍선 안의 글자만 고친다. 그림은 다시 그리지 않는다(모델 호출 없음).

짚은 자리에서 말풍선의 밝은 안쪽을 채워 찾고, 그 안에 갇힌 어두운 점을 글자로 본다. 글자 줄의 높이 · 줄 간격 ·
줄마다의 폭 · 획의 굵기를 재서, 원래 글자를 지우고 같은 자리에 같은 크기로 맞는 글을 찍는다.
말풍선 테두리가 닫혀 있지 않거나(그림 위에 바로 얹힌 글자) 안쪽이 어두우면 못 한다 — 그때는 NoBubble 로 알린다.
"""
import json
from itertools import combinations
from pathlib import Path
from statistics import median

from PIL import Image, ImageDraw, ImageFont, ImageStat

LIGHT = 215          # 이보다 밝으면 말풍선 안쪽
SCALE = 4            # 글자를 이만큼 크게 찍어 줄인다 — 획 굵기를 1/4 픽셀 단위로 맞출 수 있다
MAX_ITEMS = 8
FONTS = ("/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
         "/System/Library/Fonts/AppleSDGothicNeo.ttc",
         r"C:\Windows\Fonts\malgun.ttf")


class NoBubble(ValueError):
    """고칠 말풍선을 못 찾았다."""


def font_file():
    for path in FONTS:
        if Path(path).exists():
            return path
    raise NoBubble("한글 글꼴을 찾지 못했습니다")


def _seed(light, x, y, reach):
    """짚은 자리에서 가장 가까운 밝은 점. 글자 획 위를 짚어도 말풍선 안쪽을 찾는다."""
    w, h = light.size
    px = light.load()
    for r in range(reach + 1):
        for dx in range(-r, r + 1):
            for dy in ((-r, r) if abs(dx) != r else range(-r, r + 1)):
                u, v = x + dx, y + dy
                if 0 <= u < w and 0 <= v < h and px[u, v] == 255:
                    return u, v
    raise NoBubble("짚은 곳 가까이에 말풍선이 없습니다")


def _spans(flags, bridge=1):
    """참이 이어지는 구간들. bridge 칸 이하의 틈은 잇는다."""
    out, start, gap = [], None, 0
    for i, on in enumerate([*flags, *[False] * (bridge + 1)]):
        if on:
            start, gap = (i if start is None else start), 0
        elif start is not None:
            gap += 1
            if gap > bridge:
                out.append((start, i - gap + 1))
                start, gap = None, 0
    return out


def _wrap(words, widths, measure):
    """낱말을 원래 줄 수대로 나눈다. 줄마다의 폭이 원래 줄의 폭에 가장 가깝게 — 한 글자만 바뀌면 줄바꿈이 그대로다.

    줄 수 × 낱말 수² 의 동적 계획법이다(모든 나눔을 늘어놓으면 긴 대사에서 수백만 가지가 된다).
    """
    n = min(len(widths), len(words))
    m = len(words)
    width = {}

    def cost(a, b, row):
        if (a, b) not in width:
            width[a, b] = measure(" ".join(words[a:b]))
        return abs(width[a, b] - widths[row])

    best = {(0, 0): (0.0, None)}
    for row in range(n):
        for start in range(m):
            if (start, row) not in best:
                continue
            ends = [m] if row == n - 1 else range(start + 1, m - (n - row - 1) + 1)
            for end in ends:
                total = best[start, row][0] + cost(start, end, row)
                if (end, row + 1) not in best or total < best[end, row + 1][0]:
                    best[end, row + 1] = (total, start)
    lines, end = [], m
    for row in range(n, 0, -1):
        start = best[end, row][1]
        lines.append(" ".join(words[start:end]))
        end = start
    return lines[::-1]


def _stamp(lines, path, size, pitch, weight):
    """줄들을 가운데로 맞춰 찍은 글자 판(L, 글자가 밝다)."""
    font = ImageFont.truetype(path, size * SCALE)
    w = int(max(font.getlength(s) for s in lines)) + 12 * SCALE
    h = int(pitch * SCALE * (len(lines) - 1) + size * SCALE * 2)
    big = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(big)
    for i, s in enumerate(lines):
        l, t, r, b = draw.textbbox((0, 0), s, font=font, stroke_width=weight)
        draw.text((w / 2 - (l + r) / 2, size * SCALE + i * pitch * SCALE - (t + b) / 2), s,
                  font=font, fill=255, stroke_width=weight, stroke_fill=255)
    return big.resize((max(1, w // SCALE), max(1, h // SCALE)), Image.Resampling.LANCZOS)


def _amount(mask):
    return sum(mask.histogram()[40:])


def patch(page, x, y, text, path=None):
    """page 의 (x, y) 픽셀이 가리키는 글자 덩어리를 text 로 바꾼 새 그림."""
    path = path or font_file()
    words = text.split()
    if not words:
        raise NoBubble("넣을 글이 없습니다")
    page = page.convert("RGB")
    W, H = page.size
    light = page.convert("L").point(lambda v: 255 if v >= LIGHT else 0)
    for _ in range(60):
        seed = _seed(light, x, y, round(W * .06))
        ImageDraw.floodfill(light, seed, 128)
        inside = light.point(lambda v: 255 if v == 128 else 0)
        area = inside.histogram()[255]
        if area >= W * H * .002:
            break
        ImageDraw.floodfill(light, seed, 64)      # 「ㅇ」「ㅁ」 속 같은 글자 안의 빈칸이었다 — 다음으로 가까운 곳을 본다
    else:
        raise NoBubble("짚은 곳 가까이에 말풍선이 없습니다")
    if area > W * H * .3:
        raise NoBubble("말풍선의 테두리가 닫혀 있지 않습니다")
    box = l, t, r, b = inside.getbbox()
    # 말풍선 안쪽에 갇힌 점 = 글자. 테두리 밖과 이어진 점은 글자가 아니다.
    frame = Image.new("L", (r - l + 2, b - t + 2), 0)
    frame.paste(inside.crop(box), (1, 1))
    ImageDraw.floodfill(frame, (0, 0), 128)
    frame = frame.crop((1, 1, r - l + 1, b - t + 1))
    ink = frame.point(lambda v: 255 if v == 0 else 0)
    rows = [s for s in _spans(v > 0 for v in ink.resize((1, ink.height), Image.Resampling.BOX).tobytes())
            if s[1] - s[0] >= 5]
    if not rows:
        raise NoBubble("말풍선 안에 글자가 없습니다")
    tall = median(e - s for s, e in rows)
    # 안쪽이 이어진 말풍선이 좌우로 붙어 있으면 같은 높이에 글 덩어리가 둘이다 — 글자 키보다 넓게 벌어진 세로 틈으로 가르고
    # 짚은 쪽만 남긴다(낱말 사이 띄어쓰기는 글자 키보다 좁다).
    cols = _spans((v > 0 for v in ink.resize((ink.width, 1), Image.Resampling.BOX).tobytes()), bridge=round(tall * .8))
    if len(cols) > 1:
        at_x = x - l
        left_, right_ = min(cols, key=lambda c: 0 if c[0] <= at_x <= c[1] else min(abs(at_x - c[0]), abs(at_x - c[1])))
        keep = Image.new("L", ink.size, 0)
        keep.paste(255, (max(0, left_ - 1), 0, min(ink.width, right_ + 1), ink.height))
        ink = Image.composite(ink, Image.new("L", ink.size, 0), keep)
        frame = Image.composite(frame, Image.new("L", frame.size, 128), keep)
        rows = [s for s in _spans(v > 0 for v in ink.resize((1, ink.height), Image.Resampling.BOX).tobytes())
                if s[1] - s[0] >= 5]
        if not rows:
            raise NoBubble("짚은 곳에 글자 덩어리가 없습니다")
        tall = median(e - s for s, e in rows)
    blocks = [[rows[0]]]                          # 한 테두리 안에 말풍선 둘이 붙어 있으면 줄 사이가 크게 벌어진다
    for row in rows[1:]:
        (blocks.append([row]) if row[0] - blocks[-1][-1][1] > tall else blocks[-1].append(row))
    at = y - t
    lines = min(blocks, key=lambda bl: 0 if bl[0][0] <= at <= bl[-1][1]
                else min(abs(at - bl[0][0]), abs(at - bl[-1][1])))
    cols = [ink.crop((0, s, ink.width, e)).getbbox() for s, e in lines]
    widths = [c[2] - c[0] for c in cols]
    centre = median((c[0] + c[2]) / 2 for c in cols)
    middle = (lines[0][0] + lines[-1][1]) / 2
    pitch = median(b_[0] - a_[0] for a_, b_ in zip(lines, lines[1:])) if len(lines) > 1 else tall * 1.5
    pad = round(tall * .3)
    area = (max(0, min(c[0] for c in cols) - pad), max(0, lines[0][0] - pad),
            min(ink.width, max(c[2] for c in cols) + pad), min(ink.height, lines[-1][1] + pad))
    piece = page.crop(box)
    paper = tuple(ImageStat.Stat(piece, mask=inside.crop(box)).median)
    dark = Image.eval(piece.convert("L"), lambda v: 255 if v < 100 else 0)
    dark.paste(0, mask=Image.eval(ink, lambda v: 255 - v))
    colour = tuple(ImageStat.Stat(piece, mask=dark).median) if dark.getbbox() else (20, 20, 20)
    old = _amount(ink.crop(area))
    # 지운다: 덩어리 둘레 네모 가운데 말풍선에 속한 곳(안쪽 + 글자)만
    erase = Image.new("L", ink.size, 0)
    erase.paste(Image.eval(frame, lambda v: 0 if v == 128 else 255).crop(area), area)
    piece.paste(paper, mask=erase)
    # 찍는다: 한글의 키를 원래 줄 높이에 맞추고, 획 굵기는 원래 글자의 먹 양에 가장 가까운 것으로
    probe = ImageFont.truetype(path, 100).getbbox("가나다힣")
    size = max(8, round(100 * tall / (probe[3] - probe[1])))
    fit = ImageFont.truetype(path, size)
    out = _wrap(words, widths, fit.getlength)
    while max(ImageFont.truetype(path, size).getlength(s) for s in out) > (r - l) * .94 and size > 8:
        size -= 1
    stamp = min((_stamp(out, path, size, pitch, weight) for weight in range(0, 7)),
                key=lambda m: abs(_amount(m) - old))
    sl, st, sr, sb = stamp.getbbox()
    piece.paste(colour, (round(centre - (sl + sr) / 2), round(middle - (st + sb) / 2)), mask=stamp)
    done = page.copy()
    done.paste(piece, (l, t))
    return done


def apply(run_dir, page_no, request):
    """request(JSON 파일)의 items 대로 한 장의 글자를 고친다. 지난 판을 떠 두고 그 장을 바꿔 쓴다.

    {"items": [{"x": 27, "y": 47, "text": "맞는 글 전체"}]}  — x, y 는 그 글자 덩어리를 가리키는 자리(장 전체에 대한 %).
    """
    import detailart

    items = json.loads(Path(request).read_text(encoding="utf-8")).get("items")
    if not isinstance(items, list) or not 0 < len(items) <= MAX_ITEMS:
        raise NoBubble("고칠 글자가 없습니다")
    target = detailart.page_path(run_dir, page_no)
    if not target.exists():
        raise NoBubble("그 장이 없습니다")
    with Image.open(target) as original:
        page = original.convert("RGB")
    for item in items:
        x, y, text = item["x"], item["y"], item["text"]
        if not (all(type(v) in (int, float) and 0 <= v <= 100 for v in (x, y)) and isinstance(text, str)
                and 0 < len(text.strip()) <= 200):
            raise NoBubble("고칠 글자의 형식이 올바르지 않습니다")
        page = patch(page, round(page.width * x / 100), round(page.height * y / 100), text)
    detailart.archive_page(run_dir, page_no)
    scratch = target.with_suffix(".textfix.png")
    page.save(scratch)
    scratch.replace(target)
