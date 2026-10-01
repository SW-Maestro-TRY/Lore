#!/usr/bin/env python3
"""out/ 의 모델별 결과를 나란히 놓은 비교.html 을 만든다 (호출 0회).

gpt-5.1 기준 결과는 romance-beats 실험의 것을 읽는다(같은 입력·같은 이야기).
"""
import html, json, re
from pathlib import Path

HERE = Path(__file__).resolve().parent
RB = HERE.parent / "romance-beats" / "out"
RB_ALT = Path("/Users/mint/project/agent-romance-beats/haeun/romance-beats/out")
if not RB.exists():
    RB = RB_ALT
e = html.escape
SHOW = ["벌어지는 일", "인물의 행동과 표정", "나레이션"]
FIELDS = ["직전 상태", "장소와 상황", "벌어지는 일", "인물의 행동과 표정", "겉모습·소지품·동행", "끝나는 상태", "나레이션"]


def stories(path):
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    out = []
    for block in re.split(r"^## ", text, flags=re.M)[1:]:
        head, _, rest = block.partition("\n")
        intro = re.search(r"소개:\s*(.*?)\n\s*본문:", rest, re.S)
        body = re.search(r"본문:\s*(.*?)(?:\n---|\Z)", rest, re.S)
        out.append({"title": head.strip(), "intro": intro.group(1).strip() if intro else "",
                    "body": body.group(1).strip() if body else ""})
    return out


def scenes(path):
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    out = []
    for m in re.finditer(r"^장면 (\d+):(.*?)$(.*?)(?=^장면 \d+:|^등장인물:|\Z)", text, re.M | re.S):
        f = {}
        for k in FIELDS:
            mm = re.search(rf"^{re.escape(k)}:\s*(.*)$", m.group(3), re.M)
            f[k] = mm.group(1).strip() if mm else ""
        out.append({"n": int(m.group(1)), "f": f})
    return out


def story_card(s):
    return (f'<article class="card"><h4>{e(s["title"])}</h4><p class="intro">{e(s["intro"])}</p>'
            f'<details><summary>본문</summary><p>{e(s["body"])}</p></details></article>')


def scene_card(s):
    rows = "".join(f'<dt>{e(k)}</dt><dd>{e(s["f"][k]).replace(" / ", "<br>")}</dd>' for k in SHOW if s["f"][k])
    return f'<article class="card"><h4>장면 {s["n"]}</h4><dl>{rows}</dl></article>'


def cols3(groups, fn):
    rows = []
    for i in range(max(len(g) for _, g, _ in groups)):
        cells = "".join(f'<div class="cell"><span class="tag {c}">{e(l)}</span>{fn(g[i]) if i < len(g) else ""}</div>'
                        for l, g, c in groups)
        rows.append(f'<div class="pair three">{cells}</div>')
    return "".join(rows)


GPT_STORY = RB / "new" / "20261001T162624-1fd56f-4" / "story.md"
GPT_SCENE = RB / "new" / "20261001T162624-1fd56f-4-scene25a" / "scene.md"
st = cols3([("gpt-5.1", stories(GPT_STORY), "a"),
            ("Qwen 3.8 27B (무료)", stories(HERE / "out/qwen/soi-dohyun/story.md"), "base"),
            ("Nemotron 3 Ultra 550B (무료)", stories(HERE / "out/nemotron/soi-dohyun/story.md"), "new")], story_card)
sc = cols3([("gpt-5.1", scenes(GPT_SCENE), "a"),
            ("Qwen 3.8 27B (무료)", scenes(HERE / "out/qwen/soi-dohyun-scene/scene.md"), "base"),
            ("Nemotron 3 Ultra 550B (무료)", scenes(HERE / "out/nemotron/soi-dohyun-scene/scene.md"), "new")], scene_card)
def runs_block(paths, parse, card, label):
    out = []
    for i, path in enumerate(paths, 1):
        items = parse(path)
        out.append(f'<h3 class="run">{label} {i}회차</h3><div class="grid2">' + "".join(card(x) for x in items) + "</div>")
    return "".join(out)


N = HERE / "out" / "nemotron"
nem_st = runs_block([N / "soi-dohyun/story.md"] + [N / f"soi-dohyun-{k}/story.md" for k in (2, 3, 4)], stories, story_card, "이야기")
nem_sc = runs_block([N / "soi-dohyun-scene/scene.md"] + [N / f"soi-dohyun-scene-{k}/scene.md" for k in (2, 3, 4)], scenes, scene_card, "장면")
vc_st = cols3([("Venice Uncensored 24B", stories(HERE / "out/venice/soi-dohyun/story.md"), "a"),
               ("Cydonia 24B V4.1", stories(HERE / "out/cydonia/soi-dohyun/story.md"), "base"),
               ("Nemotron 1회차 (참고)", stories(N / "soi-dohyun/story.md"), "new")], story_card)
vc_sc = cols3([("Venice Uncensored 24B", scenes(HERE / "out/venice/soi-dohyun-scene/scene.md"), "a"),
               ("Cydonia 24B V4.1", scenes(HERE / "out/cydonia/soi-dohyun-scene/scene.md"), "base"),
               ("Nemotron 1회차 (참고)", scenes(N / "soi-dohyun-scene/scene.md"), "new")], scene_card)
paid_st = '<div class="grid2">' + "".join(story_card(x) for x in stories(HERE / "out/nemotron-paid/soi-dohyun/story.md")) + "</div>"
A = HERE / "out"
def adult_block(label, path, skip=()):
    items = [x for i, x in enumerate(stories(path), 1) if i not in skip]
    note = f'<p class="lede">{len(skip)}개는 동의 없는 성적 장면이라 페이지에 싣지 않았다 — 원문 파일에 있다.</p>' if skip else ""
    return f'<h3 class="run">{label}</h3>{note}<div class="grid2">' + "".join(story_card(x) for x in items) + "</div>"
adult_raw = (adult_block("Aion 2.0", A / "aion2/soi-dohyun-adult/story.md")
             + adult_block("MiniMax M2-her", A / "minimax-her/soi-dohyun-adult/story.md")
             + adult_block("Hermes 4 405B (3·4번 제외)", A / "hermes4/soi-dohyun-adult/story.md", skip=(3, 4)))
adult_sc = '<div class="grid2">' + "".join(scene_card(x) for x in scenes(HERE / "out/aion2/box-adult-scene/scene.md")) + "</div>"
import base64
W = HERE / "out" / "images" / "web"
def img(name, cap):
    b = base64.b64encode((W / f"{name}.jpg").read_bytes()).decode()
    return f'<figure class="shot"><img src="data:image/jpeg;base64,{b}" alt="{e(cap)}"><figcaption>{e(cap)}</figcaption></figure>'
imgs = ('<div class="shots one">' + img("sheet", "소이 캐릭터 시트 (gpt-image-2, 제품 시트 단계)") + "</div>"
        + '<h3 class="run">A. 도윤 서비스 컷 — 상의 탈의</h3><div class="shots">'
        + img("grok-A-service", "Grok Imagine 2.0 — 17초") + img("seedream-A-service", "Seedream 4.5 — 29초") + img("qwen-A-service", "Qwen Image 3 — 45초 (셔츠를 입혔다)") + img("openai-A-service", "OpenAI gpt-image-2 (제품 모델) — 33초") + "</div>"
        + '<h3 class="run">B. 베드신 — 이불로 가린 노출</h3><div class="shots">'
        + img("grok-B-bed", "Grok Imagine 2.0 — 13초") + img("seedream-B-bed", "Seedream 4.5 — 36초") + img("qwen-B-bed", "Qwen Image 3 — 37초") + '<figure class="shot refused"><div class="refusal">거절<br><small>Your request was rejected by the safety system<br>(moderation_blocked, 3초)</small></div><figcaption>OpenAI gpt-image-2 (제품 모델)</figcaption></figure></div>')
refused = lambda title, why: f'<figure class="shot refused"><div class="refusal">거절<br><small>{e(why)}</small></div><figcaption>{e(title)}</figcaption></figure>'
grok2 = ('<h3 class="run">아까 그려진 베드신을 그대로 다시 — 2번 중 2번 그림</h3><div class="shots">'
         + img("grok-B-bed", "처음 (13초)") + img("grok-B-bed-retry", "다시 (16초)") + "</div>"
         + '<h3 class="run">수위 단계 — 셋 다 거절</h3><div class="shots">'
         + refused("① 상반신 노출 베드신", "xAI blocked this request through content moderation (14초)")
         + refused("② 성관계 암시 (결합 부위 가림)", "xAI blocked this request through content moderation (13초)")
         + refused("③ 노골적인 성행위", "xAI blocked this request through content moderation (17초)") + "</div>"
         + '<h3 class="run">웹툰 페이지 — 프롬프트가 너무 길다</h3><div class="shots">'
         + refused("장면 2 페이지 (제품 프롬프트 27,693자)", "Prompt length exceeds the maximum allowed length of 8000")
         + refused("장면 6 페이지 (제품 프롬프트 27,816자)", "Prompt length exceeds the maximum allowed length of 8000") + "</div>"
         + '<h3 class="run">인물 고정 — 같은 소이 시트로 일상 장면</h3><div class="shots">'
         + img("grok2-C1-subway", "출근길 지하철 — 15초") + img("grok2-C2-cafe", "점심시간 카페 — 15초") + "</div>")
page = (HERE / "page_template.html").read_text(encoding="utf-8").replace(
    "{{STYLE}}", (HERE / "style.part").read_text(encoding="utf-8")).replace("{{GROK2}}", grok2).replace("{{IMAGES}}", imgs).replace("{{ADULT_SCENE}}", adult_sc).replace("{{ADULT_RAW}}", adult_raw).replace("{{PAID_STORIES}}", paid_st).replace("{{VC_STORIES}}", vc_st).replace("{{VC_SCENES}}", vc_sc).replace("{{NEM_STORIES}}", nem_st).replace("{{NEM_SCENES}}", nem_sc).replace("{{STORIES}}", st).replace("{{SCENES}}", sc)
(HERE / "비교.html").write_text(page, encoding="utf-8")
print(HERE / "비교.html")
