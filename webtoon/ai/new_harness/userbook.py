"""작가가 붙이고 확인한 설정집. 빼면 어느 단계에도 남기지 않는다."""

def block(char, context="", stage="story", continuation=False):
    book = (char or {}).get("lorebook") or {}
    if not book or not book.get("confirmed"):
        return ""
    if isinstance(book.get("books"), list):
        parts = [block({"lorebook": child}, context, stage, continuation) for child in book["books"] if isinstance(child, dict)]
        return "[함께 붙인 설정집 — 나열된 순서대로 참고한다. 설정이 충돌하면 뒤에 붙인 설정을 우선한다.]\n" + "\n\n".join(p for p in parts if p)
    lines = ["[작가가 확인한 설정집]", "이 설정집은 선택한 작품 세계다. 캐릭터 카드의 옛 세계보다 우선하며 인물의 외형은 유지한다."]
    if not continuation:
        lines += ["고정 설정:", str(book.get("fixed_settings") or "")]
        if book.get("progress_state"):
            lines += ["이번 이야기 시작 시점 (앞으로 쓸 줄거리를 이미 끝난 사건으로 취급하지 않는다):", book["progress_state"]]
    else:
        lines.append("현재 시점과 고정 설정은 아래의 이번 회차 확인 내용을 우선한다. 이전 사건을 다시 시작하지 않는다.")
    active = [e for e in book.get("entries", []) if isinstance(e, dict) and e.get("enabled", True)]
    context = str(context).casefold()
    chosen = [e for e in active if e.get("always") or any(str(k).casefold() in context for k in e.get("keys", []) if str(k).strip())]
    # 이야기 아이디어가 비어 있어도 선택한 세계를 활용할 출발점 두 개를 제공한다.
    if stage == "story" and not chosen:
        chosen = active[:2]
    for e in chosen:
        lines += [f"- {e.get('title', '')}: {e.get('content', '')}"]
    lines.append("설정은 인물의 선택과 사건의 결과에 활용한다. 정보 나열이나 작가만 아는 비밀 공개로 대체하지 않는다.")
    return "\n".join(lines)
