#!/usr/bin/env python3
"""
서비스용 모션 후처리 — 16프레임 격자 한 장을 움짤 하나(webp)로 만든다.

  python3 service_motion_post.py <격자.png> <출력폴더>
  → 출력폴더/motion.webp

★ 자르기·키잉·정렬은 state16_post.py 를 그대로 쓴다. 절단·초록 키잉·침범 제거·
  발 중앙값 정렬은 실험에서 여러 사고를 잡아 가며 다듬은 것이라 손대지 않는다.
  이 파일은 그 결과(프레임 16장)를 **서비스가 쓰는 이름과 형식으로 묶기만** 한다.
  부화 쪽 service_post.py 가 state8_v3 을 쓰는 방식과 같은 구조다.

★ 파일 이름이 motion.webp 로 고정인 이유 — 자바(PythonMotionPostProcessor)가 정확히
  이 이름을 찾는다. 한쪽만 바꾸면 굽기는 성공했는데 결과가 없다고 실패한다.

★ GIF 가 아니라 WebP 인 이유 — 화면이 webp 를 쓰고 있고 용량이 훨씬 작다.
  실험은 판정용으로 투명 GIF 를 냈지만, 서비스가 지급하는 것은 애니메이션 webp 다.
  프레임 간격(120ms)은 실험과 같게 둔다 — 간격이 달라지면 판정받은 그 움직임이 아니다.

⚠️ state16_post.main() 은 판정용 부산물(애니.gif · 시트.png · cut/)을 작업 폴더에 같이
  남긴다. 서비스에는 필요 없지만, 그 계산을 피하려고 로직을 갈라 쓰면 실험과 서비스가
  다른 코드를 타게 된다. 부산물은 작업 폴더째 지운다.
"""
import sys
import shutil
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import state16_post  # noqa: E402
from PIL import Image  # noqa: E402

# 화면이 찾는 이름. 자바 PythonMotionPostProcessor.OUTPUT 과 짝이다.
OUTPUT_NAME = "motion.webp"

# 16칸이 한 동작으로 이어지는 간격. 실험(state16_post 의 duration 기본값)과 같은 값.
FRAME_MS = 120

FRAMES = 16

# ★ 무손실이 아니라 q80 인 이유는 부화 후처리(service_post.py)와 같다 —
#   같은 그림이 무손실 대비 1/5 로 줄고, 펫 그림은 사람마다 달라 CDN 캐시가 거의 안 듣는다.
#   16프레임이라 8종보다 프레임이 많아 용량 차이는 더 벌어진다.
WEBP_QUALITY = 80


def build(grid_path: str, out_dir: str) -> str:
    grid = Path(grid_path)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # state16_post 는 격자와 **같은 폴더**에 cut/ 과 판정용 부산물을 만든다.
    # 원본 폴더를 어지럽히지 않도록 작업용 폴더로 옮겨 놓고 돌린다.
    work = out / "_work"
    work.mkdir(exist_ok=True)
    work_grid = work / "grid.png"
    shutil.copy(grid, work_grid)

    state16_post.main(str(work_grid), duration=FRAME_MS)

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

    shutil.rmtree(work, ignore_errors=True)   # 중간물·판정용 부산물은 남기지 않는다
    return str(dst)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("사용법: service_motion_post.py <격자.png> <출력폴더>", file=sys.stderr)
        sys.exit(2)
    print(build(sys.argv[1], sys.argv[2]))
