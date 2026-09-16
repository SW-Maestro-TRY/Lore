#!/usr/bin/env python3
"""다 그린 화 전체를 처음부터 끝까지 읽는 검수.

## pagecheck 와 무엇이 다른가

`pagecheck.py`(PAGE_REVIEW)는 그릴 때마다 **직전 장과 지금 장 두 장만**
본다. 인접한 두 장이 매끈해도, 여러 장 전의 상태와 어긋나거나(장거리
단절), 몇 장 전과 같은 순간으로 되돌아가 있거나(장거리 반복), 처음부터
끝까지 읽었을 때만 드러나는 "왜 이렇게 됐지" 는 그 자리에서 못 잡는다.

그래서 이 검수는 **한 화를 다 그린 뒤에 한 번**, 페이지 전부를 순서대로
모델에게 보여주고 이야기가 무너지는 자리가 있는지만 본다. 개별 페이지가
잘 그려졌는지(작화·말풍선 꼬리·그 장면 대사가 자연스러운지·효과음
유무·컷 구성)는 이미 pagecheck 가 끝낸 일이라 **여기서 다시 안 본다** —
설계 논의는 `webtoon/docs/full-review-design.md` 참고.

## 예전 episodecheck 와 무엇이 다른가

2026-09-16에 지운 `episodecheck.py`는 "블라인드 읽기(그림만) + 견주기
(글만)" 두 번을 불러 "다시 그리려던 것과 닿는가"를 봤는데, 실측으로
못 잡는다는 판단이 나와 코드를 통째로 지웠다. 이번 것은 구조가 다르다.

- 줄거리·direction 을 안 준다. "무엇이 되려고 했는가"가 아니라
  "페이지들 사이가 이어지는가"만 보므로 애초에 필요 없다.
- 호출을 한 번만 한다 — 블라인드를 지키려고 가를 이유가 없다(원래
  견주는 대상이 없으니 이미 아는 채로 봐도 된다).
- 판정을 사람이 읽는 문장이 아니라 **JSON**으로 받는다. 흐름·무게는
  모델이 아니라 코드가 센다(`pagecheck.parse` 와 같은 원칙) — 원래
  설계 문서의 프롬프트는 사람이 읽는 문장 형식이었는데, 자동으로
  재생성 대상 페이지를 골라내려면 구조화된 값이 있어야 해서 출력
  형식만 JSON 으로 바꿨다. 판정 기준(우선순위 6단계·등급 규칙·재생성
  대상 지정 규칙)은 그대로다.
- **`severity`(critical/major/minor)와 `redraw`(다시 그려야 하는가)를
  분리한다.** 처음에는 `재생성`/`경고` 이진 등급 하나였는데, 순위(어디서
  발견했는지)와 심각도(얼마나 나쁜지)는 다른 축이라는 지적으로
  바꿨다 — "1순위(장거리 연속성)에서 나온 문제"라고 해서 다 critical은
  아니다(장소가 살짝 튀어도 맥락상 이해되면 major·minor일 수 있다).
  코드가 규칙을 한 번 더 강제한다: 5·6순위는 critical이 될 수 없고,
  critical이 아니면 redraw는 항상 false다(`parse()` 참고).

## 아직 없는 것 — 재생성 루프

이 모듈은 **판정만 한다.** `redraw_pages`로 지목된 페이지를 실제로 다시
그리고, 다시 pagecheck 를 거쳐, 다시 이 검수를 도는 루프는 아직 없다
(`webtoon/docs/full-review-design.md` §6). 지금은 `full_review.json`에
판정만 남긴다 — 자동으로 다시 그리지 않는다.

## 어떻게 쓰는가

이어그리기가 이 화를 다 그린 뒤에 한 번 부른다(`NH_FULL_REVIEW=0`이면
안 부른다). 한 장만 다시 그릴 때(`--page 3`)는 안 부른다 — 화 전체를
보는 검수라 일부만 새로 그린 상태에서는 볼 것이 못 된다.

    python3 fullreview.py --run-id <id>            # 이미 그린 것을 검수만
    python3 fullreview.py --run-id <id> --dry-run  # 프롬프트만 (0원)

결과는 `full_review.json`에 쌓인다. 원문(`full_review.txt`)은 파싱
**전에** 먼저 쓴다 — 파싱이 죽어도 그 호출에 쓴 돈이 사라지지 않는다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

import llm
import runmeta
from llm import story

HERE = Path(__file__).resolve().parent
# 작품이 쌓이는 자리. new_harness 의 다른 모듈과 같은 관례
# (NH_RUNS_DIR — 서버가 jar 밖 임시 폴더로 고정 경로를 넘긴다).
RUNS_DIR = Path(os.environ.get("NH_RUNS_DIR") or (HERE / "runs"))
PROMPT_DIR = HERE / "prompt"
PAGE_DIR = "pages"
STAGE = "FULL_REVIEW"

SEVERITIES = ("critical", "major", "minor")
RANKS = (1, 2, 3, 4, 5, 6)
# 5·6순위(시각 연속성·웹툰 독서 경험)는 이야기 이해와 별개라 아무리
# 심해도 critical 이 될 수 없다 — 프롬프트에도 같은 문장이 있다. 모델이
# 그 규칙을 어기고 critical 을 내면 코드가 major 로 눌러서 다음 단계
# (§full-review-design.md 6.4, critical 만 자동 재생성 후보)가 이 항목
# 때문에 그림값을 쓰지 않게 한다.
NON_CRITICAL_RANKS = (5, 6)

# 한 호출에 붙이는 그림의 상한. 예전 episodecheck 의 12장은 "장거리 문제를
# 보는" 이 검수의 목적과는 안 맞는 값이다 — 화가 길어질수록 그 뒤쪽이
# 잘려서 정작 봐야 하는 마무리를 못 본다. 그래도 무한정 올리면 값이
# 끝없이 커지니 상한은 둔다. `.env` 의 NH_FULL_REVIEW_MAX_IMAGES 로 덮는다.
DEFAULT_MAX_IMAGES = 40


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
    """호출 하나를 meta.json 에 남긴다.

    `runmeta`(파일 잠금)를 쓴다 — 장면을 동시에 그리는 편이 끝나갈 때
    이 검수 호출도 같은 meta.json 에 쓸 수 있어서, pagecheck 와 같은
    안전장치가 필요하다.
    """
    runmeta.append_call(run_dir, call_meta)


def _text(x) -> str:
    return x.strip() if isinstance(x, str) else ""


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
    """기본은 **켜짐.** 끄려면 `.env` 에 `NH_FULL_REVIEW=0`."""
    return _text(llm.env("NH_FULL_REVIEW") or "").lower() not in ("0", "off", "false", "no")


def max_images() -> int:
    try:
        return max(1, int(_text(llm.env("NH_FULL_REVIEW_MAX_IMAGES") or "") or DEFAULT_MAX_IMAGES))
    except ValueError:
        return DEFAULT_MAX_IMAGES


# -------------------------------------------------------------------- 프롬프트

def pages_of(run_dir: Path) -> list[Path]:
    """이 화의 페이지 그림 전부, 순서대로 (표지 포함)."""
    dest = run_dir / PAGE_DIR
    if not dest.is_dir():
        return []
    got = []
    for p in dest.glob("page*.png"):
        m = re.fullmatch(r"page(\d+)", p.stem)
        if m:
            got.append((int(m.group(1)), p))
    return [p for _, p in sorted(got)]


def build_prompt() -> str:
    """머리말 그대로다 — 줄거리·direction 을 안 섞는다.

    페이지가 몇 장인지, 어느 페이지가 표지인지는 첨부한 그림 자체의
    순서로 이미 전달된다. 텍스트를 더 얹으면 "무엇이 되려고 했는가"를
    아는 채로 읽게 되어, 순수하게 페이지 사이 이어짐만 보려는 목적이
    흐려진다.
    """
    path = PROMPT_DIR / "full_review_prompt"
    if not path.exists():
        raise SystemExit(f"프롬프트가 없습니다: {path}")
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise SystemExit(f"프롬프트가 비어 있습니다: {path}")
    return text


# ------------------------------------------------------------------------ 파싱

def parse(text: str) -> dict:
    """검수 응답(JSON) -> 판정.

    `severity`(얼마나 심각한가)와 `redraw`(그래서 다시 그려야 하는가)는
    서로 다른 축이다 — 모델이 둘 다 내지만, **코드가 규칙을 한 번 더
    강제한다**(모델 응답을 그대로 믿지 않는다, pagecheck.parse 와 같은
    원칙):

    - 5·6순위는 critical 이 될 수 없다(NON_CRITICAL_RANKS) — 이야기
      이해와 무관한 항목이라, 모델이 실수로 critical 을 냈어도 major로
      눌러서 자동 재생성 후보(§full-review-design.md 6.4)로 안 새게 한다.
    - severity 가 critical 이 아니면 redraw 는 항상 false 로 둔다 —
      major·minor 인데 redraw:true 가 와도 무시한다(프롬프트가 이미
      그렇게 지시하지만, 지키는지는 코드가 확인한다).
    """
    obj = story.extract_json(text)
    if not isinstance(obj, dict):
        raise story.ParseFailure("검수 결과가 JSON 객체가 아닙니다.")

    issues = []
    for one in obj.get("issues") or []:
        if not isinstance(one, dict):
            continue
        rank = one.get("rank")
        try:
            rank = int(rank)
        except (TypeError, ValueError):
            rank = 0
        rank = rank if rank in RANKS else 0
        severity = _text(one.get("severity")).lower()
        severity = severity if severity in SEVERITIES else "major"
        if rank in NON_CRITICAL_RANKS and severity == "critical":
            severity = "major"
        redraw = bool(one.get("redraw")) and severity == "critical"
        issues.append({
            "rank": rank,
            "severity": severity,
            "redraw": redraw,
            "pages": _ints(one.get("pages")),
            "redraw_pages": _ints(one.get("redraw_pages")) if redraw else [],
            "why": _text(one.get("why")),
            "redraw_pick_reason": _text(one.get("redraw_pick_reason")) if redraw else "",
        })
    issues.sort(key=lambda i: (SEVERITIES.index(i["severity"]), i["rank"]))

    redraw_pages = sorted({p for i in issues if i["redraw"] for p in i["redraw_pages"]})
    return {"verdict": "재생성" if redraw_pages else ("주의" if issues else "통과"),
            "redraw_pages": redraw_pages,
            "issues": issues}


def summary(review: dict) -> str:
    n = {s: sum(1 for i in review["issues"] if i["severity"] == s) for s in SEVERITIES}
    return (f"{review['verdict']} — critical {n['critical']}"
            f"(재생성 대상 {len(review['redraw_pages'])}장) · "
            f"major {n['major']} · minor {n['minor']}")


# -------------------------------------------------------------------- 호출 한 번

def review_episode(run_dir: Path, dry_run: bool = False) -> tuple[dict | None, dict | None]:
    """화 전체를 한 번에 검수한다. -> (판정, 호출기록).

    호출이 실패해도 **예외를 밖으로 안 던진다.** 그림은 이미 다 그려서
    값을 치렀다 — 검수가 안 됐다고 화를 잃으면 안 된다.
    """
    pages = pages_of(run_dir)
    if not pages:
        log("  [전체 검수] 그려진 페이지가 없습니다 — 건너뜁니다")
        return None, None

    prompt = build_prompt()
    write_text(run_dir / "full_review_prompt.txt", prompt)
    if dry_run:
        log(f"[전체 검수] 프롬프트만 썼습니다 -> {run_dir / 'full_review_prompt.txt'}")
        return None, None

    cap = max_images()
    if len(pages) > cap:
        log(f"  [전체 검수] 페이지가 {len(pages)}장이라 뒤 {cap}장만 붙입니다")
        pages = pages[-cap:]

    images = llm.load_images(pages)
    call = llm.Call(STAGE)
    log(f"[전체 검수] {call.describe()} · 그림 {len(images)}장을 처음부터 끝까지 읽습니다…")
    try:
        text, meta = call(prompt, images=images, temperature=0.2)
    except Exception as exc:                                          # noqa: BLE001
        meta = {"stage": STAGE, "provider": call.provider, "model": call.model,
                "usage": None, "stop": None, "pages": len(images),
                "cost": {"input": 0.0, "output": 0.0, "cache_read": 0.0,
                         "cache_write": 0.0, "total": 0.0},
                "error": f"{type(exc).__name__}: {exc}"}
        log(f"  [전체 검수] 실패 — {meta['error']} (그림은 그대로 둡니다)")
        return None, meta

    write_text(run_dir / "full_review.txt", text)
    meta["pages"] = len(images)
    try:
        review = parse(text)
    except Exception as exc:                                          # noqa: BLE001
        meta["error"] = f"{type(exc).__name__}: {exc}"
        log(f"  [전체 검수] 응답을 읽지 못했습니다 — {meta['error']} (원문은 남았습니다)")
        return None, meta

    write_json(run_dir / "full_review.json", review)
    log(f"  [전체 검수] {summary(review)}")
    for one in review["issues"]:
        pages_s = ",".join(str(p) for p in one["pages"]) or "?"
        tag = f"{one['severity']}" + ("·재생성" if one["redraw"] else "")
        log(f"    - [{tag}] {one['rank']}순위 · {pages_s}페이지: {one['why']}")
        if one["redraw_pages"]:
            log(f"      다시 그릴 페이지: {one['redraw_pages']}"
                + (f" — {one['redraw_pick_reason']}" if one["redraw_pick_reason"] else ""))
    return review, meta


# -------------------------------------------------------------------- 단독 실행

def review_run(run_dir: Path, dry_run: bool = False, on_call=None) -> dict | None:
    review, meta = review_episode(run_dir, dry_run=dry_run)
    if meta and on_call:
        on_call(meta)
    return review


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run-id", required=True)
    p.add_argument("--dry-run", action="store_true", help="프롬프트만 쓰고 호출하지 않는다")
    args = p.parse_args(argv)
    run_dir = RUNS_DIR / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"run 이 없습니다: {run_dir}")
    review_run(run_dir, dry_run=args.dry_run,
               on_call=lambda meta: record(run_dir, meta))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
