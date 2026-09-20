#!/usr/bin/env python3
"""16프레임 격자 후처리 — 절단 → 초록 키잉 → 침범제거 → **16칸 전체 정렬** → 애니 GIF.
2026-08-26 신설. 사용: state16_post.py <grid.png>

state8_v3 와 무엇이 다른가
--------------------------
8종은 **2프레임 쌍이 8개**라 정렬이 2층이었다(층1=쌍 안 겹침, 층2=쌍 사이 발).
16프레임은 **한 동작이 16칸에 이어진다.** 쌍이라는 단위가 없으므로 층1을 없애고,
16칸 전부를 **발 좌표의 중앙값**에 맞춘다(층2 방식 그대로).

★중앙값을 쓰는 이유는 8종에서 얻은 그대로다 — 기준 칸 하나가 오염되면 전부 끌려간다.
  16프레임에서는 위험이 더 크다. 스쿼트처럼 **키가 의도적으로 변하는 동작**에서는
  실루엣 겹침으로 맞추면 앉은 칸이 통째로 밀린다. 발은 그런 동작에서도 고정이므로 안전하다.

절단·키잉·침범제거는 state8_v3 의 것을 그대로 가져다 쓴다(같은 격자 규격이라 재현이 목적).
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state8_v3 as S8


def checker(w, h, s=16):
    bg = Image.new("RGB", (w, h), (210, 214, 220)); px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // s) + (y // s)) % 2 == 0:
                px[x, y] = (170, 176, 186)
    return bg


def main(grid, cols=4, rows=4, duration=120):
    grid = Path(grid); R = grid.parent
    n = cols * rows
    im = Image.open(grid).convert("RGB"); W, H = im.size
    cw, ch = W / cols, H / rows

    # 칸 아래 여유 — 발이 하단 격자점에 닿게 그려지면 균등분할선이 밑창을 스친다(8종에서 실측한 12%).
    PAD = int(ch * 0.12)
    ext = Image.new("RGB", (W, H + PAD), (0, 255, 0)); ext.paste(im, (0, 0)); im = ext

    cut = R / "cut"; cut.mkdir(exist_ok=True)
    cells = []
    for r in range(rows):
        for c in range(cols):
            x0, y0 = int(round(c * cw)), int(round(r * ch))
            cells.append(S8.key_green(im.crop((x0, y0, x0 + int(cw), y0 + int(ch) + PAD)), int(ch)))

    tot = 0
    for i, cl in enumerate(cells):
        cl, rm = S8.drop_intruders(cl); cells[i] = cl; tot += rm
    print(f"침범 제거 {tot}px")

    # ── 정렬: 16칸 전부를 발 좌표 중앙값에 맞춘다 (쌍 개념 없음)
    refs = [S8.foot_ref(c) for c in cells]
    rx = float(np.median([r[0] for r in refs]))
    ry = float(np.median([r[1] for r in refs]))
    moved = 0
    for i in range(n):
        cx, by = refs[i]
        dx, dy = int(round(rx - cx)), int(round(ry - by))
        if dx or dy:
            cells[i] = S8.move(cells[i], dx, dy); moved += 1
        print(f"  f{i+1:02d} dx={dx:+3d} dy={dy:+3d}")
    fx = [r[0] for r in refs]; fy = [r[1] for r in refs]
    print(f"정렬 {moved}/{n}칸 · 발 가로 산포 {max(fx)-min(fx):.1f}px · 세로 {max(fy)-min(fy):.1f}px")

    for i, cl in enumerate(cells, 1):
        cl.save(cut / f"f{i:02d}.png")

    # ── 애니메이션 GIF (투명) — 이게 판정 대상이다
    S8.save_transparent_gif(cells, R / "애니.gif", duration)

    # ── 4x4 미리보기 시트 (정지) — 프레임을 한눈에 훑을 때
    w, h = cells[0].size
    cv = checker(w * cols, h * rows).convert("RGBA")
    for i, cl in enumerate(cells):
        cv.alpha_composite(cl, ((i % cols) * w, (i // cols) * h))
    cv.convert("RGB").save(R / "시트.png")

    print("저장:", R / "애니.gif", "·", R / "시트.png")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
