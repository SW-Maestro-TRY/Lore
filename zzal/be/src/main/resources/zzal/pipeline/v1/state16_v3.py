#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""16프레임 격자 후처리 v3 — 2026-09-12 신설.

`state16_v2.py`(v02·v4·v5 확정본을 만든 도구)는 **한 글자도 고치지 않는다**.
이 파일은 새로 만들고, v6부터 쓴다.

왜 새 파일인가 — 2026-09-12 v5 실측
-----------------------------------
검수에서 "중간에 앵커가 한 번 통째로 바뀐다"는 지적이 나왔다.
재서 보니 원인이 **둘**이었고, v2 로는 둘 다 못 막는다.

1) **절단 기준이 틀렸다.** 골격은 *"outer rows/columns centered on image edges"* 라고
   적어 두었는데 모델은 **5판 모두** 바깥 마크를 그림 가장자리에서 **16~30px 안쪽에** 찍었다
   (여울 +26/-23 · 흑연 +26/-37 · 블룸 +25/-22 · 소닉 +16/-16 · 이두나 +30/-20).
   v2 는 정확히 1/4 균등분할로 자르므로, 한 행을 가는 동안 칸 중심이 왼쪽으로 15~43px
   흘러가다 다음 행에서 되돌아온다. 그 되돌아오는 자리가 f04→f05 · f08→f09 · f12→f13 이고,
   **5캐릭터 x 3경계 = 15곳 전부**가 +14.5 ~ +39.5px 로 튀었다.
   ★결정적 증거: 흑연 3행(f09~12)과 4행(f13~16)은 본체 좌끝·우끝이 한 픽셀도 다르지 않은데
     (움직이는 건 유령뿐) 그 안에서도 똑같이 -37.5px 흐른다. 몸이 안 변하는데 자리만 흐른다
     = 애니메이션이 아니라 **절단** 문제다.
   → 처방: **모델이 실제로 찍은 마크선**으로 자른다. `lattice_points` 는 v2 도 이미 부르고 있었다.
     쓰기만 안 했을 뿐이다. 마크선으로 자르면 15곳이 전부 8.5px 이하로 사라진다.

2) **`--align foot` 이 딱 한 곳에서 폭발한다.** 소닉 f08→f09 에서 -48.5px.
   `foot_ref` 는 실루엣 **최하단 4% 띠**를 보는데, 앉음→누움이 끝나는 f08 에서 그 띠가
   신발·엉덩이에서 **누운 등·퍼진 가시**로 갈아탄다. 적용된 dx 가 f05~f09 에 걸쳐
   +16 → +22 → +32 → **+52 → -16** 으로 한 칸에 68px 튄다.
   이 점프는 **마크절단으로 바꿔도 `--align foot` 을 켜 두면 그대로 남는다**(실측 -48.5px).
   즉 절단과 정렬은 **서로 다른 두 원인**이다.
   → 처방: 자세 프로파일(`--align posture`). 아래 3번.
   (자세별 후처리의 16프레임판. 8종 쪽은 `state8_v5` 가 이미 자세로 갈랐다.)

v2 대비 무엇이 다른가 — 딱 세 가지
------------------------------------
1. **절단 기준 = 검출된 마크선**(`--cut marks`, 기본). 25개 마크를 5열/5행으로 뭉쳐
   칸 경계를 그 선으로 잡는다. 칸 크기가 칸마다 다르므로, **칸 중앙 x**와 **칸 아래선 y**를
   기준으로 공통 서비스 캔버스에 앉힌다(캔버스 크기는 칸 크기의 중앙값으로 고정).
   ⚠️**못 찾으면 조용히 틀리지 않는다** — 마크가 5x5 로 안 뭉치면 균등분할로 **폴백하고
   그 사실을 로그에 남긴다** — 판별은 코드가 결정적으로 하고, 실패는 소리를 내야 한다.
2. **`--align posture`(기본)** — 자세 프로파일.
     dy : 칸마다 `foot_ref` y 를 중앙값에 맞춘다 (v2 와 같다. **바닥선은 자세가 바뀌어도
          바닥선**이라 이 축은 v5 에서도 0~1px 로 잘 맞았다.)
     dx : **한 번만 구한다.** 몸이 완전히 멈춘 눕기 구간(기본 f09~f16)의 `foot_ref` x
          중앙값으로 **16칸 전체를 같은 양만큼** 민다. 칸마다 다시 구하지 않으므로
          기준 부위가 갈아타도 **가로 점프가 생길 수가 없다.**
          (앉음 구간도 시드의 `SEAT ANCHOR` 상 제자리여야 하고, 마크절단이 이미 표류를
           지웠으므로 가로는 손댈 이유가 없다.)
   `--align foot`(v2 와 동일) · `--align none` 도 그대로 남겨 둔다 — 회귀 대조용.
   `--lie-from N` 으로 눕기 시작 칸을 바꾼다(기본 9 = 뒤로넘어짐 v5·v6 배분).
3. **`--cut even` 으로 v2 의 절단을 그대로 재현**할 수 있다. 회귀 비교를 같은 파일 안에서 하려고.

절단·키잉·격자점 제거·침범 제거의 **알맹이는 v2 와 같은 함수**를 그대로 쓴다
(`state8_v4._v3_key_green` · `state8_v5.mark_zone/strip_marks_in_zone` · `state8_v3.drop_intruders`).
바뀐 것은 **어디를 자르는가**와 **얼마나 미는가** 둘뿐이다.

출력 파일 이름·위치는 v1·v2 와 완전히 동일하다(러너가 그 이름을 찾는다):
    <grid.png 옆>/cut/f01~f16.png · 애니.gif · 시트.png

사용:
    state16_v3.py <grid.png> [--cut marks|even] [--align posture|foot|none]
                  [--lie-from 9] [--duration 120]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state8_v3 as S8   # noqa: E402  (읽기만 — drop_intruders/foot_ref/move/save_transparent_gif)
import state8_v4 as S4   # noqa: E402  (읽기만 — 감싸지기 **전**의 v3 순정 키잉 `_v3_key_green` 만 쓴다)
import state8_v5 as S5   # noqa: E402  (읽기만 — lattice_points/mark_zone/strip_marks_in_zone)

PAD_CUT_TOP = 60    # 칸 위쪽 절단 여유 — v2 와 같은 값·같은 근거
PAD_ALIGN = 120     # 정렬 클리핑 방지 여유 — v2 와 같은 값
TOP_MARGIN = 4      # 최종 잘라내기에서 최상단 픽셀 위에 남기는 여백 — v2 와 같은 값
TOP_MIN_BLOB = 24   # 캔버스를 키울 자격이 있는 최소 덩어리 — v2 와 같은 값·같은 근거
CLUSTER_TOL = 25    # 마크 좌표를 한 선으로 볼 허용 오차(px)


# ────────────────────────────────────────────────────────────── 절단선
def _cluster(vals, tol=CLUSTER_TOL):
    vals = sorted(vals)
    out, cur = [], [vals[0]]
    for a in vals[1:]:
        if a - cur[-1] <= tol:
            cur.append(a)
        else:
            out.append(sum(cur) / len(cur)); cur = [a]
    out.append(sum(cur) / len(cur))
    return out


def cut_lines(rgb, cols, rows, mode):
    """칸 경계선 (xs, ys) 을 돌려준다. 두 번째 반환값은 '무엇으로 잡았는지' 설명."""
    H, W = rgb.shape[:2]
    even = ([W * i / cols for i in range(cols + 1)],
            [H * i / rows for i in range(rows + 1)])
    if mode == "even":
        return even, [], "균등분할(--cut even 지정)"

    pts = S5.lattice_points(rgb)
    if len(pts) < (cols + 1) * (rows + 1) * 0.6:
        return even, pts, (f"⚠️폴백: 마크 {len(pts)}개로는 격자를 못 세운다 → 균등분할로 자른다")
    cx = _cluster([p[0] for p in pts])
    cy = _cluster([p[1] for p in pts])
    if len(cx) != cols + 1 or len(cy) != rows + 1:
        return even, pts, (f"⚠️폴백: 마크가 {len(cx)}x{len(cy)} 선으로 뭉쳤다"
                           f"({cols+1}x{rows+1} 이어야 함) → 균등분할로 자른다")
    # 단조·양수 폭 확인 — 뭉치기가 엉킨 판을 조용히 넘기지 않는다
    wid = [cx[i + 1] - cx[i] for i in range(cols)]
    hei = [cy[i + 1] - cy[i] for i in range(rows)]
    if min(wid) < W / cols * 0.5 or min(hei) < H / rows * 0.5:
        return even, pts, (f"⚠️폴백: 칸 폭/높이가 비정상 {[round(v) for v in wid]} "
                           f"{[round(v) for v in hei]} → 균등분할로 자른다")
    off = (f"바깥 어긋남 x {cx[0]-even[0][0]:+.0f}/{cx[-1]-even[0][-1]:+.0f} · "
           f"y {cy[0]-even[1][0]:+.0f}/{cy[-1]-even[1][-1]:+.0f}")
    return (cx, cy), pts, f"검출된 마크선({len(pts)}개 · {off})"


# ────────────────────────────────────────────────────────────── 칸 뜨기
def cut_one(ext, xs, ys, r, c, W0, medH, PAD_CUT, points, ytop_pad):
    """칸 하나를 뜬다. 가로는 **칸 중앙**, 세로는 **칸 아래선**을 기준으로 공통 캔버스에 앉힌다.
    (칸 크기가 칸마다 다르므로 v2 처럼 '칸 왼쪽·위 모서리 + 고정크기'로는 못 맞춘다.)"""
    ccx = (xs[c] + xs[c + 1]) / 2.0
    ybot = ys[r + 1] + ytop_pad          # ext 는 위로 ytop_pad 만큼 밀려 있다
    ytop_line = ys[r] + ytop_pad

    x0 = int(round(ccx - W0 / 2.0))
    y0 = int(round(ybot - medH - PAD_CUT_TOP))
    y1 = int(round(ybot + PAD_CUT))
    box = (x0, y0, x0 + W0, y1)
    cell = S4._v3_key_green(ext.crop(box), int(medH))

    # 이 칸 안에서 격자선이 지나는 로컬 y 두 개
    corner_rows = (ytop_line - y0, ybot - y0)
    zone = S5.mark_zone((cell.height, cell.width), corner_rows,
                        (x0, y0 - ytop_pad), points)   # points 는 원본 좌표계
    before = int((np.array(cell.convert("RGBA"))[:, :, 3] > 8).sum())
    cell2 = S5.strip_marks_in_zone(cell, zone)
    after = int((np.array(cell2.convert("RGBA"))[:, :, 3] > 8).sum())
    return cell2, before - after


# ────────────────────────────────────────────── 접지 앵커 (--align seat 전용, 2026-09-12 추가)
SEAT_BAND = 0.06   # 접지 띠 = 본체 높이의 아래 6%
SEAT_KEEP = 0.25   # 띠 안 덩어리 중 가장 큰 것의 25% 이상만 접지로 인정(꼬리·잔해 제외)


def seat_anchor(im):
    """**접지 덩어리 중심 x** 와 **본체 최하단 y**.

    ★`foot_ref` 와 무엇이 다른가 — `foot_ref` 는 실루엣 최하단 4% 띠의 좌우끝 중점을 쓰는데,
      앉았다 눕는 동작에서는 그 띠가 신발 → 엉덩이 → 누운 등 → 퍼진 머리카락으로 **갈아탄다**.
      기준이 갈아타는 순간 보정량이 통째로 튄다(2026-09-12 실측: 소닉 f08→f09 에서 dx 가
      +52 → -16, 화면에서는 48px 순간이동).
    → 여기서는 (1) **본체(가장 큰 덩어리)로 한정**해 떨어진 유령을 아예 빼고
      (2) 띠 안에서도 **가장 큰 접지 덩어리의 25% 이상**만 남겨 잔해·꼬리를 뺀다.
      `mk_char` 와 같은 사상이다(유령이 몸에 닿아 한 덩어리가 되면 못 가르지만, 유령은 늘
      몸 **위**에 있어 접지 띠에는 안 들어온다 — 그래서 이 지표는 붙어 있어도 안전하다).
    """
    m = np.array(im.convert("RGBA"))[:, :, 3] > 8
    lab, n = ndimage.label(m)
    if n == 0:
        return 0.0, 0.0
    if n > 1:
        sz = ndimage.sum(m, lab, range(1, n + 1))
        m = lab == int(np.argmax(sz)) + 1
    ys, xs = np.nonzero(m)
    y0, y1 = int(ys.min()), int(ys.max())
    band = m.copy()
    band[:int(y1 - (y1 - y0) * SEAT_BAND), :] = False
    if band.any():
        bl, bn = ndimage.label(band)
        if bn > 1:
            bs = ndimage.sum(band, bl, range(1, bn + 1))
            band = np.isin(bl, np.nonzero(bs >= bs.max() * SEAT_KEEP)[0] + 1)
        bx = np.nonzero(band)[1]
        ax = float((bx.min() + bx.max()) / 2)
    else:
        ax = float((xs.min() + xs.max()) / 2)
    return ax, float(y1)


def med3(v):
    """3칸 중앙값 필터 — 무너짐은 단조 진행이라 앵커 x 가 한 칸만 왕복하면 그건 측정 오염이다.
    양 끝은 그대로 둔다(경계에서 없는 이웃을 지어내지 않는다)."""
    out = list(v)
    for i in range(1, len(v) - 1):
        out[i] = float(sorted(v[i - 1:i + 2])[1])
    return out


def checker(w, h, s=16):
    bg = Image.new("RGB", (w, h), (210, 214, 220)); px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // s) + (y // s)) % 2 == 0:
                px[x, y] = (170, 176, 186)
    return bg


def expand(im, pad=PAD_ALIGN):
    out = Image.new("RGBA", (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    out.paste(im, (pad, pad))
    return out


# ────────────────────────────────────────────────────────────── 본체
def main(grid, cols=4, rows=4, cut="marks", align="posture", lie_from=9, lie_dx="fixed",
         smooth=True, seat_lock="global", duration=120):
    grid = Path(grid); R = grid.parent
    n = cols * rows
    im = Image.open(grid).convert("RGB")
    arr = np.array(im); H, W = arr.shape[:2]

    (xs, ys), points, how = cut_lines(arr, cols, rows, cut)
    print(f"절단선 = {how}")
    if how.startswith("⚠️"):
        print("   ↑ 이 판은 v2 와 같은 균등분할로 잘렸습니다. 앵커 톱니가 남을 수 있습니다.")
    if not points:
        points = S5.lattice_points(arr)
    print(f"격자점 검출 {len(points)}개")

    wid = [xs[i + 1] - xs[i] for i in range(cols)]
    hei = [ys[i + 1] - ys[i] for i in range(rows)]
    medW = float(np.median(wid)); medH = float(np.median(hei))
    PAD_CUT = int(medH * 0.12)        # 칸 아래 여유 — v1/v2/v3 그대로 12%
    W0 = int(round(medW))
    H0 = int(round(medH)) + PAD_CUT   # 서비스 캔버스 높이
    print(f"칸 폭 {[round(v) for v in wid]} (중앙값 {medW:.0f}) · "
          f"칸 높이 {[round(v) for v in hei]} (중앙값 {medH:.0f}) → 캔버스 {W0}x{H0}")

    # 사방으로 넉넉히 넓힌 작업용 캔버스 (마크선이 가장자리 바깥을 가리켜도 안 잘리게)
    SIDE = 200
    ext = Image.new("RGB", (W + SIDE * 2, H + SIDE * 2), (0, 255, 0))
    ext.paste(im, (SIDE, SIDE))
    xs_e = [v + SIDE for v in xs]

    cells, gp_removed = [], []
    for r in range(rows):
        for c in range(cols):
            cell, rm = cut_one(ext, xs_e, ys, r, c, W0, medH, PAD_CUT, points, SIDE)
            cells.append(cell); gp_removed.append(rm)
    print(f"격자점 제거 합계 {sum(gp_removed)}px  (칸별 {gp_removed})")

    tot, rm_each = 0, []
    for i, cl in enumerate(cells):
        cl, rm = S8.drop_intruders(cl)
        cells[i] = cl; tot += rm; rm_each.append(rm)
    print(f"침범 제거 {tot}px  (칸별 {rm_each})")

    cells = [expand(c, PAD_ALIGN) for c in cells]

    # ── 발 좌표 — 정렬 여부와 무관하게 항상 잰다
    refs = [S8.foot_ref(c) for c in cells]
    fx = [r_[0] for r_ in refs]; fy = [r_[1] for r_ in refs]
    print(f"발 좌표 산포 — 가로 {max(fx)-min(fx):.1f}px · 세로 {max(fy)-min(fy):.1f}px")

    ry = float(np.median(fy))
    lie = list(range(lie_from - 1, n))                     # 눕기 구간(0-based)
    rx_lie = float(np.median([fx[i] for i in lie]))
    rx_all = float(np.median(fx))

    # ── ★--align seat (2026-09-12 검수 — 앵커가 흔들린다는 지적의 처방) ─────────────
    #    a) 앉은 구간 f01~f08 : **엉덩이 접점 x 고정** — 8칸의 접지 앵커 중앙값에 전부 맞춘다.
    #    b) 누운 구간 f09~f16 : **첫 누운 칸(f09) 에 dx 를 못박는다** — 그 뒤 칸은 f09 의 접지
    #       앵커에 맞춰, 누운 몸이 칸마다 조금씩 어긋나 그리는 흔들림까지 지운다.
    #    c) 구간 경계 f08→f09 : f09 의 보정량을 **f08 과 같게** 둬서 가로 팝이 원천적으로 못 생긴다.
    #    d) 세로 : 16칸 전부 본체 최하단을 **한 바닥선**에 맞춘다(바닥은 자세가 바뀌어도 바닥이다).
    #    앵커 x 에는 3칸 중앙값 필터(--no-smooth 로 끔).
    seat = [seat_anchor(c) for c in cells]
    sx = [p[0] for p in seat]; sy = [p[1] for p in seat]
    L0 = lie_from - 1
    # ★평활은 **무너짐 구간에만** 건다. 거기만 단조 진행이라 한 칸 왕복이 측정 오염이고,
    #   누운 구간은 몸이 멈춰 있으니 평활한 값에 못박으면 오히려 원래 떨림이 그대로 남는다
    #   (2026-09-12 1차 시제품에서 실제로 그랬다 — 소닉 f12→13 -18.5px).
    if smooth:
        sx = med3(sx[:L0]) + sx[L0:]
    base_y = float(np.median(sy))
    A_sit = float(np.median(sx[:L0]))
    dx_seat = [0] * n
    for i in range(L0):
        dx_seat[i] = int(round(A_sit - sx[i]))
    if seat_lock == "global":
        # (c) "구간 경계에서 x 는 f08 값 유지" — 누운 칸도 **같은 목표 x** 에 맞춘다.
        #     f08 의 보정 후 앵커가 곧 A_sit 이므로, 목표를 A_sit 으로 두면 경계 팝이 0 이 된다.
        for i in range(L0, n):
            dx_seat[i] = int(round(A_sit - sx[i]))
    else:
        # f09 의 자리를 그대로 두고 그 뒤 칸만 f09 에 못박는다(경계의 물리적 이동은 남긴다)
        dx8 = dx_seat[L0 - 1]
        for i in range(L0, n):
            dx_seat[i] = int(round(dx8 + (sx[L0] - sx[i])))

    moved = 0
    for i in range(n):
        cx, by = refs[i]
        if align == "seat":
            dx = dx_seat[i]
            dy = int(round(base_y - sy[i]))
            tag = ("접지앵커/앉음 고정" if i < L0 else "접지앵커/누움 " + seat_lock)
        elif align == "posture":
            # ★가로: 눕기 구간만 칸별로 맞추고, 무너짐 구간은 **눕기 첫 칸의 값을 그대로 쓴다**
            #   (lie_dx="foot"). 그러면 무너짐 구간 안에서는 보정이 상수라 기준 부위가
            #   갈아타도 점프가 안 생기고, 구간 경계 f08→f09 도 두 칸의 dx 가 같아 연속이다.
            #   lie_dx="fixed" 면 16칸 전부 같은 값(눕기 중앙값)으로 한 번만 민다.
            if lie_dx == "foot":
                j = i if i >= lie_from - 1 else lie_from - 1
                dx = int(round(rx_lie - fx[j]))
            else:
                dx = int(round(rx_lie - rx_all))
            dy = int(round(ry - by))
            tag = f"자세프로파일(가로 {lie_dx} · 세로 발기준)"
        elif align == "foot":
            dx = int(round(rx_all - cx)); dy = int(round(ry - by)); tag = "발기준(v2와 동일)"
        else:
            dx = dy = 0; tag = "정렬 안 함"
        if dx or dy:
            cells[i] = S8.move(cells[i], dx, dy); moved += 1
        print(f"  f{i+1:02d} dx={dx:+3d} dy={dy:+3d} ({tag})")
    if align == "seat":
        print(f"정렬 {moved}/{n}칸 · 접지앵커 — 앉음 f01~f{lie_from-1:02d} 는 x={A_sit:.0f} 에 고정 · "
              f"누움 f{lie_from:02d}~f{n:02d} 는 {(chr(39)+"같은 x 에 고정"+chr(39)) if seat_lock==chr(103)+chr(108)+chr(111)+chr(98)+chr(97)+chr(108) else (chr(39)+"f09 에 못박음"+chr(39))} · 세로는 바닥선 y={base_y:.0f} · "
              f"평활 {'켬(3칸 중앙값)' if smooth else '끔'}")
    elif align == "posture":
        print(f"정렬 {moved}/{n}칸 · 가로 {lie_dx}(눕기 f{lie_from:02d}~f{n:02d} 기준"
              f"{', 무너짐 구간은 f%02d 값 그대로' % lie_from if lie_dx=='foot' else ', 16칸 공통'})"
              f" · 세로는 칸별 발 기준")
    elif align == "foot":
        print(f"정렬 {moved}/{n}칸 · 발 기준 중앙값에 맞춤")
    else:
        print("정렬 건너뜀(--align none)")

    # ── 최종 잘라내기 — v2 와 같은 방식(아래·좌·우 고정, 위만 동적, 티끌은 크기판단 제외)
    base_top = PAD_ALIGN + PAD_CUT_TOP
    alpha_tops, speck = [], 0
    for k, c in enumerate(cells):
        a_ = np.array(c); a = a_[:, :, 3] > 8
        lab, nn = ndimage.label(a)
        if not nn:
            continue
        kill = np.zeros_like(a)
        for i, sl in enumerate(ndimage.find_objects(lab), 1):
            px = int((lab[sl] == i).sum())
            if px >= TOP_MIN_BLOB:
                alpha_tops.append(int(sl[0].start))
            elif sl[0].stop <= base_top:
                kill[sl] |= (lab[sl] == i); speck += px
        if kill.any():
            a_[:, :, 3] = np.where(kill, 0, a_[:, :, 3]); cells[k] = Image.fromarray(a_)
    uy0 = min(alpha_tops) if alpha_tops else base_top
    top = max(0, min(uy0 - TOP_MARGIN, base_top))
    box = (PAD_ALIGN, top, PAD_ALIGN + W0, base_top + H0)
    cells = [c.crop(box) for c in cells]
    print(f"최종 캔버스 {cells[0].size} (서비스 규격 {W0}x{H0} · 위 여유 {base_top-top}px 남김"
          f" · PAD 안 윗칸 티끌 {speck}px 제거·크기판단 제외)")

    cut_dir = R / "cut"; cut_dir.mkdir(exist_ok=True)
    for i, cl in enumerate(cells, 1):
        cl.save(cut_dir / f"f{i:02d}.png")
    S8.save_transparent_gif(cells, R / "애니.gif", duration)
    w, h = cells[0].size
    cv = checker(w * cols, h * rows).convert("RGBA")
    for i, cl in enumerate(cells):
        cv.alpha_composite(cl, ((i % cols) * w, (i // cols) * h))
    cv.convert("RGB").save(R / "시트.png")
    print("저장:", R / "애니.gif", "·", R / "시트.png")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("grid")
    ap.add_argument("--cut", choices=["marks", "even"], default="marks")
    ap.add_argument("--align", choices=["seat", "posture", "foot", "none"], default="posture")
    ap.add_argument("--lie-from", type=int, default=9)
    ap.add_argument("--lie-dx", choices=["foot", "fixed"], default="fixed")
    ap.add_argument("--no-smooth", action="store_true")
    ap.add_argument("--seat-lock", choices=["global", "f09"], default="global")
    ap.add_argument("--duration", type=int, default=120)
    if len(sys.argv) < 2:
        print(__doc__); raise SystemExit(2)
    ns = ap.parse_args()
    main(ns.grid, cut=ns.cut, align=ns.align, lie_from=ns.lie_from, lie_dx=ns.lie_dx, smooth=not ns.no_smooth, seat_lock=ns.seat_lock, duration=ns.duration)
