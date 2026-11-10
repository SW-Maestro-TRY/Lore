# -*- coding: utf-8 -*-
"""후처리 구멍 수정 테스트 — #687 (2026-10-08).

세 겹: (1) 격자 맞추기로 격자점 ≤25개  (2) 지우기는 격자점 둘레 원 안의 마크색만
(3) 안전망 — 지우기 감소율·캔버스 끝 닿음이 임계를 넘으면 exit 3.

실행: python -m pytest zzal/be/src/test/python/test_postprocess_holes.py
"""
import sys
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

PIPE = Path(__file__).resolve().parents[2] / "main" / "resources" / "zzal" / "pipeline" / "v1"
sys.path.insert(0, str(PIPE))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import frame_cut  # noqa: E402
import state8_v5  # noqa: E402
import state8_v3  # noqa: E402
from test_find_marks import make_grid, lattice_xy, N, CYA, GREEN  # noqa: E402


# ── (1) 격자 맞추기 ───────────────────────────────────────────────────

def _uneven_lattice():
    """모델이 그린 것처럼 등간격이 아닌 5x5 (간격 254~352px)."""
    xs = [22, 298, 552, 870, 1226]
    ys = [30, 312, 612, 865, 1220]
    return xs, ys


def test_fit_keeps_25_of_40():
    xs, ys = _uneven_lattice()
    true = [(x + (i % 3) - 1, y + (j % 2)) for i, x in enumerate(xs) for j, y in enumerate(ys)]
    rng = np.random.default_rng(7)
    fake = []
    while len(fake) < 15:                       # 칸 안쪽(격자선에서 40px 이상 떨어진) 가짜 후보
        x, y = rng.uniform(0, N, 2)
        if min(abs(x - v) for v in xs) > 40 and min(abs(y - v) for v in ys) > 40:
            fake.append((float(x), float(y)))
    got = frame_cut.fit_lattice(true + fake, 5, 5, N, N)
    assert got["ok"], got["reason"]
    assert len(got["points"]) == 25
    assert set(got["points"]) == set(true)
    assert len(got["dropped"]) == 15


def test_fit_drops_point_just_outside_tolerance():
    xs, ys = _uneven_lattice()
    true = [(x, y) for x in xs for y in ys]
    near = (xs[2] + frame_cut.LATTICE_TOL + 3, ys[2] + 60)    # 열에서 18px · 줄에서 60px
    got = frame_cut.fit_lattice(true + [near], 5, 5, N, N)
    assert got["ok"] and near not in got["points"] and len(got["points"]) == 25


def test_fit_duplicate_slot_keeps_closest():
    xs, ys = _uneven_lattice()
    true = [(x, y) for x in xs for y in ys]
    dup = (xs[1] + 9, ys[1] + 9)
    got = frame_cut.fit_lattice(true + [dup], 5, 5, N, N)
    assert len(got["points"]) == 25 and dup not in got["points"]


def test_fit_fails_with_four_columns():
    xs, ys = _uneven_lattice()
    pts = [(x, y) for x in xs[:4] for y in ys]
    got = frame_cut.fit_lattice(pts, 5, 5, N, N)
    assert not got["ok"] and "열" in got["reason"]


def test_find_marks_fit_drops_floating_sparkle():
    """그린 위에 떠 있는 시안 반짝이 — 그린 띠는 통과하지만 격자 자리가 아니다."""
    a = make_grid()
    xs, ys = lattice_xy()
    sx, sy = int(xs[1] + 150), int(ys[1] + 40)               # 칸 안쪽, 몸통과 떨어진 곳
    a[sy - 1:sy + 2, sx - 10:sx + 11] = CYA
    a[sy - 10:sy + 11, sx - 1:sx + 2] = CYA
    mag, cya, _ = frame_cut.find_marks_by_color(a, "lattice_5x5")
    assert len(mag) + len(cya) == 25                         # 개수 자르기가 하나를 버리지만
    mag2, cya2, info = frame_cut.find_marks_by_color(a, "lattice_5x5", fit=True)
    assert info["fit"]["ok"] and len(mag2) + len(cya2) <= 25
    assert all(min(abs(px - x) for x in xs) <= 2 and min(abs(py - y) for y in ys) <= 2
               for px, py in mag2 + cya2)


def test_gate_does_not_fit():
    """게이트(check_grid)는 fit 을 안 쓴다 — info 에 fit 이 없어야 한다."""
    _, _, info = frame_cut.find_marks_by_color(make_grid(), "lattice_5x5")
    assert "fit" not in info


def test_lattice_points_strict_raises():
    a = make_grid(rows=4)                       # 줄 4개 — 5x5 로 안 맞는다
    with pytest.raises(state8_v5.LatticeFitError):
        state8_v5.lattice_points(a.astype(np.uint8), 4, 4, strict=True)


# ── (2) 지우기 제한 ──────────────────────────────────────────────────

def _cell_with_mark_and_hair():
    """그린 칸: 격자점 십자(10,10 중심) + 같은 시안의 '머리카락' 띠가 십자에 붙어 오른쪽으로 120px."""
    c = np.zeros((200, 200, 3), np.uint8)
    c[:] = GREEN
    c[60:200, 40:160] = (200, 200, 205)              # 몸
    c[9:12, 0:21] = CYA; c[0:21, 9:12] = CYA         # 격자점
    c[18:30, 8:130] = CYA                            # 격자점에 닿은 시안 머리카락(같은 색)
    return Image.fromarray(c)


def test_erase_only_inside_radius():
    raw = _cell_with_mark_and_hair()
    keyed = state8_v5._key_green_nostrip(raw)
    zone = state8_v5.mark_zone((200, 200), (0, 0), (0, 0), [(10.5, 10.5)])
    out = np.array(state8_v5.strip_marks_in_zone(keyed, zone))[:, :, 3] > 8
    yy, xx = np.mgrid[:200, :200]
    far = (xx - 10.5) ** 2 + (yy - 10.5) ** 2 > state8_v5.MARK_R ** 2
    hair = np.zeros((200, 200), bool); hair[18:30, 8:130] = True
    assert out[hair & far].all()                     # 원 밖 머리카락은 하나도 안 지운다
    assert not out[9:12, 0:21].any()                 # 격자점은 지운다


def test_old_square_zone_would_have_eaten_more():
    """대조 — 옛 구역(±36 사각형)이었다면 원 밖 머리카락까지 지워졌다(수정이 의미 있다는 확인)."""
    raw = _cell_with_mark_and_hair()
    keyed = state8_v5._key_green_nostrip(raw)
    sq = np.zeros((200, 200), bool); sq[0:47, 0:47] = True
    out = np.array(state8_v5.strip_marks_in_zone(keyed, sq))[:, :, 3] > 8
    new = np.array(state8_v5.strip_marks_in_zone(
        keyed, state8_v5.mark_zone((200, 200), (0, 0), (0, 0), [(10.5, 10.5)])))[:, :, 3] > 8
    assert (new & ~out).sum() > 50


def test_nostrip_matches_v3_rgb_and_alpha_outside_strip():
    a = make_grid().astype(np.uint8)
    cell = Image.fromarray(a[0:420, 0:312])
    k3 = np.array(state8_v3.key_green(cell, 349))
    ns = np.array(state8_v5._key_green_nostrip(cell))
    assert (k3[:, :, :3] == ns[:, :, :3]).all()
    diff = k3[:, :, 3] != ns[:, :, 3]
    assert (k3[:, :, 3][diff] == 0).all()            # 다른 곳 = v3 가 마크로 지운 곳뿐


# ── (3) 안전망 ───────────────────────────────────────────────────────

def test_hole_loss_ignores_pure_marks():
    before = np.zeros((100, 100), bool)
    before[5:15, 5:15] = True                        # 원 안에만 있는 마크
    before[40:90, 40:90] = True                      # 캐릭터 2,500px
    zone = np.zeros((100, 100), bool); zone[0:20, 0:20] = True
    after = before.copy(); after[5:15, 5:15] = False
    assert state8_v5.hole_loss(before, after, zone) == (0, 2500)
    after[40:50, 40:90] = False                      # 캐릭터 500px 깎임
    assert state8_v5.hole_loss(before, after, zone) == (500, 2500)


def test_main_normal_grid_passes(tmp_path):
    png = tmp_path / "g.png"
    Image.fromarray(make_grid(ribbon=True).astype(np.uint8)).save(png)
    state8_v5.main(str(png), str(tmp_path / "o"))
    assert len(list((tmp_path / "o" / "cut").glob("*.webp"))) == 8


def test_main_hole_safety_net_exits_3(tmp_path, monkeypatch, capsys):
    png = tmp_path / "g.png"
    Image.fromarray(make_grid().astype(np.uint8)).save(png)
    monkeypatch.setattr(state8_v5, "HOLE_MAX", -1.0)       # 어떤 칸이든 걸리게 → 배선만 본다
    with pytest.raises(SystemExit) as e:
        state8_v5.main(str(png), str(tmp_path / "o"))
    assert e.value.code == 3
    err = capsys.readouterr().err
    assert "GRID_STRUCTURE_INVALID" in err and "POSTPROCESS_HOLE f01" in err


def test_main_fit_failure_exits_3(tmp_path, capsys):
    png = tmp_path / "g.png"
    Image.fromarray(make_grid(rows=4).astype(np.uint8)).save(png)
    with pytest.raises(SystemExit) as e:
        state8_v5.main(str(png), str(tmp_path / "o"))
    assert e.value.code == 3
    assert "GRID_STRUCTURE_INVALID" in capsys.readouterr().err


# ── (3') 캔버스 끝 닿음 안전망 (service_post) ─────────────────────────

import service_post  # noqa: E402


def test_edge_contact_straight_vs_round():
    a = np.zeros((349, 312), np.uint8)
    a[100:280, 3:120] = 255                          # 왼쪽 3px 에서 세로 180px 직선
    assert service_post.edge_contact(a)["L"] == 180
    b = np.zeros((349, 312), np.uint8)
    yy, xx = np.mgrid[:349, :312]
    b[(xx - 14) ** 2 + (yy - 170) ** 2 <= 10 ** 2] = 255   # 왼쪽 4px 까지 오는 작은 손끝 — 직선이 아니다
    assert service_post.edge_contact(b).get("L", 0) < service_post.CLIP_MIN["L"]
    # ⚠️한계 — 반지름 70px 넘는 둥근 몸이 끝 6px 안까지 오면 3px 띠에 35줄 넘게 걸려 20을 넘는다.
    #   실사용자 전달본에선 그런 프레임이 0이었다(정상 프레임의 좌·우·아래 닿음은 전부 0).
    c = np.zeros((349, 312), np.uint8)
    c[100:280, 40:120] = 255                         # 끝에서 떨어짐
    assert service_post.edge_contact(c) == {}


def test_build_rejects_body_cut_at_cell_edge(tmp_path):
    """모든 칸의 몸이 오른쪽 격자선을 넘어 그려졌다 → 칸을 뜰 때 오른쪽이 직선으로 잘린다(격자 결함)."""
    a = make_grid()
    xs, ys = lattice_xy()
    for j in range(4):
        for i in range(4):
            cx, cy = int(xs[i] + (xs[1] - xs[0]) / 2), int(ys[j] + (ys[1] - ys[0]) / 2)
            a[cy - 100:cy + 100, cx - 55:min(N, int(xs[i + 1]) + 90)] = (200, 200, 205)
    png = tmp_path / "g.png"
    Image.fromarray(a.astype(np.uint8)).save(png)
    # 게이트(check_grid)를 거치지 않고 안전망만 보려고 build 를 직접 부른다.
    with pytest.raises(service_post.ClipError) as e:
        service_post.build(str(png), str(tmp_path / "o"), list(state8_v5.KEYS),
                           dict(state8_v5.DEFAULT_POSTURE), want_anchors=False)
    msg = str(e.value)
    assert msg.startswith("GRID_STRUCTURE_INVALID")
    assert "GRID_CELL_CLIP" in msg and " R " in msg


def test_build_normal_grid_has_no_clip(tmp_path):
    png = tmp_path / "g.png"
    Image.fromarray(make_grid(ribbon=True).astype(np.uint8)).save(png)
    rc = service_post.main([str(png), str(tmp_path / "o"), "--no-anchors"])
    assert rc == 0
    assert len(list((tmp_path / "o").glob("*.webp"))) == 8
