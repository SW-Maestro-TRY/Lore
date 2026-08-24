# -*- coding: utf-8 -*-
"""LOU 인터랙션 시트 3장을 배경 없는 프레임 스프라이트로 자른다.

_source/ 의 원본 시트를 읽어 <제스처>/<seq1|seq2|random>/ 아래로 잘라 넣는다.
좌표(SPEC)는 원본을 자동 세그먼트한 뒤 눈으로 확인해 고정한 값이다.
원본을 다시 그리면 SPEC 만 고치고 이 스크립트를 다시 돌리면 된다.

    python _cut.py             # 다 자르고 manifest.json 까지 다시 만든다
    python _cut.py --contact   # 확인용 대지(_contact.png)만 다시 만든다
"""
import json
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
SRC = HERE / "_source"

PAD = 12        # 스프라이트 가장자리에 남기는 투명 여백(px)
INK = 14        # 배경(저주파 그라데이션)과 그림(고주파)을 가르는 문턱값
# 시트마다 종이 질감이 달라 필요하면 여기서 문턱값을 따로 준다
INK_PER_SHEET = {}
LINE_RUN = 0.45   # 칸을 이만큼 곧게 가로지르면 직선으로 본다 (카드 테두리 찾기용)
LINE_FILL = 0.12  # 그러면서 제 bbox 를 이만큼도 못 채우면 속 빈 사각 테두리다
BLUR = 30       # 배경으로 볼 저주파의 크기
DIL = 9         # 선을 이어 붙였다 되돌리는 폭 — 그림 내부를 채우려고
MIN_AREA = 150  # 이보다 작은 조각(노이즈)은 버린다
MIN_SIDE = 16   # 카드 테두리 같은 얇고 긴 선은 버린다

# ---------------------------------------------------------------- 시트 좌표
# key      : "<제스처>.<시퀀스>"
# y0, y1   : 그 줄이 차지하는 세로 범위
# xs       : 프레임을 가르는 세로선 (프레임 수 = len(xs) - 1)
# first_y0 : 1번 칸만 다른 y0 (그 자리에 라벨 알약이 얹혀 있는 줄)
SPEC = {
    "sheet1_shake_idle.png": [
        dict(key="shake.seq1", y0=85, y1=372, xs=[30, 320, 618, 922, 1225, 1530], first_y0=92),
        dict(key="idle.seq1", y0=470, y1=712, xs=[40, 311, 617, 927, 1227, 1520], first_y0=478),
        dict(key="idle.seq2", y0=740, y1=1012, xs=[25, 311, 615, 936, 1230, 1520]),
    ],
    "sheet2_five_gestures.png": [
        dict(key="drag.seq1", y0=56, y1=212, xs=[35, 370, 665, 975, 1255, 1530], first_y0=62),
        dict(key="multiclick.seq1", y0=258, y1=428, xs=[60, 320, 620, 925, 1235, 1520], first_y0=286),
        dict(key="pet.seq1", y0=462, y1=632, xs=[70, 310, 615, 925, 1220, 1520], first_y0=490),
        dict(key="longpress.seq1", y0=662, y1=830, xs=[70, 310, 610, 890, 1240, 1530], first_y0=690),
        dict(key="shake.seq2", y0=862, y1=1020, xs=[40, 265, 525, 780, 1020, 1280, 1520], first_y0=886),
    ],
    "sheet3_sketch.png": [
        dict(key="drag.seq2", y0=88, y1=378, xs=[100, 520, 1015, 1520]),
        dict(key="multiclick.seq2", y0=474, y1=724, xs=[35, 272, 505, 777, 1035, 1508]),
        dict(key="pet.seq2", y0=794, y1=1016, xs=[25, 258, 510, 768, 1030, 1286, 1524]),
    ],
}

# (시퀀스키, 1부터 세는 프레임번호) -> (설명, 랜덤 풀에 넣을지, 사람 손이 나오는지)
NOTES = {
    ("shake.seq1", 1): ("가만히 있다 흔들림을 느낀다", True, False),
    ("shake.seq1", 2): ("깜짝 놀란다", True, False),
    ("shake.seq1", 3): ("물살에 휩쓸려 구른다", False, False),
    ("shake.seq1", 4): ("빙글빙글 돌아 눈이 핑 돈다", False, False),
    ("shake.seq1", 5): ("어지러워 멈춘다", True, False),
    ("shake.seq2", 1): ("평온", True, False),
    ("shake.seq2", 2): ("흔들림에 놀란다", True, False),
    ("shake.seq2", 3): ("물보라를 맞고 휘청인다", False, False),
    ("shake.seq2", 4): ("뒤집혀 돈다", False, False),
    ("shake.seq2", 5): ("눈이 핑 돈 채 뻗는다", True, False),
    ("shake.seq2", 6): ("완전히 어지러워 굳는다", True, False),

    ("idle.seq1", 1): ("기본 대기", True, False),
    ("idle.seq1", 2): ("눈을 깜빡인다", True, False),
    ("idle.seq1", 3): ("실눈을 뜬다", True, False),
    ("idle.seq1", 4): ("크게 하품한다", True, False),
    ("idle.seq1", 5): ("하품 끝, 한숨", True, False),
    ("idle.seq2", 1): ("졸려서 눈이 감긴다", True, False),
    ("idle.seq2", 2): ("잠든다 (zzz)", True, False),
    ("idle.seq2", 3): ("깊게 잠든다 (zzz)", True, False),
    ("idle.seq2", 4): ("깜짝 깨어난다", True, False),
    ("idle.seq2", 5): ("\"어… 있었어요?\"", True, False),

    ("drag.seq1", 1): ("끌 수 있다는 안내 (점선 + 손)", False, True),
    ("drag.seq1", 2): ("손에 잡혀 끌려가기 시작", False, True),
    ("drag.seq1", 3): ("커서를 따라 헤엄친다", True, False),
    ("drag.seq1", 4): ("빠르게 휘두르면 소용돌이", False, False),
    ("drag.seq1", 5): ("놓아주면 기뻐한다", True, False),
    ("drag.seq2", 1): ("손에 밀려 옆으로 끌린다", False, True),
    ("drag.seq2", 2): ("신나서 파닥인다", True, False),
    ("drag.seq2", 3): ("지쳐서 한숨", True, False),

    ("multiclick.seq1", 1): ("평온", True, False),
    ("multiclick.seq1", 2): ("어? 놀란다", True, False),
    ("multiclick.seq1", 3): ("슬슬 짜증", True, False),
    ("multiclick.seq1", 4): ("화난다", True, False),
    ("multiclick.seq1", 5): ("삐져서 등을 돌린다", True, False),
    ("multiclick.seq2", 1): ("손이 누른다", False, True),
    ("multiclick.seq2", 2): ("처음엔 좋아한다", True, False),
    ("multiclick.seq2", 3): ("점점 지친다", True, False),
    ("multiclick.seq2", 4): ("결국 소리친다", True, False),
    ("multiclick.seq2", 5): ("드러누워 버린다", True, False),

    ("pet.seq1", 1): ("가만히 기다린다", True, False),
    ("pet.seq1", 2): ("쓰다듬는 손을 느낀다", False, True),
    ("pet.seq1", 3): ("좋아서 눈을 감는다", True, False),
    ("pet.seq1", 4): ("하트가 터진다", True, False),
    ("pet.seq1", 5): ("행복하게 늘어진다", True, False),
    ("pet.seq2", 1): ("졸린 채 기다린다", True, False),
    ("pet.seq2", 2): ("손이 머리를 쓸어준다", False, True),
    ("pet.seq2", 3): ("기분 좋아 눈을 감는다", True, False),
    ("pet.seq2", 4): ("스르르 잠든다 (zzz)", True, False),
    ("pet.seq2", 5): ("깜짝 깨어난다", True, False),
    ("pet.seq2", 6): ("\"아, 아직 있었어요?\"", True, False),

    ("longpress.seq1", 1): ("누르기 시작", True, False),
    ("longpress.seq1", 2): ("기다리는 중 (…)", True, False),
    ("longpress.seq1", 3): ("눈을 감는다", True, False),
    ("longpress.seq1", 4): ("잠들어 버린다 (zzz)", True, False),
    ("longpress.seq1", 5): ("놓으면 깨어나 \"어… 있었어요?\"", True, False),
}

# 세 시트 어디에도 "한 번 클릭" 줄이 없다. 다른 줄에서 한 컷짜리로 써도 되는
# 반응 포즈를 골라 click/random/ 으로 복사해 둔다 (그림 파일은 원본과 같다).
CURATED = {
    "click": [
        ("multiclick.seq1", 2, "톡 누르면 놀란다"),
        ("idle.seq1", 3, "톡 누르면 실눈을 뜬다"),
        ("pet.seq1", 4, "톡 누르면 하트가 터진다"),
        ("drag.seq1", 5, "톡 누르면 기뻐한다"),
        ("shake.seq2", 2, "톡 누르면 화들짝"),
    ],
}

GESTURE_LABEL = {
    "click": "클릭 (한 번 누르기)",
    "multiclick": "연속 클릭",
    "drag": "드래그",
    "pet": "터치 & 쓰다듬기",
    "longpress": "길게 누르기",
    "shake": "흔들기 (모바일 전용)",
    "idle": "아무것도 안 하기 (Idle)",
}
GESTURE_ORDER = ["click", "multiclick", "drag", "pet", "longpress", "shake", "idle"]


# ------------------------------------------------------------ 배경 따내기
def ink_mask(im, thr=INK):
    """저주파(배경 그라데이션)를 뺀 고주파 성분 = 그림이 있는 자리."""
    d = ImageChops.difference(im, im.filter(ImageFilter.GaussianBlur(BLUR))).convert("L")
    return d.point(lambda v: 255 if v > thr else 0)


def _components(img):
    """255 인 화소들을 4-이웃으로 묶어 (라벨맵, 대표->화소들의 bbox·넓이) 를 낸다."""
    W, H = img.size
    px = img.load()
    parent = {}

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    lab = [[0] * W for _ in range(H)]
    nxt = 1
    for y in range(H):
        row, prev = lab[y], lab[y - 1] if y else None
        for x in range(W):
            if px[x, y] < 128:
                continue
            up = prev[x] if prev else 0
            lf = row[x - 1] if x else 0
            if up and lf:
                row[x] = up
                union(up, lf)
            elif up or lf:
                row[x] = up or lf
            else:
                row[x] = nxt
                parent[nxt] = nxt
                nxt += 1
    stat = {}
    for y in range(H):
        row = lab[y]
        for x in range(W):
            if not row[x]:
                continue
            r = find(row[x])
            s = stat.get(r)
            if s is None:
                stat[r] = [x, y, x, y, 1]
            else:
                if x < s[0]: s[0] = x
                if y < s[1]: s[1] = y
                if x > s[2]: s[2] = x
                if y > s[3]: s[3] = y
                s[4] += 1
    return lab, stat, find


def drop_frame_lines(mask):
    """원본 카드의 사각 테두리를 지운다.

    안 지우면 테두리가 닫힌 사각형이라, 뒤에서 구멍을 채울 때 카드 안쪽이
    통째로 그림으로 딸려 와 스프라이트에 네모난 바탕 자국이 남는다.
    가로·세로 양쪽으로 칸을 길게 가로지르면서 제 bbox 는 거의 못 채우는
    성분만 고른다 — 고래는 윤곽이 곡선이라 세로로 길게 곧지 않고,
    속이 꽉 차 있어서 걸리지 않는다.
    """
    W, H = mask.size
    lab, stat, find = _components(mask)
    run_h, run_v = {}, {}
    for y in range(H):                       # 성분마다 가장 긴 가로 직선
        row, cur, n = lab[y], 0, 0
        for x in range(W):
            r = find(row[x]) if row[x] else 0
            if r and r == cur:
                n += 1
            else:
                if cur: run_h[cur] = max(run_h.get(cur, 0), n)
                cur, n = r, 1 if r else 0
        if cur: run_h[cur] = max(run_h.get(cur, 0), n)
    for x in range(W):                       # 세로도 같은 식으로
        cur, n = 0, 0
        for y in range(H):
            r = find(lab[y][x]) if lab[y][x] else 0
            if r and r == cur:
                n += 1
            else:
                if cur: run_v[cur] = max(run_v.get(cur, 0), n)
                cur, n = r, 1 if r else 0
        if cur: run_v[cur] = max(run_v.get(cur, 0), n)
    bad = set()
    for r, (x0, y0, x1, y1, area) in stat.items():
        box = max(1, (x1 - x0 + 1) * (y1 - y0 + 1))
        if (run_h.get(r, 0) > W * LINE_RUN and run_v.get(r, 0) > H * LINE_RUN
                and area < box * LINE_FILL):
            bad.add(r)
    if not bad:
        return mask
    out = mask.copy()
    op = out.load()
    for y in range(H):
        row = lab[y]
        for x in range(W):
            if row[x] and find(row[x]) in bad:
                op[x, y] = 0
    return out


def cutout_alpha(mask_crop):
    """선 마스크 -> 속이 채워진 알파. 노이즈와 카드 테두리는 버린다."""
    m = drop_frame_lines(mask_crop).filter(ImageFilter.MaxFilter(DIL))
    w, h = m.size
    pad = Image.new("L", (w + 4, h + 4), 0)
    pad.paste(m, (2, 2))
    inv = ImageChops.invert(pad)
    ImageDraw.floodfill(inv, (0, 0), 128, thresh=0)          # 바깥 배경을 칠한다
    solid = ImageChops.invert(inv.point(lambda v: 255 if v == 128 else 0))
    solid = solid.filter(ImageFilter.MinFilter(DIL))          # 넓혔던 만큼 되돌린다

    lab, stat, find = _components(solid)
    keep = {r for r, (x0, y0, x1, y1, a) in stat.items()
            if a >= MIN_AREA and min(x1 - x0, y1 - y0) >= MIN_SIDE}
    W, H = solid.size
    out = Image.new("L", (W, H), 0)
    op = out.load()
    for y in range(H):
        row = lab[y]
        for x in range(W):
            if row[x] and find(row[x]) in keep:
                op[x, y] = 255
    return out.crop((2, 2, 2 + w, 2 + h)).filter(ImageFilter.GaussianBlur(1.2))


# ---------------------------------------------------------------- 자르기
def cut_sheet(name, rows, tree):
    im = Image.open(SRC / name).convert("RGB")
    mask = ink_mask(im, INK_PER_SHEET.get(name, INK))
    for row in rows:
        gesture, seq = row["key"].split(".")
        xs, y0, y1 = row["xs"], row["y0"], row["y1"]
        cut = []
        for i in range(len(xs) - 1):
            top = row.get("first_y0", y0) if i == 0 else y0
            box = (xs[i], top, xs[i + 1], y1)                 # 칸 밖으로 절대 안 나간다
            a = cutout_alpha(mask.crop(box))
            bb = a.getbbox()
            if bb is None:
                raise SystemExit(f"{row['key']} {i+1}번 칸이 비었다: {box}")
            rgba = im.crop(box).convert("RGBA")
            rgba.putalpha(a)
            cut.append(rgba.crop(bb))
        cw = max(c.width for c in cut) + PAD * 2
        ch = max(c.height for c in cut) + PAD * 2
        frames = []
        for i, c in enumerate(cut, 1):                        # 시퀀스 안에서 판형을 맞춘다
            canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
            canvas.paste(c, ((cw - c.width) // 2, (ch - c.height) // 2))
            note, rnd, hand = NOTES.get((row["key"], i), ("", False, False))
            frames.append(dict(n=i, note=note, random=rnd, hand=hand, image=canvas))
        tree.setdefault(gesture, {})[seq] = dict(source=name, size=[cw, ch], frames=frames)


def write_out(tree):
    manifest = {
        "note": "_cut.py 가 _source/ 시트에서 자동으로 만든다. 손으로 고치지 말 것.",
        "gestures": {},
    }
    for gesture in GESTURE_ORDER:
        gdir = HERE / gesture
        if gdir.exists():
            shutil.rmtree(gdir)
        gdir.mkdir(parents=True)
        seqs = tree.get(gesture, {})
        entry = {"label": GESTURE_LABEL[gesture], "sequences": {}, "random": []}
        pool = []
        for seq in sorted(seqs):
            data = seqs[seq]
            sdir = gdir / seq
            sdir.mkdir()
            items = []
            for f in data["frames"]:
                fn = f"{f['n']:02d}.png"
                f["image"].save(sdir / fn)
                items.append({"file": f"{seq}/{fn}", "note": f["note"], "hasHand": f["hand"]})
                if f["random"]:
                    pool.append((f"{seq}_{fn}", f["image"], f["note"], f"{seq}/{fn}"))
            entry["sequences"][seq] = {"source": data["source"], "size": data["size"],
                                       "frames": items}
        for src_key, n, note in CURATED.get(gesture, []):
            g2, s2 = src_key.split(".")
            pool.append((f"{g2}_{s2}_{n:02d}.png", tree[g2][s2]["frames"][n - 1]["image"],
                         note, f"../{g2}/{s2}/{n:02d}.png"))
        if pool:
            rdir = gdir / "random"
            rdir.mkdir()
            for fn, img, note, origin in pool:
                img.save(rdir / fn)
                entry["random"].append({"file": f"random/{fn}", "note": note, "sameAs": origin})
        manifest["gestures"][gesture] = entry
    (HERE / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # _preview.html 은 file:// 로 열려서 fetch 가 막힌다 — 같은 내용을 js 로도 떨군다
    (HERE / "_manifest.js").write_text(
        "window.LOU_INTERACTION = " + json.dumps(manifest, ensure_ascii=False) + ";\n",
        encoding="utf-8")
    return manifest


def contact_sheet(tree):
    """잘린 결과를 한 장에 모아 눈으로 확인하는 대지."""
    rows = [(f"{g}/{s}", tree[g][s]) for g in GESTURE_ORDER for s in sorted(tree.get(g, {}))]
    TH, gap, left = 150, 6, 190
    width = left + max(sum(int(f["image"].width * TH / f["image"].height) + gap
                           for f in d["frames"]) for _, d in rows) + 20
    height = (TH + 26) * len(rows) + 20
    sheet = Image.new("RGB", (width, height), "white")
    d = ImageDraw.Draw(sheet)
    for yy in range(0, height, 14):                            # 투명이 보이게 체커보드
        for xx in range(left, width, 14):
            if (xx // 14 + yy // 14) % 2:
                d.rectangle([xx, yy, xx + 13, yy + 13], fill=(232, 232, 232))
    y = 10
    for name, data in rows:
        d.text((6, y + TH // 2), name, fill="black")
        x = left
        for f in data["frames"]:
            im = f["image"]
            w = int(im.width * TH / im.height)
            th = im.resize((w, TH), Image.LANCZOS)
            sheet.paste(th, (x, y), th)
            d.rectangle([x, y, x + w, y + TH], outline=(190, 190, 190))
            d.text((x + 3, y + 3), str(f["n"]), fill=(200, 0, 0))
            x += w + gap
        y += TH + 26
    p = HERE / "_contact.png"
    sheet.save(p)
    return p


def main():
    tree = {}
    for name, rows in SPEC.items():
        cut_sheet(name, rows, tree)
    if "--contact" not in sys.argv:
        m = write_out(tree)
        total = sum(len(s["frames"]) for g in m["gestures"].values()
                    for s in g["sequences"].values())
        rnd = sum(len(g["random"]) for g in m["gestures"].values())
        print(f"프레임 {total}장, 랜덤 풀 {rnd}장")
    print("확인용 대지:", contact_sheet(tree))


if __name__ == "__main__":
    main()
