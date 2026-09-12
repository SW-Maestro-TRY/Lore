#!/usr/bin/env python3
"""프레임 기준 절단 — 셀 경계를 쓰지 않는다.

2026-08-06 상훈님 착안: "땅과 마크를 아예 고정축 프레임으로 해버리는 거지."

왜 셀 경계를 버리는가
---------------------
지금까지는 격자를 1254/4 = 313.5로 기계 등분했다. 그런데 실측하니 GPT가 그린 실제 격자는
균등하지 않았다 — 마크 위치가 줄마다 최대 23px, 열마다 최대 14px 어긋나 있다.
그 위에서 "캐릭터가 셀 경계를 넘쳤다"고 판정한 것은 틀린 기준 위의 결론이었다.
상훈님은 "캐릭터가 땅과 마크를 벗어나지는 않았다"고 보셨고, 그 말이 맞다.

무엇을 기준으로 삼는가
---------------------
    좌마크 ●─────────────● 우마크      ← TOP RAIL (y 기준 + x 양끝)
           │             │
           │   캐릭터    │
    ───────┴─────────────┴───────      ← 바닥선(프롬프트가 마크에 매달아 그린다)

두 마크가 프레임을 정의한다:
  - 마크 사이 거리 = 그 칸의 배율. 칸마다 239~246px로 달랐다 → 정규화하면 크기 울렁임이 잡힌다.
  - 마크의 위치   = 그 칸의 원점. 여기에 맞추면 흔들림이 사라진다.

원본에서 직접 떼어내므로 잘릴 수 없다(원칙: 절대 잘라내지 않는다).

사용: python3 도구/frame_cut.py <격자.png> <출력폴더> [--out 313] [--pad 0.06]
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

MARK_MIN, MARK_MAX = 10, 30
MARK_PX = (60, 260)
LO = 15


def _is_cross(mask: np.ndarray) -> bool:
    """십자인가 — 중앙 행·열에 픽셀이 몰리고 네 모서리는 비어 있다."""
    h, w = mask.shape
    if h < 6 or w < 6:
        return False
    cy, cx = h // 2, w // 2
    mid = mask[max(0, cy - 1):cy + 2, :].sum() + mask[:, max(0, cx - 1):cx + 2].sum()
    cor = (mask[:h // 3, :w // 3].sum() + mask[:h // 3, -w // 3:].sum()
           + mask[-h // 3:, :w // 3].sum() + mask[-h // 3:, -w // 3:].sum())
    tot = mask.sum()
    return tot > 0 and mid >= tot * 0.55 and cor <= tot * 0.12


def _hsv(im: np.ndarray):
    """RGB(0~255) → (hue 0~360, sat 0~1, val 0~1). colorsys는 픽셀 루프라 못 쓴다."""
    a = im.astype(np.float32) / 255.0
    R, G, B = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    mx, mn = a.max(axis=2), a.min(axis=2)
    d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0.0)
    h = np.zeros_like(mx)
    m = d > 1e-6
    i = (mx == R) & m; h[i] = ((G - B)[i] / d[i]) % 6
    i = (mx == G) & m; h[i] = ((B - R)[i] / d[i]) + 2
    i = (mx == B) & m; h[i] = ((R - G)[i] / d[i]) + 4
    return h * 60.0, s, mx


def find_marks_by_color(im: np.ndarray):
    """★색으로 마크를 찾는다 (2026-08-10 신설, p_v9_smooth부터).

    왜 바꿨나
      프롬프트가 마크 색을 말하지 않아 검출을 모양·위치로 **추측**해야 했고 133격자 중 115개(86%)에
      그쳤다. 못 찾은 칸은 이웃에서 보간했고, 오검출이 나면 프레임이 통째로 검게 뭉갰다.
      → 상훈님 안대로 프롬프트에서 색을 못 박았다: **좌=마젠타(#FF00FF) · 우=시안(#00FFFF)**.
        마크는 어차피 지우므로 눈에 띄는 색이어도 무방하다.

    실측(2026-08-10, v9 격자 9장 = 288개 마크)
      · 색만(RGB 임계)            → 블룸에서 절반 누락. 모델이 순색을 정확히 재현하지 않는다(30,188,238).
      · hue만                     → 블룸의 하늘색 의상이 시안으로 잡혀 19·30개 과검출.
      · **hue + 행 상단 30% + 크기** → 9장 전부 마젠타 16 · 시안 16, **100%**.

    ★덤: 좌우가 색으로 갈리므로 페어링이 필요 없다. 마크 개수가 어긋나 좌우 짝이 밀리던
      사고(블룸 신판 40개 검출)가 원천적으로 사라진다.

    ★2026-08-15 재설계 — 밴드와 크기 임계를 버렸다.

    무엇이 깨졌나
      밴드는 `cell_h = H / 4` **균등분할**에 기대고 있었다. v11에서 콩콩뛰기·넘어지기처럼
      캐릭터가 눕거나 웅크리는 동작이 들어오자 모델이 마지막 행을 눌러 그리기 시작했고
      (눌림비 v10 0.997 → v11 0.960, 0.95 미만이 7% → 42%),
      4행 마크가 균등분할선보다 위로 올라가 "3행의 밴드 밖"으로 판정돼 통째로 탈락했다.
      실측: 여울 콩콩뛰기 r2 −4px · 흑연 콩콩뛰기 r2 **−1px** — 1픽셀 차이로 떨어졌다.

    왜 임계값을 안 쓰나
      고치는 과정에서 두 번 틀렸다. 밴드를 빼자 블룸의 분홍 눈·하늘색 의상이 마크로 잡혔고
      (하한 12px), 하한을 40px로 올리자 이번엔 진짜 마크가 탈락했다. 마크 크기는 판마다 다르다.
      → **크기 상위 16개**를 고른다. 잡음은 언제나 마크보다 작으므로 임계를 정할 필요가 없다.

    덤으로 5칸 격자를 잡는다
      17번째 덩어리가 16번째와 비슷한 크기면 그 격자는 4x4가 아니다(실측: 흑연 넘어지기 r1이
      마크 55~64px에 17번째 54px = 마지막 행이 5칸). 이건 **생성 결함이라 절단으로 못 살린다.**
    """
    h, s, v = _hsv(im)
    strong = (s > 0.45) & (v > 0.55)

    def grab(lo, hi):
        """(픽셀수, x, y) 목록 — 밴드도 크기 임계도 걸지 않는다."""
        msk = strong & (h >= lo) & (h <= hi)
        lab, _ = ndimage.label(msk)
        out = []
        for i, sl in enumerate(ndimage.find_objects(lab), 1):
            px = int((lab[sl] == i).sum())
            if px < 6 or px > 900:           # 명백한 먼지·거대 영역만 뺀다
                continue
            out.append((px, (sl[1].start + sl[1].stop) / 2, (sl[0].start + sl[0].stop) / 2))
        return out

    raw_mag, raw_cya = grab(280, 340), grab(165, 205)
    H, W = im.shape[0], im.shape[1]
    info = {"raw": (len(raw_mag), len(raw_cya)), "next_ratio": 0.0, "H": H, "W": W}

    # ★2026-08-25 재설계 — 마크 수를 16으로 하드코딩하지 않는다.
    #
    # 무엇이 깨졌나
    #   실험4(p_v3)에서 칸당 마크를 2개 → 4개로 늘리자 마젠타·시안이 32개씩 나왔고,
    #   문지기가 "16이 아니다 = 구조이상"으로 읽어 **멀쩡한 격자 9장을 3번씩 폐기**했다.
    #
    # 왜 개수로 정하면 안 되나
    #   실측(2026-08-25, 실험4c 3격자)에서 캐릭터마다 마크 배치가 달랐다:
    #     · 흑연·블룸 — 칸마다 독립 4마크(줄 8개)
    #     · 여울      — 인접 칸이 마크를 공유(줄 5개, 4x4 격자의 가로 경계선 개수)
    #   게다가 캐릭터가 마크를 가리면 개수가 준다(블룸 시안 30/32, 여울 마젠타 20).
    #   개수는 흔들리지만 **배치 구조(열 4개)는 안 흔들린다.** 그래서 구조로 본다.
    #
    # 크기 컷도 상위 N이 아니라 '급락 지점'으로 바꿨다 — 잡음은 마크보다 확연히 작다
    #   (여울 마젠타 90→30, 흑연 51→20, 블룸 시안 88→26에서 뚝 떨어진다).
    def cut_by_drop(blobs, lo=8, hi=40, thr=0.6):
        s_ = sorted(blobs, reverse=True)
        sizes = [b[0] for b in s_]
        cut = min(len(sizes), hi)
        for i in range(lo, min(len(sizes), hi)):
            if sizes[i] / sizes[i - 1] < thr:
                cut = i
                break
        return s_[:cut]

    keep_mag, keep_cya = cut_by_drop(raw_mag), cut_by_drop(raw_cya)
    info["kept"] = (len(keep_mag), len(keep_cya))
    if len(raw_mag) > len(keep_mag):
        info["next_ratio"] = sorted(raw_mag, reverse=True)[len(keep_mag)][0] / keep_mag[-1][0]

    mag = [(b[1], b[2]) for b in keep_mag]
    cya = [(b[1], b[2]) for b in keep_cya]
    # 옛 경로(칸당 2마크) 호환 — 줄이 4개면 예전처럼 정확히 16개만 넘긴다.
    #   frame_cut의 절단 로직이 칸당 1쌍을 전제하므로 여기서 형태를 지켜준다.
    if len(_bands(mag, H / 4 * 0.15)) == 4 and len(mag) > 16:
        mag = [(b[1], b[2]) for b in sorted(keep_mag, reverse=True)[:16]]
    if len(_bands(cya, H / 4 * 0.15)) == 4 and len(cya) > 16:
        cya = [(b[1], b[2]) for b in sorted(keep_cya, reverse=True)[:16]]
    return mag, cya, info


def _bands(pts, gap, axis=1, min_frac=0.0):
    """좌표를 gap보다 먼 곳에서 끊어 줄(또는 열)로 묶는다.

    min_frac — 전체 마크 수 대비 이 비율보다 작은 군집은 잡음으로 버린다.
      ★절대 개수로 거르면 안 된다(2026-08-25 실측). 블룸은 분홍 눈이 마젠타로 잡혀
        열 군집이 [8,8,2,8,2,8,3]으로 나왔다. 2개 이하만 버리면 7열로 읽혀 멀쩡한 격자가
        폐기되고, 4개 미만을 버리면 마크가 가려진 열까지 사라진다. 비율(열 15%)이 둘 다 피한다.
    """
    vals = sorted(p[axis] for p in pts)
    if not vals:
        return []
    g = [[vals[0]]]
    for v in vals[1:]:
        (g.append([v]) if v - g[-1][-1] > gap else g[-1].append(v))
    if min_frac <= 0:
        return [b for b in g if len(b) >= 2]
    thr = len(vals) * min_frac
    return [b for b in g if len(b) >= thr]



def check_grid_shape(mag: list, cya: list, info: dict) -> list:
    """격자가 4행 x 4칸인지 검사한다. 어긋난 이유를 문자열 목록으로 돌려준다(빈 목록 = 정상).

    ★균등분할을 쓰지 않는다. 실제 마크 좌표를 군집화해 행·열을 찾으므로 행이 눌려도 맞는다.

    ★2026-08-25 — 개수 검사(16·16)를 **배치 구조 검사**로 바꿨다. 마크 배치가 세대마다 다르기 때문이다:
      · 칸 안쪽 2마크(v9~v12) — 좌 마젠타 4열 + 우 시안 4열 = 합쳐서 **8열**
      · 칸 안쪽 4마크(p_v3~p_v5) — 같은 8열이되 줄이 8개
      · 격자 교차점 25개(p_v6~) — 5x5 격자점이라 합쳐서 **5열 · 5줄**
      두 색을 합쳐서 세는 이유 = 모델이 색 규칙(홀수열 마젠타/짝수열 시안)을 완벽히는 안 지킨다.
      실측(p_v6 첫 판): 마젠타 13 · 시안 12 = 25개는 맞는데 색이 열마다 깔끔히 갈리지는 않았다.
      ⚠️이 검사의 본래 목적은 '마지막 행 5칸' 같은 생성 결함을 잡는 것이다(맹검 54번 사고).
        5칸 격자면 열이 10개(2·4마크) 또는 6개(격자점)가 되어 여기서 걸린다.
    """
    bad = []
    H, W = info.get("H", 1252), info.get("W", 1252)
    pts = list(mag) + list(cya)
    if len(pts) < 12:
        bad.append(f"마크가 너무 적음 (마젠타 {len(mag)}·시안 {len(cya)} — 원본 검출 {info['raw'][0]}·{info['raw'][1]})")
        return bad
    cols = _bands(pts, W / 4 * 0.15, axis=0, min_frac=0.08)
    rows = _bands(pts, H / 4 * 0.15, axis=1, min_frac=0.08)
    if len(cols) not in (5, 8):
        bad.append(f"열이 5개(격자점)도 8개(칸마다 마크)도 아님 ({len(cols)}개: {[len(c) for c in cols]}) "
                   f"— 격자가 4x4가 아닐 수 있음(마지막 행 5칸 등)")
    if len(rows) not in (4, 5, 8):
        bad.append(f"가로 줄이 4·5·8 중 하나가 아님 ({len(rows)}개: {[len(r) for r in rows]})")
    return bad


def find_all_marks(im: np.ndarray, bg: np.ndarray) -> list:
    """격자 전체에서 십자 마크를 찾는다. 셀 경계를 모르므로 전역에서 찾는다.

    ⚠️v8 이하(무채색 마크) 격자를 위한 **구형 경로**다. v9부터는 find_marks_by_color가 먼저 시도된다.

    ★2026-08-09 크기·픽셀수만 보던 기준에 **모양과 위치**를 더했다.
      그 전에는 바닥선 부근 얼룩을 마크로 셌다(흑연 콩콩 격자: 검출 95개 중 진짜는 32개).
      오검출 마크가 칸에 할당되면 프레임 좌표가 격자 밖을 가리키고, 그 자리를 PIL이
      검은색으로 채워 **프레임 하나가 통째로 검게 뭉개진다**(여울 삐지기·흑연 콩콩·흑연 두리번).
      실측: 이 두 조건을 더하니 배치 133격자 중 115개가 정확히 32개, **오검출 0**.
        · 모양 — 십자다(중앙 행·열에 픽셀 55% 이상, 네 모서리 12% 이하)
        · 위치 — 마크는 칸 위쪽에 그려진다(칸 높이의 위 25% 안)
      ⚠️근본 해법은 격자 생성 프롬프트에서 마크 색을 못 박는 것이다(상훈님 안). 그때까진 이 필터.
    """
    d = np.abs(im - bg).sum(axis=2)
    lab, _ = ndimage.label(d > LO)
    cell_h = im.shape[0] / 4.0
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        h, w = sl[0].stop - sl[0].start, sl[1].stop - sl[1].start
        if not (MARK_MIN <= h <= MARK_MAX and MARK_MIN <= w <= MARK_MAX):
            continue
        px = int((lab[sl] == i).sum())
        if not (MARK_PX[0] <= px <= MARK_PX[1]):
            continue
        cy = (sl[0].start + sl[0].stop) / 2
        if (cy % cell_h) > cell_h * 0.25:      # 칸 위쪽이 아니면 마크가 아니다
            continue
        if not _is_cross(lab[sl] == i):        # 십자가 아니면 마크가 아니다
            continue
        out.append(((sl[1].start + sl[1].stop) / 2, cy))
    return out


def find_ground(d: np.ndarray, x0: float, x1: float, y_from: int, y_to: int) -> float:
    """바닥선의 y를 찾는다 — 좌우 가장자리에서 '약하게 어두운 띠'가 가로로 이어지는 줄.

    ★세로 기준은 마크가 아니라 땅이어야 한다(2026-08-06 상훈님):
      "칸 안에 땅의 위치가 올라갔다 내려갔다 해서 앵커의 상하 움직임이 너무 커서 보기가 어렵네."
    마크와 땅은 서로 독립적으로 그려져 거리가 칸마다 다르다(옛 격자 28px·새 격자 12px 편차).
    마크에 맞추면 땅이 흔들리고, 사람 눈에는 땅이 세로 앵커다.
    캐릭터를 피하려고 좌우 가장자리만 본다(캐릭터는 가운데 있다).

    ★2026-08-16 — **실패를 숨기지 않게 고쳤다.**
      옛 코드는 `best` 초기값이 −1이라 픽셀이 하나도 없어도(n=0) 첫 y를 그대로 반환했다.
      그러면 못 찾았다는 사실이 사라지고 **탐색 범위의 첫 y가 바닥선 행세**를 한다.
      실측: 소닉 만세 r1의 4행은 바닥선이 격자 밖으로 잘려 나갔는데(마크y 1004 + 278 = 1282 > 높이 1254)
      마크+98을 땅으로 반환했고, 그 결과 프레임이 세로로 튀어 상훈님이 "대실패"로 판정하셨다.
      → 못 찾으면 **NaN**을 돌려 호출부의 마크 기준 폴백이 작동하게 한다.
    ⚠️검출 방법 자체는 바꾸지 않는다. 칸 전체 폭을 보는 방법으로 갈아치우려다 반증됐다 —
      그 방법은 261판 중 22판에서 바닥선을 아예 못 찾는데, 그중 원래 방법의 편차가
      0.2~0.5px로 완벽한 판이 있었다. **바닥선이 없어도 칸마다 같은 것을 잡으면 정렬은 유지된다.**
      문제는 검출 대상이 아니라 **칸마다 다른 것을 잡는 것**이다.
    """
    xl, xr = int(x0), int(x1)
    side_w = max(8, int((xr - xl) * 0.15))
    band = np.concatenate([d[:, xl:xl + side_w], d[:, xr - side_w:xr]], axis=1)
    need = band.shape[1] * 0.5      # 띠 폭의 절반은 이어져야 바닥선으로 인정
    best, best_y = need, float("nan")
    for y in range(max(0, y_from), min(d.shape[0], y_to)):
        n = int(((band[y] > 5) & (band[y] < 70)).sum())
        if n > best:
            best, best_y = n, y
    return best_y



def _pair_by_color(mag, cyan, n_rows: int = 4):
    """★색 모드 전용 짝짓기 (2026-08-21 신설).

    왜 필요한가
      기존 경로는 마크를 `x // (W/4)` 로 **균등분할 칸에 배정**한 뒤 칸 안에서 최좌·최우를 골랐다.
      그 방식은 2026-08-09 사고(마크가 더 검출되면 짝이 통째로 밀림)를 막으려고 도입됐는데,
      **칸이 균등하다는 것을 전제**한다.

      흑연 01_손흔들기(API high)에서 그 전제가 깨졌다. 마젠타가 x=34·322·622·924에 있는데
      균등분할 경계는 312·624·936이다. 622는 경계보다 **2px**, 924는 12px 왼쪽이라
      3칸 좌마크가 2칸으로, 4칸 좌마크가 3칸으로 밀렸다.
      → 2칸=[322,530,622]에서 (322,622) 간격 300px, 3칸=[852,924]에서 간격 72px,
        4칸은 마크 1개뿐이라 균등분할. 배율 0.694·2.883이 찍혀 폐기 판정이 났다.
      ★격자는 멀쩡했다. 도구가 2픽셀 때문에 무너진 것이다.

    왜 이제 안전한가
      색 검출이 **좌=마젠타·우=시안을 확정**해 준다(find_marks_by_color 참조).
      좌우를 색으로 아는 이상 칸 배정도, 개수 전제도 필요 없다 —
      행 안에서 마젠타 i번째와 시안 i번째를 짝지으면 끝이다.
      2026-08-09 사고는 좌우를 구분 못 하던 무채색 시절 이야기라 여기 해당하지 않는다.
      (색 검출이 실패하면 color_mode 자체가 꺼지고 구형 경로로 간다.)
    """
    def rows_of(pts):
        pts = sorted(pts, key=lambda p: p[1])
        gaps = sorted(((pts[i + 1][1] - pts[i][1], i) for i in range(len(pts) - 1)),
                      reverse=True)[:n_rows - 1]
        cuts = sorted(i for _, i in gaps)
        out, prev = [], 0
        for c in cuts + [len(pts) - 1]:
            out.append(sorted(pts[prev:c + 1], key=lambda p: p[0]))
            prev = c + 1
        return out
    rm, rc = rows_of(mag), rows_of(cyan)
    if len(rm) != n_rows or len(rc) != n_rows:
        return None
    pairs = []
    for a, b in zip(rm, rc):
        if len(a) != len(b):
            return None          # 행마다 좌우 개수가 다르면 손대지 않는다
        pairs.extend(zip(a, b))
    return pairs if len(pairs) == n_rows * 4 else None


def group_rows(marks: list, n_rows: int = 4) -> list:
    """y로 묶어 행을 만든다. 행 안에서는 x로 정렬해 좌·우 쌍을 만든다."""
    marks = sorted(marks, key=lambda p: p[1])
    if len(marks) < n_rows * 2:
        return []
    # y 간격이 크게 벌어지는 지점에서 행을 끊는다
    gaps = [(marks[i + 1][1] - marks[i][1], i) for i in range(len(marks) - 1)]
    cuts = sorted(i for _, i in sorted(gaps, reverse=True)[:n_rows - 1])
    rows, prev = [], 0
    for c in cuts + [len(marks) - 1]:
        rows.append(sorted(marks[prev:c + 1], key=lambda p: p[0]))
        prev = c + 1
    return rows


TOP_BLOB_THR = 28   # 실루엣으로 볼 배경과의 색거리 (metrics.py THR과 같은 기준)
GROUND_KEEP = 14    # 바닥선 아래 이만큼은 청소하지 않는다(바닥선 띠·발밑 그림자 보호)


def clean_margin_bands(arr, bg, top_y, bot_y):
    """위·아래 **여백 구간**에서 캐릭터 본체와 이어지지 않은 조각을 배경으로 덮는다.

    왜 (2026-08-14 상훈님 판정)
    -------------------------
    여백을 20→40으로 키우니 잘림이 거의 사라졌고 상훈님 판정은
    *"진짜 훨씬 훨씬 낫다. 발, 머리 모든게 다 살아났어."* 였다.
    남은 결함 두 건도 상훈님이 정확히 짚으셨다 —

      · 블룸 애교 v04 : *"후반부 프레임에 머리 위에 발인가 땅인가가 조금 보이네"*  (위 칸 침범)
      · 여울 콩콩뛰기 v10 : *"발 아래에 안테나가 살짝 보이고"*                    (아래 칸 침범)

    둘 다 **옆칸에서 딸려온 조각**이고 캐릭터 본체와 떨어져 있다.
    여백은 눈먼 가로선이라 이걸 못 가르지만, 연결 여부는 가른다.

    프레임 안(top_y ~ bot_y)은 건드리지 않는다 — 바닥선·그림자처럼 본체와 떨어져 있어도
    정당한 요소가 그 안에 있기 때문이다. 오직 위아래 여백만 청소한다.
    """
    d = np.abs(arr - bg).sum(axis=2)
    mask = d > TOP_BLOB_THR
    if not mask.any():
        return arr
    # ⚠️침식하지 않는다 (2026-08-14 실측). 옆칸과 1~2px 닿은 다리를 끊으려고 침식 1을 걸었더니
    #   **캐릭터 자신이 18조각으로 부서졌다** — SD 캐릭터는 발목·목·손목이 가늘다.
    #   실측 프레임당 덩어리 수: 침식0=3.0 / 침식1=18.1 / 침식2=9.8 (블룸 삐지기 v03).
    #   그 상태로 '가장 큰 덩어리만 남기기'를 하니 나머지 17조각(신발 포함)이 통째로 지워졌다.
    lab, n = ndimage.label(mask)
    if n == 0:
        return arr
    inside = lab[max(0, top_y):max(1, bot_y)]           # 프레임 안 = 캐릭터가 사는 구역
    ids, cnt = np.unique(inside[inside > 0], return_counts=True)
    if len(ids) == 0:
        return arr
    main = int(ids[np.argmax(cnt)])
    keep = ndimage.binary_dilation(lab == main, iterations=2) & mask
    band = np.zeros(mask.shape, bool)
    band[:max(0, top_y)] = True                         # 위 여백
    band[max(0, bot_y):] = True                         # 아래 여백
    arr[band & ~keep] = bg
    return arr


def keep_connected_above(arr, bg, top_cut, reach):
    """(구형·미사용) 마커 위 영역에서 캐릭터 본체와 이어진 것만 남긴다.

    ⚠️2026-08-14 실측 결과 이 자리는 거의 발동하지 않는다 — `top_cut = 마커y − 여백`인데
      마커가 구조상 여백 위치에 놓이도록 계산되어 top_cut이 항상 0 근처다.
      리서치가 지목한 "후처리가 머리를 지운다"는 이 격자들에선 사실이 아니었다.
      진짜 원인은 **출력 캔버스가 짧아 머리가 캔버스 밖에 있던 것**이고,
      처방은 여백 확대(+ 위 clean_margin_bands)였다. 기록용으로 남긴다.

    왜 필요한가 (2026-08-14 실측)
    ---------------------------
    기존 방식은 `arr[:top_cut] = bg` 로 마커 위를 통째로 덮어, 위로 솟은 머리
    (흑연의 상투·안테나)까지 같이 지웠다. 흑연 8판을 넓은 여백으로 재절단해
    되살아난 245,549px을 연결요소로 갈라 보니 —

        내 캐릭터 머리 90,537px (36.9%)  /  위 칸에서 딸려온 침범 155,012px (63.1%)

    판마다 편차가 크다(0%~100%). 손흔들기 v04는 되살아난 것의 60.6%(45,386px)가
    우리 머리였다.

    ★이것이 2026-08-11 여백 스윕이 "잘림↔침범 맞바꿈"으로 끝난 이유를 설명한다.
      여백은 눈먼 가로선이라 내 머리와 남의 발을 구분하지 못한다. 연결 여부는 구분한다.

    안전장치
    -------
    - 1px 침식 후 라벨링 → 위 칸 그림자와 1~2px 닿아 한 덩어리가 되는 것을 끊는다
      (실측된 행 간 최소 틈이 2px인 판이 존재한다)
    - `reach` 상한 — 본체라도 마커보다 이만큼 위로는 못 올라간다
    """
    d = np.abs(arr - bg).sum(axis=2)
    mask = d > TOP_BLOB_THR
    core = ndimage.binary_erosion(mask, iterations=1)
    lab, n = ndimage.label(core)
    if n == 0:
        arr[:top_cut, :] = bg
        return arr
    below = lab[top_cut:]
    ids, cnt = np.unique(below[below > 0], return_counts=True)
    if len(ids) == 0:                       # 아래에 본체가 없다 = 기존 방식으로 폴백
        arr[:top_cut, :] = bg
        return arr
    main = int(ids[np.argmax(cnt)])
    keep = ndimage.binary_dilation(lab == main, iterations=2) & mask
    keep[:max(0, top_cut - reach)] = False   # 확장 상한
    band = np.zeros(mask.shape, bool)
    band[:top_cut] = True
    arr[band & ~keep] = bg
    return arr


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("grid")
    ap.add_argument("outdir")
    ap.add_argument("--margin", type=int, default=40,
                    help="프레임 바깥으로 남길 여백. ★상하좌우 모두 같은 값이다. "
                         "★2026-08-14 기본값 20→40 (상훈님 판정: '진짜 훨씬 훨씬 낫다. "
                         "발, 머리 모든게 다 살아났어'). 기존 137편은 재절단하지 않는다 — "
                         "앞으로 만드는 것에만 적용")
    ap.add_argument("--clean-bands", action="store_true",
                    help="위아래 여백에 들어온 옆칸 조각을 걷어낸다(2026-08-14 신설). "
                         "여백을 키우면 침범이 같이 들어오는 문제의 처방")
    ap.add_argument("--pad", type=float, default=0.09,
                    help="마크를 출력 위에서 이 비율만큼 아래에 놓는다(아래 여백 확보용)")
    ap.add_argument("--allow-broken", action="store_true",
                    help="격자 구조 검사(4행x4칸)에 실패해도 강행한다. ★2026-08-15 신설 — 기본은 중단이다. "
                         "마지막 행이 5칸으로 그려진 격자를 그대로 잘라 얼굴 조각·빈 칸이 섞인 결과물이 "
                         "판정까지 올라간 사고(맹검 54번, 상훈님 '절대로 나와선 안되는 실패작')를 막는다")
    ap.add_argument("--no-fingerprint", action="store_true",
                    help="절단 지문(.cut.json)을 남기지 않는다. ★재현 확인처럼 "
                         "'기존 기록을 읽어 검증만' 할 때 쓴다 — 안 쓰면 검증이 원본 기록을 덮어쓴다")

    args = ap.parse_args()

    # ★재현을 위한 지문(2026-08-09). 이 격자를 **어떤 설정·어떤 코드로** 잘랐는지 격자 옆에 남긴다.
    #   예전에는 여백 값이 save_version의 '변경의도' 자유 텍스트에만 있어서, 재현하려면
    #   사람이 메모를 읽고 손으로 넣어야 했다(실제로 최적 3편이 그래서 재현에 실패했다가
    #   기록된 여백 10·32를 손으로 넣으니 그제야 맞았다). 기계가 읽을 수 있는 자리에 둔다.
    import hashlib, json as _json
    _here = Path(__file__).resolve().parent
    _code = hashlib.sha256(b"".join(
        (_here / f).read_bytes() for f in ("frame_cut.py", "blob.py") if (_here / f).exists()
    )).hexdigest()[:12]
    if not args.no_fingerprint:
        Path(str(args.grid) + ".cut.json").write_text(_json.dumps(
            {"margin": args.margin, "pad": args.pad, "code": _code}, ensure_ascii=False), encoding="utf-8")

    im = np.array(Image.open(args.grid).convert("RGB")).astype(float)
    bg = np.median(im.reshape(-1, 3), axis=0)
    pil = Image.open(args.grid).convert("RGB")

    # ★색 마크(v9~)를 먼저 시도한다. 좌=마젠타·우=시안이 각 16개면 그것이 정답이므로
    #   모양·위치 추측(구형 경로)도, 페어링도, 보간도 필요 없다.
    _mag, _cyan, _info = find_marks_by_color(im)
    _bad = check_grid_shape(_mag, _cyan, _info)
    color_mode = not _bad
    if color_mode:
        marks = _mag + _cyan
        print("마크: 색 기반 검출 (마젠타 16 · 시안 16 · 4행x4칸 확인) — 좌우가 색으로 확정됩니다")
        _COLOR_PAIRS = _pair_by_color(_mag, _cyan)
    else:
        print("⚠️★격자 구조 검사 실패:")
        for b in _bad:
            print(f"     · {b}")
        if not args.allow_broken:
            raise SystemExit(
                "★절단 중단 — 이 격자는 재생성해야 합니다.\n"
                "  깨진 격자를 잘라 판정까지 올리면 동작이 아니라 후처리를 판정하게 됩니다"
                "(2026-08-15 맹검 54번 사고).\n"
                "  정말 강행하려면 --allow-broken 을 주십시오.")
        print("     --allow-broken 지정 → 구형(모양·위치) 검출로 진행합니다")
        marks = find_all_marks(im, bg)
    rows = group_rows(marks)
    _cp = _COLOR_PAIRS if color_mode else None
    # 칸별 (좌마크, 우마크) — ★마크를 **칸 위치로 할당**한 뒤 칸 안에서 최좌·최우를 고른다.
    #   2026-08-09 사고: 예전에는 "한 행에 정확히 8개(4칸×2)"를 전제하고 x 정렬 순서대로
    #   2개씩 묶었다. 마크가 하나라도 더 검출되면 **짝이 통째로 밀려서** 칸1의 우마크와
    #   칸2의 좌마크가 한 쌍이 된다. 그러면 마크 간격이 절반(118px vs 정상 220~240px)으로
    #   잡히고, 그 잘못된 프레임으로 오려내니 빈 칸과 얼굴 확대가 섞여 나왔다.
    #   (블룸 신판 시트에서 마크가 40개 검출되며 실제로 발생. 원본 격자는 멀쩡했다.)
    #   → 마크 개수에 의존하지 않는다. 칸 폭으로 소속을 정하면 여분 마크가 있어도 안전하다.
    cell_w = im.shape[1] / 4.0
    pairs = []
    for r in rows:
        by_cell = {}
        for m in r:
            c = min(3, max(0, int(m[0] // cell_w)))
            by_cell.setdefault(c, []).append(m)
        for c in range(4):
            got = sorted(by_cell.get(c, []), key=lambda p: p[0])
            pairs.append((got[0], got[-1]) if len(got) >= 2 else None)

    if _cp:
        pairs = list(_cp)
        print("짝짓기: 색 기준(마젠타↔시안) — 균등분할 칸 배정을 쓰지 않습니다")
    good = [p for p in pairs if p]
    if not good:
        raise SystemExit("마크를 찾지 못했습니다 — 프레임 절단 불가")

    # ★마크를 못 찾은 칸은 **이웃에서 보간**한다 (2026-08-09 신설).
    #   예전에는 균등분할로 대체했는데, 그건 이 하네스가 8/6에 이미 폐기한 가정이다
    #   (실측: 모델이 그리는 격자는 균등분할이 아니라 줄 23px·열 14px 어긋난다).
    #   그래서 폴백이 걸린 칸만 캐릭터가 작아지고 위치가 틀어졌고, 마크를 못 찾았으니
    #   **지우지도 못해 십자가 그대로 남았다**(2026-08-09 블룸 손흔들기 판3에서 상훈님 지적:
    #   "한두세 프레임 앵커가 엄청 흔들리고 마크가 안 없어진 채로 올라왔다" — f02·f09·f11).
    #   격자는 규칙적이므로 **행의 y + 열의 x**로 빠진 마크를 충분히 추정할 수 있다.
    def interpolate(pairs):
        rows_y, col_lx, col_rx, spans = {}, {}, {}, []
        for i, p in enumerate(pairs):
            if not p:
                continue
            r, c = divmod(i, 4)
            (lx, ly), (rx, ry) = p
            rows_y.setdefault(r, []).extend([ly, ry])
            col_lx.setdefault(c, []).append(lx)
            col_rx.setdefault(c, []).append(rx)
            spans.append(rx - lx)
        if not spans:
            return pairs, 0
        span_med = float(np.median(spans))
        # 행 y·열 x가 아예 없는 경우엔 전체 중앙값으로 (그래도 균등분할보다 낫다)
        all_y = [v for vs in rows_y.values() for v in vs]
        n_fix = 0
        for i, p in enumerate(pairs):
            if p:
                continue
            r, c = divmod(i, 4)
            ys = rows_y.get(r) or all_y
            y = float(np.median(ys))
            lxs, rxs = col_lx.get(c), col_rx.get(c)
            if lxs and rxs:
                lx, rx = float(np.median(lxs)), float(np.median(rxs))
            elif lxs:
                lx = float(np.median(lxs)); rx = lx + span_med
            elif rxs:
                rx = float(np.median(rxs)); lx = rx - span_med
            else:
                continue                      # 그 열에 단서가 하나도 없으면 포기
            pairs[i] = ((lx, y), (rx, y))
            n_fix += 1
        return pairs, n_fix

    pairs, n_fix = interpolate(pairs)
    if n_fix:
        print(f"⚠️마크를 못 찾은 {n_fix}칸을 이웃 행·열에서 보간했습니다(균등분할 폴백 대신).")
    good = [p for p in pairs if p]
    span_ref = float(np.median([p[1][0] - p[0][0] for p in good]))  # 기준 마크 간 거리

    # ★확대하지 않는다. 프레임을 출력 폭에 꽉 맞추면 배율이 1.15배가 되어 세로가 모자라고
    #   캐릭터 발이 잘린다(첫 시도에서 그랬다). 기준 마크간격을 '원래 크기'로 보고,
    #   칸마다 다른 크기(238.5~246.5px, 3.3% 편차)만 그 값으로 정규화한다.
    dmap = np.abs(im - bg).sum(axis=2)
    # 마크~땅 거리의 대표값. 출력에서 땅을 어디에 놓을지 정하는 데 쓴다.
    gaps = []
    for p in good:
        (lx, ly), (rx, _ry) = p
        g = find_ground(dmap, lx, rx, int(ly + (rx - lx) * 0.5), int(ly + (rx - lx) * 2.0))
        if np.isfinite(g):
            gaps.append(g - ly)
    ground_ref = float(np.median(gaps)) if gaps else span_ref * 0.8

    # ★출력 크기를 프레임에서 정한다(2026-08-06 상훈님: "프레임 상하좌우의 크기를 같게").
    #   예전엔 313/314가 섞여 하단·우측이 커졌다 작아졌다 했고, 여백도 위 26·아래 6·좌우 10으로
    #   제각각이었다. 이제 프레임(마크폭 × 마크~땅) 바깥으로 사방 같은 여백만 남긴다.
    m = args.margin
    OUT_W = int(round(span_ref)) + 2 * m
    OUT_H = int(round(ground_ref)) + 2 * m
    x_left = float(m)                  # 좌마크가 놓일 x
    y_top = float(m)                   # 마크가 놓일 y
    y_ground = float(m) + ground_ref   # 바닥선이 놓일 y
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    print(f"마크 {len(marks)}개 검출, 쌍 {len(good)}/16   기준 마크간격 {span_ref:.1f}px")
    print(f"기준 마크~땅 {ground_ref:.0f}px · 출력에서 땅 y={y_ground:.0f}")
    # ★칸별 마크~땅을 먼저 다 재고 중앙값에서 크게 벗어난 칸을 바로잡는다(2026-08-16).
    #   find_ground는 두 가지로 실패한다 — (a) 못 찾음 → NaN → 마크 폴백(위에서 처리)
    #   (b) **엉뚱한 것을 찾음** → 픽셀 수는 충분해서 NaN이 안 나온다. 이건 값으로는 못 가리고
    #   **칸끼리 비교해야만** 드러난다("문제는 검출 대상이 아니라 칸마다 다른 것을 잡는 것").
    #   실측: 편차 20px 초과 7판이 예외 없이 전부 실패 판정이었다(합격 0/7).
    _gaps = []
    for _p in pairs:
        if _p is None:
            _gaps.append(np.nan); continue
        (_lx, _ly), (_rx, _ry) = _p
        _sp = _rx - _lx
        _g = find_ground(dmap, _lx, _rx, int(_ly + _sp * 0.5), int(_ly + _sp * 2.0))
        _gaps.append(_g - _ly if np.isfinite(_g) else np.nan)
    _fin = [x for x in _gaps if np.isfinite(x)]
    _med = float(np.median(_fin)) if _fin else float("nan")
    ground_fix = []                    # 이상치로 판정돼 중앙값으로 대체된 칸
    if np.isfinite(_med):
        for _i, _x in enumerate(_gaps):
            if np.isfinite(_x) and abs(_x - _med) > 20:
                ground_fix.append((_i + 1, _x, _med))

    scale_log = []                     # 배율 게이트용
    print(f"{'칸':<6}{'마크간격':>9}{'배율':>7}{'마크~땅':>8}")
    for i, p in enumerate(pairs, 1):
        if p is None:                  # 마크를 못 찾은 칸은 균등분할로 대체
            ch = im.shape[0] / 4
            r, c = divmod(i - 1, 4)
            box = (int(c * ch), int(r * ch), int(c * ch) + OUT_W, int(r * ch) + OUT_H)
            pil.crop(box).resize((OUT_W, OUT_H), Image.LANCZOS).save(outdir / f"f{i:02d}.png")
            print(f"f{i:02d}   {'-':>9}{'-':>7}{'-':>8}  ★마크 없음 → 균등분할")
            continue
        (lx, ly), (rx, ry) = p
        span = rx - lx
        s = span / span_ref            # 이 칸이 기준보다 크게 그려졌으면 >1 → 축소된다
        tilt = ry - ly
        # ★세로 기준 = 바닥선. 마크 아래 ~ 마크+2.0×간격 범위에서 찾는다.
        gy = find_ground(dmap, lx, rx, int(ly + span * 0.5), int(ly + span * 2.0))
        if np.isfinite(gy) and np.isfinite(_med) and abs((gy - ly) - _med) > 20:
            gy = ly + _med             # 이 칸만 엉뚱한 것을 잡았다 — 나머지 칸의 합의로 되돌린다
        anchor_y = gy if np.isfinite(gy) else ly + ground_ref  # 못 찾으면 마크 기준으로 대체
        # 출력 (X,Y) → 원본 (x,y) 아핀.
        # ★가로 기준은 좌마크가 아니라 **좌우 마크의 중점**이다(2026-08-06).
        #   좌마크 하나만 쓰면 그 마크의 검출 오차가 그대로 위치 오차가 되어 캐릭터가 좌우로 흔들린다
        #   (상훈님이 9개 동작에서 "앵커가 왔다갔다" 지적). 중점을 쓰면 좌우 오차가 서로 상쇄된다.
        #   실측 근거: 원본에서 '마크 중점 대비' 캐릭터 중심 흔들림은 σ 0.1~0.4px로 거의 완벽한데,
        #   좌마크 기준으로 자른 결과물은 σ 1.1~21.4px였다 — 흔들림은 생성이 아니라 후처리가 만들었다.
        a, e = s, s
        mid_src = (lx + rx) / 2
        mid_out = x_left + span_ref / 2
        cx = mid_src - mid_out * s
        cy = anchor_y - y_ground * s   # 바닥선이 출력의 고정 높이에 오도록
        # ★★fillcolor를 배경색으로 준다 (2026-08-11, 상훈님이 4번 지적한 "gif 하단이 왔다갔다"의 진범).
        #   프레임이 격자 이미지 밖을 참조하면 PIL은 기본값 **검정(0,0,0)**으로 채운다.
        #   마지막 행(f13~16)은 격자 아래가 없으므로 하단에 검은 띠가 생기고, 그 높이가 칸마다 달라
        #   상훈님 눈에 "하단 길이가 왔다갔다"로 보인다(여울 박수 v13 실측: f1~12 배경색 249,245,239 /
        #   f13~16 하단 5줄이 전부 0,0,0).
        #   ⚠️2026-08-10에 이 현상을 "원본 특성"이라 반증했는데 **그 반증이 틀렸다.**
        #     재절단으로 대조했으니 같은 코드가 같은 검은 띠를 다시 만들어 값이 같게 나왔을 뿐이다.
        #     코드가 만드는 결함은 그 코드로 재현해서는 절대 검출되지 않는다.
        cut = pil.transform((OUT_W, OUT_H), Image.AFFINE, (a, 0, cx, 0, e, cy),
                            resample=Image.BICUBIC,
                            fillcolor=tuple(int(round(v)) for v in bg))
        # ★마크를 지운다. 지우지 않으면 침범 검사가 마크를 '이탈 조각'으로 잡는다(실측 38건).
        #   출력에서 마크가 어디 오는지 정확히 알므로 그 자리만 덮는다(모서리 전체를 덮지 않는다).
        arr = np.array(cut).astype(float)
        # ★프레임 밖은 배경으로 덮는다(2026-08-06 상훈님):
        #   "바닥 아래로 안테나가 보이거나 gif 하단의 길이가 왔다갔다 하는 것 같아."
        #   땅 아래를 원본에서 그대로 가져오면 아랫칸 캐릭터의 안테나가 딸려오고,
        #   칸마다 그 양이 달라 하단 길이가 들쭉날쭉해진다. 프레임 밖은 그림이 아니다.
        arr[int(round(y_ground)) + m:, :] = bg
        # ★좌우도 덮는다(2026-08-06 상훈님: "왼쪽에 검은 게 왔다갔다 하는 것 같은데").
        #   출력 왼쪽 여백을 원본에서 가져오면, 마크가 셀 안쪽에 있는 만큼 이전 칸이 끌려온다
        #   (마크가 셀 왼쪽에서 28px 지점 → 출력 x_left=45px 중 17px가 옆칸 = 검은 크로스백).
        lo = int(round(x_left)) - m
        hi = int(round(x_left + span_ref)) + m
        if lo > 0:
            arr[:, :lo] = bg
        if hi < arr.shape[1]:
            arr[:, hi:] = bg
        rad = int(MARK_MAX * 0.9 / s) + 4
        # 세로 기준이 바닥선이므로 마크의 출력 y는 칸마다 다르다 — 계산해서 지운다
        mark_y_out = y_ground - (anchor_y - ly) / s
        for mx in (x_left, x_left + span_ref):
            X, Y = int(round(mx)), int(round(mark_y_out))
            arr[max(0, Y - rad):Y + rad, max(0, X - rad):X + rad] = bg
        # ⚠️2026-08-09 여기서 '검출된 모든 마크를 지우는' 코드를 넣었다가 **되돌렸다.**
        #   find_all_marks가 바닥선 얼룩을 마크로 오검출한다(흑연 콩콩 격자: 검출 95개 중
        #   진짜 마크는 32개, 나머지 63개는 바닥선 부근 얼룩). 그걸 전부 덮으니 결과가 뭉개졌다.
        #   ★교훈: 마크 자리 픽셀값만 보고 47판에 일괄 적용했다. 전체 그림을 봤어야 했다.
        #   마크 잔존을 고치려면 먼저 **검출기가 진짜 마크만 잡도록** 만들어야 한다.
        top_cut = int(round(mark_y_out)) - m
        if top_cut > 0:
            arr[:top_cut, :] = bg
        # ★여백 청소 — 위아래 여백에 들어온 옆칸 조각을 걷어낸다 (2026-08-14)
        if args.clean_bands:
            # 위 기준은 **탑레일(마커선)** 이다. 마커선 위로 솟은 머리는 본체와 이어져 있어
            # 살아남고, 위 칸에서 떨어져 들어온 조각만 걷힌다.
            # ⚠️`마커y − 여백`으로 잡으면 밴드가 비어(마커가 구조상 여백 위치) 아무 일도 안 한다.
            # 아래 기준을 바닥선보다 GROUND_KEEP 만큼 내린다 — 바닥선 띠와 발밑 그림자를 보호한다.
            # ⚠️+1로 잡았더니 바닥선 아래 절반이 청소 구간에 들어가 상훈님이
            #   "땅 색깔이 바뀌는 게 좀 별로다"라고 하셨다. 바닥선 두께를 프롬프트에서
            #   셀 높이의 2%로 못 박았으므로(2026-08-14) 그만큼 비워 두면 된다.
            arr = clean_margin_bands(arr, bg,
                                     int(round(mark_y_out)),
                                     int(round(y_ground)) + GROUND_KEEP)
        cut = Image.fromarray(arr.astype(np.uint8))
        cut.save(outdir / f"f{i:02d}.png")
        scale_log.append((i, 1 / s))
        print(f"f{i:02d}   {span:>9.1f}{1/s:>7.3f}{anchor_y-ly:>8.0f}")

    print(f"\n출력 {OUT_W}x{OUT_H} (프레임 {span_ref:.0f}x{ground_ref:.0f} + 사방 여백 {m}px)")
    print("배율이 1에서 멀수록 그 칸이 원본에서 크게/작게 그려졌다는 뜻입니다.")

    if ground_fix:
        print(f"\n⚠️바닥선을 칸마다 다르게 잡아 {len(ground_fix)}칸을 중앙값({_med:.0f}px)으로 되돌렸습니다: "
              + " · ".join(f"f{i:02d}({x:.0f})" for i, x, _ in ground_fix))
        print("   (그대로 뒀으면 그 칸만 세로로 튀어 '대실패'로 보였을 자리입니다)")

    # ★배율 게이트(2026-08-15). 칸을 잘못 잡으면 배율이 크게 튄다 — 맹검 54번은 3.105였다.
    #   구조 검사를 통과했더라도 여기서 한 번 더 막는다. 지표가 둘이어야 하나가 눈멀어도 잡힌다.
    off = [(i, sc) for i, sc in scale_log if not (0.90 <= sc <= 1.10)]
    if off:
        print(f"\n⚠️★배율이 1±0.1을 벗어난 칸 {len(off)}개: "
              + " · ".join(f"f{i:02d}={sc:.3f}" for i, sc in off))
        if not args.allow_broken:
            raise SystemExit(
                "★절단 결과를 폐기합니다 — 칸을 잘못 잡았을 때 나오는 값입니다.\n"
                "  격자를 재생성하거나, 원인을 확인한 뒤 --allow-broken 으로 강행하십시오.")


if __name__ == "__main__":
    main()
