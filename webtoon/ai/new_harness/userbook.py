"""작가가 붙이고 확인한 설정집. 빼면 어느 단계에도 남기지 않는다.

orv-experiment 의 2층 로어북과 같은 방식으로 넣는다(`world_block`).
- 항상 켜짐 항목(always, 세계 자체를 설명하는 항목) — 매번 전부.
- 키워드 항목 — 이번 글에 키워드가 많이 걸린 항목부터 BUDGET 자까지. 하나도 안 걸리면 안 넣는다.
- 진행 상태 — 고정 항목 id 를 key 로 가진 줄은 그 항목이 골라졌을 때 「→ 지금:」으로 붙고, 나머지는
  [진행 상태] 로 매번 전부 들어간다. 진행 상태의 글도 항목 고르기에 쓴다.
"""

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


def progress_parts(book):
    """(덮어쓰기 {고정 항목 id: 지금 상태}, 새 상태 목록)."""
    ids = {str(e.get("id")) for e in book.get("entries", []) if isinstance(e, dict) and e.get("id")}
    over, new = {}, []
    for p in book.get("progress") or []:
        if not isinstance(p, dict) or not str(p.get("text") or "").strip():
            continue
        key = str(p.get("key") or "")
        if key in ids:
            over[key] = str(p["text"])
        else:
            new.append(p)
    return over, new


def progress_text(rows):
    return "\n".join(f"- {p.get('title', '')}: {p.get('text', '')}" for p in rows)


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
        lines += ["항상 참고하는 설정 (이야기의 세계)"] + [f"- {e.get('title', '')}: {e.get('content', '')}" for e in rules]
    over, new = progress_parts(book)
    query = str(context or "")
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
