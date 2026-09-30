"""세계별 「재미 재료」 로어북 (#502).

`worlds.json` 은 세계를 한 문단으로 넓게 보여 준다. 그것만 주면 마법학교를
골라도 벌어지는 일이 서류 정리·야근·졸업 프로젝트가 된다 — 무대만 그 세계이고
사건은 어느 세계에서나 가능한 것이 된다(2026-09-30, work/genre-translation 실측).

여기는 반대로 **그 세계에서만 가능한 사건이 시작되는 자리**(제도·장소·존재·
물건·관행)를 하나씩 둔 목록이다(`lorebook.json`). 쓰는 자리는 셋이다.

  이야기 단계  방향마다 서로 다른 재료를 배정한다(축과 같은 방식). 넷이 같은
              감정선으로 모이는 것을 막는 장치다. 배정은 `lore.json` 에 남긴다.
  장면 단계    고른 방향에 배정된 재료 + 본문에 keys 가 등장하는 재료.
  그림 단계    그 장면 글에 keys 가 등장하는 재료만. 그림에 보이는 것을 맞추는 용도.

구조는 SillyTavern 의 월드인포(키워드로 켜지는 엔트리)를 따랐다. 코드는 새로 썼다.

**세계 키로 격리된다.** 판타지 재료가 아이돌 이야기에 새지 않는다 — 한 장르 소재가
다른 장르 생성에 새는 것을 막으려고 프롬프트에 구체적 예시를 안 넣는 원칙과 같은
이유에서, 재료는 예시가 아니라 그 세계의 사실로만 적는다.

끄는 법: `.env` 에 `NH_STORY_LORE=0`. 기본은 켜짐이고 기본값은 코드에 있다
(`.env` 는 jar 에 안 실린다).
"""

from __future__ import annotations

import json
import random
from pathlib import Path

import llm

HERE = Path(__file__).resolve().parent
BOOK = HERE / "lorebook.json"
RECORD = "lore.json"            # run 폴더에 남는 배정 기록

# 방향(후보) 하나에 배정하는 재료 수. 둘이면 재료끼리 부딪히는 사건이 생기고,
# 셋부터는 재료 나열이 된다.
PER_DIRECTION = 2
# 장면·그림 단계에서 한 번에 붙이는 재료 상한. 많이 붙이면 설정집이 된다.
SCENE_LIMIT = 4
PAGE_LIMIT = 3
# 최근 몇 run 의 배정을 피하는가(samples.AVOID_RECENT 와 같은 취지).
AVOID_RECENT = 5

# 장르 문자열(자유 텍스트, 예: "헌터·게이트") -> 세계 키. 여러 개 걸리면 첫 번째다.
# **순서가 곧 우선순위다.** 합성 장르명("로맨스 판타지")이 넓은 쪽("판타지")에
# 먼저 걸리면 엉뚱한 세계가 붙으므로 좁은 쪽을 위에 둔다(samples.guess_genre 의
# 표와 같은 이유·같은 순서). run.world_text_for 도 이 표를 쓴다.
WORLD_KEYWORDS = {
    "romance_novel": ("로맨스 판타지", "로판", "빙의", "회귀", "영애"),
    "hunter_gate": ("헌터", "게이트"),
    # "학원" 은 뺐다 — "학원 로맨스" 가 마법학교 세계로 가던 이유다(#524).
    "academy_magic": ("마법학교", "마법"),
    "idol_agency": ("아이돌", "연습생"),
    "sentinel_center": ("센티넬", "가이드버스"),
    "omegaverse_grade": ("오메가버스", "옴버"),
    "hero_city": ("히어로", "능력자", "빌런"),
    "post_disaster": ("재난", "좀비", "아포칼립스"),
    "thriller_record": ("스릴러", "서스펜스"),
    "action_contract": ("액션", "격투"),
    # 현대 로맨스(#524). 로판은 맨 위 romance_novel 이 먼저 가져가고, "아이돌 로맨스"
    # 처럼 다른 세계와 겹치면 그 세계가 먼저다 — 그래서 넓은 판타지 바로 위에 둔다.
    "romance_modern": ("로맨스", "연애", "사내연애", "캠퍼스", "오피스", "학원물", "청춘"),
    # 무협·게임 판타지(#524). 예전에는 무협은 세계가 없었고, 게임 판타지는
    # "판타지" 낱말 때문에 검과 마법 대륙이 붙었다. 넓은 판타지보다 위에 둔다.
    "martial_jianghu": ("무협", "강호", "무림", "문파"),
    "game_system": ("게임 판타지", "게임판타지", "상태창", "랭커", "가상현실"),
    # 넓은 쪽은 맨 아래 — 위에서 아무것도 안 걸렸을 때만 쓴다
    "fantasy_continent": ("판타지",),
}


def enabled() -> bool:
    return str(llm.env("NH_STORY_LORE") or "1").strip().lower() in ("1", "on", "true", "yes")


def load() -> dict:
    """{세계 키: [엔트리]}. 파일이 없거나 깨졌으면 {} — 재료 없이 예전처럼 돈다."""
    try:
        doc = json.loads(BOOK.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    out = {}
    for world, entries in (doc.get("worlds") or {}).items():
        good = [e for e in entries or []
                if isinstance(e, dict) and e.get("id") and e.get("name") and e.get("text")]
        if good:
            out[world] = good
    return out


def world_key_for(genre: str, card: dict | None = None) -> str:
    """세계 키. 고른 카드의 세계가 있으면 그것이 먼저다 — 카드가 곧 사용자가 고른
    세계라서다(charcard 참고). 없으면 장르 문자열에서 찾는다. 못 찾으면 ''."""
    world = str((card or {}).get("world") or "").strip()
    if world and world in WORLD_KEYWORDS:
        return world
    genre = (genre or "").strip()
    if not genre:
        return ""
    for key, keywords in WORLD_KEYWORDS.items():
        if any(kw in genre for kw in keywords):
            return key
    return ""


def entries_for(world: str) -> list[dict]:
    return list(load().get(world) or [])


def by_id(world: str, ids) -> list[dict]:
    table = {e["id"]: e for e in entries_for(world)}
    return [table[i] for i in ids or [] if i in table]


# ---------------------------------------------------------------- 이야기 단계

def recent_ids(world: str, runs_dir, limit: int = AVOID_RECENT) -> set:
    """최근 run 들이 이 세계에서 쓴 재료 id. 읽다 실패하면 조용히 건너뛴다 —
    회피는 있으면 좋은 것이지 이것 때문에 생성이 멈추면 안 된다."""
    root = Path(runs_dir) if runs_dir else None
    if not root or not root.is_dir():
        return set()
    out: set = set()
    count = 0
    for d in sorted((p for p in root.iterdir() if p.is_dir()),
                    key=lambda p: p.name, reverse=True):
        if count >= limit:
            break
        try:
            data = json.loads((d / RECORD).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if data.get("world") != world:
            continue
        for ids in data.get("directions") or []:
            out.update(ids or [])
        count += 1
    return out


def assign(world: str, n: int = 4, per: int = PER_DIRECTION, fill: int | None = None,
           avoid=None, rng=None, fixed: bool = False) -> list[list[dict]]:
    """방향 n개 중 fill개에 재료를 per개씩. 나머지 방향은 [] (재료 없이 간다).

    fill 이 None 이면 n개 전부다. **어느 방향이 재료를 받는지는 무작위다** —
    앞 두 개로 고정하면 사람이 보는 후보 순서에서 규칙이 읽힌다.
    방향끼리 재료가 겹치지 않게, 최근에 쓴 것은 뒤로 미룬다. 재료가 모자라면
    (세계당 8개 이상 두라고 한 이유) 있는 만큼만 채운다. 하나도 없으면 [].
    """
    pool = entries_for(world)
    if not pool:
        return []
    rng = rng or random.Random()
    fill = n if fill is None else max(0, min(fill, n))
    avoid = set(avoid or ())
    fresh = [e for e in pool if e["id"] not in avoid]
    stale = [e for e in pool if e["id"] in avoid]
    rng.shuffle(fresh)
    rng.shuffle(stale)
    ordered = fresh + stale
    # fixed 면 앞에서부터(1·2). 보여 줄 순서는 run.shuffle_directions 가 섞는다.
    slots = (list(range(fill)) if fixed else sorted(rng.sample(range(n), fill))) if fill else []
    out: list[list[dict]] = [[] for _ in range(n)]
    cursor = 0
    for i in slots:
        picked = ordered[cursor:cursor + per]
        cursor += per
        if not picked:
            break
        out[i] = picked
    return out if any(out) else []


def record(run_dir: Path, world: str, assigned: list[list[dict]]) -> None:
    (Path(run_dir) / RECORD).write_text(json.dumps(
        {"world": world, "directions": [[e["id"] for e in one] for one in assigned]},
        ensure_ascii=False, indent=2), encoding="utf-8")


def read_record(run_dir: Path) -> dict:
    try:
        return json.loads((Path(run_dir) / RECORD).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def reuse(run_dir: Path, world: str) -> list[list[dict]]:
    """run 폴더에 이미 lore.json 이 있고 같은 세계면 그 배정을 그대로 쓴다.

    프롬프트만 바꾸고 같은 재료로 다시 돌려 견주는 실험용이다(다른 run 의
    lore.json 을 새 run 폴더에 복사해 두고 --run-id 로 돌린다). 없으면 []."""
    data = read_record(run_dir)
    if not world or data.get("world") != world:
        return []
    lists = data.get("directions") or []
    return [by_id(world, ids) for ids in lists] if any(lists) else []


def for_direction(run_dir: Path, n: int) -> tuple[str, list[dict]]:
    """(세계 키, 방향 n 에 배정됐던 재료). 기록이 없으면 ('', [])."""
    data = read_record(run_dir)
    world = str(data.get("world") or "")
    lists = data.get("directions") or []
    ids = lists[n - 1] if world and 0 < n <= len(lists) else []
    return world, by_id(world, ids)


STORY_HEAD = (
    "「이 세계의 재료」는 그 세계에만 있는 제도·장소·존재다. **넣어야 하는 항목이 아니라 "
    "이 세계에서 쓸 수 있는 것이다.** 사용자가 적은 설명이 이미 판을 정했으면 그 판이 "
    "먼저다 — 재료는 그 판 위에서 주인공이 마주치는 것이어야 하고, 판을 밀어내고 "
    "자기가 주인공이 되면 안 된다.\n"
    "재료가 등장하려면 **이야기 안에 이유가 있어야 한다.** 주인공이 왜 거기로 가게 "
    "되는지, 누가 그 말을 하는지가 앞 문장에서 나와야 한다. 재료가 나오는 문장을 "
    "빼도 앞뒤가 멀쩡히 이어지면 끼워 넣은 것이다. 재료에 적힌 문장을 그대로 옮기지 "
    "말고, 이 인물이 그 규칙에 걸리는 순간으로 써라.\n"
    "방향마다 재료가 둘 있지만 **하나만 써도 된다.** 둘을 쓰면 하나가 다른 하나로 "
    "가는 이유가 되게 해라 — 한 사건에서 다음 사건으로 이어지는 길이지, 나란히 "
    "놓인 두 장면이 아니다. 방향 N 의 재료는 N 번 것만 쓴다.\n"
    "재료의 이름을 지우고 읽었을 때 어느 세계에서나 가능한 이야기면 재료를 안 쓴 "
    "것이다. 재료는 판과 처지에 건다 — 주인공의 성격으로 옮기지 않는다.\n"
    "재료가 안 적힌 방향은 재료 없이 쓴다 — 사용자가 적은 것만으로 판을 세운다. "
    "다른 방향의 재료를 가져오지 않는다.\n"
    "**사용자가 적은 것이 재료보다 먼저다.** 설명·카드에 적힌 자리·시점·종·세계·처지·"
    "오늘 벌어진 일과 안 맞는 재료는 버린다. 재료가 그것을 밀어내고 1화의 사건이 되면 "
    "사용자가 고른 캐릭터의 이야기가 아니다.\n"
    "넷은 서로 다른 재미여야 한다 — 걸린 것과 뒤집히는 방식이 다르면 재료에 닿는 "
    "길도 저절로 갈린다. 재료는 그 뒤집힘의 재료가 되어야지, 지나가는 구경거리로 "
    "쓰이면 안 된다."
)


def story_lines(entries: list[dict]) -> list[str]:
    """방향 하나에 붙는 줄들. story_variety_block 의 '### 방향 N' 아래에 들어간다."""
    return [f"[이 세계의 재료] {e['name']} — {e['text']}" for e in entries]


# ------------------------------------------------------------ 장면·그림 단계

def scan(world: str, text: str, exclude=(), limit: int = SCENE_LIMIT) -> list[dict]:
    """글에 keys 가 등장하는 재료. 한글이라 부분 문자열로 본다. 먼저 등장한 순서."""
    text = text or ""
    if not world or not text:
        return []
    exclude = set(exclude or ())
    hits = []
    for e in entries_for(world):
        if e["id"] in exclude:
            continue
        keys = [k for k in (e.get("keys") or []) if k]
        pos = min((text.find(k) for k in keys if k in text), default=-1)
        if pos >= 0:
            hits.append((pos, e))
    hits.sort(key=lambda x: x[0])
    return [e for _, e in hits[:limit]]


def scene_entries(run_dir: Path, n: int, body: str) -> list[dict]:
    """장면 단계에 붙일 재료 — 고른 방향에 배정된 것 + 본문에 등장하는 것."""
    world, picked = for_direction(run_dir, n)
    if not world or not picked:
        # 재료 없이 간 방향(사용자가 적은 것만으로 판을 세운 방향)에는 본문에
        # 낱말이 걸려도 안 붙인다 — 이야기 단계에서 안 준 것을 뒤에서 주면
        # 그 방향은 재료 없이 간 것이 아니게 된다.
        return []
    more = scan(world, body, exclude=[e["id"] for e in picked],
                limit=max(0, SCENE_LIMIT - len(picked)))
    return picked + more


def scene_block(entries: list[dict]) -> list[str]:
    if not entries:
        return []
    lines = ["", "## 이 세계의 재료 — 이 이야기가 서 있는 자리", "",
             "고른 이야기는 아래 재료 위에서 벌어진다. 장면을 나눌 때 재료에 적힌 규칙과 "
             "틈이 실제 장소·물건·인물의 행동으로 화면에 보이게 한다. 재료를 설명하는 "
             "장면을 따로 만들지 않는다 — 규칙은 누군가 그것에 걸리는 순간에 드러난다."]
    lines += [f"- {e['name']} — {e['text']}" for e in entries]
    return lines


def page_world(run_dir: Path, n: int) -> str:
    """그림 단계가 재료를 붙일 세계 키. 고른 방향이 재료 없이 간 방향이면 ''."""
    world, picked = for_direction(run_dir, n)
    return world if picked else ""


def page_entries(world: str, text: str, limit: int = PAGE_LIMIT) -> list[dict]:
    return scan(world, text, limit=limit)


def page_block(entries: list[dict]) -> str:
    """그림 단계 — 이 장면 글에 등장한 재료만. 그림에 보이는 것을 맞추는 용도라 짧다."""
    if not entries:
        return ""
    lines = ["[이 세계의 설정 — 장면에 나오는 것] 아래는 이 세계에서 그것이 무엇인지다. "
             "장면 글에 적힌 대로 그리되, 그 물건·장소·사람이 이 설명과 어긋나게 보이지 않게 한다."]
    lines += [f"- {e['name']}: {e['text']}" for e in entries]
    return "\n".join(lines)
