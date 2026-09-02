#!/usr/bin/env python3
"""
서비스용 후처리 — 격자 한 장을 8종 움짤(webp)로 만든다.

  python3 service_post.py <격자.png> <출력폴더>
  → 출력폴더/idle.webp · eat.webp · hungry.webp · clean.webp
                happy.webp · sad.webp · pet.webp · train.webp

★ 자르기·정렬 자체는 state8_v3.py 를 그대로 쓴다. 그 로직은 실험에서 여러 사고를
  잡아 가며 다듬은 것이라(마젠타 격자점·땀 오인식·머리 틈 초록 잔여·발 잘림) 손대지 않는다.
  이 파일은 그 결과(프레임 16장)를 **서비스가 쓰는 이름과 형식으로 묶기만** 한다.

★ GIF 가 아니라 WebP 인 이유 — 화면이 webp 를 쓰고 있고 용량이 훨씬 작다.
  실측으로 같은 그림이 GIF 130KB · WebP 27KB 였다(여울 8종).

★ 이름 순서는 state8_v3.NAMES 와 짝이다. 한쪽만 바꾸면 **엉뚱한 그림이 엉뚱한 상태로**
  들어가는데, 그건 화면에서 봐야만 드러난다.
"""
import sys
import shutil
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import state8_v3  # noqa: E402
from PIL import Image  # noqa: E402

# state8_v3.NAMES = ["기본","식사","배고픔","청소","행복","불행","쓰다듬","훈련","잠"]
# 화면이 쓰는 이름(zzal/fe/tamagotchi/constants.ts 의 YEOUL_MOOD)과 순서를 맞춘다.
STATE_NAMES = ["idle", "eat", "hungry", "clean", "happy", "sad", "pet", "train"]

FRAME_MS = 450   # 2프레임이 번갈아 도는 간격. 실험에서 쓰던 값 그대로.

# ★ 무손실이 아니라 q80 인 이유 (2026-09-02 결정)
#   무손실 127KB · q90 32KB · q80 23KB — 기존 여울 파일(26KB)과 같은 급이 q80 이다.
#   비용 차이는 지금 규모에서 크지 않지만(1,000명당 약 $0.10) **로딩 속도**가 다르다.
#   화면 하나를 채우는 데 무손실은 1MB, q80 은 184KB 를 받아야 한다.
#   게다가 펫 그림은 사람마다 달라 CDN 캐시가 거의 안 듣는다 — 각 파일이 한 명에게만 간다.
WEBP_QUALITY = 80


def build(grid_path: str, out_dir: str) -> list[str]:
    grid = Path(grid_path)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # state8_v3 은 격자와 **같은 폴더**에 cut/ 을 만든다. 원본 폴더를 어지럽히지 않도록
    # 작업용 폴더로 옮겨 놓고 돌린다.
    work = out / "_work"
    work.mkdir(exist_ok=True)
    work_grid = work / "grid.png"
    shutil.copy(grid, work_grid)

    state8_v3.main(str(work_grid))

    cut = work / "cut"
    made = []
    for i, name in enumerate(STATE_NAMES):
        a = cut / f"f{i * 2 + 1:02d}.png"
        b = cut / f"f{i * 2 + 2:02d}.png"
        if not (a.exists() and b.exists()):
            raise FileNotFoundError(f"프레임이 없습니다: {a.name} · {b.name}")

        frames = [Image.open(a).convert("RGBA"), Image.open(b).convert("RGBA")]
        dst = out / f"{name}.webp"
        frames[0].save(
            dst, save_all=True, append_images=frames[1:],
            duration=FRAME_MS, loop=0, format="WEBP",
            lossless=False, quality=WEBP_QUALITY, method=6)
        made.append(str(dst))

    shutil.rmtree(work, ignore_errors=True)   # 중간물은 남기지 않는다
    return made


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("사용법: service_post.py <격자.png> <출력폴더>", file=sys.stderr)
        sys.exit(2)
    for path in build(sys.argv[1], sys.argv[2]):
        print(path)
