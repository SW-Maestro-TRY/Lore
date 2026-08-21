"""outputs/compare.html + outputs/score_sheet.csv 생성.

run.py 실행이 끝나면 자동으로 호출된다. 이미지가 이미 있다면
  python run.py --report-only
로 API 호출 없이 다시 만들 수도 있다.
"""

from __future__ import annotations

import csv
import html
import json
from datetime import datetime
from pathlib import Path
from typing import Any

SCORE_COLUMNS = [
    "condition",
    "scene_id",
    "repeat",
    "머리색",
    "헤어스타일",
    "얼굴형",
    "눈매",
    "복장디테일",
    "나이대",
    "같은사람인가",
    "메모",
]


# --------------------------------------------------------------------------- #
# score_sheet.csv
# --------------------------------------------------------------------------- #
def write_score_sheet(out_dir: Path, conditions: list[str], scenes: list[dict], repeats: int) -> Path:
    """채점용 빈 템플릿. 이미 있으면 덮어쓰지 않고 .new.csv 로 뺀다."""
    target = out_dir / "score_sheet.csv"
    if target.exists():
        target = out_dir / "score_sheet.new.csv"

    with target.open("w", newline="", encoding="utf-8-sig") as fh:  # Excel 대응 BOM
        writer = csv.writer(fh)
        writer.writerow(SCORE_COLUMNS)
        blanks = [""] * (len(SCORE_COLUMNS) - 3)
        for cond in conditions:
            for scene in scenes:
                for rep in range(1, repeats + 1):
                    writer.writerow([cond, scene["id"], rep, *blanks])
    return target


# --------------------------------------------------------------------------- #
# compare.html
# --------------------------------------------------------------------------- #
def _load_log(out_dir: Path) -> dict[tuple[str, str, int], dict[str, Any]]:
    """(condition, scene_id, repeat) -> 마지막 로그 항목."""
    log_path = out_dir / "log.jsonl"
    latest: dict[tuple[str, str, int], dict[str, Any]] = {}
    if not log_path.exists():
        return latest
    with log_path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            key = (rec.get("condition"), rec.get("scene_id"), int(rec.get("repeat", 0)))
            latest[key] = rec
    return latest


def _cell(out_dir: Path, cond: str, scene_id: str, repeats: int, log: dict) -> str:
    shots: list[str] = []
    for rep in range(1, repeats + 1):
        rel = f"{cond}/{scene_id}_r{rep}.png"
        rec = log.get((cond, scene_id, rep))
        if (out_dir / rel).exists():
            cap = f"{cond} · {scene_id} · r{rep}"
            shots.append(
                f'<figure class="shot"><img src="{html.escape(rel)}" alt="{html.escape(cap)}" '
                f'loading="lazy" data-caption="{html.escape(cap)}">'
                f'<figcaption>r{rep}</figcaption></figure>'
            )
        else:
            err = ""
            if rec and not rec.get("ok", True):
                err = html.escape(str(rec.get("error") or ""))[:200]
            shots.append(
                f'<figure class="shot missing"><div class="ph">r{rep}<br><span>{err or "없음"}</span></div></figure>'
            )
    return f'<div class="shots">{"".join(shots)}</div>'


def write_compare_html(
    out_dir: Path,
    conditions: list[str],
    condition_labels: dict[str, str],
    scenes: list[dict],
    repeats: int,
    meta: dict[str, Any],
    refs: list[str],
) -> Path:
    log = _load_log(out_dir)

    head = "".join(
        f"<th><span class='cond'>{html.escape(c)}</span>"
        f"<span class='lab'>{html.escape(condition_labels.get(c, ''))}</span></th>"
        for c in conditions
    )

    rows: list[str] = []
    for scene in scenes:
        cells = "".join(f"<td>{_cell(out_dir, c, scene['id'], repeats, log)}</td>" for c in conditions)
        rows.append(
            "<tr>"
            f"<th class='scene'><span class='sid'>{html.escape(scene['id'])}</span>"
            f"<span class='sp'>{html.escape(str(scene.get('prompt', '')))}</span></th>"
            f"{cells}</tr>"
        )

    ref_html = ""
    if refs:
        items = []
        for r in refs:
            p = (out_dir.parent / r).resolve()
            rel = f"../{r}" if p.exists() else None
            if rel:
                items.append(
                    f'<figure class="shot"><img src="{html.escape(rel)}" data-caption="{html.escape(r)}" '
                    f'loading="lazy" alt="{html.escape(r)}"><figcaption>{html.escape(Path(r).name)}</figcaption></figure>'
                )
        if items:
            ref_html = f"<section class='refs'><h2>레퍼런스</h2><div class='shots'>{''.join(items)}</div></section>"

    meta_html = " · ".join(
        html.escape(f"{k}: {v}") for k, v in meta.items() if v not in (None, "")
    )

    doc = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>캐릭터 일관성 비교</title>
<style>
  :root {{ color-scheme: light dark; --bd:#d8d8de; --mut:#6b6b76; --bg:#fff; --fg:#16161a; --cell:#fafafa; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bd:#33343a; --mut:#9a9aa5; --bg:#131316; --fg:#ececf0; --cell:#1a1a1f; }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; padding:24px; background:var(--bg); color:var(--fg);
         font:14px/1.5 -apple-system,"Segoe UI",Roboto,"Malgun Gothic",sans-serif; }}
  h1 {{ font-size:20px; margin:0 0 4px; }}
  .meta {{ color:var(--mut); font-size:12px; margin-bottom:20px; }}
  .refs {{ margin-bottom:24px; }} .refs h2 {{ font-size:14px; margin:0 0 8px; }}
  .wrap {{ overflow-x:auto; border:1px solid var(--bd); border-radius:8px; }}
  table {{ border-collapse:collapse; min-width:100%; }}
  th, td {{ border:1px solid var(--bd); padding:10px; vertical-align:top; }}
  thead th {{ position:sticky; top:0; background:var(--bg); z-index:2; text-align:center; }}
  thead th .cond {{ display:block; font-size:16px; }}
  thead th .lab {{ display:block; font-weight:400; font-size:11px; color:var(--mut); }}
  th.scene {{ text-align:left; width:190px; min-width:190px; background:var(--cell); }}
  th.scene .sid {{ display:block; font-family:ui-monospace,Consolas,monospace; }}
  th.scene .sp {{ display:block; font-weight:400; font-size:11px; color:var(--mut); margin-top:4px; }}
  .shots {{ display:flex; gap:8px; flex-wrap:wrap; }}
  .shot {{ margin:0; width:150px; }}
  .shot img {{ width:150px; height:150px; object-fit:cover; border-radius:6px;
               border:1px solid var(--bd); cursor:zoom-in; display:block; background:var(--cell); }}
  .shot figcaption {{ font-size:11px; color:var(--mut); text-align:center; margin-top:3px; }}
  .missing .ph {{ width:150px; height:150px; border:1px dashed var(--bd); border-radius:6px;
                  display:flex; flex-direction:column; align-items:center; justify-content:center;
                  color:var(--mut); font-size:11px; text-align:center; padding:8px; gap:4px; }}
  .missing .ph span {{ font-size:10px; word-break:break-all; }}
  #lb {{ position:fixed; inset:0; background:rgba(0,0,0,.88); display:none; z-index:99;
         align-items:center; justify-content:center; flex-direction:column; gap:10px; padding:24px; cursor:zoom-out; }}
  #lb.on {{ display:flex; }}
  #lb img {{ max-width:96vw; max-height:88vh; object-fit:contain; }}
  #lb p {{ color:#eee; margin:0; font-size:13px; font-family:ui-monospace,Consolas,monospace; }}
</style>
</head>
<body>
<h1>캐릭터 일관성 비교 — 행=장면, 열=조건</h1>
<div class="meta">{meta_html}</div>
{ref_html}
<div class="wrap">
<table>
  <thead><tr><th class="scene">scene</th>{head}</tr></thead>
  <tbody>
    {"".join(rows)}
  </tbody>
</table>
</div>
<div id="lb"><img alt=""><p></p></div>
<script>
  const lb = document.getElementById('lb');
  const lbImg = lb.querySelector('img'), lbCap = lb.querySelector('p');
  document.addEventListener('click', e => {{
    const img = e.target.closest('.shot img');
    if (img) {{ lbImg.src = img.src; lbCap.textContent = img.dataset.caption || ''; lb.classList.add('on'); return; }}
    if (e.target.closest('#lb')) lb.classList.remove('on');
  }});
  document.addEventListener('keydown', e => {{ if (e.key === 'Escape') lb.classList.remove('on'); }});
</script>
</body>
</html>
"""
    path = out_dir / "compare.html"
    path.write_text(doc, encoding="utf-8")
    return path


# --------------------------------------------------------------------------- #
# style_picks.json — "어느 장이 괜찮았나" 기록
# --------------------------------------------------------------------------- #
PICKS_FILENAME = "style_picks.json"


def load_picks(out_dir: Path) -> dict[str, dict[str, Any]]:
    """{style_id: {"picks": [1,3], "note": "..."}}. 없거나 깨졌으면 빈 dict."""
    path = out_dir / PICKS_FILENAME
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    styles = data.get("styles") if isinstance(data, dict) else None
    if not isinstance(styles, dict):
        return {}
    out: dict[str, dict[str, Any]] = {}
    for sid, entry in styles.items():
        if not isinstance(entry, dict):
            continue
        picks = entry.get("picks") or []
        out[str(sid)] = {
            "picks": sorted({int(p) for p in picks if str(p).isdigit()}),
            "note": str(entry.get("note") or ""),
        }
    return out


def save_picks(out_dir: Path, picks: dict[str, dict[str, Any]]) -> Path:
    path = out_dir / PICKS_FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "updated": datetime.now().isoformat(timespec="seconds"),
        "styles": {
            sid: {"picks": sorted(entry.get("picks") or []), "note": str(entry.get("note") or "")}
            for sid, entry in picks.items()
        },
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def picks_shorthand(styles: list[dict], picks: dict[str, dict[str, Any]]) -> str:
    """'f1 r2 r4 r6 | f2 r2 r3' 형태. 사람이 적는 방식 그대로 되돌려준다."""
    parts: list[str] = []
    for style in styles:
        sid = str(style["id"])
        entry = picks.get(sid) or {}
        chosen = entry.get("picks") or []
        short = sid.split("_")[0]
        parts.append(f"{short} " + " ".join(f"r{n}" for n in chosen) if chosen else f"{short} (없음)")
    return " | ".join(parts)


# --------------------------------------------------------------------------- #
# style_compare.html  (--style-test / --style-report)
# --------------------------------------------------------------------------- #
def _style_shots(out_dir: Path, sid: str, repeats: int, log: dict) -> str:
    shots: list[str] = []
    for rep in range(1, repeats + 1):
        rel = f"style/{sid}_r{rep}.png"
        exists = (out_dir / rel).exists()
        cap = f"{sid} · r{rep}"
        if exists:
            shots.append(
                f'<figure class="shot" data-sid="{html.escape(sid)}" data-rep="{rep}">'
                f'<img src="{html.escape(rel)}" alt="{html.escape(cap)}" loading="lazy" '
                f'data-caption="{html.escape(cap)}">'
                f'<button class="pick" type="button" data-sid="{html.escape(sid)}" data-rep="{rep}">'
                f'<span class="mark">✓</span> r{rep}</button>'
                "</figure>"
            )
        else:
            rec = log.get(("style", sid, rep))
            err = ""
            if rec and not rec.get("ok", True):
                err = html.escape(str(rec.get("error") or ""))[:120]
            shots.append(
                f'<figure class="shot missing"><div class="ph">r{rep}<br>'
                f'<span>{err or "없음"}</span></div>'
                f'<button class="pick" type="button" disabled>r{rep}</button></figure>'
            )
    return f'<div class="shots">{"".join(shots)}</div>'


def write_style_compare_html(
    out_dir: Path,
    styles: list[dict],
    repeats: int,
    scene: str,
    meta: dict[str, Any],
) -> Path:
    """스타일 카드 그리드 + 마음에 든 장을 직접 고르는 UI."""
    log = _load_log(out_dir)
    file_picks = load_picks(out_dir)

    cards: list[str] = []
    for style in styles:
        sid = str(style["id"])
        label = str(style.get("label") or "")
        cards.append(
            f"<article class='card' data-sid='{html.escape(sid)}'>"
            f"<h2><span class='sid'>{html.escape(sid)}</span>"
            f"<span class='lab'>{html.escape(label)}</span>"
            f"<span class='count' data-count='{html.escape(sid)}'></span></h2>"
            f"<p class='sp'>{html.escape(str(style.get('prompt', '')))}</p>"
            f"{_style_shots(out_dir, sid, repeats, log)}"
            f"<textarea class='note' data-note='{html.escape(sid)}' rows='2' "
            f"placeholder='메모 (예: 존나 별로임 / 내 취향 아님)'></textarea>"
            "</article>"
        )

    meta_html = " · ".join(
        html.escape(f"{k}: {v}") for k, v in meta.items() if v not in (None, "")
    )
    styles_json = json.dumps(
        [{"id": str(s["id"]), "short": str(s["id"]).split("_")[0]} for s in styles],
        ensure_ascii=False,
    )
    picks_json = json.dumps(file_picks, ensure_ascii=False)

    doc = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>그림체 비교</title>
<style>
  :root {{ color-scheme: light dark; --bd:#d8d8de; --mut:#6b6b76; --bg:#fff; --fg:#16161a;
           --cell:#fafafa; --ok:#1a7f4b; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bd:#33343a; --mut:#9a9aa5; --bg:#131316; --fg:#ececf0; --cell:#1a1a1f; --ok:#3ea76d; }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; padding:24px; background:var(--bg); color:var(--fg);
         font:14px/1.5 -apple-system,"Segoe UI",Roboto,"Malgun Gothic",sans-serif; }}
  h1 {{ font-size:20px; margin:0 0 4px; }}
  .meta {{ color:var(--mut); font-size:12px; margin-bottom:12px; }}
  .scene {{ border:1px solid var(--bd); border-radius:8px; background:var(--cell);
            padding:10px 12px; margin-bottom:20px; font-size:13px; }}
  .scene b {{ font-weight:600; margin-right:6px; }}
  .ask {{ font-size:13px; color:var(--mut); margin-bottom:20px; }}
  .grid {{ display:grid; gap:16px; grid-template-columns:repeat(auto-fill, minmax(330px, 1fr)); }}
  .card {{ border:1px solid var(--bd); border-radius:8px; padding:12px; background:var(--cell); }}
  .card h2 {{ font-size:14px; margin:0 0 6px; }}
  .card h2 .sid {{ font-family:ui-monospace,Consolas,monospace; margin-right:8px; }}
  .card h2 .lab {{ font-weight:400; font-size:12px; color:var(--mut); }}
  .card h2 .count {{ float:right; font-weight:400; font-size:11px; color:var(--mut); }}
  .card.has .sid {{ color:var(--ok); }}
  .card .sp {{ margin:0 0 10px; font-size:11px; color:var(--mut); }}
  .shots {{ display:flex; gap:8px; flex-wrap:wrap; }}
  .shot {{ margin:0; width:150px; }}
  .shot img {{ width:150px; height:150px; object-fit:cover; border-radius:6px;
               border:1px solid var(--bd); cursor:zoom-in; display:block; background:var(--bg); }}
  .shot.on img {{ border:2px solid var(--ok); }}
  .shot figcaption {{ font-size:11px; color:var(--mut); text-align:center; margin-top:3px; }}
  .pick {{ width:100%; margin-top:4px; padding:3px 0; font:inherit; font-size:11px; cursor:pointer;
           border:1px solid var(--bd); border-radius:5px; background:var(--bg); color:var(--mut); }}
  .pick .mark {{ opacity:.25; }}
  .pick:hover:not(:disabled) {{ border-color:var(--ok); }}
  .shot.on .pick {{ border-color:var(--ok); background:var(--ok); color:#fff; }}
  .shot.on .pick .mark {{ opacity:1; }}
  .pick:disabled {{ cursor:not-allowed; opacity:.4; }}
  .note {{ width:100%; margin-top:10px; padding:6px 8px; font:inherit; font-size:12px;
           border:1px solid var(--bd); border-radius:6px; background:var(--bg); color:var(--fg);
           resize:vertical; }}
  .missing .ph {{ width:150px; height:150px; border:1px dashed var(--bd); border-radius:6px;
                  display:flex; flex-direction:column; align-items:center; justify-content:center;
                  color:var(--mut); font-size:11px; text-align:center; padding:8px; gap:4px; }}
  .missing .ph span {{ font-size:10px; word-break:break-all; }}
  .bar {{ position:sticky; top:0; z-index:3; background:var(--bg); border:1px solid var(--bd);
          border-radius:8px; padding:10px 12px; margin-bottom:20px;
          display:flex; gap:12px; align-items:center; flex-wrap:wrap; }}
  .bar .sum {{ flex:1 1 320px; font-family:ui-monospace,Consolas,monospace; font-size:12px; }}
  .bar button {{ font:inherit; font-size:12px; padding:5px 10px; cursor:pointer;
                 border:1px solid var(--bd); border-radius:6px; background:var(--cell); color:var(--fg); }}
  .bar button:hover {{ border-color:var(--ok); }}
  .bar .hint {{ flex-basis:100%; font-size:11px; color:var(--mut); }}
  #lb {{ position:fixed; inset:0; background:rgba(0,0,0,.88); display:none; z-index:99;
         align-items:center; justify-content:center; flex-direction:column; gap:10px; padding:24px; cursor:zoom-out; }}
  #lb.on {{ display:flex; }}
  #lb img {{ max-width:96vw; max-height:88vh; object-fit:contain; }}
  #lb p {{ color:#eee; margin:0; font-size:13px; font-family:ui-monospace,Consolas,monospace; }}
</style>
</head>
<body>
<h1>그림체 비교 — 캐릭터/장면 고정, 스타일 문구만 변수</h1>
<div class="meta">{meta_html}</div>
<div class="scene"><b>고정 장면</b>{html.escape(scene)}</div>
<p class="ask">판정 기준은 하나입니다: <b>이게 사람이 그린 웹툰이라고 하면 믿겠는가?</b>
괜찮은 장의 <b>r 버튼</b>을 눌러 표시하고, 카드 아래 칸에 메모를 남기세요.</p>

<div class="bar">
  <div class="sum" id="sum"></div>
  <button id="copy" type="button">요약 복사</button>
  <button id="dl" type="button">style_picks.json 저장</button>
  <button id="reset" type="button">파일 값으로 되돌리기</button>
  <div class="hint" id="hint"></div>
</div>

<div class="grid">
  {"".join(cards)}
</div>
<div id="lb"><img alt=""><p></p></div>
<script>
  const STYLES = {styles_json};
  const FILE_PICKS = {picks_json};
  const KEY = 'char-harness:style-picks';

  const blank = () => Object.fromEntries(STYLES.map(s => [s.id, {{picks: [], note: ''}}]));
  const fromFile = () => {{
    const base = blank();
    for (const [sid, v] of Object.entries(FILE_PICKS)) {{
      if (base[sid]) base[sid] = {{picks: (v.picks || []).slice(), note: v.note || ''}};
    }}
    return base;
  }};

  let state, dirty = false;
  try {{
    const saved = localStorage.getItem(KEY);
    if (saved) {{ state = Object.assign(blank(), JSON.parse(saved)); dirty = true; }}
  }} catch (e) {{ /* localStorage 막힌 환경이면 그냥 파일 값 */ }}
  if (!state) state = fromFile();

  const shorthand = () => STYLES.map(s => {{
    const p = (state[s.id] && state[s.id].picks || []).slice().sort((a, b) => a - b);
    return s.short + ' ' + (p.length ? p.map(n => 'r' + n).join(' ') : '(없음)');
  }}).join(' | ');

  function render() {{
    document.querySelectorAll('.shot[data-sid]').forEach(fig => {{
      const on = (state[fig.dataset.sid].picks || []).includes(Number(fig.dataset.rep));
      fig.classList.toggle('on', on);
    }});
    STYLES.forEach(s => {{
      const n = (state[s.id].picks || []).length;
      const el = document.querySelector(`[data-count="${{s.id}}"]`);
      if (el) el.textContent = n ? n + '장 선택' : '';
      const card = document.querySelector(`.card[data-sid="${{s.id}}"]`);
      if (card) card.classList.toggle('has', n > 0);
      const note = document.querySelector(`[data-note="${{s.id}}"]`);
      if (note && note.value !== state[s.id].note) note.value = state[s.id].note;
    }});
    document.getElementById('sum').textContent = shorthand();
    document.getElementById('hint').textContent = dirty
      ? '브라우저에 임시 저장된 상태입니다. 기록으로 남기려면 style_picks.json 을 저장해 outputs/ 에 덮어쓰세요.'
      : 'outputs/style_picks.json 의 내용입니다.';
  }}

  function touch() {{
    dirty = true;
    try {{ localStorage.setItem(KEY, JSON.stringify(state)); }} catch (e) {{}}
    render();
  }}

  document.addEventListener('click', e => {{
    const btn = e.target.closest('.pick');
    if (btn && !btn.disabled) {{
      const arr = state[btn.dataset.sid].picks;
      const rep = Number(btn.dataset.rep);
      const i = arr.indexOf(rep);
      if (i === -1) arr.push(rep); else arr.splice(i, 1);
      arr.sort((a, b) => a - b);
      touch();
      return;
    }}
    const img = e.target.closest('.shot img');
    if (img) {{ lbImg.src = img.src; lbCap.textContent = img.dataset.caption || ''; lb.classList.add('on'); return; }}
    if (e.target.closest('#lb')) lb.classList.remove('on');
  }});

  document.addEventListener('input', e => {{
    const ta = e.target.closest('[data-note]');
    if (ta) {{ state[ta.dataset.note].note = ta.value; touch(); }}
  }});

  document.getElementById('copy').addEventListener('click', () => {{
    const notes = STYLES.filter(s => state[s.id].note)
      .map(s => s.short + ': ' + state[s.id].note).join('\\n');
    navigator.clipboard.writeText(shorthand() + (notes ? '\\n' + notes : ''));
  }});

  document.getElementById('dl').addEventListener('click', () => {{
    const payload = {{updated: new Date().toISOString(), styles: state}};
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(payload, null, 2) + '\\n'], {{type: 'application/json'}}));
    const a = document.createElement('a');
    a.href = url; a.download = 'style_picks.json'; a.click();
    URL.revokeObjectURL(url);
  }});

  document.getElementById('reset').addEventListener('click', () => {{
    state = fromFile();
    dirty = false;
    try {{ localStorage.removeItem(KEY); }} catch (e) {{}}
    render();
  }});

  const lb = document.getElementById('lb');
  const lbImg = lb.querySelector('img'), lbCap = lb.querySelector('p');
  document.addEventListener('keydown', e => {{ if (e.key === 'Escape') lb.classList.remove('on'); }});
  render();
</script>
</body>
</html>
"""
    path = out_dir / "style_compare.html"
    path.write_text(doc, encoding="utf-8")
    return path


def build_style_report(
    out_dir: Path,
    styles: list[dict],
    repeats: int,
    scene: str,
    provider_desc: str,
) -> Path:
    meta = {
        "생성": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "provider": provider_desc,
        "반복": repeats,
        "스타일": ", ".join(str(s["id"]) for s in styles),
    }
    return write_style_compare_html(out_dir, styles, repeats, scene, meta)


def build_reports(
    out_dir: Path,
    conditions: list[str],
    condition_labels: dict[str, str],
    scenes: list[dict],
    repeats: int,
    provider_desc: str,
    refs: list[str],
) -> tuple[Path, Path]:
    meta = {
        "생성": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "provider": provider_desc,
        "반복": repeats,
        "조건": ", ".join(conditions),
    }
    html_path = write_compare_html(out_dir, conditions, condition_labels, scenes, repeats, meta, refs)
    csv_path = write_score_sheet(out_dir, conditions, scenes, repeats)
    return html_path, csv_path
