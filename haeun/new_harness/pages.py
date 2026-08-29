#!/usr/bin/env python3
"""컷을 이미지 생성 단위(페이지)로 묶는다.

콘티는 컷 단위로 나오지만 그림은 컷 단위로 부르지 않는다. 무거운 컷은 혼자
한 장을 쓰고, 가벼운 컷들은 한 장에 모아 그린다 — 붙은 컷을 따로 그리면
이음매에서 배경이 어긋나고, 호출 수도 그만큼 늘어난다.

    large · full   -> 혼자 한 페이지
    tiny · small · normal -> 순서대로 모으다가 large/full 을 만나면 거기서 끊는다

컷 순서는 어떤 경우에도 바뀌지 않는다. 페이지를 순서대로 이어 붙이면 원래
컷 배열이 그대로 나온다.
"""

from __future__ import annotations

# storyboard_prompt 의 "크기" 값과 같다.
ALONE = ("large", "full")             # 혼자 한 페이지
GROUPED = ("tiny", "small", "normal")  # 모아서 한 페이지
SIZES = GROUPED + ALONE

DEFAULT_MAX_PER_PAGE = 5

# 콘티 파서는 한글 키로 저장하고(run.parse_board), 손으로 만든 입력은 보통
# size 로 쓴다. 둘 다 받는다 — 키 이름 때문에 묶기가 실패하면 원인을 찾기가
# 그림이 이상한 것보다 어렵다.
SIZE_KEYS = ("size", "크기")


def cut_size(cut) -> str:
    """컷의 크기. 모르는 값이면 normal 로 본다.

    모델이 낸 것을 읽는 자리라 대소문자나 오타 하나로 멈추지 않는다.
    normal 로 두면 그 컷은 "모아서 그리는 보통 컷" 이 된다 — 혼자 한 장을
    차지하는 쪽보다 되돌리기 쉬운 실수다.
    """
    if not isinstance(cut, dict):
        return "normal"
    for key in SIZE_KEYS:
        value = str(cut.get(key) or "").strip().lower()
        if value:
            return value if value in SIZES else "normal"
    return "normal"


def group_pages(cuts, max_per_page: int = DEFAULT_MAX_PER_PAGE) -> list[list]:
    """컷 배열 -> 페이지 배열. 각 페이지는 컷 배열이다."""
    if max_per_page < 1:
        raise ValueError(f"max_per_page 는 1 이상이어야 합니다 (받은 값: {max_per_page})")

    pages: list[list] = []
    holding: list = []

    def flush() -> None:
        if holding:
            pages.append(holding.copy())
            holding.clear()

    for cut in cuts or []:
        if cut_size(cut) in ALONE:
            flush()                 # 모으던 것을 여기서 끊고
            pages.append([cut])     # 이 컷은 혼자 한 장
            continue
        holding.append(cut)
        if len(holding) == max_per_page:
            flush()
    flush()
    return pages


def flatten_cuts(scenes) -> list:
    """cuts.json (장면 -> 컷) 을 컷 하나짜리 배열로 편다.

    어느 장면의 몇 번째 컷이었는지를 scene·cut 에 남긴다 — 편 뒤에는 그
    자리를 다시 알 길이 없고, 페이지를 만든 뒤에도 "장면 2 의 1컷" 을 짚을
    수 있어야 한다.
    """
    out = []
    for scene in scenes or []:
        for cut in scene.get("cuts") or []:
            out.append(dict(cut, scene=scene.get("n"), cut=cut.get("n")))
    return out
