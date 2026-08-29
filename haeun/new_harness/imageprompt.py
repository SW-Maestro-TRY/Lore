#!/usr/bin/env python3
"""페이지 하나 -> 이미지 생성 프롬프트 하나.

콘티(cuts.json)의 컷은 사람이 읽는 표기다:

    카메라: 상반신 / 정면 / 옆모습
    인물:
      - 이하은 / LD / 눈이 커진다 / 걸음을 멈춘다 / 상반신
    대사:
      - 이하은 (생각): "저기 불이 왜 켜져 있지."

이미지 모델에 보낼 때는 슬래시로 줄인 것을 도로 문장으로 편다. 모델이
"상반신 / 정면 / 옆모습" 을 셋으로 나눠 읽는다는 보장이 없고, 무엇보다
대사의 **종류**(말·생각·외침·나레이션…)가 곧 말풍선 모양이라 그 번역을
코드가 해 줘야 한다.

고정 블록은 prompt/image_prompt 에 있다. 여기서는 컷 데이터만 만든다.
"""

from __future__ import annotations

import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMPT_DIR = HERE / "prompt"

# 컷 높이 비율. full 은 숫자가 아니라 페이지 전체다.
HEIGHT_RATIO = {"tiny": 1, "small": 2, "normal": 3, "large": 5, "full": None}

# 대사 종류 -> 말풍선 지시. 고정 블록의 "말풍선 모양" 목록과 같은 표다.
BUBBLE = {
    "말": "둥근 타원",
    "생각": "구름 모양",
    "외침": "뾰족한 형태",
    "화면밖": "꼬리를 컷 바깥으로 향하게 하거나 꼬리를 없앤다",
    "전화": "각진 형태",
    "라디오": "각진 형태",
    "나레이션": "네모 상자. 컷 위쪽 모서리에 붙인다",
    "효과음": "말풍선 없이 글자만",
}
# 꼬리를 그릴 수 없는 것들 — 말하는 사람이 화면에 없거나 애초에 소리가 아니다.
NO_TAIL = ("화면밖", "나레이션", "효과음", "글")

# 인물 칸의 위치·순간. 값이 이것뿐이라 자리로 세지 않고 값으로 찾는다
# (person_line 참고).
POSITIONS = ("왼쪽", "가운데", "오른쪽")
MOMENTS = ("직전", "도중", "직후")

# `이하은 (생각): "..."` · `(나레이션): "..."` · `(글 / 노트북 화면): "..."`
LINE_RE = re.compile(
    r"^\s*(?P<who>[^(]*?)\s*\(\s*(?P<kind>[^)]*?)\s*\)\s*[:：]\s*(?P<line>.*)$")


def _parts(text: str) -> list[str]:
    return [p.strip() for p in str(text or "").split("/") if p.strip()]


def _eul(word: str) -> str:
    """받침에 맞는 목적격 조사. `직전을` · `직후를`.

    조사를 하나로 박으면 프롬프트에 "직후을" 이 나간다. 사람은 읽어 넘기지만
    이 문장은 이미지 모델이 읽는 문장이고, 어색한 한국어는 그만큼 덜 또렷한
    지시다.
    """
    last = (word or "")[-1:]
    if not last or not ("가" <= last <= "힣"):
        return "를"
    return "을" if (ord(last) - 0xAC00) % 28 else "를"


def camera_line(text: str) -> str:
    """`상반신 / 정면 / 옆모습` -> `상반신, 정면 앵글, 인물은 옆모습`."""
    shot, angle, facing = (_parts(text) + ["", "", ""])[:3]
    out = [p for p in (shot, f"{angle} 앵글" if angle else "",
                       f"인물은 {facing}" if facing else "") if p]
    return ", ".join(out)


def person_line(text: str) -> str:
    """`이름 / 그림체 / 위치 / 표정 / 행동 / 순간 / 범위` -> 한 문장.

    **위치와 순간은 자리로 세지 않고 값으로 찾는다.** 콘티 프롬프트가 "인물이
    한 명뿐이면 위치를 적지 않아도 된다" 고 허용해서, 같은 형식이 여섯 칸으로도
    일곱 칸으로도 온다. 자리로 세면 위치를 뺀 줄에서 표정이 위치로 읽힌다.
    둘 다 값이 세 개뿐이라(POSITIONS·MOMENTS) 값으로 찾는 쪽이 확실하다.

    칸이 모자라면 있는 것까지만 쓴다 — 모델이 낸 것을 읽는 자리라, 칸을 안
    채웠다고 그 인물을 통째로 버리는 쪽이 더 나쁘다.
    """
    where = moment = ""
    rest = []
    for i, part in enumerate(_parts(text)):
        # 앞의 두 칸(이름·그림체)은 건드리지 않는다 — 이름이 "오른쪽" 일 수도 있다.
        if i >= 2 and not where and part in POSITIONS:
            where = part
        elif i >= 2 and not moment and part in MOMENTS:
            moment = part
        else:
            rest.append(part)

    name, style, face, act, frame = (rest + [""] * 5)[:5]
    head = f"{name} ({style})" if style else name
    body = ", ".join(p for p in (f"화면 {where}" if where else "", face, act) if p)
    out = f"{head}: {body}" if body else head
    if moment:
        out += f". 동작의 {moment}{_eul(moment)} 그린다"
    if frame:
        out += f". 화면에는 {frame}까지 나온다"
    return out + "."


def bubble_line(text: str) -> list[str]:
    """`이하은 (생각): "…"` -> [지시 줄, 대사 줄].

    형식이 안 맞으면 원문을 그대로 한 줄로 남긴다. 대사를 지우는 것보다
    모양이 안 붙은 채로 나가는 편이 낫다 — 글자는 바꾸면 안 되는 값이다.
    """
    m = LINE_RE.match(str(text or ""))
    if not m:
        return [f"  - {str(text or '').strip()}"]

    who = m.group("who").strip()
    kinds = _parts(m.group("kind")) or [m.group("kind").strip()]
    kind, where = kinds[0], " / ".join(kinds[1:])
    line = m.group("line").strip()

    if kind == "글":
        spot = where or "화면이나 종이"
        head = f"말풍선 아님 — {spot}에 적힌 글로 그린다"
    else:
        shape = BUBBLE.get(kind, "둥근 타원")
        head = shape
        if kind not in NO_TAIL:
            head += f" / 꼬리는 {who or '말하는 인물'}을 향함"
        elif where:
            head += f" / {where}"
    return [f"  - {head}", f"    {line}"]


def cut_size(cut) -> str:
    """pages.cut_size 와 같은 규칙 — 여기서 다시 부르지 않게 얇게 둔다."""
    import pages
    return pages.cut_size(cut)


def cut_block(cut, number: int, with_place: bool = False) -> str:
    """컷 하나 -> 프롬프트 조각.

    with_place 면 장소·시간대를 이 컷에 붙인다. 페이지 전체가 한 장소일 때는
    앞에서 한 번만 적으므로(place_block) 여기서는 끈다.
    """
    size = cut_size(cut)
    ratio = HEIGHT_RATIO.get(size)
    head = f"### 컷 {number} (높이 비율 {ratio})" if ratio else f"### 컷 {number} (페이지 전체)"
    lines = [head]

    if with_place:
        for key in ("장소", "시간대"):
            value = str(cut.get(key) or "").strip()
            if value:
                lines.append(f"{key}: {value}")

    camera = camera_line(cut.get("카메라") or cut.get("camera") or "")
    if camera:
        lines.append(f"카메라: {camera}")

    background = str(cut.get("배경") or cut.get("background") or "").strip()
    if background:
        lines.append(f"배경: {background}")

    people = cut.get("인물") or cut.get("people") or []
    if people:
        lines.append("인물:")
        lines += [f"  - {person_line(p)}" for p in people]

    speech = cut.get("대사") or cut.get("speech") or []
    if speech:
        lines.append("말풍선:")
        for one in speech:
            lines += bubble_line(one)

    note = str(cut.get("지시") or cut.get("note") or "").strip()
    if note:
        lines.append(f"지시: {note}")

    never = str(cut.get("그리지 않을 것") or cut.get("never") or "").strip()
    if never:
        lines.append(f"그리지 않을 것: {never}")
    return "\n".join(lines)


def sheet_line(spec: dict) -> str:
    """sheet_spec.json -> 캐릭터 시트 한 사람 몫."""
    parts = [f"{spec.get('name') or '이름 없음'} — {spec.get('appearance_en') or ''}".strip()]
    details = spec.get("design_details") or []
    if details:
        parts.append("       고정 요소: " + " / ".join(details))
    props = spec.get("props") or []
    if props:
        parts.append("       소지품: " + " / ".join(props))
    return "\n".join(parts)


def load_fixed_block() -> str:
    path = PROMPT_DIR / "image_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit(f"프롬프트가 비어 있습니다: {path}")
    return text


def place_block(page) -> str:
    """페이지 전체가 한 장소면 그것을 앞에 한 번 적는다. 아니면 빈 문자열.

    페이지마다 따로 호출하므로, 장소를 안 적으면 같은 홀이 페이지마다 다른
    홀로 그려진다. 한 페이지 안에서 장소가 갈리면 여기서 뭉뚱그리지 않고
    컷마다 적는다(cut_block) — 틀린 하나를 앞에 크게 박는 것이 제일 나쁘다.
    """
    places = {str(c.get("장소") or "").strip() for c in (page or [])}
    times = {str(c.get("시간대") or "").strip() for c in (page or [])}
    if len(places) != 1 or not places.pop():
        return ""
    lines = ["## 장소", str(page[0].get("장소")).strip()]
    if len(times) == 1:
        one = times.pop()
        if one:
            lines.append(f"시간대: {one}")
    return "\n".join(lines)


def build_page_prompt(page, sheets=None, start_number: int = 1) -> str:
    """페이지(컷 배열) 하나 -> 호출 한 번에 보낼 프롬프트.

    컷 번호는 **페이지 안에서 1부터** 센다. 화면에 그려 넣는 번호라 페이지
    안에서 겹치지 않는 것이 전부고, 콘티의 원래 번호를 그대로 쓰면 장면이
    다른 컷이 한 페이지에 모였을 때 "컷 1" 이 두 개가 된다. 화 전체로 이어
    세고 싶으면 start_number 를 넘긴다.
    """
    blocks = [load_fixed_block()]

    sheets = [s for s in (sheets or []) if str(s or "").strip()]
    if sheets:
        blocks.append("## 캐릭터 시트\n" + "\n".join(sheets))

    place = place_block(page)
    shared = bool(place)
    if place:
        blocks.append(place)

    for i, cut in enumerate(page or []):
        blocks.append(cut_block(cut, start_number + i, with_place=not shared))
    return "\n\n".join(blocks) + "\n"


def page_prompts(pages_, sheets=None, continuous: bool = False) -> list[str]:
    """페이지 배열 -> 프롬프트 배열. continuous 면 컷 번호를 화 전체로 이어 센다."""
    out, n = [], 1
    for page in pages_ or []:
        out.append(build_page_prompt(page, sheets, start_number=n if continuous else 1))
        n += len(page)
    return out
