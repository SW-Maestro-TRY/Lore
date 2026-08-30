#!/usr/bin/env python3
"""페이지 낱장을 위아래로 이어 붙여 한 장(episode.png)으로 만든다.

**말풍선 편집은 안 한다.** new_harness 는 지금 대사를 이미지 모델이 픽셀에
직접 그려 넣는다 — webtoon-harness 처럼 말풍선 자리를 비워 두고 편집기가
글자를 얹는 방식이 아니다. 그래서 여기서 만드는 episode.png 는 "그려진 그대로
이어 붙인 결과"이고, 나중에 대사를 옮기거나 고칠 수 없다. 그게 필요해지면
이 파일이 아니라 new_harness 자체(콘티→이미지 프롬프트 단계)에 말풍선 자리를
비우는 절을 새로 넣어야 한다 — 지금은 그 작업을 안 하기로 했다.

폭이 페이지마다 다르면(캔버스 설정을 바꿔 가며 그린 run 등) 가장 넓은 폭에
맞춰 나머지를 가운데 정렬한다 — 자르거나 늘리지 않는다.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

PAGE_GLOB = "page*.png"
OUT_NAME = "episode.png"


def page_files(run_dir: Path) -> list[Path]:
    """pages/pageNN.png 를 번호 순서로. 번호를 못 읽는 파일은 뺀다."""
    pages_dir = run_dir / "pages"
    files = []
    for p in pages_dir.glob(PAGE_GLOB):
        digits = "".join(c for c in p.stem if c.isdigit())
        if digits:
            files.append((int(digits), p))
    return [p for _, p in sorted(files)]


def stitch(run_dir: Path, out_path: Path | None = None) -> Path:
    """run_dir/pages/page*.png 를 위아래로 이어 붙여 저장한다. (결과 경로)"""
    files = page_files(run_dir)
    if not files:
        raise SystemExit(f"{run_dir / 'pages'} 에 이어 붙일 페이지가 없습니다.")

    images = [Image.open(p).convert("RGB") for p in files]
    width = max(im.width for im in images)
    total_height = sum(im.height for im in images)

    canvas = Image.new("RGB", (width, total_height), "white")
    y = 0
    for im in images:
        x = (width - im.width) // 2
        canvas.paste(im, (x, y))
        y += im.height

    out_path = out_path or (run_dir / OUT_NAME)
    canvas.save(out_path)
    return out_path


def main(argv=None) -> int:
    import argparse

    ap = argparse.ArgumentParser(description="페이지를 이어 붙여 episode.png 를 만든다.")
    ap.add_argument("--run-id", required=True, help="new_harness/runs/<run-id>")
    args = ap.parse_args(argv)

    run_dir = Path(__file__).resolve().parent / "runs" / args.run_id
    if not run_dir.exists():
        raise SystemExit(f"그런 run 이 없습니다: {run_dir}")

    out = stitch(run_dir)
    files = page_files(run_dir)
    print(f"[이어붙이기] {len(files)}장 -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
