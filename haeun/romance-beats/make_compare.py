#!/usr/bin/env python3
"""out/ 의 두 쪽 결과를 나란히 놓은 비교.html 을 만든다 (호출 0회)."""
import html, json, re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R = sys.argv[1]
S = R + "-scene"
FIELDS = ["직전 상태", "장소와 상황", "벌어지는 일", "인물의 행동과 표정", "겉모습·소지품·동행", "끝나는 상태", "나레이션"]
SHOW = ["벌어지는 일", "인물의 행동과 표정", "나레이션"]

def stories(arm, rid=None):
    text = (HERE / "out" / arm / (rid or R) / "story.md").read_text(encoding="utf-8")
    out = []
    for block in re.split(r"^## ", text, flags=re.M)[1:]:
        head, _, rest = block.partition("\n")
        intro = re.search(r"소개:\s*(.*?)\n\s*본문:", rest, re.S)
        body = re.search(r"본문:\s*(.*?)(?:\n---|\Z)", rest, re.S)
        out.append({"title": head.strip(), "intro": intro.group(1).strip() if intro else "",
                    "body": body.group(1).strip() if body else ""})
    return out

def scenes(arm, sid=None):
    sid = sid or S
    text = (HERE / "out" / arm / sid / "scene.md").read_text(encoding="utf-8")
    parsed = {s["n"] for s in json.loads((HERE / "out" / arm / sid / "scenes.json").read_text())["scenes"]}
    out = []
    for m in re.finditer(r"^장면 (\d+):(.*?)$(.*?)(?=^장면 \d+:|^등장인물:|\Z)", text, re.M | re.S):
        n, label, body = int(m.group(1)), m.group(2).strip(), m.group(3)
        f = {}
        for k in FIELDS:
            mm = re.search(rf"^{re.escape(k)}:\s*(.*)$", body, re.M)
            f[k] = mm.group(1).strip() if mm else ""
        out.append({"n": n, "label": label, "f": f, "dropped": n not in parsed})
    return out

e = html.escape
def story_card(s):
    return (f'<article class="card"><h4>{e(s["title"])}</h4>'
            f'<p class="intro">{e(s["intro"])}</p><details><summary>본문</summary><p>{e(s["body"])}</p></details></article>')

def scene_card(s):
    flag = ('<p class="flag">파서가 이 장면을 버렸다 — 라벨에 「' + e(s["label"]) + '」를 덧붙여서. 실제 run 이면 이 장은 안 그려진다.</p>') if s["dropped"] else ""
    rows = "".join(f'<dt>{e(k)}</dt><dd>{e(s["f"][k]).replace(" / ", "<br>")}</dd>' for k in SHOW if s["f"][k])
    return f'<article class="card{" dropped" if s["dropped"] else ""}"><h4>장면 {s["n"]}</h4>{flag}<dl>{rows}</dl></article>'

def cols(a, b, fn, la="기준선", lb="새 판"):
    rows = []
    for i in range(max(len(a), len(b))):
        x = fn(a[i]) if i < len(a) else ""
        y = fn(b[i]) if i < len(b) else ""
        rows.append(f'<div class="pair"><div class="cell"><span class="tag base">{la}</span>{x}</div><div class="cell"><span class="tag new">{lb}</span>{y}</div></div>')
    return "".join(rows)

import difflib
BASE_PROMPT = HERE.parent.parent / "webtoon" / "ai" / "new_harness" / "prompt"

def diff_block(name):
    a = (BASE_PROMPT / name).read_text(encoding="utf-8").splitlines()
    b = (HERE / "prompt" / name).read_text(encoding="utf-8").splitlines()
    out = []
    for g in difflib.SequenceMatcher(None, a, b).get_grouped_opcodes(2):
        out.append('<div class="hunk">')
        for tag, i1, i2, j1, j2 in g:
            if tag == "equal":
                out += [f'<div class="ln ctx">{e(x) or "&nbsp;"}</div>' for x in a[i1:i2]]
            if tag in ("delete", "replace"):
                out += [f'<div class="ln del">{e(x) or "&nbsp;"}</div>' for x in a[i1:i2]]
            if tag in ("insert", "replace"):
                out += [f'<div class="ln add">{e(x) or "&nbsp;"}</div>' for x in b[j1:j2]]
        out.append("</div>")
    return f'<details class="diff" open><summary>{e(name)}</summary>{"".join(out)}</details>'

def cols3(groups, fn):
    rows = []
    n = max(len(g[1]) for g in groups)
    for i in range(n):
        cells = "".join(f'<div class="cell"><span class="tag {c}">{e(l)}</span>{fn(g[i]) if i < len(g) else ""}</div>' for l, g, c in groups)
        rows.append(f'<div class="pair three">{cells}</div>')
    return "".join(rows)

tpl = (HERE / "compare_template.html").read_text(encoding="utf-8")
page = (tpl.replace("{{SC7}}", cols3([("초안 2", scenes("new", R + "-4-scene2"), "a"), ("초안 2.5 · 1회", scenes("new", R + "-4-scene25a"), "base"), ("초안 2.5 · 2회", scenes("new", R + "-4-scene25b"), "new")], scene_card)).replace("{{SC6}}", cols3([("A 장면 프롬프트", scenes("A", R + "-4-scene"), "a"), ("초안 2", scenes("new", R + "-4-scene2"), "base"), ("초안 3 (모순·개연성)", scenes("new", R + "-4-scene3"), "new")], scene_card)).replace("{{SC5}}", cols3([("A 장면 프롬프트", scenes("A", R + "-4-scene"), "a"), ("초안 1", scenes("new", R + "-4-scene"), "base"), ("초안 2 (나레이션·서비스 컷 수정)", scenes("new", R + "-4-scene2"), "new")], scene_card)).replace("{{SC4}}", cols(scenes("A", R + "-4-scene"), scenes("new", R + "-4-scene"), scene_card, "A 장면 프롬프트", "로맨스 초안")).replace("{{AA}}", cols(stories("A", R + "-A"), stories("new", R + "-4"), story_card, "A · 1회차", "A · 2회차")).replace("{{ACD}}", cols3([("A · 오늘 수정 전", stories("A", R + "-A"), "a"), ("C · #558 머지본", stories("base", R + "-2"), "base"), ("D · 지금 실험판", stories("new", R + "-2"), "new")], story_card)).replace("{{STORIES3}}", cols(stories("new", R + "-2"), stories("new", R + "-3"), story_card, "새 판 · 재료 있음 (2차)", "새 판 · 재료 없음 (3차)")).replace("{{STORIES2}}", cols(stories("base", R + "-2"), stories("new", R + "-2"), story_card)).replace("{{STORIES}}", cols(stories("base"), stories("new"), story_card))
           .replace("{{SCENES}}", cols(scenes("base"), scenes("new"), scene_card)))
(HERE / "비교.html").write_text(page, encoding="utf-8")
print(HERE / "비교.html")
