"""Scene 이미지들을 세로로 이어 붙여 **웹툰 1화 한 장**으로 만든다.

이 하네스는 지금까지 Scene 을 한 장씩 따로 굽고 폴더에 흩어 두었다. 보려면
HTML 뷰어를 열어야 했고, 넘겨줄 것도 PNG 여러 장이었다. 그런데 만들려는 것은
"장면 모음"이 아니라 **한 편의 웹툰**이다. 세로로 쭉 이어져서 스크롤로 읽히는
그것 하나가 결과물이어야 한다.

그래서 생성이 끝나면 채택본을 순서대로 이어 붙여 episode.png 한 장을 남긴다.
후보가 1장이면 채택 기록이 없어도 c1 이 곧 채택본이다.

## 왜 그냥 붙이기만 하는가

이음매를 코드로 보정하고 싶은 유혹이 있다 — 겹쳐서 페이드하거나, 색을 맞추거나.
하지만 그건 잘못을 감추는 것이지 고치는 것이 아니다. 이음매가 튀면 그건
**프롬프트가 앞뒤 장을 이어 그리지 못했다는 신호**이고, 그 신호는 보여야 한다.
가리면 다음에 무엇을 고쳐야 하는지 알 수 없다.

폭이 다른 장이 섞이면 가장 좁은 폭에 맞춘다. 늘리지 않는다 — 늘리면 그 장만
흐릿해져서, 원인이 생성인지 이어붙이기인지 구분할 수 없게 된다.
"""

from __future__ import annotations

from pathlib import Path

EPISODE_FILE = "episode.png"

# 한 장이 너무 크면(2K x 7장 = 세로 16,800px) 뷰어와 편집기가 버거워한다.
# PNG 자체 한계(65,535px)보다 훨씬 앞에서 실용 한계가 온다.
MAX_HEIGHT = 30000


class StitchError(RuntimeError):
    """이어 붙이기 실패. run.py 가 사람이 읽을 메시지로 바꿔 출력한다."""


def episode_path(ep_dir: Path) -> Path:
    return ep_dir / EPISODE_FILE


def pick_paths(ep_dir: Path, conditions: list[str], numbers: list[int],
               picks: dict[tuple[str, int], int]) -> tuple[str, list[Path]]:
    """어느 조건의 그림으로 1화를 만들 것인가 + 그 파일 목록.

    조건을 고르는 이유: --sheet-only 는 config 의 조건을 전부 훑으므로 첫 번째가
    A(첨부 없음, 폴더도 없음)일 수 있다. 실제로 뽑아 둔 것을 붙여야 하므로
    **이미지가 가장 많이 있는 조건**을 고른다. 같으면 앞의 것.

    후보 번호는 picks.csv 를 보고, 기록이 없거나 그 파일이 없으면 c1 을 쓴다 —
    후보가 1장이면 채택이라는 말 자체가 성립하지 않기 때문이다.
    """
    best_cond, best_paths, best_hits = "", [], -1
    for cond in conditions:
        paths = []
        for n in numbers:
            k = picks.get((cond, n)) or 1
            p = ep_dir / cond / f"scene{n}_c{k}.png"
            if not p.exists() and k != 1:
                p = ep_dir / cond / f"scene{n}_c1.png"
            paths.append(p)
        hits = sum(1 for p in paths if p.exists())
        if hits > best_hits:
            best_cond, best_paths, best_hits = cond, paths, hits
    return best_cond, best_paths


def stitch(paths: list[Path], out: Path) -> tuple[int, int]:
    """세로로 틈 없이 붙여 한 장으로 저장한다. (가로, 세로) 를 돌려준다.

    틈을 두지 않는 이유: 틈은 곧 흰 띠이고, 흰 띠가 보이는 순간 "이어진 한 편"
    이 아니라 "장 여러 개" 로 읽힌다. 화면 사이 호흡은 이미 컷 설계(gap_after)가
    그림 안에서 만들고 있다.
    """
    try:
        from PIL import Image
    except ImportError as exc:      # pragma: no cover - 환경 문제
        raise StitchError(
            "Pillow 가 없어 1화를 이어 붙일 수 없습니다.\n"
            "        pip install Pillow") from exc

    usable = [p for p in paths if p.exists()]
    if not usable:
        raise StitchError("이어 붙일 이미지가 하나도 없습니다.")

    images = []
    try:
        for p in usable:
            im = Image.open(p)
            im.load()
            images.append(im.convert("RGB"))
    except OSError as exc:
        raise StitchError(f"이미지를 읽지 못했습니다: {exc}") from exc

    width = min(im.width for im in images)
    scaled = []
    for im in images:
        if im.width != width:
            # 좁은 쪽에 맞춰 **줄이기만** 한다. 늘리면 그 장만 흐려진다.
            h = max(1, round(im.height * width / im.width))
            im = im.resize((width, h), Image.LANCZOS)
        scaled.append(im)

    total = sum(im.height for im in scaled)
    if total > MAX_HEIGHT:
        raise StitchError(
            f"이어 붙이면 세로 {total:,}px 입니다 (상한 {MAX_HEIGHT:,}px).\n"
            f"        Scene 을 줄이거나 provider.options.image_size 를 낮추세요.")

    sheet = Image.new("RGB", (width, total), (255, 255, 255))
    y = 0
    for im in scaled:
        sheet.paste(im, (0, y))
        y += im.height
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    return width, total
