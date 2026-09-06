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

    --photo <파일>   사진을 읽어 외모를 글로 적고, 그 글로 그린다
    (사진 없음)      이름과 설명만으로 적고 그린다  ← 자캐 그림이 없는 사람의 길

사진을 쓰더라도 **그림에는 사진을 안 붙인다.** OpenAI 는 참조 이미지가 붙으면
"이 그림을 고쳐라" 쪽으로 읽어서, 올린 사진이 낙서거나 화풍이 다르면 그것을
따라가느라 사양대로 안 그린다(run.py 의 stage_sheet 주석과 같은 이유).

## 쓰는 법

    python character.py --name 차사 --description "택배 배달 저승사자" \
        --out /어디/에/그림.png [--photo /올린/사진.png] [--style game]

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
import llm                                           # noqa: E402
import sheet as sheetmod                             # noqa: E402


def log(msg: str) -> None:
    """진행 상황은 stderr 로. stdout 은 결과 JSON 한 줄만 쓴다."""
    print(msg, file=sys.stderr, flush=True)


def load_prompt(name: str) -> str:
    return (HERE / "prompt" / name).read_text(encoding="utf-8")


def spec_of(name: str, description: str, photo: Path | None) -> dict:
    """외모를 글로 적는다. 사진이 있으면 읽고, 없으면 설명만 본다."""
    lines = ["# 이번 입력", "", f"캐릭터 이름: {name}"]
    if description.strip():
        lines += ["", "캐릭터 설명:", description.strip()]
    if photo is not None:
        lines += ["", "첨부한 사진을 보고 외모를 적는다."]
    else:
        # **사진이 없다고 멈추지 않는다.** 이 길이 이 기능의 핵심이다 —
        # 자캐 그림이 없는 사람도 캐릭터를 가질 수 있어야 한다.
        lines += ["", "사진은 없다. 위 설명만 보고 외모를 정한다.",
                  "설명에 없는 것(머리색·눈색·옷·나이대 등)은 설명과 어울리게 네가 정한다."]
    prompt = load_prompt("sheet_prompt") + "\n\n---\n\n" + "\n".join(lines)

    call = llm.Call("SHEET")
    log(f"[캐릭터] {call.describe()} 로 외모를 적습니다…")
    images = llm.load_images([str(photo)]) if photo is not None else None
    text, meta = call(prompt, images=images, temperature=0.4)
    spec = sheetmod.parse_spec(text)
    bad = sheetmod.gate_spec(spec)
    if bad:
        log("[캐릭터] 사양에 빠진 것: " + " · ".join(bad))
    return spec, meta


def main() -> int:
    ap = argparse.ArgumentParser(description="캐릭터 그림 한 장을 만든다")
    ap.add_argument("--name", required=True)
    ap.add_argument("--description", default="")
    ap.add_argument("--photo", type=Path, default=None, help="있으면 읽어서 외모를 적는다")
    ap.add_argument("--style", default=None, help="그림체. 안 주면 하네스 기본")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    if args.photo is not None and not args.photo.is_file():
        log(f"[캐릭터] 사진이 없습니다: {args.photo} — 설명만으로 그립니다")
        args.photo = None

    spec, spec_meta = spec_of(args.name, args.description, args.photo)
    prompt = sheetmod.build_prompt(spec, args.style)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    log("[캐릭터] 그리는 중… (사진 없이 사양만)")
    art_meta = imagegen.paint("SHEET_IMAGE", prompt, args.out, kind=imagegen.SHEET_KIND)
    log(f"  -> {args.out}")

    # 부르는 쪽이 읽을 한 줄. 비용은 두 호출을 합쳐서 낸다.
    print(json.dumps({
        "out": str(args.out),
        "name": args.name,
        "source": "photo" if args.photo is not None else "prompt",
        "calls": [spec_meta, art_meta],
    }, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
