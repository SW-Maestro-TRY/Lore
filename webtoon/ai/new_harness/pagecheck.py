#!/usr/bin/env python3
"""그린 뒤 검수 — **앞 장에서 이어지는가.**

## 왜 있는가

이어그리기(`detailart.draw_continue`)는 장면 하나를 주고 컷·구도·대사를
그림 모델이 스스로 정하게 둔다. 연출은 그래야 살아 있는데, 그 자유가
**이야기까지** 건드리는 일이 실측으로 나왔다 — 두 번째 호출이 다섯 번째
장면(결말)을 그려 버리는 식이다. 그리라고 준 장면 번호를 프롬프트에 못
박아 두는 것만으로는 안 잡혔다.

그래서 **그린 뒤에** 한 번 더 본다. 직전 그림과 지금 그림 두 장을 이야기와
함께 주고, 사람이 아니라 모델이 읽는다.

## 무엇을 보는가 — "장면이 맞는가" 가 아니다

한 장이 장면 하나를 정확히 담아야 하는 것이 아니다. 한 장면이 두 장에
걸쳐도 되고, 한 장이 앞 장면의 끝을 마저 그리고 다음 장면으로 넘어가도
된다. 다음 이야기의 복선을 미리 깔아도 된다.

보는 것은 하나다 — **직전 그림에서 지금 그림으로 이야기가 이어지는가.**
`이어짐` / `제자리`(같은 순간을 다시 그림) / `건너뜀`(안 그린 이야기를
통째로 지나침) / `되돌아감` 넷으로 판정한다.

연출(컷 수·구도·카메라·여백)은 **판정 대상이 아니다.** 그것까지 보면
검수가 곧 컷 지정이 되어, 컷 대본을 없앤 이유가 사라진다. 대사는
2026-09-14부터 예외다 — 장면과 모순되거나 알아볼 수 없이 깨진 경우만
critical 로 본다(문체·표현 취향은 여전히 안 본다).

## 어떻게 쓰는가

이어그리기가 한 장을 그릴 때마다 부른다(`NH_PAGE_REVIEW=0` 이면 안 부른다).
`critical` 이 하나라도 나오면 그 장을 지우고, 검수가 적어 준 것을 프롬프트
뒤에 붙여 **한 번만** 다시 그린다(`NH_PAGE_REVIEW_RETRY`).

    python3 pagecheck.py --run-id <id>            # 이미 그린 것을 검수만 (안 다시 그림)
    python3 pagecheck.py --run-id <id> --page 3   # 그 장만
    python3 pagecheck.py --run-id <id> --dry-run  # 프롬프트만 (0원)

결과는 `pages/pageNN.review.json` 에 쌓인다. 원문(`.review.txt`)은 파싱
**전에** 먼저 쓴다 — 파싱이 죽어도 그 호출에 쓴 돈이 사라지지 않는다.
"""

from __future__ import annotations

import argparse
import json
import re
import os
from pathlib import Path

import llm
import charcard
import runmeta
import userbook
from llm import story

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
PAGE_DIR = "pages"
STAGE = "PAGE_REVIEW"

SEVERITY = ("critical", "major", "minor")
KINDS = ("장면구현", "연속성", "제자리", "건너뜀", "되돌아감", "인물", "공간",
         # 말풍선이 딴 사람을 가리키거나 효과음이 말풍선에 들어간 것.
         # 둘 다 critical 이라 아래 fail 판정이 그대로 다시 그리게 한다.
         "말풍선", "대사", "효과음",
         # 나레이션 상자·간판·두루마리 글과 말풍선이 같은 말을 되풀이한 것(#858). critical 이면 다시 그린다.
         "중복",
         # 말이 있어야 자연스러운 순간을 말 없이 둔 것(침묵이 연출이 아닌 경우). critical 이면 다시 그린다.
         "침묵",
         # 세로 스크롤인데 컷이 가로로 납작한 것. **경고만** 한다 —
         # 프롬프트가 critical 로 못 올리게 막아 두었다(다시 그려도 같은
         # 캔버스라 같은 결과가 나오기 쉽다).
         "세로형", "웹툰연출",
         # #475 — 인물 설명이 장면과 관련 있는데 안 드러남 / 나레이션 문장
         # 미완결. 둘 다 major 로만 오도록 프롬프트가 막아 두었다.
         "인물설명", "나레이션완결",
         # 보는 사람이 불쾌하거나 끔찍할 만큼 드러낸 폭력·신체 훼손(#988). critical 이면 다시 그린다.
         "수위")
FLOWS = ("이어짐", "제자리", "건너뜀", "되돌아감")

# 다시 그리게 만드는 흐름. `이어짐` 만 통과다 — 나머지 셋은 독자가 그 장에서
# 이야기를 놓친다는 뜻이라 무게와 상관없이 잡는다.
BAD_FLOWS = ("제자리", "건너뜀", "되돌아감")


def log(msg: str) -> None:
    print(msg, flush=True)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def record(run_dir: Path, call_meta: dict) -> None:
    """호출 하나를 run.py 의 meta.json 에 남긴다 — 그림 호출과 같은 자리.

    검수도 돈이 나가는 호출이다. 단독으로 돌렸을 때만 기록이 빠지면, 나중에
    이 run 에 얼마가 들었는지가 어디서 돌렸느냐에 따라 달라진다.

    쓰는 것은 runmeta 가 한다(파일 잠금) — 장면을 동시에 그리면 프로세스
    여럿이 같은 meta.json 에 쓴다.
    """
    runmeta.append_call(run_dir, call_meta)


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


def _strs(x) -> list[str]:
    return [_text(v) for v in x if _text(v)] if isinstance(x, list) else []


def _ints(x) -> list[int]:
    out = []
    for v in (x if isinstance(x, list) else []):
        try:
            out.append(int(v))
        except (TypeError, ValueError):
            continue
    return out


# ------------------------------------------------------------------ 켜고 끄기

def enabled() -> bool:
    """기본은 **켜짐.** 끄려면 `.env` 에 `NH_PAGE_REVIEW=0`.

    켜 두는 쪽을 기본으로 잡은 이유는, 이 검수가 잡으라고 만든 문제(뒤
    장면으로 건너뛰기)가 제품에서 실제로 나오고 있어서다 — 기본이 꺼져
    있으면 켜는 것을 잊은 판이 그대로 나간다.
    """
    return _text(llm.env("NH_PAGE_REVIEW") or "").lower() not in ("0", "off", "false", "no")


def max_redraw() -> int:
    """한 장을 최대 몇 번까지 다시 그리는가. 기본 1.

    0 이면 검수는 하되 다시 그리지는 않는다 — 무엇이 걸리는지만 보고 싶을
    때 쓴다. 올려 잡지 않는 이유는 두 번째도 어긋나면 그 다음이 나아진다는
    근거가 없어서다(그림 값만 계속 나간다).
    """
    try:
        return max(0, int(_text(llm.env("NH_PAGE_REVIEW_RETRY") or "1")))
    except ValueError:
        return 1


# -------------------------------------------------------------------- 프롬프트

def spec_lines(spec: dict | None) -> list[str]:
    """시트 사양(sheet_spec.json) -> 글. 그림은 안 붙이고 글만 준다(#519).

    시트 그림을 셋째 그림으로 붙이면 모델이 무엇을 검수 대상으로 보는지
    흐려진다. 글로 주면 "머리색·눈색·고정 요소가 그림과 같은가" 를 기준으로
    삼을 수 있다."""
    if not spec or not (spec.get("appearance_en") or spec.get("design_details")):
        return []
    lines = ["시트 사양 (이 인물의 고정 외형 — 그림이 이것과 다르면 인물 문제다):"]
    if spec.get("appearance_en"):
        lines.append(f"  - 외형: {spec['appearance_en']}")
    details = spec.get("design_details") or []
    if details:
        lines.append("  - 고정 요소: " + " / ".join(details))
    palette = spec.get("color_palette") or {}
    colors = " / ".join(f"{k} {v}" for k, v in palette.items() if v)
    if colors:
        lines.append(f"  - 색: {colors}")
    return lines


def now_line(book, name) -> str:
    """연재 중 이야기 속 사건으로 시트에서 오래 달라진 몸(진행 상태의 [시트와 다름: …], userbook.look_now) — 검수가 시트 사양 대신
    그 부위를 이 글로 판정하게 한다. 좀비였던 인물의 흰자가 돌아온 뒤에도 시트(검은 흰자)로 판정해 critical 로 다시 그리게 했다(2026-11-21)."""
    now = userbook.look_now(book, name)
    return (f" 단, 연재 중 달라진 곳 — {now}. 이 부위는 시트 사양이 아니라 이 글로 판정한다(이 모습이면 인물 문제가 아니다)."
            if now else "")


def people_block(char: dict | None, cast, spec: dict | None = None, sheeted=(), cast_specs=None) -> str:
    """인물 — "다른 사람이 되었다" 를 판정할 기준.

    캐릭터 시트 **그림**은 안 붙인다. 이 호출에 붙는 그림은 **직전 장과 지금 장**
    둘뿐이고, 셋째 장을 더하면 모델이 무엇을 검수 대상으로 보는지 흐려진다.
    같은 사람인지는 두 그림을 견주면 알 수 있다. 대신 시트 **사양 글**은 준다
    (#519) — 두 그림이 서로 같아도 둘 다 시트와 다르면 잡을 기준이 없었다.
    """
    lines = []
    if char:
        who = _text(char.get("name"))
        desc = _text(char.get("description"))
        card = charcard.short(charcard.for_story(char))     # #458 — detailart 와 같은 한 줄(설정집 작품은 카드의 옛 세계를 뺀다)
        desc = f"{card}. {desc}" if card and desc else (card or desc)
        if who:
            lines.append(f"{who} (주인공) — {desc}" if desc else f"{who} (주인공)")
    lines += spec_lines(spec)
    if spec and char and now_line(char.get("lorebook"), char.get("name")):
        lines.append(" " + now_line(char.get("lorebook"), char.get("name")).strip())
    # 함께 주인공인 사람(주인공 여러 명) — 시트 사양은 sheets/<이름>.spec.json 에 있어 여기선 설명만 둔다.
    for one in (char or {}).get("co") or []:
        if not isinstance(one, dict) or not _text(one.get("name")):
            continue
        desc = _text(one.get("description"))
        card = charcard.short(charcard.for_story({**one, "lorebook": char.get("lorebook")}))
        desc = f"{card}. {desc}" if card and desc else (card or desc)
        lines.append((f"{one['name']} (주인공) — {desc}" if desc else f"{one['name']} (주인공)") + now_line(char.get("lorebook"), one["name"]))
    for one in cast or []:
        if isinstance(one, dict) and _text(one.get("name")):
            if one["name"] in sheeted or any(one["name"] in nm or nm.endswith(one["name"]) for nm in sheeted):
                # 시트가 붙은 조연 — 그리는 쪽은 시트를 따르므로 검수가 원작 겉모습 글로 머리·얼굴을 판정하면 반대로 다시 그리게 한다(2026-11-20)
                # 시트 사양 글이 있으면 그것을 기준으로 준다 — 「같은 화 안에서 같은 사람인지만」 보면 7화 중간부터 도윤이 계속
                # 원작 글대로 짧은 머리로 그려져도 앞 장과 같아 통과했다(2026-11-20 사람 검토). 주인공 시트 사양(#519)과 같은 생각.
                specs = {k: v for k, v in (cast_specs or {}).items() if isinstance(v, dict)}
                sp = specs.get(one["name"]) or next((v for k, v in specs.items() if k.endswith(one["name"])), None)
                det = [_text(x) for x in (sp or {}).get("design_details") or [] if _text(x)]
                now = now_line((char or {}).get("lorebook"), one["name"])
                if det:
                    lines.append(f"{one['name']} — 조연 시트가 있는 인물. 시트 사양(이 사람의 고정 생김새): " + " / ".join(det)
                                 + " — 원작 · 장면의 겉모습 글이 아니라 이 사양으로 판정한다. 앞 장과 같아도 머리 모양 · 얼굴이 이 사양과 다르면 "
                                 "다른 사람으로 그려진 것이다(critical, kind 인물). 옷 · 상처 · 피는 장면 글을 따를 수 있다." + now)
                    continue
                lines.append(f"{one['name']} — 조연 시트가 있는 인물: 머리 모양 · 얼굴 · 체형은 이 글로 판정하지 않는다(시트를 따름). 같은 화 안에서 같은 사람으로 보이는지만 본다." + now)
                continue
            lines.append(f"{one['name']} — {_text(one.get('appearance'))}")
    if not lines:
        return ""
    # 설정집의 「말하는 방식」(lore_extract.extract_looks) — 그리는 쪽(detailart.character_block)에는 있는데
    # 검수에는 없어서, 말 못 하는 좀비 장에 말풍선이 없다고 「침묵」으로 다시 그리게 했고 다시 그린 판에서
    # 그 좀비가 「저리 가.」라고 말했다(2026-11-19, longrun v4-art ep01 10장). 설정집이 없으면 아무것도 안 붙는다.
    book = (char or {}).get("lorebook")
    names = [_text((char or {}).get("name"))] + [_text(o.get("name")) for o in (char or {}).get("co") or []
                                                if isinstance(o, dict)]
    names += [_text(o.get("name")) for o in cast or [] if isinstance(o, dict)]
    voices = []
    for nm in names:
        if not nm or not book:
            continue
        v = _text((userbook.person_look(book, nm) or {}).get("voice"))
        if v:
            voices.append(f"  - {nm}: {v}")
    if voices:
        lines += ["말하는 방식 (이 범위를 넘는 말풍선은 인물 문제다):"] + voices
    return "## 인물\n" + "\n".join(lines)


def scene_texts(direction: dict, scenes=()) -> list[str]:
    """검수가 견줄 장면 목록. **`scenes.json` 이 먼저다.**

    그림은 `scenes.json`(scene_prompt 의 산출물)으로 그려지는데
    (`detailart.draw_continue`), 검수만 `directions.json` 의 `scenes` 를 보고
    있었다. 그 필드는 옛 story 형식(### 하위 절)에서만 채워지는 호환 자리라
    지금 만들어지는 run 에서는 **늘 비어 있다**(`run.parse_directions`).

    그래서 같은 페이지인데 그림 프롬프트에는 장면 넷이 다 들어가고 검수
    프롬프트에는 「전체 0장면 중 3번 장면」이 찍혔다. 견줄 목록이 없으면
    `건너뜀`·`되돌아감` 은 판정 자체가 불가능해서, 남은 것은 인접한 두 그림이
    시각적으로 안 튀는지뿐이었고 전부 `이어짐` 으로 통과했다
    (2026-09-19, run 20260919T022231-383b4b).
    """
    out = []
    for one in scenes or []:
        if not isinstance(one, dict):
            continue
        body = " ".join(x for x in (_text(one.get("what")), _text(one.get("acting")),
                                   _text(one.get("look"))) if x)
        if body:
            out.append(body)
    return out or [x for x in (direction.get("scenes") or []) if _text(x)]


def story_block(direction: dict, scene_no: int, scenes=()) -> str:
    """전체 이야기 — 어디까지 왔는지를 견줄 자리.

    지금 장면 앞뒤를 다 준다. 뒤를 가리면 "건너뛰었다" 를 판정할 수가 없다 —
    무엇을 건너뛴 것인지 알아야 건너뛴 것을 안다.
    """
    scenes = scene_texts(direction, scenes)
    lines = ["## 전체 이야기"]
    title, genre = _text(direction.get("title")), _text(direction.get("genre"))
    if title or genre:
        lines.append(f"[작품] {title}" + (f" · {genre}" if genre else ""))
    plot = _text(direction.get("plot")) or _text(direction.get("body"))
    if plot:
        lines += ["", "[줄거리]", plot]
    if scenes:
        lines += ["", "[장면 — 순서대로 일어나는 사건들이다]"]
        for i, s in enumerate(scenes, 1):
            if i < scene_no:
                mark = "  (앞 장들에서 이미 그려진 자리)"
            elif i == scene_no:
                mark = "  ← 이 장을 그릴 때 준 장면"
            else:
                mark = "  (아직 안 그려진 자리)"
            lines.append(f"{i}. {_text(s)}{mark}")
    return "\n".join(lines)


def prev_block(direction: dict, scene_no: int, *, has_prev: bool,
               prev_is_cover: bool, next_from: str = "", scenes=()) -> str:
    """직전 그림이 무엇인지. 첨부 순서 1번을 글로 설명해 준다."""
    if not has_prev and scene_no > 1:
        # 장면을 동시에 그리는 중이라 앞 장이 아직 없다. 여기서 "첫 장이다" 로
        # 내려가면 검수가 이 장을 화의 시작으로 읽어, 앞에서 이어지는지를
        # 아예 안 본다.
        lines = ["## 직전 페이지",
                 "아직 안 그려졌다 — 이 화는 장면을 동시에 그리는 중이라 앞 장 그림이 "
                 "없다. 첨부한 그림은 지금 페이지 하나뿐이다."]
        if _text(next_from):
            lines += ["", "앞 장이 끝나기로 되어 있는 자리는 아래와 같다. 지금 그림이 "
                          "**이 자리에서 이어지는지**를 본다.",
                      f"\"{_text(next_from)}\""]
        return "\n".join(lines)
    if not has_prev:
        return "## 직전 페이지\n없다. 이 화의 첫 장이다 — 첨부한 그림은 지금 페이지 하나뿐이다."
    if prev_is_cover:
        return ("## 직전 페이지\n표지다. 이야기의 한 순간이 아니라 이 작품을 대표하는 "
                "한 장이라, 지금 그림이 표지에서 '이어질' 필요는 없다. 인물이 같은 "
                "사람인지만 견준다.")
    scenes = scene_texts(direction, scenes)
    prev_no = scene_no - 1
    body = _text(scenes[prev_no - 1]) if 0 < prev_no <= len(scenes) else ""
    lines = ["## 직전 페이지",
             f"바로 앞 장이다. 그릴 때 준 장면은 {prev_no}번이었다."]
    if body:
        lines.append(f"\"{body}\"")
    if _text(next_from):
        lines += ["", f"[앞 장 검수가 적어 둔 것 — 여기서부터 이어 그리면 된다] {_text(next_from)}"]
    return "\n".join(lines)


def scene_block(direction: dict, scene_no: int, total: int, scenes=()) -> str:
    scenes = scene_texts(direction, scenes)
    body = _text(scenes[scene_no - 1]) if 0 < scene_no <= len(scenes) else ""
    return ("## 이 장을 그릴 때 준 지시\n"
            f"전체 {total}장면 중 {scene_no}번 장면을 그리라고 했다.\n"
            + (f"\"{body}\"\n" if body else "")
            + "\n이 지시는 **출발점**이지 채점표가 아니다. 이 장면을 다 담지 못했어도, "
              "앞 장면의 끝을 마저 그리고 있어도, 다음 장면으로 넘어가 있어도 된다 — "
              "직전 그림에서 이어져 앞으로 가고 있으면 통과다.")


def build_prompt(direction: dict, *, scene_no: int, char: dict | None = None,
                 cast=(), has_prev: bool = True, prev_is_cover: bool = False,
                 next_from: str = "", scenes=(), spec: dict | None = None, sheeted=(),
                 series_ep: int = 0, known=(), cast_specs=None) -> str:
    path = PROMPT_DIR / "page_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    total = len(scene_texts(direction, scenes))
    return (series_note(path.read_text(encoding="utf-8"), series_ep, known)
            .replace("{people}", people_block(char, cast, spec, sheeted, cast_specs))
            .replace("{story}", story_block(direction, scene_no, scenes))
            .replace("{prev}", prev_block(direction, scene_no, has_prev=has_prev,
                                          prev_is_cover=prev_is_cover,
                                          next_from=next_from, scenes=scenes))
            .replace("{scene}", scene_block(direction, scene_no, total, scenes)))


SERIES_HEAD = "## 이 화의 첫 페이지(장면 1)라면 — 특히 엄격하게 본다"


def series_note(text: str, series_ep: int, known=()) -> str:
    """이어 만든 화(연재 2화부터)는 독자가 앞 화를 이미 읽었다 — 앞 화 인물을 매 화 다시 소개하라고 잡지 않는다.

    「첫 페이지」 규칙은 작품의 첫 화를 기준으로 쓰여 있어, 연재 6화 첫 장에도 「침대의 여성과 남성이
    누구인지 단서가 없다」를 critical 로 잡았다(2026-11-20 사람 검토 — 매 화 인물 소개부터 하면 안 된다).
    「이미 아는 인물」은 앞 화에 실제로 나온 이름(known)만 적는다 — 이 화에 처음 나오는 조연은 그대로 검사한다.
    이어짐 정보가 없거나 앞 화 인물을 못 찾으면 한 글자도 안 바뀐다.
    """
    known = [k for k in dict.fromkeys(known) if k]
    if series_ep < 2 or not known:
        return text
    note = (f"## 이 화는 연재 {series_ep}화다 — 앞 화를 읽은 독자\n\n"
            f"독자는 앞 {series_ep - 1}화를 이미 읽었다. 앞 화에서 이미 나온 인물: {' · '.join(known)}. "
            "이 사람들과 그 관계 · 처지는 다시 소개하지 않아도 된다 — 첫 페이지라도 이들에게 이름 · 설명이 없다는 이유로 지적하지 않는다"
            "(매 화를 인물 소개로 시작하면 안 된다). 이 목록에 없는 인물이 이 화에 처음 나오면 아래 「첫 페이지」 · 「처음 등장하는 인물」 규칙대로 본다.\n\n")
    return text.replace(SERIES_HEAD, note + SERIES_HEAD, 1) if SERIES_HEAD in text else note + text


def series_known(cont: dict | None, char: dict | None, cast=()) -> tuple[int, tuple[str, ...]]:
    """continuation.json -> (연재 화 번호, 앞 화에서 이미 나온 인물 이름). 화 번호는 2 이상의 정수일 때만 쓴다(그 밖은 0)."""
    cont = cont if isinstance(cont, dict) else {}
    ep = cont.get("episode_number")
    if isinstance(ep, bool) or not isinstance(ep, int) or ep < 2:
        return 0, ()
    from detailart import _name_keys      # 늦게 부른다 — detailart 가 이 모듈을 import 한다
    hay = " ".join(str(cont.get(k) or "") for k in ("source_body", "source_scenes", "progress_state", "fixed_settings"))
    names = [_text((char or {}).get("name"))] + [_text(o.get("name")) for o in (char or {}).get("co") or [] if isinstance(o, dict)] \
        + [_text(c.get("name")) for c in (cast or []) if isinstance(c, dict)]

    # 앞 화에 나온 인물 = 앞 화 장면의 등장인물 목록(source_dir/scenes.json) + 앞 화 글에 **이름 전체**가 경계 있게 나온 사람.
    # 성을 뺀 이름을 글자로 찾으면 「맑은 하늘 · 하늘은」에서 김하늘을, 「도윤이슬」에서 한도윤을 찾았다(11/20 검증).
    prev_cast = set()
    try:
        sd = Path(str(cont.get("source_dir") or ""))
        if str(cont.get("source_dir") or "") and (sd / "scenes.json").is_file():
            prev_cast = {_text(c.get("name")) for c in (json.loads((sd / "scenes.json").read_text(encoding="utf-8")).get("cast") or [])
                         if isinstance(c, dict)}
    except (OSError, ValueError):
        prev_cast = set()
    josa = r"(?:이|가|은|는|을|를|의|와|과|에게|도|만|아|야|이랑|랑)?"

    def seen(n):
        if n in prev_cast or any(n in _name_keys(c) or c in _name_keys(n) for c in prev_cast if c):
            return True
        return re.search(rf"(?<![가-힣]){re.escape(n)}{josa}(?![가-힣])", hay) is not None
    lead = {_text((char or {}).get("name"))} | {_text(o.get("name")) for o in (char or {}).get("co") or [] if isinstance(o, dict)}
    return ep, tuple(n for n in dict.fromkeys(names) if n and (n in lead or seen(n)))


# ------------------------------------------------------------------------ 파싱

OPENS = ("이미", "처음", "없음")


def parse(text: str, *, has_prev: bool = True, fixed_narration: bool = False,
          scene_page: bool = True) -> dict:
    """검수 응답(JSON) -> 판정.

    `verdict` 는 모델에게 안 묻는다. **흐름과 무게에서 코드가 센다** —
    "critical 이면 다시 그린다" 같은 규칙을 모델이 지켰는지는 여기서 확인할
    수 있고, 어긋나면 센 쪽이 맞다(`run.parse_review` 와 같은 이유).
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("검수 결과가 JSON 객체가 아닙니다.")

    flow = _text(obj.get("flow"))
    if flow not in FLOWS:
        # 모르는 값은 통과로 보지 않는다 — 조용히 흘려보내면 검수가 있는
        # 것과 없는 것이 같아진다. 대신 `이어짐` 이 아닐 뿐이라 무게는
        # issues 가 정한다.
        flow = flow or "?"

    issues = []
    for one in obj.get("issues") or []:
        if not isinstance(one, dict):
            continue
        sev = _text(one.get("severity")).lower()
        kind = _text(one.get("kind"))
        issues.append({"kind": kind if kind in KINDS else (kind or "연속성"),
                       "severity": sev if sev in SEVERITY else "major",
                       "what": _text(one.get("what"))})
    issues.sort(key=lambda i: SEVERITY.index(i["severity"]))

    # 나레이션이 앞 장에서 이어받는가. **모델에게는 판정을 안 묻는다** —
    # "되돌아가 다시 시작하는가" 라고 물으면, 근거를 자기 손으로 적어 놓고도
    # 통과를 낸다(2026-09-19, run 20260919T022231-383b4b). 이야기가 이해는
    # 되니까 그렇다. 그래서 "앞 장에 이미 나온 말인가" 라는 기억 질문만
    # 받고, 다시 그릴지는 여기서 정한다(`verdict` 를 코드가 세는 것과 같은
    # 자리). 직전 그림이 없으면 견줄 것이 없어 안 센다.
    opens = _text(obj.get("opens"))
    opens = opens if opens in OPENS else ""
    if has_prev and opens == "이미" and fixed_narration:
        # 나레이션 글을 장면 단계에서 정해 둔 장이다. 다시 그려도 같은
        # 글자가 찍혀서 고쳐지지 않는다 — 기록만 하고 다시 그리게 하지 않는다.
        issues.append({
            "kind": "되돌아감", "severity": "minor",
            "what": "이 장 나레이션이 앞 장이 이미 말한 상황으로 되돌아가 시작한다 "
                    "— 나레이션은 장면 글에서 정한 것이라 다시 그려도 안 바뀐다."})
    elif has_prev and opens == "이미":
        issues.insert(0, {
            "kind": "대사", "severity": "critical",
            "what": "이 장 나레이션이 앞 장에서 이어받지 않고, 앞 장이 이미 말한 "
                    "상황 설명으로 되돌아가 시작한다 — 독자가 같은 도입부를 두 번 "
                    "읽는다."})

    # 말 없는 장(#628). **「어색한가」를 모델에게 묻지 않는다** — 그렇게 물으면 「그림과 나레이션만으로도
    # 이해된다」며 통과를 냈다(2026-10-04, 말 없이 손님을 맞는 장이 두 번 통과). 말풍선 수와 「장면 글이 이 장을
    # 말 없는 장면으로 정했는가」만 받고, 정하지 않았는데 0개면 여기서 다시 그리게 한다.
    bubbles = obj.get("bubbles")
    silent = obj.get("silent_by_scene") is True
    if scene_page and isinstance(bubbles, int) and not isinstance(bubbles, bool) and bubbles == 0 and not silent:
        issues.insert(0, {
            "kind": "침묵", "severity": "critical",
            "what": "장면 글이 말 없는 장면으로 정하지 않았는데 이 장에 말풍선이 하나도 없다 — 인물이 말·속마음·"
                    "혼잣말을 할 자리가 비어 독자가 어색하게 읽는다."})

    fail = any(i["severity"] == "critical" for i in issues) or flow in BAD_FLOWS
    return {"verdict": "재생성" if fail else "통과",
            "flow": flow,
            "opens": opens,
            "drawn": _text(obj.get("drawn")),
            "covers": _text(obj.get("covers")),
            "scenes": _ints(obj.get("scenes")),
            "why": _text(obj.get("why")),
            "next_from": _text(obj.get("next_from")),
            "bubbles": bubbles if isinstance(bubbles, int) and not isinstance(bubbles, bool) else None,
            "silent_by_scene": silent,
            "issues": issues,
            "redraw": _text(obj.get("redraw"))}


def summary(review: dict) -> str:
    n = {s: 0 for s in SEVERITY}
    for one in review.get("issues") or []:
        n[one["severity"]] += 1
    return (f"{review['verdict']} · 흐름 {review['flow']} — "
            f"critical {n['critical']} · major {n['major']} · minor {n['minor']}")


def redraw_block(review: dict) -> str:
    """다시 그릴 때 프롬프트 **뒤**에 붙이는 것.

    이야기만 짚고 연출은 안 건드린다 — 여기서 컷을 지시하면 다시 그린 장이
    검수를 통과하는 대신 평평해진다(컷 대본 단계를 없앤 이유와 같다).
    """
    lines = ["## 다시 그린다 — 앞서 그린 한 장이 이야기와 어긋났다", "",
             "이 장면으로 이미 한 번 그렸는데, 읽어 보니 아래가 걸렸다. "
             "**같은 자리를 다시 그린다.**", ""]
    if review.get("drawn"):
        lines.append(f"- 앞서 그린 것: {review['drawn']}")
    if review.get("flow") and review["flow"] != "이어짐":
        why = f" — {review['why']}" if review.get("why") else ""
        lines.append(f"- 직전 그림에서 이어지지 않는다 ({review['flow']}){why}")
    for one in review.get("issues") or []:
        if one["severity"] in ("critical", "major"):
            lines.append(f"- {one['what']}")
    if review.get("redraw"):
        lines += ["", "[바로잡을 것]", review["redraw"]]
    lines += ["", "**연출은 그대로 네가 정한다** — 컷 수·구도·카메라·여백·대사는 "
                  "앞서 그린 것을 따라 할 필요가 없다. 위에 적힌 것은 이야기 쪽이다. "
                  "직전 그림에서 자연스럽게 이어지는 다음 순간을 그린다."]

    # 여기까지는 "연출은 마음대로" 로 끝난다. 그런데 말풍선이 딴 사람을
    # 가리켰거나 효과음이 말풍선에 들어간 것은 **연출이 아니라 틀린 것**이라,
    # 그 한 줄이 방금 짚은 지적을 도로 풀어 버린다("대사는 따라 할 필요가
    # 없다"). 그래서 그 둘이 걸렸을 때만 뒤에 한 문단을 더 붙여 못 박는다.
    # 무엇을 그리라고 지시하지는 않는다 — 지켜야 할 것만 적는다.
    kinds = {one.get("kind") for one in (review.get("issues") or [])}
    tail = []
    if "말풍선" in kinds:
        tail.append("- 말은 **한 사람이 한다.** 읽는 사람이 그 칸에서 누가 한 "
                    "말인지 헷갈리지 않아야 한다. 말풍선을 몇 개 어떻게 놓을지는 "
                    "네가 정하되, 그 결과가 말한 사람을 가리켜야 한다.")
    if "대사" in kinds:
        tail.append("- 나레이션은 **앞 장 마지막 줄 다음에서 이어 쓴다.** 앞 장이 "
                    "이미 설명한 상황을 다시 설명하지 않는다 — 무슨 말을 쓸지는 "
                    "네가 정하되, 읽는 사람이 앞 장에서 읽던 자리에서 그대로 "
                    "계속 읽어야 한다.")
    if "침묵" in kinds:
        tail.append("- 인물이 말을 해야 자연스러운 순간을 **말 없이 두지 않는다.** 위에서 짚은 순간에 "
                    "그 상황에 맞는 말을 말풍선으로 넣는다. 무슨 말을 할지는 네가 정하되, 장면 글과 "
                    "앞뒤 대사에 맞아야 한다.")
    if "효과음" in kinds:
        tail.append("- **사물이 낸 소리는 말풍선에 담지 않는다.** 문 소리·발소리 "
                    "같은 것은 그림 위에 그대로 얹는 글자다. 말풍선에 들어가면 "
                    "독자는 그것을 누군가 입으로 한 말로 읽는다. 사람이 낸 소리"
                    "(비명·헛기침·웃음)는 대사이므로 말풍선에 담아도 된다.")
    if "수위" in kinds:
        tail.append("- **일어난 일은 그대로 두고, 보는 사람이 불쾌하거나 끔찍할 만큼 드러내지 않는다.** "
                    "몸이 부서지거나 찢기거나 꿰뚫리는 순간과 결과, 눈길을 끄는 피·살점, 가까이서 "
                    "자세히 보여 주는 시신·상처는 화면에 두지 않는다. 그 일이 일어났다는 것은 독자가 "
                    "알 수 있어야 한다 — 어떻게 알게 할지는 네가 정한다.")
    if tail:
        lines += ["", "**아래는 연출이 아니라 지켜야 할 것이다.**", ""] + tail

    return "\n".join(lines)


# -------------------------------------------------------------------- 호출 한 번

def review_page(run_dir: Path, page_no: int, *, scene_no: int, direction: dict,
                char: dict | None = None, cast=(), prev_is_cover: bool = False,
                next_from: str = "", dry_run: bool = False,
                suffix: str = "", scenes=()) -> tuple[dict | None, dict | None]:
    """한 장을 검수한다. -> (판정, 호출기록). dry-run 이면 (None, None).

    호출이 실패해도 **예외를 밖으로 안 던진다.** 그림은 이미 그려서 값을
    치렀다 — 검수가 안 됐다고 그 장을 잃으면 안 된다. 대신 기록에 사유를
    남기고 통과로 두지 않는다(판정 자체가 없음).
    """
    dest = run_dir / PAGE_DIR
    cur = dest / f"page{page_no:02d}.png"
    prev = dest / f"page{page_no - 1:02d}.png"
    has_prev = page_no > 1 and prev.exists()

    spec = read_json(run_dir / "sheet_spec.json")
    sheeted = tuple(f.stem for f in (run_dir / "sheets").glob("*.png")) if (run_dir / "sheets").is_dir() else ()
    cast_specs = {f.name.removesuffix(".spec.json"): read_json(f) for f in (run_dir / "sheets").glob("*.spec.json")} \
        if (run_dir / "sheets").is_dir() else {}
    try:
        series_ep, known = series_known(read_json(run_dir / "continuation.json"), char, cast)
    except (ValueError, OSError):     # 깨진 이어짐 정보로 검수를 잃지 않는다 — 예전 프롬프트로
        series_ep, known = 0, ()
    prompt = build_prompt(direction, scene_no=scene_no, char=char, cast=cast, spec=spec,
                          has_prev=has_prev, prev_is_cover=prev_is_cover,
                          next_from=next_from, scenes=scenes, sheeted=sheeted,
                          series_ep=series_ep, known=known, cast_specs=cast_specs)
    write_text(dest / f"page{page_no:02d}.review{suffix}_prompt.txt", prompt)
    if dry_run:
        return None, None
    # 견줄 이야기가 없으면 **부르지 않는다.** 장면 목록이 빈 채로 보내면
    # 검수는 인접한 두 그림만 보고 거의 언제나 `이어짐` 을 낸다 — 판정이
    # 없는 것이 아니라 **틀린 통과**가 나오고, 그림값 위에 검수값까지 더
    # 나간다. 판정 없음으로 두고 사유를 기록에 남긴다.
    if not scene_texts(direction, scenes):
        log("  [검수] 장면 목록이 비어 있습니다 — 견줄 이야기가 없어 검수를 "
            "건너뜁니다 (scenes.json 을 확인하세요)")
        return None, {"stage": STAGE, "page": page_no, "usage": None, "stop": None,
                      "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                               "cache_write": 0.0, "total": 0.0},
                      "error": "장면 목록이 비어 검수를 건너뜀"}
    if not cur.exists():
        log(f"  [검수] {cur.name} 이 없습니다 — 건너뜁니다")
        return None, None

    call = llm.Call(STAGE)
    images = llm.load_images([p for p in ([prev] if has_prev else []) + [cur]])
    log(f"  [검수] {call.describe()} · 그림 {len(images)}장 …")
    try:
        text, meta = call(prompt, images=images, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None, "page": page_no,
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [검수] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta

    write_text(dest / f"page{page_no:02d}.review{suffix}.txt", text)
    meta["page"] = page_no
    try:
        one = scenes[scene_no - 1] if 0 < scene_no <= len(scenes) else {}
        review = parse(text, has_prev=has_prev,
                       fixed_narration=isinstance(one, dict) and one.get("narration") is not None,
                       scene_page=scene_no > 0)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    write_json(dest / f"page{page_no:02d}.review{suffix}.json", review)
    log(f"  [검수] {summary(review)}")
    for one in review["issues"]:
        log(f"    - [{one['severity']}] {one['kind']}: {one['what']}")
    return review, meta


# -------------------------------------------------------------------- 단독 실행

def review_run(run_dir: Path, only=None, dry_run: bool = False,
               on_call=None) -> list[dict]:
    """이미 그려 둔 페이지를 검수만 한다 — **다시 그리지는 않는다.**

    무엇이 걸리는지 먼저 보고 싶을 때, 그리고 옛 run 을 나중에 훑을 때 쓴다.
    """
    pick = read_json(run_dir / "pick.json") or {}
    directions = read_json(run_dir / "directions.json") or []
    direction = (next((d for d in directions if d.get("n") == pick.get("n")), None)
                 or (directions[0] if directions else None))
    if not direction:
        raise SystemExit(f"{run_dir / 'directions.json'} 가 없습니다.")

    char = read_json(run_dir / "input.json")
    import detailart
    scene_data = read_json(run_dir / "scenes.json") or {}
    scenes = [s for s in (scene_data.get("scenes") or []) if isinstance(s, dict)]
    cast = [c for c in (scene_data.get("cast") or direction.get("cast") or [])
            if isinstance(c, dict) and _text(c.get("name"))
            and not detailart.is_protagonist(char, _text(c.get("name")))]

    # 「직전 상태」가 있으면 "앞 장이 어디서 끝났는가" 를 앞 장 검수에서 받아
    # 오지 않는다 — 그리기 전에 scene_prompt 가 이미 정해 둔 값이 기준이다.
    out = []
    next_from = ""
    for scene_no in range(1, len(scenes) + 1):
        scene = scenes[scene_no - 1]
        prev = _text(scene.get("prev"))
        if prev and prev not in ("없음", "없음."):
            next_from = prev
        page_no = scene_no + 1                     # 1페이지는 표지다
        if only and page_no not in only:
            continue
        review, meta = review_page(run_dir, page_no, scene_no=scene_no,
                                   direction=direction, char=char, cast=cast,
                                   prev_is_cover=(scene_no == 1),
                                   next_from=next_from, dry_run=dry_run,
                                   scenes=scenes)
        if meta and on_call:
            on_call(meta)
        if review:
            out.append({"page": page_no, **review})
            next_from = review.get("next_from") or ""
    if out:
        bad = [r for r in out if r["verdict"] == "재생성"]
        log(f"[검수] {len(out)}장 중 {len(bad)}장이 다시 그릴 것으로 나왔습니다"
            + (f" — {', '.join(str(r['page']) + '페이지' for r in bad)}" if bad else ""))
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--page", type=int, nargs="*", default=[],
                   help="이 페이지 번호만 (1 은 표지라 검수 대상이 아니다)")
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    review_run(run_dir, only=args.page or None, dry_run=args.dry_run,
               on_call=lambda meta: record(run_dir, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
