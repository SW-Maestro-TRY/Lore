#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""16프레임 격자 후처리 v2 — 2026-09-12 신설.
`state16_post.py`(v1) · `state8_v3.py` · `state8_v4.py` · `state8_v5.py` 는 한 글자도 고치지 않는다
(확정된 도구는 안 고치고, 새 파일을 v02 로 만들어 다시 검수받는다).

왜 새 파일인가
--------------
v1(`state16_post.py`)은 절단·키잉의 격자점 제거를 `state8_v3.key_green()`에 통째로 맡긴다.
그런데 v3 의 격자점 제거는 **극도로 순수한 마젠타/시안 색**(R>235&G<50&B>235 등)만 씨앗으로 인정하고,
위치도 **균등분할 칸의 네 모서리**만 본다. 2026-09-01 전수 스캔(5캐릭터 192프레임, hue 기준 잔여 검사)
에서 여울 11 · 흑연 4 · 블룸 16 · 김애용 13 · 소닉 3 프레임에 마젠타/시안 격자점이 남은 채 통과했다.
`state8_v5.py`(2026-09-12)는 같은 문제를 8종 쪽에서 이미 두 가지로 고쳤다 —
    1) 씨앗을 **hue 판정**(마젠타 280~340 · 시안 165~205)으로 넓히고
    2) 위치 제한을 균등분할 네 모서리에서 **격자 전체를 스캔해 실제로 찍힌 마크 좌표 둘레**로 바꿨다
       (모델이 그리는 마크가 균등분할선과 몇~수십 px 어긋나는 판이 있어, 모서리만 보면 그 판은
       씨앗이 통째로 탈락한다 — 실측: 흑연 3행 칸 아래 마크가 모서리 띠 밖 로컬 y=240에 찍힘).
이 파일은 그 두 처방(`state8_v5.lattice_points`/`mark_zone`/`strip_marks_in_zone`)을 16프레임
쪽으로 그대로 가져다 쓴다 — 새로 설계하지 않는다.

v1 대비 무엇이 다른가
----------------------
1. **격자점 제거를 v5 방식으로 교체**(위 "왜" 참고). 절단·1차 키잉(`state8_v3.key_green`)·
   침범제거(`state8_v3.drop_intruders`)는 v1 과 동일하게 v3 것을 그대로 쓴다 — 바뀐 것은
   "격자점이 안 지워지는 칸을 마저 지우는" 2차 패스뿐이다.
2. **절단할 때 칸 위쪽에도 여유(`PAD_CUT_TOP`=60)를 둔다**(state8_v5 처방 이식). 마크의 실측 좌표가
   칸 경계 밖으로 나가 있는 판이 있어(위 실측), 격자점 검출·구역계산이 칸 위 경계 바로 바깥의
   마크까지 봐야 하기 때문이다. 위 여유는 grid-point 제거·침범 제거가 끝난 뒤 **필요한 만큼만
   남기고** 서비스 캔버스(W0 x H0, v1 과 동일 크기)로 되돌린다(아래는 v3 그대로 12%, 좌우는 원본
   경계 고정 — v5 의 "마지막 잘라내기" 방식). 정렬 이전에 **정렬 여유(`PAD_ALIGN`=120)**도 함께
   둔다 — v1 의 `move()`는 같은 크기 캔버스에 그대로 붙여 넣어 정렬이 클수록 머리·발이 조용히
   잘릴 수 있다(state8_v5 2번 처방과 같은 이유). 실제로 계산되는 dx·dy 값 자체는 v1 과 동일한
   식(발 좌표 중앙값)이라 **정렬량이 달라지는 게 아니라, 잘리지 않게 캔버스만 넉넉히 쓴다.**
3. **정렬을 선택적으로 만들었다 — 기본값은 `--align none`(정렬 안 함), `--align foot`을 주면
   v1 과 같은 정렬(16칸을 발 좌표 중앙값에 맞춤)을 켠다.**
   ★기본을 none 으로 둔 이유 — 2026-08-18 검수에서 넘어지기류 재절단본의 마지막 부분에
   땅이 크게 솟아오르는 결함이 잡혔다. 선물 동작(구르기·뒤로넘어짐)은
   캐릭터가 눕거나 구르는 프레임이 많아서 `foot_ref`(실루엣 최하단 4% 밴드)가 매 프레임 다른
   신체 부위(등·엉덩이·머리)를 가리키게 되고, "16칸을 발 좌표 중앙값에 맞춘다"는 v1 의 전제 자체가
   깨진다. 그 상태에서 정렬을 강행하면 바닥선이 프레임마다 널뛰는데, 그때 본 사고가 바로 그것
   이었다(state8_v5 가 서 있음/앉음/눕기로 자세를 갈라 대응한 것과 같은 문제의 16f 버전).
   16프레임에는 아직 state8_v5 같은 자세별 프로파일이 없으므로, **안전한 쪽(칸을 뜬 그대로 안
   건드림)을 기본으로 두고**, 서 있는 동작처럼 발이 실제로 고정인 시퀀스에서만
   `--align foot`으로 켠다.
4. **정렬 여부와 무관하게 칸별 통계(발 좌표 산포·격자점 제거 px·침범 제거 px)를 항상 출력한다** —
   정렬을 끈 채로도 "이 시퀀스가 발 기준 정렬에 적합한 동작인가"를 숫자로 먼저 볼 수 있게.

출력 파일 이름·위치는 v1(`state16_post.py`)과 완전히 동일하다(러너가 그 이름을 찾는다):
    <grid.png 옆>/cut/f01~f16.png · 애니.gif · 시트.png

사용:
    state16_v2.py <grid.png> [--align foot|none] [--duration 120]
        --align foot  = 16칸을 발 좌표 중앙값에 맞춘다 (v1 과 같은 동작)
        --align none  = 정렬하지 않는다 (기본값 — 위 3번 근거)
        --duration    = 애니.gif 프레임 간격(ms), 기본 120 (v1 과 동일 기본값)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state8_v3 as S8   # noqa: E402  (읽기만 — 기존 파일 불변. key_green/drop_intruders/foot_ref/move/save_transparent_gif)
import state8_v4 as S4   # noqa: E402  (읽기만. import 시 S8.key_green 이 v4 hue 패스로 감싸지지만,
                          #   여기서는 감싸지기 **이전**의 원본을 `S4._v3_key_green` 로 명시적으로 쓴다 —
                          #   state8_v5.py 와 똑같은 방식. 이유는 아래 `cut_one` 참고.)
import state8_v5 as S5   # noqa: E402  (읽기만. lattice_points/mark_zone/strip_marks_in_zone 재사용)

PAD_CUT_TOP = 60   # 칸 위쪽 절단 여유 — state8_v5 와 같은 값·같은 근거(위 docstring 2번)
PAD_ALIGN = 120    # 정렬 클리핑 방지용 여유 — state8_v5 와 같은 값
TOP_MARGIN = 4     # 최종 잘라내기에서 최상단 픽셀 위에 남기는 여백 — state8_v5 와 같은 값

# ★2026-09-12 추가 — 최종 잘라내기에서 **캔버스를 키울 자격이 있는 최소 덩어리 크기(px)**.
#   왜: 위 PAD(60px) 안으로 윗칸의 **1~3px 티끌**이 딸려 들어오는데, `drop_intruders` 는
#   '테두리에 닿은 덩어리'만 걷어내므로 PAD 한가운데 떠 있는 티끌은 그대로 남는다.
#   그 티끌 하나가 `uy0`(최상단 알파)를 끌어올려 **서비스 캔버스를 통째로 키운다** —
#   실측(선물 2종 v02 10판): 여울 구르기 1px 티끌 하나가 캔버스를 349→416px(+67) 로,
#   이두나 구르기 2px 이 349→423(+74) 로 부풀렸다. 나머지 6판은 349 라 **판마다 크기가 달라진다.**
#   → 캔버스를 키우는 판단에는 이 크기 이상인 덩어리만 센다(티끌은 그대로 잘려 나간다).
#   ⚠️24px 인 근거: 이번 10판에서 '진짜 그림'의 최상단 덩어리는 전부 24px 을 크게 넘고
#     (가장 작은 것도 수백 px), 딸려온 잔해는 전부 3px 이하였다 — 두 무리 사이가 비어 있다.
TOP_MIN_BLOB = 24


def checker(w, h, s=16):
    bg = Image.new("RGB", (w, h), (210, 214, 220)); px = bg.load()
    for y in range(h):
        for x in range(w):
            if ((x // s) + (y // s)) % 2 == 0:
                px[x, y] = (170, 176, 186)
    return bg


def expand(im, pad=PAD_ALIGN):
    """정렬 여유를 사방에 붙인다 — 이동(있다면)은 전부 이 넓은 캔버스 안에서 일어난다."""
    out = Image.new("RGBA", (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    out.paste(im, (pad, pad))
    return out


def cut_one(ext, im_orig_shape, cw, ch, r, c, W0, H0, points):
    """칸 하나를 위PAD 포함해 뜨고, v3 키잉 → v5 방식 격자점 제거까지 마친 뒤
    (제거된 px, 셀) 을 돌려준다.

    ★`S4._v3_key_green` 을 쓰는 이유 — `import state8_v4`의 부작용으로
      `state8_v3.key_green` 이 이미 v4 의 hue 후행패스로 감싸져 있다(v4 자체 마크 제거,
      위치기준=균등분할 모서리만). 그걸 그대로 부르면 이 파일의 v5 식 마크 제거와 **중복**
      되면서도 v4 의 위치기준은 여전히 부정확하다. `state8_v5.py` 가 쓴 것과 똑같이
      **감싸지기 전의 원본**(`_v3_key_green`, v3 순정 키잉)을 먼저 태우고, 그 위에
      이 파일이 v5 방식(실측 마크 좌표 구역 제한)의 2차 제거를 얹는다.
    """
    x0 = int(round(c * cw))
    gy = int(round(r * ch))
    up = PAD_CUT_TOP
    box = (x0, gy + PAD_CUT_TOP - up, x0 + W0, gy + PAD_CUT_TOP + H0)
    cell = S4._v3_key_green(ext.crop(box), up + int(ch))
    # 균등분할 격자선이 이 칸 안에서 지나는 로컬 y 두 개(위 PAD 만큼 밀려 있다)
    corner_rows = (up, up + int(ch))
    zone = S5.mark_zone((cell.height, cell.width), corner_rows, (x0, gy - up), points)
    before = int((np.array(cell.convert("RGBA"))[:, :, 3] > 8).sum())
    cell2 = S5.strip_marks_in_zone(cell, zone)
    after = int((np.array(cell2.convert("RGBA"))[:, :, 3] > 8).sum())
    return cell2, before - after


def main(grid, cols=4, rows=4, align="none", duration=120):
    grid = Path(grid); R = grid.parent
    n = cols * rows
    im = Image.open(grid).convert("RGB"); W, H = im.size
    cw, ch = W / cols, H / rows

    PAD_CUT = int(ch * 0.12)          # 칸 아래 여유 — v1/v3 그대로(12%)
    W0 = int(cw)
    H0 = int(ch) + PAD_CUT            # ★서비스 캔버스(v1 과 동일 크기) — 이 값은 안 바뀐다

    # 위·아래 동시로 넓힌 작업용 캔버스. 위 PAD_CUT_TOP 만큼 원본을 아래로 밀어 붙인다.
    ext = Image.new("RGB", (W, H + PAD_CUT + PAD_CUT_TOP), (0, 255, 0))
    ext.paste(im, (0, PAD_CUT_TOP))

    # ★격자 전체에서 마크를 먼저 찾는다(원본 좌표계) — 이게 v5 식 제거의 구역 근거다.
    points = S5.lattice_points(np.array(im))
    print(f"격자점 검출 {len(points)}개")

    cells = []
    gp_removed = []
    for r in range(rows):
        for c in range(cols):
            cell, rm = cut_one(ext, (H, W), cw, ch, r, c, W0, H0, points)
            cells.append(cell)
            gp_removed.append(rm)
    print(f"격자점 제거 합계 {sum(gp_removed)}px  (칸별 {gp_removed})")

    tot = 0
    rm_each = []
    for i, cl in enumerate(cells):
        cl, rm = S8.drop_intruders(cl)
        cells[i] = cl
        tot += rm
        rm_each.append(rm)
    print(f"침범 제거 {tot}px  (칸별 {rm_each})")

    # ── 정렬 여유 캔버스로 확장 — 정렬을 켜지 않아도 미리 확장해 뒀다가 그대로 되돌린다.
    #    (클리핑 방지 목적일 뿐, align=none 이면 이동량이 0이라 결과는 그대로다.)
    cells = [expand(c, PAD_ALIGN) for c in cells]

    # ── 발 좌표 통계 — 정렬 여부와 무관하게 항상 계산·출력한다.
    refs = [S8.foot_ref(c) for c in cells]
    fx = [r_[0] for r_ in refs]; fy = [r_[1] for r_ in refs]
    rx = float(np.median(fx)); ry = float(np.median(fy))
    print(f"발 좌표 산포 — 가로 {max(fx) - min(fx):.1f}px · 세로 {max(fy) - min(fy):.1f}px "
          f"(중앙값 {rx:.1f}, {ry:.1f})")

    moved = 0
    for i in range(n):
        cx, by = refs[i]
        dx, dy = int(round(rx - cx)), int(round(ry - by))
        if align == "foot":
            if dx or dy:
                cells[i] = S8.move(cells[i], dx, dy)
                moved += 1
            print(f"  f{i+1:02d} dx={dx:+3d} dy={dy:+3d} (정렬함)")
        else:
            print(f"  f{i+1:02d} dx={dx:+3d} dy={dy:+3d} (참고치 — 정렬 안 함, --align foot 이면 이만큼 움직였을 것)")
    if align == "foot":
        print(f"정렬 {moved}/{n}칸 · 발 기준 중앙값에 맞춤")
    else:
        print("정렬 건너뜀(--align none) — 칸을 뜬 그대로 둔다 (선물/넘어짐류 안전 기본값)")

    # ── 최종 잘라내기 — 위PAD·정렬여유 중 **필요한 만큼만** 남기고 서비스 캔버스(W0 x H0)로
    #    되돌린다. 아래·좌·우는 원본 칸 경계 고정, 위만 동적으로(state8_v5 "마지막 잘라내기"와 동일 방식).
    base_top = PAD_ALIGN + PAD_CUT_TOP
    alpha_tops = []
    speck = 0
    for k, c in enumerate(cells):
        arr = np.array(c)
        a = arr[:, :, 3] > 8
        lab, n = ndimage.label(a)
        if not n:
            continue
        kill = np.zeros_like(a)
        for i, sl in enumerate(ndimage.find_objects(lab), 1):
            px = int((lab[sl] == i).sum())
            if px >= TOP_MIN_BLOB:            # ★캔버스를 키울 자격 — 위 TOP_MIN_BLOB 주석
                alpha_tops.append(int(sl[0].start))
            elif sl[0].stop <= base_top:      # 격자선 위(PAD 구간)에만 들어앉은 티끌 = 윗칸 잔해
                kill[sl] |= (lab[sl] == i)
                speck += px
        if kill.any():
            arr[:, :, 3] = np.where(kill, 0, arr[:, :, 3])
            cells[k] = Image.fromarray(arr)
    uy0 = min(alpha_tops) if alpha_tops else base_top
    top = max(0, min(uy0 - TOP_MARGIN, base_top))
    box = (PAD_ALIGN, top, PAD_ALIGN + W0, base_top + H0)
    cells = [c.crop(box) for c in cells]
    print(f"최종 캔버스 {cells[0].size} (서비스 규격 {W0}x{H0} · 위 여유 {base_top - top}px 남김"
          f" · PAD 안 윗칸 티끌 {speck}px 제거·크기판단 제외)")

    cut = R / "cut"; cut.mkdir(exist_ok=True)
    for i, cl in enumerate(cells, 1):
        cl.save(cut / f"f{i:02d}.png")

    # ── 애니메이션 GIF (투명) — 검수 대상
    S8.save_transparent_gif(cells, R / "애니.gif", duration)

    # ── 4x4 미리보기 시트 (정지)
    w, h = cells[0].size
    cv = checker(w * cols, h * rows).convert("RGBA")
    for i, cl in enumerate(cells):
        cv.alpha_composite(cl, ((i % cols) * w, (i // cols) * h))
    cv.convert("RGB").save(R / "시트.png")

    print("저장:", R / "애니.gif", "·", R / "시트.png")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("grid")
    ap.add_argument("--align", choices=["foot", "none"], default="none")
    ap.add_argument("--duration", type=int, default=120)
    if len(sys.argv) < 2:
        print(__doc__); raise SystemExit(2)
    ns = ap.parse_args()
    main(ns.grid, align=ns.align, duration=ns.duration)
