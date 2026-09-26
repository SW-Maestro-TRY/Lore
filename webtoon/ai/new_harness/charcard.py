"""고른 캐릭터 카드 (#458).

위자드에서 캐릭터 카드를 고르면 서버(`JobService.cardOf`)가 `character.json` 의
`card` 에 그 카드를 담아 준다 — 세계 · 종 · 이 세계에서의 자리(twist) · 대사 ·
운명(fate).

전에는 카드를 골라도 이름과 `description`(처음 만들 때 적은 원래 설명)만
왔다. 그래서 「마법대륙의 검은여우」를 고른 사람이 「노란 후드티 대학생」
이야기를 받았다(2026-09-27 로컬 확인). 여기서 그 카드를 프롬프트에 넣는다.

정한 것
  - 이 웹툰에서 이 인물은 **주인공**이다. 카드에 조연·스쳐 가는 자리로 적혀
    있어도 그렇다(카드는 한 컷짜리 자리고, 웹툰은 이 인물의 이야기다).
  - 카드와 원래 설명이 다르면(종·세계·정체) **카드가 이긴다.** 원래 설명은
    성격·분위기를 읽는 데만 쓴다.
  - 사용자가 줄거리를 적었으면 카드의 운명은 참고만 한다 — 줄거리가 먼저다.
  - 장르를 따로 안 골랐으면 카드 세계의 장르를 쓴다(`default_genre`).

카드가 없으면(사진으로 바로 만들었거나 옛 입력) 어느 함수도 아무것도 안
붙인다 — 예전 run 은 프롬프트가 한 글자도 안 바뀐다.
"""

from __future__ import annotations

import json

import llm

TEXT_KEYS = ("world", "world_label", "genre", "species", "role", "role_tier", "twist", "quote")


def normalize(raw) -> dict:
    """`character.json` 의 `card` → 빈 칸을 걷어 낸 dict. 알맹이가 없으면 {}."""
    if not isinstance(raw, dict):
        return {}
    card = {k: str(raw.get(k) or "").strip() for k in TEXT_KEYS}
    fate = raw.get("fate")
    lines = fate.splitlines() if isinstance(fate, str) else list(fate or [])
    card["fate"] = [str(x).strip() for x in lines if str(x or "").strip()]
    card = {k: v for k, v in card.items() if v}
    # 세계 키만 있고 보여 줄 것이 없으면 카드가 아니다
    if not any(card.get(k) for k in ("world_label", "species", "twist", "fate", "quote")):
        return {}
    return card


def _presets() -> dict:
    try:
        path = llm.STORY_HARNESS / "worlds.json"
        return json.loads(path.read_text(encoding="utf-8")).get("presets") or {}
    except Exception:                                         # noqa: BLE001
        return {}


def default_genre(card: dict) -> str:
    """사람이 장르를 안 골랐을 때 쓸 장르.

    카드의 `genre` 칸(판타지·액션·드라마 …)이 아니라 **세계 프리셋의 이름**
    (판타지·히어로·아이돌 …)을 먼저 쓴다. 카드의 `genre` 는 그 카드 한 컷의
    분위기라서, 히어로 세계 카드가 「액션」으로 적혀 있으면 세계관 문장이
    액션 세계(청부업자의 뒷골목)로 붙는다. 프리셋 이름은 장르 칩과 같은 말이라
    세계관·장르 자료가 그 세계 것으로 붙는다.
    """
    if not card:
        return ""
    label = str((_presets().get(card.get("world") or "") or {}).get("label") or "").strip()
    return label or card.get("genre", "")


def block(card: dict, *, has_story: bool) -> list[str]:
    """이야기·시트 단계 입력에 넣는 「고른 캐릭터 카드」. 카드가 없으면 []."""
    if not card:
        return []
    lines = ["## 고른 캐릭터 카드 — 이 인물이 누구인지는 이 카드가 정한다", "",
             "사용자는 이 카드를 보고 이 캐릭터를 골랐다. 카드와 다른 인물이 나오면 "
             "사용자가 고른 캐릭터가 아니다.", ""]
    if card.get("world_label"):
        lines.append(f"- 세계: {card['world_label']}")
    if card.get("species"):
        lines.append(f"- 종: {card['species']}")
    if card.get("twist"):
        lines.append(f"- 이 세계에서 어떤 사람인가: {card['twist']}")
    quote = [q.strip() for q in card.get("quote", "").splitlines() if q.strip()]
    if len(quote) == 1:
        lines.append(f"- 대사: \"{quote[0]}\"")
    elif quote:                                  # 카드 한 컷의 말풍선 여러 줄
        lines.append("- 카드의 대사:")
        lines += [f"  - {q}" for q in quote]
    if card.get("fate"):
        lines.append("- 카드에 적힌 전개:")
        lines += [f"  - {f}" for f in card["fate"]]
    lines += ["",
              "- 이 웹툰에서 이 인물은 주인공이다. 카드에 조연이나 스쳐 가는 자리로 "
              "적혀 있어도 이야기는 이 인물을 중심으로 돈다.",
              "- 아래 「사용자가 처음 적은 설명」과 종·세계·정체가 다르면 카드를 따른다. "
              "그 설명은 성격과 분위기를 읽는 데만 쓴다."]
    if card.get("fate"):
        lines.append("- 카드에 적힌 전개는 참고만 한다. 사용자가 적은 이야기가 먼저다."
                     if has_story else
                     "- 카드에 적힌 전개를 이야기의 출발점으로 삼는다. 거기서 가는 길은 "
                     "후보마다 달라도 된다.")
    return lines


def short(card: dict) -> str:
    """장면·그림 단계의 인물 한 줄에 붙이는 요약. 카드가 없으면 빈 문자열.

    그림을 그리는 쪽은 긴 카드를 읽지 않는다 — 종과 세계만 있으면 원래
    설명(사람 · 대학생 …)으로 잘못 그리는 것을 막는다.
    """
    if not card:
        return ""
    bits = []
    if card.get("species"):
        bits.append(f"종: {card['species']}")
    if card.get("world_label"):
        bits.append(f"세계: {card['world_label']}")
    if card.get("twist"):
        bits.append(card["twist"])
    return " · ".join(bits)
