#!/usr/bin/env python3
"""캐릭터 시트 — 사양(JSON) 검사와 이미지 프롬프트, 그리고 실제 그리기.

이미지 호출 자체는 story-harness/story.py 의 make_sheet_painter 를 그대로 쓴다
(컷을 그리는 코드와 같은 경로다). 여기서 새로 짜는 것은 **무엇을 그릴지**뿐이다.

story.py 의 시트와 다른 점은 하나, 소지품(props) 영역이 있다는 것이다. 그래서
공통 지시도 여기 따로 둔다 — story.py 의 SHEET_COMMON_EN 은 "no props" 라고
못 박고 있어서 그대로 쓰면 소지품 영역이 지워진다.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import llm
from llm import story

PALETTE_KEYS = story.PALETTE_KEYS            # hair eyes skin outfit_main outfit_sub accent
EXPRESSION_COUNT = story.EXPRESSION_COUNT    # 6
DETAIL_MIN, DETAIL_MAX = story.DESIGN_DETAIL_MIN, story.DESIGN_DETAIL_MAX
HANGUL_RE = story.HANGUL_RE
HEX_RE = story.HEX_RE

MAX_PROPS = 4

SHEET_KIND = "sheet"
SHEET_SIZE = "1536x1024"
SHEET_RATIO = "16:9"

COMMON_EN = (
    "Character reference sheet for a Korean webtoon production. "
    "Flat even lighting, pure white background (#FFFFFF), no cast shadows, "
    "no text, no labels, no watermark, no logo, no signature. "
    "Clean line art with flat colors. The character is identical in every "
    "figure on the sheet."
)


def _numbered(items) -> str:
    return "\n".join(f"  {i}. {x}" for i, x in enumerate(items, 1))


def parse_spec(text: str) -> dict:
    """모델 응답에서 사양 JSON 을 꺼내 모양을 맞춘다."""
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("시트 사양이 JSON 객체가 아닙니다.")
    palette = obj.get("color_palette") if isinstance(obj.get("color_palette"), dict) else {}
    return {
        "name": str(obj.get("name") or "").strip(),
        "appearance_en": str(obj.get("appearance_en") or "").strip(),
        "design_details": [str(d).strip() for d in (obj.get("design_details") or [])
                           if str(d or "").strip()],
        "props": [str(p).strip() for p in (obj.get("props") or []) if str(p or "").strip()],
        "color_palette": {k: str(palette.get(k) or "").strip() for k in PALETTE_KEYS},
        "expression_set": [str(e).strip() for e in (obj.get("expression_set") or [])
                           if str(e or "").strip()],
    }


def gate_spec(spec: dict) -> list[str]:
    """그리기 전에 사양이 실제로 있는지 본다.

    사양 없이 이미지를 부르면 빈칸을 모델이 학습 데이터 평균값으로 채운다.
    그렇게 나온 시트는 "컷마다 다른 사람" 을 막지 못한다 — 돈만 쓰고 끝난다.
    """
    bad = []
    if not spec["name"]:
        bad.append("name 이 비어 있습니다.")

    if not spec["appearance_en"]:
        bad.append("appearance_en 이 없습니다. 이미지 프롬프트의 본문입니다.")
    elif HANGUL_RE.search(spec["appearance_en"]):
        bad.append("appearance_en 에 한글이 섞여 있습니다. 이미지 모델에 그대로 들어갑니다.")

    n = len(spec["design_details"])
    if not DETAIL_MIN <= n <= DETAIL_MAX:
        bad.append(f"design_details 가 {n}개입니다 ({DETAIL_MIN}~{DETAIL_MAX}개). "
                   "고정 요소가 없으면 시트를 뽑아도 컷마다 다른 사람이 됩니다.")

    if len(spec["props"]) > MAX_PROPS:
        bad.append(f"props 가 {len(spec['props'])}개입니다 (최대 {MAX_PROPS}개). "
                   "많을수록 한 장 안에서 서로를 뭉갭니다.")

    if len(spec["expression_set"]) != EXPRESSION_COUNT:
        bad.append(f"expression_set 이 {len(spec['expression_set'])}개입니다 "
                   f"(정확히 {EXPRESSION_COUNT}개). 표정 시트는 이 목록을 그대로 그립니다.")

    empty = [k for k in PALETTE_KEYS if not spec["color_palette"].get(k)]
    if empty:
        bad.append(f"color_palette 의 {empty} 가 비어 있습니다.")
    else:
        no_hex = [k for k in PALETTE_KEYS if not HEX_RE.search(spec["color_palette"][k])]
        if no_hex:
            bad.append(f"color_palette 의 {no_hex} 에 #RRGGBB 가 없습니다. "
                       "hex 가 없으면 컷마다 색이 달라집니다.")
    return bad


def build_prompt(spec: dict, style: str = None) -> str:
    """사양 -> 이미지 프롬프트 한 장.

    영역을 말로만 나누면 모델이 섞어 버린다. 그래서 자리(위·가운데·아래)와
    "영역 사이에 여백" 을 못 박고, 각 영역의 개수까지 숫자로 준다.

    한국어 사양(design_details·props·expression_set)은 번역하지 않고 그대로
    싣는다. "왼쪽 소매의 노란 반사띠" 를 "yellow stripe" 로 옮기면 위치가
    사라져서 고정 요소가 고정이 아니게 된다.
    """
    palette = spec["color_palette"]
    color_line = " / ".join(f"{k}: {palette[k]}" for k in PALETTE_KEYS if palette.get(k))
    n_details = len(spec["design_details"])
    props = spec["props"]

    parts = [
        COMMON_EN,
        "",
        "[CHARACTER SHEET — ONE PAGE, SEPARATE REGIONS]",
        "A single landscape sheet holding the regions below, stacked with clear empty "
        "white space between them so each region reads as its own block. "
        "No frames, no borders, no captions, no labels.",
        "",
        "REGION 1 — TOP BAND: turnaround.",
        "  The SAME character four times in one horizontal row, left to right:",
        "  (1) front view  (2) three-quarter view  (3) side view  (4) back view.",
        "  Full body, standing at attention, arms relaxed at the sides, feet together,",
        "  neutral expression, camera at eye level.",
        "  All four stand on one shared ground line with exactly the same height and the",
        "  same proportions. The outfit, hair length and body type do not change between",
        "  views. The character carries nothing in these four figures.",
        "",
        f"REGION 2 — MIDDLE BAND: {EXPRESSION_COUNT} expressions.",
        f"  The SAME character's head and shoulders {EXPRESSION_COUNT} times in one",
        "  horizontal row, evenly spaced, all the same size, all facing the camera at the",
        "  same angle. Only the expression changes; face shape, hairstyle and hair length",
        "  are identical in all of them.",
        "",
        f"REGION 3 — BOTTOM LEFT: {n_details} close-up insets, one per fixed design "
        "element, each showing only that element, enlarged.",
    ]

    if props:
        parts += [
            "",
            f"REGION 4 — BOTTOM MIDDLE: {len(props)} carried items, drawn on their own as "
            "separate objects laid out in a row, not held by the character and with no "
            "character in this region. Each item is drawn from the angle that reads best, "
            "at a size that makes its material and wear visible.",
            "",
            "REGION 5 — BOTTOM RIGHT: one horizontal row of flat color swatch chips, one "
            "chip per palette entry, in the listed order.",
        ]
    else:
        parts += [
            "",
            "REGION 4 — BOTTOM RIGHT: one horizontal row of flat color swatch chips, one "
            "chip per palette entry, in the listed order.",
        ]

    parts += [
        "",
        f"CHARACTER\n{spec['appearance_en']}",
        "",
        f"COLOR PALETTE (use exactly these, and these are the chips in the swatch row)\n"
        f"{color_line}",
        "",
        "FIXED DESIGN ELEMENTS — visible and identical everywhere on the sheet, and one "
        "inset each in region 3. Written in Korean; follow them literally:",
        _numbered(spec["design_details"]),
    ]

    if props:
        parts += [
            "",
            "CARRIED ITEMS to draw as separate objects. Written in Korean; the size, "
            "material and wear described are what to draw:",
            _numbered(props),
        ]

    parts += [
        "",
        "EXPRESSIONS for region 2, left to right. Written in Korean; the part after the "
        "dash describes exactly what to draw:",
        _numbered(spec["expression_set"]),
    ]

    style = style if style is not None else story.read_style_suffix()[0]
    return "\n".join(parts) + f"\n\nSTYLE\n{style}\n"


def paint(prompt: str, out_path: Path, photos=None,
          provider: str = None, model: str = None, quality: str = None) -> dict:
    """시트 한 장을 그려 저장한다. (meta)

    provider/model 을 안 주면 .env 의 SHEET_IMAGE_PROVIDER / SHEET_IMAGE_MODEL 을
    본다 — 다른 단계와 같은 규칙이다 (llm.py 참고).
    """
    provider = (provider or llm.env("SHEET_IMAGE_PROVIDER")
                or llm.env("NH_IMAGE_PROVIDER") or "gemini").strip().lower()
    if provider not in story.IMAGE_PROVIDERS:
        raise SystemExit(f"SHEET_IMAGE_PROVIDER='{provider}' 는 "
                         f"{' / '.join(story.IMAGE_PROVIDERS)} 중 하나여야 합니다.")

    ok, default_model, why = story.image_backend_ready(provider)
    if not ok:
        raise SystemExit(why)
    model = model or llm.env("SHEET_IMAGE_MODEL") or default_model
    quality = (quality or llm.env("OPENAI_IMAGE_QUALITY") or "high").strip().lower()

    painter, label = story.make_sheet_painter(provider, model, quality, list(photos or []))
    data, meta = painter(prompt, SHEET_KIND)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    return {"provider": provider, "model": model, "backend": label,
            "quality": quality, "bytes": len(data), "meta": meta or {}}
