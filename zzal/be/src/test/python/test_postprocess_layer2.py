# -*- coding: utf-8 -*-
"""2층 후처리 결함 수정 테스트 — #701 (2026-10-08).

(1) 발끝선 — 1층 캔버스가 위로 늘어난 펫(민트 312x405)의 feet.y 를 2층 캔버스(313x350)에 절대값으로
    쓰면 16칸이 아래로 잘렸다. 아랫변에서의 거리로 읽는다(옛 파일은 절대값 그대로).
(2) 소품 — 빗자루 솔이 발보다 아래로 내려온 서 있는 칸은 발 기준점이 솔에 붙었다(펭놈·쿠리만쥬 청소).
    몸통 밖 기준점일 때만 솔을 아래에서부터 따라가 떼어 내고 발을 찾는다.

실행: python -m pytest zzal/be/src/test/python/test_postprocess_layer2.py
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

PIPE = Path(__file__).resolve().parents[2] / "main" / "resources" / "zzal" / "pipeline" / "v1"
sys.path.insert(0, str(PIPE))
import service_post  # noqa: E402
import state8_v5  # noqa: E402


# ── (1) 발끝선 ──────────────────────────────────────────────────────

def _anchors(path, canvas_h, feet_y, with_canvas=True, hw=100):
    pose = {"feet": {"y": feet_y}}
    if with_canvas:
        pose["canvas"] = [312, canvas_h]
    d = {"version": "1", "K": 200, "Hw": hw, "poses": {"base": dict(pose), "eat": dict(pose)}}
    if with_canvas:
        d["canvas"] = {"w": 312, "h": canvas_h}
    path.write_text(json.dumps(d), encoding="utf-8")
    return path


def test_read_norm_ref_bottom_distance(tmp_path):
    hw, foot = service_post.read_norm_ref(_anchors(tmp_path / "a.json", 405, 362))
    assert hw == 100 and foot == ("bottom", 43.0)


def test_read_norm_ref_old_file_without_canvas_is_absolute(tmp_path):
    _, foot = service_post.read_norm_ref(_anchors(tmp_path / "a.json", 405, 362, with_canvas=False))
    assert foot == ("abs", 362.0)


def _frames(d: Path, h=350, w=313, foot=300):
    """16칸 — 머리폭 100 · 발끝 foot 의 직사각형 몸."""
    d.mkdir(parents=True)
    for i in range(1, 17):
        a = np.zeros((h, w, 4), np.uint8)
        a[foot - 200:foot, 106:206] = (200, 200, 205, 255)
        Image.fromarray(a).save(d / f"f{i:02d}.png")


def _foot_y(p):
    return int(np.nonzero((np.array(Image.open(p))[:, :, 3] > 8).any(1))[0].max()) + 1


def test_normalize_uses_bottom_distance_on_taller_layer1(tmp_path):
    """민트 모양 — 1층 캔버스 405·발끝 362(아랫변에서 43). 2층 캔버스 350 이면 발끝 307 이어야 한다.
    옛 동작(절대 362)이면 캔버스 350 밖으로 나가 16칸 전부 '아래' 클리핑이었다."""
    fr = tmp_path / "frames"
    _frames(fr)
    service_post._NORM_CLIPPED.clear()
    ref = _anchors(tmp_path / "a.json", 405, 362)
    assert service_post.normalize_grid(fr, list(state8_v5.KEYS), dict(state8_v5.DEFAULT_POSTURE), str(ref))
    assert service_post._NORM_CLIPPED == {}
    assert {_foot_y(fr / f"f{i:02d}.png") for i in range(1, 17)} == {307}


def test_normalize_same_canvas_height_same_as_absolute(tmp_path):
    """두 층 캔버스 높이가 같으면 아랫변 거리 = 절대 y — 옛 결과와 같은 자리."""
    for name, with_canvas in (("new", True), ("old", False)):
        fr = tmp_path / name / "frames"
        _frames(fr, h=349, w=312)
        ref = _anchors(tmp_path / f"{name}.json", 349, 290, with_canvas=with_canvas)
        service_post.normalize_grid(fr, list(state8_v5.KEYS), dict(state8_v5.DEFAULT_POSTURE), str(ref))
    for i in range(1, 17):
        a = np.array(Image.open(tmp_path / "new" / "frames" / f"f{i:02d}.png"))
        b = np.array(Image.open(tmp_path / "old" / "frames" / f"f{i:02d}.png"))
        assert (a == b).all()
    assert _foot_y(tmp_path / "new" / "frames" / "f01.png") == 290


# ── (2) 소품이 바닥에 닿은 서 있는 칸 — 발 기준점은 몸통 아래 ─────────────

def _sweeper(broom_side, w=552, h=649, body_cx=276, reach=95):
    """정렬 캔버스 크기의 서 있는 칸 — 몸통 120x200 · 발 두 개(아래 y=470) · 빗자루 솔이 발보다 10px 아래.
    broom_side=-1 이면 왼쪽, +1 이면 오른쪽(청소 두 칸처럼 좌우가 바뀐다).
    reach = 몸통 중심~솔 중심. 70 이면 솔이 발에 닿아 띠에서 한 덩어리가 된다(펭놈 f06)."""
    a = np.zeros((h, w, 4), np.uint8)
    a[250:450, body_cx - 60:body_cx + 60] = (40, 70, 140, 255)          # 몸통
    a[450:470, body_cx - 50:body_cx - 15] = (245, 165, 20, 255)          # 왼발
    a[450:470, body_cx + 15:body_cx + 50] = (245, 165, 20, 255)          # 오른발
    hx = body_cx + broom_side * reach                                       # 솔 중심
    for y in range(330, 440):                                            # 자루(손 → 솔), 몸통에 붙는다
        x = int(body_cx + broom_side * (40 + (y - 330) * 0.5))
        a[y, x - 3:x + 4] = (150, 100, 50, 255)
    for y in range(440, 480):                                            # 솔(아래로 넓어짐) — 발보다 10px 아래
        half = 8 + (y - 440) // 2
        a[y, hx - half:hx + half] = (215, 170, 90, 255)
    return Image.fromarray(a)


def test_foot_ref_clean_is_fooled_by_broom():
    """대조 — 고치기 전 기준점은 빗자루 솔(몸통 중심에서 95px)에 붙는다."""
    fx, _ = state8_v5.foot_ref_clean(_sweeper(-1))
    assert abs(fx - (276 - 95)) < 10


def test_foot_ref_body_finds_feet_both_sides(capsys):
    for side in (-1, +1):
        fx, fy = state8_v5.foot_ref_body(_sweeper(side))
        assert abs(fx - 276) <= 2                   # 두 발의 가운데(몸통 아래)
        assert fy == 469                            # 바닥선 = 발 밑창(솔 끝 479 가 아니라)
    # 솔이 발에 닿은 경우 — 솔이 있던 열을 떼어 내므로 닿은 발 가장자리만큼(수 px) 치우칠 수 있다.
    fx, fy = state8_v5.foot_ref_body(_sweeper(+1, reach=70))
    assert abs(fx - 276) <= 6 and fy == 469
    assert "발 기준 몸통 한정" in capsys.readouterr().out


def test_foot_ref_body_untouched_when_feet_under_body():
    """빗자루가 없으면 종전 값과 정확히 같다(바이트 동일 회귀의 근거)."""
    a = np.array(_sweeper(-1))
    a[330:480, :, :] = np.where((a[330:480, :, :3] == (150, 100, 50)).all(-1, keepdims=True)
                                | (a[330:480, :, :3] == (215, 170, 90)).all(-1, keepdims=True), 0, a[330:480])
    im = Image.fromarray(a)
    assert state8_v5.foot_ref_body(im) == state8_v5.foot_ref_clean(im)
