# -*- coding: utf-8 -*-
"""저장·공유용 GIF — #713 (2026-10-10).

도감에서 저장하면 애니메이션 webp 가 받아져 휴대폰 갤러리에서 안 움직였다. 후처리가 webp 옆에
같은 움직임의 GIF 를 함께 굽는다. 여기서 지키는 것:
(1) 프레임 수·간격·무한 반복이 webp 와 같다(2프레임 450ms · 16프레임 120ms)
(2) 투명은 투명으로 남고(체크무늬·검은 배경 금지), 검은 외곽선이 투명으로 뚫리지 않는다
    — 실험용 save_transparent_gif 는 16장 중 3장에서 외곽선을 뚫었다.
(3) 서비스 후처리(service_post)가 8종 webp 마다 GIF 를 같이 낸다
(4) 백필은 GIF 가 이미 있으면 건너뛰고, dry-run 은 아무것도 안 올린다

실행: python -m pytest zzal/be/src/test/python/test_gif_out.py
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageSequence

HERE = Path(__file__).resolve().parent
PIPE = HERE.parents[1] / "main" / "resources" / "zzal" / "pipeline" / "v1"
sys.path.insert(0, str(PIPE))
sys.path.insert(0, str(HERE))
import backfill_gif  # noqa: E402
import gif_out  # noqa: E402
import service_post  # noqa: E402


def _frame(i, w=280, h=300):
    """검은 외곽선 + 채색 몸 + 반투명 한 조각, 나머지는 투명. 프레임마다 몸이 조금 내려간다."""
    a = np.zeros((h, w, 4), np.uint8)
    a[50 + i * 3:250, 60:220] = (0, 0, 0, 255)
    a[55 + i * 3:245, 65:215] = (240, 120 + i * 5, 90, 255)
    a[100:120, 100:120] = (30, 200, 40, 128)
    return Image.fromarray(a)


def _webp(path, n, ms):
    fs = [_frame(i) for i in range(n)]
    fs[0].save(path, save_all=True, append_images=fs[1:], duration=ms, loop=0, format="WEBP",
               lossless=False, quality=80, method=6)
    return [f.convert("RGBA").copy() for f in ImageSequence.Iterator(Image.open(path))]


def _gif_frames(path):
    im = Image.open(path)
    assert im.format == "GIF"
    out = []
    for k in range(im.n_frames):
        im.seek(k)
        out.append(np.array(im.convert("RGBA")))
    return im, out


def test_two_frame_gif_keeps_count_duration_and_loop(tmp_path):
    _webp(tmp_path / "base.webp", 2, 450)
    g = gif_out.webp_to_gif(tmp_path / "base.webp")
    assert g == str(tmp_path / "base.gif")
    assert not list(tmp_path.glob("*.part.gif"))           # 반쯤 쓴 파일이 남지 않는다
    im, frames = _gif_frames(g)
    assert im.n_frames == 2
    assert im.info["duration"] == 450 and im.info["loop"] == 0
    assert not np.array_equal(frames[0], frames[1])         # 두 장이 실제로 다르다 = 움직인다


def test_sixteen_frame_gif_stays_transparent_without_outline_holes(tmp_path):
    src = _webp(tmp_path / "motion.webp", 16, 120)
    im, frames = _gif_frames(gif_out.webp_to_gif(tmp_path / "motion.webp"))
    assert im.n_frames == 16 and im.info["duration"] == 120
    for k, (got, want) in enumerate(zip(frames, src)):
        want = np.array(want)
        opaque = want[:, :, 3] > gif_out.ALPHA_ON
        assert got[0, 0, 3] == 0 and got[-1, -1, 3] == 0, f"{k}번 장 모서리가 투명이 아니다"
        holes = int(((got[:, :, 3] > 0) != opaque).sum())
        assert holes == 0, f"{k}번 장 투명 경계가 {holes}px 어긋남(외곽선 뚫림)"
        diff = np.abs(got[opaque][:, :3].astype(int) - want[opaque][:, :3].astype(int)).mean()
        assert diff < 2.0, f"{k}번 장 색이 평균 {diff:.1f} 어긋남"


def test_service_post_writes_gif_beside_each_webp(tmp_path):
    from test_postprocess_holes import make_grid   # 같은 폴더의 정상 격자 만들기를 그대로 쓴다
    png = tmp_path / "g.png"
    Image.fromarray(make_grid(ribbon=True).astype(np.uint8)).save(png)
    assert service_post.main([str(png), str(tmp_path / "o"), "--no-anchors"]) == 0
    webps = sorted(p.stem for p in (tmp_path / "o").glob("*.webp"))
    gifs = sorted(p.stem for p in (tmp_path / "o").glob("*.gif"))
    assert len(webps) == 8 and gifs == webps
    for name in webps:
        w = Image.open(tmp_path / "o" / f"{name}.webp")
        g = Image.open(tmp_path / "o" / f"{name}.gif")
        # 시험 격자는 짝 두 장이 똑같아 webp 도 한 장으로 접힌다 — 장 수는 "같다" 까지만 본다.
        assert g.n_frames == w.n_frames and g.size == w.size
        a = np.array(w.convert("RGBA"))
        b = np.array(g.convert("RGBA"))
        assert ((a[:, :, 3] > gif_out.ALPHA_ON) == (b[:, :, 3] > 0)).all()


class _FakeStore:
    def __init__(self, root: Path, keys):
        self.root, self.uploaded = root, []
        for k in keys:
            p = root / k
            p.parent.mkdir(parents=True, exist_ok=True)
            if k.endswith(".webp"):
                _webp(p, 2, 450)
            else:
                p.write_bytes(b"GIF89a")

    def list_keys(self, prefix):
        for p in self.root.rglob("*"):
            k = p.relative_to(self.root).as_posix()
            if p.is_file() and k.startswith(prefix):
                yield k, p.stat().st_size

    def download(self, key, path):
        Path(path).write_bytes((self.root / key).read_bytes())

    def upload(self, key, path):
        self.uploaded.append(key)


def test_backfill_skips_existing_and_dry_run_uploads_nothing(tmp_path):
    store = _FakeStore(tmp_path / "s3", [
        "images/zzal/pets/14/basic/2/base.webp",
        "images/zzal/pets/14/basic/2/base.gif",            # 이미 있음 → 건너뜀
        "images/zzal/pets/14/basic/2/eat.webp",
        "images/zzal/pets/18/motions/3/1/motion.webp",
        "images/zzal/pets/18/basic/1/anchors.json",
    ])
    dry = backfill_gif.run(store, ["images/zzal/pets/"], apply=False, log=lambda *_: None)
    assert (dry["scanned"], dry["have"], dry["todo"], dry["made"]) == (3, 1, 2, 0)
    assert store.uploaded == []
    done = backfill_gif.run(store, ["images/zzal/pets/"], apply=True, log=lambda *_: None)
    assert done["made"] == 2 and done["failed"] == 0 and done["pets"] == 2
    assert sorted(store.uploaded) == ["images/zzal/pets/14/basic/2/eat.gif",
                                      "images/zzal/pets/18/motions/3/1/motion.gif"]
