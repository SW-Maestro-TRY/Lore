#!/usr/bin/env python3
"""작품 폴더 안에서 **무엇이 어디 있는지**만 아는 모듈.

## 왜 따로 뽑았나

`s3_upload.py` 는 올릴 그림을 찾으려고 세 가지만 알면 된다 — 작품 폴더가
어디인지(`run_dir`), 몇 장인지(`page_numbers`), 그 한 장의 실제 파일이
무엇인지(`final_unit`).

그런데 이 셋이 `landing/newharness_pipeline.py` 안에 있었고, 그 모듈은
**랜딩 웹서버 전체**(`serve` · `accounts` · `credits` · `visibility` ·
`ownership` · `pipeline` · `usage_report` · `watermark`)를 모듈 최상단에서
import 한다. 그래서 그림 파일 경로 하나를 얻으려고 웹서버를 통째로 끌고
들어왔다 — 파이썬을 서버가 아니라 CLI 파이프라인으로만 쓰기로 한 뒤에는
지고 갈 이유가 없는 짐이다(2026-09-12).

여기 옮겨 적은 것은 동작이 같다. 옮기면서 고치지 않았다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import overlay

HERE = Path(__file__).resolve().parent
NEW_HARNESS = HERE.parent / "new_harness"


def run_dir(run_id: str) -> Path:
    return NEW_HARNESS / "runs" / run_id


def _read_json_safe(base: Path, name: str) -> dict[str, Any]:
    try:
        return json.loads((base / name).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _mtime(p: Path | None) -> float:
    try:
        return p.stat().st_mtime if p else 0.0
    except OSError:
        return 0.0


def page_numbers(run_id: str) -> list[int]:
    """이 run 의 페이지 번호(1..N).

    pages.json(콘티) 을 우선 보고, 없으면 **고른 방향의 장면 수**로 센다 —
    표지 1장 + 장면 하나당 1장이 이어그리기의 규칙이다.

    예전에는 그려진 **파일 수**로 셌는데, 그러면 중간 한 장이 없을 때 총
    개수가 그만큼 줄어서 마지막 장이 목록에서 밀려 나갔다.

    둘 다 없으면(아직 방향을 안 골랐거나 옛 run) 예전처럼 파일 수로 센다.
    """
    d = run_dir(run_id)
    try:
        pages = json.loads((d / "pages.json").read_text(encoding="utf-8"))
        if pages:
            return list(range(1, len(pages) + 1))
    except (OSError, ValueError):
        pass
    pick = _read_json_safe(d, "pick.json")
    try:
        directions = json.loads((d / "directions.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        directions = []
    chosen = next((x for x in directions if x.get("n") == pick.get("n")), None) \
        or (directions[0] if directions else None)
    scenes = [x for x in ((chosen or {}).get("scenes") or []) if str(x or "").strip()]
    if scenes:
        return list(range(1, len(scenes) + 2))          # 표지 + 장면들
    files = sorted((d / "pages").glob("page*.png")) if (d / "pages").exists() else []
    return list(range(1, len(files) + 1))


def unit_image(run_id: str, no: int) -> Path | None:
    p = run_dir(run_id) / "pages" / f"page{int(no):02d}.png"
    return p if p.exists() else None


def final_unit(run_id: str, no: int) -> Path | None:
    """편집실에서 얹은 것이 있으면 구운 판, 없으면 원본.

    (볼 때 굽고, 밑그림·얹은 것보다 새 구운 판이 있으면 재사용한다.)
    """
    base = unit_image(run_id, no)
    if not base:
        return None
    d = run_dir(run_id)
    ov = overlay.overlay_path(d)
    if not ov.exists():
        return base
    try:
        data = overlay.load_overlay(d)
    except Exception:                                           # noqa: BLE001
        return base
    if not overlay.has_items(data, no):
        return base
    out = overlay.baked_scene_path(d, no)
    if _mtime(out) >= max(_mtime(base), _mtime(ov)):
        return out
    try:
        return overlay.bake_one(d, no, base, data)
    except Exception:                                           # noqa: BLE001
        return base
