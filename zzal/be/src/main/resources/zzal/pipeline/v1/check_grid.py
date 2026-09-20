# -*- coding: utf-8 -*-
"""격자가 4행 x 4칸인지 생성 직후에 검사한다.

왜 여기서 막나
--------------
2026-08-15 맹검 54번 — 모델이 마지막 행을 5칸으로 그린 격자가 그대로 절단돼
얼굴 조각·빈 칸이 섞인 GIF가 검수까지 올라갔다. 절대로 나와서는 안 되는 결과물이다.

★2026-08-25 전면 개편 — 두 가지가 바뀌었다.

 1. **마크 사양을 프롬프트에서 읽는다.**
    그 전에는 마크 개수가 이 파일에 박혀 있어서, 프롬프트에서 마크를 바꿀 때마다
    게이트가 옛 형식만 알고 **멀쩡한 격자를 버렸다**. 하루에 세 번 그랬다:
      · 칸당 2마크 → 4마크(p_v3)   : 3캐릭터 전부 3회씩 오폐기
      · 4마크 → 격자점 25개(p_v6)  : 또 오폐기
    이제 프롬프트 첫머리의 `# GRID_SPEC: marks=.. layout=..` 을 읽어 그 사양으로 검사한다.
    프롬프트를 고치면 그 줄도 고쳐야 하고, 안 고치면 바로 그 판에서 어긋남이 드러난다.

 2. **모르면 통과시킨다.** 사양 줄이 없거나 처음 보는 layout이면 경고만 하고 통과(코드 4).
    "모르면 버린다"가 오늘 손해의 원인이었다. 버리는 쪽이 훨씬 비싸다(생성 2~10분 vs 검사 1초).

종료코드: 0=정상 / 3=구조 이상 / 4=사양 모름(통과·경고) / 1=읽기 실패
사용: check_grid.py <격자PNG> [프롬프트파일]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from frame_cut import find_marks_by_color, _bands  # noqa: E402

# layout 이름 → (두 색을 합친 열 군집 수, 허용하는 줄 군집 수)
LAYOUTS = {
    "per_cell_2":  (8, (4,)),      # 칸마다 좌 마젠타·우 시안 하나씩 (v9~v12)
    "per_cell_4":  (8, (8, 5, 4)), # 칸마다 네 귀퉁이 (p_v3~p_v5). 캐릭터가 위 마크를 가리면 줄이 준다
    "lattice_5x5": (5, (5,)),      # 격자 교차점 25개 (p_v6~) — 4x4 칸
    "lattice_3x3": (3, (3,)),      # 격자 교차점 9개 (공통에셋 커튼) — 2열x2행 = 4칸
    "lattice_4x3": (4, (3,)),      # 격자 교차점 12개 (공통에셋 쓰레기) — 3열x2행 = 6칸
    "lattice_4x4": (4, (4,)),      # 격자 교차점 16개 (공통에셋 알) — 3열x3행 = 9칸
    "lattice_5x4": (5, (4,)),      # 격자 교차점 20개 (공통에셋 오버레이) — 4열x3행 = 12칸
    "lattice_7x4": (7, (4,)),      # 격자 교차점 28개 (p_s4~) — 6열x3행 = 18칸(9종x2프레임)
}


def read_spec(prompt_path):
    """프롬프트 첫머리의 GRID_SPEC 한 줄을 읽는다. 없으면 None."""
    try:
        head = Path(prompt_path).read_text(encoding="utf-8")[:2000]
    except OSError:
        return None
    m = re.search(r"^#\s*GRID_SPEC:\s*(.+)$", head, re.M)
    if not m:
        return None
    out = {}
    for kv in m.group(1).split():
        if "=" in kv:
            k, v = kv.split("=", 1)
            out[k] = v
    return out or None


def main() -> int:
    if len(sys.argv) < 2:
        print("사용: check_grid.py <격자PNG> [프롬프트파일]", file=sys.stderr)
        return 1
    p = Path(sys.argv[1])
    prompt = sys.argv[2] if len(sys.argv) > 2 else None
    try:
        im = np.array(Image.open(p).convert("RGB")).astype(float)
    except Exception as e:
        print(f"읽기 실패: {e}", file=sys.stderr)
        return 1

    mag, cya, info = find_marks_by_color(im)
    pts = list(mag) + list(cya)
    H, W = info.get("H", im.shape[0]), info.get("W", im.shape[1])
    cols = _bands(pts, W / 4 * 0.15, axis=0, min_frac=0.08)
    rows = _bands(pts, H / 4 * 0.15, axis=1, min_frac=0.08)
    found = f"마크 {len(mag)}+{len(cya)}={len(pts)}개 · 열 {len(cols)} · 줄 {len(rows)}"

    spec = read_spec(prompt) if prompt else None
    if not spec or spec.get("layout") not in LAYOUTS:
        why = "프롬프트를 안 줌" if not prompt else (
            "GRID_SPEC 줄이 없음" if not spec else f"처음 보는 layout={spec.get('layout')}")
        print(f"⚠️사양모름 {p.name}: {why} — 검사를 건너뛰고 통과시킵니다 ({found})", file=sys.stderr)
        return 4

    want_cols, want_rows = LAYOUTS[spec["layout"]]
    bad = []
    # ★최소 개수는 **사양에서 계산**한다 (2026-08-25).
    #   전에는 12로 박혀 있어서, 2열x2행 커튼 시트(마크 9개가 정상)를 구조이상으로 버렸다.
    #   "게이트가 새 방식을 모르면 성공을 실패로 만든다"가 오늘만 세 번째다.
    want_marks = int(spec.get("marks", 0) or 0)
    floor = max(4, int(want_marks * 0.6)) if want_marks else 12
    if len(pts) < floor:
        bad.append(f"마크가 너무 적음 ({found}, 사양 {want_marks}개 기준 최소 {floor}) "
                   f"— 원본 검출 {info['raw'][0]}·{info['raw'][1]}")
    else:
        if len(cols) != want_cols:
            bad.append(f"열이 {want_cols}개가 아님 ({len(cols)}개: {[len(c) for c in cols]}) "
                       f"— 4x4가 아닐 수 있음(마지막 행 5칸 등)")
        if len(rows) not in want_rows:
            bad.append(f"줄이 {'·'.join(map(str, want_rows))} 중 하나가 아님 ({len(rows)}개)")

    if bad:
        print(f"구조이상 {p.name} [{spec['layout']}]: " + " / ".join(bad), file=sys.stderr)
        return 3
    print(f"구조정상 {p.name} [{spec['layout']}] {found}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
