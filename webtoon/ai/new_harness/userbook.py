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
        return voice_now(books, want, exact[0]["look"])
    loose = [e for e in people if want == lore_short(title(e)) or (lore_short(want) and lore_short(want) == title(e))]
    return voice_now(books, title(loose[0]), loose[0]["look"]) if len(loose) == 1 else {}


SPEECH_RE = re.compile(r"말을\s*(하|또렷|분명|시작)|말하(기 시작|게 되|는 모습)|대답(했|한다|하는|을 했)|답하는|답했다|라고 말(했|하)|말했다|문장으로 말|털어놓|부탁했|되물")
MUTE_RE = re.compile(r"(?<![가-힣])말(을|은)?\s?(거의\s)?(하지\s?못|못\s?하|할\s?수\s?없)|울음|으르렁|말을 잃")
NEG_RE = re.compile(r"못|않|없|모른")


def voice_now(books, name: str, look: dict) -> dict:
    """설정집의 「말하는 방식」은 1화 전에 정한 것이다 — 원래 말을 못 하는 인물이면, 연재 진행 상태에서 그 사람이 주어로 말한 가장 최근
    기록을 「지금 상태」로 덧붙인다. 말 못 하던 좀비가 연재 후반에 말을 알아듣고 대답하는데 그림 · 검수는 「말은 거의 하지 못한다」를 받아
    그의 대사를 빼거나 오류로 잡았다(2026-11-20, 연재 47화). 진행 상태가 없거나 그런 기록이 없으면 그대로 돌려준다.
    한 마디(「~고」 「~며」 「~지만」 · 쉼표로 끊은 단위) 안에서 이름이 주어(이 · 가 · 은 · 는)이고 말하는 낱말이 있으며 부정(못 · 않 · 없)이 없을 때만 센다."""
    if not isinstance(look, dict) or not str(look.get("voice") or "").strip() or not MUTE_RE.search(str(look["voice"])):
        return look
    keys = {k for k in (name, lore_short(name)) if k and len(k) >= 2}
    subj = [re.compile(rf"(?<![가-힣]){re.escape(k)}(이|가|은|는)(?![가-힣])") for k in keys]
    best = None
    for b in books if isinstance(books, list) else []:
        rows = b.get("progress") if isinstance(b, dict) and isinstance(b.get("progress"), list) else []
        for q in rows:
            if not isinstance(q, dict) or q.get("kind") == EVENT:
                continue
            t = str(q.get("text") or "")
            hit = False
            for c in re.split(r"(?<=고)\s+|(?<=며)\s+|(?<=지만)\s+|,\s*|(?<=[.다])\s+", t):
                if NEG_RE.search(c):
                    continue
                for r in subj:
                    m, sp = r.search(c), None
                    if m:
                        sp = SPEECH_RE.search(c, m.end())
                    # 이름(주어)과 말하는 낱말 사이에 다른 주어가 끼면 그 사람의 말이다 — 「재훈은 세린이 대답하는 모습을 보았다」
                    if m and sp and not re.search(r"[가-힣]+(이|가|은|는)\s", c[m.end():sp.start()]):
                        hit = True
                        break
                if hit:
                    break
            if not hit:
                continue
            raw = q.get("ep")
            ep = raw if isinstance(raw, int) and not isinstance(raw, bool) else (int(raw) if isinstance(raw, str) and raw.strip().isdigit() else -1)
            if best is None or ep >= best[0]:
                best = (ep, t.strip())
    if not best:
        return look
    when = f"{best[0]}화 " if best[0] >= 0 else ""
    return {**look, "voice": f"{look['voice'].strip()} — 단 연재 중 바뀜, 지금 상태({when}진행 상태): {best[1][:180]} → 말하는 방식은 이 지금 상태를 따른다."}


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


# 변하지 않는 생김새(머리 · 얼굴 · 체형)를 적은 문장만 — 낱말 하나(「키」 「턱」)로 찾으면 「지키는」 「산 중턱」 「턱선의 핏자국」까지 지웠다(11/20 검증).
LOOK_RE = re.compile(
    r"(짧|긴|길|민|밀|묶|곱슬|생머리|흑발|금발|검은|갈색|은발|백발|붉은)[^.,]{0,6}머리|머리(카락|칼)?(를|는|가|이|은)?\s*(짧|길|밀|묶|곱슬|검|갈색|하얗|희)"
    r"|머리\s*(모양|색)|얼굴(형|선|의\s*(생김새|특징))|눈매|눈동자|홍채|눈썹|쌍꺼풀|체형|체격|몸집|거구|덩치"
    r"|(붉은|검은|푸른|파란|갈색|회색|금빛|노란)\s?눈(을|이|은|에|과)?(?![가-힣])|(?<![가-힣])키(는|가)\s|센티미터|피부(색|빛)|(하얀|흰|창백한|까무잡잡한|구릿빛)\s?피부|이목구비")


HURT_RE = re.compile(r"피(가|를|에|로|투성이|범벅|자국|딱지)?(?![가-힣])|핏|출혈|피멍|상처|부상|다친|다쳐|긁힌|긁혀|흘리|흉터|총상|찢어|찢긴|멍(?![가-힣])|멍이|붕대|베인|물린|화상|부러|골절")


def _sheet_hit(title, sheeted) -> bool:
    """제목이 이 사람인가 — 이름 그대로이거나 「이름의 …」 「이름 …」. 성을 뺀 이름도 같은 규칙(「도윤희」 「도윤시청」은 아니다)."""
    title = str(title or "").strip()
    keys = {k for n in sheeted for k in (n, n[1:] if len(n) == 3 else "") if len(k) >= 2}
    return any(title == k or title.startswith((k + "의", k + " ", k + "(", k + " —")) for k in keys)


# 진행 상태 「겉모습」 줄 안의 [시트와 다름: …] — 이야기 속 사건으로 시트의 평소 모습에서 오래 달라진 몸(눈 · 피부 · 머리 · 흉터 · 체형).
# 좀비였던 인물이 치료되며 흰자가 돌아오는데 시트는 1화의 검은 흰자라, 글이 없으면 그림이 화마다 왔다 갔다 했다(2026-11-21, 연재 31~47화).
# 시트를 다시 뽑지 않고 이 글로 관리한다 — 그림 · 검수는 시트의 같은 부위를 이 글로 바꿔 읽는다(같은 장을 새로 그려 글만으로 눈이 바뀌는 것 확인).
SHEET_DIFF_RE = re.compile(r"\[시트와 다름:\s*([^\]]+)\]")


def _title_is(title, key) -> bool:
    """진행 상태 줄 제목이 바로 이 이름의 것인가 — 「이름」 「이름의 …」 「이름 …」. 성을 빼서 늘리지 않는다(_sheet_hit 와 다름)."""
    t = re.sub(r"\s+", " ", str(title or "")).strip()
    return t == key or t.startswith((key + "의", key + " ", key + "(", key + " —"))


def look_now(lorebook, name) -> str:
    """이 인물의 가장 최근 [시트와 다름: …] 글과 그 화. 없으면 "" (예전 설정집은 아무것도 안 붙는다).
    person_look 과 같이 확정된 설정집만 · 여러 권이면 모든 권. 진행 상태 줄의 제목이 이 사람(「전재훈의 겉모습」)인 것만 본다."""
    if not isinstance(lorebook, dict) or not lorebook.get("confirmed") or not str(name or "").strip():
        return ""
    books = [b for b in lorebook.get("books") or [] if isinstance(b, dict) and b.get("confirmed", True)] or [lorebook]
    want = re.sub(r"\s+", "", str(name))
    # 성을 뺀 이름(도윤)은 그 이름을 가진 인물이 설정집에 하나일 때만 그 사람으로 본다 — 한도윤 · 김도윤이 같이 있으면 「도윤의 겉모습」은 누구 것인지 모른다.
    people = {re.sub(r"\s+", "", str(e.get("title") or "")) for b in books for e in b.get("entries") or []
              if isinstance(e, dict) and e.get("kind") == "person" and str(e.get("title") or "").strip()}
    full = [p for p in people if lore_short(p) == want]
    if want not in people and len(full) == 1:
        want = full[0]                                   # 「도윤」으로 불렀고 그런 인물이 하나 → 한도윤
    short = lore_short(want)
    # 「도윤의 겉모습」을 한도윤 것으로 보는 건 한도윤이 설정집 인물이고 「도윤」으로 줄여 부를 사람이 그 하나뿐일 때만
    keys = [want] + ([short] if short and want in people and [p for p in people if lore_short(p) == short] == [want]
                     and short not in people else [])
    best = None
    for b in books:
        for q in b.get("progress") or [] if isinstance(b.get("progress"), list) else []:
            # 겉모습 줄만 — 관계 · 앎 줄에 같은 표시가 있어도 시트 예외로 삼지 않는다
            if not isinstance(q, dict) or q.get("kind") != "겉모습" or not any(_title_is(q.get("title"), k) for k in keys if k):
                continue
            got = [m.group(1).strip() for m in SHEET_DIFF_RE.finditer(str(q.get("text") or "")) if m.group(1).strip()]
            if not got:
                continue
            raw = q.get("ep")
            ep = raw if isinstance(raw, int) and not isinstance(raw, bool) else (
                int(raw.strip()) if isinstance(raw, str) and re.fullmatch(r"[0-9]{1,6}", raw.strip()) else -1)
            if best is None or ep >= best[0]:
                best = (ep, " ".join(got))
    if not best:
        return ""
    return f"{best[1]} ({best[0]}화 진행 상태)" if best[0] >= 0 else best[1]


def drop_looks(text: str, removed=None) -> str:
    """머리 · 얼굴 · 체형 · 눈 색을 적은 **마디**만 뺀다 — 시트가 있는 인물은 그림이 시트를 따르므로 설정집 겉모습 글이 시트와 싸우지 않게.
    문장째 지우면 「짧게 민 머리에 턱선을 따라 마른 핏자국이 남아 있다」의 핏자국까지 사라져서, 쉼표 · 「~에」 · 「~고」 · 「~며」 마디로 나눠 본다.
    상처 · 피 · 옷 · 소지품 · 관계 · 위치는 남긴다. [시트와 다름: …] 은 이야기 속에서 실제로 달라진 몸이라 그대로 남긴다."""
    text = str(text or "")
    marks = [m.group(0) for m in SHEET_DIFF_RE.finditer(text)]
    if marks:
        rest = _drop_plain(SHEET_DIFF_RE.sub(" ", text), removed)
        return " ".join(marks + ([rest] if rest else [])).strip()
    return _drop_plain(text, removed)


def _drop_plain(text: str, removed=None) -> str:
    out = []
    for sent in re.split(r"(?<=[.다])\s+", str(text or "")):
        if not sent or not LOOK_RE.search(sent):
            out.append(sent)
            continue
        if HURT_RE.search(sent) and not any(LOOK_RE.search(c) and not HURT_RE.search(c) for c in re.split(r"(?<=[,에고며])\s+", sent)):
            # 상처 · 피가 든 마디는 겉모습 낱말이 같이 있어도 남긴다 — 시트와 부딪칠 위험보다 상처를 잃는 게 더 나쁘다(11/20 검증 3회차)
            out.append(sent)
            continue
        clauses = re.split(r"(?<=[,에고며])\s+", sent)
        keep = [c for c in clauses if not LOOK_RE.search(c) or HURT_RE.search(c)]
        if removed is not None:
            removed.extend(c for c in clauses if c not in keep)
        if keep:
            out.append(" ".join(keep))
    return " ".join(x for x in out if x).strip()


def block(char, context="", stage="story", continuation=False, everything=False, budget=BUDGET, sheeted=(), audit=None):
    """sheeted — 그림 단계(stage="image")에서 시트가 붙은 인물 이름. 그 인물의 고정 항목 · 진행 상태에서
    머리 · 얼굴 · 체형 문장을 뺀다. 원작 글 「짧게 민 머리」(설정집 고정 항목 + 진행 상태 「한도윤의 겉모습」)가
    모든 장 그림 프롬프트 끝에 붙어, 앞머리를 내린 시트를 이기고 연재 5~11화 도윤이 짧은 머리로 그려졌다(2026-11-20).
    비어 있으면(기본) 예전과 한 글자도 같다."""
    book = (char or {}).get("lorebook") or {}
    removed = audit["시트 때문에 지운 문구"] if audit is not None else None
    if sheeted and stage == "image" and book:
        book = dict(book)
        book["entries"] = [({**e, "content": drop_looks(e.get("content"), removed)} if isinstance(e, dict) and _sheet_hit(e.get("title"), sheeted) else e)
                           for e in book.get("entries") or []]
        book["progress"] = [({**q, "text": drop_looks(q.get("text"), removed), "was": [w for w in (drop_looks(x, removed) for x in q.get("was") or []) if w]}
                             if isinstance(q, dict) and _sheet_hit(q.get("title"), sheeted) else q)
                            for q in book.get("progress") or []]
    if not book or not book.get("confirmed"):
        return ""
    if isinstance(book.get("books"), list):
        share = max(0, budget // max(1, len(book["books"])))
        parts = [block({"lorebook": child}, context, stage, continuation, everything, share, sheeted, audit) for child in book["books"] if isinstance(child, dict)]
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
    if audit is not None:
        audit["선택 항목"].extend({"id": e.get("id"), "제목": e.get("title")} for e in rules + chosen)
    if chosen:
        lines.append("설정집 항목")
        for e in chosen:
            row = f"- {e.get('title', '')}: {e.get('content', '')}"
            if str(e.get("id") or "") in over:
                row += f"\n  → 지금: {over[str(e['id'])]}"
            lines.append(row)
    lines.append("설정은 인물의 선택과 사건의 결과에 활용한다. 정보 나열이나 작가만 아는 비밀 공개로 대체하지 않는다.")
    return "\n".join(lines)
