"""글에서 2층 로어북을 뽑는다. 웹툰 생성이나 자동 확정은 하지 않는다.

orv-experiment(전독시×차시연 실험)와 같은 2층 구조다.
- 고정 로어북 = 대상 하나에 항목 하나(prompt/lore/book_prompt). 세계·조직이 돌아가는 방식은 그 세계·조직
  항목에, 인물의 비밀은 그 인물 항목에 넣는다 — 추상 규칙만 따로 떼지 않는다. 항목은 키워드가 걸릴 때만
  들어가고, 세계 자체를 설명하는 항목만 항상 켜짐(always)으로 매번 들어간다(userbook.block).
- 진행 상태 = 고정 로어북 위에 덮어쓰는 층(prompt/lore/progress_prompt). 화가 끝날 때마다 바뀐 것과
  지울 것만 받아 합친다. key 가 고정 항목 id 면 그 항목의 「→ 지금:」 덮어쓰기, 그 밖은 새 상태다.

만들기 전에 적은 글(main)은 진행 상태를 빈 목록으로 시작한다 — 실험의 텍본 조건처럼, 아직 연재가
시작되지 않았으니 덮어쓸 것이 없다. 끝난 회차 정리는 continuation.stage_digest 가 같은 함수로 한다.
"""
import argparse
import json
import re
from pathlib import Path

import llm
import run as R
import lang as lang_mod


def _prompt(name: str, lang: str) -> str:
    return R.load_prompt(name).replace("{{LANGUAGE_LINE}}", lang_mod.instruction(lang)) + "\n"


def _json(text: str) -> dict:
    t = text[text.find("{"):text.rfind("}") + 1]
    return json.loads(t) if t else {}


def _call(prompt: str, out_dir: Path, stage: str) -> dict:
    call = llm.Call("STORY")
    try:
        text, meta = call(prompt)
    except BaseException as exc:
        R.record_error(out_dir, stage, call.provider, call.model, exc)
        raise
    meta["stage"] = stage
    R.record(out_dir, meta)
    R.write_text(out_dir / f"{stage.lower()}.txt", text)
    return _json(text)


def known_block(known) -> str:
    """이미 있는 설정 — 같은 대상을 새 항목으로 다시 만들지 않고 새 사실만 덧붙이게 원문 뒤에 붙인다."""
    def tag(e):
        if e.get("kind") == "world" and e.get("title") == GENERIC_WORLD:
            return "[항상 · 세계 · 아직 이름 없음] "
        return "[항상] " if e.get("always") else ""
    rows = [f"- {tag(e)}{e.get('title', '')}: {e.get('content') or e.get('text') or ''}"
            for e in known or [] if isinstance(e, dict) and str(e.get("title") or "").strip()]
    return "\n[이미 있는 설정]\n" + ("\n".join(rows) if rows else "(없음)") + "\n"


def book_prompt(source: str, lang: str = "ko", known=None) -> str:
    return _prompt("lore/book_prompt", lang) + source + "\n" + known_block(known)


def fixed_block(entries) -> str:
    """진행 상태를 고칠 때 대조할 고정 로어북. 프롬프트가 「고정 로어북과 어긋나면 반영하지 말라」고 하는데
    예전에는 고정 항목을 안 넘겨서 대조할 수가 없었다(2026-11-18 장기 연재 점검)."""
    rows = [f"- {e.get('id', '')} {e.get('title', '')}: {e.get('content') or e.get('text') or ''}"
            for e in entries or [] if isinstance(e, dict) and e.get("enabled", True) and str(e.get("title") or "").strip()]
    return "[고정 로어북 — key 가 이 id 면 그 항목의 지금 상태다]\n" + ("\n".join(rows) if rows else "(없음)") + "\n\n"


def progress_prompt(rows: list[dict], new_text: str, lang: str = "ko", entries=None) -> str:
    return (_prompt("lore/progress_prompt", lang).replace("[현재 진행 상태]\n", fixed_block(entries) + "[현재 진행 상태]\n")
            + json.dumps({"entries": rows}, ensure_ascii=False, indent=1)
            + "\n\n[새 화 본문]\n" + new_text + "\n")


# 1화가 설정집 없이 장르의 세계 설명만으로 섰을 때 그 세계 항목의 제목(continuation.world_seed). 이름이 아니다.
GENERIC_WORLD = "세계 배경"


def _norm(title) -> str:
    return re.sub(r"\s+", "", str(title or "")).casefold()



def book_entries(entries: list, existing: list | None = None) -> tuple[list[dict], dict[str, str], dict[str, dict]]:
    """뽑은 항목을 제품 설정집 항목으로. (새 항목, {이미 있는 항목 id: 덧붙일 사실}, {세계 항목 id: 새 이름}).

    같은 제목은 하나로 합친다(실험의 extract_rules 와 같다). 이미 있는 항목(existing)과 제목이 같으면 새로
    만들지 않고 그 항목에 덧붙일 사실로 돌려준다. 새 항목 id 는 L0001… 이고 이미 있는 번호 다음부터 잇는다 —
    진행 상태가 이 id 로 항목을 덮어쓴다.
    """
    existing = [e for e in existing or [] if isinstance(e, dict)]
    known = {_norm(e.get("title")): str(e.get("id") or "") for e in existing}
    n = max([int(m.group(1)) for e in existing for m in [re.match(r"L(\d+)$", str(e.get("id") or ""))] if m], default=0)
    out, seen, more, renamed = [], {}, {}, {}
    for e in entries or []:
        if not isinstance(e, dict) or not str(e.get("text") or "").strip() or not _norm(e.get("title")):
            continue
        key, text = _norm(e["title"]), str(e["text"]).strip()
        keys = [str(k).strip() for k in (e.get("keys") if isinstance(e.get("keys"), list) else []) if str(k).strip()]
        # 세계 항목은 하나다. 1화에서 옮겨 온 세계(continuation.world_seed)가 있으면 새로 만들지 않고 거기에
        # 덧붙이고, 그 세계에 아직 이름이 없었으면(GENERIC_WORLD) 이번에 뽑은 이름으로 바꾼다.
        world = next((x for x in existing if x.get("kind") == "world"), None) if e.get("type") == "world" else None
        if world is not None and world.get("id"):
            more[world["id"]] = (more[world["id"]] + "\n" if world["id"] in more else "") + text
            if world.get("title") == GENERIC_WORLD:
                renamed[world["id"]] = {"title": str(e["title"]).strip(), "keys": keys, "named": e.get("named") is True}
            continue
        if key in known and known[key]:
            more[known[key]] = (more[known[key]] + "\n" if known[key] in more else "") + text
            continue
        if key in seen:
            hit = seen[key]
            hit["content"] += "\n" + text
            hit["keys"] += [k for k in keys if k not in hit["keys"]]
            hit["always"] = hit["always"] or e.get("always") is True
            continue
        n += 1
        row = {"id": f"L{n:04d}", "title": str(e["title"]).strip(), "kind": str(e.get("type") or "").strip(),
               "keys": keys or [str(e["title"]).strip()], "content": text,
               "always": e.get("always") is True, "enabled": True}
        if e.get("type") == "world" and e.get("named") is True:
            row["named"] = True
        seen[key] = row
        out.append(row)
    return out, more, renamed


def apply_book(existing: list, rows: list, more: dict, renamed: dict | None = None) -> list[dict]:
    """이미 있는 항목에 새 사실을 덧붙이고(updated), 이름 없던 세계에 이름을 달고, 새 항목을 뒤에 붙인다(new)."""
    renamed = renamed or {}
    kept = []
    for e in existing or []:
        if not isinstance(e, dict):
            continue
        i = str(e.get("id") or "")
        if i in more:
            e = {**e, "content": str(e.get("content") or "").rstrip() + "\n" + more[i], "updated": True}
        if i in renamed:
            r = renamed[i]
            e = {**e, "title": r["title"], "keys": list(dict.fromkeys([*(e.get("keys") or []), r["title"], *r["keys"]])),
                 **({"named": True} if r["named"] else {})}
        kept.append(e)
    return kept + [{**r, "new": True} for r in rows]


def merge_progress(rows: list[dict], changes: list, removed: list, ids=None, ep=None) -> list[dict]:
    """실험의 progress_update 합치기 — 지울 것을 빼고, key 가 있으면 덮어쓰고, 없으면 새 P 번호.

    새 P 번호는 남은 P 개수 다음 번호에서 시작하되 **이미 쓰는 번호는 건너뛴다.** 예전에는 P001·P002 중 P001
    을 지우고 새 상태를 넣으면 새 상태가 P002 가 되어 남아 있던 P002 를 덮어썼다(2026-11-18 재현).
    ids(고정 항목 id 목록)를 주면, 고정 항목에 없는 L 번호는 새 상태로 받는다 — 없는 항목을 덮어쓰는 줄이 생기지 않게."""
    removed = {str(k) for k in removed or []}
    # 「앎」은 지우지 않는다 — 한 번 안 것은 모르는 상태로 돌아가지 않는다(prompt/lore/progress_prompt).
    known = {str(r.get("key")) for r in rows if r.get("kind") == "앎"}
    removed -= known
    merged = {r["key"]: r for r in rows if r.get("key") and r["key"] not in removed}
    given = {k for k in merged if k.startswith("P")}       # 입력에 있던 P 번호만 고칠 수 있다(옮긴 줄의 새 번호는 아님)
    ids = None if ids is None else {str(i) for i in ids}
    n = sum(1 for k in merged if k.startswith("P"))

    def fresh():
        nonlocal n
        n += 1
        while f"P{n:03d}" in merged or ids is not None and f"P{n:03d}" in ids:
            n += 1
        return f"P{n:03d}"

    def is_entry(key):
        return key in ids if ids is not None else key.startswith("L")

    moved: dict[str, str] = {}
    if ids is not None:
        # 고정 항목이 없어진 「→ 지금」 줄은 새 상태로 돌린다 — 나중에 같은 번호를 받은 다른 항목 밑에 붙지 않게.
        for key in [k for k in merged if not k.startswith("P") and k not in ids]:
            row = merged.pop(key)
            moved[key] = fresh()
            given.add(moved[key])          # 옛 번호(L0002)로 온 변경만 이 줄을 고친다 — 아래 key = moved.get(...)
            merged[moved[key]] = {**row, "key": moved[key]}
    for c in changes or []:
        if not isinstance(c, dict) or not str(c.get("text") or "").strip():
            continue
        key = str(c.get("key") or "").strip()
        if key in moved.values() and key not in moved:
            key = ""                       # 모델이 입력에 없던 새 번호(옮긴 줄의 번호)를 쓴 것 — 새 줄로 받는다
        key = moved.get(key, key)
        if not ((key in given and key in merged) or (key and is_entry(key)) or (ids is None and key in merged)):
            key = fresh()
        if key in known and str(c.get("kind") or "앎").strip() != "앎":
            key = fresh()                  # 「앎」 줄을 다른 종류로 덮어쓰지 않는다 — 새 줄로 받는다
        before = merged.get(key) or {}
        kind = str(c.get("kind") or before.get("kind") or "").strip()
        row = {"key": key, "title": str(c.get("title") or "").strip(), "text": str(c["text"]).strip()}
        if kind in KINDS:
            row["kind"] = kind
        if ep is not None:
            row["ep"] = ep
        was = list(before.get("was") or [])
        if kind == "관계" and before.get("text") and before["text"] != row["text"]:
            # 관계는 지금 사이만 남기면 「왜 이렇게 됐는지」가 사라져 재회 때 처음으로 돌아간다. 바뀌기 전을 셋까지 남긴다.
            was = (was + [f"{before.get('ep') or '?'}화까지: {before['text']}"])[-3:]
        if was:
            row["was"] = was
        merged[key] = row
    return list(merged.values())


# 진행 상태의 종류(prompt/lore/progress_prompt). 사건 기록은 진행 상태와 같은 목록에 kind 「사건」으로 둔다 —
# 서버가 진행 상태 목록을 그대로 다음 화로 넘기므로 저장할 자리를 따로 만들지 않아도 된다.
KINDS = ("지금", "몸", "관계", "앎", "미해결")
EVENT = "사건"
KEEP = ("kind", "ep", "was")


def is_event(row) -> bool:
    return isinstance(row, dict) and str(row.get("kind") or "") == EVENT


def progress_rows(progress) -> list[dict]:
    """진행 상태 줄(사건 기록은 뺀다). 종류 · 출처 화 · 바뀌기 전 상태가 있으면 그대로 둔다."""
    out = []
    for p in progress or []:
        if not isinstance(p, dict) or not str(p.get("text") or "").strip() or is_event(p):
            continue
        row = {"key": str(p.get("key") or p.get("id") or ""), "title": str(p.get("title") or ""),
               "text": str(p.get("text") or "")}
        row.update({k: p[k] for k in KEEP if p.get(k) not in (None, "", [])})
        out.append(row)
    return out


def event_rows(progress) -> list[dict]:
    return [p for p in progress or [] if is_event(p) and str(p.get("text") or "").strip()]


def extract(source: str, out_dir: Path, lang: str = "ko", existing: list | None = None) -> tuple[list[dict], dict[str, str], dict[str, dict]]:
    """고정 로어북 — 대상마다 항목 하나. 이미 있는 대상은 새 사실만 돌려준다."""
    existing = [e for e in existing or [] if isinstance(e, dict)]
    got = _call(book_prompt(source, lang, existing), out_dir, "LORE_BOOK").get("entries", [])
    return book_entries(got, existing)


def update_progress(rows: list[dict], new_text: str, out_dir: Path, lang: str = "ko", entries=None, ep=None) -> tuple[list, list, list]:
    got = _call(progress_prompt(rows, new_text, lang, entries), out_dir, "LORE_PROGRESS")
    changes = got.get("entries", []) if isinstance(got.get("entries"), list) else []
    removed = got.get("remove", []) if isinstance(got.get("remove"), list) else []
    ids = None if entries is None else [e.get("id") for e in entries if isinstance(e, dict) and e.get("id")]
    kept_known = {str(r.get("key")) for r in rows if r.get("kind") == "앎"}
    return merge_progress(rows, changes, removed, ids, ep), changes, [k for k in removed if str(k) not in kept_known]


def events_prompt(new_text: str, lang: str = "ko") -> str:
    return _prompt("lore/events_prompt", lang) + new_text + "\n"


def extract_events(new_text: str, out_dir: Path, lang: str = "ko", ep=None) -> dict | None:
    """이 화의 사건 기록 한 줄(진행 상태 목록에 kind 「사건」으로 붙는다). 뽑은 것이 없으면 None."""
    got = _call(events_prompt(new_text, lang), out_dir, "LORE_EVENTS")
    raw = got.get("events") if isinstance(got.get("events"), list) else []
    lines = [x.strip() for x in raw if isinstance(x, str) and x.strip()]
    if not lines:
        return None
    n = ep if ep is not None else 0
    return {"key": f"E{n:03d}", "kind": EVENT, "ep": ep, "title": f"{n}화", "text": "\n".join(lines)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    lang = str(data.get("language") or "ko")
    entries, _, _ = extract(data["text"], args.out.parent, lang)
    book = {"name": "내 이야기의 설정", "source": "extracted", "confirmed": False,
            "fixed_settings": "", "progress_state": "", "entries": entries, "progress": []}
    args.out.write_text(json.dumps(book, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
