# -*- coding: utf-8 -*-
"""격자점 검출(find_marks_by_color)·격자 게이트(check_grid) 합성 테스트 — #687 (B6).

실행: python -m pytest zzal/be/src/test/python/test_find_marks.py
의존: pipeline/v1/requirements.txt (numpy·scipy·pillow) + pytest

합성 격자 = 1254 정사각 크로마 그린 위 5x5 격자점(열 1·3·5 마젠타 · 2·4 시안) + 칸마다 회색 캐릭터.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

PIPE = Path(__file__).resolve().parents[2] / "main" / "resources" / "zzal" / "pipeline" / "v1"
sys.path.insert(0, str(PIPE))
import frame_cut  # noqa: E402

N = 1254
GREEN, MAG, CYA, BODY = (0, 255, 0), (255, 0, 255), (0, 255, 255), (200, 200, 205)
INSET = 28
STEP = (N - 2 * INSET) / 4


def lattice_xy(cols=5, rows=5):
    return ([INSET + STEP * i for i in range(cols)], [INSET + STEP * j for j in range(rows)])


def make_grid(rows=5, drop=(), ribbon=False):
    a = np.zeros((N, N, 3), np.uint8)
    a[:] = GREEN
    xs, ys = lattice_xy(5, rows)
    # 캐릭터 — 칸 가운데 회색 몸통. ribbon=True 면 몸통 안에 시안·마젠타 조각(리본·옷 무늬)
    for j in range(4):
        for i in range(4):
            cx, cy = int(xs[i] + STEP / 2), int(INSET + STEP * j + STEP / 2)
            a[cy - 100:cy + 100, cx - 55:cx + 55] = BODY
            if ribbon:
                a[cy - 80:cy - 64, cx - 30:cx - 14] = CYA
                a[cy + 20:cy + 32, cx + 10:cx + 22] = MAG
    for ci, x in enumerate(xs):
        for ri, y in enumerate(ys):
            if (ci, ri) in drop:
                continue
            col = MAG if ci % 2 == 0 else CYA
            x, y = int(round(x)), int(round(y))
            a[y - 1:y + 2, x - 10:x + 11] = col
            a[y - 10:y + 11, x - 1:x + 2] = col
    return a.astype(float)


def gate(tmp_path, arr, layout="lattice_5x5"):
    png = tmp_path / "g.png"
    Image.fromarray(arr.astype(np.uint8)).save(png)
    spec = tmp_path / "spec.txt"
    spec.write_text(f"# GRID_SPEC: marks=25 layout={layout}\n", encoding="utf-8")
    r = subprocess.run([sys.executable, str(PIPE / "check_grid.py"), str(png), str(spec)],
                       capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def on_lattice(pts, rows=5):
    xs, ys = lattice_xy(5, rows)
    return all(min(abs(px - x) for x in xs) <= 2 and min(abs(py - y) for y in ys) <= 2 for px, py in pts)


def test_lattice_expect():
    assert frame_cut.lattice_expect("lattice_5x5") == (15, 10)
    assert frame_cut.lattice_expect("lattice_7x4") == (16, 12)
    assert frame_cut.lattice_expect("lattice_3x3") == (6, 3)
    assert frame_cut.lattice_expect("lattice_4x3") == (6, 6)
    assert frame_cut.lattice_expect("per_cell_2") is None
    assert frame_cut.lattice_expect(None) is None


def test_normal(tmp_path):
    mag, cya, info = frame_cut.find_marks_by_color(make_grid(), "lattice_5x5")
    assert (len(mag), len(cya)) == (15, 10)
    assert on_lattice(mag + cya)
    assert info["green_bg"]
    code, out = gate(tmp_path, make_grid())
    assert code == 0, out


def test_ribbon_inside_character_is_not_a_mark(tmp_path):
    arr = make_grid(ribbon=True)
    mag, cya, info = frame_cut.find_marks_by_color(arr, "lattice_5x5")
    assert info["raw"] == (31, 26)              # 리본·무늬 32조각이 hue로는 잡힌다
    assert (len(mag), len(cya)) == (15, 10)     # 그린 둘러싸임 조건이 전부 걷어낸다
    assert on_lattice(mag + cya)
    code, out = gate(tmp_path, arr)
    assert code == 0, out


def test_one_point_missing(tmp_path):
    arr = make_grid(ribbon=True, drop={(2, 2)})
    mag, cya, _ = frame_cut.find_marks_by_color(arr, "lattice_5x5")
    assert (len(mag), len(cya)) == (14, 10)
    assert on_lattice(mag + cya)
    code, out = gate(tmp_path, arr)
    assert code == 0, out                         # 하나 빠진 건 구조 이상이 아니다


def test_four_rows_rejected(tmp_path):
    arr = make_grid(rows=4, ribbon=True)
    mag, cya, _ = frame_cut.find_marks_by_color(arr, "lattice_5x5")
    assert (len(mag), len(cya)) == (12, 8)
    code, out = gate(tmp_path, arr)
    assert code == 3 and "줄이 5" in out, out
    assert "후보 상위 20" in out


def test_no_layout_still_filters_by_green(tmp_path):
    """layout 을 모를 때(frame_cut 단독 실행)도 그린 조건은 건다 — 개수 자르기만 안 한다."""
    mag, cya, _ = frame_cut.find_marks_by_color(make_grid(ribbon=True))
    assert (len(mag), len(cya)) == (15, 10)
