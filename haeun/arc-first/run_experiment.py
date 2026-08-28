#!/usr/bin/env python3
"""순서 실험 — P2 → W4 → 씬 → W5.

지금 파이프라인의 순서는 P2 → 씬 → W4 → W5 다. 그런데 씬(1화 도입부)이
엔진 카드에 **산문 전문 그대로** 실리고("1화의 컷은 이 장면을 컷으로 옮기는
일이다"), 그 카드가 W4 와 W5 에 똑같이 들어간다. 그래서 1화를 정하는 것은
사실상 씬이고, W4 는 그 장면을 Arc 로 요약하고, W5 는 다시 그것을 받아쓴다.
실측(run 20260828T140618): 씬 1 "공식 기자회견장에서 실명 공개를 거부한다"
→ Arc 1 요약도, W5 의 1화도 같은 장면이었다.

이 스크립트는 순서를 뒤집는다.

  1. P2   — 기존 run 의 것을 그대로 재사용한다 (같은 전제라야 비교가 된다)
  2. W4   — 장면이 **빠진** 엔진 카드로 큰 줄거리를 잡는다
  3. 씬   — Arc 1 의 압력 설계를 보고 1화 도입부를 쓴다
  4. W5   — 그 장면들이 실린 카드로 1화를 설계한다

story-harness 는 손대지 않는다. 여기서 그 모듈을 불러다 쓰기만 한다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
STORY = HERE.parent / "story-harness"
sys.path.insert(0, str(STORY))

import story          # noqa: E402
import webtoon        # noqa: E402


# ---------------------------------------------------------------- 엔진 카드
#
# W4 에게는 장면을 **안 보여준다.** 이 실험의 전부가 이 한 줄이다.
# build_engine_card 는 장면이 비어도 "[1화 도입부 — 사람이 통과시킨 장면]"
# 머리말과 "이 장면을 컷으로 옮기는 일이다" 지시를 그대로 찍으므로,
# 그 블록 자체를 들어낸다. 안 들어내면 W4 가 있지도 않은 장면을 지키려 든다.
_SCENE_BLOCK = "[1화 도입부 — 사람이 통과시킨 장면]"


def strip_scene_block(card: str) -> str:
    start = card.find(_SCENE_BLOCK)
    if start < 0:
        return card
    tail = card.find("=== /엔진 카드 ===", start)
    head = card[:start].rstrip("\n")
    return head + "\n" + (card[tail:] if tail >= 0 else "=== /엔진 카드 ===")


# ---------------------------------------------------------------- 씬 프롬프트
#
# 씬은 원래 P1·P2 만 보고 1화 도입부를 쓴다. 이제 그 앞에 Arc 가 있으므로,
# 이 도입부가 **어느 압력 구간에 놓이는지**를 같이 준다. 프롬프트 파일은
# 고치지 않고 [설정] 바로 뒤에 블록을 끼운다 — 실험이 끝나야 이게 제품에
# 들어갈 물건인지 알 수 있다.
_SCENE_ANCHOR = "{premise_json}"


def arc_block_for_scene(arc: dict) -> str:
    def one(key, label):
        v = arc.get(key)
        return f"  {label}: {v}" if not story.is_blank(v) else ""

    lines = [
        "",
        "[이 도입부가 놓이는 자리 — 큰 줄거리 1]",
        "★ 이건 **압력**이지 사건이 아니다. 여기 적힌 사건을 쓰라는 것이 아니라,",
        "  이 압력이 걸린 상태에서 1화 도입부를 쓰라는 것이다. 무슨 일이",
        "  벌어질지는 당신이 정한다 — 그것이 이 단계의 일이다.",
        f"  제목: {arc.get('title')}",
        one("starts_with", "시작 상태 (주인공이 지금 할 수 있다고 믿는 것)"),
        one("pressure", "조이는 힘"),
        one("ends_with", "이 구간 끝에서 더는 가능하지 않게 되는 것"),
    ]
    nots = [x for x in (arc.get("not_yet") or []) if str(x or "").strip()]
    if nots:
        lines.append("  아직 일어나지 않는 일 (여기서 터뜨리지 마라):")
        lines += [f"    - {x}" for x in nots]
    for r in (arc.get("cast_roles") or []):
        if isinstance(r, dict) and r.get("name"):
            lines.append(f"  {r.get('name')} — {r.get('role')}")
    return "\n".join([ln for ln in lines if ln != ""] + [""])


def inject_arc(template: str, arc: dict) -> str:
    if _SCENE_ANCHOR not in template:
        raise SystemExit("scene.txt 에서 {premise_json} 을 못 찾았습니다.")
    return template.replace(
        _SCENE_ANCHOR, _SCENE_ANCHOR + "\n" + arc_block_for_scene(arc), 1)


# ---------------------------------------------------------------- 보고서
def md_report(base_id: str, arcs: list, scene_obj: dict, scenes: list,
              episode: dict, old_scenes: list, old_arcs: list) -> str:
    out = [f"# 순서 실험 — P2 → W4 → 씬 → W5", "",
           f"바탕 run: `{base_id}` (P1·P2 재사용)", "",
           "## 1. W4 — 장면을 안 보고 잡은 큰 줄거리", ""]
    for a in arcs:
        out.append(f"### Arc {a.get('order')}. {a.get('title')}  "
                   f"[{a.get('arc_type')}] · 예상 {a.get('estimated_episode_count')}화")
        for key, label in (("starts_with", "시작 상태"), ("pressure", "조이는 힘"),
                           ("ends_with", "끝 상태")):
            if not story.is_blank(a.get(key)):
                out.append(f"- {label}: {a.get(key)}")
        for x in a.get("not_yet") or []:
            out.append(f"- 아직 아님: {x}")
        out.append(f"- 한 줄: {a.get('summary')}")
        for q in a.get("opens") or []:
            out.append(f"- 여는 질문: {q}")
        for q in a.get("closes") or []:
            out.append(f"- 닫는 질문: {q}")
        out.append("")

    out += ["## 2. 씬 — Arc 1 압력 위에서 쓴 1화 도입부", "",
            f"제목: **{scene_obj.get('title')}**", "",
            f"훅: {scene_obj.get('hook')}", ""]
    for i, s in enumerate(scenes, 1):
        out.append(f"### 장면 {i}. {s.get('one_line')}")
        out.append(f"- 선택: {s.get('choice')}")
        out.append(f"- 달라진 것: {s.get('changed')}")
        out.append("")
        out.append(str(s.get("text") or "").strip())
        out.append("")

    out += ["## 3. W5 — 1화 설계", "",
            f"제목: **{episode.get('title')}**", "",
            str(episode.get("summary") or ""), ""]
    setting = episode.get("setting") or {}
    if setting:
        out.append("| 무대 | |")
        out.append("| --- | --- |")
        for k, label in (("place", "장소"), ("time", "시간"), ("weather", "날씨"),
                         ("light", "빛"), ("props", "소품"), ("movement", "동선")):
            v = setting.get(k)
            if v:
                out.append(f"| {label} | {v if not isinstance(v, list) else ', '.join(map(str, v))} |")
        out.append("")
    for key, label in (("opens", "이 화가 여는 질문"), ("closes", "이 화가 닫는 질문")):
        for q in episode.get(key) or []:
            out.append(f"- {label}: "
                       f"{q if not isinstance(q, dict) else q.get('text') or q}")
    st = episode.get("stinger")
    if st:
        out.append("")
        out.append(f"- 스팅어: {st if not isinstance(st, dict) else st.get('text') or st}")

    out += ["", "---", "", "## 비교 — 지금 파이프라인(씬 먼저)이 낸 것", ""]
    for a in old_arcs[:1]:
        out.append(f"- 옛 Arc 1: {a.get('summary')}")
    for i, s in enumerate(old_scenes, 1):
        out.append(f"- 옛 장면 {i}: {s.get('one_line')}")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- 본체
def main() -> int:
    ap = argparse.ArgumentParser(description="P2 → W4 → 씬 → W5 순서 실험")
    ap.add_argument("--run", required=True, help="바탕이 되는 story run_id")
    ap.add_argument("--out", default=str(STORY / "runs"))
    ap.add_argument("--result-dir", default=str(HERE / "out"))
    ap.add_argument("--provider", choices=("anthropic", "gemini", "openai"))
    ap.add_argument("--model")
    ap.add_argument("--judge-model")
    ap.add_argument("--max-tokens", type=int, default=8000)
    ap.add_argument("--scenes", type=int, default=3)
    ap.add_argument("--max-retries", type=int, default=2)
    ap.add_argument("--mock", action="store_true")
    args = ap.parse_args()

    base = Path(args.out) / args.run
    if not base.exists():
        raise SystemExit(f"run 폴더가 없습니다: {base}")

    p1 = json.loads((base / "p1.json").read_text(encoding="utf-8"))
    p2 = json.loads((base / "p2.json").read_text(encoding="utf-8"))
    meta = json.loads((base / "meta.json").read_text(encoding="utf-8"))
    seed_path = base / "seed.json"
    seed = json.loads(seed_path.read_text(encoding="utf-8")) if seed_path.exists() else {}
    old_scene_obj = json.loads((base / "scenes.json").read_text(encoding="utf-8"))
    old_scenes = old_scene_obj.get("scenes") or []
    old_arcs_path = base / "webtoon" / "arcs.json"
    old_arcs = (json.loads(old_arcs_path.read_text(encoding="utf-8")).get("arcs") or []) \
        if old_arcs_path.exists() else []
    idea = meta.get("input", {}).get("one_line", "") or seed.get("one_line", "")

    ps_story = story.load_prompts()
    ps_wt = story.load_prompts(contract=webtoon.WEBTOON_CONTRACT)

    provider, model, judge_model = story.resolve_provider(args)
    backend = story.MockBackend(provider) if args.mock else story.make_backend(provider)
    story.describe_setup(provider, model, judge_model, args.mock)
    caller = story.Caller(backend, model, judge_model, args.max_tokens)
    usage = story.Usage()

    stamp = time.strftime("%Y%m%dT%H%M%S")
    outdir = Path(args.result_dir) / f"{stamp}-{args.run[-6:]}"
    outdir.mkdir(parents=True, exist_ok=True)
    story.log(f"결과: {outdir}")

    # ---------------------------------------------------------- 2. W4
    card_no_scenes = strip_scene_block(
        webtoon.build_engine_card(p1, p2, idea, [], seed=seed))
    (outdir / "engine_card_w4.txt").write_text(card_no_scenes, encoding="utf-8")

    sheet = json.dumps(p1, ensure_ascii=False, separators=(",", ":"))
    feedback = ""
    for attempt in range(args.max_retries + 1):
        arcs_payload, _ = caller.json_call(
            "W4",
            story.render(ps_wt.texts["w4"], {
                "engine_card": card_no_scenes, "character_sheet": sheet,
                "user_memory": "", "retry_feedback": feedback,
            }),
            story.TEMP_CREATIVE, usage)
        failures = webtoon.gate_arcs(arcs_payload)
        if not failures:
            break
        story.log(f"  W4 게이트 실패 {len(failures)}건 (시도 {attempt + 1})")
        for f in failures:
            story.log(f"      - {f}")
        feedback = story.feedback_block("\n".join(f"- {f}" for f in failures))
    else:
        raise SystemExit("W4 게이트 재시도 소진")

    arcs = arcs_payload["arcs"]
    webtoon.write_json(outdir / "arcs.json", arcs_payload)
    story.log(f"  W4 통과: Arc {len(arcs)}개 "
              f"(반전 {sum(1 for a in arcs if a.get('arc_type') == '반전')})")
    arc1 = arcs[0]

    # ---------------------------------------------------------- 3. 씬
    setting_text = json.dumps(p2, ensure_ascii=False) + json.dumps(p1, ensure_ascii=False)
    scene_template = inject_arc(ps_story.texts["scene"], arc1)
    (outdir / "scene_prompt_head.txt").write_text(
        arc_block_for_scene(arc1), encoding="utf-8")

    fix_directive = ""
    scenes: list = []
    scene_obj: dict = {}
    for attempt in range(args.max_retries + 1):
        scene_obj, _ = caller.json_call(
            "SCENE",
            story.render(scene_template, {
                "scene_count": args.scenes,
                "idea": idea,
                "character_sheet_json": sheet,
                "premise_json": json.dumps(p2, ensure_ascii=False, separators=(",", ":")),
                "fix_directive": fix_directive,
                "user_memory": "",
            }),
            story.TEMP_CREATIVE, usage)
        scenes = story.parse_scenes(scene_obj)
        if not scenes:
            raise SystemExit("SCENE 파싱 실패")
        hits = story.check_scenes(scenes, idea, setting_text, p1, scene_obj)
        if not hits:
            story.log(f"  장면 점검 통과 ({len(scenes)}장면)")
            break
        story.log(f"  장면 점검 걸림 {[h['name'] for h in hits]} (시도 {attempt + 1})")
        fix_directive = story.scene_fix_block(hits)
    webtoon.write_json(outdir / "scenes.json", scene_obj)

    # ---------------------------------------------------------- 4. W5
    card = webtoon.build_engine_card(p1, p2, idea, scenes, seed=seed)
    (outdir / "engine_card_w5.txt").write_text(card, encoding="utf-8")

    ledger = webtoon.Ledger(str(p2.get("engine_question") or ""))
    state = webtoon.SeriesState(run_id=args.run)
    state.seed_cast(p1.get("supporting_cast"))

    episode, _ = caller.json_call(
        "W5",
        story.render(ps_wt.texts["w5"], {
            "engine_card": card,
            "series_arc": webtoon.series_arc_block(arcs, arc1),
            "arc_json": json.dumps(arc1, ensure_ascii=False, separators=(",", ":")),
            "series_state": state.brief(ledger),
            "user_memory": "",
            "retry_feedback": "",
        }),
        story.TEMP_CREATIVE, usage)
    webtoon.write_json(outdir / "episode1.json", episode)

    # ---------------------------------------------------------- 보고서
    (outdir / "report.md").write_text(
        md_report(args.run, arcs, scene_obj, scenes, episode, old_scenes, old_arcs),
        encoding="utf-8")
    # as_dict() 는 합계만 준다. "어느 단계가 몇 번 돌았나" 를 나중에 못 보면
    # 이 실험이 무엇을 다시 돌렸는지 증명할 수가 없다 — records 를 같이 남긴다.
    webtoon.write_json(outdir / "usage.json",
                       {**usage.as_dict(), "records": usage.records})
    cost = usage.cost()
    story.log(f"완료 · 호출 {usage.calls}회 · {usage.total:,}토큰 · "
              f"{story.cost_text(cost['usd'])}")
    story.log(f"읽을 것: {outdir / 'report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
