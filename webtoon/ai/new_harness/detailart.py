#!/usr/bin/env python3
"""디테일 -> 사건 그림 직행 실험 (컷 대본·콘티를 건너뛴다).

## 왜 있는가

지금 흐름은 씬 -> 디테일 -> 컷 대본 -> 콘티 -> 그림이다. 그런데 컷 대본
단계에서 대사·컷·카메라를 다 못박고 나면, 그림은 그것을 성실히 옮기기만
해서 결과가 평평해진다(실측). 반대로 **디테일을 그대로 그림 모델에게 주고
"이 장면을 웹툰으로 그려라" 하면 훨씬 살아 있는 화면이 나온다** — 대신
장면 하나에 내용이 다 몰려 산만해지거나, 장면별로 따로 그리면 앞뒤 그림이
안 이어진다.

**그래서 한 장이 되는 단위는 장면이 아니라 사건이다.** 장면 하나에는 사건이
여러 개 들어 있다 — "일어난다 / 시계를 본다 / 방을 나간다 / 마주친다 /
인사한다 / 아침을 차린다 / 질문을 받는다" 가 한 장면이었다. 이것을 한 장에
다 그리게 하면 산만해지고, 그렇다고 컷을 하나씩 지정하면 연출을 사람이 다
짜는 것이 된다. 사건에서 끊으면 그림 모델이 사건 하나를 받아 **컷 수·구도·
여백·대사를 스스로 정한다.** 사건을 어떻게 나누고 사건 사이를 어떻게 잇는지는
구체화 단계가 정한다(prompt/detail_prompt, `scenes[].events[]`).

사건 칸이 없는 옛 run 은 장면 하나가 사건 하나로 읽혀(`pages.detail_events`)
예전처럼 장면당 한 장이 나온다.

그 둘을 같이 잡아 보려는 실험이다. 이어짐은 **글로 다 넣는 대신** 세 가지로
붙든다.

1. 고정 앵커 — 그림체·캐릭터 시트는 장면마다 **글자까지 같은 것**이 들어간다.
2. 직전 상태 콜백 — 직전 장면이 "어떻게 끝났는지" 한 줄만 넘긴다.
   (앞 내용을 통째로 다시 주면 다시 산만해진다)
3. 직전 그림 자체를 참조로 — 텍스트로는 못 잡는 조명·각도·인상을 잇는다.
   pageart.draw 가 페이지를 이어 그릴 때 쓰는 방식과 같다.

**첫 장면은 직전 그림이 없으므로 대신 제목을 준다** — 표지처럼 쓸 수 있게.

## 쓰는 법

    python detail_image_test.py --run-id <id> --dry-run   # 프롬프트만 (무료)
    python detail_image_test.py --run-id <id>             # 실제 생성 (과금)
    python detail_image_test.py --run-id <id> --only 2 3  # 그 장면만

결과는 run 폴더의 `detail_images/` 아래에만 쓴다 — 기존 파이프라인의
`pages/`·`board.json` 은 건드리지 않는다. 실험이라서 확정된 것이 아니다.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import imagegen
import imageprompt
import llm
import charcard
import pagecheck
import pages
import runmeta

HERE = Path(__file__).resolve().parent
# 작품이 쌓이는 자리.
#
# **환경변수로 덮을 수 있어야 한다.** 서버(webtoon/be)는 이 하네스를 jar 에서
# 임시 폴더로 풀어서 돌리는데, 그 폴더는 서버가 뜰 때마다 새로 생긴다. 그래서
# 여기가 `HERE/"runs"` 로 고정되면 **재시작하는 순간 만든 작품이 통째로
# 사라지고, 진행 중이던 작업도 이어받을 수 없다**(2026-09-12에 실제로 겪었다 —
# 그리는 도중 서버를 다시 띄우자 그 작업이 영원히 "running" 으로 남았다).
#
# 서버는 NH_RUNS_DIR 로 고정 경로를 넘긴다. 사람이 직접 돌릴 때는 안 주므로
# 예전처럼 하네스 옆 runs/ 를 쓴다.
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))
PROMPT_DIR = HERE / "prompt"
STAGE = "PAGE_IMAGE"
PAGE_DIR = "pages"


def log(msg: str) -> None:
    print(msg, flush=True)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def record(run_dir: Path, call_meta: dict) -> None:
    """호출 하나(성공·실패 상관없이)를 run.py 의 meta.json 에 남긴다.

    run.py 의 record() 와 같은 파일·같은 모양이다 — 나중에 비용을 정산할
    때 이 run이 어느 스크립트로 그렸는지 신경 쓸 필요가 없게 한다.

    쓰는 것은 runmeta 가 한다(파일 잠금) — 장면을 동시에 그리면 프로세스
    여럿이 같은 meta.json 에 쓴다.
    """
    runmeta.append_call(run_dir, call_meta)
    cost = call_meta.get("cost") or {}
    tag = "실패" if call_meta.get("error") else "완료"
    log(f"  [{tag}] {call_meta.get('stage')}  {call_meta.get('provider')}:"
        f"{call_meta.get('model')}  ${cost.get('total', 0):.4f}"
        + (f"  — {call_meta['error']}" if call_meta.get("error") else ""))


def cast_of(detail: dict, run_dir: Path) -> list[dict]:
    """조연 외모. 디테일이 적어 줬으면 그것, 없으면 옛 run 을 위해 콘티에서.

    주인공은 시트가 있어서 매번 같은 사람으로 나오지만 조연은 지정이 없으면
    장면마다 다른 사람이 그려진다 — 그래서 이것도 고정 앵커에 넣는다.
    """
    got = detail.get("cast")
    if not got:
        board = read_json(run_dir / "board.json") or {}
        got = board.get("cast") or []
    # 주인공은 시트로 이미 고정돼 있다 — cast 에 또 있으면 같은 사람을 두 번
    # 적게 되고, 두 설명이 조금이라도 다르면 그림이 흔들린다.
    hero = ((read_json(run_dir / "input.json") or {}).get("name") or "").strip()
    return [c for c in got
            if isinstance(c, dict) and (c.get("name") or "").strip()
            and (c.get("name") or "").strip() != hero]


def character_block(char: dict | None, spec: dict | None, cast: list[dict]) -> str:
    """장면마다 **글자까지 같게** 들어가는 인물 고정 앵커."""
    lines = []
    if spec:
        lines.append(imageprompt.sheet_line(spec))
    elif char:
        who = (char.get("name") or "").strip()
        desc = (char.get("description") or "").strip()
        # 고른 캐릭터 카드(#458)의 종·세계를 앞에 둔다 — 원래 설명만 보면
        # 카드가 바꿔 놓은 종(사람 → 검은여우)을 놓치고 사람으로 그린다.
        card = charcard.short(char.get("card") or {})
        desc = f"{card}. {desc}" if card and desc else (card or desc)
        lines.append(f"{who} — {desc}" if desc else who)
        for k, v in (char.get("fields") or {}).items():
            lines.append(f"- {k}: {v}")
    for one in cast:
        lines.append(f"{one['name']} — {(one.get('appearance') or '').strip()}")
    lines = [ln for ln in lines if ln and ln.strip()]
    if not lines:
        return ""
    return ("## 인물 (외모는 장면이 바뀌어도 그대로다)\n"
            "주인공은 첨부한 시트를 그대로 따른다. 아래 인물은 이 화 내내 같은 사람으로 그린다.\n"
            + "\n".join(lines))


def build_cover_prompt(*, title: str, genre: str, plot: str, first: dict,
                       char: dict | None, spec: dict | None, cast: list[dict],
                       provider: str, style: str) -> str:
    """표지 한 장. 컷을 나누지 않는 **한 장짜리 그림**이라 지시가 다르다."""
    blocks = [imageprompt.load_fixed_block(provider, style)]
    blocks.append("""\
## 이 그림은 표지다 (중요 — 위의 '페이지 구성'보다 이 절이 우선한다)

컷을 나누지 않는다. **한 장짜리 그림 하나**를 그린다. 말풍선·나레이션 상자·
효과음도 넣지 않는다.

- 이 화를 아직 안 읽은 사람이 보고 "무슨 이야기지?" 하고 눌러 보고 싶어지는
  그림이어야 한다.
- 주인공이 어떤 처지에 있는 사람인지, 여기가 어떤 세계인지가 한 장에서
  읽혀야 한다. 이 화의 특정 사건을 설명하지는 않는다.
- 제목을 그림 안에 글자로 넣는다. 인물의 얼굴을 가리지 않는 자리에 두고,
  아래 적힌 제목을 **글자 그대로** 쓴다.""")

    who = character_block(char, spec, cast)
    if who:
        blocks.append(who)

    lines = ["## 이 작품", f"제목(이 글자 그대로 그린다): {title}" if title else ""]
    if genre:
        lines.append(f"장르: {genre}")
    if plot:
        lines += ["", "줄거리:", plot]
    if first.get("detail"):
        lines += ["", "이 화가 시작되는 자리(분위기 참고용 — 이 장면을 그대로 "
                      "그리는 것이 아니다):", first["detail"]]
    blocks.append("\n".join(x for x in lines if x))
    return "\n\n".join(b for b in blocks if b) + "\n"


def page_path(run_dir: Path, page_no: int) -> Path:
    """페이지 번호는 1부터다 — **1 이 표지**, 2 부터가 장면이다.

    기존 콘티 흐름과 **같은 이름·같은 자리**에 쓴다(pageart.page_path 와 동일).
    그래야 둘러보기·편집실·굽기가 이 결과를 그대로 읽는다 — 제품 쪽 코드를
    한 줄도 안 고치고 붙이려는 것이 이 규칙의 이유다.
    """
    return run_dir / PAGE_DIR / f"page{page_no:02d}.png"


def scene_context() -> str:
    """그림 프롬프트에 장면을 몇 개 주는가. `one`(기본) 또는 `all`.

    **기본값을 `one` 으로 바꿨다(2026-09-19).** 장면 전체를 다 주던 `all` 은
    페이지마다 이야기가 도입부로 되돌아가는 문제가 실측으로 확인됐다 — 같은
    이야기·같은 캐릭터 시트로 `all` 과 `one` 을 제품과 같은 조건(자바처럼
    페이지 4장을 프로세스 4개로 동시에 그림)에서 견줘서, 전체 검수의 `opens`
    판정(이 장 나레이션 첫 줄이 앞에 이미 나왔는가)이 `all` 에서는 뒤 세 장
    전부 "이미"(되돌아감)로 나왔고 `one` 에서는 전부 "처음"으로 바뀌는 것을
    확인했다(run 20260919T022231-383b4b vs -concurrent, 비교 결과는
    `webtoon/ai/work/compare-scene-context/`).

    **기본값은 코드에 둔다** — `.env` 는 jar 에 안 실려서 서버에서는
    통째로 사라진다(webtoon/CLAUDE.md). `NH_SCENE_CONTEXT=all` 을 주면
    예전 방식(장면 전체)으로 되돌릴 수 있다 — 되돌릴 일이 생기면 여기를
    다시 볼 것.
    """
    want = (llm.env("NH_SCENE_CONTEXT") or "").strip().lower()
    return "all" if want == "all" else "one"


def opens_at(scenes: list[dict], scene_no: int) -> str:
    """장면 `scene_no` 가 시작하는 자리 — 그 장면 자신의 「직전 상태」.

    1번 장면은 「직전 상태」가 "없음" 이라, 대신 그 장면 자체의 장소·상황을
    쓴다(scenelink.opens_at 과 같은 역할, scenes.json 형태에 맞춘 것).
    """
    scene = scenes[scene_no - 1] if 0 < scene_no <= len(scenes) else {}
    opens = (scene.get("prev") or "").strip()
    if opens and opens not in ("없음", "없음."):
        return opens
    return " — ".join(x for x in (scene.get("where"), scene.get("what")) if x)


def build_continue_prompt(direction: dict, scenes: list[dict], char: dict | None,
                          spec: dict | None, cast: list[dict], *, scene_no: int,
                          has_prev: bool) -> str:
    """scenes.json(scene_prompt 의 산출물)만으로 씬 하나를 그린다.

    씬 하나 = 이미지 하나. 각 장면 dict 에 이미 「직전 상태」·「끝나는 상태」가
    있다(scene_prompt 가 선택된 스토리를 장면으로 쪼갤 때 같이 정해 둔다) —
    그래서 이 함수는 그것을 그대로 읽기만 한다. 앞 장 그림이 없어도(장면을
    동시에 그려도) 이 두 지점이 이어짐을 대신한다.

    {style} 자리는 호출부(draw_continue)가 채운다.
    """
    path = PROMPT_DIR / "detail_image_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8")

    title = (direction.get("title") or "").strip()
    genre = (direction.get("genre") or "").strip()

    lines = ["## 이 화 전체 줄거리 (지금 그릴 자리는 아래 「장면 내용」이 정한다)", ""]
    if title or genre:
        lines.append(f"[작품] {title}" + (f" · {genre}" if genre else ""))
    if scenes and scene_context() == "one":
        # 지금 그릴 장면 하나만 준다. 앞뒤를 안 보여주면 그림 모델이 이
        # 장면을 **화 전체의 요약**으로 그리지 않고 그 순간만 그리는지
        # 보려고 둔 자리다(2026-09-19 비교 실험). 기본값은 아니다 —
        # 켜려면 `.env` 에 `NH_SCENE_CONTEXT=one`.
        one = scenes[scene_no - 1] if 0 < scene_no <= len(scenes) else {}
        lines += ["", "[이 장에서 그릴 장면]", (one.get("what") or "").strip(),
                  "", "앞뒤 장면은 주지 않는다. 이 한 순간만 그린다 — 화 전체를 "
                  "요약하거나 앞에서 이미 지나온 상황을 다시 설명하지 않는다."]
        if scene_no > 1:
            # 나레이션 이어쓰기. 지금 나오는 것이 장마다 도입부로 되돌아가서,
            # 독자가 같은 설명을 네 번 읽는다(2026-09-19 전체 검수가 3·4·5
            # 페이지를 그렇게 잡았다). 무엇을 쓸지는 안 정해 준다 — 어디서부터
            # 쓰는지만 못 박는다.
            lines += ["", "[나레이션] 앞 장이 이미 설명한 것을 다시 설명하지 "
                      "않는다. 독자는 앞 장을 읽고 여기로 왔다 — 상황을 다시 "
                      "깔지 말고, 앞 장 마지막 줄 다음에서 이어 쓴다. 이 장에서 "
                      "처음 알게 되는 것만 적는다. 무슨 말을 쓸지는 네가 정한다."]
    elif scenes:
        lines += ["", "[장면들 — 순서대로 일어나는 사건들이다]"]
        for i, s in enumerate(scenes, 1):
            mark = " ← 이 장에서 그릴 자리" if i == scene_no else ""
            summary = s.get("what") or ""
            lines.append(f"{i}. {summary}{mark}")
    con = "\n".join(lines)

    scene = scenes[scene_no - 1] if 0 < scene_no <= len(scenes) else {}
    first = scene_no == 1
    last = scene_no == len(scenes)
    ends = (scene.get("ends") or "").strip()
    opens = opens_at(scenes, scene_no)

    # 이 장면이 화 전체에서 어느 자리인지 — 프롬프트 맨 위, 실제 장면
    # 데이터 바로 옆에 짧게 박는다. 같은 말이 파일 앞쪽(4-1 등)에도
    # 있지만, 실측으로 확인된 문제는 그 설명이 실제 데이터와 너무 멀리
    # 떨어져 있으면 지켜지지 않는다는 것이었다 — 그래서 여기서 한 번
    # 더 짧게 못박는다.
    if first:
        role = ("이 화의 첫 장면 — 독자가 이 작품을 처음 여는 순간이다. "
                "인물의 이름·장소·상황을 나레이션·대사·시각 단서 중 하나로 "
                "반드시 드러낸다.")
    elif last:
        role = "이 화의 마지막 장면 — 지금 장면을 마무리하며, 다음 화가 궁금해지는 여운으로 끝낸다."
    else:
        role = "중간 장면 — 앞 장면에서 자연스럽게 이어받아 진행한다."

    lines = [f"[이 페이지의 역할] {role}", "",
             (f"위 목록의 {scene_no}번 장면 자리를 그린다: "
              if scene_context() == "all" else "이 장면을 그린다: ")
             + f"\"{scene.get('what', '')}\"", ""]
    if scene.get("where"):
        lines += [f"[장소와 상황] {scene['where']}", ""]
    if scene.get("acting"):
        lines += [f"[인물의 행동과 표정] {scene['acting']}", ""]
    lines += [
        (f"[이 화가 열리는 자리 — 여기서부터 그린다] {opens}" if first else
         f"[여기서부터 그린다 — 앞 장이 끝난 자리다] {opens}"),
        "",
        f"[여기서 끝낸다 — 다음 장이 이어받을 자리다] {ends}",
        "",
        "**다른 장면들은 지금 동시에 그려지고 있다.** 위 두 지점이 앞뒤 장과 "
        "맞물리는 자리다 — 옆 장의 그림을 보고 맞추는 것이 아니라 이 글에 "
        "맞춘다.",
        "",
        ("- 「여기서부터」가 이 화의 첫 장면이다. 여기부터 그린다."
         if first else
         "- 「여기서부터」는 앞 장이 이미 그린 순간이다. 그 순간을 다시 그리지 "
         "말고, 거기서 곧바로 이어지는 다음 순간부터 그린다."),
        "- 「여기서 끝낸다」는 이 장의 마지막이다. 그 지점이 화면에 나오는 "
        "데서 끊는다. 더 나아가면 다음 장과 같은 순간을 두 번 그리게 된다.",
        "- 그 사이를 컷 몇 개로 어떻게 보여줄지, 무슨 대사를 넣을지는 전부 "
        "**네가 정한다.**",
    ]
    scene_instr = "\n".join(lines)
    if has_prev:
        scene_instr += ("\n\n첨부한 직전 그림이 바로 앞 장이다 — 인물·공간·"
                        "시간대·조명이 뚝 끊기지 않게 참고한다. 이야기가 어디서 "
                        "시작해 어디서 끝나는지는 위 두 지점이 정한다.")
    return (text
            .replace("{people}", character_block(char, spec, cast))
            .replace("{continuity}", con)
            .replace("{scene}", scene_instr))


def note_block(note: str) -> str:
    """편집실에서 사람이 적은 것 -> 그림 프롬프트 뒤에 붙는 문단.

    **연출을 지시로 바꾸지 않는다.** 사람이 "컷을 더 붙여라" 라고 적으면
    그건 따르되, 안 적은 것까지 이 문단 때문에 굳으면 안 된다 — 컷 대본
    단계를 없앤 이유가 그것이다. 그래서 적힌 것만 반영하라고 못 박는다.
    """
    return ("## 다시 그린다 — 이 장을 본 사람이 남긴 말\n\n"
            "이 장은 이미 한 번 그려졌고, 그것을 본 사람이 아래를 적었다. "
            "**같은 장면을 다시 그리되 아래를 반영한다.**\n\n"
            f"{note.strip()}\n\n"
            "- 위에 적힌 것만 바꾼다. 적히지 않은 것(컷 수·구도·카메라·여백·"
            "대사)은 **네가 그대로 다시 정한다** — 앞서 그린 것을 따라 할 "
            "필요도, 피할 필요도 없다.\n"
            "- 적힌 말이 이야기와 부딪히면 이야기를 따른다.\n")


def draw_continue(run_dir: Path, dry_run: bool = False, only=None,
                  allow_no_sheet: bool = False, on_page=None,
                  review: bool | None = None, note: str = "") -> list[dict]:
    """이어그리기 — **지금의 최종 방식.** 구체화(detail.json)·콘티(board.json)·
    컷 대본을 전부 건너뛰고, story 단계(방향 후보) 산출물만으로 그린다.

    표지가 1페이지, 씬이 2페이지부터다 — draw()·pageart.draw 와 같은 자리
    (`pages/pageNN.png`)에 쓴다. 씬 하나 = 이미지 하나로 고정한다(모델이
    알아서 이어 그리게 뒀던 첫 버전은 순서를 안 지키고 뒤 장면으로 건너뛰는
    문제가 실측으로 나왔다). 이어짐은 직전 그림을 참조로 붙이는 것과, 매
    호출에 전체 줄거리를 같이 주는 것 둘로만 잡는다 — 씬 사이 상태를 글로
    미리 요약해 두는 구체화 단계가 없다.

    on_page(meta) : 한 장이 끝날 때마다(성공이든 실패든) 바로 부른다 — 중간에
    죽어도 이미 나간 돈과, 실패였다면 그 사유까지 meta.json 에 남는다.
    검수 호출도 같은 자리로 넘긴다.

    review : 한 장을 그릴 때마다 **그린 것을 검수**한다(pagecheck). 직전 장과
    지금 장 두 그림을 이야기와 함께 모델에게 주고 "이어지는가" 를 묻는다.
    `이어짐` 이 아니거나 critical 이 나오면 그 장을 지우고 한 번 다시 그린다.
    None 이면 `.env`(`NH_PAGE_REVIEW`, 기본 켜짐)를 따른다.

    note : 편집실에서 사람이 적어 보낸 것(다시 그리기). 그리는 프롬프트
    **뒤**에 붙는다 — 모델은 뒤에 온 것을 더 세게 듣는다. 검수가 붙이는
    글(pagecheck.redraw_block)과 같은 자리이고, 둘 다 있으면 사람 말이 뒤에
    온다: 검수는 이야기가 어긋난 것을 잡는 장치이고, 사람은 그 판정까지
    보고 나서 다시 그리라고 한 것이라 사람 쪽이 더 최근이다.

    검수를 그리기 **전**이 아니라 **후**에 두는 이유는, 컷을 미리 정해 주는
    순간 연출이 지시를 옮기기만 해서 평평해지기 때문이다 — 그래서 자유롭게
    그리게 두고, 나온 그림이 이야기와 어긋났을 때만 되돌린다.
    """
    pick = read_json(run_dir / "pick.json") or {}
    directions = read_json(run_dir / "directions.json") or []
    n = pick.get("n")
    direction = next((d for d in directions if d.get("n") == n), None) or (directions[0] if directions else {})
    if not direction:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다. 이야기 단계를 먼저 돌리세요.")

    # 장면 -- scene_prompt 가 고른 방향을 쪼개 만든 scenes.json 에서 읽는다
    # (직전 상태·끝나는 상태가 장면마다 이미 있다 — run.py --scenes 로 만든다).
    scene_data = read_json(run_dir / "scenes.json") or {}
    scenes = [s for s in (scene_data.get("scenes") or []) if isinstance(s, dict)]
    if not scenes:
        raise SystemExit(f"{run_dir / 'scenes.json'} 가 없습니다. "
                        "run.py --pick <번호> --scenes 를 먼저 돌리세요.")

    char = read_json(run_dir / "input.json")
    spec = read_json(run_dir / "sheet_spec.json")
    hero = (char.get("name") or "").strip() if char else ""
    # cast — scene_prompt 가 장면을 쪼개면서 직접 뽑는다(scenes.json 의
    # "등장인물"). 옛 run(story_prompt 가 등장인물을 직접 뽑던 시절)을 위해
    # direction['cast']·board.json 도 순서대로 봐준다.
    cast = [c for c in (scene_data.get("cast") or [])
           if isinstance(c, dict) and (c.get("name") or "").strip()
           and (c.get("name") or "").strip() != hero]
    if not cast:
        cast = [c for c in (direction.get("cast") or [])
               if isinstance(c, dict) and (c.get("name") or "").strip()
               and (c.get("name") or "").strip() != hero]
    if not cast:
        board = read_json(run_dir / "board.json") or {}
        cast = [c for c in (board.get("cast") or [])
               if isinstance(c, dict) and (c.get("name") or "").strip()
               and (c.get("name") or "").strip() != hero]

    sheet = run_dir / "sheet.png"
    if not sheet.exists() and not dry_run and not allow_no_sheet:
        raise SystemExit(
            f"캐릭터 시트가 없습니다: {sheet}\n"
            "        run.py --sheet 로 만들거나, 정말 없이 그리려면 --no-sheet 를 붙이세요.")
    refs_base = [sheet] if sheet.exists() else []

    # dry-run 은 키가 없어도 돌아야 한다 — backend_for() 는 키 없으면
    # SystemExit 이라, 진짜 생성일 때만 부른다.
    if dry_run:
        provider, model, quality = llm.provider_for(STAGE), "", ""
    else:
        provider, model, quality = imagegen.backend_for(STAGE)
    style = (llm.env("NH_STYLE") or llm.env("PAGE_STYLE") or imageprompt.DEFAULT_STYLE)
    dest = run_dir / PAGE_DIR
    dest.mkdir(parents=True, exist_ok=True)
    do_review = pagecheck.enabled() if review is None else bool(review)
    tries = pagecheck.max_redraw() if do_review else 0
    log(f"[이어그리기] 표지 1장 + 장면 {len(scenes)}개 · 그림체 {style} · {provider}"
        + (f":{model}" if model else "")
        + " · 이음새 있음(동시에 그려도 이어진다)"
        + (f" · 검수 켜짐(다시 그리기 {tries}회)" if do_review else " · 검수 꺼짐"))

    title, genre = direction.get("title") or "", direction.get("genre") or ""
    plot = scene_data.get("plot") or direction.get("plot") or ""
    first_detail = " — ".join(x for x in (scenes[0].get("where"), scenes[0].get("what")) if x)

    made = []
    for n_ in range(0, len(scenes) + 1):  # 0 = 표지, 1..len(scenes) = 씬
        page_no = n_ + 1
        if n_ == 0:
            prompt = build_cover_prompt(title=title, genre=genre, plot=plot,
                                        first={"detail": first_detail}, char=char, spec=spec,
                                        cast=cast, provider=provider, style=style)
        else:
            # 직전 그림이 **실제로 있는지**를 본다. 차례로 그릴 때는 늘 있지만,
            # 장면을 동시에 그리면 옆 장이 아직 안 끝나 없을 수 있다 — 그때
            # "첨부한 직전 그림" 이라고 적으면 없는 그림을 가리키게 된다.
            has_prev = page_path(run_dir, page_no - 1).exists()
            prompt = (build_continue_prompt(direction, scenes, char, spec, cast,
                                            scene_no=n_, has_prev=has_prev)
                      .replace("{style}", imageprompt.load_style(style)))
        # 사람이 적어 보낸 것은 **맨 뒤**에 붙인다 — 모델은 뒤에 온 것을 더
        # 세게 듣는다. 그리라고 준 장면을 바꾸는 것이 아니라, 같은 장면을
        # 그 사람 말대로 다시 그리는 것이다.
        if note.strip():
            prompt += "\n\n" + note_block(note)
        # 프롬프트는 **그릴 장의 것만 쓴다.** 장면을 동시에 그리면 프로세스마다
        # 이 반복문을 다 도는데, 자기가 안 그릴 장의 파일까지 쓰면 여럿이 같은
        # 파일에 동시에 써서 반 토막이 남는다.
        if only and page_no not in only:
            continue
        (dest / f"page{page_no:02d}.txt").write_text(prompt, encoding="utf-8")
        if dry_run:
            continue

        out = page_path(run_dir, page_no)
        label = "표지" if n_ == 0 else f"장면 {n_}/{len(scenes)}"
        if out.exists():
            log(f"  {label}: 이미 있습니다 (다시 그리려면 지우세요)")
            continue

        prev_img = page_path(run_dir, page_no - 1)
        refs = refs_base + ([prev_img] if page_no > 1 and prev_img.exists() else [])
        log(f"[{label}] 참조 {len(refs)}장 …")
        try:
            meta = imagegen.paint(STAGE, prompt, out, refs=refs, kind=imagegen.PAGE_KIND)
        except Exception as exc:                                     # noqa: BLE001
            # 실패해도 무엇에 얼마나 썼는지는 남겨야 나중에 비용을 정산할 수
            # 있다 — 성공 때와 같은 모양(stage·provider·model·cost)에 error 만
            # 더해서 on_page 로 넘긴다. 이어그리기는 직전 이미지가 있어야
            # 다음 장을 그릴 수 있으므로, 실패하면 여기서 멈춘다.
            err_meta = {"stage": STAGE, "provider": provider, "model": model,
                       "quality": quality, "page": page_no, "scene": n_,
                       "refs": [r.name for r in refs],
                       "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                                "cache_write": 0.0, "total": 0.0},
                       "error": f"{type(exc).__name__}: {exc}",
                       "output_path": str(out)}
            if on_page:
                on_page(err_meta)
            log(f"  실패 [{label}]: {err_meta['error']}")
            raise
        meta["page"] = page_no
        meta["scene"] = n_
        made.append(meta)
        if on_page:
            on_page(meta)
        log(f"  -> {out}  (${meta['cost'].get('total', 0):.4f})")

        # 표지는 이야기의 한 순간이 아니라 검수 대상이 아니다.
        if not do_review or n_ == 0:
            continue
        # 검수에게 "앞 장이 어디까지 갔는지" 를 알려 주는 자리. scenes.json 에
        # 이미 정해져 있는 값이라, 앞 장 검수를 기다리지 않아도 되고 장면을
        # 동시에 그려도 검수가 같은 기준으로 본다.
        prev_from, last = opens_at(scenes, n_), None
        for attempt in range(tries + 1):
            got, rmeta = pagecheck.review_page(
                run_dir, page_no, scene_no=n_, direction=direction,
                char=char, cast=cast, prev_is_cover=(n_ == 1),
                next_from=prev_from,
                suffix="" if attempt == 0 else f".{attempt + 1}",
                # 그림이 보고 그린 것과 **같은 장면**을 검수에게 준다.
                # direction 만 넘기던 때는 검수 쪽 장면 목록이 통째로
                # 비었다(pagecheck.scene_texts 참고).
                scenes=scenes)
            if rmeta and on_page:
                on_page(rmeta)
            if not got:
                # 검수를 못 했다. 그림은 이미 값을 치렀으니 그대로 둔다 —
                # 판정이 없는 것을 통과로 여겨 다음 장으로 넘어간다.
                break
            last = got
            if got["verdict"] == "통과":
                break
            if attempt >= tries:
                log(f"  [검수] 다시 그릴 횟수를 다 썼습니다 — 이 장은 그대로 둡니다")
                break
            log(f"  [검수] 다시 그립니다 ({attempt + 1}/{tries})")
            out.unlink(missing_ok=True)
            again = prompt + "\n\n" + pagecheck.redraw_block(got)
            (dest / f"page{page_no:02d}.redraw{attempt + 1}.txt").write_text(
                again, encoding="utf-8")
            try:
                meta = imagegen.paint(STAGE, again, out, refs=refs,
                                      kind=imagegen.PAGE_KIND)
            except Exception as exc:                                 # noqa: BLE001
                err_meta = {"stage": STAGE, "provider": provider, "model": model,
                           "quality": quality, "page": page_no, "scene": n_,
                           "redraw": attempt + 1,
                           "refs": [r.name for r in refs],
                           "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                                    "cache_write": 0.0, "total": 0.0},
                           "error": f"{type(exc).__name__}: {exc}",
                           "output_path": str(out)}
                if on_page:
                    on_page(err_meta)
                log(f"  실패 [{label} 다시 그리기]: {err_meta['error']}")
                raise
            meta["page"], meta["scene"], meta["redraw"] = page_no, n_, attempt + 1
            made.append(meta)
            if on_page:
                on_page(meta)
            log(f"  -> {out}  (${meta['cost'].get('total', 0):.4f})")

    if dry_run:
        log(f"[이어그리기] 프롬프트만 썼습니다 -> {dest}")
    elif made:
        total = sum(m["cost"].get("total", 0) for m in made)
        log(f"끝났습니다 — {len(made)}장 · ${total:.4f} -> {dest}")
    return made


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True, help="detail.json 이 있는 run")
    p.add_argument("--only", type=int, nargs="*", default=[],
                   help="이 페이지 번호만 (1 이 표지)")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    p.add_argument("--no-sheet", action="store_true",
                   help="캐릭터 시트 없이 진행 (인물이 장면마다 달라진다)")
    p.add_argument("--no-review", action="store_true",
                   help="그린 뒤 검수를 하지 않는다 (기본은 켜짐 — .env 의 "
                        "NH_PAGE_REVIEW=0 과 같다)")
    args = p.parse_args(argv)
    # on_page 를 안 넘기면 이 스크립트로 그린 판은 meta.json 에 한 줄도 안
    # 남는다 — 실제로 그렇게 그려져 png 만 있고 비용 기록이 통째로 빠진 run
    # 이 있다(20260831T121734-c17371 · 20260831T194100-c6c00b). 같은 그림을
    # 어느 명령으로 그렸느냐에 따라 정산이 달라지면 안 된다.
    run_dir = RUNS_DIR / args.run_id
    draw_continue(run_dir, dry_run=args.dry_run, only=args.only or None,
                 allow_no_sheet=args.no_sheet,
                 review=False if args.no_review else None,
                 on_page=lambda meta: record(run_dir, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
