# -*- coding: utf-8 -*-
"""후처리 v4 — 격자점 제거를 **hue 기반**으로 고친다. 2026-09-03 신설.

무엇이 문제였나 (상훈님 지적, 2026-09-02)
------------------------------------------
판정에 올린 5캐릭터와 서비스가 만든 민트 8종 **모서리에 자주색 조각이 남아 있다.**
상훈님: *"마크 색이 우리가 정한거랑 완전 같지 않게 나오는 걸로 알고 있어"* — 맞다.

v3 의 씨앗 조건은 **극도로 순수한 색만** 인정한다:
    seed = ((r>235)&(g<50)&(b>235)) | ((r<50)&(g>235)&(b>235))
그런데 모델이 내는 실제 마크 색은 순수하지 않다:
    · 민트 8종 모서리 마젠타 = RGB(207, 66,191)  → r>235 실패 → 탈락
    · 2026-08-10 실측 시안    = RGB( 30,188,238)  → g>235 실패 → 탈락
씨앗이 안 잡히면 그 뒤 loose 연결확장이 통째로 안 돌아 **십자가 그대로 남는다.**

왜 hue 인가 — 이미 검증된 방법이다
----------------------------------
`frame_cut.py: find_marks_by_color()` 의 2026-08-10 실측(v9 격자 9장 = 288개 마크):
    · 색만(RGB 임계) → 블룸에서 절반 누락. **모델이 순색을 정확히 재현하지 않는다(30,188,238)**
    · hue만          → 블룸의 하늘색 의상이 시안으로 잡혀 과검출
    · ★hue + 위치 + 크기 → 9장 전부 마젠타 16 · 시안 16, **100%**
같은 판정을 여기 그대로 가져온다. 마젠타 hue 280~340 · 시안 hue 165~205.

실측 색을 hue 로 환산하면 전부 범위 안에 들어온다:
    (207, 66,191) → 306.8도 ✅   (239,  8,248) → 297.7도 ✅   ← 마젠타
    ( 30,188,238) → 194.4도 ✅   (  4,247,255) → 181.8도 ✅   ← 시안

★위치 제한(칸 네 모서리)은 v3 그대로 유지한다 — hue 만으로는 못 막는 것이 있다:
    · 소닉 가시 RGB(129,177,255) = 217도 → 범위 밖이라 안전
    · ★김애용 땀방울 RGB( 76,221,235) = 185.4도 → **시안 범위 안이다.**
      색으로는 못 거르고 **위치로만 걸러진다**(격자점은 정의상 칸 모서리에만 있다).
      2026-08-26 에 이 땀방울이 f15의 80%·f16의 66%를 뜯어간 사고가 있었다.

어떻게 붙였나 — v3 를 고치지 않는다
-----------------------------------
v3 의 key_green 을 **그대로 부른 뒤 후행 패스**로 남은 마크만 더 지운다.
지금 증상은 '덜 지움'이지 '과하게 지움'이 아니므로 후행 패스로 충분하고,
v3 의 초록 키잉·despill 로직(판정 537건이 걸려 있다)은 한 줄도 건드리지 않는다.

사용:
    python3 도구/state8_v4.py <grid.png>          # grid.png 옆에 cut/·상태8.gif·순차.gif
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state8_v3 as S8  # noqa: E402  (읽기만 — 기존 파일 불변)

NAMES = S8.NAMES

# frame_cut.py 와 같은 값 — 두 곳이 갈리면 검출과 제거가 어긋난다
MAG = (280.0, 340.0)
CYA = (165.0, 205.0)


def _hsv(a: np.ndarray):
    """RGB(0~255) → (hue 0~360, sat 0~1, val 0~1). frame_cut._hsv 와 동일한 식."""
    x = a.astype(np.float32) / 255.0
    R, G, B = x[:, :, 0], x[:, :, 1], x[:, :, 2]
    mx, mn = x.max(axis=2), x.min(axis=2)
    d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0.0)
    h = np.zeros_like(mx)
    m = d > 1e-6
    i = (mx == R) & m; h[i] = ((G - B)[i] / d[i]) % 6
    i = (mx == G) & m; h[i] = ((B - R)[i] / d[i]) + 2
    i = (mx == B) & m; h[i] = ((R - G)[i] / d[i]) + 4
    return h * 60.0, s, mx


def _in(h, rng, pad=0.0):
    return (h >= rng[0] - pad) & (h <= rng[1] + pad)


def strip_marks_hue(cell: Image.Image, cell_h=None, grow_px=4, alpha_min=8) -> Image.Image:
    """남은 격자점(마젠타·시안 십자)을 hue 로 찾아 지운다. v3 처리 뒤에 덧대는 후행 패스.

    ★alpha_min — 색을 볼 자격이 있는 최소 알파 (2026-09-03 신설).
      초록판(키잉 후)은 8이면 된다. 배경이 이미 알파 0이라 남은 건 실제 그림뿐이다.
      ⚠️**투명 배경 격자에서는 반드시 크게 올려야 한다(200 권장).**
        PNG의 완전투명 픽셀은 RGB가 정의되지 않아 쓰레기 값이 들어 있다
        (실측: 알파 0인 61,336px의 RGB 평균이 (105,75,95), hue 215도).
        그 쓰레기가 마젠타/시안 hue 에 걸리면 loose 연결확장이 투명 영역을 타고
        번져 **칸 전체가 한 덩어리로 지워진다** — 실측으로 한 칸에서 192,373px이
        날아갔다(2026-09-03, 투명 첫 시도). 마크는 불투명하게 그려지므로
        알파 하한을 올려도 마크는 안 놓친다.
    """
    out = np.array(cell.convert("RGBA"))
    rgb = out[:, :, :3]
    alpha = out[:, :, 3]
    h, s, v = _hsv(rgb)

    보임 = alpha > alpha_min
    # 씨앗 — 마크의 또렷한 중심. 채도·명도 하한은 frame_cut 과 같은 값(0.45 / 0.55)
    seed = 보임 & (s > 0.45) & (v > 0.55) & (_in(h, MAG) | _in(h, CYA))

    # ★위치 제한 — 격자점은 칸 네 모서리에만 있다. 색으로 못 거르는 것(김애용 땀방울 185도)을
    #   여기서 막는다. 반경은 v3 와 같은 칸 너비의 16%.
    if cell_h is not None:
        H, W = seed.shape
        R = int(W * 0.16)
        corner = np.zeros((H, W), bool)
        for cy in (0, int(cell_h)):
            for cx in (0, W - 1):
                corner[max(0, cy - R):min(H, cy + R + 1),
                       max(0, cx - R):min(W, cx + R + 1)] = True
        seed &= corner

    if not seed.any():
        return cell

    # 느슨 — 마크의 흐린 가장자리(안티에일리어싱). 채도·명도를 낮추고 hue 범위를 조금 넓힌다.
    #   씨앗에 **닿아 있는 덩어리만** 인정하므로 넓혀도 캐릭터를 먹지 않는다(v3 가 세운 원리).
    loose = 보임 & (s > 0.22) & (v > 0.30) & (_in(h, MAG, 12) | _in(h, CYA, 12))

    lab, _ = ndimage.label(loose)
    hit = np.unique(lab[seed & (lab > 0)])
    hit = hit[hit > 0]
    if not len(hit):
        return cell
    core = np.isin(lab, hit)

    grow = core.copy()
    for _ in range(grow_px):                     # v3 와 같은 4px 번지기
        g2 = grow.copy()
        g2[1:, :] |= grow[:-1, :]; g2[:-1, :] |= grow[1:, :]
        g2[:, 1:] |= grow[:, :-1]; g2[:, :-1] |= grow[:, 1:]
        grow = g2
    # ★번지기는 씨앗이 있는 모서리 영역 안에서만 — 캐릭터 쪽으로 새어 나가지 않게
    if cell_h is not None:
        grow &= corner

    out[:, :, 3] = np.where(grow, 0, out[:, :, 3])
    return Image.fromarray(out)


# ── v3 의 key_green 을 감싼다. v3 파일 자체는 건드리지 않는다.
_v3_key_green = S8.key_green
_STATS = {"칸": 0, "지운칸": 0, "지운px": 0}


def key_green_v4(cell, cell_h=None, *a, **kw):
    out = _v3_key_green(cell, cell_h, *a, **kw)
    before = int((np.array(out.convert("RGBA"))[:, :, 3] > 8).sum())
    out2 = strip_marks_hue(out, cell_h)
    after = int((np.array(out2.convert("RGBA"))[:, :, 3] > 8).sum())
    _STATS["칸"] += 1
    if after < before:
        _STATS["지운칸"] += 1
        _STATS["지운px"] += before - after
    return out2


S8.key_green = key_green_v4


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); raise SystemExit(2)
    if len(sys.argv) > 2 and sys.argv[2] == "--from-cut":
        S8.regen(sys.argv[1])
    elif len(sys.argv) > 2 and sys.argv[2].startswith("--grid"):
        c, r = (sys.argv[3] if sys.argv[2] == "--grid" else sys.argv[2].split("=", 1)[1]).split("x")
        S8.main(sys.argv[1], int(c), int(r))
    else:
        S8.main(sys.argv[1])
    print(f"  [v4] 격자점 후행제거 — {_STATS['칸']}칸 중 {_STATS['지운칸']}칸에서 "
          f"{_STATS['지운px']:,}px 추가 제거")
