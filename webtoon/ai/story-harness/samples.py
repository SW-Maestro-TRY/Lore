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
STRUCTURE_FILE = SAMPLE_DIR / "story_structures.json"

# 장르 키 → (파일, 사람이 읽는 이름). --genre 값이 이 키다.
#
# 앞의 4종이 원본이고 나머지는 나중에 붙였다. UI 에서 고를 수 있는데 전용
# 샘플이 없는 장르가 있으면 exemplars_all() 로 폴백해서 **엉뚱한 장르 카드**를
# 보고 쓰게 된다 — "평범한 일상"을 골랐는데 각성·던전이 나오던 이유다.
GENRES = {
    "romance": ("romance.ts", "로맨스 판타지 (빙의·회귀)"),
    "idol": ("idol.ts", "아이돌 (연습생·데뷔)"),
    "hunter": ("hunter.ts", "헌터 (각성·게이트)"),
    "academy": ("academy.ts", "마법학교 (입학·기숙사)"),
    "gamefantasy": ("gamefantasy.ts", "게임 판타지 (시스템·랭커)"),
    "omegaverse": ("omegaverse.ts", "오메가버스 (제2의 성·제도)"),
    "sentinel": ("sentinel.ts", "센티넬 (감각·매칭)"),
    "martial": ("martial.ts", "무협 (강호·문파)"),
    "thriller": ("thriller.ts", "스릴러 (추적·서스펜스)"),
    "comedy": ("comedy.ts", "개그 (코미디)"),
    "action": ("action.ts", "액션 (격투·추격)"),
    "daily": ("daily.ts", "일상 (학교·회사·자취)"),
    "fantasy": ("fantasy.ts", "판타지 (검과 마법·이세계)"),
    "hero": ("hero.ts", "히어로 (능력·빌런)"),
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


# 한 번에 보여줄 샘플 장수. 6장을 매번 통째로 넣으면 그 6장이 곧 정답지가 되어
# 같은 장르 생성물이 서로 닮는다. 일부만, 매번 다르게 보여주는 편이 낫다 —
# 공식은 3장으로도 충분히 전달되고, 남은 3장은 다음 생성의 몫이다.
EXEMPLAR_PICK = 3


def _pick_cards(cards: list, pick: int, r) -> list:
    """정통 1장 + 반전 1장을 보장하고 나머지를 무작위로 채운다.

    반전(04~06)이 한 장도 안 뽑히면 그 장르는 정통만 있는 것처럼 보인다 —
    romance.ts 주석의 "반전 카드가 뽑히는 게 이 세계관의 진짜 훅"이 무너진다.
    """
    if pick <= 0 or pick >= len(cards):
        return list(cards)
    straight, twist = cards[:3], cards[3:]
    picked = []
    if straight:
        picked.append(r.choice(straight))
    if twist:
        picked.append(r.choice(twist))
    rest = [c for c in cards if c not in picked]
    r.shuffle(rest)
    picked.extend(rest[:max(0, pick - len(picked))])
    # 원래 순서대로 되돌린다. 정통이 먼저 보여야 반전이 반전으로 읽힌다.
    picked.sort(key=cards.index)
    return picked[:pick]


def exemplars(genre: str, limit: int = 6, pick: int = EXEMPLAR_PICK, rng=None) -> str:
    """P1 프롬프트에 넣는 같은 장르 샘플 카드.

    pick 장만 무작위로 고른다. pick=0 이면 예전처럼 limit 장을 순서대로 전부
    넣는다(--card-mix 처럼 카드 풀 전체가 필요한 곳에서 쓴다).
    """
    try:
        cards = load(genre)
    except SampleError:
        return "(이 장르의 샘플 카드가 없습니다. 아래 공식만으로 씁니다.)"
    chosen = _pick_cards(cards[:limit], pick, rng or random)
    return "\n\n".join(_fmt_card(c, i) for i, c in enumerate(chosen, 1))


def exemplars_all(per_genre: int = 2, genres: int = 3, rng=None) -> str:
    """장르를 못 고를 때 — 장르 몇 개를 골라 정통 1장 + 반전 1장씩.

    전용 샘플이 생기기 전에는 여기로 폴백하는 장르가 많았고, 그때는 장르 4종을
    전부 보여줬다. 이제 장르가 14종이라 다 보여주면 28장이 들어가서 프롬프트가
    샘플로 뒤덮인다 — 몇 개만, 매번 다르게 고른다.
    """
    r = rng or random
    keys = available()
    if genres and len(keys) > genres:
        keys = sorted(r.sample(keys, genres), key=available().index)
    out = []
    for key in keys:
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


# ------------------------------------------------------------- 회차 구조
#
# story_templates.json 의 '스토리 구조'는 3막(도입→전개→절정→결말) 하나뿐이라,
# 어떤 장르를 골라도 같은 리듬으로 나왔다 — 일상물인데도 사건이 터지고 반전이
# 생기고 다음 화 떡밥이 깔리던 이유다. 축과 같은 방식으로 매번 하나씩 고른다.
#
# 축이 "누가 어디에 서 있는가"라면 구조는 "어떤 순서로 보여주는가"다.


def load_structures() -> dict:
    """story_structures.json. 없거나 깨졌으면 빈 dict — 구조 없이 진행한다."""
    try:
        data = json.loads(STRUCTURE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def structure_names() -> list:
    return [k for k in load_structures() if not str(k).startswith("_")]


def pick_structure(genre: str = "", rng=None, seed=None) -> dict:
    """회차 구조와 반전 배치를 하나씩 뽑는다. {"구조": {...}, "반전_배치": {...}}."""
    table = load_structures()
    if not table:
        return {}
    r = rng or (random.Random(seed) if seed is not None else random)
    gkey = _override_key(table, genre)
    picked = {}
    for name in structure_names():
        values = _axis_values(table, name, gkey)
        if values:
            picked[name] = r.choice(values)
    return picked


def structure_block(picked: dict) -> str:
    """뽑힌 구조를 P2 프롬프트에 넣을 문자열로."""
    if not picked:
        return ""
    out = []
    for name, value in picked.items():
        out.append(f"[{name.replace('_', ' ')}] {value.get('이름', '')}")
        desc = str(value.get("설명") or "").strip()
        if desc:
            out.append(f"  {desc}")
        for step in (value.get("단계") or []):
            out.append(f"  → {step}")
        for label in ("배치", "끝내는 법"):
            if str(value.get(label) or "").strip():
                out.append(f"  {label}: {value[label]}")
        out.append("")
    return "\n".join(out).rstrip()


def structure_summary(picked: dict) -> str:
    """로그 한 줄용. '수수께끼 · 조기 공개'"""
    return " · ".join(str(v.get("이름", "")) for v in (picked or {}).values())


# --------------------------------------------------- 최근 것과 겹치지 않게
#
# 축과 구조를 무작위로 뽑으면 조합은 넓지만(7,560 × 50) **바로 직전과 같은 것이
# 나오는 일**은 여전히 생긴다. 한 번 겪으면 "또 이거네"가 되고, 다양성을
# 넓힌 보람이 그 자리에서 사라진다.
#
# 전부를 기억하지는 않는다. 최근 몇 개만 피한다 — 오래된 것까지 피하려 들면
# 뽑을 것이 없어지고, 결국 "안 겹치는 하나"로 다시 고정된다.

AVOID_RECENT = 5          # 최근 몇 번의 생성을 피할 것인가
_AVOID_TRIES = 12         # 다시 뽑는 횟수 상한. 넘으면 그냥 마지막 것을 쓴다


def _combo_key(axes: dict, structure: dict = None) -> str:
    """한 번의 생성을 한 줄로. 겹쳤는지 보는 기준이 이것이다."""
    parts = [str(v.get("이름", "")) for v in (axes or {}).values()]
    parts += [str(v.get("이름", "")) for v in (structure or {}).values()]
    return "|".join(parts)


def recent_combos(runs_dir, limit: int = AVOID_RECENT) -> list:
    """최근 run 들이 쓴 조합. 읽다 실패하면 조용히 건너뛴다 —
    회피는 있으면 좋은 것이지, 이것 때문에 생성이 멈추면 안 된다."""
    from pathlib import Path
    root = Path(runs_dir)
    if not root.is_dir():
        return []
    out = []
    for d in sorted((p for p in root.iterdir() if p.is_dir()),
                    key=lambda p: p.name, reverse=True):
        if len(out) >= limit:
            break
        try:
            data = json.loads((d / "axes.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        key = _combo_key(data.get("축") or {}, data.get("구조") or {})
        if key.strip("|"):
            out.append(key)
    return out


def pick_fresh(genre: str = "", runs_dir=None, rng=None, seed=None) -> tuple:
    """최근 것과 겹치지 않는 (축, 구조). runs_dir 이 없으면 그냥 한 번 뽑는다.

    완전히 같은 조합만 피한다. "축 하나라도 겹치면 다시"로 하면 값이 몇 개
    안 되는 축(톤 6개)이 금세 바닥나서, 피하려던 고정이 오히려 생긴다.
    """
    r = rng or (random.Random(seed) if seed is not None else random)
    seen = set(recent_combos(runs_dir)) if runs_dir else set()
    axes = structure = None
    for _ in range(_AVOID_TRIES if seen else 1):
        axes = pick_axes(genre, rng=r)
        structure = pick_structure(genre, rng=r)
        if _combo_key(axes, structure) not in seen:
            return axes, structure, True
    # 여기까지 왔으면 뽑을 수 있는 조합이 좁다는 뜻이다(장르 제약이 센 경우).
    # 겹치더라도 생성은 계속한다 — 멈추는 것이 더 나쁘다.
    return axes, structure, False


def guess_genre(text: str) -> str:
    """자유 입력 장르 문자열 → 샘플 장르 키. 못 찾으면 빈 문자열.

    inputs.csv 의 장르 칸은 자유 문자열이다("로맨스 판타지", "헌터물", …).
    샘플을 붙이려면 어느 풀에 속하는지 골라야 하는데, 이건 코드가 단어로 민다 —
    틀려도 손해는 "예시가 덜 맞는 것"뿐이고, 못 고르면 전 장르를 다 보여준다.
    """
    t = str(text or "").lower()
    # 순서가 곧 우선순위다. 합성 장르명("게임 판타지", "로맨스 판타지")이
    # 넓은 쪽('판타지')에 먼저 걸리면 안 되므로 좁은 쪽을 위에 둔다.
    # 앞의 4줄은 원래 있던 것 — 순서를 바꾸면 기존 매칭이 달라진다.
    table = {
        "romance": ("로맨스", "로판", "romance", "빙의", "회귀", "영애", "귀족", "황실", "궁중"),
        "idol": ("아이돌", "idol", "연습생", "데뷔", "아이돌물", "아이돌판"),
        "hunter": ("헌터", "hunter", "각성", "게이트", "던전", "레이드", "길드"),
        "academy": ("마법학교", "academy", "아카데미", "학원", "기숙사", "마법사"),
        "gamefantasy": ("게임 판타지", "게임판타지", "가상현실", "랭커", "시스템 창",
                        "히든 클래스", "로그아웃", "레이드물"),
        "omegaverse": ("오메가버스", "오메가 버스", "알파버스", "bl", "옴버"),
        "sentinel": ("센티넬", "가이드버스", "sentinel", "가이드 버스"),
        "martial": ("무협", "강호", "문파", "무공", "비급", "사문", "중원", "마교"),
        # '느와르' 는 일부러 뺐다 — "모르는 장르는 억지로 고르지 않는다"를
        # 지키는 회귀 테스트가 그 낱말을 예시로 쓰고 있다.
        "thriller": ("스릴러", "thriller", "미스터리", "서스펜스", "추리",
                     "좀비", "아포칼립스", "오컬트"),
        "comedy": ("개그", "코미디", "comedy", "유머", "병맛"),
        "action": ("액션", "action", "격투", "용병", "첩보", "경호", "청부"),
        "daily": ("일상", "힐링", "슬라이스", "daily", "생활물"),
        "fantasy": ("판타지", "fantasy", "이세계", "마법", "왕국", "용사", "마왕"),
        # 맨 아래에 둔다. 위로 올리면 '능력자'·'히어로'가 들어간 기존 문자열
        # ("각성 능력자" → hunter, "판타지 히어로" → fantasy)의 판정이 바뀐다.
        # 여기 두면 지금까지 아무 데도 안 걸리던 것만 새로 hero 로 온다.
        "hero": ("히어로", "빌런", "hero", "능력자", "슈퍼히어로", "자경단",
                 "초능력", "히어로물", "안티히어로"),
    }
    for key, words in table.items():
        if any(w in t for w in words):
            return key
    return ""
