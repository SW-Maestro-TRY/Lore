#!/usr/bin/env python3
"""캐릭터 그림 한 장 — 웹툰 한 편을 안 만들고 캐릭터만 만든다.

## 왜 따로 있나

`run.py --sheet` 는 **작품 폴더 안에서** 돈다. 고른 이야기가 있어야 하고,
결과는 그 작품의 캐릭터 시트다. 그런데 캐릭터 탭에서 하려는 것은 그 반대다 —
**이야기 없이 캐릭터부터** 만들어 두고, 나중에 그 캐릭터로 웹툰을 만든다.

그래서 같은 조각(사양 쓰기 · 그리기)을 쓰되 작품 없이 도는 길을 하나 둔다.
프롬프트도 모델 설정도 `run.py` 와 같은 것을 본다 — 두 벌이 되면 반드시
어긋난다.

## 두 갈래

    --photo <파일> [--photo <파일> ...]   사진(최대 4장)을 읽어 외모를 글로
                                          적고, 그 글로 그린다
    (사진 없음)                          이름과 설명만으로 적고 그린다
                                          ← 자캐 그림이 없는 사람의 길

여러 장을 주면 **한 사람의 여러 각도·표정**으로 보고 외모를 적는다 — 웹툰
만들기 쪽(nhApi 의 photos_data)과 같은 방식이다. 사진을 쓰더라도 **그림에는
사진을 안 붙인다.** OpenAI 는 참조 이미지가 붙으면 "이 그림을 고쳐라" 쪽으로
읽어서, 올린 사진이 낙서거나 화풍이 다르면 그것을 따라가느라 사양대로 안
그린다(run.py 의 stage_sheet 주석과 같은 이유).

## 세 번째 갈래 — 「캐릭터 만들어보기」 한 컷 (--panel)

    python character.py --panel --world romance_novel --out /어디/panel.png \
        [--name 몽이] [--description "..."] [--photo a.png]

사진·설명·이름이 **전부 없어도 된다.** 세계관(--world)은 story-harness 의
프리셋 키이거나 사람이 직접 쓴 한 줄이고, 그것도 없으면 프리셋에서 무작위로
고른다. 결과는 위 두 갈래의 "표지 같은 그림" 이 아니라 **그 세계관 웹툰의 한
컷**(세로 2:3, 글자 없음)과 카드 글(반전 한 줄 · 대사 · 운명 두세 줄)이다.
글자를 안 그리는 이유는 웹툰 페이지와 같다 — 말풍선은 화면이 얹는다.

넣은 것은 바꾸지 않는다. 사진 속 존재가 사람이 아니면 그 종 그대로 그
세계관의 자리를 맡는다(의인화 없음). 규칙은 `prompt/panel_prompt` 에 있다.

## 쓰는 법

    python character.py --name 차사 --description "택배 배달 저승사자" \
        --out /어디/에/그림.png [--photo /올린/사진.png [--photo /또/하나.png]] \
        [--style game]

끝나면 만든 것을 한 줄 JSON 으로 stdout 에 찍는다 — 부르는 쪽(스프링)이 이걸
읽는다. 진행 상황은 stderr 로 나간다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import imagegen                                      # noqa: E402
import imageprompt                                   # noqa: E402
import llm                                           # noqa: E402
import sheet as sheetmod                             # noqa: E402


def log(msg: str) -> None:
    """진행 상황은 stderr 로. stdout 은 결과 JSON 한 줄만 쓴다."""
    print(msg, file=sys.stderr, flush=True)


def load_prompt(name: str) -> str:
    return (HERE / "prompt" / name).read_text(encoding="utf-8")


def spec_of(name: str, description: str, photos: list[Path]) -> dict:
    """외모를 글로 적는다. 사진이 있으면 읽고, 없으면 설명만 본다.

    사진이 여러 장이면 같은 사람의 다른 각도·표정으로 보고 하나의 외모로
    합쳐 적는다 — 한 장만으로는 안 보이는 부분(옆모습·전신 옷차림 등)이
    다른 장에는 있을 수 있어서다.
    """
    lines = ["# 이번 입력", ""]
    if name.strip():
        lines.append(f"캐릭터 이름: {name.strip()}")
    else:
        lines.append("캐릭터 이름: (없음 — 설명에 어울리는 한국어 이름을 네가 짓는다)")
    if description.strip():
        lines += ["", "캐릭터 설명:", description.strip()]
    if photos:
        word = "첨부한 사진들을" if len(photos) > 1 else "첨부한 사진을"
        lines += ["", f"{word} 보고 외모를 적는다."]
        if len(photos) > 1:
            lines.append("여러 장이면 같은 사람의 다른 각도·표정이다 — 하나의 외모로 합쳐 적는다.")
    else:
        # **사진이 없다고 멈추지 않는다.** 이 길이 이 기능의 핵심이다 —
        # 자캐 그림이 없는 사람도 캐릭터를 가질 수 있어야 한다.
        lines += ["", "사진은 없다. 위 설명만 보고 외모를 정한다.",
                  "설명에 없는 것(머리색·눈색·옷·나이대 등)은 설명과 어울리게 네가 정한다."]
    prompt = load_prompt("sheet_prompt") + "\n\n---\n\n" + "\n".join(lines)

    call = llm.Call("SHEET")
    log(f"[캐릭터] {call.describe()} 로 외모를 적습니다…")
    images = llm.load_images([str(p) for p in photos]) if photos else None
    text, meta = call(prompt, images=images, temperature=0.4)
    spec = sheetmod.parse_spec(text)
    bad = sheetmod.gate_spec(spec)
    if bad:
        log("[캐릭터] 사양에 빠진 것: " + " · ".join(bad))
    return spec, meta


def portrait_prompt(spec: dict, style_text: str) -> str:
    """사양 -> **한 장짜리 그림** 프롬프트.

    자료 시트(`sheet.build_prompt`)를 안 쓴다. 그건 앞·옆·뒤와 표정 여섯 개를
    한 장에 늘어놓은 <b>작업용 참고 자료</b>라, 「내 캐릭터」 칸에 걸어 두면
    예쁘지가 않다. 여기서 필요한 것은 이 캐릭터를 한 장으로 보여주는
    <b>표지 같은 그림</b>이다.

    사양은 그대로 쓴다 — 사진이나 설명에서 뽑아낸 고정 요소(머리색·옷·소품)가
    거기 적혀 있고, 그게 있어야 나중에 웹툰을 만들 때 같은 인물로 읽힌다.
    """
    palette = spec["color_palette"]
    colors = " / ".join(f"{k}: {palette[k]}" for k in sheetmod.PALETTE_KEYS if palette.get(k))
    details = spec["design_details"]
    props = spec["props"]

    parts = [
        "Character portrait illustration for a Korean webtoon service — "
        "a single cover-quality picture of ONE character.",
        "",
        "[COMPOSITION]",
        "One character only. **Waist-up**, facing the viewer or slightly turned — "
        "close enough that the face and the upper body read clearly. Not a tiny "
        "full-body figure in a wide space.",
        "The face is clearly visible and is the centre of attention.",
        "A simple, attractive background that suits the character — soft light, a hint of "
        "place or mood. Not a plain white cutout, and not a busy scene that competes with "
        "the character.",
        "No text, no labels, no captions, no watermark, no logo, no signature.",
        "No panel borders, no split frames, no turnaround views, no expression rows — "
        "this is ONE picture, not a reference sheet.",
        "",
        "[CHARACTER]",
        f"  {spec['appearance_en']}",
    ]
    if spec.get("species"):
        parts.append(f"  종족: {spec['species']}")
    if details:
        parts.append("  고정 요소 (반드시 그대로 그린다):")
        parts += [f"    - {d}" for d in details]
    if props:
        parts.append("  지물: " + " / ".join(props))
    if colors:
        parts.append(f"  색: {colors}")

    parts += ["", "STYLE", style_text, ""]
    return "\n".join(parts)


# ---- 한 컷 (--panel) --------------------------------------------------------

WORLDS_FILE = HERE.parent / "story-harness" / "worlds.json"

# 그림체 후보. 사양을 쓰는 모델이 세계관을 보고 하나 고른다 — 세계관을 사람이
# 직접 쓸 수 있어서 코드의 고정 표로는 다 못 잇는다. 여기 적는 한 줄은
# "무엇에 맞는 그림체인가" 이지 그림체 문구 자체가 아니다(문구는 prompt/style/).
PANEL_STYLES = {
    "romance_fantasy": "로맨스 판타지 — 가는 선, 파스텔, 드레스와 궁정",
    "shoujo": "순정·BL — 고전 순정 만화의 결",
    "cinematic": "영화 같은 반실사 — 헌터·액션·스릴러·재난",
    "noir": "흑백 잉크 느와르 — 범죄·어둠",
    "pastel": "손그림 일상툰 — 일상·개그·소소한 이야기",
    "game": "모바일 게임 일러스트 — 게임 판타지·시스템",
    "webtoon_lock_bg": "표준 한국 웹툰 — 어디에나 무난한 기본",
}
DEFAULT_PANEL_STYLE = "webtoon_lock_bg"

# 글자를 안 그리게 하는 문구 — webtoon-harness/strip.py 의 NO_TEXT_AT_ALL_CLAUSE 와
# 같은 뜻이다(그쪽은 완성본 취급이라 import 대신 같은 문장을 둔다).
NO_TEXT_CLAUSE = (
    "NO TEXT ANYWHERE. Draw no speech balloons, no thought bubbles, no caption "
    "boxes, no sound-effect lettering and no writing of any kind in this panel — "
    "not in Korean, not in English, not as decoration. This panel is artwork "
    "only; every balloon and every word is composited on afterwards.")


def load_worlds() -> dict:
    try:
        return json.loads(WORLDS_FILE.read_text(encoding="utf-8")).get("presets") or {}
    except (OSError, ValueError):
        return {}


def resolve_world(world: str) -> tuple[str, str, str]:
    """--world -> (키 또는 "", 라벨, 설명). 프리셋 키면 그 프리셋, 아니면 사람이
    쓴 한 줄 그대로, 비었으면 프리셋에서 무작위."""
    presets = load_worlds()
    text = (world or "").strip()
    if text and text in presets:
        one = presets[text]
        return text, str(one.get("label") or text), str(one.get("text") or "")
    if text:
        return "", text, text
    if presets:
        import random
        key = random.choice(sorted(presets))
        one = presets[key]
        return key, str(one.get("label") or key), str(one.get("text") or "")
    return "", "", ""


def parse_panel_spec(text: str) -> dict:
    from llm import story
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("한 컷 사양이 JSON 객체가 아닙니다.")
    fate = obj.get("fate") or []
    if isinstance(fate, str):
        fate = [fate]
    style = str(obj.get("style") or "").strip()
    return {
        "name": str(obj.get("name") or "").strip(),
        "species": str(obj.get("species") or "사람").strip() or "사람",
        "species_en": str(obj.get("species_en") or "").strip(),
        "world_label": str(obj.get("world_label") or "").strip(),
        "genre_word": str(obj.get("genre_word") or "").strip(),
        "role": str(obj.get("role") or "").strip(),
        "twist": str(obj.get("twist") or "").strip(),
        "quote": str(obj.get("quote") or "").strip(),
        "fate": [str(f).strip() for f in fate if str(f or "").strip()][:3],
        "appearance_en": str(obj.get("appearance_en") or "").strip(),
        "scene_en": str(obj.get("scene_en") or "").strip(),
        "style": style if style in PANEL_STYLES else DEFAULT_PANEL_STYLE,
    }


def gate_panel_spec(spec: dict) -> list[str]:
    """그리기 전에 본다. 비면 그 자리를 모델이 평균값으로 채운다 — 반전이 없는
    한 컷은 돈만 쓰고 끝난다."""
    bad = []
    for key in ("name", "twist", "quote", "appearance_en", "scene_en"):
        if not spec[key]:
            bad.append(f"{key} 가 비어 있습니다.")
    if len(spec["fate"]) < 2:
        bad.append("fate 가 2줄 미만입니다.")
    for key in ("appearance_en", "scene_en"):
        if spec[key] and sheetmod.HANGUL_RE.search(spec[key]):
            bad.append(f"{key} 에 한글이 섞여 있습니다. 이미지 모델에 그대로 들어갑니다.")
    if spec["species"] != "사람":
        if not spec["species_en"]:
            bad.append("species 가 사람이 아닌데 species_en 이 없습니다.")
        elif spec["species_en"].lower() not in spec["appearance_en"].lower():
            bad.append("appearance_en 이 species_en 으로 시작하지 않습니다 — 사람으로 뭉개졌을 수 있습니다.")
    return bad


def panel_spec_of(name: str, description: str, photos: list[Path],
                  world_label: str, world_text: str) -> tuple[dict, dict]:
    lines = ["# 이번 입력", ""]
    lines.append(f"이름: {name.strip()}" if name.strip()
                 else "이름: (없음 — 네가 짓는다)")
    if description.strip():
        lines += ["", "설명:", description.strip()]
    else:
        lines += ["", "설명: (없음)"]
    if photos:
        word = "첨부한 사진들을" if len(photos) > 1 else "첨부한 사진을"
        lines += ["", f"{word} 보고 존재와 외모를 읽는다. 여러 장이면 같은 존재의 다른 각도·표정이다."]
    else:
        lines += ["", "사진: (없음)"]
    if world_label:
        lines += ["", f"세계관: {world_label}"]
        if world_text and world_text != world_label:
            lines.append(world_text)
    else:
        lines += ["", "세계관: (없음 — 네가 정한다)"]
    lines += ["", "그림체 목록:"]
    lines += [f"  - {k}: {v}" for k, v in PANEL_STYLES.items()]
    prompt = load_prompt("panel_prompt") + "\n\n---\n\n" + "\n".join(lines)

    call = llm.Call("SHEET")
    log(f"[한 컷] {call.describe()} 로 사양을 적습니다…")
    images = llm.load_images([str(p) for p in photos]) if photos else None
    # 사양이 모자라면 **그림을 그리기 전에** 한 번 더 묻는다. 그림 값(64원)이
    # 나간 뒤에 반전·대사가 비어 있으면 "카드 없는 카드" 가 된다 — 그림은 있는데
    # 공유 링크가 404 고 말풍선·운명 칸이 빈다. 두 번째도 모자라면 멈춘다.
    metas = []
    for attempt in (1, 2):
        text, meta = call(prompt, images=images, temperature=0.8 if attempt == 1 else 0.5)
        metas.append(meta)
        spec = parse_panel_spec(text)
        bad = gate_panel_spec(spec)
        if not bad:
            return spec, metas
        log(f"[한 컷] 사양에 빠진 것({attempt}/2): " + " · ".join(bad))
    raise SystemExit("한 컷 사양이 두 번 다 모자랍니다 — 그림은 그리지 않습니다.")


def panel_prompt(spec: dict, style_text: str) -> str:
    """사양 -> 웹툰 한 컷 프롬프트. 세로 한 장, 글자 없음."""
    parts = [
        "ONE panel of a Korean webtoon — a single vertical picture (portrait, 2:3), "
        "artwork only. Not a page of several panels, not a character sheet, not a cover.",
        "",
        "[SCENE]",
        f"  {spec['scene_en']}",
        "  One main character. The character is large in the frame and the face "
        "(or the head, if not human) reads clearly. The place and the character's "
        "position in this world must be visible in the picture itself.",
        "",
        "[CHARACTER]",
        f"  {spec['appearance_en']}",
    ]
    if spec["species"] != "사람" and spec["species_en"]:
        sp = spec["species_en"]
        parts += [
            f"  This character is a real {sp}, drawn as a {sp} with its actual body and "
            f"anatomy. Do NOT anthropomorphize: no human body, no standing on two legs "
            f"like a person, no human face. Small accessories that mark its role in this "
            f"world (a collar, a ribbon, a crest, a hat) may sit on its real body.",
        ]
    parts += ["", "STYLE", style_text, "", NO_TEXT_CLAUSE, ""]
    return "\n".join(parts)


def run_panel(args) -> int:
    photos = [p for p in args.photo if p.is_file()]
    for missing in set(args.photo) - set(photos):
        log(f"[한 컷] 사진이 없습니다: {missing} — 빼고 그립니다")

    world_key, world_label, world_text = resolve_world(args.world)
    if world_label:
        log(f"[한 컷] 세계관: {world_label}" + (f" ({world_key})" if world_key else ""))
    spec, spec_metas = panel_spec_of(args.name, args.description, photos,
                                     world_label, world_text)
    style_text = imageprompt.load_style(args.style or spec["style"])
    prompt = panel_prompt(spec, style_text)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    (args.out.parent / "panel_prompt.txt").write_text(prompt, encoding="utf-8")
    log(f"[한 컷] 그리는 중… ({spec['style']}) {spec['twist']}")
    # **세로 2:3 으로 그린다** — 웹툰 페이지와 같은 캔버스(imagegen.PAGE_KIND).
    # 카드에 웹툰 한 컷처럼 걸리는 그림이라 정사각 초상이 아니다.
    art_meta = imagegen.paint("SHEET_IMAGE", prompt, args.out, kind=imagegen.PAGE_KIND)
    log(f"  -> {args.out}")

    result = {
        "out": str(args.out),
        "name": args.name,
        "named": spec["name"],
        "species": spec["species"],
        "world": world_key,
        "world_label": spec["world_label"] or world_label,
        "genre": spec["genre_word"],
        "role": spec["role"],
        "twist": spec["twist"],
        "quote": spec["quote"],
        "fate": spec["fate"],
        "style": args.style or spec["style"],
        "source": "photo" if photos else "prompt",
        "calls": [*spec_metas, art_meta],
    }
    (args.out.parent / "panel.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="캐릭터 그림 한 장을 만든다")
    # **이름은 안 받아도 된다.** 안 주면 사양이 지어 준다 — 사람에게
    # 이름부터 물으면 "뭐라고 부르지" 에서 멈춘다.
    ap.add_argument("--name", default="")
    ap.add_argument("--description", default="")
    ap.add_argument("--photo", type=Path, action="append", default=[],
                    help="있으면 읽어서 외모를 적는다 — 여러 번 줄 수 있다(최대 4장)")
    ap.add_argument("--style", default=None, help="그림체. 안 주면 하네스 기본")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--panel", action="store_true",
                    help="「캐릭터 만들어보기」 — 그 세계관 웹툰의 한 컷과 카드 글")
    ap.add_argument("--world", default="",
                    help="--panel 일 때 세계관: story-harness 프리셋 키 또는 직접 쓴 한 줄. "
                         "비우면 프리셋에서 무작위")
    args = ap.parse_args()

    if args.panel:
        return run_panel(args)

    photos = [p for p in args.photo if p.is_file()]
    for missing in set(args.photo) - set(photos):
        log(f"[캐릭터] 사진이 없습니다: {missing} — 빼고 그립니다")

    spec, spec_meta = spec_of(args.name, args.description, photos)

    # **그림체는 이름이 아니라 문구를 넘긴다.** 받은 값이 그대로 STYLE 칸에
    # 실린다 — 이름을 넘기면 "romance" 다섯 글자가 그림체 설명 전부가 되고,
    # 모델에게 아무 말도 안 한 것과 같아진다(실측으로 그렇게 밋밋한 그림이
    # 한 장 나왔다).
    style_text = imageprompt.load_style(args.style)
    prompt = portrait_prompt(spec, style_text)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    log("[캐릭터] 그리는 중…")
    # **정사각으로 그린다.** 세로로 긴 웹툰 페이지 비율(2:3)로 그렸더니 카드에
    # 걸린 그림이 지나치게 길쭉했다. 캐릭터 한 장은 얼굴과 상반신이 보이면
    # 되는 것이라 정사각이 알맞다("details" 칸이 이미 1024x1024 다).
    art_meta = imagegen.paint("SHEET_IMAGE", prompt, args.out, kind="details")
    log(f"  -> {args.out}")

    # 부르는 쪽이 읽을 한 줄. 비용은 두 호출을 합쳐서 낸다.
    print(json.dumps({
        "out": str(args.out),
        # 사람이 이름을 안 적었으면 사양이 지은 것을 돌려준다.
        "named": (spec.get("name") or "").strip(),
        "name": args.name,
        "source": "photo" if photos else "prompt",
        "calls": [spec_meta, art_meta],
    }, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
