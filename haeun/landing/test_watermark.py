# -*- coding: utf-8 -*-
"""워터마크 검사 — pytest 아님. 그냥 돌리면 마지막 줄에 ALL PASS 가 찍힌다.

    cd landing && python test_watermark.py

여기서 지키려는 것은 넷이다.

1. **원본을 안 건드린다.** 표시는 내보낼 때만 붙는 것이라, 저장물이 한 바이트도
   안 바뀌어야 한다. 이게 깨지면 다시 구울 때마다 표시가 겹쳐 찍힌다.
2. **캐시가 원본을 따라온다.** 그림을 다시 그렸는데 예전 표시본이 나가면
   사용자는 안 고쳐진 줄 안다.
3. **실패해도 내려받기는 된다.** 표시는 있으면 좋은 것이지, 없다고 파일을 못
   주는 것이 아니다.
4. **세로로 아주 긴 그림에서도** 띠와 마크가 제 크기로 들어간다.
"""

from __future__ import annotations

import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from PIL import Image                      # noqa: E402

import watermark                           # noqa: E402

FAILED: list[str] = []


def check(label: str, cond: bool, detail: str = "") -> None:
    print(f"  {'ok  ' if cond else 'FAIL'}  {label}{'  — ' + detail if detail and not cond else ''}")
    if not cond:
        FAILED.append(label)


def make(path: Path, w: int, h: int, color=(120, 170, 200)) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (w, h), color).save(path)
    return path


def test_stamp_grows_and_keeps_width(tmp: Path) -> None:
    print("표시를 얹으면 폭은 그대로, 높이는 띠만큼 늘어난다")
    src = make(tmp / "a.png", 800, 1200)
    out = watermark.stamp(src, tmp / "out" / "a_wm.png", "초롱 · 1화")
    got = Image.open(out)
    check("폭은 안 변한다", got.width == 800, f"{got.width}")
    band = watermark._fit(800 * watermark.BAND_RATIO,
                          watermark.BAND_MIN, watermark.BAND_MAX)
    check("높이는 띠 높이만큼 는다", got.height == 1200 + band,
          f"{got.height} != {1200 + band}")


def test_source_untouched(tmp: Path) -> None:
    print("원본은 한 바이트도 안 바뀐다")
    src = make(tmp / "b.png", 600, 900)
    before = src.read_bytes()
    watermark.stamp(src, tmp / "out" / "b_wm.png")
    check("원본 그대로", src.read_bytes() == before)


def test_cache_follows_source(tmp: Path) -> None:
    print("캐시는 원본이 새로 그려지면 다시 만든다")
    root = tmp / "ep"
    src = make(root / "episode.png", 500, 700, (200, 120, 120))
    first = watermark.for_download(src, root, "초롱 · 1화")
    check("캐시 폴더에 생긴다", first.parent.name == watermark.CACHE_DIR, str(first))
    stamp1 = first.stat().st_mtime

    again = watermark.for_download(src, root, "초롱 · 1화")
    check("안 바뀌었으면 다시 안 그린다",
          again == first and again.stat().st_mtime == stamp1)

    # 원본을 다시 그린다 — 크기까지 바꿔서 눈으로도 구분되게
    make(root / "episode.png", 500, 1100, (120, 200, 140))
    import os
    os.utime(src, (stamp1 + 10, stamp1 + 10))
    third = watermark.for_download(src, root, "초롱 · 1화")
    band = watermark._fit(500 * watermark.BAND_RATIO,
                          watermark.BAND_MIN, watermark.BAND_MAX)
    check("원본이 새것이면 다시 그린다",
          Image.open(third).height == 1100 + band, str(Image.open(third).size))


def test_falls_back_to_source(tmp: Path) -> None:
    print("표시를 못 붙여도 내려받기는 된다")
    root = tmp / "broken"
    root.mkdir(parents=True, exist_ok=True)
    bad = root / "episode.png"
    bad.write_bytes("이건 그림이 아니다".encode("utf-8"))
    got = watermark.for_download(bad, root, "초롱 · 1화")
    check("원본 경로를 그대로 돌려준다", got == bad, str(got))

    gone = root / "없는파일.png"
    check("없는 파일도 그대로 돌려준다",
          watermark.for_download(gone, root) == gone)

    watermark.ENABLED = False
    try:
        src = make(root / "c.png", 400, 400)
        check("꺼 두면 원본 그대로", watermark.for_download(src, root) == src)
    finally:
        watermark.ENABLED = True


def test_tall_strip(tmp: Path) -> None:
    print("세로로 아주 긴 한 편에서도 띠가 제 크기로 들어간다")
    src = make(tmp / "tall.png", 900, 24000)
    out = watermark.stamp(src, tmp / "out" / "tall_wm.png", "초롱 · 1화")
    got = Image.open(out)
    band = got.height - 24000
    check("띠 높이는 상한을 안 넘는다", band <= watermark.BAND_MAX, str(band))
    check("띠 높이는 하한을 넘는다", band >= watermark.BAND_MIN, str(band))
    # 띠는 종이색 바탕이라, 그림(파랑)과 확실히 달라야 눈에 띈다
    check("띠가 실제로 밝게 그려졌다",
          got.getpixel((5, got.height - 3))[0] > 200,
          str(got.getpixel((5, got.height - 3))))


def test_caption_too_long_is_dropped(tmp: Path) -> None:
    print("작품 이름이 너무 길면 겹치는 대신 뺀다")
    src = make(tmp / "d.png", 420, 500)
    out = watermark.stamp(src, tmp / "out" / "d_wm.png", "아" * 200)
    check("그래도 그려진다", Image.open(out).width == 420)


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="wm-test-"))
    try:
        for fn in (test_stamp_grows_and_keeps_width, test_source_untouched,
                   test_cache_follows_source, test_falls_back_to_source,
                   test_tall_strip, test_caption_too_long_is_dropped):
            fn(tmp / fn.__name__)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if FAILED:
        print("\nFAILED: " + ", ".join(FAILED))
        return 1
    print("\nALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
