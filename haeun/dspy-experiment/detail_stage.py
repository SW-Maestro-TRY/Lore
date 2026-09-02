#!/usr/bin/env python3
"""DSPy 실험 — new_harness 의 DETAIL 단계(장면 목록 -> 구체화)를
dspy.Signature 로 다시 짜서, 기존(손으로 쓴 프롬프트) 방식과 같은 입력으로
나란히 돌려 비교한다.

new_harness 는 읽기만 한다 — 여기서 한 줄도 고치지 않는다. 파싱(parse_detail)과
게이트(gate_detail)는 new_harness/run.py 것을 그대로 빌려 쓴다. 그래야 "출력이
같은 기준으로 채점됐다"를 보장할 수 있다 — 게이트를 따로 만들면 어느 쪽이
잘한 게 아니라 어느 쪽 게이트가 관대한지를 비교하게 된다.

사용법
  python detail_stage.py --run-id <id>            # 프롬프트만 만들어 본다 (0원)
  python detail_stage.py --run-id <id> --run       # 실제로 둘 다 호출해서 비교 (돈 든다)

--run-id 는 new_harness/runs/ 안에 이미 STORY 단계까지 끝나 있는(방향을
고른) run 이어야 한다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEW_HARNESS = HERE.parent / "new_harness"
sys.path.insert(0, str(NEW_HARNESS))

import run as nh          # new_harness/run.py — 프롬프트 조립·파싱·게이트를 그대로 빌린다
import llm as nh_llm       # new_harness/llm.py — provider 호출 계층 (재시도·비용 계산 포함)

import dspy
from pydantic import BaseModel, ConfigDict, Field


# --------------------------------------------------------------------- 스키마
#
# new_harness.parse_detail() 이 만드는 것과 같은 모양으로 맞춘다 — 그래야
# gate_detail() 을 고치지 않고 양쪽 출력에 그대로 쓸 수 있다.

class Learn(BaseModel):
    what: str
    how: str


class Guess(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    what: str
    from_: str = Field(alias="from")


class SceneDetail(BaseModel):
    id: int
    source: str
    function: str
    detail: str
    leads_to: str
    learns: list[Learn] = Field(default_factory=list)
    guesses: list[Guess] = Field(default_factory=list)


class DetailResult(BaseModel):
    scenes: list[SceneDetail]
    hidden: list[str] = Field(default_factory=list)


class DetailSignature(dspy.Signature):
    """장면 목록에 압축된 사건을 실제로 성립하게 만든다.

    장면을 길게 쓰는 게 아니라 빠진 원인과 근거를 되살리는 것이다. 필요한
    앎(learns)을 먼저 정하고 그것을 알 수 있는 자리를 그 장면에 놓는다 —
    소재를 먼저 정하고 알아낼 것을 짜내지 않는다. 확인한 것과 짐작한
    것(guesses)은 구분하고, 근거를 못 대는 앎/짐작은 버린다. 마지막 장면은
    감정이 아니라 다음 행동을 부르는 사건이나 발견으로 끝난다. hidden 에
    적힌 것의 답은 말하지 않는다.
    """

    plot: str = dspy.InputField(desc="1화 줄거리 2~3줄")
    scene_list: str = dspy.InputField(desc="번호 매겨진 장면 목록")
    character_info: str = dspy.InputField(desc="이름·설명·외관")
    genre: str = dspy.InputField()
    hidden_things: str = dspy.InputField(desc="이 화에서 끝까지 밝히지 않을 것")
    result: DetailResult = dspy.OutputField()


# --------------------------------------------------------------------- 입력

def load_fixture(run_id: str):
    run_dir = NEW_HARNESS / "runs" / run_id
    char = nh.read_character(run_dir / "input.json")
    pick = json.loads((run_dir / "pick.json").read_text(encoding="utf-8"))
    directions = json.loads((run_dir / "directions.json").read_text(encoding="utf-8"))
    direction = next(d for d in directions if d["n"] == pick["n"])
    return run_dir, char, direction


def dspy_inputs(char: dict, direction: dict) -> dict:
    fields_txt = "\n".join(f"- {k}: {v}" for k, v in char["fields"].items())
    character_info = f"이름: {char['name']}\n설명: {char['description']}\n{fields_txt}".strip()
    scene_list = "\n".join(f"{i}. {s}" for i, s in enumerate(direction["scenes"], 1))
    hidden = "\n".join(f"- {h}" for h in direction["hidden"]) or "(없음)"
    return dict(
        plot=direction["plot"],
        scene_list=scene_list,
        character_info=character_info,
        genre=direction["genre"] or char["genre"] or "(정해진 것 없음)",
        hidden_things=hidden,
    )


def to_gate_dict(result: DetailResult) -> dict:
    """gate_detail() 이 기대하는 모양(plain dict, 'from' 키)으로 되돌린다."""
    return json.loads(result.model_dump_json(by_alias=True))


def summarize(label: str, detail: dict, issues: list[str]) -> None:
    scenes = detail.get("scenes") or []
    avg_len = sum(len(s.get("detail", "")) for s in scenes) / max(1, len(scenes))
    learns = sum(len(s.get("learns") or []) for s in scenes)
    guesses = sum(len(s.get("guesses") or []) for s in scenes)
    print(f"\n[{label}] 장면 {len(scenes)}개 · detail 평균 {avg_len:.0f}자 · "
          f"learns {learns} · guesses {guesses} · 게이트 위반 {len(issues)}건")
    for one in issues:
        print(f"  - {one}")


# --------------------------------------------------------------------- 실행

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--run", action="store_true",
                     help="실제로 호출한다 (돈 든다). 기본은 프롬프트만 만들어 본다.")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    run_dir, char, direction = load_fixture(args.run_id)
    out_dir = Path(args.out) if args.out else HERE / "out" / args.run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    # ---- 기존 방식 프롬프트
    b_prompt = nh.compose("detail_prompt", nh.detail_block(char, direction, run_dir))
    (out_dir / "baseline_prompt.txt").write_text(b_prompt, encoding="utf-8")
    print(f"[기존] 프롬프트 {len(b_prompt)}자 -> {out_dir / 'baseline_prompt.txt'}")

    # ---- dspy 방식이 실제로 보낼 메시지 미리보기 (호출 없음)
    provider = nh_llm.provider_for("DETAIL")
    model = nh_llm.model_for("DETAIL", provider)
    lm = dspy.LM(f"{provider}/{model}", temperature=0.9, max_tokens=16000)
    dspy.settings.configure(lm=lm)
    predictor = dspy.Predict(DetailSignature)

    inputs = dspy_inputs(char, direction)
    adapter = dspy.ChatAdapter()
    messages = adapter.format(DetailSignature, [], inputs)
    d_preview = json.dumps(messages, ensure_ascii=False, indent=2)
    (out_dir / "dspy_prompt_preview.txt").write_text(d_preview, encoding="utf-8")
    print(f"[dspy] {provider}/{model} 로 보낼 메시지 -> {out_dir / 'dspy_prompt_preview.txt'}")

    if not args.run:
        print("\n--run 을 안 줘서 실제 호출은 안 했습니다 (0원).")
        return 0

    # ---- 실제 호출 (돈 든다)
    call = nh_llm.Call("DETAIL")
    print(f"\n[기존] {call.describe()} 호출 중…")
    b_text, b_meta = call(b_prompt)
    (out_dir / "baseline_raw.txt").write_text(b_text, encoding="utf-8")
    b_detail = nh.parse_detail(b_text)
    b_issues = nh.gate_detail(b_detail, direction)
    (out_dir / "baseline_detail.json").write_text(
        json.dumps(b_detail, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[dspy] {provider}/{model} 호출 중…")
    pred = predictor(**inputs)
    d_detail = to_gate_dict(pred.result)
    d_issues = nh.gate_detail(d_detail, direction)
    (out_dir / "dspy_detail.json").write_text(
        json.dumps(d_detail, ensure_ascii=False, indent=2), encoding="utf-8")

    summarize("기존", b_detail, b_issues)
    summarize("dspy", d_detail, d_issues)

    (out_dir / "gate_baseline.json").write_text(
        json.dumps(b_issues, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "gate_dspy.json").write_text(
        json.dumps(d_issues, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n결과 -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
