#!/usr/bin/env python3
"""
서비스용 후처리 v4 — 격자 한 장을 기본 행동 8종(webp)으로 만든다(1층·2층 같은 스크립트).

  python3 service_post.py <격자.png> <출력폴더> [--keys a,b,..] [--postures sick=crouch,..]
                          [--base-anchors <앵커.json> | --base-k N --base-hw N] [--no-anchors]
  → 출력폴더/<key>.webp x 8  +  출력폴더/anchors.json (1층·2층이 **한 장에 합쳐진다**)

  --keys      자바 카탈로그(또는 app.zzal.hatch.states.v4)의 이름을 격자 칸 순서로 넘길 때.
              생략하면 state8_v5.KEYS(= base,eat,joy,sad,sick,pet,hello,sleep)를 쓴다.
  --postures  칸 이름 → 자세 유형 매핑. ★이름은 **`--keys` 로 넘긴 그 이름**이다.
              2층이면 `wash=crouch,...` 처럼 2층 key 로 쓴다(1층 이름으로 옮겨 적지 않는다).
              주면 **여덟 칸을 모두** 적어야 한다. 생략하면 state8_v5.DEFAULT_POSTURE(1층 v4 매핑).

종료코드
  0  정상
  3  ★격자 구조 이상 — 자르기도 하지 않고 여기서 멈춘다(아래 '게이트' 참고)
  1  그 밖의 실패

v2 대비 바뀐 것 넷
------------------
1. **자르기·정렬이 state8_v3 이 아니라 `state8_v5`** 다. 2026-09-12 1층 v4 5캐릭터 검수에서
   확정한 바로 그 조합이고, 이 폴더의 state8_v3/v4/v5 는 실험본과 **바이트 단위로 같다.**
   손대지 않는다 — 손대는 순간 확정 라벨이 가리키는 대상이 사라진다.

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
   확정본 v02 80프레임을 전수 측정하니 본체에서 떨어져 나온 8px 이상 조각이 넷 있었고,
   그중 **본체 최하단선보다 아래**에 뜬 것은 여울 `sick` 두 장뿐이었다(각 10px · 본체와 54px 떨어짐).
   검수에서 '여울 sick 아래 10px 티끌'로 남은 그것이다.
   · 기준을 '크기'가 아니라 **'바닥선 아래'** 로 잡은 이유 — 같은 10px 조각이 여울 `hello`
     머리 위에도 있는데 그건 머리카락 끝일 수 있다. 발이 서는 선이 바닥이므로 그 **아래**에
     떠 있는 것은 캐릭터의 일부일 수 없다(이웃 칸 조각·격자선 부스러기다).
     크기만으로 지우면 캐릭터 고유색을 오삭제한 2026-08-25/26 사고를 되풀이한다.
   · ★이 처방은 **여기(service_post)에만** 있다. `state8_v5.py` 는 확정본을 만든 코드
     그대로 두어야 재현이 된다.
   · 지운 조각은 한 줄씩 로그에 남긴다 — 조용히 지우면 '내 머리카락'을 지워도 안 보인다.

4. **`anchors.json` 을 webp 옆에 같이 낸다**(`anchors.py`).
   화면의 머리 앵커는 캐릭터 상자 위에서 12% 고정이었다. 서 있을 때는 맞지만 `sick`(웅크림)은
   정수리가 109px, `sleep`(눕기)은 205px 어긋나 **해골이 머리 위 허공에 떴다.**
   상자 비율이 아니라 그 자세의 실루엣을 **실제로 재서** 내려 주는 값이 이 파일이다.
   · 형식 정본 = `contract/소품-앵커-자세별-v1.json`. 프론트·백엔드가 이미 합의한 그 모양이다.
   · **그림은 한 픽셀도 안 건드린다.** webp 는 이 변경 전후로 바이트까지 같다 —
     앵커는 다 만들어진 f1 프레임을 **읽기만** 한다.
   · 모델(RTMPose)은 안 쓴다. 부위 트래킹 실측에서 `sleep` 이 오차 27.6px 로 무너졌다.
   · ★**1층과 2층이 같은 자리로 간다**(S3 `images/zzal/pets/<id>/basic/` — PostProcessStep 규약).
     그냥 쓰면 나중에 도는 2층이 1층 여덟 자세를 **지운다.** 그래서 출력 폴더에 이미 있으면
     **합쳐 쓴다**(`merge_into`) — 한 캐릭터의 앵커 한 장에 자세 16칸이 모이는 것이
     정본의 모양이기도 하다.
     ⚠️자바는 **호출마다 빈 임시 폴더**를 쓴다(`PythonPostProcessor`). 2층에서 합쳐지게 하려면
       자바가 기존 `basic/anchors.json` 을 그 폴더에 **내려받아 두거나** `--base-anchors` 로
       경로를 넘겨야 한다. 안 해 주면 2층은 K·Hw 를 못 찾아 **멈춘다**(조용히 안 틀린다).
   · ★`K`(머리끝~발끝)·`Hw`(머리 폭)는 **base 한 자세에서만** 온다. 소품 규격이 전부 이 둘을
     단위로 쓰는데 **2층 격자에는 base 칸이 없다.** 그래서 이 순서로 찾는다 —
     `--base-anchors` → `--base-k`/`--base-hw` → `--keys` 안의 `base` 칸 → **출력 폴더에
     이미 있는 anchors.json**. 넷 다 없으면 **멈춘다.**
     '없으면 첫 칸으로 대신 재자' 는 안 넣었다 — 통과는 하고 캐릭터마다 소품이 몇 % 씩
     어긋나는데 그건 화면을 나란히 놓고 봐야만 드러난다.
   · 한 자세를 못 재면(본체가 없음) 그 칸만 `null` 로 두고 이름을 `"missing"` 에 적는다.
     파일 전체는 나간다 — 나머지 일곱 자세가 살고, 프론트가 그 한 칸만 옛 폴백으로 받는다.
     ★조용히 빼면 프론트는 '없는 자세'와 '못 잰 자세'를 구별할 길이 없다.
   · `--no-anchors` 로 끌 수 있다. 그림만 다시 굽는 자리(재처리)를 위한 문이고, 기본은 켜짐이다.

★ 키 이름이 곧 파일 이름이고, 화면과 카탈로그가 이 이름으로 찾는다. 한쪽만 바꾸면
  **엉뚱한 그림이 엉뚱한 상태로** 들어가는데 그건 화면을 봐야만 드러난다.
  그래서 자바가 이름을 넘길 길(--keys)을 열어 두고, 기본값은 state8_v5 한 곳에서만 온다.

★ 층마다 자세가 다른 칸에 온다 — 그래서 `--postures` 가 인자다(코드 아님).
  1층 v4 : 5번 `sick` 웅크림 · 8번 `sleep` 눕기 · 나머지 서 있음 (= state8_v5.DEFAULT_POSTURE)
  2층 v3 : 4번 `wash`(목욕) 웅크림 · 나머지 일곱 칸 서 있음
  ⚠️2층에 1층 기본값이 그대로 실리면 5번(`reply`)이 웅크림, 8번(`wake_up`)이 눕기로 후처리된다.
    통과는 하는데 결과가 조용히 틀어지므로, `--postures` 를 주면 **여덟 칸 전부** 요구한다.
    빠뜨리거나 `--keys` 에 없는 이름을 쓰면 **설정 이름을 말하는 예외**로 멈춘다.

★ webp 저장 규격(2프레임 · 450ms · q80)은 state8_v5 의 상수를 그대로 쓴다. 값을 다시 적으면
  확정본과 인코딩이 달라져 '같은 그림인데 파일이 다른' 상태가 된다.
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
import anchors      # noqa: E402
import state8_v5    # noqa: E402

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
#   실측(확정본 v02): 여울 sick 조각 10px / 본체 18,948px = 0.05%.
#   0.5% 면 그보다 열 배 여유가 있으면서, 팔·다리 같은 진짜 부위(수천 px)는 절대 안 걸린다.
#   이보다 큰 것이 바닥 아래 떠 있으면 그건 티끌이 아니라 결함이므로 지우지 않고 남겨 둔다
#   — 지워 버리면 결함이 있었다는 사실까지 사라진다.
SPECK_MAX_RATIO = 0.005

# 앵커 파일 이름. 프론트·백엔드가 이 이름으로 찾는다 — 한쪽만 바꾸면 조용히 못 읽는다.
ANCHORS_NAME = "anchors.json"
# K·Hw 를 재는 칸 이름(1층). 2층 --keys 에는 이 이름이 없으므로 다른 데서 받아야 한다.
BASE_KEY = "base"


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


def resolve_postures(keys, spec):
    """`--postures` 를 state8_v5 가 아는 표로 푼다.

    ★키가 **두 벌**이다 — 자바(화면·카탈로그)는 층마다 다른 이름(`wash`·`reply`…)을 쓰고,
      state8_v5 는 1층 이름(`base`…`sleep`)을 칸 번호처럼 쓴다. 여기서 **자리로** 옮긴다.
      `--keys` 의 n 번째 이름 = state8_v5.KEYS 의 n 번째.
    ⚠️주면 여덟 칸을 다 요구한다. 일부만 받고 나머지를 1층 기본값으로 채우면 2층에서
      `reply` 가 웅크림, `wake_up` 이 눕기로 후처리되는데 그건 화면을 봐야만 드러난다.
      ⚠️`state8_v5.parse_devnull` 류에 빈 표를 넘겨 대신 시킬 수 없다 —
        `parse_postures(spec, base={})` 의 `base or DEFAULT_POSTURE` 가 빈 dict 를 거짓으로 읽어
        **조용히 1층 기본값으로 되돌린다**(실패 주입에서 실제로 통과해 버렸다). 그래서 여기서 센다.
    """
    if not spec:
        return state8_v5.parse_postures(None)
    at = {k: i for i, k in enumerate(keys)}
    moved = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"--postures 형식 오류: '{part}' — `키=자세` 로 쓸 것")
        k, v = (t.strip() for t in part.split("=", 1))
        if k not in at:
            # 설정이 원인일 때는 설정 이름을 그대로 말한다.
            raise ValueError(
                f"--postures 의 '{k}' 가 --keys 에 없습니다 — --keys = {', '.join(keys)}")
        moved.append(f"{state8_v5.KEYS[at[k]]}={v}")
    given = {m.split("=", 1)[0] for m in moved}
    missing = [keys[i] for i, k in enumerate(state8_v5.KEYS) if k not in given]
    if missing:
        raise ValueError(
            f"--postures 에 빠진 칸: {', '.join(missing)} — 여덟 칸을 모두 적을 것 "
            f"(--keys = {', '.join(keys)})")
    try:
        return state8_v5.parse_postures(",".join(moved), base={})
    except ValueError as e:
        # state8_v5 는 1층 이름으로 말한다. 자바가 넘긴 이름으로 되돌려 말해 준다.
        back = {a: b for a, b in zip(state8_v5.KEYS, keys)}
        msg = str(e)
        for a, b in back.items():
            msg = msg.replace(a, b)
        raise ValueError(msg) from e


def resolve_base_scale(a):
    """`--base-*` 인자만 푼다. 아무것도 없으면 (None, None) — 그림에서 찾는 몫은 build 가 한다."""
    if a.base_anchors:
        if a.base_k is not None or a.base_hw is not None:
            raise ValueError("--base-anchors 와 --base-k/--base-hw 를 같이 줄 수 없습니다 — 하나만")
        try:
            return anchors.read_base_scale(a.base_anchors)
        except (OSError, ValueError) as e:
            raise ValueError(f"--base-anchors 를 읽지 못했습니다({a.base_anchors}): {e}") from e
    if (a.base_k is None) != (a.base_hw is None):
        raise ValueError("--base-k 와 --base-hw 는 짝으로 주어야 합니다")
    return a.base_k, a.base_hw


def find_base_scale(out: Path, keys, base_k, base_hw):
    """앵커의 단위 K·Hw 를 어디서 받을지 정한다. **어디서 왔는지 한 줄 남긴다.**

    순서는 **명시 > 이 격자 > 옆에 있던 파일**이다.
      1. `--base-anchors` · `--base-k`/`--base-hw` — 부르는 쪽이 말한 값.
      2. `--keys` 에 `base` 가 있으면(=1층) **그 칸에서 잰다.** 원본이므로 가장 정확하다.
      3. 출력 폴더에 이미 있는 `anchors.json`(=2층. 자바가 1층 결과를 내려받아 둔 것).
      4. 넷 다 없으면 **멈춘다.**

    ⚠️4에서 '그럼 첫 칸으로 대신 재자' 는 안 된다. 2층 `eat_rice` 의 실루엣 높이는 base 와
      같지 않고, 소품 크기가 전부 K 배수라 캐릭터마다 몇 % 씩 어긋난다. 통과는 하고 결과만
      조용히 틀어지는 길이다(→ 메모리 `silent-config-mismatch`).
    """
    if base_k is not None:
        print(f"[앵커] K·Hw 는 인자에서 — K={base_k:.0f} Hw={base_hw:.0f}")
        return base_k, base_hw
    if BASE_KEY in keys:
        return None, None                       # build 가 base 칸에서 잰다
    prior = out / ANCHORS_NAME
    if prior.exists():
        k, hw = anchors.read_base_scale(prior)
        print(f"[앵커] K·Hw 는 출력 폴더의 {ANCHORS_NAME} 에서 — K={k:.0f} Hw={hw:.0f}")
        return k, hw
    # 설정이 원인일 때는 설정 이름을 그대로 말한다.
    raise anchors.AnchorError(
        f"K·Hw 를 어디서도 못 얻었습니다 — --keys 에 '{BASE_KEY}' 가 없고(--keys = "
        f"{', '.join(keys)}) {out}/{ANCHORS_NAME} 도 없습니다. 2층이면 1층을 먼저 돌리거나 "
        f"--base-anchors <1층 결과>/{ANCHORS_NAME} · --base-k/--base-hw 를 주십시오. "
        f"그림만 필요하면 --no-anchors.")


def build(grid_path: str, out_dir: str, keys, postures,
          want_anchors: bool = True, base_k=None, base_hw=None) -> list:
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
    # 앵커는 **서비스에 나가는 바로 그 첫 장**에서 잰다(티끌을 지운 뒤의 a1).
    # 원본 f01.png 에서 재면 지운 티끌이 발 아래 10px 로 남아 발끝이 그만큼 내려간다.
    first = {}
    if want_anchors:
        base_k, base_hw = find_base_scale(out, keys, base_k, base_hw)
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
            # 확정본과 **바이트까지** 같아야 "확정된 그 그림" 이라고 말할 수 있다.
            shutil.copyfile(src, dst)
        else:
            a1.save(dst, save_all=True, append_images=[b1],
                    duration=state8_v5.FRAME_MS, loop=0, quality=state8_v5.WEBP_Q)
        made.append(str(dst))
        first[name] = a1

    if want_anchors:
        data = anchors.build(first, keys, K=base_k, Hw=base_hw, base_key=BASE_KEY)
        data = anchors.merge_into(out / ANCHORS_NAME, data)   # 1층·2층이 같은 자리로 간다
        path = anchors.write(data, out / ANCHORS_NAME)
        got = sum(1 for v in data["poses"].values() if v)
        print(f"[앵커] {path} — 자세 {got}/{len(data['poses'])} · K={data['K']} Hw={data['Hw']}"
              + (f" · 못 잼: {', '.join(data['missing'])}" if data.get("missing") else ""))
        made.append(str(path))

    shutil.rmtree(work, ignore_errors=True)   # 중간물은 남기지 않는다
    return made


def main(argv) -> int:
    ap = argparse.ArgumentParser(description="격자 1장 → 기본 행동 8종 webp(1층·2층)")
    ap.add_argument("grid")
    ap.add_argument("out")
    ap.add_argument("--keys", help="쉼표로 구분한 이름 8개(격자 칸 순서). 생략 시 state8_v5.KEYS")
    ap.add_argument("--postures",
                    help="예(1층): base=standing,...,sick=crouch,...,sleep=lying / "
                         "예(2층): ...,wash=crouch,... — 이름은 --keys 의 것, 여덟 칸 전부")
    ap.add_argument("--base-anchors",
                    help="K·Hw 를 물려받을 anchors.json. 생략하면 base 칸 → 출력 폴더의 파일 순")
    ap.add_argument("--base-k", type=float, help="base 머리끝~발끝(px). --base-anchors 대신 직접 줄 때")
    ap.add_argument("--base-hw", type=float, help="base 머리 폭(px). --base-k 와 짝으로")
    ap.add_argument("--no-anchors", action="store_true",
                    help="anchors.json 을 내지 않는다(그림만 다시 굽는 재처리용)")
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
        pmap = resolve_postures(keys, a.postures)
        base_k, base_hw = resolve_base_scale(a)
    except ValueError as e:
        print(f"✗ {e}", file=sys.stderr)
        return EXIT_FAIL

    try:
        paths = build(a.grid, a.out, keys, pmap,
                      want_anchors=not a.no_anchors, base_k=base_k, base_hw=base_hw)
    except anchors.AnchorError as e:
        # 앵커가 원인일 때는 그림 탓으로 보이지 않게 말머리를 붙인다.
        print(f"✗ 앵커: {e}", file=sys.stderr)
        return EXIT_FAIL
    for path in paths:
        print(path)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
