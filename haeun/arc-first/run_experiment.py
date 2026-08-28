#!/usr/bin/env python3
"""순서 실험 — P1(재료) → P2(엔진) → W4(방향) → SCENE(선택) → W5(집필).

## 왜 이걸 만들었나

지금 파이프라인의 순서는 `P2 → 씬 → W4 → W5` 이고, 씬이 쓴 1화 도입부가 엔진
카드에 산문 전문으로 실린다("1화의 컷은 이 장면을 컷으로 옮기는 일이다").
그래서 1화를 정하는 것은 사실상 씬이고 W4·W5 는 그것을 받아쓴다.

1차 실험(2026-08-29)에서 순서를 `W4 → 씬` 으로 바꿔 봤더니 Arc 는 압력 설계로
바뀌었는데 **1화는 거의 그대로였다.** 원인이 더 앞에 있었다 — `p1.json` 의
`trigger_situations` 세 줄("기자회견장에 섰을 때" / "공개하라는 압박" / "복도에서
마스크를 벗었는데 누군가 목격한다")이 그대로 1화의 세 장면이었다. 씬도 W4 도
p1 전문을 받으므로, 구체적인 상황 세 개가 눈앞에 있으면 그게 1화가 된다.

## 그래서 이번에 바꾼 것

**씬을 「1화 도입부 집필기」에서 「핵심사건 후보 생성기」로 바꾼다.**
씬은 후보 4개를 내고, 사람이 그중 하나를 고르고, W5 가 그것으로 1화를 쓴다.

  P1    재료 창고 (A/B · want/need · 관계 · trigger_situations)
  P2    이야기 엔진
  W4    방향 — 이 Arc 가 어떤 압력으로 상태를 어디까지 바꾸는가
  SCENE 선택 — 모순을 가장 잔인하게 터뜨릴 사건 후보들
  W5    집필 — 고른 사건으로 실제 1화

씬이 물어야 하는 것은 "설정에 맞는 사건" 이 아니라 "독자가 가장 궁금해할
선택" 이다. 그 기준을 prompts/scene_candidates.txt 가 들고 있다.

## 쓰는 법

    python run_experiment.py candidates --run <run_id>       # 후보 4개
    python run_experiment.py episode  --dir <out/...> --pick B   # 고른 것으로 1화

story-harness 는 손대지 않는다. 여기서 그 모듈을 불러다 쓰기만 한다.
"""

from __future__ import annotations

import argparse
import json
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
# 엔진 카드는 "[1화 도입부 — 사람이 통과시킨 장면]" 블록에 씬이 쓴 산문을 통째로
# 싣고 "이 장면을 컷으로 옮기는 일이다" 라고 못박는다. 씬이 후보 생성기가 되면
# 그 블록에 들어갈 것이 달라진다 — W4 에게는 아무것도, W5 에게는 사람이 고른
# 핵심사건 하나.
_SCENE_BLOCK = "[1화 도입부 — 사람이 통과시킨 장면]"
_CARD_END = "=== /엔진 카드 ==="


def _split_card(card: str):
    start = card.find(_SCENE_BLOCK)
    if start < 0:
        return card.rstrip("\n"), ""
    tail = card.find(_CARD_END, start)
    return card[:start].rstrip("\n"), (card[tail:] if tail >= 0 else _CARD_END)


def strip_scene_block(card: str) -> str:
    """장면을 아예 안 보여준다. W4 와 후보 생성이 쓰는 카드."""
    head, tail = _split_card(card)
    return head + "\n" + (tail or _CARD_END)


def card_with_core_event(card: str, c: dict) -> str:
    """사람이 고른 핵심사건 하나를 실은 카드. W5 가 이걸로 1화를 쓴다.

    승인된 산문을 옮겨 적게 하던 자리에 **씨앗 하나**를 놓는다. 사건은 못 바꾸되
    장면·대사·순서·주변 인물은 W5 가 만든다 — 그게 이 단계의 일이다.
    """
    head, tail = _split_card(card)
    block = [
        "[1화 핵심사건 — 사람이 고른 것]",
        "  ★ 이 사건으로 1화를 쓴다. 사건 자체를 다른 것으로 바꾸지 마라.",
        "  ★ 다만 이건 줄거리가 아니라 **씨앗**이다. 장면·대사·순서·주변 인물은",
        "    당신이 만든다 — 그게 이 단계의 일이다. 아래 문장을 옮겨 적지 마라.",
        f"  사건: {c.get('core_event')}",
        f"  하은이 놓이는 선택: {c.get('dilemma')}",
        f"    행동하면: {c.get('cost_if_acts')}",
        f"    안 하면: {c.get('cost_if_refuses')}",
        f"  터뜨리는 모순: {c.get('contradiction')}",
        f"  되돌릴 수 없게 되는 것: {c.get('irreversible')}",
        f"  이 화가 끝난 뒤 독자에게 남아야 하는 질문: {c.get('question_after')}",
    ]
    return head + "\n\n" + "\n".join(block) + "\n" + (tail or _CARD_END)


# ---------------------------------------------------------------- 후보 게이트
CANDIDATE_FIELDS = (
    ("core_event", "1화의 핵심사건"),
    ("dilemma", "하은이 놓이는 양자택일"),
    ("cost_if_acts", "행동했을 때 치르는 값"),
    ("cost_if_refuses", "안 했을 때 치르는 값"),
    ("contradiction", "이 사건이 터뜨리는 모순"),
    ("pressure_axis", "무엇이 미는가"),
    ("irreversible", "되돌릴 수 없게 되는 것"),
    ("question_after", "직후 독자에게 남는 질문"),
    ("why_not_safe", "왜 뻔한 사건이 아닌가"),
)

# 후보끼리 겹쳤다고 볼 문턱. 넘으면 사실상 같은 후보다.
SAME_CANDIDATE = 0.72
# p1 의 trigger_situations 를 그대로 베꼈다고 볼 문턱.
COPIED_TRIGGER = 0.55
# 엔진급 질문을 화 질문으로 베꼈다고 볼 문턱 (webtoon 게이트와 같은 값).
ENGINE_ECHO = webtoon.ENGINE_ECHO_THRESHOLD


def gate_candidates(payload: dict, want: int, triggers: list, engine_q: str) -> list:
    """후보가 **고를 만한 것들**인가. 내용의 재미는 사람이 본다 — 여기서는 형식만."""
    rows = payload.get("candidates")
    if not isinstance(rows, list) or not rows:
        return ["candidates 가 배열이 아니거나 비어 있습니다."]

    failures = []
    if len(rows) != want:
        failures.append(f"후보가 {len(rows)}개입니다. {want}개여야 합니다.")

    norm_trig = [webtoon._norm_q(t) for t in (triggers or []) if str(t or "").strip()]
    base_q = webtoon._norm_q(engine_q)

    for i, c in enumerate(rows):
        if not isinstance(c, dict):
            failures.append(f"{i + 1}번째 후보가 객체가 아닙니다.")
            continue
        label = c.get("label") or f"{i + 1}번째"
        for key, why in CANDIDATE_FIELDS:
            if not str(c.get(key) or "").strip():
                failures.append(f"후보 {label}: {key} 가 비어 있습니다 ({why}).")

        # p1 의 상황을 그대로 옮겨 적은 후보. 1차 실험에서 1화 전체가 이렇게 나왔다.
        ev = webtoon._norm_q(c.get("core_event"))
        for t, raw in zip(norm_trig, triggers):
            if webtoon._similarity(ev, t) >= COPIED_TRIGGER:
                failures.append(
                    f"후보 {label}: 핵심사건이 캐릭터 카드의 trigger_situations 를 "
                    f"그대로 옮긴 것입니다 (\"{str(raw)[:30]}…\"). 그 상황이 말하는 "
                    "모순을 가져오되, 그것이 터질 다른 자리를 찾으세요.")
                break

        if base_q and webtoon._similarity(
                webtoon._norm_q(c.get("question_after")), base_q) >= ENGINE_ECHO:
            failures.append(
                f"후보 {label}: question_after 가 작품 전체의 질문(엔진급)과 거의 "
                "같습니다. 방금 벌어진 일에서 나오는 질문을 적으세요.")

    # 후보끼리 달라야 한다. 넷이 다 같은 압박이면 후보가 하나인 것과 같다.
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            a, b = rows[i], rows[j]
            if not (isinstance(a, dict) and isinstance(b, dict)):
                continue
            for key, what in (("pressure_axis", "미는 힘"), ("core_event", "핵심사건")):
                r = webtoon._similarity(webtoon._norm_q(a.get(key)),
                                        webtoon._norm_q(b.get(key)))
                if r >= SAME_CANDIDATE:
                    failures.append(
                        f"후보 {a.get('label')} 와 {b.get('label')} 의 {what}가 "
                        f"거의 같습니다. 후보는 서로 다른 축이어야 고를 의미가 "
                        "있습니다.")
    return failures


# ---------------------------------------------------------------- 공용
def load_base(out_root: str, run_id: str) -> dict:
    base = Path(out_root) / run_id
    if not base.exists():
        raise SystemExit(f"run 폴더가 없습니다: {base}")
    read = lambda n: json.loads((base / n).read_text(encoding="utf-8"))
    seed = read("seed.json") if (base / "seed.json").exists() else {}
    meta = read("meta.json")
    return {
        "dir": base, "p1": read("p1.json"), "p2": read("p2.json"),
        "meta": meta, "seed": seed,
        "idea": meta.get("input", {}).get("one_line", "") or seed.get("one_line", ""),
    }


def make_caller(args):
    provider, model, judge_model = story.resolve_provider(args)
    backend = story.MockBackend(provider) if args.mock else story.make_backend(provider)
    story.describe_setup(provider, model, judge_model, args.mock)
    return story.Caller(backend, model, judge_model, args.max_tokens), story.Usage()


def save_usage(outdir: Path, usage) -> None:
    # 합계만 남기면 "어느 단계를 몇 번 돌렸나" 를 나중에 증명할 수 없다.
    prev = {}
    path = outdir / "usage.json"
    if path.exists():
        prev = json.loads(path.read_text(encoding="utf-8"))
    webtoon.write_json(path, {**usage.as_dict(),
                             "records": (prev.get("records") or []) + usage.records})
    c = usage.cost()
    story.log(f"  호출 {usage.calls}회 · {usage.total:,}토큰 · "
              f"{story.cost_text(c['usd'])}")


# ---------------------------------------------------------------- 1) 후보
def cmd_candidates(args) -> int:
    b = load_base(args.out, args.run)
    caller, usage = make_caller(args)
    ps_wt = story.load_prompts(contract=webtoon.WEBTOON_CONTRACT)

    outdir = Path(args.result_dir) / f"{time.strftime('%Y%m%dT%H%M%S')}-{args.run[-6:]}"
    outdir.mkdir(parents=True, exist_ok=True)
    story.log(f"결과: {outdir}")

    card = strip_scene_block(
        webtoon.build_engine_card(b["p1"], b["p2"], b["idea"], [], seed=b["seed"]))
    (outdir / "engine_card.txt").write_text(card, encoding="utf-8")
    sheet = json.dumps(b["p1"], ensure_ascii=False, separators=(",", ":"))

    # ---- W4 (방향) — 이미 잡아 둔 것이 있으면 다시 안 돈다. 큰 줄거리는 작품에
    #      한 번 정하는 것이고, 화마다 다시 잡으면 앞 화와 방향이 어긋난다.
    if args.arcs:
        arcs_payload = json.loads(Path(args.arcs).read_text(encoding="utf-8"))
        story.log(f"  W4: 기존 것을 재사용 ({args.arcs})")
    else:
        feedback = ""
        for attempt in range(args.max_retries + 1):
            arcs_payload, _ = caller.json_call(
                "W4",
                story.render(ps_wt.texts["w4"], {
                    "engine_card": card, "character_sheet": sheet,
                    "user_memory": "", "retry_feedback": feedback}),
                story.TEMP_CREATIVE, usage)
            fails = webtoon.gate_arcs(arcs_payload)
            if not fails:
                break
            story.log(f"  W4 게이트 실패 {len(fails)}건 (시도 {attempt + 1})")
            for f in fails:
                story.log(f"      - {f}")
            feedback = story.feedback_block("\n".join(f"- {f}" for f in fails))
        else:
            raise SystemExit("W4 게이트 재시도 소진")
        story.log(f"  W4 통과: Arc {len(arcs_payload['arcs'])}개")
    webtoon.write_json(outdir / "arcs.json", arcs_payload)
    arc1 = arcs_payload["arcs"][0]

    # ---- SCENE (선택) — 1화를 쓰지 않는다. 고를 거리를 만든다.
    template = (HERE / "prompts" / "scene_candidates.txt").read_text(encoding="utf-8")
    triggers = [t for t in (b["p1"].get("trigger_situations") or []) if str(t or "").strip()]
    engine_q = str(b["p2"].get("engine_question") or "")

    fix = ""
    for attempt in range(args.max_retries + 1):
        payload, _ = caller.json_call(
            "SCENE",
            story.render(template, {
                "candidate_count": args.n,
                "engine_card": card,
                "character_sheet": sheet,
                "arc_json": json.dumps(arc1, ensure_ascii=False, separators=(",", ":")),
                "user_memory": "",
                "fix_directive": fix,
            }),
            story.TEMP_CREATIVE, usage)
        fails = gate_candidates(payload, args.n, triggers, engine_q)
        if not fails:
            story.log(f"  후보 {args.n}개 통과")
            break
        story.log(f"  후보 게이트 실패 {len(fails)}건 (시도 {attempt + 1})")
        for f in fails:
            story.log(f"      - {f}")
        fix = story.feedback_block("\n".join(f"- {f}" for f in fails))
    else:
        story.log("  ! 게이트를 통과하지 못했지만 마지막 후보를 남깁니다 — 사람이 봅니다.")

    # 어느 run 을 바탕으로 만든 후보인지 같이 남긴다 — 다음 단계가 이걸 보고
    # p1·p2 를 찾는다. 폴더 이름만으로는 run_id 를 복원할 수 없다.
    payload["run_id"] = args.run
    webtoon.write_json(outdir / "candidates.json", payload)
    (outdir / "candidates.md").write_text(
        candidates_md(arc1, payload.get("candidates") or []), encoding="utf-8")
    save_usage(outdir, usage)
    story.log(f"읽을 것: {outdir / 'candidates.md'}")
    story.log(f"고른 뒤: python run_experiment.py episode --dir {outdir} --pick A")
    return 0


def candidates_md(arc: dict, rows: list) -> str:
    out = ["# 1화 핵심사건 후보", "",
           f"놓이는 자리 — Arc 1. {arc.get('title')}", "",
           f"- 시작 상태: {arc.get('starts_with')}",
           f"- 조이는 힘: {arc.get('pressure')}",
           f"- 끝 상태: {arc.get('ends_with')}", ""]
    for c in rows:
        out += [f"## {c.get('label')}. {c.get('core_event')}", "",
                f"- **선택**: {c.get('dilemma')}",
                f"  - 행동하면: {c.get('cost_if_acts')}",
                f"  - 안 하면: {c.get('cost_if_refuses')}",
                f"- 터뜨리는 모순: {c.get('contradiction')}",
                f"- 미는 힘: {c.get('pressure_axis')}",
                f"- 되돌릴 수 없게 되는 것: {c.get('irreversible')}",
                f"- 직후 질문: {c.get('question_after')}",
                f"- 왜 뻔하지 않은가: {c.get('why_not_safe')}", ""]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- 2) 1화
def cmd_episode(args) -> int:
    outdir = Path(args.dir)
    cands = json.loads((outdir / "candidates.json").read_text(encoding="utf-8"))
    arcs_payload = json.loads((outdir / "arcs.json").read_text(encoding="utf-8"))
    arcs = arcs_payload["arcs"]
    arc1 = arcs[0]

    picked = next((c for c in cands["candidates"]
                   if str(c.get("label")).strip().upper() == args.pick.strip().upper()),
                  None)
    if picked is None:
        raise SystemExit(f"후보 {args.pick} 를 못 찾았습니다: "
                         f"{[c.get('label') for c in cands['candidates']]}")
    story.log(f"고른 후보 {picked.get('label')}: {str(picked.get('core_event'))[:50]}…")

    run_id = args.run or cands.get("run_id")
    if not run_id:
        raise SystemExit("바탕 run_id 를 모릅니다. --run 으로 주세요.")
    b = load_base(args.out, run_id)
    caller, usage = make_caller(args)
    ps_wt = story.load_prompts(contract=webtoon.WEBTOON_CONTRACT)

    card = card_with_core_event(
        webtoon.build_engine_card(b["p1"], b["p2"], b["idea"], [], seed=b["seed"]),
        picked)
    (outdir / "engine_card_w5.txt").write_text(card, encoding="utf-8")

    ledger = webtoon.Ledger(str(b["p2"].get("engine_question") or ""))
    state = webtoon.SeriesState(run_id=b["meta"]["run_id"])
    state.seed_cast(b["p1"].get("supporting_cast"))

    feedback = ""
    episode = None
    for attempt in range(args.max_retries + 1):
        one, _ = caller.json_call(
            "W5",
            story.render(ps_wt.texts["w5"], {
                "engine_card": card,
                "series_arc": webtoon.series_arc_block(arcs, arc1),
                "arc_json": json.dumps(arc1, ensure_ascii=False, separators=(",", ":")),
                "series_state": state.brief(ledger),
                "user_memory": "",
                "retry_feedback": feedback,
            }),
            story.TEMP_CREATIVE, usage)
        # 코드로 볼 수 있는 것만 본다. 내용 판정(W6)은 이 실험의 범위가 아니다.
        ep_payload = {"arc_order": arc1.get("order"), "episodes": [one]}
        webtoon.assign_ids(ep_payload, ledger)
        fails = webtoon.gate_episodes_shape(ep_payload, ledger, None, None)
        episode = one
        if not fails:
            story.log(f"  1화 형식 게이트 통과 · 「{one.get('title')}」")
            break
        story.log(f"  1화 형식 게이트 실패 {len(fails)}건 (시도 {attempt + 1})")
        for f in fails:
            story.log(f"      - {f}")
        feedback = story.feedback_block("\n".join(f"- {f}" for f in fails))

    webtoon.write_json(outdir / "episode1.json", episode)
    (outdir / "episode1.md").write_text(episode_md(picked, episode), encoding="utf-8")
    save_usage(outdir, usage)
    story.log(f"읽을 것: {outdir / 'episode1.md'}")
    return 0


def episode_md(picked: dict, e: dict) -> str:
    out = [f"# 1화 — {e.get('title')}", "",
           f"고른 핵심사건 ({picked.get('label')}): {picked.get('core_event')}", "",
           "## 줄거리", "", str(e.get("summary") or ""), ""]
    why = e.get("why_now") or {}
    if why:
        out += ["## 왜 지금 이 행동인가", "",
                f"- 행동: {why.get('action')}",
                f"- 이유: {why.get('reason')}",
                f"- 화면에 보이는 근거: {why.get('shown_by')}", ""]
    st = e.get("setting") or {}
    if st:
        out += ["## 무대", "", "| | |", "| --- | --- |"]
        for k, label in (("place", "장소"), ("time", "시간"), ("weather", "날씨"),
                         ("light", "빛"), ("props", "소품"), ("movement", "동선")):
            v = st.get(k)
            if v:
                out.append(f"| {label} | "
                           f"{', '.join(map(str, v)) if isinstance(v, list) else v} |")
        out.append("")
    if e.get("beats"):
        out += ["## 핵심 행동", ""] + [f"{i}. {x}" for i, x in enumerate(e["beats"], 1)] + [""]
    out += ["## 이 화가 연 질문", ""]
    for q in e.get("questions_opened") or []:
        out.append(f"- {q.get('text') if isinstance(q, dict) else q}"
                   f"{' · ' + q.get('type') if isinstance(q, dict) and q.get('type') else ''}")
    stg = e.get("stinger") or {}
    if stg:
        out += ["", "## 스팅어", "",
                str(stg.get("text") if isinstance(stg, dict) else stg)]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- CLI
def main() -> int:
    ap = argparse.ArgumentParser(description="P1 → P2 → W4 → SCENE(후보) → W5")
    ap.add_argument("--out", default=str(STORY / "runs"), help="story runs 폴더")
    ap.add_argument("--result-dir", default=str(HERE / "out"))
    ap.add_argument("--provider", choices=("anthropic", "gemini", "openai"))
    ap.add_argument("--model")
    ap.add_argument("--judge-model")
    ap.add_argument("--max-tokens", type=int, default=8000)
    ap.add_argument("--max-retries", type=int, default=2)
    ap.add_argument("--mock", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("candidates", help="1화 핵심사건 후보를 만든다")
    c.add_argument("--run", required=True, help="바탕이 되는 story run_id")
    c.add_argument("--arcs", help="이미 잡아 둔 arcs.json (있으면 W4 를 안 돈다)")
    c.add_argument("-n", type=int, default=4, help="후보 개수 (기본 4)")
    c.set_defaults(func=cmd_candidates)

    e = sub.add_parser("episode", help="고른 후보로 1화를 쓴다")
    e.add_argument("--dir", required=True, help="candidates 가 만든 결과 폴더")
    e.add_argument("--pick", required=True, help="후보 label (A/B/C/D)")
    e.add_argument("--run", help="바탕 run_id (기본: 결과 폴더 이름에서)")
    e.set_defaults(func=cmd_episode)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
