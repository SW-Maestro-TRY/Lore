#!/usr/bin/env python3
"""웹툰 컷 이미지 생성 하네스.

스토리 파이프라인의 "컷 서술 → 실제 웹툰 이미지" 구간만 검증하는 실험 도구다.
제품이 아니다. UI/DB/서버 없음.

  python run.py --run-id 20260803T223106-38684d --episode 1 --condition A
  python run.py --run-id 20260803T223106-38684d --episode 1 --all-conditions
  python run.py --run-id ... --episode 1 --all-conditions --dry-run
  python run.py --run-id ... --episode 1 --sheet-only     # 컨택트 시트만 다시 만들기
  python run.py --run-id ... --episode 1 -c C --view      # 채택 컷 세로 스크롤 뷰어
  python run.py --run-id ... --episode 1 --mode scene -c C     # 컷 3개 = 이미지 1장
  python run.py --run-id ... --episode 1 --mode both -c C --view  # 두 모드 나란히 비교
  python run.py --run-id ... --episode 1 --mode both -c C --view --directed  # 연출 여백 적용
  python run.py --run-id ... --episode 1 -c C --style-lock   # Scene 사이 그림체 일관성 확인

단계: load → prompt_gen/scene_gen(텍스트 LLM) → render(이미지 모델) → contact_sheet
자동 선택은 없다. 전부 저장하고 사람이 contact_sheet 에서 고른다.

--mode cut(기본)  : 컷 1개 = 이미지 1장.  outputs/.../ep1/<조건>/cut{n}_c{k}.png
--mode scene      : 컷 N개 = 이미지 1장.  outputs/.../ep1/scene_<조건>/scene{n}_c{k}.png
--mode both       : 둘 다. 호출 수와 비용을 나란히 찍는다.

--view 는 생성을 하지 않는다. picks.csv 의 채택본을 세로로 이어붙여
읽는 경험만 확인한다 (컷 모드는 layout.json 의 크기대로).

--style-lock 도 생성을 하지 않는다. 이미 뽑아 둔 Scene 채택본만 읽어
Scene 사이에서 그림체(선 굵기·채도 톤·얼굴 양식화·배경 밀도)가 유지되는지
본다. 스타일 문구 실험은 "한 장 안"에서만 검증됐고 Scene 사이는 아직이다.
얼굴만 잘라 가로로 붙여 보는 모드가 핵심이다 — 차이는 얼굴에서 먼저 드러난다.

연출 (스토리 하네스 W7.5, ep{NN}_direction.json):
  있으면 Scene 경계를 scene_break 이 정한다 (config 의 cuts_per_scene 은 예비 경로가 된다).
  gaze 는 scene_gen 프롬프트로 넘어가 패널 서술의 시선 방향이 된다.
  gap_after 는 --directed 뷰어에서 컷/Scene 사이의 빈 자리가 된다.
  --directed 는 이미지를 다시 만들지 않는다 — 같은 그림을 여백만 바꿔 다시 깐다.
  그래서 "내릴 맛이 생겼는가"를 0원으로 비교할 수 있다.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml

import bubbles
import cast
import charsheet
import episode
import scenegen
import storyload
import stylelock
import supporting
import strip
import vision
import stripview
import textgen
import verifyall
import viewer
import report
import review
from providers import GenRequest, ProviderError, build_provider
from report import build_contact_sheet, load_picks, picks_path

ROOT = Path(__file__).resolve().parent
OUT_ROOT = ROOT / "outputs"

# 콘솔이 cp949 면 '—' 같은 문자에서 print 가 UnicodeEncodeError 로 죽는다.
# 안내 문구 하나 때문에 실행이 멈추면 안 되므로 못 찍는 글자는 대체 문자로 흘린다.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # 파이프로 감싼 스트림 등
        pass

DRY_SCENE = "[dry-run — 텍스트 LLM 미호출. 실제 실행 시 이 자리에 영어 장면 서술이 들어갑니다]"


def die(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    print(f"\n[중단] {msg}\n", file=sys.stderr)
    raise SystemExit(1)


# --------------------------------------------------------------------------- #
# .env / config
# --------------------------------------------------------------------------- #
def load_dotenv(path: Path) -> dict[str, str]:
    """의존성 없는 최소 .env 파서. 이미 설정된 환경변수가 우선."""
    values: dict[str, str] = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.removeprefix("export ").partition("=")
            key, val = key.strip(), val.strip()
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
                val = val[1:-1]
            values[key] = val
    return {**values, **{k: v for k, v in os.environ.items() if v}}


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        die(f"설정 파일이 없습니다: {path}")
    cfg = yaml.safe_load(path.read_text(encoding="utf-8")) or {}

    if not cfg.get("conditions"):
        die("config.yaml 에 conditions 가 없습니다.")

    cfg.setdefault("candidates_per_cut", 3)
    cfg.setdefault("global_suffix", "")
    # 캐릭터 시트의 design_details/color_palette 가 들어갈 자리. config.yaml 에
    # 적는 값이 아니라 run.py 가 p1.json 을 읽어 채운다.
    cfg.setdefault("design_lock", "")
    cfg.setdefault("prompt_template", "{appearance}\n\nScene: {scene}\n\n{style}\n{extra}")
    cfg.setdefault("story_runs_root", "C:/lore/story-harness/runs")
    cfg.setdefault("pricing", {}).setdefault("usd_to_krw", 1400)
    lim = cfg.setdefault("limits", {})
    lim.setdefault("max_calls_per_condition", 36)
    lim.setdefault("max_total_calls", 120)
    retry = cfg.setdefault("retry", {})
    retry.setdefault("max_retries", 2)
    retry.setdefault("backoff_sec", 5)
    prov = cfg.setdefault("provider", {})
    prov.setdefault("name", "mock")
    prov.setdefault("api_key_env", "GEMINI_API_KEY")
    prov.setdefault("model_env", "GEMINI_IMAGE_MODEL")
    prov.setdefault("cost_per_image_usd", 0.0)
    prov.setdefault("options", {})
    txt = cfg.setdefault("text", {})
    txt.setdefault("api_key_env", "GEMINI_API_KEY")
    txt.setdefault("model_env", "GEMINI_TEXT_MODEL")
    txt.setdefault("temperature", 0.4)
    txt.setdefault("max_output_tokens", 8000)
    txt.setdefault("timeout_sec", 180)
    txt.setdefault("cost_per_call_usd", 0.0)
    cfg.setdefault("prompt_gen", {}).setdefault("banned_terms", [])
    # 검증용 후보 수는 채택용보다 적다 — 여기서 고를 것이 아니라 잴 것이기 때문이다.
    cfg.setdefault("scene", {}).setdefault("verify_candidates_per_scene", 2)
    view = cfg.setdefault("viewer", {})
    view.setdefault("width_px", 690)
    view.setdefault("gap_px", 0)
    view.setdefault("show_captions", True)
    view.setdefault("background", "#ffffff")
    view.setdefault("sizes", {"wide": "16:9", "normal": "4:3",
                              "tall": "3:4", "impact": "screen"})
    return cfg


def only_candidate_picks(cfg: dict[str, Any], conditions: list[str],
                         cut_numbers: list[int],
                         scene_numbers: list[int]) -> dict[tuple[str, int], int]:
    """후보가 1장뿐인 모드의 "자동 채택". 아니면 빈 dict.

    후보를 여러 장 뽑았을 때는 사람이 고른 것만이 채택본이다. 1장뿐이면 고른다는
    말 자체가 성립하지 않으므로, 없는 picks.csv 를 요구하지 않는다.
    """
    picks: dict[tuple[str, int], int] = {}
    if int(cfg["candidates_per_cut"]) == 1:
        for cname in conditions:
            picks.update({(cname, n): 1 for n in cut_numbers})
    if int(cfg["scene"]["candidates_per_scene"]) == 1:
        for cname in conditions:
            picks.update({(scene_cond(cname), n): 1 for n in scene_numbers})
    return picks


def apply_candidates(cfg: dict[str, Any], wanted: int | None) -> None:
    """--candidates N → 후보 장수를 이번 실행에만 덮어쓴다.

    후보를 여러 장 뽑는 것은 "그중에서 고르려고" 하는 일이다. 완성된 한 화를 한 벌
    보는 것이 목적이라면 고를 것이 없으므로 1장이면 된다 — 같은 프롬프트로 같은
    모델에서 3번 뽑아 봐야 미묘하게 다른 그림 3장이 나올 뿐이고, 비용은 3배다.
    """
    if wanted is None:
        return
    if wanted < 1:
        die(f"--candidates 는 1 이상이어야 합니다 (받은 값: {wanted}).")
    before_cut = int(cfg["candidates_per_cut"])
    before_scene = int(cfg["scene"]["candidates_per_scene"])
    cfg["candidates_per_cut"] = wanted
    cfg["scene"]["candidates_per_scene"] = wanted
    cfg["scene"]["verify_candidates_per_scene"] = wanted
    print(f"[candidates] 후보 {wanted}장 — 이번 실행에만 적용합니다 "
          f"(config 값은 컷 {before_cut} / Scene {before_scene} 그대로).")
    if wanted == 1:
        print("             고를 것이 없으므로 채택 단계를 건너뜁니다 — picks.csv 가 "
              "없어도 --view / --style-lock 이 c1 을 채택본으로 봅니다.")


def select_style(cfg: dict[str, Any], wanted: str | None) -> str:
    """--style <이름> → style_suffix. config 의 styles 표에서 고른다.

    그림체는 이 실험의 가장 큰 변수이고, story-harness 의 캐릭터 시트도 같은 값을
    읽어 간다. 그래서 문구를 직접 적게 두지 않고 이름으로만 고르게 한다 —
    두 하네스가 "romance" 라는 같은 말을 쓸 수 있어야 대조가 성립한다.
    """
    # 값은 문자열 하나이거나 {render_style: 문구} 표다. 표일 때는 normal 이 대표값.
    styles: dict[str, str] = {}
    variants: dict[str, dict[str, str]] = {}
    for key, value in (cfg.get("styles") or {}).items():
        if isinstance(value, dict):
            table = {str(k).strip().lower(): str(v or "").strip()
                     for k, v in value.items() if str(v or "").strip()}
            if not table:
                continue
            if "normal" not in table:
                die(f"config.yaml 의 styles.{key} 에 normal 이 없습니다.\n"
                    f"        normal 이 이 그림체의 대표값입니다 "
                    f"(캐릭터 시트 대조에 쓰입니다).")
            styles[str(key)], variants[str(key)] = table["normal"], table
        elif str(value or "").strip():
            styles[str(key)] = str(value).strip()
    name = str(wanted or cfg.get("style_default") or "").strip()

    if not styles:
        # styles 표가 없는 예전 config. style_suffix 를 그대로 쓴다.
        legacy = str(cfg.get("style_suffix") or "").strip()
        if not legacy:
            die("config.yaml 에 styles 표도 style_suffix 도 없습니다.\n"
                "        styles: 아래에 이름 붙인 그림체를 하나 이상 등록하세요.")
        if wanted:
            die(f"config.yaml 에 styles 표가 없어 --style {wanted} 을 쓸 수 없습니다.\n"
                f"        styles: 아래에 이름 붙인 그림체를 등록하세요.")
        return legacy

    if not name:
        die(f"어떤 그림체로 뽑을지 정해지지 않았습니다.\n"
            f"        --style <이름> 을 주거나 config.yaml 의 style_default 를 "
            f"채우세요.\n        등록된 그림체: {', '.join(styles)}")
    if name not in styles:
        die(f"config.yaml 의 styles 에 없는 그림체입니다: {name}\n"
            f"        등록된 그림체: {', '.join(styles)}")
    # 컷의 render_style 마다 통째로 다른 문구를 쓴다. 접미사를 덧붙이는 구조로는
    # 셋이 결국 같은 그림체로 보였다.
    cfg["style_variants"] = variants.get(name, {})
    return styles[name]


def style_name_of(cfg: dict[str, Any], suffix: str) -> str:
    """지금 쓰는 문구가 styles 표의 어느 이름인지. 화면 표시와 경고에 쓴다.

    render_style 별 표로 등록된 style 은 normal 이 대표값이다 — select_style() 이
    돌려주는 것도 그 값이므로 여기서도 normal 과 맞춰 본다. 표 전체를 문자열로
    바꿔 비교하면 어떤 이름과도 맞지 않아 "(styles 표에 없음)" 이 뜬다.
    """
    for k, v in (cfg.get("styles") or {}).items():
        if isinstance(v, dict):
            v = (v.get("normal") or "")
        if str(v or "").strip() == suffix.strip():
            return str(k)
    return "(styles 표에 없음)"


def require_appearance(cfg: dict[str, Any], sheet: charsheet.Sheet | None = None) -> str:
    """모든 프롬프트 맨 앞에 붙을 외형 문구. 스토리 쪽 값이 먼저다.

    예전에는 이 문구를 config.yaml 에 손으로 적었다. 그러면 스토리 쪽 캐릭터
    설정이 바뀌어도 여기는 그대로라 조용히 어긋난다. 이제 story-harness 의
    p1.json(appearance_en)이 기준이고, config 값은 시트가 없는 예전 run 을 위한
    예비 경로다.
    """
    from_sheet = (sheet.appearance if sheet else "").strip()
    from_cfg = str(cfg.get("character_appearance") or "").strip()
    # 성별은 외형의 일부다. appearance_en 이 "short blonde hair, tall" 처럼
    # 성별을 말하지 않으면 이미지 모델이 컷마다 알아서 정한다.
    gender = charsheet.gender_line(
        (sheet.gender if sheet else "") or str(cfg.get("character_gender") or ""))

    def with_gender(text: str) -> str:
        return f"{text.rstrip()}\n{gender}" if gender else text

    if from_sheet:
        if from_cfg and from_cfg != from_sheet:
            print("[appearance] p1.json 의 appearance_en 을 씁니다 "
                  "(config.yaml 의 character_appearance 는 무시됩니다 — "
                  "두 값이 다릅니다).")
        else:
            print("[appearance] p1.json 의 appearance_en 을 씁니다.")
        if gender:
            print(f"[appearance] 성별을 명시합니다 — {gender}")
        return with_gender(from_sheet)
    if from_cfg:
        print("[appearance] config.yaml 의 character_appearance 를 씁니다 "
              "(p1.json 에 appearance_en 이 없습니다).")
        return with_gender(from_cfg)
    die(
        "캐릭터 외형이 어디에도 없습니다.\n"
        "        1) story-harness 의 p1.json 에 appearance_en 이 있어야 하거나\n"
        "        2) config.yaml 의 character_appearance 를 영문으로 채워야 합니다.\n"
        "        이 문구는 모든 프롬프트 맨 앞에 그대로 붙습니다 — 비면 캐릭터가\n"
        "        컷마다 다른 사람이 됩니다."
    )


def ref_path(raw: Any) -> Path:
    """레퍼런스 경로 하나. config 의 refs/ 는 하네스 기준 상대경로, 캐릭터 시트는
    스토리 run 폴더 안의 절대경로다. 둘을 같은 자리에서 받는다."""
    p = Path(str(raw))
    return p if p.is_absolute() else ROOT / p


def env_model(env: dict[str, str], var: str, kind: str) -> str:
    model = str(env.get(var) or "").strip()
    if not model:
        die(f"{var} 가 없습니다. .env 에 '{var}=...' 로 {kind} 모델 이름을 넣어주세요 (.env.example 참고).")
    return model


def env_key(env: dict[str, str], var: str) -> str:
    key = str(env.get(var) or "").strip()
    if not key:
        die(f"{var} 가 없습니다. .env 에 '{var}=...' 를 넣어주세요 (.env.example 참고).")
    return key


# --------------------------------------------------------------------------- #
# 프롬프트 조립 — 코드가 강제한다. LLM 은 {scene} 만 쓴다.
# --------------------------------------------------------------------------- #
# 주인공이 없는 컷에 외형 대신 들어가는 문구. "여기 있는 사람들은 다른 인물이다"
# 까지 쓰면 인물이 없는 배경 컷(대관식 홀 전경)에 사람을 그려 넣게 된다.
ABSENT_NOTE = ("The main character does not appear in this panel. "
               "Do not draw the main character here.")


def render_suffix(cfg: dict[str, Any], render_style: str) -> str:
    """컷의 render_style -> 덧붙일 스타일 문구. 모르는 값이면 빈 문자열.

    모르는 값에서 세우지 않는 이유: 스토리 하네스가 값을 하나 늘렸을 때 컷을 아예
    못 그리게 되는 것보다, 기본 작화로 그리고 넘어가는 편이 낫다.
    """
    table = cfg.get("render_style_suffix")
    if not isinstance(table, dict):
        return ""
    return str(table.get(str(render_style or "normal").strip().lower()) or "").strip()


def cond_extra(cfg: dict[str, Any], cond: dict[str, Any], render_style: str,
               attached: bool, description: str = "",
               second_lead: "charsheet.Sheet | None" = None,
               zone_text: str = "", uses_previous: bool = False) -> str:
    """조건의 {extra} + 시트 사용 지침 + 이 컷에 나오는 조연의 외형.

    시트가 실제로 붙은 컷에만 시트 지침을 붙인다 — 첨부가 없는데 "attached
    sheet" 를 말하면 모델이 없는 이미지를 찾는다.

    조연 블록은 첨부 여부와 무관하다. 조연은 시트가 없어서 글자로만 고정되기
    때문이다. 그 컷 서술에 이름이 나올 때만 붙는다 (supporting.block).

    second_lead 는 "그 한 사람"의 시트가 이 컷/장면에 실제로 붙었을 때만
    넘어온다. 첨부 이미지가 둘이 되는 순간이라, 몇 번째가 누구 것인지 못박지
    않으면 모델이 두 얼굴을 섞는다.

    zone_text 는 그 장면이 벌어지는 자리의 서술이다 — **이미지가 아니라 글로**
    넘긴다. 배경을 한 번 구워 재사용하면 그 안에 잘못 들어간 것이 화 전체에
    박히고(자판기 위의 머그컵), 되돌리려면 그 존의 컷을 전부 다시 뽑아야 한다.
    글로 넘기면 같은 존의 컷이 같은 서술을 받아 배경이 이어지면서도, 틀린 곳은
    series.json 한 줄을 고치면 다음 컷부터 바로 반영된다.
    """
    text = str(cond.get("extra") or "").strip()
    if attached:
        note = str((cfg.get("sheet_notes") or {}).get(
            str(render_style or "normal").strip().lower()) or "").strip()
        if note:
            text = f"{text}\n{note}".strip()
    if second_lead is not None:
        # 첨부가 셋이 되는 순간 config 의 "SECOND attached image" 문구가 어긋난다 —
        # 직전 장은 언제나 **맨 뒤**에 붙는데(resolve_attachments), 그 자리를
        # 이 시트가 밀어낸다. 그래서 순서를 여기서 다시 못박는다. 앞의 문구와
        # 충돌하면 뒤에 오는 이것이 이긴다고 명시한다.
        who = second_lead.name or "this scene’s other character"
        order = ["the main character's sheet"] if attached else []
        order.append(f"the character sheet for {who}")
        if uses_previous:
            order.append("the sheet directly above this one in the episode")
        listing = "; ".join(f"({i}) {x}" for i, x in enumerate(order, 1))
        text = (f"{text}\nATTACHMENT ORDER — this overrides any numbering "
               f"stated above. The attached images are, in order: {listing}. "
               f"The sheet for {who} applies ONLY to that character: never use "
               f"it for anyone else and never blend its face or design into "
               f"another character.").strip()
    if str(zone_text or "").strip():
        text = (f"{text}\nTHIS PLACE — the setting of this page, written out so "
               f"it stays the same every time it appears. Draw the environment "
               f"to match it exactly, and change nothing about the place itself "
               f"from panel to panel:\n{str(zone_text).strip()}").strip()
    crowd = supporting.block(cfg.get("supporting_book") or supporting.Book(),
                             description)
    return f"{text}\n{crowd}".strip() if crowd else text


def style_block(cfg: dict[str, Any], render_style: str) -> str:
    """{style} 자리에 들어갈 것 — 이 컷의 render_style 에 해당하는 그림체 문구.

    styles 표가 render_style 별 문구를 갖고 있으면 통째로 그것을 쓴다 (sd 컷은
    로판 문구를 아예 보지 않는다). 문자열 하나짜리 style 이면 예전처럼 공통 문구에
    render_style_suffix 를 덧붙인다.
    """
    kind = str(render_style or "normal").strip().lower()
    variants = dict(cfg.get("style_variants") or {})
    if kind in variants:
        return variants[kind]
    base = str(cfg["style_suffix"]).strip()
    per_cut = render_suffix(cfg, kind)
    return f"{base}\n{per_cut}" if per_cut else base


def assemble(cfg: dict[str, Any], appearance: str, scene: str, extra: str,
             with_lock: bool = True, render_style: str = "normal") -> str:
    lock = str(cfg.get("design_lock") or "") if with_lock else ""
    text = str(cfg["prompt_template"])
    # 그림체 문구는 코드가 {style} 자리에 박는다 — LLM 도 템플릿도 뺄 수 없다.
    # 컷마다 이것만 달라진다. Scene 모드와 같은 style_block() 을 쓴다: style 이
    # render_style 별 표면 그 문구를 통째로, 문자열 하나면 공통 문구 + 접미사.
    style = style_block(cfg, render_style)
    for token, value in (
        ("{appearance}", appearance.strip()),
        ("{scene}", scene.strip()),
        ("{style}", style),
        ("{extra}", str(extra or "").strip()),
    ):
        text = text.replace(token, value)
    # 디자인 고정 문구는 style_suffix 와 같은 자리, 같은 방식이다 — 코드가 붙이고
    # 템플릿으로도 LLM 으로도 뺄 수 없다. 이게 없으면 소매 반사띠 같은 것이 컷마다 샌다.
    if lock.strip():
        text = f"{text.rstrip()}\n{lock.strip()}"
    tail = str(cfg.get("global_suffix") or "").strip()
    if tail:
        text = f"{text.rstrip()}\n{tail}"
    # 흑백 못은 Scene 모드와 같은 자리(맨 끝)에 박는다. 두 모드의 차이는 "묶음 +
    # 레이아웃" 뿐이어야 하므로, 한쪽에만 있으면 비교가 깨진다.
    # 스팟 컬러는 주인공의 것이다. with_lock 이 꺼진 컷은 주인공이 화면에 없다는
    # 뜻이므로(build_jobs 의 present), 그 컷에는 스팟 컬러를 말하지 않는다.
    if cfg.get("style_monochrome"):
        text = f"{text.rstrip()}\n{scenegen.mono_tail(cfg, spots=with_lock)}"
    text = "\n".join(line.rstrip() for line in text.splitlines())
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def lint_scene(scene: str, banned: list[str]) -> list[str]:
    low = scene.lower()
    return [t for t in banned if str(t).lower() in low]


# --------------------------------------------------------------------------- #
# 로그
# --------------------------------------------------------------------------- #
def next_call_id(ep_dir: Path) -> str:
    """이 화의 몇 번째 API 호출인가. log.jsonl 줄 수로 센다.

    호출 하나를 나중에 지목할 수 있어야 한다 — "3,200원 나간 그 컷" 을 프롬프트,
    첨부, 토큰, 응답까지 되짚으려면 줄마다 이름이 있어야 하기 때문이다. 파일
    줄 수를 세는 것이므로 여러 번 나눠 실행해도 이어지고, 별도 상태 파일이 필요
    없다. 한 화를 동시에 두 프로세스로 돌리면 번호가 겹칠 수 있으나, 이 하네스는
    순차 실행이다.
    """
    path = ep_dir / "log.jsonl"
    n = 0
    if path.exists():
        with path.open(encoding="utf-8") as fh:
            n = sum(1 for line in fh if line.strip())
    return f"{ep_dir.name}-{n + 1:04d}"


def log_line(ep_dir: Path, record: dict[str, Any]) -> None:
    ep_dir.mkdir(parents=True, exist_ok=True)
    # call_id 는 여기서 박는다 — 호출 기록 사이트마다 챙기게 두면 언젠가 빠진다.
    record = {"call_id": next_call_id(ep_dir), **record}
    with (ep_dir / "log.jsonl").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        fh.flush()


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


# --------------------------------------------------------------------------- #
# 토큰과 비용 — 호출할 때마다 그 자리에서 남긴다
#
# 예전에는 로그에 고정 단가만 적혔다 (이미지 1장 = 얼마). 실제로는 첨부한 시트
# 장수와 프롬프트 길이에 따라 호출마다 토큰이 다르고, thinking 토큰은 청구에
# 들어가는데 로그 어디에도 보이지 않았다. usageMetadata 자체는 provider 가 이미
# meta 로 돌려주고 있었으므로 (providers/gemini.py, textgen.py), 그것을 최상위
# 필드로 펴서 남기고 실행이 끝나면 log.jsonl 전체를 다시 세어 합계를 저장한다.
#
# 없는 값은 0 이 아니라 None 이다 — "안 썼다" 와 "모른다" 는 다르다. 실패한
# 호출도 토큰은 청구되므로 ok=False 인 줄에도 그대로 남긴다.
# --------------------------------------------------------------------------- #
TOKEN_KEYS = ("tokens_in", "tokens_out", "tokens_thought", "tokens_cached",
              "tokens_total", "tokens_in_text", "tokens_in_image",
              "tokens_out_image")


def _modality_tokens(details: Any, want: str) -> int | None:
    """usageMetadata 의 *TokensDetails 목록에서 한 modality 만 합산한다."""
    if not isinstance(details, list):
        return None
    total, seen = 0, False
    for row in details:
        if isinstance(row, dict) and str(row.get("modality") or "").upper() == want:
            total += int(row.get("tokenCount") or 0)
            seen = True
    return total if seen else None


def token_fields(meta: dict[str, Any] | None) -> dict[str, int | None]:
    """provider meta -> 로그에 펴서 넣을 토큰 필드."""
    usage = (meta or {}).get("usage")
    if not isinstance(usage, dict):
        return {k: None for k in TOKEN_KEYS}

    def num(key: str) -> int | None:
        v = usage.get(key)
        return int(v) if isinstance(v, (int, float)) else None

    prompt_d = usage.get("promptTokensDetails")
    cand_d = usage.get("candidatesTokensDetails")
    return {
        "tokens_in": num("promptTokenCount"),
        "tokens_out": num("candidatesTokenCount"),
        "tokens_thought": num("thoughtsTokenCount"),
        "tokens_cached": num("cachedContentTokenCount"),
        "tokens_total": num("totalTokenCount"),
        "tokens_in_text": _modality_tokens(prompt_d, "TEXT"),
        "tokens_in_image": _modality_tokens(prompt_d, "IMAGE"),
        "tokens_out_image": _modality_tokens(cand_d, "IMAGE"),
    }


def rate_for(cfg: dict[str, Any], model: str) -> dict[str, float]:
    """pricing.rates 에서 이 모델의 100만 토큰당 단가. 없으면 빈 표.

    이름은 앞부분만 맞으면 된다 — 같은 모델이 -preview 나 날짜 꼬리표를 달고
    오기 때문이다. 여러 개가 걸리면 가장 길게 맞는 것을 쓴다.
    """
    table = (cfg.get("pricing") or {}).get("rates") or {}
    if not isinstance(table, dict) or not model:
        return {}
    best, best_len = {}, -1
    for key, row in table.items():
        name = str(key).strip()
        if isinstance(row, dict) and name and model.startswith(name) and len(name) > best_len:
            best, best_len = {k: float(v) for k, v in row.items()
                              if isinstance(v, (int, float))}, len(name)
    return best


def call_cost(cfg: dict[str, Any], model: str, kind: str,
              tokens: dict[str, int | None]) -> tuple[float | None, str]:
    """(USD, 산정 근거). 단가표가 있으면 토큰으로, 없으면 예전 고정 단가로.

    고정 단가를 남겨 두는 이유: 요금표를 채우지 않은 채로도 지금까지처럼 대략의
    비용은 나와야 하고, 예전 실행분과 숫자를 비교할 수 있어야 한다. 어느 쪽으로
    계산했는지는 로그의 cost_basis 에 남으므로 나중에 섞이지 않는다.
    """
    rate = rate_for(cfg, model)
    if rate and tokens.get("tokens_total") is not None:
        # 출력 이미지 토큰 단가가 따로 있는 모델은 그만큼 일반 출력에서 뺀다.
        out_img = tokens.get("tokens_out_image") or 0
        out = tokens.get("tokens_out") or 0
        out_text = max(out - out_img, 0) if "output_image" in rate else out
        usd = (
            (tokens.get("tokens_in") or 0) * rate.get("input", 0.0)
            + out_text * rate.get("output", 0.0)
            + (out_img * rate["output_image"] if "output_image" in rate else 0.0)
            + (tokens.get("tokens_thought") or 0)
            * rate.get("thought", rate.get("output", 0.0))
        ) / 1_000_000
        return usd, "tokens"

    if kind == "image":
        flat = float(cfg["provider"]["cost_per_image_usd"])
    else:
        flat = float(cfg["text"]["cost_per_call_usd"])
    return flat, "flat"


def cost_fields(cfg: dict[str, Any], model: str, kind: str,
                meta: dict[str, Any] | None, ok: bool = True) -> dict[str, Any]:
    """한 호출의 토큰 + 비용 필드 전부. 로그 record 에 그대로 펴 넣는다.

    **실패했고 토큰도 안 온 호출은 0원으로 둔다.** 예전에는 고정 단가를 그대로
    붙여서, 네트워크가 끊긴 호출까지 장당 단가만큼 비용에 잡혔다 — 한 화에서
    4건이 그랬고 750원이 부풀었다. 응답이 없으면 모델이 무엇을 했는지도 없다.

    실패해도 usage 가 왔으면(안전 필터에 걸려 되돌아온 경우 등) 그건 실제로 돈이
    나간 것이므로 그대로 매긴다. 그래서 판정 기준은 "실패했는가" 가 아니라
    "쓴 흔적이 있는가" 다.

    그림체(style)도 여기서 같이 넣는다. 같은 조건으로 두 그림체를 뽑으면 로그가
    한 파일에 섞이는데, 그것을 가를 열이 없었다.
    """
    krw = float(cfg["pricing"]["usd_to_krw"])
    tokens = token_fields(meta)
    out: dict[str, Any] = dict(tokens)
    out["style"] = style_name_of(cfg, str(cfg.get("style_suffix") or ""))

    if not ok and tokens.get("tokens_total") is None:
        out["cost_basis"] = "no_usage"
        out["est_cost_usd"] = 0.0
        out["est_cost_krw"] = 0
        return out

    usd, basis = call_cost(cfg, model, kind, tokens)
    out["cost_basis"] = basis
    out["est_cost_usd"] = round(usd, 6) if usd is not None else None
    out["est_cost_krw"] = round(usd * krw) if usd is not None else None
    return out


USAGE_FILE = "usage.json"
USAGE_CSV = "usage.csv"

# usage.csv 한 줄에 들어가는 것. 순서가 곧 열 순서다.
# "어떤 요청이 / 어떤 모델로 / 토큰 얼마에 / 얼마" 가 한 줄에서 다 읽혀야 한다.
LEDGER_COLUMNS = (
    "call_id", "timestamp", "kind", "mode", "condition", "cut_number",
    "candidate", "attempt", "ok", "provider", "model",
    "tokens_in", "tokens_in_text", "tokens_in_image", "tokens_out",
    "tokens_out_image", "tokens_thought", "tokens_cached", "tokens_total",
    "cost_basis", "est_cost_usd", "est_cost_krw",
    "duration_sec", "attachments", "output_path", "error",
)


def read_log(ep_dir: Path) -> list[dict[str, Any]]:
    """log.jsonl 전체. 깨진 줄은 건너뛴다 — 한 줄 때문에 집계를 포기하지 않는다.

    토큰 필드를 최상위에 적기 전에 돌린 줄은 provider_meta.usage 에 원본이
    그대로 들어 있다. 읽을 때 거기서 채워 준다 — 예전 실행분도 같은 표에
    들어와야 "이 화가 얼마였나" 가 성립한다. 파일은 고치지 않는다.
    """
    path = ep_dir / "log.jsonl"
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not row.get("call_id"):
                # call_id 를 적기 전의 줄. next_call_id() 와 같은 셈법이므로
                # 지금 붙이는 번호가 그 줄이 받았을 번호와 같다.
                row = {**row, "call_id": f"{ep_dir.name}-{len(rows) + 1:04d}"}
            if row.get("tokens_total") is None and row.get("provider_meta"):
                back = token_fields(row["provider_meta"])
                if back["tokens_total"] is not None:
                    row = {**row, **back, "tokens_backfilled": True}
            rows.append(row)
    return rows


def write_ledger(ep_dir: Path, rows: list[dict[str, Any]]) -> Path:
    """호출 하나 = 한 줄인 표. 표 계산기로 열어 정렬·필터하라고 CSV 다.

    log.jsonl 에 이미 다 있는 값이지만, 거기에는 프롬프트 전문과 응답까지 들어
    있어 사람이 훑기 어렵다. 여기에는 추적에 필요한 열만 둔다 — 프롬프트 원문은
    call_id 로 log.jsonl 을 찾아보면 된다.
    """
    path = ep_dir / USAGE_CSV
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(LEDGER_COLUMNS)
        for row in rows:
            out = []
            for col in LEDGER_COLUMNS:
                v = row.get(col)
                if col == "attachments" and isinstance(v, list):
                    v = len(v)          # 몇 장 붙였나 — 입력 토큰이 튄 이유가 여기 있다
                out.append("" if v is None else v)
            writer.writerow(out)
    return path


def usage_rollup(ep_dir: Path, cfg: dict[str, Any]) -> dict[str, Any] | None:
    """log.jsonl 전체를 다시 세어 usage.json 으로 저장하고 합계를 돌려준다.

    마지막 실행분만 더하지 않는 이유: 한 화는 보통 여러 번에 나눠 돌린다
    (--skip-existing 으로 실패분 재시도, 조건 추가). 화 전체가 얼마였는지가
    알고 싶은 값이므로 매번 처음부터 다시 센다 — 로그가 수천 줄이어도 싸다.
    """
    rows = read_log(ep_dir)
    if not rows:
        return None

    def blank() -> dict[str, Any]:
        # seconds — 이 묶음이 API 를 기다린 시간의 합. 비용과 토큰만 굴리고
        # 시간을 안 굴렸더니 "이 화 뽑는 데 얼마나 걸렸나"를 보려면 usage.csv 를
        # 직접 더해야 했다. 실패한 호출의 시간도 더한다 — 기다린 것은 같다.
        return {"calls": 0, "ok": 0, "failed": 0, "cost_usd": 0.0, "seconds": 0.0,
                **{k: 0 for k in TOKEN_KEYS}}

    total, by_kind, by_model, by_condition, by_style = blank(), {}, {}, {}, {}
    no_usage, flat_priced = 0, 0
    for row in rows:
        kind = str(row.get("kind") or "?")
        model = str(row.get("model") or "?")
        # 조건이 없는 줄(cut_split / prompt_gen)은 조건별 표에서 따로 묶는다.
        cond = str(row.get("condition") or f"({kind})")
        buckets = [total, by_kind.setdefault(kind, blank()),
                   by_model.setdefault(model, blank()),
                   by_condition.setdefault(cond, blank()),
                   # 같은 조건으로 그림체만 바꿔 뽑으면 로그가 한 파일에 섞인다.
                   # 1화가 얼마였는지는 **그림체 단위**로 봐야 한다.
                   by_style.setdefault(str(row.get("style") or "(미기록)"), blank())]
        for b in buckets:
            b["calls"] += 1
            b["ok" if row.get("ok") else "failed"] += 1
            b["cost_usd"] += float(row.get("est_cost_usd") or 0.0)
            b["seconds"] += float(row.get("duration_sec") or 0.0)
            for k in TOKEN_KEYS:
                v = row.get(k)
                if isinstance(v, (int, float)):
                    b[k] += int(v)
        if row.get("tokens_total") is None:
            no_usage += 1
        # cost_basis 가 없는 줄은 이 필드를 적기 전의 실행분이고, 그때는 전부
        # 고정 단가였다.
        if str(row.get("cost_basis") or "flat") == "flat":
            flat_priced += 1

    krw = float(cfg["pricing"]["usd_to_krw"])
    for b in [total, *by_kind.values(), *by_model.values(),
              *by_condition.values(), *by_style.values()]:
        b["cost_usd"] = round(b["cost_usd"], 6)
        b["cost_krw"] = round(b["cost_usd"] * krw)
        b["seconds"] = round(b["seconds"], 1)

    # 가장 비쌌던 호출 — 어디서 돈이 샜는지 파일만 열어도 보이게.
    priciest = sorted(rows, key=lambda r: float(r.get("est_cost_usd") or 0.0),
                      reverse=True)[:5]
    out = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "log": rel(ep_dir / "log.jsonl"),
        "ledger": rel(ep_dir / USAGE_CSV),
        "usd_to_krw": krw,
        "total": total,
        "by_kind": by_kind,
        "by_model": by_model,
        "by_condition": by_condition,
        "by_style": by_style,
        "most_expensive": [
            {k: r.get(k) for k in ("call_id", "kind", "condition", "cut_number",
                                   "candidate", "model", "tokens_total",
                                   "est_cost_usd", "est_cost_krw", "duration_sec")}
            for r in priciest
        ],
        # 토큰이 안 남은 호출 수. 예전 실행분(토큰을 안 적던 때)과 usage 를 주지
        # 않은 호출이 여기 잡힌다 — 합계가 낮게 나오는 이유를 설명해 준다.
        "calls_without_tokens": no_usage,
        # 고정 단가로 매긴 호출 수. pricing.rates 를 채우면 0 이 된다.
        "calls_priced_flat": flat_priced,
    }
    (ep_dir / USAGE_FILE).write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    write_ledger(ep_dir, rows)
    return out


def print_usage(rollup: dict[str, Any] | None) -> None:
    if not rollup:
        return
    t = rollup["total"]
    print(f"\n[usage] 누적 {t['calls']}회 (성공 {t['ok']} / 실패 {t['failed']}) · "
          f"토큰 in {t['tokens_in']:,} / out {t['tokens_out']:,} / "
          f"thinking {t['tokens_thought']:,} · "
          f"{t['cost_krw']:,}원 (약 ${t['cost_usd']:,.2f}) · "
          f"{t.get('seconds', 0):,.0f}초")
    for kind, b in rollup["by_kind"].items():
        print(f"        {kind:<11} {b['calls']:>4}회 · "
              f"토큰 {b['tokens_total']:>9,} · {b['cost_krw']:>8,}원 · "
              f"{b.get('seconds', 0):>7,.0f}초")
    for model, b in rollup["by_model"].items():
        print(f"        {model:<28} {b['calls']:>4}회 · {b['cost_krw']:>8,}원")
    # 1화가 얼마였는지는 그림체 단위로 봐야 한다. 같은 화를 두 그림체로 뽑으면
    # 합계는 두 판을 더한 값이라 "1화 비용" 이 아니다.
    styles = rollup.get("by_style") or {}
    if len(styles) > 1:
        print("        ── 그림체별 (1화 기준으로 보려면 여기를 보세요) ──")
        for name, b in styles.items():
            print(f"        {name:<28} {b['calls']:>4}회 · "
                  f"토큰 {b['tokens_total']:>8,} · {b['cost_krw']:>8,}원")
    if rollup["calls_priced_flat"]:
        print(f"        · {rollup['calls_priced_flat']}회는 고정 단가로 매긴 어림값입니다 "
              f"(config.yaml 의 pricing.rates 를 채우면 실제 토큰으로 계산합니다).")
    if rollup["calls_without_tokens"]:
        print(f"        · {rollup['calls_without_tokens']}회는 토큰 기록이 없습니다 "
              f"(토큰을 남기기 전에 돌린 예전 실행분).")


# --------------------------------------------------------------------------- #
# 1. load  (+ 예비 경로: summary 기반 컷 분해)
# --------------------------------------------------------------------------- #
def load_episode(cfg: dict[str, Any], args, ep_dir: Path,
                 make_text_client) -> storyload.Episode:
    runs_root = Path(str(cfg["story_runs_root"]))
    try:
        return storyload.load_w7_episode(runs_root, args.run_id, args.episode)
    except storyload.LoadError as exc:
        if "W7 컷 산출물이 없습니다" not in str(exc):
            die(str(exc))

    # ---- 예비 경로 ---------------------------------------------------------- #
    print("[load] W7 컷 산출물이 없는 run 입니다 → summary 기반 컷 분해로 넘어갑니다.")
    try:
        ep = storyload.load_summary(runs_root, args.run_id, args.episode)
    except storyload.LoadError as exc:
        die(str(exc))

    cache = ep_dir / "cuts_split.json"
    if cache.exists() and not args.regen_prompts:
        data = json.loads(cache.read_text(encoding="utf-8"))
        ep.cuts = [storyload.Cut(**c) for c in data["cuts"]]
        print(f"[load] 캐시된 컷 분해 사용: {rel(cache)} (컷 {len(ep.cuts)}개)")
        return ep

    if args.dry_run:
        die("dry-run 은 API 를 호출하지 않으므로 컷 분해를 할 수 없습니다.\n"
            "        먼저 --dry-run 없이 한 번 실행해 cuts_split.json 을 만들거나,\n"
            "        W7 산출물이 있는 run 을 쓰세요.")

    tmpl = (ROOT / "prompts" / "cut_split.txt").read_text(encoding="utf-8")
    prompt = (tmpl.replace("{episode_title}", ep.title)
                  .replace("{episode_summary}", ep.summary)
                  .replace("{cut_count}", str(args.cut_count)))
    client = make_text_client()
    print(f"[cut_split] {client.describe()} 호출 — {args.cut_count}컷 분해...")
    parsed, meta, secs = textgen.call_json(
        client, prompt, int(cfg["retry"]["max_retries"]), float(cfg["retry"]["backoff_sec"]),
        on_retry=lambda a, e: print(f"    재시도 {a}: {e[:160]}"))
    raw_cuts = parsed.get("cuts") if isinstance(parsed, dict) else parsed
    if not isinstance(raw_cuts, list) or not raw_cuts:
        die(f"cut_split 응답에서 cuts 를 찾지 못했습니다: {str(parsed)[:200]}")
    ep.cuts = [
        storyload.Cut(
            cut_number=int(c.get("cut_number") or i),
            description=str(c.get("description") or "").strip(),
            dialogue=str(c.get("dialogue") or "").strip(),
            reader_only=bool(c.get("reader_only")),
        )
        for i, c in enumerate(raw_cuts, 1)
    ]
    ep_dir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps({"cuts": [c.__dict__ for c in ep.cuts]},
                                ensure_ascii=False, indent=2), encoding="utf-8")
    log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                      "kind": "cut_split", "model": client.model, "duration_sec": secs,
                      "prompt": prompt,
                      "cuts": len(ep.cuts), "ok": True,
                      "provider": "gemini-text",
                      **cost_fields(cfg, client.model, "text", meta),
                      "provider_meta": meta})
    print(f"[cut_split] 컷 {len(ep.cuts)}개 ({secs}s) -> {rel(cache)}")
    return ep


# --------------------------------------------------------------------------- #
# 2. prompt_gen  (조건과 무관하게 화당 1회. 조건이 유일한 변수여야 하므로 공유한다)
# --------------------------------------------------------------------------- #
def cuts_fingerprint(ep: storyload.Episode) -> str:
    blob = json.dumps([[c.cut_number, c.description, c.dialogue] for c in ep.cuts],
                      ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def direction_fingerprint(ep: storyload.Episode) -> str:
    """연출만의 지문. 컷은 그대로인데 연출만 바뀐 경우를 캐시가 알아채야 한다."""
    # size / render_style 도 지문에 넣는다. 그림체만 바뀌어도 프롬프트가 달라지므로
    # 캐시를 그대로 쓰면 SD 로 바뀐 컷이 예전 프롬프트로 그려진다.
    blob = json.dumps([[c.cut_number, c.beat, c.gap_after, c.gaze, c.scene_break,
                        getattr(c, "size", ""), getattr(c, "render_style", "normal")]
                       for c in ep.cuts], ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def cut_rows(ep: storyload.Episode) -> list[dict[str, Any]]:
    """컷 → 뒤 단계가 쓰는 행 dict. 연출이 있으면 같이 실린다.

    ⚠️ **여기 안 적은 필드는 뒤 단계에 존재하지 않는다.** Scene 도 프롬프트도
    cast 판정도 전부 이 dict 를 보고, storyload.Cut 을 직접 보지 않는다.
    콘티가 필드를 늘리면 여기도 같이 늘려야 한다 — 실제로 zone 을 빠뜨려서
    배경 서술이 프롬프트에 한 번도 안 들어간 적이 있다(조용히, 오류 없이).
    """
    return [{"cut_number": c.cut_number, "description": c.description,
             "dialogue": c.dialogue, "reader_only": c.reader_only,
             "beat": c.beat, "gap_after": c.gap_after, "gaze": c.gaze,
             "scene_break": c.scene_break,
             "size": getattr(c, "size", ""),
             "render_style": getattr(c, "render_style", "normal"),
             # 텍스트 네 종류. 말풍선만으로 굴러가지 않는다.
             "narration": getattr(c, "narration", ""),
             "thought": getattr(c, "thought", ""),
             "sfx": getattr(c, "sfx", ""),
             # 한 컷에 말이 여러 줄일 수 있다. 위의 세 칸은 옛 형식이고, 이쪽이
             # 있으면 이쪽이 전부다 (storyload.speech_lines 가 둘을 하나로 본다).
             "lines": storyload.speech_lines(c),
             # 화면 안 글자 — 말풍선이 아니라 UI 로 합성된다.
             "screen_text": getattr(c, "screen_text", ""),
             # 배경을 잇는 자리. 이 값으로 존 서술이 프롬프트에 들어간다.
             "zone": getattr(c, "zone", ""),
             # 화면에 누가 있고 어떻게 보이는가.
             "characters_in_frame": list(getattr(c, "characters_in_frame", []) or []),
             "composition": getattr(c, "composition", "none"),
             "composition_note": getattr(c, "composition_note", ""),
             "bubble_zone": getattr(c, "bubble_zone", "none"),
             "speaker": getattr(c, "speaker", ""),
             "speaker_side": getattr(c, "speaker_side", ""),
             "shot": getattr(c, "shot", ""),
             "angle": getattr(c, "angle", "")} for c in ep.cuts]


def generate_prompts(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                     make_text_client) -> list[dict[str, Any]]:
    """[{cut_number, description, dialogue, reader_only, scene, warnings}] 반환."""
    cache = ep_dir / "prompts.json"
    fp = cuts_fingerprint(ep)
    dfp = direction_fingerprint(ep)

    if cache.exists() and not args.regen_prompts:
        data = json.loads(cache.read_text(encoding="utf-8"))
        if data.get("cuts_fingerprint") == fp and len(data.get("cuts") or []) == len(ep.cuts):
            if data.get("direction_fingerprint") == dfp:
                print(f"[prompt_gen] 캐시 사용: {rel(cache)} (컷 {len(ep.cuts)}개)")
                return data["cuts"]
            # 컷은 그대로고 연출만 바뀐 경우. 장면 서술은 다시 만들 필요가 없다 —
            # gaze 는 뒤 단계에서 붙고, gap/scene_break 은 그림 밖의 것이다.
            print("[prompt_gen] 컷은 그대로고 연출만 바뀌었습니다 — 장면 서술은 그대로 두고 "
                  "연출만 갱신합니다.")
            by_num = {int(c["cut_number"]): c for c in data["cuts"]}
            merged = [dict(by_num.get(c["cut_number"], {}), **c)
                      for c in cut_rows(ep)]
            data["cuts"], data["direction_fingerprint"] = merged, dfp
            cache.write_text(json.dumps(data, ensure_ascii=False, indent=2),
                             encoding="utf-8")
            return merged
        print("[prompt_gen] 캐시가 원본 컷과 달라 다시 생성합니다.")

    base = cut_rows(ep)

    if args.dry_run:
        print("[prompt_gen] dry-run — 텍스트 LLM 을 호출하지 않고 자리표시자를 씁니다.")
        return [dict(c, scene=DRY_SCENE, warnings=[]) for c in base]

    tmpl = (ROOT / "prompts" / "prompt_gen.txt").read_text(encoding="utf-8")
    cuts_json = json.dumps(
        [{"cut_number": c["cut_number"], "description": c["description"],
          "dialogue": c["dialogue"]} for c in base], ensure_ascii=False, indent=2)
    prompt = (tmpl.replace("{episode_title}", ep.title)
                  .replace("{cut_count}", str(len(base)))
                  .replace("{cuts_json}", cuts_json))

    client = make_text_client()
    print(f"[prompt_gen] {client.describe()} 호출 — 컷 {len(base)}개 한국어 서술 → 영어 장면...")
    started = time.time()
    try:
        parsed, meta, secs = textgen.call_json(
            client, prompt, int(cfg["retry"]["max_retries"]), float(cfg["retry"]["backoff_sec"]),
            on_retry=lambda a, e: print(f"    재시도 {a}: {e[:160]}"))
    except textgen.TextError as exc:
        log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                          "kind": "prompt_gen", "model": client.model,
                          "duration_sec": round(time.time() - started, 2),
                          "ok": False, "error": str(exc)})
        die(f"prompt_gen 실패: {exc}")

    panels = parsed.get("panels") if isinstance(parsed, dict) else parsed
    if not isinstance(panels, list):
        die(f"prompt_gen 응답에서 panels 를 찾지 못했습니다: {str(parsed)[:200]}")
    by_num = {}
    for p in panels:
        try:
            by_num[int(p["cut_number"])] = str(p.get("scene") or "").strip()
        except (KeyError, TypeError, ValueError):
            continue

    banned = list(cfg["prompt_gen"]["banned_terms"])
    out: list[dict[str, Any]] = []
    missing: list[int] = []
    for c in base:
        scene = by_num.get(c["cut_number"], "")
        if not scene:
            missing.append(c["cut_number"])
        warnings = lint_scene(scene, banned)
        out.append(dict(c, scene=scene, warnings=warnings))
        if warnings:
            print(f"    ! 컷 {c['cut_number']}: LLM 이 금지어를 썼습니다 → {', '.join(warnings)}")
    if missing:
        die(f"prompt_gen 이 컷 {missing} 의 장면을 돌려주지 않았습니다. "
            f"--regen-prompts 로 다시 시도하세요.")

    ep_dir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps({
        "run_id": ep.run_id, "episode": ep.episode, "title": ep.title, "source": ep.source,
        "text_model": client.model, "generated_at": datetime.now().isoformat(timespec="seconds"),
        "cuts_fingerprint": fp, "direction_fingerprint": dfp,
        "has_direction": ep.has_direction, "cuts": out,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                      "kind": "prompt_gen", "model": client.model, "duration_sec": secs,
                      "prompt": prompt,
                      "cuts": len(out), "ok": True,
                      "warned_cuts": [c["cut_number"] for c in out if c["warnings"]],
                      "provider": "gemini-text",
                      **cost_fields(cfg, client.model, "text", meta),
                      "provider_meta": meta})
    print(f"[prompt_gen] 완료 ({secs}s) -> {rel(cache)}")
    return out


# --------------------------------------------------------------------------- #
# 3. render
# --------------------------------------------------------------------------- #
@dataclass
class Job:
    condition: str
    cut_number: int          # Scene 모드에서는 Scene 번호
    candidate: int
    description: str
    dialogue: str
    scene: str
    prompt: str
    refs: list[Path]
    use_previous_cut: bool
    out_path: Path
    attachments: list[Path] = field(default_factory=list)
    # 이 컷의 캔버스 비율. **컷마다 다르다** — 콘티의 size(wide/normal/tall/
    # impact)가 곧 화면 모양이기 때문이다. 전역 aspect_ratio 하나로 다 뽑으면
    # wide 컷도 세로로 나온다 (실제로 그랬다).
    aspect: str = ""
    stem: str = "cut"        # 파일 이름 앞머리. Scene 모드는 "scene"
    unit: str = "컷"         # 화면 표시용 단위 이름


# 콘티의 size 를 캔버스 비율로. Gemini 가 받는 값 중에서 고른다
# (1:1 · 3:4 · 4:3 · 9:16 · 16:9 — 더 긴 세로는 400 이다. 실측 2026-08).
SIZE_ASPECT = {"wide": "16:9", "normal": "4:3", "tall": "3:4", "impact": "9:16"}


def cut_aspect(cut: dict[str, Any], default: str) -> str:
    """이 컷을 어떤 모양의 캔버스에 그릴까. size 가 없으면 전역값."""
    return SIZE_ASPECT.get(str(cut.get("size") or "").strip().lower(), default)


def composition_line(cfg: dict[str, Any], cut: dict[str, Any]) -> str:
    """컷 모드가 콘티에서 옮겨 싣는 연출 전부. Scene 모드의 panel_clauses 와 짝이다.

    두 모드가 어긋나면 안 되므로 같은 표를 쓴다. 여기 들어오기 전까지 컷 모드는
    **콘티의 연출을 거의 다 버렸다** — 텍스트 LLM 에 넘기는 것이
    {cut_number, description, dialogue} 셋뿐이라, 거리·앵글·시선·효과음이
    이미지 프롬프트에 도달하는 길이 아예 없었다. 그 결과 클로즈업 컷이 원경으로
    나오고, 콘티가 정한 효과음("쨍")이 그림 어디에도 안 나타났다.
    Scene 모드는 panel_clauses 로 이것들을 붙이고 있어서 컷 모드에서만 났다.
    """
    parts = (scenegen.shot_clause(cut) + scenegen.composition_clause(cut)
             + scenegen.gaze_clause(cut) + scenegen.sfx_clause(cfg, cut))
    return ("\n" + " ".join(parts)) if parts else ""


def write_strip(cfg, ep_dir, cuts, conditions, sheets,
                second_lead, rel, title: str = "") -> None:
    """컷 모드 결과물 — **진짜 세로 웹툰 한 편.**

    컷 하나가 이미지 하나이므로, 여기서 코드가 세 가지를 한다:
      1. 말풍선을 그리고 한글을 넣는다 (모델은 안 그린다 — 모양·꼬리·자리를
         전부 통제하려면 이 방법뿐이다)
      2. 컷 사이에 여백을 둔다 (gap_after. 콘티가 계산해 둔 값이다)
      3. 세로로 이어 붙인다

    Scene 모드와 달리 "이음매"가 없다 — 컷 사이에 흰 여백이 있는 것이
    웹툰의 원래 모습이라, 붙일 자리 자체가 없다.
    """
    if not cuts:
        return
    picks = load_picks(ep_dir)
    # --sheet-only 는 config 의 조건을 전부 훑으므로 첫 번째가 A(첨부 없음,
    # 폴더도 없음)일 수 있다. **이미지가 가장 많이 있는 조건**을 고른다 —
    # episode.pick_paths 와 같은 이유다.
    def hits(name: str) -> int:
        return sum(1 for c in cuts
                   if (ep_dir / name / f"cut{int(c['cut_number'])}_c1.png").exists())
    cond = max(conditions, key=hits) if conditions else ""
    if not cond or not hits(cond):
        print("[경고] 이어 붙일 컷 이미지가 없습니다 "
              f"(찾아본 조건: {', '.join(conditions) or '없음'}).")
        return
    items, notes, missing = [], [], 0
    for c in cuts:
        n = int(c["cut_number"])
        k = picks.get((cond, n)) or 1
        src = ep_dir / cond / f"cut{n}_c{k}.png"
        if not src.exists():
            src = ep_dir / cond / f"cut{n}_c1.png"
        if not src.exists():
            missing += 1
            continue
        try:
            from PIL import Image
            im = Image.open(src)
            im.load()
        except OSError as exc:
            print(f"[경고] 컷 {n} 을 읽지 못했습니다: {exc}")
            missing += 1
            continue
        items.append((im, c, strip.gap_px(im.width, c.get("gap_after"))))

    # ---- 말풍선 ------------------------------------------------------------
    # 지금은 **이미지 모델이 그린다** (strip.draw_text_clause 로 프롬프트에 한글
    # 대사와 풍선 모양을 실어 보낸다). 그래서 여기서 덧그리지 않는다 — 그리면
    # 풍선이 두 개가 된다.
    #
    # 코드가 그리는 길도 남아 있다(strip.compose_cut + vision). 한글이 깨지거나
    # 풍선이 얼굴을 덮으면 그쪽으로 되돌린다: draw_text_clause 를
    # NO_BUBBLE_CLAUSE 로 바꾸고 아래 주석을 푼다.
    #
    #   layout, cache = vision.load_layout(ep_dir), vision.load_cache(ep_dir)
    #   ... vision.locate → vision.place → strip.draw_bubble ...
    wtab = dict((cfg.get("scene") or {}).get("width_ratio") or {})
    drawn = [(im.convert("RGB"), gap, strip.width_ratio(c, wtab))
             for im, c, gap in items]
    items = drawn
    if not items:
        print("[경고] 이어 붙일 컷이 없습니다.")
        return

    out = ep_dir / strip.EPISODE_FILE
    try:
        w, h = strip.stitch_strip(items, out)
    except strip.StripError as exc:
        print(f"[경고] 1화를 이어 붙이지 못했습니다: {exc}")
        return
    note = f" — {missing}컷 빠짐" if missing else ""
    print(f"{strip.EPISODE_FILE}              -> {rel(out)} "
          f"({cond} · {len(items)}컷 · {w}x{h}px{note})")
    # 완성본을 보면서 말풍선을 끌어 고치는 화면. 좌표만 고치고 다시 조립하면
    # 이미지 재생성 없이(0원) 반영된다.
    try:
        view = stripview.build(
            ep_dir, {"run_id": getattr(sheets, "run_dir", Path(".")).name,
                     "episode": ep_dir.name.replace("ep", ""),
                     "title": title}, cuts, cond)
        print(f"{stripview.VIEW_FILE}               -> {rel(view)} "
              f"(말풍선을 끌어 고치고 [내려받기])")
    except Exception as exc:                       # 화면은 부수적이다
        print(f"[경고] 편집 화면을 만들지 못했습니다: {exc}")
    for x in notes:
        print(f"    ! {x}")


# 직전 컷이 첨부될 때 코드가 박는 문구 (조건 S+ · D 의 컷 모드).
#
# config 의 조건 extra 는 Scene 모드 기준으로 쓰여 있어서("이 시트 바로 위에
# 붙는 장"), 컷 모드에서 그대로 나가면 첨부의 정체를 틀리게 설명한다. 그래서
# 코드가 뒤에 덧붙이고, 충돌하면 이쪽이 이긴다고 못박는다.
#
# **채색을 맞추라고 말하는 자리가 여기다.** 조건 S(직전 컷 없음)로 한 화를 뽑아
# 봤더니 컷마다 채색이 갈렸다 — 어떤 컷은 순수 선화, 어떤 컷은 피부까지 칠해져
# 나왔다. 컷들이 서로를 아예 못 보고 각자 시트만 보고 그려졌기 때문이다.
PREV_CUT_CLAUSE = (
    "CONTINUITY WITH THE PREVIOUS PANEL — this overrides any wording above about "
    "what the attached images are. The LAST attached image is the PREVIOUS PANEL "
    "of this same episode, drawn immediately before this one. Match its rendering: "
    "the same line weight and line quality, the same choices about what is filled "
    "solid and what is left as bare white paper, the same amount of tone, the same "
    "treatment of skin, hair and clothing, and the same colour discipline. Two "
    "consecutive panels must look like the same artist drew them minutes apart — "
    "if that panel left skin white, leave skin white here too. "
    "Do NOT copy its composition, its framing, its camera distance or its poses; "
    "this panel has its own framing described above. Do not copy the main "
    "character's face from it either — the character sheet decides that.")


def build_jobs(cfg: dict[str, Any], appearance: str, cuts: list[dict[str, Any]],
               conditions: list[str], ep_dir: Path,
               present: dict[int, bool] | None = None,
               second_lead: "charsheet.Sheet | None" = None,
               present_2nd: dict[int, bool] | None = None,
               zone_text: dict[str, str] | None = None,
               episode_cut_numbers: list[int] | None = None) -> list[Job]:
    """present: {컷 번호: 주인공이 화면에 있는가}. 없는 컷에는 주인공의 외형 문구도
    디자인 고정 문구도 캐릭터 시트도 붙이지 않는다 — 붙이면 조연이 주인공 얼굴로
    그려진다.

    episode_cut_numbers: 이 화의 **전체** 컷 번호. --cuts 로 일부만 뽑을 때 "직전
    컷이 있는가"를 여기서 판단한다. 필터된 목록의 첫 항목을 첫 컷으로 보면,
    `--cuts 3` 으로 3번만 다시 뽑을 때 직전 컷(2번)이 실제로는 첨부되는데도
    설명 문구가 빠진다 — resolve_attachments 는 전체 번호로 찾기 때문이다."""
    candidates = int(cfg["candidates_per_cut"])
    base_aspect = str((cfg["provider"].get("options") or {}).get("aspect_ratio")
                      or "9:16")
    jobs: list[Job] = []
    for cname in conditions:
        cond = cfg["conditions"][cname]
        refs = [ref_path(r) for r in (cond.get("refs") or [])]
        # COND_D 의 체인이 성립하려면 컷 순서대로 돌아야 한다.
        lead_refs = ([ref_path(second_lead.paths[k]) for k in second_lead.kinds()]
                     if second_lead is not None and second_lead.has_images else [])
        uses_prev = bool(cond.get("use_previous_cut"))
        all_numbers = list(episode_cut_numbers or
                           [int(c["cut_number"]) for c in cuts])
        first_number = all_numbers[0] if all_numbers else None
        for cut in cuts:
            here = (present or {}).get(int(cut["cut_number"]), True)
            here_2nd = bool(lead_refs) and (present_2nd or {}).get(
                int(cut["cut_number"]), False)
            kind = str(cut.get("render_style") or "normal")
            # 직전 컷이 붙는 조건(S+ · D)에서는 그것이 무엇인지 코드가 말한다.
            # config 의 S+ extra 는 Scene 모드 문장("이 시트 위에 붙는 장")이라
            # 컷 모드에서는 첨부의 정체를 틀리게 설명한다. 첫 컷은 직전이 없다.
            has_prev = uses_prev and int(cut["cut_number"]) != first_number
            prev_note = ("\n" + PREV_CUT_CLAUSE) if has_prev else ""
            # 조연 대조는 **한글 서술**로 한다. 영어 프롬프트(cut["scene"])에는
            # 이름이 로마자로 바뀌거나 "the companion" 으로 뭉개져 있다 — 실제로
            # 윤재가 "The companion" 으로만 나왔다.
            zid = str(cut.get("zone") or "").strip()
            prompt = assemble(cfg, appearance if here else ABSENT_NOTE, cut["scene"],
                              cond_extra(cfg, cond, kind, here and bool(refs),
                                         str(cut.get("description") or ""),
                                         second_lead if here_2nd else None,
                                         zone_text=(zone_text or {}).get(zid, ""),
                                         uses_previous=uses_prev)
                              + prev_note
                              + "\n" + strip.text_clause(cut, scenegen.lettering(cfg))
                              + composition_line(cfg, cut),
                              with_lock=here, render_style=kind)
            for k in range(1, candidates + 1):
                jobs.append(Job(
                    condition=cname,
                    cut_number=cut["cut_number"],
                    candidate=k,
                    description=cut["description"],
                    dialogue=cut.get("dialogue") or "",
                    scene=cut["scene"],
                    prompt=prompt,
                    refs=(refs if here else []) + (lead_refs if here_2nd else []),
                    # 주인공이 없는 컷은 직전 컷 체인에서도 빠진다. 주인공이 안 나오는
                    # 컷을 참조로 물려주면 다음 컷의 얼굴 기준이 흐려진다.
                    use_previous_cut=bool(cond.get("use_previous_cut")) and here,
                    out_path=ep_dir / cname / f"cut{cut['cut_number']}_c{k}.png",
                    aspect=cut_aspect(cut, base_aspect),
                ))
    return jobs


def scene_cond(name: str) -> str:
    """Scene 모드의 조건 이름. picks.csv 와 출력 폴더에서 컷 모드와 구분되는 유일한 표식."""
    return f"scene_{name}"


def grouping_mode(cfg: dict[str, Any], ep: storyload.Episode) -> str:
    """이 run 의 Scene 경계를 무엇이 정하는가 — "direction" 또는 "fixed".

    기본은 예전 그대로다: 연출(W7.5)이 있으면 그 리듬(scene_break)이 경계를
    정하고, 없으면 개수로 자른다. 개수로 자르면 경계가 아무 데나 떨어지므로
    그쪽이 예비 경로인 것도 그대로다.

    바뀐 것은 **연출이 있어도 개수로 자를 수 있게** 한 것뿐이다
    (`scene.grouping: fixed`). 한 장에 정확히 N컷을 넣어야 하는 쓰임이 있는데
    상한(max_cuts_per_scene)만으로는 그게 안 된다 — 상한은 큰 묶음을 **고르게**
    쪼개므로 4컷 묶음에 상한 3을 걸면 3+1 이 아니라 2+2 가 되고, 리듬이 2씩
    끊어 놓은 자리는 상한을 아무리 올려도 2로 남는다.

    리듬을 버리는 대가는 분명하다 — 설명하다 만 자리에서 장이 넘어갈 수 있다.
    그래서 기본값은 건드리지 않았고, config 에 명시해야만 켜진다.
    """
    mode = str((cfg.get("scene") or {}).get("grouping") or "rhythm").strip().lower()
    if mode not in ("rhythm", "fixed"):
        die(f'config.yaml 의 scene.grouping 값 "{mode}" 를 모릅니다. '
            f"rhythm(연출 리듬이 경계를 정함) 또는 fixed(개수로 고정) 여야 합니다.")
    return "direction" if (mode == "rhythm" and ep.has_direction) else "fixed"


def group_scenes(cfg: dict[str, Any], ep: storyload.Episode,
                 base: list[dict[str, Any]]) -> list[scenegen.Scene]:
    """컷을 Scene 으로 묶는다. 세 곳(본 생성 · verify-all · probe)이 같이 쓴다."""
    per = int(cfg["scene"]["cuts_per_scene"])
    max_per = int(cfg["scene"].get("max_cuts_per_scene") or 0)
    if grouping_mode(cfg, ep) == "direction":
        return scenegen.group_by_break(base, max_per)
    return scenegen.group(base, min(per, max_per) if max_per else per)


def generate_scenes(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                    make_text_client) -> list[scenegen.Scene]:
    """컷을 Scene 으로 묶고 패널 서술을 만든다. 조건과 무관하게 화당 1회 (캐시)."""
    per = int(cfg["scene"]["cuts_per_scene"])
    max_per = int(cfg["scene"].get("max_cuts_per_scene") or 0)
    base = cut_rows(ep)
    cache = ep_dir / scenegen.CACHE_FILE
    fp = cuts_fingerprint(ep)
    dfp = direction_fingerprint(ep)

    if cache.exists() and not args.regen_prompts:
        data = json.loads(cache.read_text(encoding="utf-8"))
        # 묶는 방식이 바뀌면 캐시를 버려야 한다. 상한(max_cuts_per_scene)은 연출이
        # 있는 run 에도 걸리므로 두 경로 모두에서 본다 — 이걸 빼 두면 config 를
        # 고쳐도 예전 묶음 그대로 돌아 "고쳤는데 안 바뀌네"가 된다.
        mode = grouping_mode(cfg, ep)
        same_group = (
            data.get("grouping", "direction" if ep.has_direction else "fixed") == mode
            and (data.get("direction_fingerprint") == dfp if mode == "direction"
                 else int(data.get("cuts_per_scene") or 0) == per)
            and int(data.get("max_cuts_per_scene") or 0) == max_per)
        if data.get("cuts_fingerprint") == fp and same_group:
            try:
                scenes = scenegen.from_json(data.get("scenes") or [], base)
            except scenegen.SceneError as exc:
                die(str(exc))
            print(f"[scene_gen] 캐시 사용: {rel(cache)} (Scene {len(scenes)}개)")
            return scenes
        print("[scene_gen] 캐시가 원본 컷/묶음과 달라 다시 생성합니다.")

    try:
        # Scene 경계는 연출이 정한다 (W7.5 의 scene_break). 개수로 자르는 것은
        # 연출이 없는 run 의 예비 경로다 — 경계가 아무 데나 떨어진다.
        scenes = group_scenes(cfg, ep, base)
        # 레이아웃은 캐시가 없을 때만 새로 고른다. 이미 생성한 이미지와 어긋나면
        # "레이아웃 때문인지 조건 때문인지"를 구분할 수 없게 된다.
        scenegen.assign_layouts(scenes, list(cfg["scene"]["layout_templates"] or []),
                                str(cfg["scene"]["layout_pick"]),
                                f"{ep.run_id}:ep{ep.episode}")
    except scenegen.SceneError as exc:
        die(str(exc))

    sizes = ", ".join(str(len(sc.cuts)) for sc in scenes)
    how = ("연출 기준" if grouping_mode(cfg, ep) == "direction"
           else f"{min(per, max_per) if max_per else per}개씩 고정")
    print(f"[scene_gen] 컷 {len(base)}개 → Scene {len(scenes)}개 (묶음 {sizes} · {how})")
    if grouping_mode(cfg, ep) == "direction":
        ends = " / ".join(f"S{sc.scene_number}:{sc.beats[-1] or '?'}" for sc in scenes)
        print(f"    · 각 Scene 이 끝나는 beat — {ends}")
    elif scenes and len(scenes[-1].cuts) == 1 and per > 1:
        print(f"    · 마지막 Scene 이 1컷뿐입니다 — 패널 1개짜리 '페이지'가 됩니다. "
              f"cuts_per_scene 을 바꾸면 더 고르게 나뉩니다.")

    # 무대가 비면 패널마다 배경이 새로 정해진다 — 장소가 다르면 배경 사람도
    # 달라지고, 그러면 같은 곳으로 안 읽힌다. dry-run 앞에 둔다: 0원으로 미리
    # 알아야 하는 값이고, 알고 나면 뽑기 전에 되돌아갈 수 있다.
    if not (ep.setting or {}).get("place"):
        print("[경고] 이 화에 무대(setting.place)가 없습니다 — 장소·시간·조명이 "
              "패널마다 새로 정해집니다.\n"
              "        배경이 흔들리면 배경 사람들(지나가는 학생·행인)도 같이 "
              "흔들립니다. 같은 장소로 안 읽힙니다.\n"
              "        story-harness 의 W5 가 setting 을 채우게 되어 있으므로, "
              "그 값을 쓰려면 story run 을 새로 뽑으세요.")

    if args.dry_run:
        print("[scene_gen] dry-run — 텍스트 LLM 을 호출하지 않고 자리표시자를 씁니다.")
        for sc in scenes:
            sc.panels = [DRY_SCENE] * len(sc.cuts)
        return scenes

    tmpl = (ROOT / "prompts" / "scene_gen.txt").read_text(encoding="utf-8")
    client = make_text_client()
    print(f"[scene_gen] {client.describe()} 호출 — Scene {len(scenes)}개 패널 서술...")
    started = time.time()
    try:
        sg_prompt = scenegen.build_prompt(tmpl, ep.title, scenes, ep.setting,
                                          ep.scenes, cfg)
        parsed, meta, secs = textgen.call_json(
            client, sg_prompt,
            int(cfg["retry"]["max_retries"]), float(cfg["retry"]["backoff_sec"]),
            on_retry=lambda a, e: print(f"    재시도 {a}: {e[:160]}"))
    except textgen.TextError as exc:
        log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                          "kind": "scene_gen", "model": client.model,
                          "duration_sec": round(time.time() - started, 2),
                          "ok": False, "error": str(exc)})
        die(f"scene_gen 실패: {exc}")

    try:
        missing = scenegen.fill_panels(scenes, parsed)
    except scenegen.SceneError as exc:
        die(str(exc))
    if missing:
        die(f"scene_gen 이 Scene {missing} 의 패널을 다 돌려주지 않았습니다. "
            f"--regen-prompts 로 다시 시도하세요.")

    banned = list(cfg["prompt_gen"]["banned_terms"])
    for sc in scenes:
        sc.warnings = lint_scene(" ".join(sc.panels), banned)
        if sc.warnings:
            print(f"    ! Scene {sc.scene_number}: LLM 이 금지어를 썼습니다 "
                  f"→ {', '.join(sc.warnings)}")

    ep_dir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps({
        "run_id": ep.run_id, "episode": ep.episode, "title": ep.title,
        "text_model": client.model, "generated_at": datetime.now().isoformat(timespec="seconds"),
        "cuts_fingerprint": fp, "direction_fingerprint": dfp,
        "grouping": grouping_mode(cfg, ep),
        "cuts_per_scene": per, "max_cuts_per_scene": max_per,
        "scenes": scenegen.to_json(scenes),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                      "kind": "scene_gen", "model": client.model, "duration_sec": secs,
                      "prompt": sg_prompt,
                      "scenes": len(scenes), "cuts_per_scene": per, "ok": True,
                      "grouping": grouping_mode(cfg, ep),
                      "scene_sizes": [len(sc.cuts) for sc in scenes],
                      "warned_scenes": [s.scene_number for s in scenes if s.warnings],
                      "provider": "gemini-text",
                      **cost_fields(cfg, client.model, "text", meta),
                      "provider_meta": meta})
    print(f"[scene_gen] 완료 ({secs}s) -> {rel(cache)}")
    return scenes


def scene_has_main(sc: scenegen.Scene, present: dict[int, bool] | None) -> bool:
    """Scene 한 장에 주인공이 나오는가 = 묶인 컷 중 하나라도 나오면 나온다.

    Scene 은 컷 여러 개가 한 장에 구워진다. 그중 한 패널에만 주인공이 있어도
    시트는 붙어야 한다 — 대신 "시트는 주인공에게만 적용" 문구가 나머지 패널의
    인물을 지켜 준다.
    """
    if not present:
        return True
    return any(present.get(n, True) for n in sc.cut_numbers)


def build_scene_jobs(cfg: dict[str, Any], appearance: str, scenes: list[scenegen.Scene],
                     conditions: list[str], ep_dir: Path,
                     present: dict[int, bool] | None = None,
                     second_lead: "charsheet.Sheet | None" = None,
                     present_2nd: dict[int, bool] | None = None,
                     zone_text: dict[str, str] | None = None) -> list[Job]:
    """second_lead/present_2nd 는 '그 한 사람' 시트가 있을 때만 넘어온다
    (주연만 시트를 뽑는다 — 조연 전원이 아니다). 주인공 refs 와는 독립적으로
    그 장면에 그 인물이 나올 때만 붙는다.

    zone_text 는 {zone_id: 그 자리의 서술} — **이미지가 아니라 글이다.**
    묶인 컷이 전부 같은 존일 때만(scenegen.scene_zone) 그 서술을 프롬프트에
    넣는다. 컷마다 존이 갈리는 Scene 은 넣지 않는다 — 한 장에 두 자리가 섞여
    있는데 한쪽 서술만 주면 나머지 패널이 그쪽으로 끌려간다.
    """
    candidates = int(cfg["scene"]["candidates_per_scene"])
    jobs: list[Job] = []
    lead_refs = ([ref_path(second_lead.paths[k]) for k in second_lead.kinds()]
                if second_lead is not None and second_lead.has_images else [])
    for cname in conditions:
        cond = cfg["conditions"][cname]
        refs = [ref_path(r) for r in (cond.get("refs") or [])]
        # COND_D 의 체인이 성립하려면 Scene 순서대로 돌아야 한다.
        # 앞뒤 장을 같이 넘긴다 — 이 장들은 틈 없이 세로로 붙는다.
        for i, sc in enumerate(scenes):
            here = scene_has_main(sc, present)
            here_2nd = bool(lead_refs) and scene_has_main(sc, present_2nd)
            zid = scenegen.scene_zone(sc)
            kind = scenegen.render_style(sc)
            prompt = scenegen.assemble(
                cfg, appearance if here else ABSENT_NOTE, sc,
                cond_extra(cfg, cond, kind, here and bool(refs), sc.description(),
                          second_lead if here_2nd else None,
                          zone_text=(zone_text or {}).get(zid, ""),
                          uses_previous=bool(cond.get("use_previous_cut")) and i > 0),
                with_lock=here, style_text=style_block(cfg, kind),
                prev=scenes[i - 1] if i else None,
                nxt=scenes[i + 1] if i + 1 < len(scenes) else None)
            for k in range(1, candidates + 1):
                jobs.append(Job(
                    condition=scene_cond(cname),
                    cut_number=sc.scene_number,
                    candidate=k,
                    description=sc.description(),
                    dialogue=sc.dialogue(),
                    scene="\n".join(f"Panel {i}: {p}" for i, p in enumerate(sc.panels, 1)),
                    prompt=prompt,
                    refs=(refs if here else []) + (lead_refs if here_2nd else []),
                    use_previous_cut=bool(cond.get("use_previous_cut")) and here,
                    out_path=ep_dir / scene_cond(cname) / f"scene{sc.scene_number}_c{k}.png",
                    stem="scene",
                    unit="Scene",
                ))
    return jobs


def scene_rows(cfg: dict[str, Any], scenes: list[scenegen.Scene]) -> list[dict[str, Any]]:
    """Scene → contact sheet / viewer 가 쓰는 행 dict.

    레이아웃은 scenes.json 에 캐시된 예비 템플릿이 아니라 지금 프롬프트에 실제로
    들어가는 문구를 보여준다. 시트에 적힌 것과 실제가 다르면 시트를 볼 이유가 없다.
    """
    return [{
        "cut_number": sc.scene_number,
        "scene_number": sc.scene_number,
        "cut_numbers": sc.cut_numbers,
        "label": sc.label,
        "note": (f"{sc.label} · 패널 {len(sc.cuts)}개 · 크기 "
                 f"{'/'.join(str(c.get('size') or '?') for c in sc.cuts)}\n"
                 f"{scenegen.layout_text(cfg, sc)}"),
        "description": sc.description(),
        "dialogue": sc.dialogue(),
        "reader_only": sc.reader_only(),
        "scene": "\n".join(f"Panel {i}: {p}" for i, p in enumerate(sc.panels, 1)),
        "warnings": sc.warnings,
    } for sc in scenes]


def resolve_attachments(job: Job, ep_dir: Path, picks: dict[tuple[str, int], int],
                        cut_numbers: list[int], quiet: bool = False) -> list[Path]:
    """COND_D: 턴어라운드 + 직전 컷의 채택 이미지. 채택 기록이 없으면 후보 1번.

    Scene 모드에서는 "직전 컷"이 "직전 Scene" 이 된다 (job.stem / job.unit).
    """
    paths = list(job.refs)
    if not job.use_previous_cut:
        return paths
    idx = cut_numbers.index(job.cut_number)
    if idx == 0:
        return paths  # 첫 컷은 직전 컷이 없다
    prev = cut_numbers[idx - 1]
    picked = picks.get((job.condition, prev))
    cand = picked or 1
    path = ep_dir / job.condition / f"{job.stem}{prev}_c{cand}.png"
    if not path.exists() and picked:
        if not quiet:
            print(f"    ! 채택 기록({job.condition} {job.unit}{prev} c{picked})의 파일이 "
                  f"없어 c1 을 씁니다.")
        path = ep_dir / job.condition / f"{job.stem}{prev}_c1.png"
    if path.exists():
        paths.append(path)
        if not quiet and not picked:
            print(f"    · {job.unit}{prev} 채택 기록이 없어 후보 1번을 첨부합니다.")
    elif not quiet:
        print(f"    ! 직전 {job.unit} 이미지({rel(path)})가 없어 턴어라운드만 첨부합니다.")
    return paths


def run_job(job: Job, provider, cfg: dict[str, Any], ep_dir: Path,
            picks: dict[tuple[str, int], int], cut_numbers: list[int],
            image_model: str = "", env: dict[str, str] | None = None) -> bool:
    max_retries = int(cfg["retry"]["max_retries"])
    backoff = float(cfg["retry"]["backoff_sec"])

    # 컷마다 캔버스 모양이 다르면 그 컷 전용 provider 를 만든다. provider 는
    # 만들 때 옵션이 굳으므로 하나를 돌려 쓰면 전역 비율로만 뽑힌다.
    base = dict(cfg["provider"].get("options") or {})
    if job.aspect and job.aspect != str(base.get("aspect_ratio") or ""):
        try:
            provider = build_provider(
                name=str(cfg["provider"]["name"]), model=image_model,
                api_key=(env_key(env or {}, str(cfg["provider"]["api_key_env"]))
                         if str(cfg["provider"]["name"]) != "mock" else None),
                options=dict(base, aspect_ratio=job.aspect))
            print(f"    · 캔버스 {job.aspect} (size 에서)")
        except Exception as exc:
            print(f"    ! 비율 {job.aspect} 로 바꾸지 못했습니다: {exc}")

    job.attachments = resolve_attachments(job, ep_dir, picks, cut_numbers)

    for attempt in range(1, max_retries + 2):
        started = time.time()
        ok, err, meta, retryable = False, None, {}, True
        try:
            result = provider.generate(GenRequest(prompt=job.prompt, images=job.attachments))
            job.out_path.parent.mkdir(parents=True, exist_ok=True)
            job.out_path.write_bytes(result.image_bytes)
            ok, meta = True, result.meta
        except ProviderError as exc:
            err, retryable = str(exc), exc.retryable
        except Exception as exc:  # provider 구현 밖의 예기치 못한 오류
            err = f"{type(exc).__name__}: {exc}"

        elapsed = round(time.time() - started, 2)
        log_line(ep_dir, {
            "timestamp": datetime.now().isoformat(timespec="seconds"),
            "kind": "image",
            "mode": "scene" if job.stem == "scene" else "cut",
            "condition": job.condition,
            "cut_number": job.cut_number,
            "candidate": job.candidate,
            "attempt": attempt,
            "description_ko": job.description,
            "dialogue_ko": job.dialogue,
            "scene_en": job.scene,
            "prompt": job.prompt,
            "attachments": [rel(p) for p in job.attachments],
            "provider": provider.name,
            "model": provider.model,
            "duration_sec": elapsed,
            "ok": ok,
            "error": err,
            # 실패한 호출도 토큰은 청구된다 — ok=False 여도 그대로 남긴다.
            **cost_fields(cfg, provider.model, "image", meta, ok),
            "output_path": rel(job.out_path) if ok else None,
            "provider_meta": meta or None,
        })

        if ok:
            print(f"    OK ({elapsed}s) -> {rel(job.out_path)}")
            return True
        print(f"    실패 (시도 {attempt}/{max_retries + 1}, {elapsed}s): {err}")
        if not retryable:
            print("    재시도해도 소용없는 오류라 건너뜁니다.")
            return False
        if attempt <= max_retries:
            wait = backoff * (2 ** (attempt - 1))
            print(f"    {wait:.0f}초 후 재시도...")
            time.sleep(wait)
    return False


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="웹툰 컷 이미지 생성 하네스")
    p.add_argument("--run-id", required=True, help="스토리 하네스 run 폴더 이름")
    p.add_argument("--episode", type=int, required=True, help="화 번호 (절대 번호, 1부터)")
    p.add_argument("--cuts", default="", metavar="N 또는 A-B",
                   help="컷 모드에서 이 컷들만 생성 (예: 3 또는 1-3). "
                        "새 방식을 몇 컷만 확인할 때 — 12컷을 다 뽑고 나서 "
                        "틀린 걸 알면 그만큼이 버려집니다")
    p.add_argument("--mode", choices=("cut", "scene", "both"), default="cut",
                   help="cut=컷 1개당 이미지 1장(기본), scene=컷 여러 개를 한 장으로, both=둘 다")
    p.add_argument("--condition", "-c", action="append", default=[],
                   help="실행할 조건 (여러 번 지정 가능, 쉼표 구분도 허용)")
    p.add_argument("--all-conditions", action="store_true", help="config 의 모든 조건 실행")
    p.add_argument("--dry-run", action="store_true", help="프롬프트 조립만 출력. API 호출 없음")
    p.add_argument("--skip-existing", action="store_true", help="이미 있는 출력 파일은 건너뜀")
    p.add_argument("--sheet-only", action="store_true",
                   help="생성 없이 contact_sheet.html 만 다시 만듦 (picks.csv 반영)")
    p.add_argument("--sfx-test", nargs="?", type=int, const=1, default=None,
                   metavar="SCENE",
                   help="Scene 1개만 새 프롬프트로 뽑아 빈 말풍선/효과음을 확인 "
                        "(기본 Scene 1). 기존 출력은 건드리지 않고 probe/ 에 저장")
    p.add_argument("--directed", action="store_true",
                   help="--view 와 같이: 연출 여백을 적용한 viewer_*_directed.html 도 만듦 "
                        "(이미지는 그대로 — 여백만 다시 배치합니다)")
    p.add_argument("--view", action="store_true",
                   help="생성 없이 채택 컷을 세로로 이어붙인 viewer_<조건>.html 만듦 "
                        "(컷 크기는 layout.json)")
    p.add_argument("--style-lock", action="store_true",
                   help="생성 없이 Scene 사이 그림체 일관성 확인 화면 style_lock.html + "
                        "빈 채점표 style_score.csv 만듦 (조건 1개, API 호출 0회)")
    p.add_argument("--verify-all", action="store_true",
                   help="Scene 전체를 현재 scene_gen.txt 로 후보 몇 장씩만 뽑아 "
                        "그림체·말풍선·효과음·통독을 한 벌로 검증 (verify/ 에 저장, "
                        "기존 scene_<조건>/ 은 그대로). --dry-run 으로 프롬프트만 확인 가능")
    p.add_argument("--regen-prompts", action="store_true", help="prompts.json 캐시를 무시하고 다시 생성")
    p.add_argument("--cut-count", type=int, default=10, help="예비 경로(cut_split)에서 만들 컷 수")
    p.add_argument("--yes", "-y", action="store_true", help="확인 프롬프트 건너뛰기")
    p.add_argument("--candidates", type=int, default=None, metavar="N",
                   help="단위당 후보 장수를 이번 실행에만 N 으로 바꿉니다 "
                        "(config 의 candidates_per_cut / scene.candidates_per_scene "
                        "/ scene.verify_candidates_per_scene 을 모두 덮어씀). "
                        "후보를 고르지 않고 한 벌만 보려면 --candidates 1")
    p.add_argument("--style", default=None, metavar="이름",
                   help="그림체를 config.yaml 의 styles 에서 골라 씁니다 "
                        "(예: --style romance). 생략하면 style_default")
    p.add_argument("--config", default=str(ROOT / "config.yaml"), help="설정 파일 경로")
    return p.parse_args()


def select_conditions(args, cfg: dict[str, Any]) -> list[str]:
    available = list(cfg["conditions"].keys())
    if args.all_conditions or args.sheet_only:
        return available
    picked: list[str] = []
    for item in args.condition:
        picked.extend(x.strip() for x in str(item).split(",") if x.strip())
    if not picked:
        die(f"--condition A 또는 --all-conditions 중 하나는 필요합니다. "
            f"사용 가능한 조건: {', '.join(available)}")
    unknown = [c for c in picked if c not in cfg["conditions"]]
    if unknown:
        die(f"config.yaml 에 없는 조건: {', '.join(unknown)} (사용 가능: {', '.join(available)})")
    seen: list[str] = []
    for c in picked:
        if c not in seen:
            seen.append(c)
    return seen


def resolve_cast(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                 sheet: charsheet.Sheet) -> tuple[dict[int, bool], dict[int, str]]:
    """컷별 "주인공이 화면에 있는가". cast.json > prompts.json > 이름 대조 순."""
    rows = cut_rows(ep)
    # prompts.json 이 있으면 거기 남은 판정도 본다 (LLM 이 남겼다면).
    cache = ep_dir / "prompts.json"
    if cache.exists():
        try:
            cached = json.loads(cache.read_text(encoding="utf-8")).get("cuts") or []
            flags = {int(c["cut_number"]): c.get("main_present") for c in cached}
            for r in rows:
                v = flags.get(int(r["cut_number"]))
                if isinstance(v, bool):
                    r["main_present"] = v
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            pass

    name = sheet.name or str(cfg.get("character_name") or "")
    try:
        for w in cast.warnings_for(ep_dir, rows):
            print(f"[경고] {w}")
        present, source = cast.resolve(ep_dir, rows, name)
    except cast.CastError as exc:
        die(str(exc))

    if not cast.name_keys(name):
        print("[cast] 주인공 이름을 몰라 컷을 가르지 못합니다 "
              "(p1.json 의 name 또는 config 의 character_name). "
              "모든 컷에 외형·시트를 붙입니다.")
        return present, source

    path, created = cast.write_draft(ep_dir, rows, present, name)
    off = [n for n in sorted(present) if not present[n]]
    print(f"[cast] 주인공 「{name}」 · 나오는 컷 {len(present) - len(off)}/{len(present)}"
          + (f" · 없는 컷 {', '.join(str(n) for n in off)}" if off else ""))
    if created:
        print(f"[cast] {rel(path)} 초안을 만들었습니다 — 판정이 틀렸으면 이 파일의 "
              f"true/false 만 고치세요 (API 호출 없음).")
    else:
        print(f"[cast] {rel(path)} 의 값을 우선 씁니다.")
    return present, source


def apply_charsheet(cfg: dict[str, Any], sheet: charsheet.Sheet,
                    conditions: list[str], fatal: bool = True) -> None:
    """조건의 refs 를 채택된 캐릭터 시트로 바꿔 끼운다 (config 를 제자리에서 고친다).

    조건이 sheets: [turnaround, ...] 를 선언하면 **그 run 의 시트만** 붙는다.
    없으면 세운다. config 의 refs 로 떨어지지 않는다.

    예전에는 시트가 없으면 config 의 refs (refs/turnaround.png) 로 조용히
    떨어졌다. 그 자리에는 지난번 run 의 시트가 남아 있기 마련이고, 조건 문구는
    "얼굴·머리·체형·의상을 첨부 시트와 똑같이 하라"고 명령한다 — 즉 **다른
    사람을 똑같이 그리라고** 시키게 된다. 실제로 그렇게 한 화가 통째로 날아갔다
    (민시하 13컷에 제라프 턴어라운드가 붙었다). 화면에 한 줄 찍히기는 했지만
    경고가 아니라 안내처럼 보였고, 결과를 보기 전에는 아무도 몰랐다.

    돈이 나가기 전에 멈추는 편이 언제나 싸다.

    fatal=False 는 이미지를 한 장도 만들지 않는 경로(--sheet-only)를 위한 것이다.
    그때는 세울 이유가 없다 — 시트가 없는 조건은 열만 비게 되고, 다른 조건의
    결과를 보려고 시트를 다시 만드는 일까지 막을 필요는 없다.
    """
    for cname in conditions:
        cond = cfg["conditions"][cname]
        want = [str(k).strip() for k in (cond.get("sheets") or []) if str(k).strip()]
        if not want:
            continue

        unknown = [k for k in want if k not in charsheet.KINDS]
        if unknown:
            die(f"config.yaml 의 조건 {cname} 에 모르는 시트 종류가 있습니다: "
                f"{', '.join(unknown)}\n"
                f"        쓸 수 있는 것: {', '.join(charsheet.KINDS)}")

        gone = sheet.missing(want)
        if not gone:
            cond["refs"] = [str(sheet.paths[k]) for k in want]
            kinds = ", ".join(f"{k}({charsheet.KIND_LABEL[k]})" for k in want)
            print(f"[charsheet] 조건 {cname} ← {kinds}")
            continue

        # 시트를 요구한 조건인데 시트가 없다. 여기서 끝낸다.
        have = (f"지금 있는 것: {', '.join(sheet.kinds())}"
                if sheet.has_images else "채택된 시트가 하나도 없습니다")
        stale = (f"\n        config 의 refs 는 쓰지 않습니다 "
                 f"({', '.join(str(r) for r in cond['refs'])}) — 지난 run 의 "
                 f"시트일 수 있고, 그러면 다른 사람 얼굴로 한 화가 통째로 "
                 f"그려집니다." if cond.get("refs") else "")

        # 있는 시트로 돌 수 있는 조건이 이미 있으면 그것부터 알려준다.
        # 통합 시트(sheet)를 뽑아 놓고 조건 C 를 부르는 일이 흔한데, 그때
        # "--split 으로 다시 뽑으세요" 라고만 하면 있는 것을 두고 돈을 또 쓴다.
        usable = [c for c, v in cfg["conditions"].items()
                  if v.get("sheets") and not sheet.missing(list(v["sheets"]))]
        fix = ""
        if usable:
            picks = " 또는 ".join(f"-c {c}" for c in usable)
            fix = (f"\n        지금 있는 시트로 바로 돌 수 있는 조건이 있습니다: "
                   f"**{picks}**\n"
                   f"          python run.py --run-id {sheet.run_dir.name} "
                   f"--episode 1 {picks.split(' 또는 ')[0]}")

        msg = (f"조건 {cname} 은 이 run 의 캐릭터 시트가 있어야 합니다 "
               f"(필요: {', '.join(want)} / 없는 것: {', '.join(gone)}).\n"
               f"        {sheet.run_dir / charsheet.CHARSHEET_DIR} — {have}.{stale}{fix}\n"
               f"        없는 종류를 새로 뽑으려면 story-harness 에서:\n"
               f"          python story.py --charsheet --run-id {sheet.run_dir.name}"
               f"{' --split' if set(want) & set(charsheet.SPLIT_KINDS) else ''}\n"
               f"          python story.py --charsheet --run-id {sheet.run_dir.name} --pick\n"
               f"        시트 없이 그림체만 먼저 보려면 조건 A 로 도세요 (-c A).")
        if fatal:
            die(msg)
        print(f"[건너뜀] {msg}\n")


def check_refs(cfg: dict[str, Any], conditions: list[str], fatal: bool) -> None:
    missing: list[str] = []
    affected: list[str] = []
    for cname in conditions:
        for r in cfg["conditions"][cname].get("refs") or []:
            if not ref_path(r).exists():
                if r not in missing:
                    missing.append(r)
                if cname not in affected:
                    affected.append(cname)
    if not missing:
        return
    lines = "\n".join(f"          - {m}" for m in missing)
    msg = (f"조건 {', '.join(affected)} 에 필요한 레퍼런스 이미지가 없습니다:\n{lines}\n"
           f"        story-harness 가 캐릭터 시트를 만들었다면 그 run 을 --run-id 로 "
           f"주면 자동으로 붙습니다.\n"
           f"        수동 경로를 쓰려면 그 자리에 이미지를 넣고 다시 실행하세요.\n"
           f"        레퍼런스 없이 돌리려면 --condition A 만 쓰세요.")
    if fatal:
        die(msg)
    print(f"[경고] {msg}\n")


def cost_of(n_images: int, n_text: int, cfg: dict[str, Any]) -> tuple[float, float]:
    usd = (n_images * float(cfg["provider"]["cost_per_image_usd"])
           + n_text * float(cfg["text"]["cost_per_call_usd"]))
    return usd, usd * float(cfg["pricing"]["usd_to_krw"])


def enforce_per_condition(cfg: dict[str, Any], units: int, candidates: int,
                          unit: str = "컷", knob: str = "candidates_per_cut") -> None:
    per_cond = int(cfg["limits"]["max_calls_per_condition"])
    need = units * candidates
    if need > per_cond:
        die(f"조건당 호출 {need}회({unit} {units} x 후보 {candidates})가 "
            f"상한 {per_cond}회를 넘었습니다.\n"
            f"        config.yaml 의 {knob} 을 줄이거나 "
            f"limits.max_calls_per_condition 을 올리세요.")


def enforce_total(jobs: list[Job], cfg: dict[str, Any], conditions: list[str]) -> None:
    total = int(cfg["limits"]["max_total_calls"])
    if len(jobs) > total:
        die(f"총 호출 {len(jobs)}회(조건 {len(conditions)}개)가 상한 {total}회를 넘었습니다.\n"
            f"        조건이나 모드를 나눠 실행하거나 limits.max_total_calls 를 올리세요.")


def call_counts(cfg: dict[str, Any], n_cuts: int, n_scenes: int) -> dict[str, Any]:
    """두 모드의 조건당 호출 수와 예상 비용. 이 실험의 핵심 비교 수치다."""
    per_cut = int(cfg["candidates_per_cut"])
    per_scene = int(cfg["scene"]["candidates_per_scene"])
    usd = float(cfg["provider"]["cost_per_image_usd"])
    krw = float(cfg["pricing"]["usd_to_krw"])
    cut_calls, scene_calls = n_cuts * per_cut, n_scenes * per_scene
    return {"cut_units": n_cuts, "cut_candidates": per_cut, "cut_calls": cut_calls,
            "cut_krw": round(cut_calls * usd * krw),
            "scene_units": n_scenes, "scene_candidates": per_scene,
            "scene_calls": scene_calls, "scene_krw": round(scene_calls * usd * krw)}


def print_comparison(c: dict[str, Any]) -> None:
    saved = c["cut_calls"] - c["scene_calls"]
    pct = (saved / c["cut_calls"] * 100) if c["cut_calls"] else 0
    print(f"[비교] 조건당 호출 — 컷 모드 {c['cut_calls']}회"
          f"(컷 {c['cut_units']} x {c['cut_candidates']}) vs "
          f"Scene 모드 {c['scene_calls']}회"
          f"(Scene {c['scene_units']} x {c['scene_candidates']}) "
          f"= {saved}회({pct:.0f}%) 적음")
    print(f"[비교] 조건당 예상 비용 — {c['cut_krw']:,}원 vs {c['scene_krw']:,}원")


def print_dry_run(jobs: list[Job], ep_dir: Path, picks, numbers_for) -> None:
    for i, job in enumerate(jobs, 1):
        atts = resolve_attachments(job, ep_dir, picks, numbers_for(job), quiet=True)
        nums = numbers_for(job)
        planned = ""
        # 첫 단위는 직전이 없다. 있지도 않을 첨부를 예고하지 않는다.
        if (job.use_previous_cut and nums and job.cut_number != nums[0]
                and not any(a.parent.name == job.condition for a in atts)):
            planned = f"  (실행 시 직전 {job.unit} 채택본이 여기 첨부됩니다)"
        print("=" * 78)
        print(f"[{i}/{len(jobs)}] condition={job.condition}  "
              f"{job.stem}={job.cut_number}  c{job.candidate}")
        print(f"출력: {rel(job.out_path)}")
        print(f"첨부: {', '.join(rel(a) for a in atts) if atts else '(없음)'}{planned}")
        print(f"원문: {job.description}")
        if job.dialogue:
            print(f"대사: {job.dialogue}")
        print("-" * 78)
        print(job.prompt)
        print()


def confirm(n_images: int, n_text: int, cfg: dict[str, Any], desc: str,
            text_desc: str, auto_yes: bool) -> None:
    usd, krw = cost_of(n_images, n_text, cfg)
    print("\n" + "=" * 78)
    print(f"이미지 모델: {desc}")
    print(f"텍스트 모델: {text_desc}  (호출 {n_text}회)")
    print(f"총 이미지 호출 {n_images}회, 예상 비용 {krw:,.0f}원 (약 ${usd:,.2f}). "
          f"재시도가 발생하면 그만큼 늘어납니다.")
    if auto_yes:
        print("진행할까요? -> --yes 로 자동 승인됨\n")
        return
    if not sys.stdin.isatty():
        die("확인 입력을 받을 수 없습니다 (비대화형). 확인을 건너뛰려면 --yes 를 주세요.")
    if input("진행할까요? [y/N] ").strip().lower() not in ("y", "yes"):
        print("취소했습니다.")
        raise SystemExit(0)
    print()


def load_cached(ep_dir: Path, filename: str, key: str, what: str,
                flag: str = "--view") -> tuple[dict, list]:
    """생성 없는 옵션들이 읽는 캐시 파일. 없으면 무엇을 먼저 돌려야 하는지 알려주고 멈춘다."""
    path = ep_dir / filename
    if not path.exists():
        die(f"{filename} 이 없습니다: {rel(path)}\n"
            f"        {flag} 는 이미 만들어 둔 것을 읽기만 합니다. {what}")
    data = json.loads(path.read_text(encoding="utf-8"))
    items = data.get(key) or []
    if not items:
        die(f"{rel(path)} 에 {key} 가 비어 있습니다.")
    return data, items


def direction_from_story(cfg: dict[str, Any], args, cuts: list[dict[str, Any]]) -> bool:
    """prompts.json 에 연출이 없으면 스토리 하네스의 direction 파일에서 직접 읽는다.

    --view 는 원래 이 폴더 안의 것만 본다. 여기서만 예외를 두는 이유는, 이미
    그려 놓은 이미지에 연출만 얹어 보는 것이 이 기능의 전부이기 때문이다 —
    그러자고 컷 프롬프트를 다시 만들게 하면 "재생성 없이 확인한다"가 아니게 된다.
    """
    if any(c.get("beat") for c in cuts):
        return True

    wt_dir = Path(str(cfg["story_runs_root"])) / args.run_id / "webtoon"
    path = storyload.direction_path(wt_dir, args.episode)
    if not path.exists():
        return False

    objs = [storyload.Cut(cut_number=int(c["cut_number"]),
                          description=str(c.get("description") or "")) for c in cuts]
    try:
        storyload.apply_direction(wt_dir, args.episode, objs)
    except storyload.LoadError as exc:
        die(str(exc))
    for c, o in zip(cuts, objs):
        c.update({"beat": o.beat, "gap_after": o.gap_after,
                  "gaze": o.gaze, "scene_break": o.scene_break})
    print(f"[direction] {path} 에서 연출을 읽었습니다 "
          f"(prompts.json 에는 아직 없습니다 — 다음 생성 때 붙습니다).")
    return True


def gap_counts(levels: list[int]) -> dict[int, int]:
    return {lv: levels.count(lv) for lv in (0, 1, 2, 3)}


def cut_direction(cuts: list[dict[str, Any]], gap_map: dict[int, str]) -> dict[str, Any]:
    """컷 모드 연출 뷰어에 넘길 것 — 컷 사이 여백과 Scene 경계 표시."""
    gaps, breaks, order = {}, {}, 0
    for c in cuts:
        n = int(c["cut_number"])
        gaps[n] = int(c.get("gap_after") if isinstance(c.get("gap_after"), int) else 1)
        if c.get("scene_break"):
            order += 1
            breaks[n] = order
    return {"gaps": gaps, "breaks": breaks, "gap_map": gap_map,
            "counts": gap_counts([gaps[int(c["cut_number"])] for c in cuts[:-1]])}


def scene_direction(scenes: list[dict[str, Any]], cuts: list[dict[str, Any]],
                    gap_map: dict[int, str]) -> dict[str, Any]:
    """Scene 모드 연출 뷰어 — Scene 사이 여백은 그 Scene 마지막 컷의 gap_after 다.

    이미 그려진 Scene 이미지는 컷 3개가 한 장에 구워져 있다. 그러니 연출이 새로
    정한 경계(scene_break)로 다시 묶으려면 이미지를 다시 만들어야 한다. 여백만은
    재생성 없이 확인된다 — 그 차이를 뷰어 안에 적어 둔다.
    """
    by_num = {int(c["cut_number"]): c for c in cuts}
    gaps = {}
    for sc in scenes:
        nums = [int(n) for n in sc.get("cut_numbers") or []]
        last = by_num.get(nums[-1]) if nums else None
        gap = (last or {}).get("gap_after")
        gaps[int(sc["scene_number"])] = gap if isinstance(gap, int) else 1

    now = [len(sc.get("cut_numbers") or []) for sc in scenes]
    want, run = [], 0
    for c in cuts:
        run += 1
        if c.get("scene_break"):
            want.append(run)
            run = 0
    if run:
        want.append(run)

    note = ""
    if want and want != now:
        note = (f"연출이 정한 Scene 묶음은 {'+'.join(map(str, want))} 인데, 이 이미지들은 "
                f"{'+'.join(map(str, now))} 로 이미 구워져 있습니다. 여백은 지금 확인되지만 "
                f"묶음이 바뀐 효과를 보려면 이미지를 다시 만들어야 합니다 "
                f"(--mode scene --regen-prompts). 컷 모드 연출본"
                f"(viewer_<조건>_directed.html)은 컷 이미지를 그대로 쓰므로 새 묶음까지 "
                f"지금 확인됩니다.")
    return {"gaps": gaps, "gap_map": gap_map,
            "counts": gap_counts([gaps[int(sc["scene_number"])] for sc in scenes[:-1]]),
            "regroup_note": note}


def run_viewer(cfg: dict[str, Any], args, ep_dir: Path, conditions: list[str]) -> int:
    """--view: 이미지를 만들지 않고 뷰어 HTML 만 만든다.

    이미 있는 산출물(prompts.json / scenes.json / picks.csv / layout.json)만 읽는다.
    스토리 하네스 폴더도 API 키도 필요 없다.
    """
    want_cut = args.mode in ("cut", "both")
    want_scene = args.mode in ("scene", "both")

    cuts: list[dict[str, Any]] = []
    scenes: list[dict[str, Any]] = []
    meta: dict[str, Any] = {"run_id": args.run_id, "episode": args.episode,
                            "title": f"{args.episode}화"}

    if want_cut:
        data, cuts = load_cached(ep_dir, "prompts.json", "cuts",
                                 "먼저 컷 모드 생성을 한 번 돌리세요.")
        meta = {"run_id": data.get("run_id") or args.run_id,
                "episode": data.get("episode") or args.episode,
                "title": data.get("title") or f"{args.episode}화"}
    elif (ep_dir / "prompts.json").exists():
        # Scene 모드에도 컷 원문이 필요하다 — 말풍선에 넣을 대사가 거기 있다.
        # 컷 모드와 달리 없어도 진행한다 (Scene 만 돌린 run 도 있을 수 있다).
        cuts = json.loads((ep_dir / "prompts.json").read_text(encoding="utf-8")).get("cuts") or []
    if want_scene:
        data, raw = load_cached(ep_dir, scenegen.CACHE_FILE, "scenes",
                                "먼저 --mode scene 으로 한 번 돌리세요.")
        if not want_cut:
            meta = {"run_id": data.get("run_id") or args.run_id,
                    "episode": data.get("episode") or args.episode,
                    "title": data.get("title") or f"{args.episode}화"}
        scenes = [{
            "scene_number": int(sc["scene_number"]),
            "cut_numbers": [int(n) for n in sc.get("cut_numbers") or []],
            "label": (lambda ns: f"컷 {ns[0]}" if len(ns) == 1 else f"컷 {ns[0]}~{ns[-1]}")(
                [int(n) for n in sc.get("cut_numbers") or [0]]),
            "description": "\n".join(str(p) for p in sc.get("panels") or []),
            # 말풍선은 컷별 원문 대사가 필요하다 (캡션용 합친 문자열로는 못 나눈다).
            "lines": [{"cut": int(d["cut"]), "text": str(d.get("text") or "")}
                      for d in sc.get("dialogues") or []],
            "dialogue": "", "reader_only": False,
        } for sc in raw]

    picks = load_picks(ep_dir)
    if not picks:
        # 후보가 1장뿐이면 고를 것이 없다. 그때까지 picks.csv 를 손으로 만들게 하는
        # 것은 의미 없는 관문이라, 있는 그 한 장(c1)을 채택본으로 본다.
        picks = only_candidate_picks(cfg, conditions, [int(c["cut_number"]) for c in cuts],
                                     [int(s["scene_number"]) for s in scenes])
        if not picks:
            die(f"picks.csv 가 없습니다: {rel(picks_path(ep_dir))}\n"
                f"        컷 시트에서 후보를 고르고 [picks.csv 내려받기] 로 이 폴더에 "
                f"저장하세요.\n"
                f"        (후보를 1장만 뽑았다면 --candidates 1 을 같이 주세요 — "
                f"그러면 c1 을 채택본으로 봅니다.)")
        print("[picks] picks.csv 가 없지만 후보가 1장뿐이라 c1 을 채택본으로 봅니다.")

    # Scene 캡션·말풍선은 묶인 컷들의 대사다. 컷 원문이 있으면 거기서 끌어온다
    # (scenes.json 에 dialogues 가 없는 예전 파일도 이 경로로 채워진다).
    if want_scene and cuts:
        by_num = {int(c["cut_number"]): c for c in cuts}
        for sc in scenes:
            picked = [by_num[n] for n in sc["cut_numbers"] if n in by_num]
            sc["reader_only"] = any(c.get("reader_only") for c in picked)
            sc["description"] = "\n".join(
                f"[컷 {c['cut_number']}] {c.get('description') or ''}" for c in picked)
            if not sc["lines"]:
                sc["lines"] = [{"cut": int(c["cut_number"]), "text": str(c["dialogue"])}
                               for c in picked if str(c.get("dialogue") or "").strip()]
    for sc in scenes:
        sc["dialogue"] = "\n".join(f"[컷 {l['cut']}] {l['text']}" for l in sc["lines"])

    vcfg = dict(cfg["viewer"])
    try:
        sizes = viewer.parse_sizes(vcfg.get("sizes"))
        layout, warns = viewer.load_layout(ep_dir, sizes)
        gap_map = viewer.parse_gap_map(vcfg.get("gap_map"))
    except viewer.LayoutError as exc:
        die(str(exc))

    # 연출본 — 이미지는 그대로 두고 여백만 다시 배치한 별도 파일.
    cut_dir_opts = scene_dir_opts = None
    if args.directed:
        if not cuts:
            die("--directed 는 컷 원문이 필요합니다 (prompts.json).\n"
                "        컷 모드를 한 번 돌리거나 --mode both --view 로 실행하세요.")
        if not direction_from_story(cfg, args, cuts):
            die(f"연출 정보가 없습니다.\n"
                f"        스토리 하네스에서 7.5단계를 먼저 돌리세요:\n"
                f"          python webtoon.py --run {args.run_id} --direction-only "
                f"--episode {args.episode}\n"
                f"        (화당 호출 1회. 이미지는 다시 만들지 않습니다.)")
        if want_cut:
            cut_dir_opts = cut_direction(cuts, gap_map)
        if want_scene and scenes:
            scene_dir_opts = scene_direction(scenes, cuts, gap_map)
            if scene_dir_opts.get("regroup_note"):
                print(f"[direction] {scene_dir_opts['regroup_note']}")

    if want_cut:
        numbers = {int(c["cut_number"]) for c in cuts}
        warns += [f"layout.json: 컷 {n} 은 이 화에 없습니다 (컷 {min(numbers)}~{max(numbers)})."
                  for n in sorted(set(layout) - numbers)]
        for w in warns:
            print(f"[경고] {w}")
        if layout:
            print(f"[layout] {rel(viewer.layout_path(ep_dir))} · 컷 "
                  f"{len(set(layout) & numbers)}개 크기 지정")
        else:
            print(f"[layout] {rel(viewer.layout_path(ep_dir))} 이 없어 전부 "
                  f"{viewer.DEFAULT_SIZE} 로 시작합니다 "
                  f"(쓸 수 있는 크기: {', '.join(sizes)})")

    # 말풍선 그림은 이미지 안에 있다. 여기서 얹는 것은 그 안에 들어갈 글자뿐이다.
    bubs: dict[int, list[bubbles.Region]] = {}
    had_bubbles = False
    if want_scene:
        try:
            bubs, bwarn, had_bubbles = bubbles.load(ep_dir, scenes)
        except bubbles.BubbleError as exc:
            die(str(exc))
        for w in bwarn:
            print(f"[경고] {w}")
        lines = sum(len(sc.get("lines") or []) for sc in scenes)
        placed = sum(len(v) for v in bubs.values())
        if lines == 0:
            print("[bubbles] 대사를 하나도 찾지 못했습니다 — 얹을 글자가 없습니다.")
            print(f"          대사는 {scenegen.CACHE_FILE} 의 dialogues, 없으면 "
                  f"prompts.json 의 컷 대사에서 옵니다.")
            if not (ep_dir / "prompts.json").exists():
                print(f"          {rel(ep_dir)}/prompts.json 이 없습니다. "
                      f"컷 모드를 한 번 돌리거나 --regen-prompts 로 다시 만드세요.")
        elif not had_bubbles:
            print(f"[bubbles] {rel(bubbles.bubbles_path(ep_dir))} 이 없습니다 — 대사 "
                  f"{lines}줄이 전부 '배치 대기' 입니다.")
            print(f"          뷰어에서 [말풍선 편집] 을 켜고 그려진 말풍선 위에 "
                  f"사각형을 끌어 그리면 순서대로 들어갑니다.")
        else:
            print(f"[bubbles] {rel(bubbles.bubbles_path(ep_dir))} · 대사 {lines}줄 중 "
                  f"{placed}줄 배치됨" + ("" if placed == lines else f" (대기 {lines - placed}줄)"))

    counts = (call_counts(cfg, len(cuts), len(scenes))
              if (want_cut and want_scene and cuts and scenes) else None)

    for cname in conditions:
        cpicks = {n: k for (c, n), k in picks.items() if c == cname}
        spicks = {n: k for (c, n), k in picks.items() if c == scene_cond(cname)}

        if want_cut:
            path, missing, tally = viewer.build_viewer(
                ep_dir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
                cuts, cpicks, layout, sizes, vcfg, siblings=conditions)
            mix = " ".join(f"{name} {tally[name]}" for name in sizes if tally[name])
            print(f"viewer_{cname}.html      -> {rel(path)} "
                  f"(컷 {len(cuts) - len(missing)}/{len(cuts)} · {mix})")
            if missing:
                print(f"    ! 채택 이미지가 없는 컷 {', '.join(str(n) for n in missing)} "
                      f"(자리표시자로 표시했습니다)")
            if cut_dir_opts:
                path, _, _ = viewer.build_viewer(
                    ep_dir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
                    cuts, cpicks, layout, sizes, vcfg, siblings=conditions,
                    direction=cut_dir_opts)
                print(f"viewer_{cname}_directed.html -> {rel(path)} "
                      f"(같은 이미지 · 연출 여백 적용)")

        if want_scene:
            path, missing = viewer.build_scene_viewer(
                ep_dir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
                scenes, spicks, vcfg, siblings=conditions,
                bubbles_by_scene=bubs, had_bubble_file=had_bubbles)
            print(f"viewer_scene_{cname}.html -> {rel(path)} "
                  f"(Scene {len(scenes) - len(missing)}/{len(scenes)})")
            if missing:
                print(f"    ! 채택 이미지가 없는 Scene {', '.join(str(n) for n in missing)} "
                      f"(자리표시자로 표시했습니다)")
            if scene_dir_opts:
                path, _ = viewer.build_scene_viewer(
                    ep_dir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
                    scenes, spicks, vcfg, siblings=conditions,
                    bubbles_by_scene=bubs, had_bubble_file=had_bubbles,
                    direction=scene_dir_opts)
                print(f"viewer_scene_{cname}_directed.html -> {rel(path)} "
                      f"(같은 이미지 · 연출 여백 적용)")

        if want_cut and want_scene:
            path, _, _ = viewer.build_compare_viewer(
                ep_dir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
                cuts, cpicks, layout, sizes, scenes, spicks, vcfg, calls=counts,
                bubbles_by_scene=bubs)
            print(f"viewer_both_{cname}.html  -> {rel(path)}  (컷 vs Scene 나란히)")

    if counts:
        print_comparison(counts)
    if args.directed:
        print("→ 연출본은 그림이 같고 사이의 빈 곳만 다릅니다. 기존 뷰어와 두 창을 "
              "나란히 놓고 내려보세요.")
        print("   상단 바의 [연출 여백] 을 끄면 같은 창에서 바로 전/후가 됩니다.")
    if want_cut:
        print("→ 컷 크기는 컷 위 버튼이나 숫자키로 바로 바꿉니다. 바꾼 배치는 "
              "[layout.json 내려받기] 로")
        print(f"   {rel(ep_dir)}/layout.json 에 저장해야 다음 실행에도 남습니다.")
    if want_scene:
        print("→ 대사는 [말풍선 편집] 을 켜고 말풍선 위에 사각형을 끌어 그려 넣습니다. "
              "더블클릭으로 문구 수정.")
        print(f"   [bubbles.json 내려받기] 로 {rel(ep_dir)}/{bubbles.BUBBLE_FILE} 에 "
              f"저장해야 다음 실행에도 남습니다.")
    return 0


def run_style_lock(cfg: dict[str, Any], args, ep_dir: Path, conditions: list[str]) -> int:
    """--style-lock: Scene 과 Scene 사이에서 그림체가 유지되는가만 본다.

    이미 있는 산출물(scenes.json / picks.csv / scene_<조건>/ 이미지)만 읽는다.
    이미지를 다시 만들지 않으므로 API 키도 스토리 하네스 폴더도 필요 없다.

    조건 하나만 받는다. 이 화면이 묻는 것은 "조건 A 와 C 중 뭐가 나은가"가 아니라
    "이 조건 안에서 Scene 1 과 Scene 4 가 같은 그림체인가"이기 때문이다.
    파일 이름(style_lock.html / style_score.csv)도 그래서 화당 하나다.
    """
    if len(conditions) != 1:
        die(f"--style-lock 은 조건 하나만 봅니다 (지금 {len(conditions)}개: "
            f"{', '.join(conditions)}).\n"
            f"        한 조건 안에서 Scene 사이 그림체가 유지되는지를 보는 화면이라\n"
            f"        style_lock.html / style_score.csv 는 화당 하나입니다.\n"
            f"        -c {conditions[0]} 처럼 하나만 주세요.")
    cname = conditions[0]
    cond_dir = scene_cond(cname)

    data, raw = load_cached(ep_dir, scenegen.CACHE_FILE, "scenes",
                            "먼저 --mode scene 으로 한 번 돌리세요 "
                            "(그림체 일관성은 Scene 이미지에서 봅니다).",
                            flag="--style-lock")
    meta = {"run_id": data.get("run_id") or args.run_id,
            "episode": data.get("episode") or args.episode,
            "title": data.get("title") or f"{args.episode}화"}
    scenes = []
    for sc in raw:
        nums = [int(n) for n in sc.get("cut_numbers") or []]
        scenes.append({
            "scene_number": int(sc["scene_number"]),
            "label": (f"컷 {nums[0]}" if len(nums) == 1
                      else f"컷 {nums[0]}~{nums[-1]}" if nums else ""),
            "description": "\n".join(str(p) for p in sc.get("panels") or []),
        })
    numbers = [sc["scene_number"] for sc in scenes]

    all_picks = load_picks(ep_dir)
    if not all_picks:
        all_picks = only_candidate_picks(cfg, [cname], [], numbers)
        if not all_picks:
            die(f"picks.csv 가 없습니다: {rel(picks_path(ep_dir))}\n"
                f"        Scene 시트에서 후보를 고르고 [picks.csv 내려받기] 로 "
                f"이 폴더에 저장하세요.\n"
                f"        (후보를 1장만 뽑았다면 --candidates 1 을 같이 주세요 — "
                f"그러면 c1 을 채택본으로 봅니다.)")
        print("[picks] picks.csv 가 없지만 후보가 1장뿐이라 c1 을 채택본으로 봅니다.")
    picks = {n: k for (c, n), k in all_picks.items() if c == cond_dir}
    if not picks:
        die(f"picks.csv 에 {cond_dir} 채택 기록이 없습니다.\n"
            f"        contact_sheet_scene.html 에서 조건 {cname} 의 후보를 고르고 "
            f"[picks.csv 내려받기] 로 저장하세요.")

    try:
        crops, warns, had_file = stylelock.load_crops(ep_dir, cond_dir, numbers)
    except stylelock.StyleLockError as exc:
        die(str(exc))
    for w in warns:
        print(f"[경고] {w}")

    score_file, created = stylelock.write_score(ep_dir, numbers)
    saved_score = stylelock.read_score(ep_dir)

    path, missing = stylelock.build_page(
        ep_dir, meta, cname, cond_dir,
        str(cfg["conditions"][cname].get("label") or ""),
        scenes, picks, crops, had_file, saved_score, dict(cfg["viewer"]))

    print(f"[style-lock] {cond_dir}/ 의 채택본만 읽었습니다 — API 호출 0회, 0원.")
    print(f"style_lock.html          -> {rel(path)} "
          f"(Scene {len(scenes) - len(missing)}/{len(scenes)})")
    if missing:
        print(f"    ! 채택 이미지가 없는 Scene {', '.join(str(n) for n in missing)} "
              f"(자리표시자로 표시했습니다)")
    if created:
        print(f"style_score.csv          -> {rel(score_file)} "
              f"(빈 채점표 · Scene {len(numbers)}행)")
    else:
        filled = sum(len(v) for v in saved_score.values())
        print(f"style_score.csv          -> {rel(score_file)} "
              f"(이미 있어 그대로 둡니다 · Y/N {filled}칸 채워짐)")
    if had_file:
        print(f"[faces] {rel(stylelock.crops_path(ep_dir))} · 얼굴 자리 {len(crops)}개")
    else:
        print(f"[faces] {rel(stylelock.crops_path(ep_dir))} 이 없어 어림잡은 상자로 "
              f"시작합니다 — 얼굴 자리는 코드가 모릅니다.")
    print("→ 브라우저로 열어 [얼굴만 크롭] 으로 얼굴을 가로로 붙여 보고, "
          "[확대] 로 선 굵기·채색 질감을 보세요.")
    print("   상자가 얼굴을 못 잡았으면 [크롭 편집] 을 켜고 이미지 위에 끌어 그린 뒤 "
          f"[{stylelock.CROP_FILE} 내려받기] 로 이 폴더에 저장하세요.")
    print(f"   채점은 Y/N 만 씁니다 (점수 금지). 열: {', '.join(stylelock.SCORE_HEADER)}")
    return 0


def scene_for_viewer(sc: scenegen.Scene) -> dict[str, Any]:
    """scenegen.Scene → viewer / verify 화면이 읽는 dict.

    대사는 합친 문자열이 아니라 컷별로 남긴다 — 말풍선은 컷 하나에 하나씩 얹힌다.
    """
    lines = [{"cut": int(c["cut_number"]), "text": str(c["dialogue"]).strip()}
             for c in sc.cuts if str(c.get("dialogue") or "").strip()]
    return {"scene_number": sc.scene_number, "cut_numbers": sc.cut_numbers,
            "label": sc.label, "description": sc.description(), "lines": lines,
            "dialogue": "\n".join(f"[컷 {l['cut']}] {l['text']}" for l in lines),
            "reader_only": sc.reader_only()}


def verify_scenes(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                  make_text_client) -> tuple[list[scenegen.Scene], int]:
    """--verify-all 이 쓸 패널 서술. (Scene 목록, 텍스트 호출 수).

    본 파이프라인의 scenes.json 을 덮지 않는다. 덮으면 이미 만들어 둔 scene_<조건>/
    이미지와 기록이 어긋난다 — 이 실행은 "새 프롬프트가 먹는가"를 보려는 것이지
    본 산출물을 갈아치우려는 것이 아니다.

    레이아웃만은 scenes.json 의 것을 그대로 가져온다. 레이아웃까지 달라지면
    결과가 달라졌을 때 프롬프트 규칙 때문인지 레이아웃 때문인지 가를 수 없다.
    """
    vdir = verifyall.verify_dir(ep_dir)
    cache = verifyall.scenes_path(ep_dir)
    base = cut_rows(ep)

    if cache.exists() and not args.regen_prompts:
        try:
            data = json.loads(cache.read_text(encoding="utf-8"))
            scenes = scenegen.from_json(data.get("scenes") or [], base)
            print(f"[verify] 패널 서술 캐시 사용: {rel(cache)} (Scene {len(scenes)}개) "
                  f"— 텍스트 호출 0회")
            return scenes, 0
        except (json.JSONDecodeError, scenegen.SceneError, KeyError, TypeError) as exc:
            print(f"[verify] {rel(cache)} 를 읽지 못해 다시 만듭니다: {exc}")

    per = int(cfg["scene"]["cuts_per_scene"])
    max_per = int(cfg["scene"].get("max_cuts_per_scene") or 0)
    try:
        scenes = group_scenes(cfg, ep, base)
        scenegen.assign_layouts(scenes, list(cfg["scene"]["layout_templates"] or []),
                                str(cfg["scene"]["layout_pick"]),
                                f"{ep.run_id}:ep{ep.episode}")
    except scenegen.SceneError as exc:
        die(str(exc))

    main_cache = ep_dir / scenegen.CACHE_FILE
    if main_cache.exists():
        try:
            old = {int(s["scene_number"]): str(s.get("layout") or "")
                   for s in json.loads(main_cache.read_text(encoding="utf-8"))
                   .get("scenes") or []}
            for sc in scenes:
                if old.get(sc.scene_number):
                    sc.layout = old[sc.scene_number]
            print(f"[verify] 레이아웃은 {rel(main_cache)} 의 것을 그대로 씁니다.")
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            print(f"[verify] {rel(main_cache)} 에서 레이아웃을 읽지 못해 새로 고릅니다.")

    sizes = ", ".join(str(len(sc.cuts)) for sc in scenes)
    how = ("연출 기준" if grouping_mode(cfg, ep) == "direction"
           else f"{min(per, max_per) if max_per else per}개씩 고정")
    print(f"[verify] 컷 {len(base)}개 → Scene {len(scenes)}개 (묶음 {sizes} · {how})")

    if args.dry_run:
        print("[verify] dry-run — 텍스트 LLM 을 호출하지 않고 자리표시자를 씁니다.")
        for sc in scenes:
            sc.panels = [DRY_SCENE] * len(sc.cuts)
        return scenes, 0

    tmpl = (ROOT / "prompts" / "scene_gen.txt").read_text(encoding="utf-8")
    client = make_text_client()
    print(f"[verify] {client.describe()} 호출 — 현재 scene_gen.txt 로 패널 서술 생성...")
    try:
        sg_prompt = scenegen.build_prompt(tmpl, ep.title, scenes, ep.setting,
                                          ep.scenes, cfg)
        parsed, meta, secs = textgen.call_json(
            client, sg_prompt,
            int(cfg["retry"]["max_retries"]), float(cfg["retry"]["backoff_sec"]),
            on_retry=lambda a, e: print(f"    재시도 {a}: {e[:160]}"))
        missing = scenegen.fill_panels(scenes, parsed)
    except (textgen.TextError, scenegen.SceneError) as exc:
        die(f"verify 의 scene_gen 실패: {exc}")
    if missing:
        die(f"scene_gen 이 Scene {missing} 의 패널을 다 돌려주지 않았습니다. "
            f"--regen-prompts 로 다시 시도하세요.")

    banned = list(cfg["prompt_gen"]["banned_terms"])
    for sc in scenes:
        sc.warnings = lint_scene(" ".join(sc.panels), banned)
        if sc.warnings:
            print(f"    ! Scene {sc.scene_number}: LLM 이 금지어를 썼습니다 "
                  f"→ {', '.join(sc.warnings)}")

    vdir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps({
        "run_id": ep.run_id, "episode": ep.episode, "title": ep.title,
        "text_model": client.model, "generated_at": datetime.now().isoformat(timespec="seconds"),
        "cuts_per_scene": per,
        "note": "--verify-all 전용. 본 파이프라인은 scenes.json 을 씁니다.",
        "scenes": scenegen.to_json(scenes),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    log_line(ep_dir, {"timestamp": datetime.now().isoformat(timespec="seconds"),
                      "kind": "verify_scene_gen", "model": client.model, "prompt": sg_prompt,
                      "duration_sec": secs, "scenes": len(scenes), "ok": True,
                      "provider": "gemini-text",
                      **cost_fields(cfg, client.model, "text", meta),
                      "provider_meta": meta})
    print(f"[verify] 패널 서술 완료 ({secs}s) -> {rel(cache)}")
    return scenes, 1


def run_verify_all(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                   conditions: list[str], appearance: str, provider_factory,
                   make_text_client, image_model: str, text_model: str,
                   present: dict[int, bool] | None = None) -> int:
    """--verify-all: 한 벌 뽑아 그림체·말풍선·효과음·통독을 동시에 잰다.

    셋을 따로 검증하면 생성 비용이 세 번 나간다. 같은 이미지로 동시에 잴 수 있는
    것들이라 한 번만 뽑는다. 후보는 채택용(3장)이 아니라 검증용(2장)이다 —
    여기서 고를 것이 아니라 잴 것이기 때문이다.
    """
    if len(conditions) != 1:
        die(f"--verify-all 은 조건 하나만 봅니다 (지금 {len(conditions)}개: "
            f"{', '.join(conditions)}).\n"
            f"        한 조건의 한 벌을 네 가지로 나눠 보는 화면이라 "
            f"verify.html / verify_score.csv 는 화당 하나입니다.")
    cname = conditions[0]
    vdir = verifyall.verify_dir(ep_dir)
    cond_dir = scene_cond(cname)                   # verify/scene_C/
    candidates = int(cfg["scene"]["verify_candidates_per_scene"])

    scenes, n_text = verify_scenes(cfg, args, ep, ep_dir, make_text_client)
    enforce_per_condition(cfg, len(scenes), candidates, unit="Scene",
                          knob="scene.verify_candidates_per_scene")

    cond = cfg["conditions"][cname]
    jobs: list[Job] = []
    for sc in scenes:
        here = scene_has_main(sc, present)
        prompt = scenegen.assemble(
            cfg, appearance if here else ABSENT_NOTE, sc,
            (cond.get("extra") or "") if here else "", with_lock=here,
            style_text=style_block(cfg, scenegen.render_style(sc)))
        for k in range(1, candidates + 1):
            jobs.append(Job(
                condition=cond_dir, cut_number=sc.scene_number, candidate=k,
                description=sc.description(), dialogue=sc.dialogue(),
                scene="\n".join(f"Panel {i}: {p}" for i, p in enumerate(sc.panels, 1)),
                prompt=prompt,
                refs=[ref_path(r) for r in (cond.get("refs") or [])] if here else [],
                # 검증용 한 벌이므로 직전 Scene 체인은 쓰지 않는다. 체인을 걸면
                # "그림체가 유지되는가"의 답이 조건 D 의 힘인지 스타일 문구의 힘인지 섞인다.
                use_previous_cut=False,
                out_path=vdir / cond_dir / f"scene{sc.scene_number}_c{k}.png",
                stem="scene", unit="Scene"))

    if args.dry_run:
        print("=" * 78)
        for i, job in enumerate(jobs, 1):
            if job.candidate != 1:
                continue        # 후보끼리는 프롬프트가 같다. 한 번만 보여준다.
            sc = scenes[(i - 1) // candidates]
            print(f"[Scene {job.cut_number}] {sc.label} · 패널 {len(sc.panels)}개 · "
                  f"후보 {candidates}장 (프롬프트 동일)")
            print(f"레이아웃: {sc.layout}")
            print(f"대사: {job.dialogue or '(없음)'}")
            print(f"첨부: {', '.join(rel(r) for r in job.refs) or '(없음)'}")
            print("-" * 78)
            print(job.prompt)
            print("=" * 78)
        usd, krw = cost_of(len(jobs), n_text, cfg)
        print(f"[dry-run] 조건 {cname} · Scene {len(scenes)} x 후보 {candidates} "
              f"= 이미지 호출 {len(jobs)}회 예정, 예상 비용 {krw:,.0f}원 (약 ${usd:,.2f})")
        print(f"[dry-run] 출력 예정: {rel(vdir / cond_dir)}/scene{{n}}_c{{k}}.png")
        print("[dry-run] API 호출은 하지 않았습니다. 위 장면 서술은 자리표시자입니다.")
        return 0

    todo = [j for j in jobs if not j.out_path.exists()]
    if len(todo) != len(jobs):
        print(f"[verify] 이미 있는 {len(jobs) - len(todo)}건은 다시 뽑지 않습니다 "
              f"(다시 뽑으려면 그 파일을 지우세요).")

    ok_count, fail = 0, []
    if todo:
        print("\n" + "=" * 78)
        print("이 한 번의 생성으로 그림체·말풍선·효과음을 동시에 검증합니다.")
        print(f"  Scene {len(scenes)}장 x 후보 {candidates}장 = 이미지 호출 {len(todo)}회")
        print(f"  저장 위치: {rel(vdir / cond_dir)}/  "
              f"(기존 {rel(ep_dir / cond_dir)}/ 는 건드리지 않습니다)")
        if not ep.has_direction:
            print("  [주의] 이 화에는 연출 필드(beat/gap_after/gaze/scene_break)가 "
                  "아직 없습니다.")
        print("  [주의] 스토리 하네스 W7 수정이 끝나기 전에 뽑으면 컷 서술이 바뀌면서 "
              "이 한 벌이 통째로 버려집니다.")
        confirm(len(todo), n_text, cfg, provider_factory.describe(),
                text_model or "(미호출)", args.yes)

        for i, job in enumerate(todo, 1):
            print(f"[{i}/{len(todo)}] {job.condition} / Scene{job.cut_number} / "
                  f"c{job.candidate}")
            if run_job(job, provider_factory, cfg, vdir, {},
                       [sc.scene_number for sc in scenes]):
                ok_count += 1
            else:
                fail.append(f"Scene{job.cut_number}_c{job.candidate}")
        print("\n" + "=" * 78)
        print(f"[verify] 성공 {ok_count} / 실패 {len(fail)}")
        if fail:
            print("실패 목록: " + ", ".join(fail))
            print("→ 같은 명령을 다시 실행하면 실패한 건만 다시 시도합니다.")
    else:
        print("[verify] 이미 뽑아 둔 것만 읽습니다 — API 호출 0회, 0원.")

    return build_verify_pages(cfg, args, ep, ep_dir, cname, cond_dir, scenes,
                              candidates, image_model, text_model, appearance,
                              1 if fail else 0)


def build_verify_pages(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
                       cname: str, cond_dir: str, scenes: list[scenegen.Scene],
                       candidates: int, image_model: str, text_model: str,
                       appearance: str, exit_code: int) -> int:
    """생성이 끝난 뒤(또는 이미 다 있을 때) 읽는 화면들을 만든다. 여기서는 0원이다."""
    vdir = verifyall.verify_dir(ep_dir)
    vscenes = [scene_for_viewer(sc) for sc in scenes]
    numbers = [sc.scene_number for sc in scenes]
    meta = {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title}

    # 후보 고르기는 컨택트 시트가 이미 하는 일이다. verify/ 안에 한 벌 둔다.
    sheet_path = report.build_contact_sheet(
        vdir, {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title,
               "source": ep.source},
        [{"name": cond_dir, "label": str(cfg["conditions"][cname].get("label") or "")}],
        scene_rows(cfg, scenes), candidates,
        {"image_model": image_model, "text_model": text_model or "(캐시 사용)",
         "style": str(cfg["style_suffix"]), "appearance": appearance},
        filename="contact_sheet_scene.html", unit="Scene",
        file_pattern="{cond}/scene{n}_c{k}.png")

    # 통독 탭은 흉내가 아니라 실제 뷰어다. verify/ 이미지로 한 벌 만들어 iframe 에 건다.
    picks = {n: k for (c, n), k in load_picks(vdir).items() if c == cond_dir}
    vcfg = dict(cfg["viewer"])
    try:
        bubs, bwarn, had_bub = bubbles.load(vdir, vscenes)
    except bubbles.BubbleError as exc:
        die(str(exc))
    for w in bwarn:
        print(f"[경고] {w}")
    view_path, view_missing = viewer.build_scene_viewer(
        vdir, meta, cname, str(cfg["conditions"][cname].get("label") or ""),
        vscenes, picks or {n: 1 for n in numbers}, vcfg,
        bubbles_by_scene=bubs, had_bubble_file=had_bub)

    # 얼굴 자리는 그림마다 다르다. verify/ 의 그림은 scene_<조건>/ 과 다른 한 벌이므로
    # style_faces.json 안에서도 다른 이름표를 쓴다 (같은 파일, 다른 최상위 키).
    img_dir = f"{verifyall.VERIFY_DIR}/{cond_dir}"
    try:
        crops, warns, had_crop = stylelock.load_crops(ep_dir, img_dir, numbers)
    except stylelock.StyleLockError as exc:
        die(str(exc))
    for w in warns:
        print(f"[경고] {w}")

    score_path, created = stylelock.write_score(
        ep_dir, numbers, verifyall.SCORE_HEADER, verifyall.SCORE_FILE)
    saved_score = stylelock.read_score(ep_dir, verifyall.SCORE_HEADER,
                                       verifyall.SCORE_FILE)

    page, missing = verifyall.build_page(
        ep_dir, meta, cname, img_dir,
        str(cfg["conditions"][cname].get("label") or ""),
        vscenes, picks, candidates, crops, had_crop, saved_score,
        f"{verifyall.VERIFY_DIR}/{view_path.name}", vcfg)

    print(f"\nverify.html              -> {rel(page)}  (탭 4개)")
    print(f"  ├ 통독 탭이 띄우는 뷰어  -> {rel(view_path)}")
    print(f"  └ 후보 고르기 컨택트 시트 -> {rel(sheet_path)}")
    if missing or view_missing:
        gone = sorted(set(missing) | set(view_missing))
        print(f"    ! 이미지가 없는 Scene {', '.join(str(n) for n in gone)}")
    if created:
        print(f"{verifyall.SCORE_FILE}         -> {rel(score_path)} "
              f"(빈 채점표 · Scene {len(numbers)}행 x 열 {len(verifyall.ALL_KEYS)}개)")
    else:
        filled = sum(len(v) for v in saved_score.values())
        print(f"{verifyall.SCORE_FILE}         -> {rel(score_path)} "
              f"(이미 있어 그대로 둡니다 · Y/N {filled}칸 채워짐)")
    print("→ 브라우저로 열어 ① 말풍선/효과음 → ② 그림체 → ③ 통독 순서로 보고, "
          "④ 채점에서 내려받으세요.")
    print("   ③ 통독 탭에서는 말풍선을 바로 끌어 그릴 수 있습니다 "
          f"([bubbles.json 내려받기] → {rel(vdir)}/bubbles.json).")
    return exit_code


PROBE_DIR = "probe"


def run_probe(cfg: dict[str, Any], args, ep: storyload.Episode, ep_dir: Path,
              conditions: list[str], appearance: str, provider_factory,
              make_text_client, image_model: str, text_model: str,
              present: dict[int, bool] | None = None) -> int:
    """--sfx-test: Scene 하나만 새 프롬프트로 뽑아 눈으로 확인한다.

    전체 재생성 전에 "빈 말풍선이 정말 비어 있는가 / 효과음 레터링이 그림에
    녹아드는가" 만 본다. 기존 scene_C/ 와 scenes.json 은 건드리지 않는다 —
    probe/ 아래에 따로 쌓아 두고 비교한다.
    """
    probe = ep_dir / PROBE_DIR
    probe.mkdir(parents=True, exist_ok=True)

    per = int(cfg["scene"]["cuts_per_scene"])
    max_per = int(cfg["scene"].get("max_cuts_per_scene") or 0)
    base = cut_rows(ep)
    try:
        scenes = group_scenes(cfg, ep, base)
        scenegen.assign_layouts(scenes, list(cfg["scene"]["layout_templates"] or []),
                                str(cfg["scene"]["layout_pick"]),
                                f"{ep.run_id}:ep{ep.episode}")
    except scenegen.SceneError as exc:
        die(str(exc))

    # 이미 만들어 둔 scenes.json 이 있으면 레이아웃만 그대로 가져온다.
    # 레이아웃까지 달라지면 "말풍선 지시 때문인지 레이아웃 때문인지"를 못 가른다.
    cache = ep_dir / scenegen.CACHE_FILE
    if cache.exists():
        try:
            old = {int(s["scene_number"]): str(s.get("layout") or "")
                   for s in json.loads(cache.read_text(encoding="utf-8")).get("scenes") or []}
            for sc in scenes:
                if old.get(sc.scene_number):
                    sc.layout = old[sc.scene_number]
            print(f"[sfx-test] 레이아웃은 {rel(cache)} 의 것을 그대로 씁니다.")
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            print(f"[sfx-test] {rel(cache)} 에서 레이아웃을 읽지 못해 새로 고릅니다.")

    want = int(args.sfx_test)
    picked = [sc for sc in scenes if sc.scene_number == want]
    if not picked:
        die(f"Scene {want} 이 없습니다. 이 화는 Scene {scenes[0].scene_number}~"
            f"{scenes[-1].scene_number} 입니다 (컷 {len(base)}개 / {per}개씩 묶음).")
    scene = picked[0]

    tmpl = (ROOT / "prompts" / "scene_gen.txt").read_text(encoding="utf-8")
    client = make_text_client()
    print(f"[sfx-test] {client.describe()} 호출 — 새 프롬프트로 패널 서술 다시 생성...")
    try:
        sg_prompt = scenegen.build_prompt(tmpl, ep.title, scenes, ep.setting,
                                          ep.scenes, cfg)
        parsed, meta, secs = textgen.call_json(
            client, sg_prompt,
            int(cfg["retry"]["max_retries"]), float(cfg["retry"]["backoff_sec"]),
            on_retry=lambda a, e: print(f"    재시도 {a}: {e[:160]}"))
        missing = scenegen.fill_panels(scenes, parsed)
    except (textgen.TextError, scenegen.SceneError) as exc:
        die(f"sfx-test 의 scene_gen 실패: {exc}")
    if scene.scene_number in missing:
        die(f"scene_gen 이 Scene {want} 의 패널을 다 돌려주지 않았습니다.")

    # 결과는 따로 남긴다. scenes.json 을 덮으면 이미 만든 이미지와 기록이 어긋난다.
    (probe / "scenes_probe.json").write_text(json.dumps({
        "run_id": ep.run_id, "episode": ep.episode, "title": ep.title,
        "text_model": client.model, "generated_at": datetime.now().isoformat(timespec="seconds"),
        "cuts_per_scene": per, "note": "sfx-test 전용. 본 파이프라인은 scenes.json 을 씁니다.",
        "scenes": scenegen.to_json(scenes),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[sfx-test] 패널 서술 ({secs}s) -> {rel(probe / 'scenes_probe.json')}")

    candidates = int(cfg["scene"]["candidates_per_scene"])
    jobs: list[Job] = []
    for cname in conditions:
        cond = cfg["conditions"][cname]
        here = scene_has_main(scene, present)
        prompt = scenegen.assemble(
            cfg, appearance if here else ABSENT_NOTE, scene,
            (cond.get("extra") or "") if here else "", with_lock=here,
            style_text=style_block(cfg, scenegen.render_style(scene)))
        for k in range(1, candidates + 1):
            jobs.append(Job(
                condition=f"{PROBE_DIR}_{cname}", cut_number=scene.scene_number, candidate=k,
                description=scene.description(), dialogue=scene.dialogue(),
                scene="\n".join(f"Panel {i}: {p}" for i, p in enumerate(scene.panels, 1)),
                prompt=prompt, refs=[ref_path(r) for r in (cond.get("refs") or [])],
                use_previous_cut=False,   # 한 장만 뽑으므로 직전 컷 체인은 의미가 없다
                out_path=probe / cname / f"scene{scene.scene_number}_c{k}.png",
                stem="scene", unit="Scene"))

    print("=" * 78)
    print(f"[sfx-test] Scene {scene.scene_number} ({scene.label}) · 패널 {len(scene.panels)}개")
    print("[sfx-test] 레이아웃 —")
    for line in scenegen.layout_text(cfg, scene).splitlines():
        print(f"    {line}")
    print("-" * 78)
    print(jobs[0].prompt)
    print("=" * 78)

    confirm(len(jobs), 1, cfg, provider_factory.describe(), text_model or "(호출됨)", args.yes)

    ok_count, fail = 0, []
    for i, job in enumerate(jobs, 1):
        print(f"[{i}/{len(jobs)}] {job.condition} / Scene{job.cut_number} / c{job.candidate}")
        if run_job(job, provider_factory, cfg, ep_dir, {}, [scene.scene_number]):
            ok_count += 1
        else:
            fail.append(f"c{job.candidate}")

    path = report.build_contact_sheet(
        probe,
        {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title, "source": ep.source},
        [{"name": c, "label": str(cfg["conditions"][c].get("label") or "")} for c in conditions],
        [{"cut_number": scene.scene_number,
          "note": (f"{scene.label} · 크기 "
                   f"{'/'.join(str(c.get('size') or '?') for c in scene.cuts)}\n"
                   f"{scenegen.layout_text(cfg, scene)}"),
          "description": scene.description(), "dialogue": scene.dialogue(),
          "scene": "\n".join(f"Panel {i}: {p}" for i, p in enumerate(scene.panels, 1)),
          "warnings": lint_scene(" ".join(scene.panels),
                                 list(cfg["prompt_gen"]["banned_terms"]))}],
        candidates,
        {"image_model": image_model, "text_model": client.model,
         "style": str(cfg["style_suffix"]), "appearance": appearance},
        filename="probe.html", unit="Scene", file_pattern="{cond}/scene{n}_c{k}.png")

    print("\n" + "=" * 78)
    print(f"[sfx-test] 성공 {ok_count} / 실패 {len(fail)}")
    print(f"probe.html -> {rel(path)}")
    print("→ 열어서 확인할 것:")
    print("   1) 말풍선이 그려졌는가, 그리고 그 안이 정말 비어 있는가(글자·낙서 없음)")
    print("   2) 대사 있는 컷 수만큼만 그려졌는가 (대사 없는 패널에 말풍선이 없어야 함)")
    print("   3) 말풍선 크기가 대사 길이에 맞는가 (짧은 대사 = 작은 말풍선)")
    print("   4) 효과음 레터링이 한글로, 그림에 녹아들게 그려졌는가")
    print("괜찮으면 전체 재생성: "
          f"python run.py --run-id {ep.run_id} --episode {ep.episode} "
          f"--mode scene -c {' -c '.join(conditions)} --regen-prompts")
    return 1 if fail else 0


def sheet(cfg: dict[str, Any], ep: storyload.Episode, cuts: list[dict[str, Any]],
          ep_dir: Path, image_model: str, text_model: str, appearance: str) -> Path:
    conditions = [{"name": k, "label": str(v.get("label") or "")}
                  for k, v in cfg["conditions"].items()]
    return build_contact_sheet(
        ep_dir,
        {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title, "source": ep.source},
        conditions, cuts, int(cfg["candidates_per_cut"]),
        {"image_model": image_model, "text_model": text_model,
         "style": str(cfg["style_suffix"]), "appearance": appearance},
    )


def scene_sheet(cfg: dict[str, Any], ep: storyload.Episode, scenes: list[scenegen.Scene],
                ep_dir: Path, image_model: str, text_model: str, appearance: str) -> Path:
    """Scene 시트는 컷 시트와 파일이 다르다. picks.csv 는 같이 쓰되 조건 이름이 scene_* 이다."""
    conditions = [{"name": scene_cond(k), "label": str(v.get("label") or "")}
                  for k, v in cfg["conditions"].items()]
    return build_contact_sheet(
        ep_dir,
        {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title, "source": ep.source},
        conditions, scene_rows(cfg, scenes), int(cfg["scene"]["candidates_per_scene"]),
        {"image_model": image_model, "text_model": text_model,
         "style": str(cfg["style_suffix"]), "appearance": appearance},
        filename="contact_sheet_scene.html", unit="Scene",
        file_pattern="{cond}/scene{n}_c{k}.png",
    )


# --------------------------------------------------------------------------- #
def main() -> int:
    args = parse_args()
    cfg = load_config(Path(args.config))
    conditions = select_conditions(args, cfg)
    apply_candidates(cfg, args.candidates)

    # ---- 그림체 — 이 실험의 가장 큰 변수. 이름으로 고른다 ------------------- #
    cfg["style_suffix"] = select_style(cfg, args.style)
    style_name = style_name_of(cfg, cfg["style_suffix"])
    print(f"[style] {style_name} — {cfg['style_suffix'][:72]}"
          f"{'…' if len(cfg['style_suffix']) > 72 else ''}")

    # 흑백 그림체인가. 그림체 문구만으로는 부족하다 — 코드가 프롬프트 뒤쪽에
    # 팔레트(hex)와 효과음 색 지시를 강제로 붙이고, 뒤에 온 것이 이긴다. 이 값이
    # 켜지면 팔레트는 명도로 바뀌고(charsheet.ink_palette), scene_gen 은 색 낱말을
    # 쓰지 않게 되고, 프롬프트 맨 끝에 흑백 못이 박힌다(scenegen.MONO_TAIL).
    # monochrome_styles 는 목록이어도 되고 사전이어도 된다.
    #   - lineart            (목록 항목)  완전 무채색
    #   - lineart: [eyes]    (사전 항목)  eyes 만 색으로 남기는 스팟 컬러
    # 목록만 쓰던 config 를 그대로 두고도 돌아가야 하므로 둘 다 받는다.
    mono_cfg = cfg.get("monochrome_styles") or []
    if isinstance(mono_cfg, dict):
        mono_map = {str(k).strip(): [str(v).strip() for v in (val or [])]
                    for k, val in mono_cfg.items()}
    else:
        mono_map = {str(x).strip(): [] for x in mono_cfg}
    cfg["style_monochrome"] = style_name in mono_map
    cfg["style_accent_keys"] = mono_map.get(style_name, [])
    if cfg["style_monochrome"]:
        spots = cfg["style_accent_keys"]
        how = (f"{', '.join(spots)} 만 색으로 남기고 나머지를" if spots else "팔레트를")
        print(f"[style] 「{style_name}」 은 선화입니다 — {how} 명도로 바꾸고 "
              f"프롬프트 끝에 무채색 지시를 박습니다 (config 의 monochrome_styles).")

    # ---- 캐릭터 시트 — 외형·레퍼런스·디자인 고정 문구의 출처 ---------------- #
    # 손으로 적던 것(config 의 외형 문구, refs/turnaround.png)을 스토리 run 에서
    # 읽어 온다. 없으면 그대로 예전 길로 간다 — 예전 run 도 계속 돌아야 한다.
    sheets = charsheet.load(Path(str(cfg["story_runs_root"])), args.run_id)
    for note in sheets.notes:
        print(f"[charsheet] {note}")
    if sheets.has_images:
        print(f"[charsheet] {charsheet.describe(sheets)}")
    appearance = require_appearance(cfg, sheets)
    # --sheet-only 는 HTML 만 다시 만든다 (API 호출 0회). 시트가 없는 조건 때문에
    # 세우면, 볼 수 있는 결과까지 못 보게 된다.
    apply_charsheet(cfg, sheets, conditions, fatal=not args.sheet_only)

    # design_details / color_palette 는 style_suffix 와 같은 방식으로 코드가 박는다.
    # config.yaml 에 적는 값이 아니라 여기서 채우는 값이다.
    # 의상만은 config 에서 온다 — p1.json 이 옷을 여러 벌 나열하는 일이 잦고,
    # 어느 한 벌로 갈지는 사람이 정해야 하기 때문이다.
    outfit = str(cfg.get("outfit_lock") or "").strip()
    # 머리는 config 에 적지 않아도 된다 — 비어 있으면 appearance_en 에서 뽑는다.
    # 사람이 채워야 하는 칸을 늘리지 않기 위해서다. config 에 hair_lock 이 있으면
    # 그게 이긴다 (뽑아낸 구절이 어색할 때 덮어쓰는 용도).
    hair = str(cfg.get("hair_lock") or "").strip()
    cfg["design_lock"] = charsheet.lock_text(
        sheets, outfit, hair, monochrome=bool(cfg.get("style_monochrome")),
        accent_keys=cfg.get("style_accent_keys") or ())
    used_hair = hair or charsheet.hair_phrase(sheets.appearance if sheets else "")
    if used_hair:
        origin = "config.yaml 의 hair_lock" if hair else "p1.json 의 appearance_en"
        print(f"[머리] 머리를 고정합니다 — {used_hair}  ({origin})")
        note = charsheet.hair_warning(sheets, used_hair)
        if note:
            print(f"[머리] {note}")
    else:
        print("[머리] 머리 서술을 찾지 못했습니다 — appearance_en 에 hair 가 들어간 "
              "구절이 없습니다.\n"
              "       컷마다 길이가 달라질 수 있습니다 (시트가 있어도 짧아지는 쪽으로 "
              "흐릅니다).")
    accessory_note = charsheet.accessory_warning(
        sheets, sheets.appearance if sheets else "")
    if accessory_note:
        print(f"[소지품] {accessory_note}")
    if outfit:
        print(f"[outfit] 기본 의상을 고정합니다 — {outfit[:60]}"
              f"{'…' if len(outfit) > 60 else ''}")
    else:
        print("[outfit] config.yaml 의 outfit_lock 이 비어 있습니다 — 컷마다 옷이 "
              "달라질 수 있습니다.\n"
              "         p1.json 의 외형 문구가 옷을 여러 벌 나열하면 모델이 매번 "
              "다른 것을 고릅니다.")
    if cfg["design_lock"]:
        print(f"[charsheet] 디자인 고정 문구 {len(cfg['design_lock'])}자를 모든 "
              f"프롬프트에 붙입니다 (--dry-run 으로 전문 확인).")

    # ---- 두 번째 주연 — "그 한 사람" 시트가 있으면 따로 붙인다. 주연만
    # 시트를 뽑으므로(story.py --second-lead) 이건 주인공과 달리 없을 수
    # 있고, 없으면 그냥 예전처럼 supporting.block() 의 글자 고정만 쓰인다.
    second_lead = charsheet.load_second_lead(
        Path(str(cfg["story_runs_root"])), args.run_id)
    for note in second_lead.notes:
        print(f"[charsheet] {note}")
    if second_lead.has_images:
        print(f"[charsheet] 「{second_lead.name or '그 한 사람'}」 — "
              f"{charsheet.describe(second_lead)}")

    # ---- 존 서술 — 배경은 이미지로 굽지 않고 글로 넘긴다 -------------------- #
    zone_text = charsheet.load_zone_text(Path(str(cfg["story_runs_root"])), args.run_id)
    if zone_text:
        print(f"[존] 배경 서술 {len(zone_text)}개를 글로 넘깁니다 "
              f"({', '.join(sorted(zone_text))})")
        print("     배경 이미지를 미리 굽지 않습니다 — 틀린 곳은 series.json "
              "한 줄을 고치면 다음 컷부터 반영됩니다.")

    env = load_dotenv(ROOT / ".env")
    ep_dir = OUT_ROOT / args.run_id / f"ep{args.episode}"

    # ---- 조연 — 시트가 없는 인물들. 채운 만큼만 고정된다 -------------------- #
    book = supporting.load(ep_dir, sheets.run_dir, sheets.name)
    cfg["supporting_book"] = book
    for note in book.notes:
        print(f"[조연] {note}")
    if book.people:
        print(f"[조연] {len(book.people)}명 중 {len(book.filled)}명 고정됨 "
              f"({rel(supporting.supporting_path(ep_dir))})")
        if book.empty:
            print(f"       빈 칸: {', '.join(p.name for p in book.empty)} — "
                  f"비워 두면 컷마다 얼굴도 성별도 달라집니다. "
                  f"appearance 를 영어로 채우세요.")

    # ---- --view: 배치만 바꿔 보는 도구. 생성 경로를 전혀 타지 않는다. ------- #
    if args.view:
        return run_viewer(cfg, args, ep_dir, conditions)

    # ---- --style-lock: 이미 뽑아 둔 것을 보기만 한다. 여기도 생성 경로 밖. -- #
    if args.style_lock:
        return run_style_lock(cfg, args, ep_dir, conditions)

    # 모델 이름은 .env 에서만 온다. dry-run/sheet-only 에서는 없어도 진행한다.
    need_api = not (args.dry_run or args.sheet_only)
    if args.sfx_test is not None and args.dry_run:
        die("--sfx-test 는 실제로 이미지를 뽑아 보는 옵션이라 --dry-run 과 같이 쓸 수 없습니다.")
    image_model = (env_model(env, str(cfg["provider"]["model_env"]), "이미지") if need_api
                   else str(env.get(cfg["provider"]["model_env"]) or "(미설정)"))
    text_model = (str(env.get(cfg["text"]["model_env"]) or "").strip()
                  or ("(미설정)" if not need_api else ""))

    # style_suffix 는 두 하네스의 유일한 공통 기준점이다. 어긋난 채로 뽑으면
    # 시트가 가리키는 그림체와 컷이 향하는 그림체가 다른 곳이 된다.
    for warn in charsheet.style_warning(sheets, str(cfg["style_suffix"]), image_model,
                                        style_name):
        print(f"[경고] {warn}\n")
    warn = charsheet.gender_warning(sheets, str(cfg.get("character_gender") or ""))
    if warn:
        print(f"[경고] {warn}\n")

    def make_text_client():
        # 텍스트 LLM 은 이미지 provider 와 따로 고른다 (config 의 text.provider).
        model = env_model(env, str(cfg["text"]["model_env"]), "텍스트")
        return textgen.build(
            provider=str(cfg["text"].get("provider") or "gemini"),
            model=model,
            api_key=env_key(env, str(cfg["text"]["api_key_env"])),
            options={k: cfg["text"][k] for k in ("temperature", "max_output_tokens", "timeout_sec")},
        )

    # ---- 1. load ---------------------------------------------------------- #
    ep = load_episode(cfg, args, ep_dir, make_text_client)
    cut_numbers = [c.cut_number for c in ep.cuts]
    print(f"[load] {ep.run_id} · {ep.episode}화 「{ep.title}」 컷 {len(ep.cuts)}개 (출처 {ep.source})")

    # ---- 컷마다 누가 나오는가 ---------------------------------------------- #
    # 이 하네스는 등장인물 1명을 전제로 만들어졌다. w7 컷에는 여러 인물이 나온다 —
    # 주인공이 없는 컷에 주인공 시트를 붙이면 조연이 주인공 얼굴로 그려진다.
    present, cast_source = resolve_cast(cfg, args, ep, ep_dir, sheets)

    # 그 한 사람도 같은 방식으로 가른다 — 다만 cast.json 은 주인공 전용 파일
    # 이라 다시 쓰지 않는다. 같은 파일에 두 사람 몫을 번갈아 쓰면 나중 실행이
    # 먼저 것을 덮어써 엉뚱한 사람의 참/거짓이 된다. 그래서 이름 대조(예비
    # 경로)만 쓴다 — 틀리면 지금은 사람이 고칠 파일이 없다는 뜻이고, 그건
    # 다음에 조연용 override 를 추가할 이유가 된다.
    present_2nd: dict[int, bool] | None = None
    if second_lead.has_images and second_lead.name:
        keys_2nd = cast.name_keys(second_lead.name)
        if keys_2nd:
            present_2nd = cast.from_cuts(cut_rows(ep), keys_2nd)
            on_2nd = sum(1 for v in present_2nd.values() if v)
            print(f"[cast] 「{second_lead.name}」 나오는 장면 "
                  f"{on_2nd}/{len(present_2nd)} (이름 대조)")

    want_cut = args.mode in ("cut", "both")
    want_scene = args.mode in ("scene", "both")

    # ---- sfx-test: Scene 하나만 뽑아 보는 최소 실행 ------------------------ #
    if args.sfx_test is not None:
        check_refs(cfg, conditions, fatal=True)
        provider = build_provider(
            name=str(cfg["provider"]["name"]), model=image_model,
            api_key=(env_key(env, str(cfg["provider"]["api_key_env"]))
                     if str(cfg["provider"]["name"]) != "mock" else None),
            options=dict(cfg["provider"].get("options") or {}))
        return run_probe(cfg, args, ep, ep_dir, conditions, appearance, provider,
                         make_text_client, image_model, text_model, present)

    # ---- verify-all: 한 벌 뽑아 네 가지를 동시에 재는 통합 검증 ------------- #
    if args.verify_all:
        check_refs(cfg, conditions, fatal=not args.dry_run)
        provider = None
        if not args.dry_run:
            provider = build_provider(
                name=str(cfg["provider"]["name"]), model=image_model,
                api_key=(env_key(env, str(cfg["provider"]["api_key_env"]))
                         if str(cfg["provider"]["name"]) != "mock" else None),
                options=dict(cfg["provider"].get("options") or {}))
        return run_verify_all(cfg, args, ep, ep_dir, conditions, appearance, provider,
                              make_text_client, image_model, text_model, present)

    # ---- sheet-only ------------------------------------------------------- #
    if args.sheet_only:
        picks = load_picks(ep_dir)
        if want_cut:
            cache = ep_dir / "prompts.json"
            if not cache.exists():
                die(f"prompts.json 이 없습니다: {rel(cache)}\n"
                    f"        먼저 한 번 생성하거나 --dry-run 없이 실행하세요.")
            cuts = json.loads(cache.read_text(encoding="utf-8"))["cuts"]
            if args.cuts.strip():
                spec = args.cuts.strip()
                lo, hi = ((int(x) for x in spec.split("-")) if "-" in spec
                          else (int(spec), int(spec)))
                lo, hi = int(lo), int(hi)
                cuts = [c for c in cuts if lo <= int(c["cut_number"]) <= hi]
            path = sheet(cfg, ep, cuts, ep_dir, image_model, text_model or "(미설정)", appearance)
            print(f"contact_sheet.html       -> {rel(path)}")
            # 컷 모드는 여기서 세로 조립까지 한다. 말풍선 좌표를 고친 뒤 다시
            # 붙이는 길이 이것이다 — 이미지 생성 없이(0원) 반영된다.
            write_strip(cfg, ep_dir, cuts, conditions, sheets, second_lead,
                        rel, ep.title)
        if want_scene:
            cache = ep_dir / scenegen.CACHE_FILE
            if not cache.exists():
                die(f"{scenegen.CACHE_FILE} 이 없습니다: {rel(cache)}\n"
                    f"        먼저 --mode scene 으로 한 번 실행하세요.")
            data = json.loads(cache.read_text(encoding="utf-8"))
            base = cut_rows(ep)
            try:
                scenes = scenegen.from_json(data.get("scenes") or [], base)
            except scenegen.SceneError as exc:
                die(str(exc))
            path = scene_sheet(cfg, ep, scenes, ep_dir, image_model,
                               text_model or "(미설정)", appearance)
            print(f"contact_sheet_scene.html -> {rel(path)}")
            # 채택을 바꿨거나 이미지를 손봤을 때 다시 붙이는 길. API 호출 0회.
            cond, paths = episode.pick_paths(
                ep_dir, [scene_cond(c) for c in conditions],
                [sc.scene_number for sc in scenes], picks)
            gone = sum(1 for p in paths if not p.exists())
            try:
                out = episode.episode_path(ep_dir)
                w, h = episode.stitch(paths, out)
                print(f"{episode.EPISODE_FILE}              -> {rel(out)} "
                      f"({cond} · {len(paths) - gone}장 · {w}x{h}px"
                      f"{f' — {gone}장 빠짐' if gone else ''})")
            except episode.StitchError as exc:
                print(f"[경고] 1화를 이어 붙이지 못했습니다: {exc}")
            # 검토 화면도 여기서 다시 만든다. 적어 둔 피드백은 feedback.json 에
            # 있으므로 다시 만들어도 잃지 않는다 (박혀 들어와 복원된다).
            rv = review.build(
                ep_dir,
                {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title},
                scenes, cond,
                {"style_name": style_name_of(cfg, str(cfg["style_suffix"])),
                 "image_model": image_model}, picks=picks)
            print(f"{review.REVIEW_FILE}               -> {rel(rv)}")
        print(f"picks.csv                -> {rel(picks_path(ep_dir))} "
              f"({'채택 ' + str(len(picks)) + '건' if picks else '아직 없음'})")
        note = report.feedback_summary(ep_dir)
        print(f"{report.FEEDBACK_FILE}            -> {rel(report.feedback_path(ep_dir))} "
              f"({note or '아직 없음'}) — review.html 의 피드백 칸에 적고 내려받으세요.")
        return 0

    check_refs(cfg, conditions, fatal=not args.dry_run)

    # ---- 2. prompt_gen / scene_gen ---------------------------------------- #
    cuts: list[dict[str, Any]] = []
    scenes: list[scenegen.Scene] = []
    # --cuts 는 **생성** 필터일 뿐이다. 조립(strip·episode.png·contact sheet)은
    # 언제나 이 화 전체를 본다. 예전에는 필터된 목록을 그대로 조립에 넘겨서,
    # 컷 하나만 다시 뽑으면 strip.html 과 episode.png 가 그 한 장짜리로
    # 덮어써졌다 — 12컷을 다 뽑아 놓고 12번만 손보면 나머지 11장이 화면에서
    # 사라졌다.
    all_cuts: list[dict[str, Any]] = []
    all_scenes: list[scenegen.Scene] = []
    jobs: list[Job] = []
    n_text_calls = 0

    if want_cut:
        existed = (ep_dir / "prompts.json").exists() and not args.regen_prompts
        cuts = generate_prompts(cfg, args, ep, ep_dir, make_text_client)
        all_cuts = list(cuts)
        if args.cuts.strip():
            spec = args.cuts.strip()
            try:
                lo, hi = (int(x) for x in spec.split("-")) if "-" in spec                     else (int(spec), int(spec))
            except ValueError:
                die(f"--cuts 값을 알 수 없습니다: {spec} (예: 3 또는 1-3)")
            cuts = [c for c in cuts if lo <= int(c["cut_number"]) <= hi]
            if not cuts:
                die(f"--cuts {spec} 에 해당하는 컷이 없습니다.")
            print(f"[cut] --cuts {spec} — 컷 {len(cuts)}개만 생성합니다 "
                  f"({', '.join(str(c['cut_number']) for c in cuts)})")
        n_text_calls += 0 if (existed or args.dry_run) else 1
        cut_jobs = build_jobs(cfg, appearance, cuts, conditions, ep_dir, present,
                              second_lead=second_lead, present_2nd=present_2nd,
                              zone_text=zone_text,
                              episode_cut_numbers=[int(c["cut_number"])
                                                   for c in all_cuts])
        enforce_per_condition(cfg, len(ep.cuts), int(cfg["candidates_per_cut"]))
        jobs += cut_jobs

    if want_scene:
        existed = (ep_dir / scenegen.CACHE_FILE).exists() and not args.regen_prompts
        scenes = generate_scenes(cfg, args, ep, ep_dir, make_text_client)
        all_scenes = list(scenes)
        # --cuts 는 Scene 모드에도 걸린다. 새 설정을 몇 컷만 확인할 때 12컷을
        # 다 뽑고 나서 틀린 걸 알면 그만큼이 버려진다.
        if args.cuts.strip():
            spec = args.cuts.strip()
            try:
                lo, hi = ((int(x) for x in spec.split("-")) if "-" in spec
                          else (int(spec), int(spec)))
                lo, hi = int(lo), int(hi)
            except ValueError:
                die(f"--cuts 값을 알 수 없습니다: {spec} (예: 3 또는 1-3)")
            scenes = [sc for sc in scenes
                      if any(lo <= n <= hi for n in sc.cut_numbers)]
            if not scenes:
                die(f"--cuts {spec} 에 해당하는 Scene 이 없습니다.")
            print(f"[scene] --cuts {spec} — Scene {len(scenes)}장만 생성합니다 "
                  f"(컷 {', '.join(str(n) for sc in scenes for n in sc.cut_numbers)})")
        n_text_calls += 0 if (existed or args.dry_run) else 1
        scene_jobs = build_scene_jobs(cfg, appearance, scenes, conditions, ep_dir,
                                      present, second_lead=second_lead,
                                      present_2nd=present_2nd, zone_text=zone_text)
        enforce_per_condition(cfg, len(scenes), int(cfg["scene"]["candidates_per_scene"]),
                              unit="Scene", knob="scene.candidates_per_scene")
        jobs += scene_jobs

    # ---- 3. render -------------------------------------------------------- #
    enforce_total(jobs, cfg, conditions)
    scene_numbers = [sc.scene_number for sc in scenes]

    def numbers_for(job: Job) -> list[int]:
        """COND_D 체인이 쓸 단위 번호 목록. 모드마다 다르다."""
        return scene_numbers if job.stem == "scene" else cut_numbers

    if args.skip_existing:
        before = len(jobs)
        jobs = [j for j in jobs if not j.out_path.exists()]
        if before != len(jobs):
            print(f"[skip-existing] 이미 있는 {before - len(jobs)}건 제외")

    picks = load_picks(ep_dir)
    if picks:
        print(f"[picks] {rel(picks_path(ep_dir))} 에서 채택 {len(picks)}건 읽음")

    if args.dry_run:
        print_dry_run(jobs, ep_dir, picks, numbers_for)
        usd, krw = cost_of(len(jobs), 1, cfg)
        print("=" * 78)
        print(f"[dry-run] 모드 {args.mode} / 조건 {', '.join(conditions)} / "
              f"그림체 {style_name_of(cfg, str(cfg['style_suffix']))}")
        print(f"[dry-run] 컷별 등장 판정 — 틀린 줄은 {cast.CAST_FILE} 에서 고치세요:")
        print(cast.table(cut_rows(ep), present, cast_source,
                         sheets.name or str(cfg.get("character_name") or "주인공")))
        if want_cut:
            print(f"[dry-run] 컷 {len(ep.cuts)} x 후보 {cfg['candidates_per_cut']}")
        if want_scene:
            print(f"[dry-run] Scene {len(scenes)} x 후보 "
                  f"{cfg['scene']['candidates_per_scene']} "
                  f"(묶음 {'+'.join(str(len(sc.cuts)) for sc in scenes)} · "
                  f"{'연출 기준' if grouping_mode(cfg, ep) == 'direction' else '고정 분할'})")
        print(f"[dry-run] 이미지 호출 {len(jobs)}회 예정, "
              f"예상 비용 {krw:,.0f}원 (약 ${usd:,.2f})")
        print(f"[dry-run] 조건당 상한 {cfg['limits']['max_calls_per_condition']}회 / "
              f"전체 상한 {cfg['limits']['max_total_calls']}회")
        if want_cut and want_scene:
            print_comparison(call_counts(cfg, len(ep.cuts), len(scenes)))
        print("[dry-run] API 호출은 하지 않았습니다. 위 장면 서술은 자리표시자입니다.")
        return 0

    # 아래 둘은 **화 전체**를 본다 (all_cuts / all_scenes). --cuts 로 몇 컷만
    # 다시 뽑아도 화면에는 12컷이 그대로 남아야 한다 — 방금 뽑지 않은 컷은
    # 이미 디스크에 있는 이미지를 그대로 쓴다.
    def write_sheets() -> None:
        if want_cut:
            path = sheet(cfg, ep, all_cuts, ep_dir, image_model, text_model, appearance)
            print(f"contact_sheet.html       -> {rel(path)}")
        if want_scene:
            path = scene_sheet(cfg, ep, all_scenes, ep_dir, image_model, text_model,
                               appearance)
            print(f"contact_sheet_scene.html -> {rel(path)}")

    def write_episode() -> None:
        """만들려는 것은 장면 모음이 아니라 **웹툰 한 편**이다. 세로로 이어
        붙인 한 장을 남긴다 — 그것이 결과물이고, 나머지는 과정이다."""
        if want_cut and all_cuts:
            write_strip(cfg, ep_dir, all_cuts, conditions, sheets, second_lead,
                        rel, ep.title)
            return
        if not want_scene or not all_scenes:
            return
        picks = load_picks(ep_dir)
        cond, paths = episode.pick_paths(
            ep_dir, [scene_cond(c) for c in conditions],
            [sc.scene_number for sc in all_scenes], picks)
        missing = [p for p in paths if not p.exists()]
        try:
            w, h = episode.stitch(paths, episode.episode_path(ep_dir))
            note = f" — {len(missing)}장 빠짐" if missing else ""
            print(f"{episode.EPISODE_FILE}              -> "
                  f"{rel(episode.episode_path(ep_dir))} "
                  f"({cond} · {len(paths) - len(missing)}장 · {w}x{h}px{note})")
        except episode.StitchError as exc:
            # 이어 붙이기가 실패해도 검토 화면은 만든다 — 오히려 그때 더 필요하다.
            # 어느 장이 비었는지 보려면 화면이 있어야 한다.
            print(f"[경고] 1화를 이어 붙이지 못했습니다: {exc}")
        # 완성본을 읽으면서 컷마다 피드백을 남기는 화면. 컨택트 시트(후보 고르는
        # 표)와 목적이 다르다 — 고칠 것은 순서대로 통독할 때 보인다.
        rv = review.build(
            ep_dir,
            {"run_id": ep.run_id, "episode": ep.episode, "title": ep.title},
            all_scenes, cond,
            {"style_name": style_name_of(cfg, str(cfg["style_suffix"])),
             "image_model": image_model}, picks=picks)
        print(f"{review.REVIEW_FILE}               -> {rel(rv)}")

    if not jobs:
        print("생성할 이미지가 없습니다 (전부 이미 있음).")
        write_sheets()
        write_episode()
        # 이미지는 안 만들었어도 텍스트 호출은 돌았을 수 있다. 그 비용도 남긴다.
        print_usage(usage_rollup(ep_dir, cfg))
        return 0

    provider = build_provider(
        name=str(cfg["provider"]["name"]), model=image_model,
        api_key=(env_key(env, str(cfg["provider"]["api_key_env"]))
                 if str(cfg["provider"]["name"]) != "mock" else None),
        options=dict(cfg["provider"].get("options") or {}),
    )
    confirm(len(jobs), n_text_calls, cfg, provider.describe(),
            text_model or "(미호출)", args.yes)

    started = time.time()
    ok_count, fail = 0, []
    for i, job in enumerate(jobs, 1):
        print(f"[{i}/{len(jobs)}] {job.condition} / {job.unit}{job.cut_number} / c{job.candidate}")
        if run_job(job, provider, cfg, ep_dir, picks, numbers_for(job),
                   image_model, env):
            ok_count += 1
        else:
            fail.append(f"{job.condition}/{job.stem}{job.cut_number}_c{job.candidate}")

    print("\n" + "=" * 78)
    print(f"완료: 성공 {ok_count} / 실패 {len(fail)} (총 {round(time.time() - started, 1)}s)")
    if fail:
        print("실패 목록: " + ", ".join(fail))
        print("→ 같은 명령에 --skip-existing 을 붙이면 실패한 건만 다시 시도합니다.")

    print(f"\nlog.jsonl                -> {rel(ep_dir / 'log.jsonl')}")
    rollup = usage_rollup(ep_dir, cfg)
    if rollup:
        print(f"{USAGE_FILE}               -> {rel(ep_dir / USAGE_FILE)}")
        print(f"{USAGE_CSV}                -> {rel(ep_dir / USAGE_CSV)}")
    if want_cut:
        print(f"prompts.json             -> {rel(ep_dir / 'prompts.json')}")
    if want_scene:
        print(f"{scenegen.CACHE_FILE}             -> {rel(ep_dir / scenegen.CACHE_FILE)}")
    write_sheets()
    write_episode()
    note = report.feedback_summary(ep_dir)
    if note:
        print(f"{report.FEEDBACK_FILE}            -> {rel(report.feedback_path(ep_dir))} "
              f"({note}) — 시트에 그대로 복원됩니다.")
    print_usage(rollup)
    if want_cut and want_scene:
        print_comparison(call_counts(cfg, len(ep.cuts), len(scenes)))
    print("→ 브라우저로 열어 후보를 고르고 [picks.csv 내려받기] 로 같은 폴더에 저장하세요.")
    if want_scene:
        print(f"   그 다음 --mode {args.mode} --view 로 뷰어를 만들면 세로로 이어 볼 수 있습니다.")
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
