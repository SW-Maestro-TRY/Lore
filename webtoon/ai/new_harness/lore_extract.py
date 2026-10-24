"""글에서 2층 로어북을 뽑는다. 웹툰 생성이나 자동 확정은 하지 않는다.

orv-experiment(전독시×차시연 실험)와 같은 구조다.
- 고정 로어북 = 키워드 항목(prompt/lore/entries_prompt) + 항상 켜짐 규칙(prompt/lore/rules_prompt).
  둘은 따로 뽑는다. 항목은 키워드가 걸릴 때만, 규칙은 매 화 전부 들어간다(userbook.block).
- 진행 상태 = 고정 로어북 위에 덮어쓰는 층(prompt/lore/progress_prompt). 화가 끝날 때마다 바뀐 것과
  지울 것만 받아 합친다. key 가 고정 항목 id 면 그 항목의 「→ 지금:」 덮어쓰기, 그 밖은 새 상태다.

만들기 전에 적은 글(main)은 진행 상태를 빈 목록으로 시작한다 — 실험의 텍본 조건처럼, 아직 연재가
시작되지 않았으니 덮어쓸 것이 없다. 끝난 회차 정리는 continuation.stage_digest 가 같은 함수로 한다.
"""
import argparse
import json
import re
from concurrent.futures import ThreadPoolExecutor
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


def entries_prompt(source: str, lang: str = "ko") -> str:
    return _prompt("lore/entries_prompt", lang) + source + "\n"


def rules_prompt(source: str, lang: str = "ko") -> str:
    return _prompt("lore/rules_prompt", lang) + source + "\n"


def progress_prompt(rows: list[dict], new_text: str, lang: str = "ko") -> str:
    return (_prompt("lore/progress_prompt", lang) + json.dumps({"entries": rows}, ensure_ascii=False, indent=1)
            + "\n\n[새 화 본문]\n" + new_text + "\n")


def _norm(title) -> str:
    return re.sub(r"\s+", "", str(title or "")).casefold()


def book_entries(entries: list, rules: list, existing: list | None = None) -> list[dict]:
    """뽑은 항목·규칙을 제품 설정집 항목으로. 같은 제목은 하나로 합친다(실험의 extract_rules 와 같다).

    id 는 항목 L0001…, 규칙 R001… — 진행 상태가 이 id 로 항목을 덮어쓴다. 이미 있는 항목(existing)과
    제목이 같은 것은 새로 만들지 않고, 번호는 이미 있는 것 다음부터 잇는다.
    """
    existing = [e for e in existing or [] if isinstance(e, dict)]
    known = {_norm(e.get("title")) for e in existing}

    def last(prefix):
        nums = [int(m.group(1)) for e in existing
                for m in [re.match(prefix + r"(\d+)$", str(e.get("id") or ""))] if m]
        return max(nums, default=0)

    n_entry, n_rule = last("L"), last("R")
    out, seen = [], {}
    for e in entries or []:
        if not isinstance(e, dict) or not str(e.get("text") or "").strip() or not _norm(e.get("title")):
            continue
        key = _norm(e["title"])
        if key in known:
            continue
        keys = [str(k).strip() for k in (e.get("keys") if isinstance(e.get("keys"), list) else []) if str(k).strip()]
        if key in seen:
            hit = seen[key]
            hit["content"] += " / " + str(e["text"]).strip()
            hit["keys"] += [k for k in keys if k not in hit["keys"]]
            continue
        n_entry += 1
        row = {"id": f"L{n_entry:04d}", "title": str(e["title"]).strip(), "kind": str(e.get("type") or "").strip(),
               "keys": keys or [str(e["title"]).strip()], "content": str(e["text"]).strip(),
               "always": False, "enabled": True}
        seen[key] = row
        out.append(row)
    for r in rules or []:
        if not isinstance(r, dict) or not str(r.get("text") or "").strip() or not _norm(r.get("title")):
            continue
        key = "rule:" + _norm(r["title"])
        if _norm(r["title"]) in known:
            continue
        if key in seen:
            seen[key]["content"] += " / " + str(r["text"]).strip()
            continue
        n_rule += 1
        row = {"id": f"R{n_rule:03d}", "title": str(r["title"]).strip(), "kind": "rule", "keys": [],
               "content": str(r["text"]).strip(), "always": True, "enabled": True}
        seen[key] = row
        out.append(row)
    return out


def merge_progress(rows: list[dict], changes: list, removed: list) -> list[dict]:
    """실험의 progress_update 합치기 그대로 — 지울 것을 빼고, key 가 있으면 덮어쓰고, 없으면 새 P 번호."""
    removed = {str(k) for k in removed or []}
    merged = {r["key"]: r for r in rows if r.get("key") and r["key"] not in removed}
    n = sum(1 for k in merged if k.startswith("P"))
    for c in changes or []:
        if not isinstance(c, dict) or not str(c.get("text") or "").strip():
            continue
        key = str(c.get("key") or "").strip()
        if not key or key not in merged and not key.startswith("L"):
            n += 1
            key = f"P{n:03d}"
        merged[key] = {"key": key, "title": str(c.get("title") or "").strip(), "text": str(c["text"]).strip()}
    return list(merged.values())


def progress_rows(progress) -> list[dict]:
    return [{"key": str(p.get("key") or p.get("id") or ""), "title": str(p.get("title") or ""),
             "text": str(p.get("text") or "")}
            for p in progress or [] if isinstance(p, dict) and str(p.get("text") or "").strip()]


def extract(source: str, out_dir: Path, lang: str = "ko", existing: list | None = None) -> list[dict]:
    """고정 로어북 — 항목과 규칙을 나란히 뽑는다."""
    with ThreadPoolExecutor(max_workers=2) as pool:
        got_entries = pool.submit(_call, entries_prompt(source, lang), out_dir, "LORE_ENTRIES")
        got_rules = pool.submit(_call, rules_prompt(source, lang), out_dir, "LORE_RULES")
        entries, rules = got_entries.result().get("entries", []), got_rules.result().get("rules", [])
    return book_entries(entries, rules, existing)


def update_progress(rows: list[dict], new_text: str, out_dir: Path, lang: str = "ko") -> tuple[list, list, list]:
    got = _call(progress_prompt(rows, new_text, lang), out_dir, "LORE_PROGRESS")
    changes = got.get("entries", []) if isinstance(got.get("entries"), list) else []
    removed = got.get("remove", []) if isinstance(got.get("remove"), list) else []
    return merge_progress(rows, changes, removed), changes, removed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    lang = str(data.get("language") or "ko")
    entries = extract(data["text"], args.out.parent, lang)
    book = {"name": "내 이야기의 설정", "source": "extracted", "confirmed": False,
            "fixed_settings": "", "progress_state": "", "entries": entries, "progress": []}
    args.out.write_text(json.dumps(book, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
