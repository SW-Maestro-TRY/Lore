#!/usr/bin/env python3
"""
서비스용 후처리 v2 — 격자 한 장을 기본 행동 8종(webp)으로 만든다. 부화는 격자 2장 = 16종.

  python3 service_post.py <격자.png> <출력폴더> [--layer 1|2] [--keys a,b,c,d,e,f,g,h]
  → 출력폴더/<key>.webp × 8

  --layer 1 (기본)  1층 = base · eat · joy · sad · sick · practice · shy · call
  --layer 2         2층 = tilt · wave · sleep · wash · startle · nod · smile_idle · sit
  --keys            자바 쪽 카탈로그 키를 그대로 넘겨 이름을 덮어쓸 때(순서 = 격자 칸 순서).
                    layer 와 keys 가 둘 다 오면 keys 가 이긴다.

★ v1 과 다른 점은 **이름과 장수**뿐이다. 자르기·정렬은 v1 과 같은 state8_v3.py 를 그대로 쓴다
  (마젠타 격자점·땀 오인식·머리 틈 초록 잔여·발 잘림을 잡아 가며 다듬은 로직이라 손대지 않는다).
  프롬프트 v2(prompt/v2/grid.txt · grid2.txt)의 칸 순서 = 정본 13장 번호 순서 = 아래 키 순서.

★ 키 이름이 곧 파일 이름이고, 화면(zzal/fe)과 카탈로그(MotionCatalog)가 이 이름으로 찾는다.
  한쪽만 바꾸면 **엉뚱한 그림이 엉뚱한 상태로** 들어가는데, 그건 화면에서 봐야만 드러난다.
  그래서 자바가 --keys 로 자기 카탈로그를 넘겨 주는 길을 열어 뒀다 — 두 벌 관리를 피한다.

★ GIF 가 아니라 WebP 인 이유·q80 인 이유는 v1 과 같다(같은 그림이 GIF 130KB · WebP 27KB,
  무손실 127KB · q90 32KB · q80 23KB — 펫 그림은 사람마다 달라 CDN 캐시가 거의 안 듣는다).
"""
import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import state8_v3  # noqa: E402
from PIL import Image  # noqa: E402

# 정본 13장 번호 순서. 1층 = 1~8, 2층 = 9~16.
LAYER_KEYS = {
    1: ["base", "eat", "joy", "sad", "sick", "practice", "shy", "call"],
    2: ["tilt", "wave", "sleep", "wash", "startle", "nod", "smile_idle", "sit"],
}

FRAME_MS = 450    # 2프레임이 번갈아 도는 간격. v1·실험과 같은 값.
WEBP_QUALITY = 80


def build(grid_path: str, out_dir: str, keys: list[str]) -> list[str]:
    if len(keys) != 8:
        raise ValueError(f"키는 정확히 8개여야 합니다(격자 4x4 = 8쌍): {keys}")
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
    for i, name in enumerate(keys):
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


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="격자 1장 → 기본 행동 8종 webp")
    ap.add_argument("grid")
    ap.add_argument("out")
    ap.add_argument("--layer", type=int, choices=(1, 2), default=1)
    ap.add_argument("--keys", help="쉼표로 구분한 키 8개(자바 카탈로그 순서). layer 보다 우선")
    a = ap.parse_args(argv)
    keys = a.keys.split(",") if a.keys else LAYER_KEYS[a.layer]
    keys = [k.strip() for k in keys]
    for path in build(a.grid, a.out, keys):
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
