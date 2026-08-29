#!/usr/bin/env python3
"""new_harness 검사 — 모델 응답을 잘라 읽는 부분과 시트 사양 게이트.

호출은 하지 않는다. 돈이 안 든다.

    python3 test_parse.py
"""

from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import pages as P        # noqa: E402
import run as R          # noqa: E402
import sheet as S        # noqa: E402

FAILED = []


def check(name: str, got, want) -> None:
    if got != want:
        FAILED.append(f"{name}\n    나온 것: {got!r}\n    바라던 것: {want!r}")


def ok(name: str, cond, why: str = "") -> None:
    if not cond:
        FAILED.append(f"{name}{('  — ' + why) if why else ''}")


# ------------------------------------------------------------- 이야기 후보

STORY_MD = """\
네, 네 방향을 제안합니다.

## 방향 1 — 밤에만 열리는 강의실

장르: 오컬트 미스터리

### 줄거리
하은은 폐강된 강의실에 불이 켜져 있는 것을 본다.
들어가 보니 칠판에 내일 시험 문제가 이미 적혀 있다.

### 장면 목록
1. 하은이 야간 자습을 마치고 나오다 3층 복도의 불빛을 본다.
2. 문을 두 번 지나쳤다가 결국 손잡이를 돌린다.
3. 칠판에 적힌 문제를 사진으로 찍는다.
4. 다음 날 시험지에서 같은 문제를 발견하고 손을 멈춘다.

### 밝히지 않은 것
- 누가 칠판에 적었는지
- 하은 말고 그 강의실을 본 사람이 있는지

## 방향 2 — 택배가 먼저 안다

장르: 일상 스릴러

### 줄거리
받은 적 없는 택배가 문 앞에 놓인다.

### 장면 목록
- 하은이 문 앞의 상자를 발견하고 송장을 확인한다.
- 자기 이름이 맞는데 보낸 사람 칸이 비어 있다.
- 상자를 열자 어제 잃어버린 이어폰이 들어 있다.

### 밝히지 않은 것
- 누가 주웠고 왜 돌려줬는지

## 방향 3 — 한 정거장 더

장르: 로맨스

### 줄거리
매일 같은 칸에 타는 사람이 있다.

### 장면 목록
1. 하은이 늘 타던 칸을 놓치고 다음 칸에 탄다.
2. 옆자리 사람이 하은의 이름을 부른다.

### 밝히지 않은 것
- 그 사람이 어떻게 이름을 아는지

## 방향 4 — 물이 마르지 않는 층

장르: 판타지

### 줄거리
기숙사 4층 복도만 늘 젖어 있다.

### 장면 목록
1. 하은이 젖은 복도에서 미끄러지고, 물이 위가 아니라 아래에서 올라온 것을 본다.
2. 관리인에게 말하지만 4층은 작년에 폐쇄됐다는 답을 듣는다.
3. 자기 방 열쇠에 4층 호수가 적혀 있는 것을 확인한다.

### 밝히지 않은 것
- 왜 하은의 열쇠만 4층인지
- 물이 어디서 오는지
"""


def test_directions() -> None:
    ds = R.parse_directions(STORY_MD)
    check("방향 개수", len(ds), 4)
    check("번호", [d["n"] for d in ds], [1, 2, 3, 4])
    check("제목", ds[0]["title"], "밤에만 열리는 강의실")
    check("장르", [d["genre"] for d in ds],
          ["오컬트 미스터리", "일상 스릴러", "로맨스", "판타지"])
    check("장면 개수 (번호 목록)", len(ds[0]["scenes"]), 4)
    check("장면 개수 (- 목록)", len(ds[1]["scenes"]), 3)
    check("첫 장면", ds[0]["scenes"][0],
          "하은이 야간 자습을 마치고 나오다 3층 복도의 불빛을 본다.")
    check("밝히지 않은 것", len(ds[3]["hidden"]), 2)
    ok("줄거리에 장면 목록이 안 섞였다", "장면" not in ds[0]["plot"], ds[0]["plot"])
    ok("머리말은 방향에 안 들어갔다", "네 방향을 제안합니다" not in ds[0]["raw"])

    # 고르기 — 번호로 찾는다
    check("--pick 3", R.choose(ds, 3)["title"], "한 정거장 더")
    try:
        R.choose(ds, 9)
    except SystemExit:
        pass
    else:
        FAILED.append("없는 번호를 골랐는데 안 멈췄다")


# ------------------------------------------------------------------- 콘티

BOARD_MD = """\
## 장면 1 — 하은이 야간 자습을 마치고 나오다 3층 복도의 불빛을 본다.

### 컷 1
크기: large
카메라: 전신 / 로우앵글 / 앞모습
배경: 그라데이션 — 남색에서 검정으로
인물:
  - 이하은 / LD / 무표정 / 가방을 고쳐 멘다 / 전신
대사:
  - (나레이션): "10시 반, 3층."

### 컷 2
크기: normal
카메라: 상반신 / 정면 / 옆모습
배경: 실제공간 — 형광등이 하나만 켜진 복도
인물:
  - 이하은 / LD / 눈이 커진다 / 걸음을 멈춘다 / 상반신
대사:
  - 이하은 (생각): "저기 불이 왜 켜져 있지."
  - ??? (화면밖): "들어와."
지시: 말풍선 꼬리를 컷 바깥으로

## 장면 2 — 문을 두 번 지나쳤다가 결국 손잡이를 돌린다.

### 컷 1
크기: tiny
카메라: 부분(손) / 정면 / 앞모습
배경: 없음
인물:
  - 이하은 / LD / (얼굴 없음) / 손잡이 위에서 손이 멈춘다 / 손만
"""


def test_board() -> None:
    scenes = R.parse_board(BOARD_MD)
    check("장면 개수", len(scenes), 2)
    check("장면 1 제목", scenes[0]["title"],
          "하은이 야간 자습을 마치고 나오다 3층 복도의 불빛을 본다.")
    check("장면 1 컷 수", len(scenes[0]["cuts"]), 2)
    check("장면 2 컷 수", len(scenes[1]["cuts"]), 1)

    cut = scenes[0]["cuts"][1]
    check("크기", cut["크기"], "normal")
    check("카메라", cut["카메라"], "상반신 / 정면 / 옆모습")
    check("배경", cut["배경"], "실제공간 — 형광등이 하나만 켜진 복도")
    check("인물", cut["인물"], ["이하은 / LD / 눈이 커진다 / 걸음을 멈춘다 / 상반신"])
    check("대사 2줄", cut["대사"],
          ['이하은 (생각): "저기 불이 왜 켜져 있지."', '??? (화면밖): "들어와."'])
    check("지시", cut["지시"], "말풍선 꼬리를 컷 바깥으로")

    fenced = "```\n" + BOARD_MD + "```\n"
    check("펜스에 담겨 와도 같다", len(R.parse_board(fenced)), 2)


# --------------------------------------------------------------- 시트 사양

GOOD_SPEC = """{
  "name": "이하은",
  "appearance_en": "A young Korean woman in her early twenties, shoulder-length black hair tucked behind one ear, dark brown eyes, slim build, oversized grey hoodie over a white tee, dark jeans.",
  "design_details": [
    "왼쪽 손목에만 감은 검정 헤어끈 두 겹",
    "후드 오른쪽 주머니만 실밥이 터져 벌어져 있다",
    "왼쪽 눈썹 끝에 짧은 흉터 한 줄"
  ],
  "props": [
    "A4 가 겨우 들어가는 낡은 캔버스 에코백, 회색, 바닥 모서리가 닳아 실이 보인다"
  ],
  "color_palette": {
    "hair": "ink black (#22252A)",
    "eyes": "dark brown (#4A3229)",
    "skin": "warm ivory (#F1E0CE)",
    "outfit_main": "ash grey (#8E8B85)",
    "outfit_sub": "off white (#F2F0EA)",
    "accent": "muted coral (#D9705F)"
  },
  "expression_set": [
    "평온 — 입은 다물고 눈꺼풀이 살짝 내려온, 힘이 빠진 얼굴",
    "놀람 — 눈이 크게 열리고 눈썹이 위로, 입은 작게 벌어진",
    "두려움 — 눈은 크게 뜬 채 눈썹 안쪽이 올라가고 턱에 힘이 들어간",
    "결심 — 입술을 안으로 물고 눈은 한 점을 보는",
    "지침 — 눈을 반쯤 감고 고개가 살짝 기운",
    "안도 — 눈꼬리가 내려가고 입꼬리가 아주 조금 올라간"
  ]
}"""


def test_spec() -> None:
    spec = S.parse_spec("설명을 붙여서 드립니다:\n```json\n" + GOOD_SPEC + "\n```")
    check("이름", spec["name"], "이하은")
    check("고정 요소 3개", len(spec["design_details"]), 3)
    check("소지품 1개", len(spec["props"]), 1)
    check("표정 6개", len(spec["expression_set"]), 6)
    check("게이트 통과", S.gate_spec(spec), [])

    def bad(mutate, why: str) -> None:
        import copy
        broken = copy.deepcopy(spec)
        mutate(broken)
        ok(f"게이트가 잡는다: {why}", S.gate_spec(broken), why)

    bad(lambda s: s.update(appearance_en="검은 머리의 대학생"), "appearance_en 에 한글")
    bad(lambda s: s.update(appearance_en=""), "appearance_en 이 빔")
    bad(lambda s: s.update(name=""), "이름이 빔")
    bad(lambda s: s["design_details"].pop(), "고정 요소가 2개")
    bad(lambda s: s["expression_set"].pop(), "표정이 5개")
    bad(lambda s: s["color_palette"].update(eyes=""), "팔레트 한 칸이 빔")
    bad(lambda s: s["color_palette"].update(eyes="짙은 갈색"), "팔레트에 hex 가 없음")
    bad(lambda s: s.update(props=["가", "나", "다", "라", "마"]), "소지품이 5개")

    # 소지품이 없어도 통과한다 — 없는 것을 지어내게 만들면 안 된다
    import copy
    empty = copy.deepcopy(spec)
    empty["props"] = []
    check("소지품 0개도 통과", S.gate_spec(empty), [])


def test_sheet_prompt() -> None:
    spec = S.parse_spec(GOOD_SPEC)
    text = S.build_prompt(spec, style="Korean webtoon style")

    ok("4면도 영역", "REGION 1" in text and "turnaround" in text)
    ok("표정 영역", "REGION 2" in text and "6 expressions" in text)
    ok("디테일 영역", "REGION 3" in text and "3 close-up insets" in text)
    ok("소지품 영역", "REGION 4" in text and "1 carried items" in text)
    ok("색상 칩 영역", "REGION 5" in text and "swatch chips" in text)
    ok("소지품을 지우지 않는다", "no props" not in text)
    ok("고정 요소가 한글 그대로", "왼쪽 손목에만 감은 검정 헤어끈 두 겹" in text)
    ok("소지품이 한글 그대로", "낡은 캔버스 에코백" in text)
    ok("hex 가 그대로", "#22252A" in text)
    ok("스타일이 끝에", text.strip().endswith("Korean webtoon style"))

    spec["props"] = []
    text2 = S.build_prompt(spec, style="x")
    ok("소지품이 없으면 영역도 없다", "carried items" not in text2)
    ok("소지품이 없어도 색상 칩은 있다", "swatch chips" in text2)
    ok("소지품 없을 땐 REGION 5 도 없다", "REGION 5" not in text2)


# ------------------------------------------------------------------- 입력

def test_input() -> None:
    char = R.normalize({"name": "이하은", "description": "  ",
                        "fields": {"성격": "겁이 많다", "직업": ""},
                        "genre": "", "photos": []})
    check("빈 칸은 지운다", char["fields"], {"성격": "겁이 많다"})
    check("빈 설명은 빈 문자열", char["description"], "")
    ok("이름만 있고 설명이 있으면 통과", R.gate_input(char) == [])

    check("이름이 없으면 막는다",
          len(R.gate_input(R.normalize({"name": "", "fields": {"성격": "x"}}))), 1)
    check("이름만 있고 외관이 아무것도 없으면 막는다",
          len(R.gate_input(R.normalize({"name": "이하은"}))), 1)

    block = R.input_block(char)
    ok("장르가 없으면 그렇다고 말한다", "장르: (없음 — 네가 정한다)" in block)
    ok("사진이 없으면 그렇다고 말한다", "사진 없음" in block)
    ok("필드가 줄로 들어간다", "- 성격: 겁이 많다" in block)


# --------------------------------------------------------------- 페이지 묶기

def cuts(*sizes) -> list:
    """크기 목록 -> 컷 배열. 순서를 확인할 수 있게 번호를 붙인다."""
    return [{"n": i, "size": s} for i, s in enumerate(sizes, 1)]


def shape(pages) -> list:
    """페이지마다 컷 번호. 순서가 지켜졌는지 한눈에 보려고."""
    return [[c["n"] for c in page] for page in pages]


def test_pages() -> None:
    check("빈 입력", P.group_pages([]), [])
    check("None 도 빈 페이지", P.group_pages(None), [])

    check("가벼운 컷은 모인다",
          shape(P.group_pages(cuts("normal", "small", "tiny"))), [[1, 2, 3]])
    check("large 는 혼자",
          shape(P.group_pages(cuts("large"))), [[1]])
    check("full 도 혼자",
          shape(P.group_pages(cuts("full"))), [[1]])

    # 모으는 도중 large 를 만나면 거기서 끊는다
    check("도중에 large 를 만나면 끊는다",
          shape(P.group_pages(cuts("normal", "small", "large", "tiny", "normal"))),
          [[1, 2], [3], [4, 5]])
    check("large 가 연달아 오면 각자 한 장",
          shape(P.group_pages(cuts("large", "full", "large"))), [[1], [2], [3]])
    check("large 로 시작해도 빈 페이지가 안 생긴다",
          shape(P.group_pages(cuts("large", "normal"))), [[1], [2]])
    check("large 로 끝나도 빈 페이지가 안 생긴다",
          shape(P.group_pages(cuts("normal", "large"))), [[1], [2]])

    # 최대 개수
    check("기본 5개에서 넘어간다",
          shape(P.group_pages(cuts(*["normal"] * 7))), [[1, 2, 3, 4, 5], [6, 7]])
    check("정확히 5개면 한 장",
          shape(P.group_pages(cuts(*["normal"] * 5))), [[1, 2, 3, 4, 5]])
    check("max_per_page=2",
          shape(P.group_pages(cuts(*["small"] * 5), max_per_page=2)),
          [[1, 2], [3, 4], [5]])
    check("max_per_page=1 이면 전부 한 장씩",
          shape(P.group_pages(cuts("tiny", "small", "normal"), max_per_page=1)),
          [[1], [2], [3]])
    check("max_per_page 는 large 를 안 건드린다",
          shape(P.group_pages(cuts("normal", "large", "normal"), max_per_page=1)),
          [[1], [2], [3]])
    try:
        P.group_pages(cuts("normal"), max_per_page=0)
    except ValueError:
        pass
    else:
        FAILED.append("max_per_page=0 인데 안 막았다")

    # 순서는 어떤 경우에도 안 바뀐다 — 페이지를 이어 붙이면 원래 배열이다
    mixed = cuts("normal", "large", "tiny", "small", "full", "normal", "normal",
                 "normal", "normal", "small", "large")
    for limit in (1, 2, 3, 5, 99):
        flat = [c for page in P.group_pages(mixed, max_per_page=limit) for c in page]
        check(f"순서가 그대로 (max={limit})", flat, mixed)

    # 크기 읽기
    check("한글 키도 읽는다",
          shape(P.group_pages([{"n": 1, "크기": "large"}, {"n": 2, "크기": "normal"}])),
          [[1], [2]])
    check("대문자도 읽는다", P.cut_size({"size": "FULL"}), "full")
    check("앞뒤 공백도 읽는다", P.cut_size({"size": "  large  "}), "large")
    check("모르는 값은 normal", P.cut_size({"size": "거대"}), "normal")
    check("빈 값은 normal", P.cut_size({"size": ""}), "normal")
    check("크기 칸이 없으면 normal", P.cut_size({"n": 1}), "normal")
    check("dict 가 아니어도 안 죽는다", P.cut_size("large"), "normal")
    check("모르는 크기는 모이는 쪽으로",
          shape(P.group_pages([{"n": 1, "size": "거대"}, {"n": 2, "size": "normal"}])),
          [[1, 2]])

    # 원본을 건드리지 않는다
    original = cuts("normal", "large")
    P.group_pages(original)
    check("입력 배열이 그대로", original, cuts("normal", "large"))


def test_flatten() -> None:
    scenes = R.parse_board(BOARD_MD)
    flat = P.flatten_cuts(scenes)
    check("컷 3개로 펴진다", len(flat), 3)
    check("어느 장면의 몇 컷인지 남는다",
          [(c["scene"], c["cut"]) for c in flat], [(1, 1), (1, 2), (2, 1)])
    check("크기는 콘티에서 온 그대로",
          [P.cut_size(c) for c in flat], ["large", "normal", "tiny"])
    # large 한 장 + (normal, tiny) 한 장
    check("펴서 바로 묶인다",
          [[(c["scene"], c["cut"]) for c in page] for page in P.group_pages(flat)],
          [[(1, 1)], [(1, 2), (2, 1)]])
    check("장면이 없으면 빈 배열", P.flatten_cuts([]), [])


def main() -> int:
    for fn in (test_directions, test_board, test_spec, test_sheet_prompt, test_input,
               test_pages, test_flatten):
        fn()
    if FAILED:
        print("FAILED:")
        for f in FAILED:
            print("  - " + f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
