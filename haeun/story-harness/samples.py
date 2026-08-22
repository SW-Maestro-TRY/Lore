"""기준 샘플 — 검수를 통과한 상업 카드 풀과 스토리 바이블.

samples/ 는 사람이 검수해서 실제로 서비스에 나간 카드 24장(장르 4종 × 6장)과
스토리 바이블 2편이다. P1 은 이 카드들의 공식을 따라 쓰고, P3 는 같은 장르의
샘플 intro 와 나란히 놓고 판정하며, --card-mix 는 이 카드들 속에 AI 생성분을
섞어서 사람이 골라낼 수 있는지 본다.

**샘플은 정답지가 아니라 기준선이다.** 베끼면 --card-mix 에서 바로 들킨다
(같은 소재가 두 장 보이면 사람은 그 둘을 먼저 의심한다).

카드 24장의 구성 규칙 (romance.ts 상단 주석에서):
  01~03 정통 — 그 장르를 기대하고 온 사람이 원하는 것
  04~06 반전 — 판을 뒤집어서 "어? 왜?"가 나오는 것
  "반전 카드가 뽑히는 게 이 세계관의 진짜 훅이다 — 절대 정통 카드로만 채우지 말 것."
"""

from __future__ import annotations

import json
import random
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SAMPLE_DIR = ROOT / "samples"
AXES_FILE = SAMPLE_DIR / "variation_axes.json"

# 장르 키 → (파일, 사람이 읽는 이름). --genre 값이 이 키다.
GENRES = {
    "romance": ("romance.ts", "로맨스 판타지 (빙의·회귀)"),
    "idol": ("idol.ts", "아이돌 (연습생·데뷔)"),
    "hunter": ("hunter.ts", "헌터 (각성·게이트)"),
    "academy": ("academy.ts", "마법학교 (입학·기숙사)"),
}

# 카드가 아니라 통째로 참고하는 완성 기획서. 프롬프트에는 요약만 들어간다.
BIBLES = ("로판.txt", "좀비아포칼립스.txt")

TONES = ("somber", "serene", "radiant", "intense")


class SampleError(RuntimeError):
    """샘플을 읽을 수 없음. 명령이 사람이 읽을 메시지로 바꿔 출력한다."""


# --------------------------------------------------------------- TS 읽기

_STR_RE = re.compile(r'"(?:[^"\\]|\\.)*"')
_KEY_RE = re.compile(r'([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:')
_TRAIL_RE = re.compile(r',(\s*[}\]])')


def _strip_ts(text: str) -> str:
    """TS 객체 리터럴 → JSON.

    문자열을 먼저 자리표시자로 빼 둔다. 안 그러면 키 따옴표 붙이는 정규식이
    한국어 대사 안의 콜론까지 건드린다.
    """
    literals: list[str] = []

    def stash(m):
        literals.append(m.group(0))
        return f'"\x00{len(literals) - 1}\x00"'

    text = _STR_RE.sub(stash, text)
    text = "\n".join(l for l in text.splitlines() if not l.strip().startswith("//"))
    text = _KEY_RE.sub(r'\1"\2":', text)
    text = _TRAIL_RE.sub(r'\1', text)
    return re.sub(r'"\x00(\d+)\x00"', lambda m: literals[int(m.group(1))], text)


def parse_cards(path: Path) -> list:
    """samples/*.ts 의 StoryCard 배열을 dict 목록으로."""
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise SampleError(f"샘플을 읽을 수 없습니다: {path} ({exc})") from exc

    # "StoryCard[]" 의 대괄호를 배열 시작으로 잡지 않도록 그 뒤에서 찾는다.
    marker = raw.find("StoryCard[]")
    start = raw.find("[", marker + len("StoryCard[]")) if marker >= 0 else -1
    end = raw.rfind("]")
    if start < 0 or end <= start:
        raise SampleError(f"{path.name} 에서 StoryCard 배열을 찾지 못했습니다.")

    try:
        cards = json.loads(_strip_ts(raw[start:end + 1]))
    except json.JSONDecodeError as exc:
        raise SampleError(f"{path.name} 을 JSON 으로 옮기지 못했습니다: {exc}") from exc
    if not isinstance(cards, list) or not cards:
        raise SampleError(f"{path.name} 에 카드가 없습니다.")
    return cards


def load(genre: str) -> list:
    """장르 키 → 샘플 카드 6장."""
    key = str(genre or "").strip().lower()
    if key not in GENRES:
        raise SampleError(
            f"모르는 장르 '{genre}' 입니다. 쓸 수 있는 것: {', '.join(sorted(GENRES))}")
    path = SAMPLE_DIR / GENRES[key][0]
    if not path.exists():
        raise SampleError(
            f"샘플 파일이 없습니다: {path}\n"
            f"  samples/ 폴더에 {GENRES[key][0]} 를 넣으세요.")
    return parse_cards(path)


def available() -> list:
    """지금 실제로 읽히는 장르만."""
    return sorted(k for k in GENRES if (SAMPLE_DIR / GENRES[k][0]).exists())


def genre_label(genre: str) -> str:
    return GENRES.get(str(genre or "").lower(), ("", str(genre)))[1]


# --------------------------------------------------------- 프롬프트용 블록

def _fmt_card(c: dict, index: int = 0) -> str:
    ap = c.get("appearance") or {}
    beats = c.get("fateBeats") or c.get("fate_beats") or []
    head = f"[샘플 {index}] " if index else "[샘플] "
    lines = [
        f"{head}{c.get('id', '')}",
        f"  intro: {c.get('intro', '')}",
        f"  name: {c.get('name', '')}",
        f"  rank: {c.get('rank', '')}",
        f"  personality: {c.get('personality', '')}",
        f"  quote: {c.get('quote', '')}",
        f"  appearance: {ap.get('hair', '')} / {ap.get('eyes', '')} / "
        f"{ap.get('impression', '')} / 속성 {ap.get('element', '')}",
    ]
    for i, b in enumerate(beats, 1):
        lines.append(f"  fateBeats {i}: {b}")
    return "\n".join(lines)


def exemplars(genre: str, limit: int = 6) -> str:
    """P1 프롬프트에 통째로 넣는 같은 장르 샘플 카드."""
    try:
        cards = load(genre)
    except SampleError:
        return "(이 장르의 샘플 카드가 없습니다. 아래 공식만으로 씁니다.)"
    return "\n\n".join(_fmt_card(c, i) for i, c in enumerate(cards[:limit], 1))


def exemplars_all(per_genre: int = 2) -> str:
    """장르를 못 고를 때 — 장르마다 정통 1장 + 반전 1장씩."""
    out = []
    for key in available():
        cards = load(key)
        picked = cards[:1] + cards[3:4]     # 01 정통 / 04 반전
        out.append(f"── {genre_label(key)}")
        out.extend(_fmt_card(c) for c in picked[:per_genre])
    return "\n\n".join(out) or "(샘플 카드가 없습니다. 아래 공식만으로 씁니다.)"


def intro_list(genre: str, limit: int = 6) -> str:
    """P3 가 나란히 놓고 볼 intro 목록."""
    try:
        cards = load(genre)
    except SampleError:
        return "(이 장르의 샘플 카드가 없습니다. 이 항목은 공식 구조만으로 판정합니다.)"
    return "\n".join(f"- {c.get('intro', '')}" for c in cards[:limit])


def all_intros(limit_per_genre: int = 6) -> str:
    """장르를 모를 때 쓰는 전 장르 intro 목록."""
    out = []
    for key in available():
        out.append(f"[{genre_label(key)}]")
        out.append(intro_list(key, limit_per_genre))
    return "\n".join(out) or "(샘플 카드가 없습니다.)"


# ------------------------------------------------------- 이야기 변수 축(축)
#
# 샘플 카드는 **수렴 장치**다. "이렇게 써라"라고 보여주는 것이라, 장르마다
# 전용 카드를 붙이면 장르 오염은 사라지지만 같은 장르 안에서는 오히려 더
# 비슷해진다. 다양성은 예시를 늘려서 나오지 않고 **입력이 매번 달라져야**
# 나온다 — 그 달라지는 입력이 이 축이다.
#
# 축은 소재가 아니라 자리다. 장르와 직교하기 때문에 12개 장르 × 7,000여 조합이
# 곱해진다. 샘플 카드를 몇 장 더 쓰는 것과 비교가 안 되는 폭이다.


def load_axes() -> dict:
    """variation_axes.json. 없거나 깨졌으면 빈 dict — 축 없이 그냥 진행한다."""
    try:
        data = json.loads(AXES_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def axis_names() -> list:
    """실제로 뽑을 축 이름. `_` 로 시작하는 메타 키는 뺀다."""
    return [k for k in load_axes() if not str(k).startswith("_")]


def _axis_values(table: dict, axis: str, genre: str) -> list:
    """한 축에서 이 장르가 쓸 수 있는 값 목록.

    _genre_overrides 에 걸리면 그만큼만, 아니면 전체. 못 걸러도 손해는
    "이상한 조합이 한 번 나오는 것"이라 값이 하나도 안 남는 쪽을 더 조심한다.
    """
    node = table.get(axis) or {}
    values = [v for v in (node.get("값") or []) if isinstance(v, dict) and v.get("이름")]
    allowed = ((table.get("_genre_overrides") or {}).get(genre) or {}).get(axis)
    if allowed:
        narrowed = [v for v in values if v["이름"] in allowed]
        if narrowed:
            return narrowed
    return values


def _override_key(table: dict, genre: str) -> str:
    """자유 입력 장르 문자열 → _genre_overrides 키. 못 찾으면 빈 문자열."""
    g = str(genre or "").strip()
    if not g:
        return ""
    keys = [k for k in (table.get("_genre_overrides") or {}) if not str(k).startswith("_")]
    if g in keys:
        return g
    return next((k for k in keys if k in g), "")


def pick_axes(genre: str = "", rng=None, seed=None) -> dict:
    """축마다 값 하나씩 뽑는다. {축이름: {"이름":…, "설명":…}}.

    seed 를 주면 같은 조합이 다시 나온다 — 테스트와 "이 run 을 그대로 재현"에
    쓴다. 안 주면 매 호출마다 달라지는 것이 이 함수의 목적이다.
    """
    table = load_axes()
    if not table:
        return {}
    r = rng or (random.Random(seed) if seed is not None else random)
    gkey = _override_key(table, genre)
    picked = {}
    for axis in axis_names():
        values = _axis_values(table, axis, gkey)
        if values:
            picked[axis] = r.choice(values)
    return picked


def axes_block(picked: dict) -> str:
    """뽑힌 조합을 P1 프롬프트에 넣을 문자열로."""
    if not picked:
        return ""
    lines = []
    for axis, value in picked.items():
        lines.append(f"- {axis.replace('_', ' ')}: **{value.get('이름', '')}**")
        desc = str(value.get("설명") or "").strip()
        if desc:
            lines.append(f"    {desc}")
    return "\n".join(lines)


def axes_summary(picked: dict) -> str:
    """로그 한 줄용. '최약체 · 균열의 순간 · 정보 격차 · 라이벌 · 냉소'"""
    return " · ".join(str(v.get("이름", "")) for v in (picked or {}).values())


def guess_genre(text: str) -> str:
    """자유 입력 장르 문자열 → 샘플 장르 키. 못 찾으면 빈 문자열.

    inputs.csv 의 장르 칸은 자유 문자열이다("로맨스 판타지", "헌터물", …).
    샘플을 붙이려면 어느 풀에 속하는지 골라야 하는데, 이건 코드가 단어로 민다 —
    틀려도 손해는 "예시가 덜 맞는 것"뿐이고, 못 고르면 전 장르를 다 보여준다.
    """
    t = str(text or "").lower()
    table = {
        "romance": ("로맨스", "로판", "romance", "빙의", "회귀", "영애", "귀족", "황실", "궁중"),
        "idol": ("아이돌", "idol", "연습생", "데뷔", "아이돌물", "아이돌판"),
        "hunter": ("헌터", "hunter", "각성", "게이트", "던전", "레이드", "길드"),
        "academy": ("마법학교", "academy", "아카데미", "학원", "기숙사", "마법사"),
    }
    for key, words in table.items():
        if any(w in t for w in words):
            return key
    return ""
