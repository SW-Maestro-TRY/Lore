#!/usr/bin/env python3
"""
서비스용 후처리 v4 — 격자 한 장을 1층 기본 행동 8종(webp)으로 만든다.

  python3 service_post.py <격자.png> <출력폴더> [--keys a,b,..] [--postures sick=crouch,..]
  → 출력폴더/<key>.webp x 8

  --keys      자바 카탈로그(또는 app.zzal.hatch.states.v4)의 이름을 격자 칸 순서로 넘길 때.
              생략하면 state8_v5.KEYS(= base,eat,joy,sad,sick,pet,hello,sleep)를 쓴다.
  --postures  칸 이름 → 자세 유형 매핑. 생략하면 state8_v5.DEFAULT_POSTURE(1층 v4 매핑).

종료코드
  0  정상
  3  ★격자 구조 이상 — 자르기도 하지 않고 여기서 멈춘다(아래 '게이트' 참고)
  1  그 밖의 실패

v2 대비 바뀐 것 셋
------------------
1. **자르기·정렬이 state8_v3 이 아니라 `state8_v5`** 다. 2026-09-12 1층 v4 5캐릭터 판정에서
   상훈님이 *"다 완벽이야 v02"* 로 확정하신 바로 그 조합이고, 이 폴더의 state8_v3/v4/v5 는
   실험본(`~/work/jakae-lab/01_움짤/도구/`)과 **바이트 단위로 같다.** 손대지 않는다 —
   손대는 순간 판정 라벨이 가리키는 대상이 사라진다.

2. **자르기 전에 격자 구조 게이트를 돌린다**(`check_grid.py`).
   여태 서비스는 4x4가 아닌 격자도 무조건 균등 분할해서, 얼굴 조각·빈 칸이 담긴 webp 8장을
   정상으로 보고 그대로 사용자에게 줬다(1·2층은 사람 검수가 없다).
   · 사양은 **이 폴더의 `grid_spec.txt`** 에서 읽는다. 그 파일은 `prompt/v4/grid.txt` 의
     첫 줄(`# GRID_SPEC: ...`)과 **글자 단위로 같아야** 하고, 어긋나면 자바 테스트가 빌드를 깬다
     (GridSpecContractTest). 사양 숫자를 이 스크립트에 박지 않는 이유가 그것이다 —
     프롬프트에서 마크를 바꿨을 때 게이트가 옛 형식만 알고 멀쩡한 격자를 버린 사고가 있었다.
   · **모르면 통과시킨다.** 사양 파일이 없거나 처음 보는 layout 이면(check_grid 종료코드 4)
     경고만 남기고 자르기로 넘어간다. 읽기 실패(1)도 같다 — 그림이 진짜 깨졌으면
     뒤의 state8_v5 가 더 정확한 말로 실패한다. 게이트가 성공을 실패로 만들지는 않는다.
   · 구조 이상(3)일 때만 멈추고, 자바가 알아볼 수 있게 표식 한 줄을 찍는다.

3. **바닥선 아래 티끌 제거**(`drop_floor_specks`).
   판정본 v02 80프레임을 전수 측정하니 본체에서 떨어져 나온 8px 이상 조각이 넷 있었고,
   그중 **본체 최하단선보다 아래**에 뜬 것은 여울 `sick` 두 장뿐이었다(각 10px · 본체와 54px 떨어짐).
   상훈님이 판정에서 *"여울 sick 아래 10px 티끌"* 로 남기신 그것이다.
   · 기준을 '크기'가 아니라 **'바닥선 아래'** 로 잡은 이유 — 같은 10px 조각이 여울 `hello`
     머리 위에도 있는데 그건 머리카락 끝일 수 있다. 발이 서는 선이 바닥이므로 그 **아래**에
     떠 있는 것은 캐릭터의 일부일 수 없다(이웃 칸 조각·격자선 부스러기다).
     크기만으로 지우면 캐릭터 고유색을 오삭제한 2026-08-25/26 사고를 되풀이한다.
   · ★이 처방은 **여기(service_post)에만** 있다. `state8_v5.py` 는 판정본을 만든 코드
     그대로 두어야 재현이 된다(메모리 judged-artifacts-are-frozen).
   · 지운 조각은 한 줄씩 로그에 남긴다 — 조용히 지우면 '내 머리카락'을 지워도 안 보인다.

★ 키 이름이 곧 파일 이름이고, 화면과 카탈로그가 이 이름으로 찾는다. 한쪽만 바꾸면
  **엉뚱한 그림이 엉뚱한 상태로** 들어가는데 그건 화면을 봐야만 드러난다.
  그래서 자바가 이름을 넘길 길(--keys)을 열어 두고, 기본값은 state8_v5 한 곳에서만 온다.

★ webp 저장 규격(2프레임 · 450ms · q80)은 state8_v5 의 상수를 그대로 쓴다. 값을 다시 적으면
  판정본과 인코딩이 달라져 '같은 그림인데 파일이 다른' 상태가 된다.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))
import state8_v5  # noqa: E402

GATE = _HERE / "check_grid.py"
GATE_SPEC = _HERE / "grid_spec.txt"

# 자바가 알아보는 표식. 이 줄이 실패 메시지에 실리면 '격자를 다시 구워라' 라는 뜻이다.
GRID_STRUCTURE_MARK = "GRID_STRUCTURE_INVALID"

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_GRID_STRUCTURE = 3

# 알파가 이보다 크면 '보이는 픽셀'. state8_v5 의 마지막 잘라내기와 같은 기준.
ALPHA_ON = 8
# 바닥선 아래 조각을 티끌로 볼 상한 — 본체 픽셀 수 대비 비율.
#   실측(판정본 v02): 여울 sick 조각 10px / 본체 18,948px = 0.05%.
#   0.5% 면 그보다 열 배 여유가 있으면서, 팔·다리 같은 진짜 부위(수천 px)는 절대 안 걸린다.
#   이보다 큰 것이 바닥 아래 떠 있으면 그건 티끌이 아니라 결함이므로 지우지 않고 남겨 둔다
#   — 지워 버리면 결함이 있었다는 사실까지 사라진다.
SPECK_MAX_RATIO = 0.005


def run_gate(grid: Path) -> int:
    """격자 구조를 본다. 돌려주는 것은 이 스크립트가 끝낼 종료코드(0 = 계속 진행)."""
    if not GATE.exists() or not GATE_SPEC.exists():
        print(f"[게이트] 건너뜀 — 도구·사양 파일이 없습니다({GATE.name}·{GATE_SPEC.name})", file=sys.stderr)
        return EXIT_OK
    p = subprocess.run([sys.executable, str(GATE), str(grid), str(GATE_SPEC)],
                       capture_output=True, text=True)
    said = (p.stdout + p.stderr).strip()
    if p.returncode == 3:
        print(f"[게이트] {GRID_STRUCTURE_MARK} — {said}", file=sys.stderr)
        return EXIT_GRID_STRUCTURE
    if p.returncode == 0:
        print(f"[게이트] {said}")
    else:
        # 4 = 사양 모름 / 1 = 읽기 실패. 둘 다 통과시킨다 — 모르는 것으로 버리지 않는다.
        print(f"[게이트] 통과(코드 {p.returncode}) — {said}", file=sys.stderr)
    return EXIT_OK


def drop_floor_specks(frame: Image.Image, label: str) -> Image.Image:
    """본체 최하단선보다 **아래**에 완전히 떨어져 뜬 작은 조각을 지운다.

    본체(가장 큰 덩어리)와 세로로 겹치는 것은 건드리지 않는다 — 손에 든 것·늘어진 머리카락이
    거기 있다. 지운 것은 크기와 자리를 로그로 남긴다.
    """
    a = np.array(frame.convert("RGBA"))
    m = a[:, :, 3] > ALPHA_ON
    lab, n = ndimage.label(m)
    if n <= 1:
        return frame
    sizes = ndimage.sum(m, lab, range(1, n + 1))
    objs = ndimage.find_objects(lab)
    body = int(np.argmax(sizes))
    body_bottom = objs[body][0].stop
    limit = max(1.0, float(sizes[body]) * SPECK_MAX_RATIO)

    removed = []
    for i in range(n):
        if i == body:
            continue
        sl = objs[i]
        size = int(sizes[i])
        if sl[0].start < body_bottom:          # 본체와 세로로 겹친다 = 바닥 아래가 아니다
            continue
        if size > limit:                       # 티끌이라기엔 크다 — 남겨서 보이게 둔다
            print(f"[티끌] {label}: 바닥 아래 {size}px 덩어리가 상한({limit:.0f}px)을 넘어 남겨 둡니다 "
                  f"— y{sl[0].start}~{sl[0].stop} x{sl[1].start}~{sl[1].stop}", file=sys.stderr)
            continue
        a[:, :, 3][lab == i + 1] = 0
        removed.append(f"{size}px@y{sl[0].start}~{sl[0].stop},x{sl[1].start}~{sl[1].stop}")

    if not removed:
        return frame
    print(f"[티끌] {label}: 바닥선({body_bottom}) 아래 조각 {len(removed)}개 제거 — " + " · ".join(removed))
    return Image.fromarray(a, "RGBA")


def build(grid_path: str, out_dir: str, keys, postures) -> list:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # state8_v5 는 격자 옆에 결과 폴더를 만든다. 원본 폴더를 어지럽히지 않게 작업 폴더로 옮겨 돌린다.
    work = out / "_work"
    work.mkdir(exist_ok=True)
    work_grid = work / "grid.png"
    shutil.copy(grid_path, work_grid)

    state8_v5.main(str(work_grid), str(work), postures=postures)

    frames = work / "frames"
    cut = work / "cut"
    made = []
    for i, name in enumerate(keys):
        a_path = frames / f"f{i * 2 + 1:02d}.png"
        b_path = frames / f"f{i * 2 + 2:02d}.png"
        if not (a_path.exists() and b_path.exists()):
            raise FileNotFoundError(f"프레임이 없습니다: {a_path.name} · {b_path.name}")

        a0 = Image.open(a_path).convert("RGBA")
        b0 = Image.open(b_path).convert("RGBA")
        a1 = drop_floor_specks(a0, f"{name} f{i * 2 + 1:02d}")
        b1 = drop_floor_specks(b0, f"{name} f{i * 2 + 2:02d}")

        dst = out / f"{name}.webp"
        src = cut / f"{state8_v5.KEYS[i]}.webp" if i < len(state8_v5.KEYS) else None
        if a1 is a0 and b1 is b0 and src is not None and src.exists():
            # 지운 것이 없으면 state8_v5 가 만든 파일을 그대로 쓴다 — 다시 인코딩하지 않는다.
            # 판정본과 **바이트까지** 같아야 "판정받은 그 그림" 이라고 말할 수 있다.
            shutil.copyfile(src, dst)
        else:
            a1.save(dst, save_all=True, append_images=[b1],
                    duration=state8_v5.FRAME_MS, loop=0, quality=state8_v5.WEBP_Q)
        made.append(str(dst))

    shutil.rmtree(work, ignore_errors=True)   # 중간물은 남기지 않는다
    return made


def main(argv) -> int:
    ap = argparse.ArgumentParser(description="격자 1장 → 1층 기본 행동 8종 webp")
    ap.add_argument("grid")
    ap.add_argument("out")
    ap.add_argument("--keys", help="쉼표로 구분한 이름 8개(격자 칸 순서). 생략 시 state8_v5.KEYS")
    ap.add_argument("--postures", help="예: sick=crouch,sleep=lying. 생략 시 1층 v4 기본 매핑")
    a = ap.parse_args(argv)

    grid = Path(a.grid)
    gate = run_gate(grid)
    if gate != EXIT_OK:
        return gate

    keys = [k.strip() for k in a.keys.split(",")] if a.keys else list(state8_v5.KEYS)
    if len(keys) != len(state8_v5.KEYS):
        # 설정이 원인일 때는 설정 이름을 그대로 말한다.
        print(f"✗ --keys 는 정확히 {len(state8_v5.KEYS)}개여야 합니다(격자 4x4 = 8쌍): {keys}", file=sys.stderr)
        return EXIT_FAIL
    try:
        pmap = state8_v5.parse_postures(a.postures)
    except ValueError as e:
        print(f"✗ {e}", file=sys.stderr)
        return EXIT_FAIL

    for path in build(a.grid, a.out, keys, pmap):
        print(path)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
