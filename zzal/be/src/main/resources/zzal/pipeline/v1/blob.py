# -*- coding: utf-8 -*-
"""덩어리(연결요소) 유틸 — 침범 판정의 공통 기반 (2026-08-05).

★왜 2D인가: 처음엔 '가로줄(row run)'로 덩어리를 갈랐다. 그러면 침범한 머리카락이
  그림자와 **세로로 겹치기만 해도** 같은 덩어리로 뭉뚱그려져 검사가 통과해버린다.
  (2026-08-05 삐지기: 검사는 '침범 없음'인데 상훈님 눈에는 발 아래 머리카락이 보였다.)
  → 진짜 연결 여부는 2D 연결요소로 봐야 한다.
"""
import numpy as np
from scipy import ndimage as ndi


def mask_of(a, bg, thr=28):
    return np.sqrt(((a.astype(float) - bg) ** 2).sum(2)) > thr


def components(m, min_px=8):
    """(라벨맵, [(픽셀수, y0, y1, x0, x1, 라벨)]) — 큰 것부터."""
    lab, n = ndi.label(m)
    out = []
    if n == 0:
        return lab, out
    for i in range(1, n + 1):
        ys, xs = np.where(lab == i)
        if len(ys) < min_px:
            continue
        out.append((len(ys), int(ys.min()), int(ys.max()), int(xs.min()), int(xs.max()), i))
    out.sort(reverse=True)
    return lab, out


def is_shadow(comp, main):
    """공중 동작에서 몸과 분리된 그림자인가 — 넓고 납작하며 본체 바로 아래."""
    px, y0, y1, x0, x1, _ = comp
    mpx, my0, my1, mx0, mx1, _ = main
    h = y1 - y0 + 1
    w = x1 - x0 + 1
    return (y0 > my1) and h <= 30 and w >= 40 and (w / max(h, 1)) >= 2.0 and (y0 - my1) <= 45


def split(a, bg, thr=28, min_px=8):
    """(본체, 그림자들, 침범들) 로 분류."""
    m = mask_of(a, bg, thr)
    lab, comps = components(m, min_px)
    if not comps:
        return None, [], [], lab, m
    main = comps[0]
    shadows, bleeds = [], []
    for c in comps[1:]:
        (shadows if is_shadow(c, main) else bleeds).append(c)
    return main, shadows, bleeds, lab, m
