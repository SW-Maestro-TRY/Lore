#!/usr/bin/env python3
"""
서비스용 모션 후처리 — 16프레임 격자 한 장을 움짤 하나(webp)로 만든다.

  python3 service_motion_post.py <격자.png> <출력폴더> --profile "script=state16_v3, align=seat, ..."
  → 출력폴더/motion.webp

  --profile  ★필수. 이 동작의 후처리 프로파일(pipeline/v1/motion_post_profiles.txt 의 한 줄).
             `script=<모듈>` 로 어느 후처리를 탈지 고르고, 나머지는 그 모듈 main() 의 인자다.

★ 어느 후처리를 탈지는 **동작마다 다르다** — 자바가 `--profile` 로 넘긴다.
  16프레임은 한 칸이 독립이 아니라 한 동작이 이어지는 루프라, "무엇을 기준으로 칸을 맞추나" 가
  동작의 성질을 탄다. 구르기는 발 기준(state16_v2), 뒤로넘어짐은 접지앵커 기준(state16_v3)으로
  확정됐다. 표는 `motion_post_profiles.txt` 에 있고 코드에는 없다.
  ⚠️**프로파일이 안 넘어오면 멈춘다.** 조용히 아무 후처리로 떨어지면 검수를 거치지 않은 그림이
    그대로 나가는데, 그건 화면을 봐야만 드러난다.

★ 자르기·키잉·정렬은 state16_v2.py / state16_v3.py 를 그대로 쓴다. 절단·초록 키잉·침범 제거·
  발 중앙값 정렬은 실험에서 여러 사고를 잡아 가며 다듬은 것이라 손대지 않는다.
  이 파일은 그 결과(프레임 16장)를 **서비스가 쓰는 이름과 형식으로 묶기만** 한다.
  부화 쪽 service_post.py 가 state8_v5 를 쓰는 방식과 같은 구조다.

★ 2026-09-12 — state16_post(v1) 에서 state16_v2 로 올렸다. 검수에서 뒤로넘어짐 3판·
  구르기 2판의 오른쪽 아래에 검은 점이 남은 것이 잡혔다.
  v1 은 격자점 씨앗을 극도로 순수한 마젠타/시안만 인정하고
  균등분할 칸의 네 모서리만 봐서, 모델이 마크를 몇~수십 px 어긋나게 그린 판에서는
  격자점이 통째로 남았다. v2 는 그 처방(hue 판정 + 실제 마크 좌표 둘레)을 8종 쪽
  state8_v5 에서 그대로 가져온다. 본체 미연결 격자점 잔여 210px → 0px · 본체 픽셀 감소 0.
  v1(state16_post.py)은 지우지 않고 남겨 둔다 — 옛 확정본이 어떤 코드로 나왔는지를
  설명하는 것이 그 파일이다.

★ 파일 이름이 motion.webp 로 고정인 이유 — 자바(PythonMotionPostProcessor)가 정확히
  이 이름을 찾는다. 한쪽만 바꾸면 굽기는 성공했는데 결과가 없다고 실패한다.

★ GIF 가 아니라 WebP 인 이유 — 화면이 webp 를 쓰고 있고 용량이 훨씬 작다.
  실험은 검수용으로 투명 GIF 를 냈지만, 서비스가 지급하는 것은 애니메이션 webp 다.
  프레임 간격(120ms)은 실험과 같게 둔다 — 간격이 달라지면 확정된 그 움직임이 아니다.

⚠️ 후처리 main() 은 검수용 부산물(애니.gif · 시트.png · cut/)을 작업 폴더에 같이
  남긴다. 서비스에는 필요 없지만, 그 계산을 피하려고 로직을 갈라 쓰면 실험과 서비스가
  다른 코드를 타게 된다. 부산물은 작업 폴더째 지운다.
"""
import argparse
import importlib
import inspect
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from PIL import Image  # noqa: E402

# 프로파일이 고를 수 있는 후처리. **여기 적힌 것만** 부를 수 있다 —
# 임의의 모듈 이름을 그대로 import 하면 표의 오타가 엉뚱한 파일을 실행시킨다.
ALLOWED_SCRIPTS = ("state16_v2", "state16_v3")

# 화면이 찾는 이름. 자바 PythonMotionPostProcessor.OUTPUT 과 짝이다.
OUTPUT_NAME = "motion.webp"

# 16칸이 한 동작으로 이어지는 간격. 실험(state16_v2 의 duration 기본값)과 같은 값.
FRAME_MS = 120

# ★ 정렬 방식은 여기 없다 — 동작마다 다르므로 motion_post_profiles.txt 가 정한다.
#   (2026-09-12 v6b 까지: 구르기 = state16_v2 발 정렬 · 뒤로넘어짐 = state16_v3 접지앵커 정렬)

FRAMES = 16

# ★ 무손실이 아니라 q80 인 이유는 부화 후처리(service_post.py)와 같다 —
#   같은 그림이 무손실 대비 1/5 로 줄고, 펫 그림은 사람마다 달라 CDN 캐시가 거의 안 듣는다.
#   16프레임이라 8종보다 프레임이 많아 용량 차이는 더 벌어진다.
WEBP_QUALITY = 80


def resolve_profile(profile: str):
    """`script=state16_v3, cut=marks, align=seat, seat-lock=global` → (모듈, main 인자)."""
    if not profile or not profile.strip():
        # 설정이 원인일 때는 설정 이름을 그대로 말한다.
        raise ValueError(
            "--profile 이 없습니다 — pipeline/v1/motion_post_profiles.txt 의 그 동작 줄을 넘기세요. "
            "기본값으로 굽지 않습니다(검수를 거치지 않은 후처리로 구워진 그림은 화면을 봐야만 드러납니다)")

    parsed = {}
    for part in profile.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"--profile 형식 오류: '{part}' — `이름=값` 으로 쓸 것")
        k, v = (t.strip() for t in part.split("=", 1))
        parsed[k.replace("-", "_")] = v

    name = parsed.pop("script", None)
    if name not in ALLOWED_SCRIPTS:
        raise ValueError(
            f"--profile 의 script 가 '{name}' 입니다 — 가능한 값: {', '.join(ALLOWED_SCRIPTS)}")
    module = importlib.import_module(name)

    # ★ main() 이 실제로 받는 인자인지 본다. 오타("aling=foot")를 그대로 넘기면 TypeError 가
    #   나기는 하지만, 무엇을 고쳐야 하는지는 말해 주지 않는다.
    accepted = set(inspect.signature(module.main).parameters) - {"grid", "cols", "rows", "duration"}
    unknown = [k for k in parsed if k not in accepted]
    if unknown:
        raise ValueError(
            f"--profile 에 {name}.main() 이 모르는 옵션이 있습니다: {', '.join(unknown)} — "
            f"가능한 옵션: {', '.join(sorted(accepted))}")
    return module, parsed


def build(grid_path: str, out_dir: str, profile: str) -> str:
    # ★ 폴더를 만들기 **전에** 프로파일부터 본다. 반쯤 만들어진 출력 폴더를 남기면
    #   다음 사람이 "돌다 만 것" 과 "아예 안 돈 것" 을 구별하지 못한다.
    module, options = resolve_profile(profile)

    grid = Path(grid_path)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # state16_post 는 격자와 **같은 폴더**에 cut/ 과 검수용 부산물을 만든다.
    # 원본 폴더를 어지럽히지 않도록 작업용 폴더로 옮겨 놓고 돌린다.
    work = out / "_work"
    work.mkdir(exist_ok=True)
    work_grid = work / "grid.png"
    shutil.copy(grid, work_grid)

    module.main(str(work_grid), duration=FRAME_MS, **options)

    cut = work / "cut"
    frames = []
    for i in range(1, FRAMES + 1):
        f = cut / f"f{i:02d}.png"
        if not f.exists():
            # 16칸 중 하나라도 없으면 실패로 본다. 빠진 채로 지급하면 움직임이 튀는데,
            # 그건 화면에서 봐야만 드러난다.
            raise FileNotFoundError(f"프레임이 없습니다: {f.name}")
        frames.append(Image.open(f).convert("RGBA"))

    dst = out / OUTPUT_NAME
    frames[0].save(
        dst, save_all=True, append_images=frames[1:],
        duration=FRAME_MS, loop=0, format="WEBP",
        lossless=False, quality=WEBP_QUALITY, method=6)

    shutil.rmtree(work, ignore_errors=True)   # 중간물·검수용 부산물은 남기지 않는다
    return str(dst)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="16프레임 격자 1장 → 움짤 하나(webp)")
    ap.add_argument("grid")
    ap.add_argument("out")
    ap.add_argument("--profile", help="pipeline/v1/motion_post_profiles.txt 의 그 동작 줄")
    ns = ap.parse_args()
    try:
        print(build(ns.grid, ns.out, ns.profile))
    except ValueError as e:
        print(f"✗ {e}", file=sys.stderr)
        sys.exit(1)
