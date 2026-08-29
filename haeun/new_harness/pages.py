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

# 컷 높이 비율. 이미지 프롬프트가 쓰는 표와 같은 값이다
# (imageprompt.HEIGHT_RATIO). 여기 있는 것은 **모아 그리는 컷** 기준이라
# large/full 은 어차피 혼자 한 장이므로 합계에 안 들어간다.
HEIGHT_RATIO = {"tiny": 1, "small": 2, "normal": 3, "large": 5, "full": 99}

# 한 페이지의 높이 비율 합계 상한.
#
# 개수만으로 끊으면 normal 다섯 개(합계 15)가 한 장에 들어간다. 페이지는 이미지
# **한 장**이고 그 장의 모양은 정해져 있으므로, 컷을 많이 넣을수록 각 컷이 그
# 안에서 납작해진다. 페이지의 세로/가로를 A, 합계를 R 이라 하면 normal 한 컷의
# 가로:세로는
#
#     R / (3 x A)
#
#     A=1.78, R=15  ->  2.8 : 1   (띠. 인물 상반신도 답답하다)
#     A=1.78, R= 9  ->  1.7 : 1
#     A=1.78, R= 6  ->  1.1 : 1   (거의 정사각. 페이지가 늘어 호출이 더 나간다)
#
# 그래서 상한은 **캔버스 모양에서 나온다.** 목표 컷 모양을 1.7:1 로 두면
# R = 3 x A x 1.7 = 5.1 x A 다. 캔버스가 프로바이더마다 다르므로(imagegen 참고)
# 이 값도 따라 움직여야 한다:
#
#     Gemini  9:16      A=1.78  ->  9
#     OpenAI  1024x1536 A=1.50  ->  8
TARGET_PANEL_ASPECT = 1.7      # normal 컷의 가로:세로. 웹툰 보통 컷은 조금 가로로 길다
DEFAULT_PAGE_ASPECT = 1.78     # 안 알려주면 Gemini 9:16 으로 본다


def max_ratio_for(page_aspect: float = DEFAULT_PAGE_ASPECT) -> int:
    """캔버스 세로/가로 -> 한 페이지의 높이 비율 합계 상한."""
    return max(HEIGHT_RATIO["normal"],
               round(HEIGHT_RATIO["normal"] * page_aspect * TARGET_PANEL_ASPECT))


DEFAULT_MAX_RATIO = max_ratio_for()

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


def group_pages(cuts, max_per_page: int = DEFAULT_MAX_PER_PAGE,
                max_ratio: int | None = DEFAULT_MAX_RATIO) -> list[list]:
    """컷 배열 -> 페이지 배열. 각 페이지는 컷 배열이다.

    개수(max_per_page)와 높이 비율 합계(max_ratio) 둘 중 **먼저 걸리는 쪽**에서
    끊는다. max_ratio=None 이면 개수로만 끊는다 — 그러면 normal 다섯 개가 한
    장에 들어가고, 9:16 캔버스에서 각 컷이 2.8:1 띠가 된다(DEFAULT_MAX_RATIO
    주석의 산수 참고).
    """
    if max_per_page < 1:
        raise ValueError(f"max_per_page 는 1 이상이어야 합니다 (받은 값: {max_per_page})")
    if max_ratio is not None and max_ratio < 1:
        raise ValueError(f"max_ratio 는 1 이상이어야 합니다 (받은 값: {max_ratio})")

    pages: list[list] = []
    holding: list = []

    def flush() -> None:
        if holding:
            pages.append(holding.copy())
            holding.clear()

    for cut in cuts or []:
        size = cut_size(cut)
        if size in ALONE:
            flush()                 # 모으던 것을 여기서 끊고
            pages.append([cut])     # 이 컷은 혼자 한 장
            continue
        # 이 컷을 얹으면 비율 합계가 넘치는가. **얹기 전에** 본다 — 넘긴 뒤
        # 끊으면 상한을 이미 넘은 페이지가 나간다.
        if max_ratio is not None and holding:
            here = sum(HEIGHT_RATIO[cut_size(c)] for c in holding)
            if here + HEIGHT_RATIO[size] > max_ratio:
                flush()
        holding.append(cut)
        if len(holding) == max_per_page:
            flush()
    flush()
    return pages


# 장면에 붙는 값 중 컷까지 따라 내려가야 하는 것.
# 페이지는 장면 경계를 안 지키므로(가벼운 컷은 장면을 넘어 모인다), 여기서
# 안 내려보내면 페이지를 만든 뒤에는 그 컷이 어디서 벌어지는지 알 수 없다.
CARRY_DOWN = ("location", "time")


def flatten_cuts(scenes) -> list:
    """board.json 의 장면 배열 -> 컷 하나짜리 배열.

    어느 장면의 몇 번째 컷이었는지를 scene·cut 에 남긴다 — 편 뒤에는 그
    자리를 다시 알 길이 없고, 페이지를 만든 뒤에도 "장면 2 의 1컷" 을 짚을
    수 있어야 한다. 장소·시간대도 같이 내려보낸다(CARRY_DOWN).
    """
    out = []
    for scene in scenes or []:
        carried = {k: scene[k] for k in CARRY_DOWN if str(scene.get(k) or "").strip()}
        for cut in scene.get("cuts") or []:
            # 컷이 스스로 적은 값이 있으면 그것을 남긴다 — 장면 값으로 덮지 않는다.
            out.append(dict(carried, **cut,
                            scene=scene.get("id"), cut=cut.get("id")))
    return out
