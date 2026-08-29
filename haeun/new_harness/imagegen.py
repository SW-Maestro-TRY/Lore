#!/usr/bin/env python3
"""이미지 한 장을 그린다. 시트도 페이지도 여기를 지난다.

실제 호출은 story-harness 의 make_sheet_painter 를 그대로 쓴다 — 컷을 그리는
코드와 같은 경로다. 여기서 다시 구현하면 재시도·응답 파싱·참조 이미지 첨부가
조금씩 달라지고, 그 차이가 그림 차이로 나타난다.

story.py 는 한 글자도 안 고친다(harness-is-final). 대신 그 쪽 크기표에
**페이지용 칸을 하나 더 단다** — make_sheet_painter 가 kind 로 그 표를 찾기
때문에, 표에 없는 이름을 넘기면 KeyError 다. 기존 칸은 안 건드리므로 시트는
예전과 똑같이 나온다.
"""

from __future__ import annotations

from pathlib import Path

import llm
from llm import story

SHEET_KIND = "sheet"        # 가로로 넓은 자료 시트 (story.py 가 이미 아는 칸)
PAGE_KIND = "page"          # 세로로 읽는 웹툰 페이지 — 여기서 더한다

# 세로 스크롤 웹툰이라 페이지는 세로로 길어야 한다.
#
# ★ 두 프로바이더의 캔버스 **모양이 다르다.** 같은 프롬프트를 줘도 한 페이지에
#   들어가는 세로 길이가 달라진다:
#
#       Gemini  9:16       = 세로/가로 1.78
#       OpenAI  1024x1536  = 세로/가로 1.50   (약 16% 짧다)
#
#   OpenAI 는 gpt-image 가 받는 크기가 1024x1024 · 1024x1536 · 1536x1024
#   셋뿐이라 더 긴 값을 줄 수가 없다. Gemini 도 9:16 이 천장이다 —
#   webtoon-harness 가 실측해 뒀다(config.yaml: 1:2 · 9:21 · 1:3 은 전부 400,
#   image_size 를 올려도 픽셀만 늘고 캔버스 모양은 같다).
#
#   그래서 한 페이지에 컷을 많이 모을수록 각 컷이 납작해지고, 그 정도가
#   프로바이더마다 다르다. pages.max_ratio 를 만들어 두고 기본을 꺼 놓은 이유가
#   이것이다 — 쓰는 모델을 정한 뒤에 그 캔버스로 붙여 보고 정해야 한다.
PAGE_SIZE = "1024x1536"     # OpenAI
PAGE_RATIO = "9:16"         # Gemini

story.CHARSHEET_SIZES.setdefault(PAGE_KIND, PAGE_SIZE)
story.CHARSHEET_RATIOS.setdefault(PAGE_KIND, PAGE_RATIO)


def backend_for(stage: str) -> tuple[str, str, str]:
    """(provider, model, quality). 못 쓰면 왜 못 쓰는지를 달고 멈춘다."""
    provider = llm.provider_for(stage).strip().lower()
    ok, default_model, why = story.image_backend_ready(provider)
    if not ok:
        raise SystemExit(why)
    model = llm.model_for(stage, provider) or default_model
    quality = (llm.env("OPENAI_IMAGE_QUALITY") or "high").strip().lower()
    return provider, model, quality


def paint(stage: str, prompt: str, out_path: Path, refs=None,
          kind: str = SHEET_KIND) -> dict:
    """한 장 그려서 저장한다. refs 는 같이 붙일 참조 이미지 경로.

    refs 순서가 곧 모델이 보는 순서다. 부르는 쪽이 정한다 — 시트를 먼저,
    직전 페이지를 마지막에 두는 것이 webtoon-harness 가 쓰는 순서와 같다.
    """
    provider, model, quality = backend_for(stage)
    refs = [Path(r) for r in (refs or []) if Path(r).exists()]

    painter, label = story.make_sheet_painter(provider, model, quality, refs)
    data, meta = painter(prompt, kind)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    return {"stage": stage, "provider": provider, "model": model, "backend": label,
            "quality": quality, "bytes": len(data),
            "refs": [r.name for r in refs], "meta": meta or {}}
