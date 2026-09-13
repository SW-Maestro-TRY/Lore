#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""자세별 **소품 앵커**를 그림에서 기하로 잰다 — 모델 없음(PIL·numpy 만).

무엇을 위한 것인가
------------------
지금 화면의 머리 앵커는 캐릭터 상자 위에서 12% 고정이다(`constants.ts`). 서 있을 때는 맞지만
`sick`(웅크림)은 정수리가 109px, `sleep`(눕기)은 205px 어긋나 해골이 머리 위 허공에 뜬다.
상자 비율이 아니라 **그 자세의 실루엣을 실제로 재서** 내려 주는 값이 여기서 나온다.

★ 정본은 `~/.claude/soma/lore/contract/소품-앵커-자세별-v1.json` 이다.
  이 모듈은 그 파일을 만든 측정 스크립트(`과정/2026-09-13_소품최종/코드/측정.py`)의
  **기하를 함수로 옮긴 것**이고, 같은 그림을 넣으면 같은 숫자가 나와야 한다.
  형식(키 이름·필드)을 여기서 바꾸면 프론트가 조용히 못 읽는다 — 정본을 먼저 고친다.

★ RTMPose 같은 모델은 쓰지 않는다. 부위 트래킹 실측에서 `sleep` 은 오차 27.6px 로 무너졌다.
  못 재는 것을 재려 들지 않는다 — 기하는 틀려도 같은 방향으로 틀리므로 눈으로 잡힌다.

좌표계
------
캐릭터 스프라이트 캔버스 안의 px(원점 = 왼쪽 위). 프론트는 `렌더된 폭 ÷ canvas.w` 를 곱해
화면 좌표로 옮긴다.

재는 법 (정본 `필드` 절과 글자 그대로 같아야 한다)
--------------------------------------------------
- `bbox`      : 알파 > 8 인 픽셀의 상자. `h` 가 그 **자세의** 실루엣 높이다.
                ★`K` 를 여기서 뽑지 않는다 — K 는 base 것 하나만 쓴다.
- `head_top`  : 정수리. 실루엣 윗변 **4줄**의 가로 중앙(x)과 윗변(y).
- `head_side` : 머리 옆 띠. y = 정수리 아래 **Hw x 0.25**.
                left_x·right_x = 그 높이의 머리 가장자리(정수리 ± Hw/2 와 실제 실루엣 중 **바깥쪽**).
- `hand_front`: 손 앞. y = 정수리 아래 **K x 0.55**. left_x·right_x = 그 높이의 실루엣 좌우 가장자리.
- `feet`      : 발끝. y = 실루엣 아랫변 + 1, left_x·right_x = **아랫변 4줄**의 좌우 끝,
                center_x = bbox 가로 중앙.

K · Hw 는 **base 한 자세에서만** 온다
-------------------------------------
- `K`  = base 의 실루엣 높이(머리끝~발끝)
- `Hw` = base 의 머리 폭 = 위 **22%** 구간의 최대 가로폭
소품 규격 JSON(`소품-규격-v1.2.json`)의 크기·오프셋이 전부 이 둘을 단위로 쓴다.
그래서 **2층 격자에는 base 칸이 없으므로** K·Hw 를 직접 잴 수 없다 — 1층에서 잰 값을 받아야 한다.
받지 못하면 멈춘다. 2층 그림 하나로 K 를 다시 재면 캐릭터마다 소품이 몇 % 씩 어긋나는데
그건 화면을 나란히 놓고 봐야만 드러난다(→ 메모리 `silent-config-mismatch`).

★ 1층과 2층은 **같은 자리로 간다**(S3 `images/zzal/pets/<id>/basic/` — PostProcessStep 규약).
  그래서 파일을 그냥 쓰면 2층이 1층 여덟 자세를 **지워 버린다.** `merge_into()` 로 합친다 —
  한 캐릭터의 `anchors.json` 한 장에 자세 16칸이 모이는 것이 정본의 모양이기도 하다.
  출력 폴더에 1층 파일이 있으면 2층은 K·Hw 도 거기서 물려받는다.
  ⚠️자바는 호출마다 **빈 임시 폴더**를 쓰므로, 그 파일을 거기 내려받아 주는 것은 자바 몫이다.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

# 알파가 이보다 크면 '보이는 픽셀'. state8_v5 의 마지막 잘라내기·service_post 의 티끌 제거와 같은 기준.
ALPHA_ON = 8
# 머리 폭을 재는 위 구간 — 실루엣 높이의 22%.
HEAD_BAND = 0.22
# 머리 옆 띠의 높이 — 정수리 아래 Hw x 이것.
HEAD_SIDE_K = 0.25
# 손 앞의 높이 — 정수리 아래 K x 이것.
HAND_FRONT_K = 0.55
# 정수리·발끝을 읽는 가장자리 줄 수.
EDGE_ROWS = 4

SCHEMA_VERSION = "1"


class AnchorError(ValueError):
    """앵커를 못 잰다 — 설정이 원인이면 설정 이름을 그대로 말한다."""


# ── 픽셀에서 읽기 ──────────────────────────────────────────────────────────
def mask(img: Image.Image) -> np.ndarray:
    """알파 > ALPHA_ON 인 곳만 True 인 판."""
    return np.array(img.convert("RGBA").split()[3]) > ALPHA_ON


def _row_span(m: np.ndarray, y: int):
    """y 부근 3줄에서 실루엣의 좌우 가장자리. 그 높이에 아무것도 없으면 None."""
    y0 = max(0, y - 1)
    y1 = min(m.shape[0], y + 2)
    if y1 <= y0:
        return None
    xs = np.where(m[y0:y1])[1]
    if len(xs) == 0:
        return None
    return int(xs.min()), int(xs.max()) + 1


def measure(m: np.ndarray) -> dict:
    """실루엣 한 장의 날 숫자. 본체가 없으면 AnchorError."""
    ys, xs = np.where(m)
    if len(xs) == 0:
        raise AnchorError("본체가 없습니다 — 알파 > 8 인 픽셀이 한 개도 없습니다")
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    h = y1 - y0 + 1

    # 정수리 = 윗변 4줄의 가로 중앙
    tx = np.where(m[y0:y0 + EDGE_ROWS])[1]
    crown_x = float(tx.mean())

    # 머리 폭 = 위 22% 구간의 최대 가로폭
    hw = 0
    for r in m[y0:y0 + max(1, int(h * HEAD_BAND))]:
        x = np.where(r)[0]
        if len(x):
            hw = max(hw, int(x.max() - x.min() + 1))

    # 발 = 아랫변 4줄
    bxs = np.where(m[max(y0, y1 - (EDGE_ROWS - 1)):y1 + 1])[1]
    return {
        "bbox": [x0, y0, x1 - x0 + 1, h],
        "crown_x": crown_x,
        "hw": hw,
        "feet_left": int(bxs.min()),
        "feet_right": int(bxs.max()) + 1,
    }


def base_scale(img: Image.Image) -> tuple:
    """base 한 장에서 (K, Hw). K = 머리끝~발끝, Hw = 머리 폭."""
    d = measure(mask(img))
    return int(d["bbox"][3]), int(d["hw"])


def pose_anchors(img: Image.Image, K: float, Hw: float) -> dict:
    """자세 한 장의 앵커 한 벌. 정본 JSON 의 자세 한 칸과 **같은 모양**이다."""
    m = mask(img)
    d = measure(m)
    x0, y0, w, h = d["bbox"]
    y1 = y0 + h - 1
    crown_y = y0

    # 머리 옆 띠 — 정수리 아래 Hw x 0.25.
    #   ★`sick`·`sleep` 도 예외 없이 윗변을 정수리로 쓴다. 머리를 따로 찾으려 들면
    #     웅크린 등·이불이 머리로 잡혀 더 크게 틀린다.
    hs_y = crown_y + Hw * HEAD_SIDE_K
    sp = _row_span(m, int(round(hs_y)))
    head_l = d["crown_x"] - Hw / 2
    head_r = d["crown_x"] + Hw / 2
    if sp:                      # 실루엣이 더 바깥이면 그쪽을 쓴다 — 말풍선이 머리에 겹치지 않게
        head_l = min(head_l, sp[0])
        head_r = max(head_r, sp[1])

    # 손 앞 — 정수리 아래 K x 0.55. 그 높이가 비면 bbox 좌우로 물러선다.
    hf_y = crown_y + K * HAND_FRONT_K
    sp2 = _row_span(m, int(round(hf_y))) or (x0, x0 + w)

    return {
        "canvas": list(img.size),
        "bbox": {"x": x0, "y": y0, "w": w, "h": h},
        "head_top": {"x": round(d["crown_x"], 1), "y": y0},
        "head_side": {"y": round(hs_y, 1), "left_x": round(head_l, 1), "right_x": round(head_r, 1)},
        "hand_front": {"y": round(hf_y, 1), "left_x": sp2[0], "right_x": sp2[1]},
        "feet": {"y": y1 + 1, "left_x": d["feet_left"], "right_x": d["feet_right"],
                 "center_x": round(x0 + w / 2, 1)},
    }


# ── 파일 한 장 만들기 ──────────────────────────────────────────────────────
def build(frames: dict, keys, K=None, Hw=None, base_key: str = "base") -> dict:
    """`{key: PIL.Image}` 를 앵커 파일 한 장으로.

    frames  : 자세 key → 그 자세의 **f1 프레임**(서비스에 나가는 webp 의 첫 장과 같은 것)
    keys    : 카탈로그 key 여덟 개. **이 순서 그대로** 파일에 담긴다.
    K · Hw  : 주면 그대로 쓴다(2층). 없으면 `base_key` 칸에서 잰다(1층).

    돌려주는 dict 의 머리에 version · canvas · K · Hw 가 있고, `poses` 아래에 자세별 앵커가 온다.
    못 잰 자세는 **null** 로 두고 이름을 `missing` 에 적는다 — 파일 전체는 나간다.
    그래야 나머지 일곱 자세가 살고, 프론트가 그 한 칸만 예전 폴백(상자 12%)으로 받는다.
    ★조용히 빼면 프론트는 '없는 자세'와 '못 잰 자세'를 구별할 길이 없다.
    """
    missing_frames = [k for k in keys if k not in frames]
    if missing_frames:
        # 설정이 원인일 때는 설정 이름을 그대로 말한다.
        raise AnchorError(
            f"앵커를 잴 프레임이 없습니다: {', '.join(missing_frames)} — "
            f"--keys 는 {len(keys)}개인데 받은 프레임은 {len(frames)}개입니다")

    if K is None or Hw is None:
        if base_key not in frames:
            raise AnchorError(
                f"K·Hw 를 잴 '{base_key}' 칸이 없습니다 — 2층처럼 base 가 없는 격자는 "
                f"1층에서 잰 값을 --base-anchors(또는 --base-k/--base-hw)로 넘겨야 합니다")
        K, Hw = base_scale(frames[base_key])

    K = float(K)
    Hw = float(Hw)
    if K <= 0 or Hw <= 0:
        raise AnchorError(f"K·Hw 가 0 이하입니다(K={K} Hw={Hw}) — base 칸이 비었는지 확인할 것")

    canvas = frames[keys[0]].size
    poses, missing = {}, []
    for k in keys:
        try:
            poses[k] = pose_anchors(frames[k], K, Hw)
        except AnchorError as e:
            poses[k] = None
            missing.append(k)
            print(f"[앵커] {k}: 못 쟀습니다 — {e}. null 로 두고 missing 에 적습니다")

    out = {
        "version": SCHEMA_VERSION,
        "canvas": {"w": int(canvas[0]), "h": int(canvas[1])},
        "K": int(round(K)),
        "Hw": int(round(Hw)),
        "HwOverK": round(Hw / K, 3),
        "poses": poses,
    }
    if missing:
        out["missing"] = missing
    return out


def write(data: dict, path) -> Path:
    p = Path(path)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    return p


def merge_into(path, data: dict) -> dict:
    """이미 있는 앵커 파일에 **합친다**. 없으면 그대로 쓴다. 돌려주는 것은 파일에 담긴 최종 dict.

    왜 합치나 — 1층과 2층이 **같은 자리로 간다**(S3 `basic/`). 그냥 쓰면 나중에 도는 2층이
    1층 여덟 자세를 지운다. 통과는 하고 화면에서 1층 소품만 제자리를 잃는데, 그건 부화를
    끝까지 돌려 봐야만 드러난다.

    머리(canvas·K·Hw)가 다르면 합치지 않고 **갈아엎고 한 줄 남긴다** — 다른 캐릭터이거나
    다시 구운 것이므로, 옛 자세를 남겨 두면 그쪽이 틀린 자리를 가리킨다.
    """
    p = Path(path)
    if not p.exists():
        return data
    try:
        old = json.loads(p.read_text(encoding="utf-8"))
        same = (old.get("version") == data["version"]
                and old.get("canvas") == data["canvas"]
                and old.get("K") == data["K"] and old.get("Hw") == data["Hw"])
    except (OSError, ValueError):
        print(f"[앵커] 기존 {p.name} 을 읽지 못해 새로 씁니다")
        return data
    if not same:
        print(f"[앵커] 기존 {p.name} 의 머리가 다릅니다"
              f"(canvas {old.get('canvas')} K={old.get('K')} Hw={old.get('Hw')} → "
              f"{data['canvas']} K={data['K']} Hw={data['Hw']}) — 합치지 않고 새로 씁니다")
        return data

    poses = dict(old.get("poses") or {})
    poses.update(data["poses"])                      # 이번에 잰 것이 이긴다
    merged = dict(data)
    merged["poses"] = poses
    missing = [k for k in (old.get("missing") or []) if k not in data["poses"]]
    missing += data.get("missing", [])
    if missing:
        merged["missing"] = missing
    else:
        merged.pop("missing", None)
    kept = [k for k in poses if k not in data["poses"]]
    if kept:
        print(f"[앵커] 기존 {len(kept)}칸을 함께 둡니다 — {', '.join(kept)}")
    return merged


def read_base_scale(path) -> tuple:
    """앞서 나온 앵커 파일에서 (K, Hw) 만 꺼낸다 — 2층이 1층 값을 물려받는 길."""
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    if "K" not in d or "Hw" not in d:
        raise AnchorError(f"--base-anchors 에 K·Hw 가 없습니다: {path}")
    return float(d["K"]), float(d["Hw"])
