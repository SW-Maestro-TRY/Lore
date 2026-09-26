#!/usr/bin/env python3
"""new_harness — 사진·설명·장르로 1화 이야기와 콘티, 그리고 캐릭터 시트를 만든다.

    입력 -> 이야기 후보 4개 -> (사람이 고름) -> 콘티 -> 캐릭터 시트 -> 페이지 그림
          story_prompt                storyboard_prompt   sheet_prompt   image_prompt

컷은 **한 장씩 그리지 않는다.** pages.py 가 붙일 수 있는 컷을 한 페이지로 묶고,
페이지 하나당 이미지 호출을 한 번 한다 (pageart.py).

이야기는 story-harness 를 거치지 않는다. prompt/ 안의 프롬프트가 전부다.
이미지 호출만 story-harness 것을 빌려 쓴다 (imagegen.py 참고).

사용법
  python run.py --plan                                 # 어느 단계가 어느 모델인지
  python run.py --character ../landing/jobs/<id>/character.json
  python run.py --name 이하은 --photo a.png --desc "..." --genre 판타지
  python run.py --run-id <id> --pick 2                 # 후보 고르고 콘티까지
  python run.py --run-id <id> --pick 2 --scenes        # 줄거리 없이 곧장 장면 분리 (글 1회)
  python run.py --run-id <id> --sheet                  # 캐릭터 시트
  python run.py --run-id <id> --sheet-from ../story-harness/runs/<run>  # 시트 재사용
  python run.py --run-id <id> --pages                  # 페이지 그림 (페이지당 1회 호출)
  python run.py --run-id <id> --page 3                 # 3페이지만 다시
  python run.py --name ... --photo a.png --all --pick 2   # 한 번에
  아무 명령에나 --dry-run 을 붙이면 프롬프트만 쓰고 호출은 안 한다 (0원).
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import unicodedata
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
WEBTOON_HARNESS = HERE.parent / "webtoon-harness"
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
if str(WEBTOON_HARNESS) not in sys.path:
    sys.path.append(str(WEBTOON_HARNESS))     # append, 안 insert(0,...) — new_harness
                                               # 자신의 모듈(run.py 등 이름이 겹치는
                                               # 것)을 가리면 안 된다

import directing                              # noqa: E402  (webtoon-harness 것을 그대로 빌린다)
import imagegen                              # noqa: E402
import llm                                    # noqa: E402
import detailart                              # noqa: E402
import storycheck                             # noqa: E402
import charcard                               # noqa: E402
import storydiff                              # noqa: E402
import fullreview                             # noqa: E402
import pages as pagemod                       # noqa: E402
import runmeta                                # noqa: E402
import sheet as sheetmod                      # noqa: E402
from llm import story                         # noqa: E402
import samples                                # noqa: E402  (story-harness 것을 그대로 빌린다)
from pages import SIZES                       # noqa: E402

PROMPT_DIR = HERE / "prompt"
# 작품이 쌓이는 자리.
#
# **환경변수로 덮을 수 있어야 한다.** 서버(webtoon/be)는 이 하네스를 jar 에서
# 임시 폴더로 풀어서 돌리는데, 그 폴더는 서버가 뜰 때마다 새로 생긴다. 그래서
# 여기가 `HERE/"runs"` 로 고정되면 **재시작하는 순간 만든 작품이 통째로
# 사라지고, 진행 중이던 작업도 이어받을 수 없다**(2026-09-12에 실제로 겪었다 —
# 그리는 도중 서버를 다시 띄우자 그 작업이 영원히 "running" 으로 남았다).
#
# 서버는 NH_RUNS_DIR 로 고정 경로를 넘긴다. 사람이 직접 돌릴 때는 안 주므로
# 예전처럼 하네스 옆 runs/ 를 쓴다.
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))

# landing 의 폼과 같은 칸. 여기 없는 칸은 설명 본문에 섞여 들어온다.
FIELD_KEYS = ("나이", "성별", "직업", "성격", "말투", "과거", "관계", "약점")

log, warn = story.log, story.warn


# --------------------------------------------------------------------- 입력

def read_character(path: Path) -> dict:
    """landing 이 쓰는 character.json 을 그대로 읽는다.

    폴더를 주면 그 안의 character.json 을 찾는다 — jobs/<id> 를 그대로
    넘길 수 있게 하기 위해서다.

    story(줄거리)는 **사람이 직접 적었을 때만** 들고 온다. story_prompt 는
    "줄거리는 받지 않는다, 네가 새로운 이야기를 만들어야 한다" 고 못 박고
    있는데, 그것은 **아무것도 안 주어졌을 때의 규칙**이다 — 랜딩 화면은
    「어떤 이야기를 만들까요?」를 한 단계로 물어보고 확인 화면에서 다시
    보여준다. 물어보고 버리면 사람은 자기가 적은 것이 반영된 줄 안다.

    적었으면 이야기 단계가 프롬프트부터 바꾼다 — `story_prompt` 대신
    적힌 이야기를 중심에 두는 `story_prompt_seeded` 를 쓴다
    (`seeded_input_block` 참고). 안 적었으면 예전과 한 글자도 안 바뀐다.
    """
    if path.is_dir():
        path = path / "character.json"
    if not path.exists():
        raise SystemExit(f"캐릭터 파일이 없습니다: {path}")
    doc = json.loads(path.read_text(encoding="utf-8"))

    photo = doc.get("photo")
    photos = [photo] if isinstance(photo, str) else list(photo or [])
    fields = doc.get("fields") if isinstance(doc.get("fields"), dict) else {}
    return normalize({
        "name": doc.get("name"),
        "description": doc.get("character"),
        "fields": {k: str(fields.get(k) or "").strip() for k in FIELD_KEYS},
        "genre": doc.get("genre"),
        "photos": photos,
        "photo_note": doc.get("photo_note"),
        "story": doc.get("story"),
        "card": doc.get("card"),
    })


def normalize(raw: dict) -> dict:
    """빈 칸은 빈 칸으로 둔다. 코드가 기본값을 채우면 작가가 준 것과 섞인다."""
    fields = {k: str(v).strip() for k, v in (raw.get("fields") or {}).items()
              if str(v or "").strip()}
    # 고른 캐릭터 카드(#458). 장르를 따로 안 골랐으면 카드 세계의 장르를 쓴다 —
    # 안 그러면 마법대륙 카드를 골라도 모델이 장르를 새로 정해 현대물이 나온다.
    card = charcard.normalize(raw.get("card"))
    genre = str(raw.get("genre") or "").strip() or charcard.default_genre(card)
    photos = []
    for p in raw.get("photos") or []:
        path = Path(p)
        if not path.exists():
            warn(f"사진을 찾지 못했습니다: {path}")
            continue
        photos.append(str(path.resolve()))
    return {
        "name": str(raw.get("name") or "").strip(),
        "description": str(raw.get("description") or "").strip(),
        "fields": fields,
        "genre": genre,
        "photos": photos,
        "photo_note": str(raw.get("photo_note") or "").strip(),
        "story": str(raw.get("story") or "").strip(),
        "card": card,
    }


def gate_input(char: dict) -> list[str]:
    """story_prompt 가 필수라고 적은 것만 본다 — 이름과 외관."""
    bad = []
    if not char["name"]:
        bad.append("캐릭터 이름이 없습니다 (필수).")
    if (not char["photos"] and not char["description"] and not char["fields"]
            and not char.get("card")):
        bad.append("외관이 없습니다 — 사진이나 설명 중 하나는 있어야 합니다.")
    return bad


# 사용자가 적은 설명을 어떻게 쓰는가 — 설명이 있을 때만 그 바로 아래에 붙는다(#458).
#
# 모모를 「장난치는 걸 좋아한다」 한 줄로 돌렸더니, 후보 넷 중 둘이 장난 때문에
# 사건이 터지는 이야기(장난으로 쓴 동의서가 왕실 계약서가 된다)였고, 하나는
# 방향별 축(전문가 · 냉소)을 따라 「냉소적인 전문가」가 됐다(2026-09-27).
# 사용자 지시: 설명에 없는 성격은 절대 붙이지 않는다. 그리고 「이름만 바꿔
# 다른 캐릭터를 넣어도 성립하면」 그 인물이 살아 움직이는 게 아니다 — 장난치다
# 금지 소환진을 터뜨린 후보처럼 성격이 일을 바꿔야 그 인물로 읽힌다. 다만
# 「성격이 이야기를 만들어야 한다」를 세게 걸었더니 모델이 판 자체를 성격으로
# 지었다(장난감 전쟁 기념관, 장난 배틀 앱). 판이 먼저 서고 성격은 그 판에서 일을
# 바꾸는 쪽이다(사용자 판정, 2026-09-27). 0921 멘토링대로 판정 기준을 적는다.
TRAIT_RULES = [
    "## 이 인물의 성격은 위 설명이 전부다",
    "",
    "- 위 설명(과 고른 캐릭터 카드)에 없는 성격·말투·버릇·과거를 붙이지 않는다. "
    "설명이 짧으면 짧은 대로 둔다. 빈 곳을 성격 형용사로 채우지 마라.",
    "- 방향별 값(톤·주인공 위치·모순 등)은 이야기의 판과 처지에 건다. 그 값을 "
    "주인공의 성격으로 옮기지 않는다.",
    "- 판(세계·소재·중심 사건)은 성격 없이도 그 장르에서 재미있게 서 있어야 한다. "
    "성격 낱말로 세계·소재·제목을 짓지 마라 — 판을 성격에 맞추면 재미도 사건도 사라진다.",
    "- 그 판에서 적힌 성격 때문에 일이 다르게 터지거나 꼬이거나 풀린다. 판정: 주인공을 "
    "다른 인물로 바꾸면 이 판에서 벌어지는 일이 달라지는가?",
]


def input_block(char: dict, *, with_genre: bool = True) -> str:
    """프롬프트 뒤에 붙는 이번 입력."""
    lines = ["# 이번 입력", "", f"캐릭터 이름: {char['name']}"]

    if char["photos"]:
        n = len(char["photos"])
        note = f" ({char['photo_note']})" if char["photo_note"] else ""
        lines.append(f"외관: 첨부한 사진 {n}장을 보라{note}.")
    else:
        lines.append("외관: (사진 없음 — 아래 설명에서 읽는다)")

    card = char.get("card") or {}
    if card:
        lines += ["", *charcard.block(card, has_story=bool(user_story(char)))]

    if char["description"] or char["fields"]:
        # 카드를 골랐으면 이 설명은 카드가 되기 전에 사람이 처음 적은 것이다 —
        # 종·세계가 카드와 다를 수 있어서(사람 → 검은여우) 이름표를 단다.
        lines += ["", "사용자가 처음 적은 설명 (성격·분위기 참고):" if card else "설명:"]
        if char["description"]:
            lines.append(char["description"])
        for k, v in char["fields"].items():
            lines.append(f"- {k}: {v}")
        lines += ["", *TRAIT_RULES]
    else:
        lines += ["", "설명: (없음 — 네가 정한다)"]

    if with_genre:
        lines += ["", f"장르: {char['genre']}" if char["genre"]
                  else "장르: (없음 — 네가 정한다)"]
    return "\n".join(lines) + "\n"


def user_story(char: dict) -> str:
    """사람이 「어떤 이야기를 볼까요?」에 적은 것. 안 적었으면 빈 문자열."""
    return (char.get("story") or "").strip()


def seeded_input_block(char: dict) -> str:
    """사람이 줄거리를 적었을 때의 이야기 단계 입력 (#457).

    예전에는 `story_prompt`(입력이 없을 때를 기준으로 쓴 프롬프트) 뒤에
    「사람이 적은 이야기」 한 문단을 덧붙여 "줄거리는 받지 않는다" 만
    덮었다. 그런데 그 프롬프트의 나머지 — 캐릭터를 잊고 장르 소재부터
    떠올려 "완전히 다른 판 4개"를 고르라는 단계 — 와, 그 뒤에 "참고가
    아니라 지시다" 로 붙는 방향별 축·구조가 그대로 살아서 적힌 이야기를
    밀어냈다. "역대급 꼴찌가 입학했다" 에 주인공 위치 「최상위」가
    배정되는 식이다(2026-09-26, 사용자 지적).

    그래서 적은 것이 있으면 프롬프트부터 `story_prompt_seeded` 로 바꾸고,
    여기서는 다음을 뺀다.
      - 방향별 축·구조·엔진 — 무작위라 적힌 사실과 부딪힌다. 후보 넷의
        차이는 적힌 것에 무엇을 더하느냐에서 나오게 한다.
      - 장르 기준 샘플 카드 — 다른 주인공의 사건이라, 적힌 이야기 옆에
        두면 그 소재가 섞여 든다.
    세계관·전개 문법은 남기되 적힌 이야기 아래에 둔다. 적힌 이야기는
    **맨 뒤**에 놓는다 — 모델은 뒤에 온 것을 더 세게 듣는다(`compose`).
    """
    lines = [input_block(char).rstrip("\n")]
    genre = char["genre"]
    if genre:
        world = world_text_for(genre)
        if world:
            lines += ["", "## 이 세계의 배경 — 적힌 이야기와 부딪히지 않는 곳에서만 쓴다",
                      "", world]
        lines += genre_lore_section(genre)
    lines += ["", "## 사용자가 적은 이야기 — 이 웹툰의 중심", "",
              f"> {user_story(char)}"]
    if genre:
        lines += ["", "위의 세계관·전개 문법이 이 이야기와 부딪히면 이 이야기가 이긴다."]
    return "\n".join(lines) + "\n"


def story_input_block(char: dict, run_dir: Path | None = None) -> str:
    """이야기 단계의 입력 — 장르가 주어졌을 때만 장르 참고 자료를 더한다.

    장르가 없으면 story_prompt 가 4개 방향마다 서로 다른 장르를 스스로
    고르므로, 어느 장르의 세계관을 붙일지 미리 알 수 없다 — 그때는 지금까지
    처럼 붙이지 않는다. detail_block 과 같은 자료를 쓴다(genre_lore_for·
    world_text_for) — 구체화 단계에서만 장르 세계관을 주면, 장면 목록 자체가
    이미 장르 색이 없는 소재(출입증·CCTV 등)로 굳어 있어서 구체화가 소재를
    바꿔치기하는 식으로만 손볼 수 있었다(2026-08-31, 사용자 지적).

    `run_dir`(2026-09-19 추가) — 있으면 장르 샘플 카드가 **최근에 안 보여준
    카드를 우선** 고르고, 이번에 고른 카드를 그 run 에 남겨 다음 run 이 이어
    피하게 한다(`genre_samples_for` 참고). 없으면(기본) 예전처럼 그냥
    무작위로 고른다 — 호출부를 다 못 고친 자리가 있어도 안 깨진다.
    """
    block = input_block(char).rstrip("\n")
    genre = char["genre"]
    if not genre:
        return block + "\n"
    lines = [block]
    world = world_text_for(genre)
    if world:
        lines += ["", "## 이 세계의 배경 — 이 안에서 무엇이 벌어질지는 정해져 있지 않다", "", world]
    cards = genre_samples_for(genre, run_dir=run_dir)
    if cards:
        lines += ["", "## 이 장르의 기준 샘플 (사람이 검수해 서비스에 나간 카드)",
                  "", GENRE_SAMPLE_NOTE, "", cards]
    lines += genre_lore_section(genre)
    return "\n".join(lines) + "\n"


def load_prompt(name: str) -> str:
    path = PROMPT_DIR / name
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit(f"프롬프트가 비어 있습니다: {path}")
    return text


def compose(prompt_name: str, block: str) -> str:
    """프롬프트 + 이번 입력. 입력은 **뒤**에 붙인다 — 모델은 뒤에 온 것을 더 세게 듣는다."""
    return f"{load_prompt(prompt_name)}\n\n---\n\n{block}"


# --------------------------------------------------------------------- 파싱
#
# 이야기 후보는 마크다운, 콘티는 JSON 이다. 형식이 다른 것은 읽는 사람이
# 다르기 때문이다 — 후보는 사람이 읽고 하나를 고르는 것이라 형식이 느슨해도
# 되고, 콘티는 컷마다 칸이 정해져 있어서 JSON 이 맞다.
#
# 후보 쪽은 원문(story.md)을 그대로 남기고 골라야 하는 만큼만 잘라 읽는다.
# 잘라 읽기가 실패해도 원문은 남는다.

# 줄 안의 공백만 허용한다 — \s 를 쓰면 줄바꿈까지 먹어서, 값이 빈 줄
# ("인물:" 처럼 뒤가 비는 줄)에서 **다음 줄을 값으로 집어간다.**
S = r"[ \t]*"

DIRECTION_RE = re.compile(rf"^##{S}방향{S}(\d+){S}[—–\-:]?{S}(.*)$", re.M)
SECTION_RE = re.compile(rf"^###{S}(.+?){S}$", re.M)
GENRE_RE = re.compile(rf"^{S}장르{S}[:：]{S}(.+?){S}$", re.M)
INTRO_LABEL_RE = re.compile(rf"^{S}소개{S}[:：]{S}$", re.M)
BODY_LABEL_RE = re.compile(rf"^{S}본문{S}[:：]{S}$", re.M)
BULLET_RE = re.compile(rf"^{S}(?:[-*·]|\d+[.)]){S}(.+?){S}$", re.M)
# 가로줄(`---` `***` `___`)과 코드펜스(```). 불릿으로 읽히면 안 되는 줄들.
_RULE_RE = re.compile(r"^\s*(?:([-*_])\1{2,}|`{3,}\w*)\s*$")


def _sections(body: str) -> dict:
    """### 로 나뉜 토막들."""
    out, marks = {}, list(SECTION_RE.finditer(body))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(body)
        out[m.group(1).strip()] = body[m.end():end].strip()
    return out


def _bullets(text: str) -> list[str]:
    """번호나 - 로 시작하는 줄. 없으면 빈 줄로 나눈 문단.

    가로줄(`---`)과 코드펜스(```)는 먼저 걷어낸다. 방향과 방향 사이에 가로줄이
    들어가는데, 그것이 마지막 절(「밝히지 않은 것」)의 본문에 딸려 들어온다.
    `BULLET_RE` 는 `---` 을 "`-` 불릿 + 내용 `--`" 로 읽고, 불릿이 하나라도
    잡히면 문단 fallback 을 안 쓴다 — 그래서 모델이 그 칸을 불릿 없이 문장으로
    적으면 **내용이 통째로 버려지고 `["--"]` 만 남았다**(2026-09-12 실측:
    run 20260912T091730-87fd7b 의 네 후보 중 셋). 그러면 storycheck 의
    "「밝히지 않은 것」에 적힌 것은 빼라" 예외가 안 걸려서, 일부러 남긴
    미스터리가 전부 「처음 등장한다」 지적으로 돌아온다.
    """
    body = "\n".join(ln for ln in text.splitlines() if not _RULE_RE.match(ln))
    hits = [m.group(1).strip() for m in BULLET_RE.finditer(body)]
    if hits:
        return hits
    return [ln.strip() for ln in body.splitlines() if ln.strip()]


def _cast_bullets(text: str) -> list[dict]:
    """"이름 — 외모" 한 줄씩 -> [{"name", "appearance"}]. 구분자가 없는 줄은 버린다.

    board.json·detail.json 이 만들던 cast(조연 외모 고정)를 story 단계에서
    바로 만든다 — 콘티·구체화를 건너뛰는 이어그리기 흐름은 그 둘이 없어서,
    여기서 안 만들면 조연 외모를 고정할 데가 없다.
    """
    out = []
    for line in _bullets(text):
        for sep in ("—", "–", "-"):
            if sep in line:
                name, _, appearance = line.partition(sep)
                name, appearance = name.strip(), appearance.strip()
                if name and appearance:
                    out.append({"name": name, "appearance": appearance})
                break
    return out


def _drop_trailing_rules(text: str) -> str:
    """끝에 붙은 가로줄(`---` 등)을 걷어낸다.

    방향은 다음 「## 방향」 머리까지 잘라 읽는데, 모델이 방향 사이에 가로줄을
    넣어서 **마지막 칸(본문)의 끝에 `---` 가 딸려 들어온다.** 그 본문이 그대로
    이야기 고르기 화면의 「직접 고쳐도 돼요」 칸과 DB(webtoon_story)로 가서,
    사람에게 `---` 가 보였다(2026-09-26 run 20260926T132032-278bf7, 넷 중 셋).
    """
    lines = text.rstrip().splitlines()
    while lines and (not lines[-1].strip() or _RULE_RE.match(lines[-1])):
        lines.pop()
    return "\n".join(lines).rstrip()


def _labeled(body: str, label_re: re.Pattern, stop_res: list[re.Pattern]) -> str:
    """`label_re` 줄 다음부터, `stop_res` 중 가장 먼저 나오는 줄 전까지."""
    m = label_re.search(body)
    if not m:
        return ""
    end = len(body)
    for stop_re in stop_res:
        sm = stop_re.search(body, m.end())
        if sm and sm.start() < end:
            end = sm.start()
    return _drop_trailing_rules(body[m.end():end].strip())


def parse_directions(md: str) -> list[dict]:
    """story_prompt 의 응답에서 방향 4개를 잘라 읽는다.

    지금 story_prompt 는 제목 + 장르 한 줄 + 소개(2~3문장) + 본문(5~8문장)만
    낸다(장면 목록·등장인물은 없다 — 그건 방향을 고른 **뒤에** scene_prompt 가
    만든다). `intro` 는 사람이 고를 때 보는 짧은 요약, `body` 는 그 뒤
    scene_prompt 에 「선택된 스토리」로 그대로 넘기는 본문이다. `plot`·
    `scenes`·`cast` 는 옛 형식(### 하위 절)을 쓰던 run 과의 호환을 위해
    남겨 둔다 — 지금 형식에는 없으니 비어 있는 게 정상이다.
    """
    marks = list(DIRECTION_RE.finditer(md))
    out = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(md)
        body = md[m.end():end]
        sec = _sections(body)
        genre_m = GENRE_RE.search(body.split("###")[0])
        genre = genre_m.group(1).strip() if genre_m else ""
        intro = _labeled(body, INTRO_LABEL_RE, [BODY_LABEL_RE])
        body_text = _labeled(body, BODY_LABEL_RE, [])
        out.append({
            "n": int(m.group(1)),
            "title": m.group(2).strip(),
            "genre": genre,
            "intro": intro,
            "body": body_text,
            "plot": sec.get("줄거리", "").strip(),
            "scenes": _bullets(sec.get("장면 목록", "")),
            "cast": _cast_bullets(sec.get("등장인물", "")),
            "hidden": _bullets(sec.get("밝히지 않은 것", "")),
            "raw": (m.group(0) + body).strip(),
        })
    return out


def _num(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _text(value) -> str:
    return str(value).strip() if value not in (None, "") else ""


def _dicts(value) -> list[dict]:
    return [v for v in (value or []) if isinstance(v, dict)]


def _wide(text: str) -> int:
    """터미널에서 차지하는 칸 수. 한글은 두 칸인데 len() 은 하나로 센다."""
    return sum(2 if unicodedata.east_asian_width(c) in ("W", "F") else 1
               for c in str(text))


def _pad(text: str, width: int) -> str:
    return str(text) + " " * max(0, width - _wide(text))


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def load_meta(run_dir: Path) -> dict:
    return runmeta.load(run_dir)


def record(run_dir: Path, call_meta: dict) -> None:
    # 쓰는 것은 runmeta 가 한다(파일 잠금) — 장면을 동시에 그리면 프로세스
    # 여럿이 같은 meta.json 에 쓴다. 그냥 쓰면 나중에 쓴 쪽이 앞의 기록을
    # 덮어써서, 돈은 나갔는데 정산에서만 사라진다.
    runmeta.append_call(run_dir, call_meta)
    cost = call_meta.get("cost") or {}
    tag = "실패" if call_meta.get("error") else ""
    log(f"  {tag}{call_meta['stage']}  {call_meta['provider']}:{call_meta['model']}  "
        f"{story.cost_text(cost.get('total'))}"
        + (f"  — {call_meta['error']}" if call_meta.get("error") else ""))


def record_error(run_dir: Path, stage: str, provider: str, model: str, exc: Exception) -> None:
    """호출이 실패했을 때도 meta.json 에 흔적을 남긴다 — 성공 때(record)와 같은
    자리, 비용은 0으로. 실패 사유가 로그에서만 스쳐 지나가면 나중에 이 run이
    왜 멈췄는지, 어디까지 돈이 나갔는지 다시 알아낼 수 없다."""
    record(run_dir, {
        "stage": stage, "provider": provider, "model": model,
        "usage": None, "stop": None,
        "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                 "cache_write": 0.0, "total": 0.0},
        "error": f"{type(exc).__name__}: {exc}",
    })


def read_input(run_dir: Path) -> dict:
    path = run_dir / "input.json"
    if not path.exists():
        raise SystemExit(f"{path} 가 없습니다. 먼저 이야기 단계를 돌리세요.")
    return json.loads(path.read_text(encoding="utf-8"))


DIRECTIONS_PER_RUN = 4


def _distinct_structures(genre: str, first: dict, n: int = DIRECTIONS_PER_RUN) -> list[dict]:
    """회차 구조를 서로 다른 것으로 n개. 첫 번째는 pick_fresh 가 뽑아 준 것을 쓴다.

    장르 제약이 세서 n개를 못 채우면 있는 만큼만 준다 — 겹치는 구조를
    억지로 채워 넣느니 그 방향은 구조 없이 가는 편이 낫다.
    """
    out, seen = [], set()
    for cand in [first] + [samples.pick_structure(genre) for _ in range(n * 6)]:
        if len(out) >= n:
            break
        key = str((cand or {}).get("구조", {}).get("이름") or "")
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(cand)
    return out


def _pick_engines(n: int = DIRECTIONS_PER_RUN) -> list[dict]:
    """문제가 옮겨 가는 길 — "처음 문제가 마지막에 무엇이 되어 있는가".

    축(어디에 서 있는가)·구조(어떤 순서로 보여주는가)와 다른 층이다. 축과
    구조를 방향별로 갈라도 4개가 전부 "상대에게 숨겨진 감정이 있고
    주인공은 모른다"로 수렴하는 것을 실측하고 넣었다(2026-08-31) — 소재는
    달라도 그 위에 얹히는 갈등 공식이 같으면 읽는 경험이 같다.

    story_prompt 에 목록만 주고 "서로 다르게 하라"고 했을 때는 장르가
    끌어당기는 쪽으로 다시 수렴했다. 축·구조처럼 값을 박아야 갈린다.
    "비밀과 추적"이 하나를 넘지 않는 것은 서로 다른 것을 뽑는 것만으로
    저절로 지켜진다.
    """
    if not engines_enabled():
        return []
    try:
        doc = json.loads((PROMPT_DIR / "story_engines.json").read_text(encoding="utf-8"))
        values = [v for v in (doc.get("엔진") or {}).get("값") or []
                  if isinstance(v, dict) and v.get("이름")]
    except (OSError, json.JSONDecodeError):
        return []                       # 없으면 엔진 없이 간다 — 예전과 같다
    if not values:
        return []
    pool = list(values)
    random.shuffle(pool)
    return [pool[i % len(pool)] for i in range(n)]


def _engine_block(engine: dict) -> str:
    """배정된 값 한 덩어리. `시작`·`도착`이 있으면 경로로, 없으면 예전 형식으로.

    옛 `story_engines.json`(핵심동사만 있는 것)을 그대로 두고 돌려도 읽힌다 —
    이 파일은 손으로 갈아 끼우는 자리라, 한쪽 형식만 읽으면 갈아 끼운 순간
    방향별 값이 통째로 사라진다.
    """
    if not engine:
        return ""
    name = engine.get("이름", "")
    if str(engine.get("시작") or "").strip():
        lines = [f"[문제가 옮겨 가는 길] {name}",
                 f"  시작 — {engine['시작']}",
                 f"  도착 — {engine.get('도착', '')}"]
    else:
        lines = [f"[압력] {name} — 핵심 동사: {engine.get('핵심동사', '')}"]
    for key in ("설명", "전개"):
        if str(engine.get(key) or "").strip():
            lines.append(f"  {engine[key]}")
    return "\n".join(lines)


def _distinct_axes(genre: str, first: dict, n: int = DIRECTIONS_PER_RUN) -> list[dict]:
    """이야기 변수를 방향마다 하나씩. **축 하나하나가 방향끼리 안 겹치게** 뽑는다.

    조합 단위로만 다르게 뽑으면 축 하나가 4개 방향에 똑같이 걸릴 수 있고,
    그러면 소재가 달라도 넷이 같은 이야기로 읽힌다 — 2026-08-31 실측에서
    관계_구도(삼각관계)가 4개 전부에 나왔다. 그래서 축별로 값을 갈라 준다.

    1번 방향은 pick_fresh 가 뽑아 준 것을 그대로 쓴다. 최근 run 들과 겹치지
    않게 고른 값이라 이것을 버리면 run 사이 반복 회피가 사라진다.
    값이 n개보다 적은 축(장르 제약)은 있는 만큼만 돌려 쓴다.
    """
    table = samples.load_axes()
    if not table:
        return []
    gkey = samples._override_key(table, genre)
    picked: list[dict] = [{} for _ in range(n)]
    for axis in samples.axis_names():
        values = samples._axis_values(table, axis, gkey)
        if not values:
            continue
        head = (first or {}).get(axis)
        rest = [v for v in values
                if not head or v.get("이름") != head.get("이름")]
        random.shuffle(rest)
        chosen = ([head] if head else []) + rest
        for i in range(n):
            picked[i][axis] = chosen[i % len(chosen)]
    return picked


def engines_enabled() -> bool:
    """방향별 엔진(「문제가 옮겨 가는 길」)을 프롬프트에 박을지. **기본은 꺼짐**
    (2026-09-16부터 — axes_enabled 와 기본값을 맞바꿨다. 아래 참고).

    끄면 `story_engines.json` 을 아예 안 읽는다 — `_pick_engines` 가 빈 리스트를
    돌려주고, `story_variety_block` 은 이야기 변수(축)·회차 구조만으로 방향을
    가른다(둘 다 꺼져 있으면 방향별 차이가 하나도 안 박힌다).

    `.env` 에 `NH_STORY_ENGINES=1` 로 켠다. axes_enabled 와 짝인 스위치라,
    둘을 따로 켜고 꺼서 어느 쪽이 방향을 갈라놓는지 비교할 수 있다
    (2026-09-15 축·엔진 On/Off 비교에서 이 스위치가 필요해 만들었다).
    """
    return str(llm.env("NH_STORY_ENGINES") or "").strip().lower() in ("1", "on", "true", "yes")


def axes_enabled() -> bool:
    """이야기 변수(축)·회차 구조를 프롬프트에 박을지. **기본은 켜짐**
    (2026-09-16부터 — engines_enabled 와 기본값을 맞바꿨다).

    2026-09-12에 한 번 기본을 껐다 — 켜 둔 채로도 후보 넷이 "이상한 것을
    발견하고 확인해 나간다"로 수렴하는 것이 실측으로 나와서(run
    20260907T214656-c31cff), 효과가 확인되지 않은 채 프롬프트 무게의
    절반을 쓰고 있었기 때문이다. 그런데 이후 축·엔진을 실제로 켜고 꺼
    가며 비교해 보니 축 쪽이 더 나은 결과를 줘서, 2026-09-16에 기본을
    다시 켜짐으로 되돌렸다(엔진은 반대로 기본 꺼짐이 됐다).

    `.env` 에 `NH_STORY_AXES=0` 으로 끈다. 켠 상태에서도 `axes.json` 은
    그대로 쌓인다 — 무엇이 배정됐는지는 나중에 비교할 때 필요하다.
    """
    return str(llm.env("NH_STORY_AXES") or "1").strip().lower() in ("1", "on", "true", "yes")


TRAIT_AXIS = "주인공_모순"


def has_character_traits(char: dict) -> bool:
    """사용자가 성격을 정할 거리를 줬는가 — 설명 · 항목 · 고른 카드 중 하나라도."""
    return bool(char.get("description") or char.get("fields") or char.get("card"))


def story_variety_block(run_dir: Path, char: dict) -> str:
    """방향별 압력 — 그리고 (켜져 있으면) 이야기 변수 · 회차 구조.

    **방향마다 하나씩** 뽑는다. run 하나가 이야기 4개라 방향이 단위다.
    (한 벌을 4개가 나눠 쓰게 했다가 관계_구도가 4개 전부에 같이 걸리는
    것을 실측으로 확인하고 고쳤다. 2026-08-31.)

    압력은 넷뿐이라 방향 1~4 가 A·B·C·D 를 하나씩 나눠 갖는다. 어느 방향이
    무엇을 받는지는 매 run 무작위다(`_pick_engines` 의 shuffle) — 사람이
    보는 후보 순서에서 압력의 차례를 읽어 낼 수 없게 하려는 것이다.

    축·구조는 기본이 꺼짐이다(`axes_enabled`). 꺼져 있어도 `samples.pick_fresh`
    는 그대로 부른다 — run 사이 반복 회피 기록(`axes.json`)을 남겨 두어야
    나중에 다시 켤 때 이어지고, 무엇이 뽑혔는지 비교할 수 있다.
    """
    axes, structure, fresh = samples.pick_fresh(char["genre"], runs_dir=RUNS_DIR)
    use_axes = axes_enabled()
    axes_list = _distinct_axes(char["genre"], axes) if (axes and use_axes) else []
    structures = _distinct_structures(char["genre"], structure) if (structure and use_axes) else []
    engines = _pick_engines()

    if axes or structure or engines:
        write_json(run_dir / "axes.json",
                   {"축": axes, "구조": structure, "축_사용": use_axes,
                    "엔진_사용": engines_enabled(),
                    "방향별_축": axes_list, "방향별_구조": structures,
                    "방향별_엔진": engines})
    # 사용자가 캐릭터 설명(또는 카드)을 넣었으면 「주인공 모순」은 프롬프트에 안
    # 박는다(#458). 이 축의 값은 처지가 아니라 속마음 — "가까워지는 것이 두려워서
    # 먼저 멀어진다" 같은 성격 묘사 그 자체라서, "성격으로 옮기지 마라"라고 적어도
    # 모델이 그대로 주인공의 성격으로 썼다(2026-09-27 모모 run). 사용자 지시:
    # 설명에 없는 성격은 절대 붙이지 않는다. 설명이 없으면 모델이 성격을 정해야
    # 하니 그대로 둔다. 무엇이 배정됐는지는 위 axes.json 에 그대로 남는다.
    if has_character_traits(char):
        axes_list = [{k: v for k, v in one.items() if k != TRAIT_AXIS} for one in axes_list]
    for i in range(max(len(axes_list), len(structures), len(engines))):
        bits = []
        if i < len(engines):
            bits.append(str(engines[i].get("이름") or ""))
        if i < len(axes_list):
            bits.append(samples.axes_summary(axes_list[i]))
        if i < len(structures):
            bits.append(samples.structure_summary(structures[i]))
        if bits:
            log(f"  방향 {i + 1}: {' | '.join(b for b in bits if b)}")
    if use_axes and (axes or structure) and not fresh:
        log("  (최근 생성물과 조합이 겹칩니다 — 고를 수 있는 폭이 좁습니다)")

    count = max(len(axes_list), len(structures), len(engines))
    if not count:
        return ""
    head = "## 방향별 「문제가 옮겨 가는 길」" \
           + (" · 이야기 변수 · 회차 구조" if use_axes else "") \
           + " — 참고가 아니라 지시다"
    parts = [
        "", head, "",
        "아래 값은 방향 번호에 그대로 대응한다. **방향 N 은 N 번 값으로 쓴다.** "
        "4개가 서로 다른 이야기가 되게 하는 장치가 이것이다 — 넷이 같은 소재로 "
        "모이면 값을 안 쓴 것이다.", "",
        # 값은 어느 세계에나 얹히는 말이라, 번역하지 않으면 아무 데서나 가능한
        # 장면이 된다. 실측으로 확인됐다 — 「동료 · 반복되는 하루 · 대리인」이
        # 마법학교에서 관청 서류 업무로 나왔다(2026-09-19). 무대만 그 장르이고
        # 벌어지는 일은 어느 장르에서나 가능한 판이 되는 것을 여기서 막는다.
        "**값은 그 세계 안에서 무엇으로 나타나는지를 먼저 정하고 쓴다.** 값은 어느 "
        "세계에나 얹히는 말이라, 그대로 두면 아무 데서나 가능한 장면이 된다 — "
        "「동료」를 사무실 동료로, 「반복되는 하루」를 출근길로 쓰면 무대만 그 "
        "장르이고 벌어지는 일은 그 장르가 아니다. 위에 적힌 세계관의 규칙과 그 "
        "장르가 실제로 다루는 것으로 값을 옮겨 적어라. **옮긴 결과를 다른 장르에 "
        "그대로 가져가도 말이 되면, 아직 옮기지 않은 것이다.**", "",
        "**이것은 소재가 아니라 경로다.** 무엇에 대한 이야기인지가 아니라, 처음 "
        "문제가 마지막에 무엇이 되어 있는지를 정한 것이다. 배정된 시작점과 도착점을 "
        "먼저 잡고 그 사이를 채워라. 도착점이 시작점과 같은 종류의 문제면 실패고, "
        "넷의 도착점이 서로 비슷해도 실패다.",
    ]
    if use_axes:
        parts += ["", "이야기 변수는 인물이 어디에 서서 무엇과 부딪히는지를, 회차 "
                  "구조는 그것을 어떤 순서로 보여줄지를 정한다. 그 '어디'와 '무엇'은 "
                  "이 세계의 것이어야 한다 — 값과 세계는 따로가 아니다."]
    parts += ["", "**값은 판과 처지에 건다. 주인공의 성격으로 옮기지 않는다.** 톤은 "
              "이야기의 분위기이고, 주인공 위치와 모순은 주인공이 놓인 처지다. "
              "주인공이 어떤 사람인지는 사용자가 적은 설명만 정한다."]
    for i in range(count):
        parts += ["", f"### 방향 {i + 1}", ""]
        for txt in (_engine_block(engines[i]) if i < len(engines) else "",
                    samples.axes_block(axes_list[i]) if i < len(axes_list) else "",
                    samples.structure_block(structures[i]) if i < len(structures) else ""):
            if txt:
                parts += [txt, ""]
    return "\n".join(parts)


def stage_story(run_dir: Path, char: dict, dry_run: bool, note: str = "",
                review: bool | None = None, diff: bool | None = None) -> list[dict]:
    """이야기 후보 4개. 다 쓰고 나서 **한 번 더 독자의 눈으로 읽는다.**

    review : 후보 하나하나를 검수한다(storycheck). 사람이 고르는 화면에
    판정이 같이 붙어 보이는 것이 전부고, **아무 후보도 막지 않는다.**
    None 이면 `.env`(`NH_STORY_REVIEW`, 기본 켜짐)를 따른다.

    diff : 후보 넷이 서로 다른가를 견준다(storydiff). review 와 같은
    자리에서 도는 별도 검수다 — 하나가 하나를 읽는 것이 아니라 넷을 짝지어
    본다. None 이면 `.env`(`NH_STORY_DIFF`, **기본 꺼짐**)를 따른다. 이
    자가 사람 눈과 맞는지 아직 확인되지 않아서, storycheck 과 달리 켜져
    있지 않다(`storydiff.enabled` 참고).
    """
    # 사람이 줄거리를 적었으면 그 줄거리가 중심이다 — 프롬프트도, 입력
    # 블록도 따로 간다(seeded_input_block 참고). 안 적었으면 예전 그대로.
    seeded = bool(user_story(char))
    if seeded:
        block = seeded_input_block(char).rstrip("\n")
    else:
        block = story_input_block(char, run_dir).rstrip("\n") + "\n" + story_variety_block(run_dir, char)
    note = (note or "").strip()
    if note:
        # 다시 만들기에서 사람이 남긴 요청 — 캐릭터 설정 자체가 아니라 "이번엔
        # 이렇게 더 반영해 달라"는 한 번짜리 지시라, story_input_block 이 아니라
        # 여기서 따로 붙인다(캐릭터 파일을 고치면 다음 시도에도 계속 남는다).
        block += f"\n\n## 이번 시도에 추가로 반영할 것\n사용자가 방금 다시 만들기를 " \
                 f"요청하며 남긴 말이다. 가능한 한 반영한다:\n{note}"
    prompt = compose("story_prompt_seeded" if seeded else "story_prompt", block)
    write_text(run_dir / "story_prompt.txt", prompt)
    if dry_run:
        log(f"[이야기] 프롬프트만 썼습니다 -> {run_dir / 'story_prompt.txt'}")
        return []

    call = llm.Call("STORY")
    log(f"[이야기] {call.describe()} 로 후보 4개를 만듭니다…")
    try:
        text, meta = call(prompt, images=llm.load_images(char["photos"]))
    except Exception as exc:                                          # noqa: BLE001
        record_error(run_dir, "STORY", call.provider, call.model, exc)
        raise
    write_text(run_dir / "story.md", text)
    record(run_dir, meta)

    directions = parse_directions(text)
    if len(directions) != 4:
        warn(f"방향을 {len(directions)}개만 읽었습니다 (4개여야 합니다). "
             f"원문은 {run_dir / 'story.md'} 에 그대로 있습니다.")
    write_json(run_dir / "directions.json", directions)

    # 검수는 후보를 쓴 **뒤**다. 앞에 두면(만들면서 같이 보게 하면) 만드는
    # 쪽과 보는 쪽이 한 호출에 섞여서, 자기가 방금 만든 것은 그럴듯해
    # 보인다 — stage_review 가 따로 있던 이유와 같다.
    if (storycheck.enabled() if review is None else review) and directions:
        _, rmeta = storycheck.review_directions(run_dir, char, directions)
        if rmeta:
            record(run_dir, rmeta)

    # 넷이 서로 다른가는 후보 하나하나를 보는 것과 다른 질문이라, 따로
    # 붙였다(storydiff 문서 참고) — 기본 꺼짐이라 지금은 켜기 전까지
    # 아무 run 에도 안 걸린다.
    if (storydiff.enabled() if diff is None else diff) and directions:
        _, dmeta = storydiff.diff_directions(run_dir, char, directions)
        if dmeta:
            record(run_dir, dmeta)
    return directions


def show_directions(directions: list[dict]) -> None:
    for d in directions:
        genre = f"  [{d['genre']}]" if d["genre"] else ""
        print(f"\n── {d['n']}. {d['title']}{genre}")
        if d.get("intro"):
            print(f"   {d['intro']}")
        elif d["plot"]:
            print(f"   {d['plot'].splitlines()[0]}")
        for s in d["scenes"]:
            print(f"     · {s}")


def choose(directions: list[dict], pick: int | None) -> dict:
    if pick is not None:
        for d in directions:
            if d["n"] == pick:
                return d
        raise SystemExit(f"방향 {pick} 이 없습니다 (있는 것: "
                         f"{[d['n'] for d in directions]}).")
    show_directions(directions)
    while True:
        answer = input("\n어느 방향으로 갈까요? 번호: ").strip()
        for d in directions:
            if answer == str(d["n"]):
                return d
        print("목록에 있는 번호를 넣으세요.")


# 장르 문자열(자유 텍스트, 예: "헌터·게이트") -> story-harness/worlds.json
# 프리셋 라벨의 키워드. 여러 개 걸리면 첫 번째로 매칭된 것을 쓴다. 장르가
# 이 목록에 없으면(오컬트 미스터리·좀비 아포칼립스 등) 조용히 건너뛴다 —
# 세계관 문장 없이도 지금까지처럼 돌아간다.
# **순서가 곧 우선순위다.** 합성 장르명("게임 판타지"·"로맨스 판타지")이 넓은
# 쪽("판타지")에 먼저 걸리면 엉뚱한 세계관이 붙으므로 좁은 쪽을 위에 둔다
# (samples.guess_genre 의 표와 같은 이유·같은 순서).
#
# 예전에는 여섯 줄뿐이라 화면이 고르게 해 둔 장르 14개 중 9개(로맨스 판타지·
# 판타지·게임 판타지·센티넬·오메가버스·스릴러·액션·개그·일상)가 세계관 문장을
# 한 줄도 못 받았다 — 장르를 골라도 그 장르의 규칙이 프롬프트에 없었다는 뜻이다
# (2026-09-17, 사용자 지적으로 확인).
_WORLD_KEYWORDS = {
    # 합성 장르 — '판타지' 보다 먼저
    "romance_novel": ("로맨스 판타지", "로판", "빙의", "회귀", "영애"),
    "hunter_gate": ("헌터", "게이트"),
    "academy_magic": ("마법학교", "마법", "학원"),
    "idol_agency": ("아이돌", "연습생"),
    "sentinel_center": ("센티넬", "가이드버스"),
    "omegaverse_grade": ("오메가버스", "옴버"),
    "hero_city": ("히어로", "능력자", "빌런"),
    "post_disaster": ("재난", "좀비", "아포칼립스"),
    "thriller_record": ("스릴러", "서스펜스"),
    "action_contract": ("액션", "격투"),
    # 넓은 쪽은 맨 아래 — 위에서 아무것도 안 걸렸을 때만 쓴다
    "fantasy_continent": ("판타지",),
}


def genre_lore_for(genre: str) -> str:
    """장르에 맞는 story-harness 의 장르 템플릿(모티프·캐릭터유형·전개패턴·
    체크리스트)을 그대로 빌린다. 없으면 빈 문자열.

    story-harness/samples/genre_template.json 의 `_preset_map` 이 "헌터·게이트"
    같은 한글 장르명을 이미 판타지·액션·스릴러 같은 실제 템플릿 조합으로
    라우팅해 둔 상태다(story.resolve_genre_templates). world_text_for 보다
    훨씬 구체적이라 — 던전·이세계 전이·용/드래곤 같은 실제 소재 목록과
    캐릭터 유형·클리셰까지 들어 있다. 이걸 못 찾고 있다가 사용자가 다시
    짚어서 뒤늦게 붙였다.
    """
    genre = (genre or "").strip()
    if not genre:
        return ""
    try:
        names = story.resolve_genre_templates(genre)
        if not names:
            return ""
        return story.genre_template_block(names)
    except Exception:
        return ""


# 기준 샘플을 프롬프트에 붙일 때 같이 주는 경고. 샘플은 정답지가 아니라
# 기준선이라(samples.py 첫 줄) 이 말을 빼면 모델이 카드 소재를 그대로
# 베낀다 — story-harness 쪽 P1 도 같은 문장을 달고 쓴다.
GENRE_SAMPLE_NOTE = (
    "아래는 이 장르가 실제로 어떤 소재·어떤 밀도로 쓰이는지 보여 주는 "
    "기준선이다. **베끼지 마라** — 같은 소재가 다시 나오면 베낀 것이 "
    "바로 보인다. 이 장르의 이야기가 무엇을 다루는지, 어느 정도로 "
    "구체적인지만 읽고 네 캐릭터의 이야기를 써라."
)


def genre_samples_for(genre: str, run_dir: Path | None = None) -> str:
    """장르에 맞는 story-harness 의 검수된 기준 샘플 카드. 없으면 빈 문자열.

    samples/ 에는 장르 14종마다 사람이 검수해 실제로 서비스에 나간 카드가
    6장씩 있고, `samples.guess_genre` 가 "아이돌"·"헌터·게이트" 같은 한글
    장르명을 그 풀로 정확히 민다. 그런데 new_harness 는 이 풀을 **한 번도
    안 쓰고** 있었다 — axes·structure(무엇을 쓰는가의 '자리')만 빌려 쓰고,
    정작 그 장르가 무엇을 다루는지(소재)는 안 빌렸다.

    그래서 소재 쪽 입력이 worlds.json 한 문단뿐이었고, 장르 템플릿은
    전개 문법(일상·로맨스 …)만 주는 축이라 — 아이돌을 골라도 연습생·무대가
    한 번도 안 나오고 그냥 학원물이 됐다(2026-09-17, 사용자 지적).

    못 찾으면 빈 문자열이다. 안 맞는 장르 카드를 억지로 붙이지 않는다
    (resolve_genre_templates 와 같은 원칙).

    `run_dir` 이 있으면 회피가 붙는다(2026-09-19) — 장르당 카드가 6장뿐이라
    (`samples.EXEMPLAR_PICK` 주석 참고) 최근 run들과 안 겹치게 고르지 않으면
    몇 번 안 가 같은 3장 조합이 반복된다(사용자 지적 — "시작점이 5개뿐이라
    매번 비슷해 보인다"와 같은 종류의 문제, 카드 쪽이 더 좁다). 골랐으면 그
    run 디렉터리에 `story_cards.json` 으로 남겨서 다음 run 이 이어 피한다.
    """
    genre = (genre or "").strip()
    if not genre:
        return ""
    try:
        key = samples.guess_genre(genre)
        if not key:
            return ""
        if run_dir is None:
            return samples.exemplars(key)
        avoid = samples.recent_card_ids(key, RUNS_DIR)
        text, ids = samples.exemplars_fresh(key, avoid_ids=avoid)
        if ids:
            write_json(run_dir / "story_cards.json", {"genre": key, "ids": ids})
        return text
    except Exception:
        return ""


# 장르 템플릿을 **빌려 쓸 때** 같이 주는 틀.
#
# genre_template.json 의 _preset_map 은 일부러 두 축을 나눠 뒀다 — 프리셋
# (아이돌·헌터·마법학교·히어로)은 **소재** 축이고, 템플릿(일상·로맨스·판타지…)은
# **전개 문법** 축이다. 그래서 아이돌은 "일상+로맨스" 를 빌린다.
#
# 문제는 빌려온 템플릿의 '소재모티프배경'·'사례분석' 칸이 그 템플릿 장르의
# 소재("학교, 회사, 가정, 카페")로 차 있다는 것이다. 틀 없이 그대로 주면
# 모델이 그것을 이번 화의 소재로 읽는다 — 아이돌을 골랐는데 예술고등학교
# 복도와 교복이 나온 실제 경로가 이것이다(2026-09-17).
#
# 그래서 빌려 쓸 때는 "여기서 가져갈 것은 전개 문법뿐, 소재는 위 세계관과
# 샘플을 따른다"를 명시한다. 템플릿을 덜어내지 않는 이유는 전개 문법 쪽은
# 그대로 쓸모가 있고, story.py 의 렌더링은 다른 하네스와 공유라서다.
GENRE_LORE_NOTE_BORROWED = (
    "아래 템플릿은 이 장르의 **전개 문법**(분위기·인물 관계·사건이 굴러가는 "
    "방식)을 빌려 오려고 붙인 것이지, 이 화의 소재가 아니다. 템플릿에 적힌 "
    "장소·소품·직업(학교·회사·카페 같은 것)을 이번 화의 무대로 가져오지 마라 "
    "— 무대와 소재는 위의 세계관과 기준 샘플이 정한다."
)


def genre_lore_section(genre: str) -> list[str]:
    """장르 문법 블록 + (빌려 쓴 것이면) 그 사실을 밝히는 틀.

    이야기 단계와 장면 단계가 **같은 문구**를 쓰도록 여기 하나로 모은다.
    """
    lore = genre_lore_for(genre)
    if not lore:
        return []
    try:
        names = story.resolve_genre_templates(genre)
    except Exception:
        names = []
    borrowed = bool(names) and genre.strip() not in names
    head = ["", "## 이 장르의 전개 문법 (참고 자료)"]
    if borrowed:
        head += ["", GENRE_LORE_NOTE_BORROWED]
    return head + ["", lore]


def world_text_for(genre: str) -> str:
    """장르에 맞는 story-harness/worlds.json 세계관 한 문단. 없으면 빈 문자열.

    detail_prompt 가 "장르"만 받고 구체적인 세계 규칙을 못 받아서, 구체화
    단계가 장르 특유의 소재(마나·몬스터·게이트 현상 등) 없이 아무 장르에나
    쓸 수 있는 일반적인 소재(출입증·CCTV·무전기)로 채우는 문제가 있었다
    (2026-08-30, 사용자 지적). story-harness 가 이미 갖고 있는 프리셋
    문장을 그대로 빌려 온다 — 새 문장을 짓지 않는다.
    """
    genre = (genre or "").strip()
    if not genre:
        return ""
    path = llm.STORY_HARNESS / "worlds.json"
    if not path.exists():
        return ""
    try:
        presets = json.loads(path.read_text(encoding="utf-8")).get("presets") or {}
    except Exception:
        return ""
    for key, keywords in _WORLD_KEYWORDS.items():
        if any(kw in genre for kw in keywords):
            return (presets.get(key) or {}).get("text") or ""
    return ""


def picked_direction(run_dir: Path, pick: int | None) -> dict:
    """이 run 에서 고른 방향. --pick 을 안 줘도 pick.json 에서 찾는다."""
    path = run_dir / "directions.json"
    if not path.exists():
        raise SystemExit(f"{path} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
    picked = run_dir / "pick.json"
    n = pick or (json.loads(picked.read_text(encoding="utf-8"))["n"]
                 if picked.exists() else None)
    if n is None:
        raise SystemExit("--pick <번호> 로 어느 방향인지 알려주세요.")
    return choose(json.loads(path.read_text(encoding="utf-8")), n)


# ------------------------------------------------------------- 장면 (scene_prompt)
#
# story_prompt 는 이제 방향 4개(제목+본문)만 낸다. 장면은 그중 **고른 뒤에**
# scene_prompt 가 따로 만든다 — 줄거리를 풀고, 장면으로 쪼개고, 장면마다
# 「직전 상태」·「끝나는 상태」를 적어서 동시에 그릴 수 있게 한다.

SCENE_RE = re.compile(rf"^{S}장면{S}(\d+){S}[:：]?{S}$", re.M)
SCENE_FIELD_RE = re.compile(
    rf"^{S}(직전 상태|장소와 상황|벌어지는 일|인물의 행동과 표정|끝나는 상태){S}[:：]{S}(.*)$")
PLOT_LABEL_RE = re.compile(rf"^{S}줄거리{S}[:：]{S}$", re.M)
CAST_LABEL_RE = re.compile(rf"^{S}등장인물{S}[:：]{S}$", re.M)


def scene_input_block(char: dict, direction: dict) -> str:
    """scene_prompt 뒤에 붙는 이번 입력 — 고른 스토리 + 캐릭터 + **장르**.

    **장르를 여기에도 준다.** 예전에는 제목·본문·캐릭터만 넘겼는데, 장면
    단계는 무엇을 실제로 그릴지(장소·상황·옷차림)를 정하는 자리라 장르를
    모르면 가장 무난한 해석으로 번역해 버린다 — "데뷔조 막내"를 받고
    「예술고등학교 복도 · 교복」으로 적어서, 아이돌을 골랐는데 다 그리고
    나면 그냥 학원물이 되어 있었다(2026-09-17, 실측 확인: 그 run 의
    scene_prompt.txt 10KB 안에 '아이돌·연습생·데뷔·기획사' 가 0번).

    이야기 단계와 **같은 자료**를 준다(genre_lore_for · world_text_for ·
    genre_samples_for) — 단계마다 다른 것을 주면 고른 이야기와 그려지는
    장면이 갈라진다. 장르가 없으면 지금까지처럼 아무것도 안 붙는다.
    """
    lines = ["# 이번 입력", "", "[선택된 스토리]", direction.get("title", ""), ""]
    lines.append(direction.get("body") or direction.get("raw", ""))
    lines += ["", "[캐릭터]", f"{char['name']} — {char.get('description') or ''}".rstrip(" —")]
    # 고른 카드가 있으면 종·세계·정체를 같이 준다(#458) — 원래 설명(사람 ·
    # 대학생 …)만 보고 장면을 짜면 사람이 고른 캐릭터가 아닌 인물로 그린다.
    if charcard.short(char.get("card") or {}):
        lines.append(f"- 고른 캐릭터 카드: {charcard.short(char['card'])} (원래 설명과 다르면 카드를 따른다)")
    for k, v in (char.get("fields") or {}).items():
        lines.append(f"- {k}: {v}")

    # 고른 방향이 스스로 밝힌 장르가 먼저다. 사람이 장르를 안 고르면
    # story_prompt 가 방향마다 장르를 정하므로, 그때는 캐릭터의 장르 칸이
    # 비어 있고 방향 쪽에만 있다.
    genre = (direction.get("genre") or char.get("genre") or "").strip()
    if genre:
        lines += ["", f"[장르] {genre}",
                  "", "이 화는 위 장르의 작품이다. 장소·소품·옷차림·인물들이 하는 "
                  "일이 그 장르의 것이어야 한다 — 장르를 지우고 아무 데서나 "
                  "일어날 수 있는 장면으로 옮기지 마라."]
        world = world_text_for(genre)
        if world:
            lines += ["", "## 이 세계의 배경 — 이 안에서 무엇이 벌어질지는 정해져 있지 않다", "", world]
        cards = genre_samples_for(genre)
        if cards:
            lines += ["", "## 이 장르의 기준 샘플 (사람이 검수해 서비스에 나간 카드)",
                      "", GENRE_SAMPLE_NOTE, "", cards]
        lines += genre_lore_section(genre)
    return "\n".join(lines) + "\n"


def parse_scenes(text: str) -> dict:
    """scene_prompt 응답 -> {"plot", "scenes":[{"n","prev","where","what","acting","ends"}], "cast"}."""
    plot_m = PLOT_LABEL_RE.search(text)
    scene_marks = list(SCENE_RE.finditer(text))
    cast_m = CAST_LABEL_RE.search(text)
    plot = ""
    if plot_m:
        end = scene_marks[0].start() if scene_marks else len(text)
        plot = text[plot_m.end():end].strip()

    scenes = []
    for i, m in enumerate(scene_marks):
        end = scene_marks[i + 1].start() if i + 1 < len(scene_marks) else len(text)
        if cast_m and cast_m.start() < end and cast_m.start() > m.start():
            end = cast_m.start()
        body = text[m.end():end]
        fields = {}
        for line in body.splitlines():
            fm = SCENE_FIELD_RE.match(line)
            if fm:
                fields[fm.group(1)] = fm.group(2).strip()
        scenes.append({
            "n": int(m.group(1)),
            "prev": fields.get("직전 상태", ""),
            "where": fields.get("장소와 상황", ""),
            "what": fields.get("벌어지는 일", ""),
            "acting": fields.get("인물의 행동과 표정", ""),
            "ends": fields.get("끝나는 상태", ""),
        })
    cast = _cast_bullets(text[cast_m.end():]) if cast_m else []
    return {"plot": plot, "scenes": scenes, "cast": cast}


def stage_scenes(run_dir: Path, char: dict, direction: dict, dry_run: bool) -> dict | None:
    """선택된 방향 -> 줄거리 + 장면(직전 상태·끝나는 상태 포함). `scenes.json` 에 쓴다."""
    prompt = compose("scene_prompt", scene_input_block(char, direction))
    write_text(run_dir / "scene_prompt.txt", prompt)
    if dry_run:
        log(f"[장면] 프롬프트만 썼습니다 -> {run_dir / 'scene_prompt.txt'}")
        return None

    call = llm.Call("SCENE")
    log(f"[장면] {call.describe()} 로 줄거리와 장면을 만듭니다…")
    try:
        text, meta = call(prompt)
    except Exception as exc:                                          # noqa: BLE001
        record_error(run_dir, "SCENE", call.provider, call.model, exc)
        raise
    write_text(run_dir / "scene.md", text)
    record(run_dir, meta)

    parsed = parse_scenes(text)
    if len(parsed["scenes"]) < 4:
        warn(f"장면을 {len(parsed['scenes'])}개만 읽었습니다 (4개 이상이어야 합니다). "
             f"원문은 {run_dir / 'scene.md'} 에 그대로 있습니다.")
    write_json(run_dir / "scenes.json", parsed)
    return parsed


def stage_sheet(run_dir: Path, char: dict, dry_run: bool,
                spec_only: bool = False, note: str = "") -> None:
    photos = char["photos"]
    block = input_block(char)
    note = (note or "").strip()
    if note:
        # 다시 만들기에서 남긴 한 번짜리 요청 — character.json 을 고치지 않고
        # 여기서만 붙인다(stage_story 의 note 와 같은 이유).
        block += f"\n\n## 이번 시도에 추가로 반영할 것\n사용자가 방금 다시 만들기를 " \
                 f"요청하며 남긴 말이다. 가능한 한 반영한다:\n{note}"
    prompt = compose("sheet_prompt", block)
    write_text(run_dir / "sheet_spec_prompt.txt", prompt)

    spec_path = run_dir / "sheet_spec.json"
    if spec_path.exists():
        log(f"[시트] 사양이 이미 있습니다 -> {spec_path} (재사용)")
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
    elif dry_run:
        log(f"[시트] 사양 프롬프트만 썼습니다 -> {run_dir / 'sheet_spec_prompt.txt'}")
        return
    else:
        call = llm.Call("SHEET")
        log(f"[시트] {call.describe()} 로 사양을 적습니다…")
        try:
            text, meta = call(prompt, images=llm.load_images(photos), temperature=0.4)
        except Exception as exc:                                      # noqa: BLE001
            record_error(run_dir, "SHEET", call.provider, call.model, exc)
            raise
        record(run_dir, meta)
        spec = sheetmod.parse_spec(text)
        bad = sheetmod.gate_spec(spec)
        if bad:
            write_json(run_dir / "sheet_spec_rejected.json", spec)
            raise SystemExit("시트 사양이 모자랍니다 — 그리기 전에 멈춥니다:\n  - "
                             + "\n  - ".join(bad))
        write_json(spec_path, spec)

    image_prompt = sheetmod.build_prompt(spec, from_photo=bool(photos))
    write_text(run_dir / "sheet_prompt.txt", image_prompt)
    if spec_only:
        log(f"[시트] 사양까지만 했습니다. 그림은 안 그렸습니다 -> {spec_path}")
        return
    if dry_run:
        log(f"[시트] 이미지 프롬프트만 썼습니다 -> {run_dir / 'sheet_prompt.txt'}")
        return

    out = run_dir / "sheet.png"
    if out.exists():
        log(f"[시트] {out} 가 이미 있습니다. 다시 뽑으려면 지우세요.")
        return
    # 사진은 **안 붙인다.** 사양(appearance_en)이 기준이다.
    #
    # OpenAI 는 참조 이미지가 붙으면 편집 쪽으로 가서 "이 그림을 고쳐라" 에
    # 가깝게 읽는다. 올린 사진이 낙서나 다른 화풍이면 그것을 따라가느라
    # 사양대로 안 그린다. 사양은 이미 사진을 보고 쓴 것이라(SHEET 단계에서
    # 사진을 첨부해 읽는다) 여기서 사진을 또 붙일 이유가 없다.
    log("[시트] 그리는 중… (사진 없이 사양만)")
    sheet_provider, sheet_model, _q = imagegen.backend_for("SHEET_IMAGE")
    try:
        meta = sheetmod.paint(image_prompt, out)
    except Exception as exc:                                          # noqa: BLE001
        record_error(run_dir, "SHEET_IMAGE", sheet_provider, sheet_model, exc)
        raise
    record(run_dir, meta)
    log(f"  -> {out}")


def direction_of(run_dir: Path) -> dict | None:
    """이 run 에서 고른 방향. 없으면 첫 번째. 둘 다 없으면 None."""
    pick = json.loads((run_dir / "pick.json").read_text(encoding="utf-8")) \
        if (run_dir / "pick.json").exists() else {}
    path = run_dir / "directions.json"
    if not path.exists():
        return None
    directions = json.loads(path.read_text(encoding="utf-8"))
    return (next((d for d in directions if d.get("n") == pick.get("n")), None)
            or (directions[0] if directions else None))


def stage_detail_pages(run_dir: Path, dry_run: bool, only=None,
                       allow_no_sheet: bool = False,
                       review: bool | None = None,
                       note: str = "") -> None:
    """이어그리기(최종 방식) — **구체화·콘티·컷 대본을 전부 건너뛰고**
    scene_prompt 산출물(scenes.json)만으로 표지+전체 씬을 그린다.

    2026-09-02 이전에는 이 함수가 `detailart.draw()`(구체화 후 씬 단위)를
    불렀다 — 이제는 `detailart.draw_continue()`를 부른다. 씬 하나가 페이지
    하나가 되는 것은 같지만, 무엇을 그릴지 결정하는 재료가 detail.json이
    아니라 scenes.json(+pick.json)이다. `detailart.draw()`·`build_prompt()`
    등 구체화 버전 코드는 지우지 않고 그대로 남겨 뒀다 — 나중에 다시 비교할
    수 있게.

    쓰는 자리가 `pages/` 로 같아서 둘러보기·편집실은 어느 흐름으로 만든
    것인지 몰라도 된다.
    """
    # scenes.json 이 없으면 먼저 만든다 — 그림 값이 나가기 전이다.
    #
    # **한 장만 다시 그리는 길(`only`)에서는 안 만든다.** 그 길은 이미 그린
    # 화의 한 장을 고치는 것이고, 장면을 나눠 동시에 그릴 때도 이 모양으로
    # 들어온다. 거기서 새로 장면을 쪼개면 프로세스마다 다른 장면이 생겨서,
    # 이 단계로 없애려던 어긋남이 그대로 돌아온다.
    if not only and not read_json(run_dir / "scenes.json"):
        direction = direction_of(run_dir)
        if not direction:
            raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다. 이야기 단계를 먼저 돌리세요.")
        char = json.loads((run_dir / "input.json").read_text(encoding="utf-8")) \
            if (run_dir / "input.json").exists() else None
        stage_scenes(run_dir, char, direction, dry_run)

    made = detailart.draw_continue(run_dir, dry_run=dry_run, only=only,
                                   allow_no_sheet=allow_no_sheet, review=review,
                                   note=note,
                                   on_page=lambda meta: record(run_dir, meta))
    if made:
        log(f"[이어그리기] {len(made)}장 그렸습니다 -> {run_dir / detailart.PAGE_DIR}")

    # 화 전체 검수(fullreview)는 여기서 자동으로 안 부른다. 2026-09-16부터
    # 그 트리거·재생성 루프·한도는 JobRunner.java 가 쥔다(webtoon/docs/
    # full-review-design.md §6.1) — 파이썬은 그리기와 판정만 하고, "언제
    # 다시 검수를 돌리고 언제 멈출지"는 상태 머신을 가진 자바 쪽 책임이다.
    # 단독으로 보고 싶으면 `--full-review`(아래 CLI)를 따로 부른다.


# --------------------------------------------------------------------- CLI

def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        description="new_harness — 이야기 후보 · 콘티 · 캐릭터 시트",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    p.add_argument("--character", type=Path,
                   help="landing 의 character.json (또는 jobs/<id> 폴더)")
    p.add_argument("--name", help="캐릭터 이름 (필수)")
    p.add_argument("--photo", action="append", default=[], help="사진 (여러 번 가능)")
    p.add_argument("--desc", default="", help="설명 (선택)")
    p.add_argument("--genre", default="", help="장르 (선택)")

    p.add_argument("--run-id",
                   help="이어서 할 run. 없는 번호를 주고 --character 를 같이 "
                        "주면 그 번호로 새로 만든다 (앱 서버가 번호를 쥔다)")
    p.add_argument("--pick", type=int, help="고를 방향 번호 (없으면 물어본다)")
    p.add_argument("--story-review", action="store_true",
                   help="이미 만든 이야기 후보를 검수만 한다 (기본 흐름에서 이미 "
                        "자동으로 도는 단계 — 단독 재실행용. 후보는 안 건드리고 "
                        "story_review.json 만 쓴다)")
    p.add_argument("--sheet", action="store_true", help="캐릭터 시트만")
    p.add_argument("--sheet-spec", action="store_true",
                   help="시트 사양(글)만. 그림은 안 그린다")
    p.add_argument("--sheet-from", type=Path,
                   help="이미 뽑아 둔 시트를 가져온다 (story-harness run 폴더 · "
                        "new_harness run 폴더 · png 하나). 호출 0회")
    p.add_argument("--scenes", action="store_true",
                   help="고른 방향(본문) -> 장면(직전 상태·끝나는 상태·등장인물 "
                        "포함, 글 호출 1회). scene_prompt 를 쓴다 — --pick 으로 "
                        "고른 뒤(또는 pick.json 이 있을 때) 쓴다. 그리기 "
                        "(`--detail-pages`)가 이 파일이 없으면 알아서 한 번 "
                        "만든다 — 장면을 나눠 동시에 그릴 거면 그리기 전에 이걸 "
                        "먼저 한 번 따로 돌려 둔다")
    p.add_argument("--detail-pages", action="store_true",
                   help="이어그리기(최종 방식) — 구체화·콘티·컷 대본을 전부 "
                        "건너뛰고 방향 후보로 바로 페이지를 그린다 "
                        "(1페이지가 표지, 장면 하나가 페이지 하나)")
    p.add_argument("--pick-save", action="store_true",
                   help="다른 단계를 안 돌리고 pick.json 만 남긴다 — 이어그리기 "
                        "흐름은 구체화가 없어서, 방향을 고른 뒤 검수 화면으로 "
                        "가기 전에 이걸로 pick 만 기록한다 (호출 0회)")
    p.add_argument("--restory", action="store_true",
                   help="기존 run 에서 이야기 후보 4개를 다시 만든다 (방향 고르기 "
                        "화면에서 '다시 만들기' — --note 와 같이 쓸 수 있다)")
    p.add_argument("--page", type=int, action="append", default=[],
                   help="그 번호 페이지만 다시 (여러 번 가능)")
    p.add_argument("--no-sheet", action="store_true",
                   help="캐릭터 시트 없이 페이지를 그린다 (인물이 장마다 달라진다)")
    p.add_argument("--no-page-review", action="store_true",
                   help="이어그리기에서 그린 뒤 검수를 하지 않는다 (기본은 켜짐 — "
                        ".env 의 NH_PAGE_REVIEW=0 과 같다)")
    p.add_argument("--no-story-review", action="store_true",
                   help="이야기 후보를 만든 뒤 검수를 하지 않는다 (기본은 켜짐 — "
                        ".env 의 NH_STORY_REVIEW=0 과 같다)")
    p.add_argument("--story-diff", action="store_true",
                   help="이미 만든 이야기 후보 넷이 서로 다른가를 견주기만 한다 "
                        "(기본 흐름에선 NH_STORY_DIFF=1 일 때만 자동으로 도는 단계 — "
                        "단독 재실행용. 후보는 안 건드리고 story_diff.json 만 쓴다)")
    p.add_argument("--full-review", action="store_true",
                   help="이미 그린 화를 처음부터 끝까지 읽어 검수만 한다 "
                        "(다시 그리지 않는다. full_review.json 만 쓴다)")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    p.add_argument("--note", default="", help="다시 만들기에서 이번 시도에만 추가로 "
                                              "반영할 요청 (이야기·시트 단계에서 씀)")
    p.add_argument("--plan", action="store_true", help="단계별 모델만 보여준다")
    args = p.parse_args(argv)

    if args.plan:
        rows = llm.plan()
        cols = [
            ("단계", lambda r: r["stage"]),
            ("", lambda r: r["label"]),
            ("모델", lambda r: f"{r['provider']}:{r['model']}"),
            ("어디서", lambda r: r["from"]),
        ]
        table = [[head for head, _ in cols]] + [[get(r) for _, get in cols]
                                                for r in rows]
        widths = [max(_wide(row[i]) for row in table) for i in range(len(cols))]
        for i, row in enumerate(table):
            print("  " + "  ".join(_pad(cell, w) for cell, w in zip(row, widths)).rstrip())
            if i == 0:
                print("  " + "  ".join("─" * w for w in widths))
        print("\n  바꾸려면 .env 에 <단계>_PROVIDER / <단계>_MODEL 을 적으세요 "
              "(.env.example 참고).")
        return 0

    if args.run_id and (RUNS_DIR / args.run_id).exists():
        run_dir = RUNS_DIR / args.run_id
        char = read_input(run_dir)
        new_run = False
    elif args.run_id and not (args.character or args.name):
        # 이어서 하라고 했는데 이어 갈 것이 없다.
        raise SystemExit(f"그런 run 이 없습니다: {RUNS_DIR / args.run_id}")
    else:

        char = (read_character(args.character) if args.character
                else normalize({"name": args.name, "description": args.desc,
                                "genre": args.genre, "photos": args.photo}))
        bad = gate_input(char)
        if bad:
            raise SystemExit("입력이 모자랍니다:\n  - " + "\n  - ".join(bad))
        # **번호는 부르는 쪽이 정할 수 있다.**
        #
        # 안 주면 예전처럼 여기서 짓는다(사람이 손으로 돌릴 때). 주면 그 번호를
        # 쓴다 — 앱 서버가 여러 편을 **동시에** 돌리기 시작하면 "방금 생긴 폴더"
        # 로는 어느 것이 누구 것인지 못 가린다. 부르는 쪽이 번호를 쥐고 있으면
        # 가릴 것도 없다.
        run_dir = RUNS_DIR / (args.run_id or story.new_run_id())
        write_json(run_dir / "input.json", char)
        new_run = True
        log(f"run: {run_dir}")

    # 시트 가져오기는 어느 흐름이든 **가장 먼저** 한다 — 뒤의 단계가 이 시트를
    # 참조로 쓰고, 이미 있으면 시트 단계가 새로 그리지 않는다.
    if args.sheet_from:
        got = sheetmod.import_sheet(run_dir, args.sheet_from)
        who = f" ({got['name']})" if got.get("name") else ""
        log(f"[시트] 가져왔습니다{who} <- {got['from']}")
        log(f"  사양도 함께: {'예' if got['spec'] else '아니오 (그림만)'}")

    # 한 단계만 다시 돌리는 길.
    #
    # --sheet-from 만 준 것도 여기서 끝난다 — 시트를 가져다 놓는 것이 그
    # 명령의 전부인데, 그냥 흘려보내면 아래 이야기 단계로 내려가 "어느 방향으로
    # 갈까요" 를 묻는다 (실제로 그래서 EOFError 로 죽었다).
    if (args.story_review or args.story_diff or args.full_review
            or args.sheet or args.sheet_spec or args.detail_pages
            or args.page or args.sheet_from or args.pick_save or args.restory
            or args.scenes):
        if args.restory:
            # 방향 후보를 다시 만든다 — 이전 pick.json 은 더 이상 유효하지
            # 않다(방향 번호가 새로 나온 4개와 안 맞을 수 있다), 지운다.
            (run_dir / "pick.json").unlink(missing_ok=True)
            # 지난 판정도 같이 지운다 — 후보가 바뀌었는데 옛 판정이 남아
            # 있으면 화면이 다른 이야기의 지적을 붙여 보여준다.
            (run_dir / "story_review.json").unlink(missing_ok=True)
            (run_dir / "story_diff.json").unlink(missing_ok=True)
            stage_story(run_dir, char, args.dry_run, note=args.note,
                        review=False if args.no_story_review else None)
        if args.story_review:
            storycheck.review_run(run_dir, dry_run=args.dry_run,
                                  on_call=lambda meta: record(run_dir, meta))
        if args.story_diff:
            storydiff.diff_run(run_dir, dry_run=args.dry_run,
                               on_call=lambda meta: record(run_dir, meta))
        if args.full_review:
            fullreview.review_run(run_dir, dry_run=args.dry_run,
                                  on_call=lambda meta: record(run_dir, meta))
        if args.pick_save:
            chosen = picked_direction(run_dir, args.pick)
            write_json(run_dir / "pick.json", {"n": chosen["n"], "title": chosen["title"],
                                               "genre": chosen["genre"]})
            log(f"[방향 선택] {chosen['n']}번 저장했습니다 -> {run_dir / 'pick.json'}")
        if args.sheet or args.sheet_spec:
            stage_sheet(run_dir, char, args.dry_run, spec_only=args.sheet_spec, note=args.note)
        if args.scenes:
            direction = picked_direction(run_dir, args.pick)
            stage_scenes(run_dir, char, direction, args.dry_run)
        if args.detail_pages:
            stage_detail_pages(run_dir, args.dry_run, only=args.page or None,
                               allow_no_sheet=args.no_sheet,
                               review=False if args.no_page_review else None,
                               note=args.note)
        return 0

    if new_run:
        directions = stage_story(run_dir, char, args.dry_run, note=args.note,
                                 review=False if args.no_story_review else None)
        if args.dry_run:
            return 0
        show_directions(directions)
        print(f"\n골랐으면:  python run.py --run-id {run_dir.name} --pick <번호> --pick-save")
        print(f"장면:      python run.py --run-id {run_dir.name} --pick <번호> --scenes")
        print(f"그리려면:  python run.py --run-id {run_dir.name} --detail-pages")
        print(f"           (장면마다 따로·동시에 그리려면 --detail-pages --page <번호>)")
        return 0

    # **구체화·콘티·컷 대본 단계는 2026-09-13에 지웠다** — 운영 서버는 이미
    # 2026-09-02부터 이 단계들을 거치지 않고(`--detail-pages`,
    # `detailart.draw_continue`) 방향 후보만으로 바로 그린다. 이 자리(run_id
    # 는 있는데 --pick-save 등 단일 단계 플래그도 없이 도착한 경우)는 그 옛
    # 흐름의 마지막 자리였는데, 이제 갈 곳이 없다.
    raise SystemExit(
        "이 run 은 이미 이야기 후보가 있습니다. 다음 중 하나를 쓰세요:\n"
        f"  python run.py --run-id {run_dir.name} --pick <번호> --pick-save\n"
        f"  python run.py --run-id {run_dir.name} --detail-pages")


if __name__ == "__main__":
    raise SystemExit(main())
