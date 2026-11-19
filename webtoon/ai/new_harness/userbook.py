"""작가가 붙이고 확인한 설정집. 빼면 어느 단계에도 남기지 않는다.

orv-experiment 의 2층 로어북과 같은 방식으로 넣는다(`world_block`).
- 항상 켜짐 항목(always, 세계 자체를 설명하는 항목) — 매번 전부.
- 키워드 항목 — 이번 글에 키워드가 많이 걸린 항목부터 BUDGET 자까지. 하나도 안 걸리면 안 넣는다.
- 진행 상태 — 고정 항목 id 를 key 로 가진 줄은 그 항목이 골라졌을 때 「→ 지금:」으로 붙고, 나머지는
  [진행 상태] 로 매번 전부 들어간다. 진행 상태의 글도 항목 고르기에 쓴다.
"""

import re


def lore_short(title) -> str:
    """lore_extract.short_name 과 같다(이 파일은 llm 을 부르는 lore_extract 를 import 하지 않는다)."""
    t = re.sub(r"\s+", "", str(title or ""))
    return t[1:] if len(t) == 3 and all("가" <= ch <= "힣" for ch in t) else ""


def person_look(lorebook, name) -> dict:
    """설정집에서 이 이름의 인물 항목이 가진 원작 생김새(lore_extract.extract_looks). 없으면 {}.

    block 과 같이 확정된 설정집 · 켜진 항목만 본다(여러 권이면 모든 권). 이름이 항목 이름과 그대로 같은 것을 먼저
    찾고, 없을 때만 성을 뺀 이름으로 찾는다 — 그때도 맞는 사람이 하나일 때만(성만 다른 두 사람을 섞지 않는다)."""
    want = re.sub(r"\s+", "", str(name or ""))
    if not want or not isinstance(lorebook, dict) or not lorebook.get("confirmed"):
        return {}
    books = [b for b in lorebook.get("books") or [] if isinstance(b, dict) and b.get("confirmed", True)] or [lorebook]
    people = [e for b in books for e in b.get("entries") or []
              if isinstance(e, dict) and e.get("enabled", True) and isinstance(e.get("look"), dict)]
    title = lambda e: re.sub(r"\s+", "", str(e.get("title") or ""))
    exact = [e for e in people if title(e) == want]
    if exact:
        return exact[0]["look"]
    loose = [e for e in people if want == lore_short(title(e)) or (lore_short(want) and lore_short(want) == title(e))]
    return loose[0]["look"] if len(loose) == 1 else {}


def look_lines(look: dict, quotes: int = 6) -> list[str]:
    """시트 · 장 그림 프롬프트에 붙이는 원작 근거 줄. 비어 있으면 빈 목록(옛 run 은 아무것도 안 붙는다)."""
    look = look or {}
    out = []
    qs = [q for q in look.get("quotes") or [] if str(q).strip()][:quotes]
    if qs:
        out.append("원작 묘사: " + " / ".join(f"「{q}」" for q in qs))
    if str(look.get("voice") or "").strip():
        out.append(f"말하는 방식: {look['voice'].strip()}")
    if str(look.get("weight") or "").strip():
        out.append(f"작품 속 비중: {look['weight'].strip()}")
    return out


# 실험의 lorebook_condition.budget_chars 와 같다.
BUDGET = 30000


def select(entries, query, budget=BUDGET):
    """실험의 select 그대로. 키워드가 많이 걸린 항목부터, 같으면 원래 순서, budget 자까지.
    항목 이름도 늘 키워드로 친다 — 이름이 나왔는데 키워드에 안 적었다고 안 읽히면 안 된다(2026-10-26)."""
    folded = str(query or "").casefold()
    ranked = []
    for i, e in enumerate(entries):
        keys = {str(k).strip() for k in [*(e.get("keys") or []), e.get("title") or ""] if str(k).strip()}
        hits = sum(k.casefold() in folded for k in keys)
        short = lore_short(e.get("title")) if e.get("kind") == "person" else ""
        if short and short not in keys and re.search(r"(?<![가-힣])" + re.escape(short), folded):
            # 성을 뺀 부름도 그 인물이다(한도윤 → 도윤) — 본문은 성 없이 부르는 일이 많다(2026-11-18).
            # 앞에 다른 글자가 붙은 「김도윤」은 그 인물이 아니다.
            hits += 1
        if hits:
            ranked.append((-hits, i, e))
    chosen, used = set(), 0
    for _, i, e in sorted(ranked, key=lambda r: (r[0], r[1])):
        size = len(str(e.get("content") or ""))
        if used + size <= budget:
            chosen.add(i)
            used += size
    return [e for i, e in enumerate(entries) if i in chosen]


def select_all(entries, budget=BUDGET):
    out, used = [], 0
    for e in entries:
        size = len(str(e.get("content") or ""))
        if used + size <= budget:
            out.append(e)
            used += size
    return out


EVENT = "사건"          # lore_extract.EVENT 와 같다 — 진행 상태 목록 안의 사건 기록 줄
RECENT = 3              # 사건 기록 — 최근 몇 화는 늘 넣는다
EVENT_BUDGET = 8000     # 그 전 화의 사건 기록은 이번 글에 걸린 것만 이만큼까지


def progress_parts(book):
    """(덮어쓰기 {고정 항목 id: 지금 상태}, 새 상태 목록). 사건 기록 줄은 뺀다(event_lines)."""
    ids = {str(e.get("id")) for e in book.get("entries", []) if isinstance(e, dict) and e.get("id")}
    over, new = {}, []
    for p in book.get("progress") or []:
        if not isinstance(p, dict) or not str(p.get("text") or "").strip() or p.get("kind") == EVENT:
            continue
        key = str(p.get("key") or "")
        if key in ids:
            over[key] = str(p["text"])
        else:
            new.append(p)
    return over, new


def progress_text(rows):
    def one(p):
        kind = f"[{p['kind']}] " if p.get("kind") else ""
        since = f" ({p['ep']}화)" if p.get("ep") not in (None, "") else ""
        was = "".join(f"\n  · {w}" for w in p.get("was") or [])
        return f"- {kind}{p.get('title', '')}: {p.get('text', '')}{since}{was}"
    return "\n".join(one(p) for p in rows)


def event_lines(book, query, entries):
    """사건 기록 — 최근 RECENT 화는 전부, 그 전 화는 이번 글에 나온 인물 · 장소 · 물건이 그 기록에도 나올 때만.

    진행 상태는 「지금」만 남겨서, 몇 화 전에 헤어진 인물이 돌아오면 그 사이 일을 찾을 곳이 없었다(2026-11-18).
    고르는 기준은 고정 설정 항목의 이름 · 키워드다 — 항목 고르기(select)와 같은 낱말 일치, 벡터 검색은 아니다."""
    rows = [p for p in book.get("progress") or [] if isinstance(p, dict) and p.get("kind") == EVENT
            and str(p.get("text") or "").strip()]
    if not rows:
        return []
    rows.sort(key=lambda p: int(p.get("ep") or 0))
    last = int(rows[-1].get("ep") or 0)
    # 최근 RECENT 「화」 — 기록이 빠진 화가 있어도 화 번호로 센다(마지막 기록 세 건이 아니다).
    recent = [p for p in rows if int(p.get("ep") or 0) > last - RECENT]
    older = [p for p in rows if int(p.get("ep") or 0) <= last - RECENT]
    hit = [e for e in select(entries, query, budget=10**9) if e.get("kind") != "world"]
    words = set()
    for e in hit:
        words |= {str(k).strip() for k in [*(e.get("keys") or []), e.get("title") or ""] if len(str(k).strip()) >= 2}
        if e.get("kind") == "person" and lore_short(e.get("title")):
            words.add(lore_short(e.get("title")))
    chosen, used = [], 0
    for p in reversed(older):                 # 가까운 화부터 예산까지
        if any(w in str(p["text"]) for w in words) and used + len(p["text"]) <= EVENT_BUDGET:
            chosen.append(p)
            used += len(p["text"])
    picked = sorted(chosen, key=lambda p: int(p.get("ep") or 0)) + recent
    return ["지난 사건 기록 (이미 일어난 일 — 다시 시작하지 않는다. 인물이 다시 나오면 그 사이의 일을 이어 받는다)"] + [
        f"- {p.get('ep')}화: " + str(p["text"]).replace("\n", "\n  ") for p in picked]


def block(char, context="", stage="story", continuation=False, everything=False, budget=BUDGET):
    book = (char or {}).get("lorebook") or {}
    if not book or not book.get("confirmed"):
        return ""
    if isinstance(book.get("books"), list):
        share = max(0, budget // max(1, len(book["books"])))
        parts = [block({"lorebook": child}, context, stage, continuation, everything, share) for child in book["books"] if isinstance(child, dict)]
        return "[함께 붙인 설정집 — 나열된 순서대로 참고한다. 설정이 충돌하면 뒤에 붙인 설정을 우선한다.]\n" + "\n\n".join(p for p in parts if p)
    lines = ["[작가가 확인한 설정집]", "이 설정집은 선택한 작품 세계다. 캐릭터 카드의 옛 세계보다 우선하며 인물의 외형은 유지한다."]
    if not continuation:
        if str(book.get("fixed_settings") or "").strip():
            lines += ["고정 설정:", str(book["fixed_settings"])]
        if book.get("progress_state"):
            lines += ["이번 이야기 시작 시점 (앞으로 쓸 줄거리를 이미 끝난 사건으로 취급하지 않는다):", book["progress_state"]]
    else:
        lines.append("현재 시점과 고정 설정은 아래의 이번 회차 확인 내용을 우선한다. 이전 사건을 다시 시작하지 않는다.")
    active = [e for e in book.get("entries", []) if isinstance(e, dict) and e.get("enabled", True)]
    rules = [e for e in active if e.get("always")]
    keyed = [e for e in active if not e.get("always")]
    if rules:
        over, _ = progress_parts(book)
        # 항상 켜짐 항목도 연재 중에 상태가 바뀐다(세계의 통행금지가 풀렸다 등). 예전에는 그 상태를 저장해 놓고
        # 다음 화에 안 보여 줬다 — 키워드 항목처럼 「→ 지금」으로 붙인다(2026-11-18).
        lines += ["항상 참고하는 설정 (이야기의 세계)"] + [
            f"- {e.get('title', '')}: {e.get('content', '')}"
            + (f"\n  → 지금: {over[str(e.get('id'))]}" if str(e.get("id") or "") in over else "") for e in rules]
    over, new = progress_parts(book)
    query = str(context or "")
    lines += event_lines(book, query + "\n" + progress_text(new), keyed)
    if new:
        lines += ["진행 상태 (연재하면서 바뀐 지금 상태)", progress_text(new)]
        query += "\n" + progress_text(new)
    # everything: 줄거리 없이 설정집만 붙인 1화 이야기 단계 — 설정집 전체가 요청이라 키워드를 안 보고
    # 원래 순서대로 예산까지 넣는다.
    chosen = select_all(keyed, budget) if everything else select(keyed, query, budget)
    if chosen:
        lines.append("설정집 항목")
        for e in chosen:
            row = f"- {e.get('title', '')}: {e.get('content', '')}"
            if str(e.get("id") or "") in over:
                row += f"\n  → 지금: {over[str(e['id'])]}"
            lines.append(row)
    lines.append("설정은 인물의 선택과 사건의 결과에 활용한다. 정보 나열이나 작가만 아는 비밀 공개로 대체하지 않는다.")
    return "\n".join(lines)
