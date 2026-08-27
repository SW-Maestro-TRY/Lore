"""이미 만든 화의 **8단계만** 다시 돌린다. 7단계는 건드리지 않는다.

8단계는 컷이 확정된 뒤에 글자와 서사적 중요도만 정하는 자리라, 앞 단계를 다시
돌릴 이유가 없다. 프롬프트(prompts/w8.txt)를 고치고 그 효과만 보려고 화를 통째로
다시 뽑으면 7단계 값이 같이 흔들려서 무엇 때문에 달라졌는지 알 수 없다.

    python redo_w8.py --run 20260826T202930-ecdcb3 --episode 1 --dry-run
    python redo_w8.py --run 20260826T202930-ecdcb3 --episode 1

결과는 ep01_cuts.json 을 **덮어쓰지 않고** ep01_cuts.w8.json 으로 따로 쓴다 —
앞 결과와 나란히 놓고 비교할 수 있어야 한다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import webtoon as WT                                          # noqa: E402
from story import (Caller, Usage, load_prompts, make_backend,  # noqa: E402
                   resolve_provider, log, warn)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--episode", type=int, default=1)
    ap.add_argument("--provider", default=None)
    ap.add_argument("--model", default=None)
    ap.add_argument("--max-retries", type=int, default=2)
    ap.add_argument("--dry-run", action="store_true",
                    help="프롬프트만 조립해 보고 호출은 안 한다")
    args = ap.parse_args()

    wt_dir = HERE / "runs" / args.run / "webtoon"
    if not wt_dir.exists():
        sys.exit(f"없는 run 입니다: {wt_dir}")
    no = args.episode

    payload = json.loads((wt_dir / f"ep{no:02d}_cuts.json").read_text(encoding="utf-8"))
    card = (wt_dir / "engine_card.txt").read_text(encoding="utf-8")
    eps = json.loads((wt_dir / f"arc{payload.get('arc_order', 1)}_episodes.json")
                     .read_text(encoding="utf-8"))
    rows = eps if isinstance(eps, list) else (eps.get("episodes") or [])
    episode = next((e for e in rows
                    if int(e.get("order") or e.get("episode_order") or 0) == no), rows[0])

    # 장부 스냅샷은 파일에 통째로 저장돼 있지 않다. 8단계에 넘기는 것은 문자열
    # 하나뿐이라, 저장된 ledger.json 을 그대로 실어 보낸다 — 판정에 쓰는 재료가
    # 같으면 충분하고, 여기서 장부를 다시 굴릴 이유는 없다.
    ledger_path = wt_dir / "ledger.json"
    ledger_snapshot = (ledger_path.read_text(encoding="utf-8")
                       if ledger_path.exists() else "")

    ps = load_prompts(HERE / "prompts", WT.WEBTOON_CONTRACT)

    cuts = payload.get("cuts") or []
    print(f"컷 {len(cuts)}개 | 8단계 프롬프트 {len(ps.texts['w8']):,}자")
    before = {c.get("cut_number"): c.get("weight") for c in cuts}

    if args.dry_run:
        print("--dry-run — 호출하지 않았습니다.")
        return

    # resolve_provider 는 args 객체를 통째로 받아 (provider, model, judge) 를 준다.
    provider, model, _judge = resolve_provider(args)
    backend = make_backend(provider)   # 2번째 인자는 model 이 아니라 max_retries 다
    print(f"모델: {provider} / {model}")
    usage = Usage()
    caller = Caller(backend, model, _judge, WT.DEFAULT_MAX_TOKENS)

    def call(stage, label, prompt, temp, verdict_of=None):
        obj, _ = caller.json_call(stage, prompt, temp, usage)
        return obj

    payload, _, notes = WT.solve_text(
        ps, call, card, episode, payload, ledger_snapshot,
        no, args.max_retries, facts=[], author_note="", memory_text="")

    for n in notes:
        warn(f"  {n}")

    out = wt_dir / f"ep{no:02d}_cuts.w8.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n저장: {out}")

    cuts = payload.get("cuts") or []
    print("\n컷  중요도    무게(전 → 후)   샷")
    for c in cuts:
        n = c.get("cut_number")
        print(f"  {n:>2}  {(c.get('narrative_weight') or '(없음)'):<8}  "
              f"{str(before.get(n)):<7}→ {str(c.get('weight')):<7} "
              f"{str(c.get('shot') or '')[:16]}")
    import collections
    print("\n중요도 분포:", dict(collections.Counter(
        c.get("narrative_weight") or "(없음)" for c in cuts)))
    print("무게 분포  :", dict(collections.Counter(c.get("weight") for c in cuts)))
    print(f"비용: {usage.total_krw():,}원" if hasattr(usage, "total_krw") else "")


if __name__ == "__main__":
    main()
