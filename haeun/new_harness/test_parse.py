#!/usr/bin/env python3
"""new_harness 검사 — 모델 응답을 잘라 읽는 부분과 시트 사양 게이트.

호출은 하지 않는다. 돈이 안 든다.

    python3 test_parse.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import imageprompt as IP  # noqa: E402
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

BOARD_JSON = """설명을 붙여 드립니다.

```json
{
  "cast": [
    {"name": "담당 교수",
     "appearance": "40대 초반, 큰 키에 마른 체격. 짧은 은회색 머리, 짙은 청색 눈."},
    {"name": "관리인", "appearance": "60대, 굽은 등, 회색 작업복."}
  ],
  "scenes": [
    {
      "id": 1,
      "summary": "하일은 입학식에서 이름이 불리자 지팡이를 들고 주문을 외운다",
      "location": "마법학교 중앙 대강당의 입학식장",
      "time": "실내조명",
      "cuts": [
        {
          "id": 1,
          "size": "large",
          "camera": {"shot": "광각", "angle": "정면", "facing": "앞모습"},
          "background": {"type": "실제공간", "desc": "높은 천장과 늘어선 마법등"},
          "characters": [
            {"name": "하일", "style": "LD", "position": "왼쪽",
             "expression": "긴장한 표정", "action": "이름이 불려 고개를 든다",
             "moment": "직후", "framing": "무릎 위"},
            {"name": "담당 교수", "style": "LD", "position": "오른쪽",
             "expression": "무표정", "action": "명단을 본다",
             "moment": "도중", "framing": "상반신"}
          ],
          "dialogue": [
            {"order": 2, "speaker": null, "type": "나레이션", "text": "입학식 사흘째.",
             "bubble": {"shape": "네모 상자", "tail": null, "position": "왼쪽 위"}},
            {"order": 1, "speaker": "담당 교수", "type": "화면밖", "text": "하일.",
             "bubble": {"shape": "둥근 타원", "tail": "컷 바깥", "position": "오른쪽 위"}}
          ],
          "sfx": [
            {"text": "웅성…", "source": "학생들", "position": "오른쪽 아래",
             "reason": "이름이 불린 뒤 이는 술렁임"}
          ],
          "forbid": [],
          "note": "시선이 하일에게 모이는 흐름을 만든다"
        },
        {
          "id": 2,
          "size": "normal",
          "camera": {"shot": "상반신", "angle": "정면", "facing": "앞모습"},
          "background": {"type": "효과", "desc": "지팡이 끝에서 흔들리는 마력"},
          "characters": [
            {"name": "하일", "style": "LD", "position": "왼쪽",
             "expression": "초조한 표정", "action": "주문을 외우다 말이 꼬임",
             "moment": "도중", "framing": "상반신"}
          ],
          "dialogue": [
            {"order": 1, "speaker": "하일", "type": "말", "text": "……그리고, 어둠을—",
             "bubble": {"shape": "둥근 타원", "tail": "하일", "position": "왼쪽 위"}},
            {"order": 2, "speaker": "하일", "type": "생각", "text": "아니, 빛을……?",
             "bubble": {"shape": "구름", "tail": "하일", "position": "오른쪽 아래"}}
          ],
          "sfx": [],
          "forbid": ["교수의 얼굴"],
          "note": "메모는 그림에 안 나가야 한다"
        }
      ]
    },
    {
      "id": 2,
      "summary": "관리인이 하일을 부른다",
      "location": "기숙사 복도",
      "time": "밤",
      "cuts": [
        {
          "id": 1,
          "size": "tiny",
          "camera": {"shot": "부분", "angle": "정면", "facing": "앞모습"},
          "background": {"type": "없음", "desc": ""},
          "characters": [
            {"name": "하일", "style": "LD", "expression": "(얼굴 없음)",
             "action": "손이 멈춘다", "moment": "직전", "framing": "손만"}
          ],
          "dialogue": [
            {"order": 1, "speaker": null, "type": "글", "text": "파일은 삭제해 주세요.",
             "bubble": {"shape": null, "tail": null, "position": "노트북 화면 안"}}
          ],
          "sfx": [{"text": "사아…", "source": "복도", "position": "화면 전체",
                   "reason": "정적"}],
          "forbid": [],
          "note": ""
        }
      ]
    }
  ]
}
```
"""


def test_board() -> None:
    board = R.parse_board(BOARD_JSON)
    check("cast 2명", [c["name"] for c in board["cast"]], ["담당 교수", "관리인"])
    scenes = board["scenes"]
    check("장면 2개", len(scenes), 2)
    check("장소", scenes[0]["location"], "마법학교 중앙 대강당의 입학식장")
    check("시간대", scenes[1]["time"], "밤")
    check("장면 1 컷 수", len(scenes[0]["cuts"]), 2)

    cut = scenes[0]["cuts"][0]
    check("size", cut["size"], "large")
    check("카메라", cut["camera"], {"shot": "광각", "angle": "정면", "facing": "앞모습"})
    check("배경", cut["background"]["type"], "실제공간")
    check("인물 2명", len(cut["characters"]), 2)
    # order 가 뒤집혀 온 것을 바로 세운다 — 읽는 순서가 곧 배치 순서다
    check("order 대로 정렬", [d["order"] for d in cut["dialogue"]], [1, 2])
    check("정렬된 첫 대사", cut["dialogue"][0]["text"], "하일.")
    check("forbid 는 배열", scenes[0]["cuts"][1]["forbid"], ["교수의 얼굴"])

    check("JSON 만 와도 같다", len(R.parse_board(
        BOARD_JSON[BOARD_JSON.index("{"):BOARD_JSON.rindex("}") + 1])["scenes"]), 2)
    try:
        R.parse_board("JSON 이 아닙니다")
    except Exception:
        pass
    else:
        FAILED.append("JSON 이 아닌데 안 멈췄다")

    # 번호가 없으면 나온 순서로 매긴다
    guessed = R.parse_board('{"scenes":[{"cuts":[{"size":"normal"},{"size":"tiny"}]}]}')
    check("장면 번호를 매긴다", guessed["scenes"][0]["id"], 1)
    check("컷 번호를 매긴다", [c["id"] for c in guessed["scenes"][0]["cuts"]], [1, 2])


def test_gate_board() -> None:
    check("멀쩡한 콘티는 통과", R.gate_board(R.parse_board(BOARD_JSON)), [])

    def issues(obj) -> list:
        return R.gate_board(R.parse_board(json.dumps(obj, ensure_ascii=False)))

    ok("장면이 없으면 잡는다", issues({"scenes": []}))
    ok("location 이 없으면 잡는다",
       any("location" in x for x in issues(
           {"scenes": [{"id": 1, "cuts": [{"size": "normal"}]}]})))
    ok("size 가 이상하면 잡는다",
       any("size" in x for x in issues(
           {"scenes": [{"id": 1, "location": "홀", "cuts": [{"size": "거대"}]}]})))
    ok("moment 가 없으면 잡는다",
       any("moment" in x for x in issues({"scenes": [{"id": 1, "location": "홀", "cuts": [
           {"size": "normal", "characters": [{"name": "하일"}]}]}]})))
    ok("둘 이상인데 position 이 없으면 잡는다",
       any("position" in x for x in issues({"scenes": [{"id": 1, "location": "홀", "cuts": [
           {"size": "normal", "characters": [
               {"name": "하일", "moment": "도중"},
               {"name": "교수", "moment": "도중"}]}]}]})))
    ok("한 명뿐이면 position 이 없어도 된다",
       not any("position" in x for x in issues({"scenes": [{"id": 1, "location": "홀",
           "cuts": [{"size": "normal", "characters": [
               {"name": "하일", "moment": "도중"}]}]}]})))
    # 좌우가 장면 안에서 바뀌는 것 — 다 그린 뒤에 발견하면 다시 그리는 값이 비싸다
    ok("좌우가 바뀌면 잡는다",
       any("좌우" in x for x in issues({"scenes": [{"id": 1, "location": "홀", "cuts": [
           {"size": "normal", "characters": [
               {"name": "교수", "moment": "도중", "position": "오른쪽"}]},
           {"size": "normal", "characters": [
               {"name": "교수", "moment": "도중", "position": "왼쪽"}]}]}]})))
    ok("대사 text 가 비면 잡는다",
       any("text" in x for x in issues({"scenes": [{"id": 1, "location": "홀", "cuts": [
           {"size": "normal", "dialogue": [{"order": 1, "text": ""}]}]}]})))


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


def test_scene_head() -> None:
    board = R.parse_board(BOARD_JSON)
    flat = P.flatten_cuts(board["scenes"])
    check("컷 3개로 펴진다", len(flat), 3)
    check("어느 장면의 몇 컷인지 남는다",
          [(c["scene"], c["cut"]) for c in flat], [(1, 1), (1, 2), (2, 1)])
    check("장소가 컷까지 내려온다",
          [c["location"] for c in flat],
          ["마법학교 중앙 대강당의 입학식장"] * 2 + ["기숙사 복도"])
    check("시간대도", [c["time"] for c in flat], ["실내조명"] * 2 + ["밤"])
    check("크기는 콘티에서 온 그대로",
          [P.cut_size(c) for c in flat], ["large", "normal", "tiny"])
    # large 는 혼자, normal+tiny 가 한 장
    check("펴서 바로 묶인다",
          [[(c["scene"], c["cut"]) for c in page] for page in P.group_pages(flat)],
          [[(1, 1)], [(1, 2), (2, 1)]])
    check("장면이 없으면 빈 배열", P.flatten_cuts([]), [])

    # 컷이 스스로 적었으면 장면 값으로 안 덮는다
    own = P.flatten_cuts([{"id": 1, "location": "홀",
                           "cuts": [{"id": 1, "location": "복도"}]}])
    check("컷이 적은 장소가 이긴다", own[0]["location"], "복도")


# ------------------------------------------------- 이미지 생성 프롬프트

def test_image_prompt_pieces() -> None:
    check("카메라를 문장으로 편다",
          IP.camera_line({"shot": "상반신", "angle": "정면", "facing": "옆모습"}),
          "상반신, 정면 앵글, 인물은 옆모습")
    check("칸이 모자라도 있는 것까지",
          IP.camera_line({"shot": "극클로즈업"}), "극클로즈업")
    check("배경", IP.background_line({"type": "실제공간", "desc": "복도"}),
          "실제공간 — 복도")
    check("설명이 없으면 종류만", IP.background_line({"type": "없음"}), "없음")

    check("인물을 문장으로 편다",
          IP.person_line({"name": "하일", "style": "LD", "position": "왼쪽",
                          "expression": "긴장한 표정", "action": "고개를 든다",
                          "moment": "직후", "framing": "무릎 위"}),
          "하일 (LD): 화면 왼쪽, 긴장한 표정, 고개를 든다. 동작의 직후를 그린다. "
          "화면에는 무릎 위까지 나온다.")
    check("위치가 없으면 그 조각만 빠진다",
          IP.person_line({"name": "하일", "style": "LD", "expression": "웃는다",
                          "moment": "직전", "framing": "전신"}),
          "하일 (LD): 웃는다. 동작의 직전을 그린다. 화면에는 전신까지 나온다.")
    # 조사를 하나로 박으면 "직후을" 이 프롬프트로 나간다
    check("받침 없는 순간은 를", IP._eul("직후"), "를")
    check("받침 있는 순간은 을", IP._eul("직전"), "을")

    # framing 은 값 목록이 없는 자유 텍스트다 — "손만까지 나온다" 가 안 나와야 한다
    def frame(value: str) -> str:
        return IP.person_line({"name": "하일", "framing": value})

    ok("상반신까지", frame("상반신").endswith("화면에는 상반신까지 나온다."))
    ok("무릎 위까지", frame("무릎 위").endswith("화면에는 무릎 위까지 나온다."))
    ok("손만 나온다", frame("손만").endswith("화면에는 손만 나온다."))
    ok("일부 나온다", frame("손과 눈 일부").endswith("화면에는 손과 눈 일부 나온다."))

    # 대사 종류가 곧 말풍선 모양이다
    check("말",
          IP.bubble_line({"speaker": "하일", "type": "말", "text": "안녕",
                          "bubble": {"shape": "둥근 타원", "tail": "하일",
                                     "position": "왼쪽 위"}}),
          ["  - 둥근 타원 / 꼬리는 하일을 향함 / 위치 왼쪽 위", '    "안녕"'])
    ok("화면밖은 꼬리가 컷 바깥으로",
       "꼬리는 컷 바깥으로" in IP.bubble_line(
           {"speaker": "???", "type": "화면밖", "text": "들어와.",
            "bubble": {"shape": "둥근 타원", "tail": "컷 바깥"}})[0])
    ok("꼬리 없음도 그대로",
       "꼬리 없음" in IP.bubble_line(
           {"type": "화면밖", "text": "x", "bubble": {"tail": "없음"}})[0])
    ok("나레이션은 꼬리를 안 단다",
       "꼬리" not in IP.bubble_line(
           {"type": "나레이션", "text": "921년",
            "bubble": {"shape": "네모 상자"}})[0])
    ok("shape 가 비면 종류로 채운다",
       "구름" in IP.bubble_line({"speaker": "하일", "type": "생각", "text": "어?"})[0])
    check("글은 말풍선이 아니라 적힌 것을 그린다",
          IP.bubble_line({"type": "글", "text": "파일은 삭제해 주세요.",
                          "bubble": {"position": "노트북 화면 안"}})[0],
          "  - 말풍선 아님 — 노트북 화면 안에 적힌 글로 그린다")
    check("대사 글자는 한 글자도 안 바뀐다",
          IP.bubble_line({"speaker": "하일", "type": "말",
                          "text": "……그리고, 어둠을—"})[1],
          '    "……그리고, 어둠을—"')

    check("효과음은 글자와 위치만",
          IP.sfx_line({"text": "웅성…", "source": "학생들", "position": "오른쪽 아래",
                       "reason": "술렁임"}),
          '  - "웅성…" / 위치 오른쪽 아래')


def test_image_prompt_page() -> None:
    board = R.parse_board(BOARD_JSON)
    pages = P.group_pages(P.flatten_cuts(board["scenes"]))
    text = IP.build_page_prompt(pages[0], sheets=["하일 — 마른 체격의 소년."],
                                cast=board["cast"])

    ok("고정 블록이 앞에", text.startswith("세로로 읽는 웹툰 페이지를 그린다."))
    ok("주인공 시트", "하일 — 마른 체격의 소년." in text)
    ok("이 페이지에 나오는 조연만", "담당 교수 — 40대 초반" in text)
    ok("안 나오는 조연은 안 적는다", "관리인" not in text)
    ok("장소가 앞에 한 번", "## 장소\n마법학교 중앙 대강당의 입학식장" in text)
    ok("시간대도", "시간대: 실내조명" in text)
    ok("컷 1 은 높이 비율 5", "### 컷 1 (높이 비율 5)" in text)
    ok("카메라", "카메라: 광각, 정면 앵글, 인물은 앞모습" in text)
    ok("배경", "배경: 실제공간 — 높은 천장과 늘어선 마법등" in text)
    ok("인물", "하일 (LD): 화면 왼쪽, 긴장한 표정" in text)
    ok("좌우가 둘 다", "담당 교수 (LD): 화면 오른쪽" in text)
    ok("나레이션이 먼저 오지 않는다 (order 대로)",
       text.index('"하일."') < text.index('"입학식 사흘째."'))
    ok("효과음 절", "효과음 (말풍선 없이 글자만 그린다):" in text)
    ok("효과음 글자", '"웅성…" / 위치 오른쪽 아래' in text)

    # 그림에 안 그려지는 칸은 프롬프트에 없어야 한다
    ok("note 가 안 나간다", "시선이 하일에게 모이는" not in text)
    ok("sfx reason 이 안 나간다", "술렁임" not in text)
    ok("summary 가 안 나간다", "하일은 입학식에서" not in text)

    page2 = pages[1]
    text2 = IP.build_page_prompt(page2, cast=board["cast"])
    ok("장소가 갈리면 앞에 안 적는다", "## 장소" not in text2)
    ok("대신 컷마다", "장소: 마법학교 중앙 대강당의 입학식장" in text2
       and "장소: 기숙사 복도" in text2)
    ok("forbid", "그리지 않을 것: 교수의 얼굴" in text2)
    ok("글 대사", "말풍선 아님 — 노트북 화면 안에 적힌 글로 그린다" in text2)

    # 컷 번호는 페이지 안에서 1부터
    check("컷 번호가 안 겹친다", text2.count("### 컷 1 ("), 1)
    ok("1, 2 로 센다", "### 컷 1 (" in text2 and "### 컷 2 (" in text2)
    prompts = IP.page_prompts(pages, cast=board["cast"], continuous=True)
    check("페이지 수만큼", len(prompts), 2)
    ok("이어 세면 두 번째는 컷 2 부터", "### 컷 2 (" in prompts[1])

    full = IP.build_page_prompt([{"size": "full"}])
    ok("full 은 페이지 전체", "### 컷 1 (페이지 전체)" in full)
    ok("시트가 없으면 절도 없다", "## 캐릭터 시트" not in full)


def test_sheet_line() -> None:
    spec = S.parse_spec(GOOD_SPEC)
    line = IP.sheet_line(spec)
    ok("이름으로 시작", line.startswith("이하은 — "))
    ok("외형", "shoulder-length black hair" in line)
    ok("고정 요소", "왼쪽 손목에만 감은 검정 헤어끈 두 겹" in line)
    ok("소지품", "낡은 캔버스 에코백" in line)
    spec["props"] = []
    ok("소지품이 없으면 그 줄도 없다", "소지품:" not in IP.sheet_line(spec))


def test_ratio_break() -> None:
    check("기본은 비율로 안 끊는다",
          shape(P.group_pages(cuts(*["normal"] * 5))), [[1, 2, 3, 4, 5]])
    check("max_ratio=9 면 normal 3개",
          shape(P.group_pages(cuts(*["normal"] * 7), max_ratio=9)),
          [[1, 2, 3], [4, 5, 6], [7]])
    check("얹기 전에 본다 — 상한을 넘긴 페이지가 안 나간다",
          shape(P.group_pages(cuts("tiny", "small", "normal", "normal"), max_ratio=8)),
          [[1, 2, 3], [4]])
    check("개수와 비율 중 먼저 걸리는 쪽",
          shape(P.group_pages(cuts(*["tiny"] * 6), max_per_page=4, max_ratio=99)),
          [[1, 2, 3, 4], [5, 6]])
    check("large 는 비율과 무관하게 혼자",
          shape(P.group_pages(cuts("normal", "large", "normal"), max_ratio=99)),
          [[1], [2], [3]])
    try:
        P.group_pages(cuts("normal"), max_ratio=0)
    except ValueError:
        pass
    else:
        FAILED.append("max_ratio=0 인데 안 막았다")


# ------------------------------------------------------------- 페이지 그리기

def test_pageart() -> None:
    import shutil
    import tempfile

    import imagegen
    import pageart

    # 페이지는 세로로 길어야 한다. 시트 칸은 안 건드렸는지 같이 본다
    check("페이지 칸이 생겼다", imagegen.story.CHARSHEET_SIZES["page"], "1024x1536")
    check("페이지 비율", imagegen.story.CHARSHEET_RATIOS["page"], "9:16")
    check("시트 칸은 그대로", imagegen.story.CHARSHEET_SIZES["sheet"], "1536x1024")

    root = Path(tempfile.mkdtemp(prefix="nh-pageart-"))
    try:
        run_dir = root / "run"
        run_dir.mkdir()
        board = R.parse_board(BOARD_JSON)
        R.write_json(run_dir / "board.json", board)
        R.write_json(run_dir / "pages.json",
                     P.group_pages(P.flatten_cuts(board["scenes"])))
        R.write_json(run_dir / "sheet_spec.json", S.parse_spec(GOOD_SPEC))

        pgs, prompts = pageart.build_prompts(run_dir)
        check("페이지 2장", len(pgs), 2)
        check("프롬프트도 2장", len(prompts), 2)
        ok("시트 사양이 프롬프트에", "이하은 — A young Korean woman" in prompts[0])
        ok("조연도", "담당 교수 — 40대 초반" in prompts[0])

        # 시트가 없으면 참조도 없다
        check("시트가 없으면 빈 목록", pageart.sheet_refs(run_dir), [])
        (run_dir / "sheet.png").write_bytes(b"x")
        check("시트가 있으면 그것 하나",
              [p.name for p in pageart.sheet_refs(run_dir)], ["sheet.png"])

        # --dry-run 은 프롬프트만 쓰고 그림은 안 만든다
        quiet, pageart.log = pageart.log, lambda *_: None
        try:
            made = pageart.draw(run_dir, dry_run=True)
        finally:
            pageart.log = quiet
        check("dry-run 은 아무것도 안 그린다", made, [])
        ok("프롬프트는 남는다", (run_dir / "pages" / "page01.txt").exists())
        ok("그림은 없다", not (run_dir / "pages" / "page01.png").exists())

        # 참조 사슬 — 첫 장은 시트만, 그다음부터 직전 페이지가 붙는다
        pageart.page_path(run_dir, 1).parent.mkdir(exist_ok=True)
        check("1페이지 자리", pageart.page_path(run_dir, 1).name, "page01.png")
        check("2페이지 자리", pageart.page_path(run_dir, 2).name, "page02.png")

        # pages.json 이 없으면 콘티부터 하라고 멈춘다
        (run_dir / "pages.json").unlink()
        try:
            pageart.build_prompts(run_dir)
        except SystemExit:
            pass
        else:
            FAILED.append("pages.json 이 없는데 안 멈췄다")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    for fn in (test_directions, test_board, test_gate_board, test_spec,
               test_sheet_prompt, test_input, test_pages, test_scene_head,
               test_image_prompt_pieces, test_image_prompt_page, test_sheet_line,
               test_ratio_break, test_pageart):
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
