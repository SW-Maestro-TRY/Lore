"""한 컷만 다시 그려 배경 프롬프트를 실험한다.

파이프라인을 안 건드린다. 이미 돌린 run 의 log.jsonl 에 **실제로 나갔던
프롬프트가 통째로 남아 있으므로**, 그것을 그대로 꺼내 배경 지시만 덧붙이고
같은 모델로 한 장 다시 그린다. 그림체·대사·레퍼런스는 전부 그대로라, 나온
차이는 배경 지시 때문이라고 말할 수 있다.

    # 무엇이 나갈지 보기만 한다 (과금 없음)
    python run_one.py --run 20260827T090004-ed5bfe --scene 1 --dry-run

    # 실제로 한 장 그린다 (과금 — 장당 대략 0.07 USD / 100원)
    python run_one.py --run 20260827T090004-ed5bfe --scene 1

결과는 bg-experiment/out/<run>/scene<N>_<블록이름>.png 로 떨어지고, 옆에
같은 이름의 .txt 로 나간 프롬프트 전문을 남긴다 — 나중에 무엇을 바꿔서 이
그림이 나왔는지 되짚을 수 있어야 한다.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HARNESS = HERE.parent / "webtoon-harness"
sys.path.insert(0, str(HARNESS))

OUTPUTS = HARNESS / "outputs"
STORY_RUNS = HERE.parent / "story-harness" / "runs"


def load_prompt(run_id: str, scene: int, style: str | None) -> tuple[str, dict]:
    """log.jsonl 에서 그 장의 이미지 호출을 찾아 프롬프트를 그대로 돌려준다.

    같은 장을 여러 번 그린 run 이 있다(그림체를 바꿔 A/B 를 했거나, 검수가
    다시 그렸거나). 그럴 때는 **마지막 것**을 쓴다 — 지금 폴더에 남아 있는
    그림이 그것이기 때문이다. --style 을 주면 그 그림체의 것만 고른다.
    """
    log = OUTPUTS / run_id / "ep1" / "log.jsonl"
    if not log.exists():
        sys.exit(f"log.jsonl 이 없습니다: {log}")
    rows = [json.loads(l) for l in log.read_text(encoding="utf-8").splitlines() if l.strip()]
    imgs = [r for r in rows if r.get("kind") == "image"]
    if style:
        imgs = [r for r in imgs if r.get("style") == style]
    if not imgs:
        sys.exit(f"이미지 호출 기록이 없습니다 (style={style})")
    # 장 번호는 로그에 없다. 호출 순서가 곧 장 순서이고, 같은 그림체 안에서
    # 5장이 한 묶음으로 돈다 — 그래서 뒤에서부터 한 묶음을 떼어 그 안에서 고른다.
    per_round = len({r["prompt"] for r in imgs})
    last_round = imgs[-per_round:] if per_round <= len(imgs) else imgs
    if not 1 <= scene <= len(last_round):
        sys.exit(f"scene 은 1~{len(last_round)} 사이여야 합니다")
    row = last_round[scene - 1]
    return row["prompt"], row


def reference_images(run_id: str) -> list[Path]:
    """캐릭터 시트. 이게 빠지면 인물이 딴 사람이 되어 배경 비교가 안 된다."""
    sheet = STORY_RUNS / run_id / "charsheet" / "sheet_c1.png"
    return [sheet] if sheet.exists() else []


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="run_id (예: 20260827T090004-ed5bfe)")
    ap.add_argument("--scene", type=int, default=1)
    ap.add_argument("--style", default=None, help="log 에서 고를 그림체 (webtoon/pastel…)")
    ap.add_argument("--block", default="",
                    help="덧붙일 배경 지시 파일 (bg-experiment 안). 비우면 안 붙인다")
    ap.add_argument("--swap-style", default="",
                    help="기록된 프롬프트 속 그림체 문구를 config 의 이 그림체로 "
                         "바꿔치기한다 (예: webtoon). --contract 와 같이 쓴다")
    ap.add_argument("--contract", default="v2",
                    help="--swap-style 이 읽을 style_contract (v1|v2)")
    ap.add_argument("--tag", default="", help="결과 파일명에 쓸 꼬리표")
    ap.add_argument("--model", default="gpt-image-2")
    ap.add_argument("--quality", default="high")
    ap.add_argument("--dry-run", action="store_true", help="나갈 프롬프트만 보고 끝낸다")
    args = ap.parse_args()

    prompt, row = load_prompt(args.run, args.scene, args.style)
    full = prompt.rstrip()
    swapped = ""

    # 그림체 문구 통째로 바꿔치기. 덧붙이는 것과 다르다 — 기록된 프롬프트 안에
    # 박혀 있는 옛 그림체 문구를 **같은 자리에서** 새것으로 갈아 끼우므로,
    # 앞뒤 문맥(캐릭터 서술·장면 서술·말풍선 지시)이 그대로 유지된다. 덧붙이면
    # 앞의 옛 지시와 새 지시가 같이 남아 서로 싸운다.
    if args.swap_style:
        import yaml                                          # noqa: E402
        sys.path.insert(0, str(HARNESS))
        import run as harness_run                            # noqa: E402
        cfg = yaml.safe_load((HARNESS / "config.yaml").read_text(encoding="utf-8"))
        old_name = args.style or row.get("style")
        old_text = harness_run.select_style(
            {**cfg, "style_contract": "v1"}, old_name).strip()
        new_text = harness_run.select_style(
            {**cfg, "style_contract": args.contract}, args.swap_style).strip()
        if old_text not in full:
            sys.exit(f"기록된 프롬프트에서 '{old_name}' 그림체 문구를 못 찾았습니다 "
                     "— config 가 그 사이 바뀐 것 같습니다.")
        full = full.replace(old_text, new_text)
        swapped = f"{old_name} → {args.swap_style}({args.contract})"

    block = ""
    if args.block:
        block = (HERE / args.block).read_text(encoding="utf-8").strip()
        # **맨 끝에** 붙인다. 앞에 넣으면 뒤따르는 장면 서술(영화 언어)에 덮인다.
        full = f"{full}\n\n{block}"
    full += "\n"

    out_dir = HERE / "out" / args.run
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = args.tag or (f"{args.contract}_{args.swap_style}" if args.swap_style
                       else Path(args.block).stem)
    stem = f"scene{args.scene}_{tag}"
    (out_dir / f"{stem}.txt").write_text(full, encoding="utf-8")

    print(f"원본 {len(prompt):,}자 → 최종 {len(full):,}자"
          + (f" | 그림체 교체: {swapped}" if swapped else "")
          + (f" | 블록 +{len(block):,}자" if block else ""))
    print(f"그림체(로그)  : {row.get('style')}")
    print(f"레퍼런스      : {[p.name for p in reference_images(args.run)] or '(없음)'}")
    print(f"프롬프트 저장 : {out_dir / (stem + '.txt')}")
    if args.dry_run:
        print("\n--dry-run 이라 여기서 멈춥니다 (과금 없음).")
        return

    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        env = HARNESS / ".env"
        if env.exists():
            for line in env.read_text(encoding="utf-8").splitlines():
                if line.startswith("OPENAI_API_KEY="):
                    key = line.split("=", 1)[1].strip().strip('"').strip("'")
                    break
    if not key:
        sys.exit("OPENAI_API_KEY 를 못 찾았습니다 (webtoon-harness/.env 확인)")

    from providers.base import GenRequest                     # noqa: E402
    from providers.openai_images import OpenAIProvider         # noqa: E402

    client = OpenAIProvider(args.model, key,
                          {"quality": args.quality, "size": "1024x1536"})
    res = client.generate(GenRequest(prompt=full,
                                     images=reference_images(args.run)))
    dest = out_dir / f"{stem}.png"
    dest.write_bytes(res.image_bytes)
    print(f"\n그렸습니다: {dest}")
    usage = (res.meta or {}).get("usage") or {}
    if usage:
        print(f"토큰: in {usage.get('input_tokens')} / out {usage.get('output_tokens')}")


if __name__ == "__main__":
    main()
