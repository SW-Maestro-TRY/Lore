"""실패를 사람에게 설명할 수 있게 남기는 자리 (#531).

하네스가 실패하면 자바는 끝난 코드(0 이 아님)만 받는다. 그래서 화면에는
「캐릭터 시트를 만들지 못했습니다」처럼 **어디서** 멈췄는지만 뜨고 **왜**
멈췄는지는 안 떴다. 여기서 이유를 작품 폴더의 `failure.json` 에 적으면
자바(`JobRunner`)가 읽어 사람에게 할 말을 고른다.

    {"stage": "SHEET_IMAGE", "code": "image_safety",
     "categories": ["sexual"], "message": "…원문 에러…"}

코드는 셋이다.
  image_safety   그림 모델의 안전 검사에 걸렸다(다시 한 번 그려도 또 걸림)
  photo_missing  올린 사진을 찾지 못했다
  error          그 밖의 것

그림 안전 검사는 **한 번은 스스로 다시 그린다.** 무엇이 걸렸는지(분류)를
그리는 쪽에 알려 주고 그 분류에 안 걸리게 고쳐 그리게 한다. 두 번째도
걸리면 그때 사람에게 알린다.
"""
from __future__ import annotations

import ast
import json
import re
from pathlib import Path

FILE = "failure.json"

# OpenAI 는 거절도 400 이라 잘못된 인자와 상태코드로는 못 가른다 — 본문의 말을 본다.
# (webtoon-harness/providers/openai_images.py 의 REFUSAL_MARKERS 와 같은 기준)
REFUSAL_MARKERS = (
    "moderation_blocked", "content_policy", "safety system", "safety_violation",
    "rejected as a result of our safety system", "content_policy_violation",
)


def refusal_categories(exc: BaseException) -> list[str] | None:
    """안전 검사 거절이면 걸린 분류 목록(모르면 빈 목록). 거절이 아니면 None."""
    text = str(exc)
    if not any(m in text for m in REFUSAL_MARKERS):
        return None
    found = re.search(r"safety_violations=\[([^\]]*)\]", text)
    if found:
        return [c.strip().strip("'\"") for c in found.group(1).split(",") if c.strip()]
    found = re.search(r"'categories':\s*(\[[^\]]*\])", text)
    if found:
        try:
            return [str(c) for c in ast.literal_eval(found.group(1))]
        except (ValueError, SyntaxError):
            pass
    return []


def safety_note(categories: list[str]) -> str:
    """다시 그릴 때 붙이는 말 — 무엇이 걸렸고 무엇을 지켜야 하는지만 적는다."""
    what = ", ".join(categories) if categories else "분류 미상"
    return ("## 안전 검사\n"
            f"앞선 그림이 이미지 모델의 안전 검사에 걸렸다(걸린 분류: {what}). "
            "인물의 생김새·색·인상과 장면의 뜻은 그대로 두고, 걸린 분류에 해당할 수 "
            "있는 요소만 전체 이용가 기준 안으로 바꿔서 그린다.")


def write(run_dir: Path, stage: str, code: str, message: str = "",
          categories: list[str] | None = None) -> None:
    """실패 이유를 남긴다. 못 남겨도 원래 실패를 가리지 않는다."""
    try:
        (Path(run_dir) / FILE).write_text(json.dumps({
            "stage": stage, "code": code,
            "categories": categories or [],
            "message": (message or "")[:1000],
        }, ensure_ascii=False, indent=1), encoding="utf-8")
    except OSError:
        pass


def clear(run_dir: Path) -> None:
    """새로 시작하는 걸음은 앞 걸음의 실패를 지운다 — 안 지우면 옛 이유가 다시 읽힌다."""
    try:
        (Path(run_dir) / FILE).unlink(missing_ok=True)
    except OSError:
        pass
