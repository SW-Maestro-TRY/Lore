#!/usr/bin/env python3
"""
저장·공유용 GIF — 서비스가 지급한 애니메이션 webp 를 같은 움직임의 투명 GIF 로 옮긴다(#713).

  python3 gif_out.py <a.webp> [<b.webp> ...]   → 같은 자리에 a.gif · b.gif

★ 왜 GIF 를 따로 만드나 — 화면 재생은 webp 가 낫다(같은 그림이 GIF 의 1/4~1/5).
  그런데 **저장한 파일**이 webp 면 휴대폰 갤러리(iOS 사진·카카오톡 전달)가 첫 장만 보여 준다.
  저장·공유만 GIF 로 준다. 화면은 계속 webp 를 쓴다.

★ 왜 프레임이 아니라 **완성된 webp 에서** 만드나 — 서비스에 나간 바로 그 그림과 같아야 한다.
  후처리 단계마다(8종 지름길 복사·티끌 제거·정규화·16칸 모션·관리자 교체) 프레임을 따로 잡으면
  GIF 만 다른 그림이 되는 길이 생긴다. webp 하나에서 만들면 기존 펫 백필(backfill_gif.py)도
  **같은 함수 한 줄**을 탄다.

★ 투명 처리를 state8_v3.save_transparent_gif 에 맡기지 않는 이유 (2026-10-10 실측)
  그 함수(실험 검수용 애니.gif)로 16프레임을 만들었더니 **16장 중 3장에서 검은 외곽선이 통째로
  투명**이 됐다(합성 그림, 장마다 662~790px). 팔레트를 255색으로 줄인 뒤 투명 번호를 덧칠하는
  방식이라, 저장할 때 Pillow 가 팔레트를 다시 정리하면서 검정과 투명 번호가 겹치는 장이 생긴다.
  여기서는 **투명 번호를 0번으로 비워 두고** 색은 1~255번에만 넣는다 — 같은 시험에서 0장.
  실험 파일(state8_v3)은 확정본과 바이트까지 같아야 해서 고치지 않는다.
  GIF 는 투명이 켜짐/꺼짐뿐이라 반투명 가장자리는 알파 8 기준(실험과 같은 값)으로 갈린다.

⚠️ GIF 는 프레임마다 256색이다. 이 그림들은 평면 채색이라 티가 거의 안 나지만, 그라데이션이
  넓은 그림이면 띠가 보일 수 있다.
"""
import sys
from pathlib import Path

from PIL import Image, ImageSequence, ImageStat

# ★ numpy 를 안 쓴다 — 백필(backfill_gif.py)은 boto3 가 있는 쪽 파이썬(서버의 웹툰 venv)에서 돈다.
#   그 venv 에는 Pillow 는 있고 numpy 는 없다. Pillow 만으로 끝나게 둬야 두 자리가 같은 함수를 탄다.

# 이보다 알파가 크면 보이는 픽셀. 실험 검수용 GIF(state8_v3.save_transparent_gif)와 같은 값.
ALPHA_ON = 8
TRANSPARENT = 0      # 투명 번호 — 색은 1~255 번에만 둔다


def gif_path_of(webp_path) -> Path:
    """webp 옆의 GIF 이름 — 확장자만 바꾼다(자바·프론트의 키 규칙과 같다)."""
    p = Path(webp_path)
    if p.suffix.lower() != ".webp":
        raise ValueError(f"webp 가 아닙니다: {p.name}")
    return p.with_suffix(".gif")


def read_frames(webp_path):
    """(RGBA 프레임들, 프레임별 ms) — 애니메이션 webp 를 캔버스 크기로 합성된 채 읽는다."""
    frames, durations = [], []
    with Image.open(webp_path) as im:
        for fr in ImageSequence.Iterator(im):
            frames.append(fr.convert("RGBA").copy())
            durations.append(int(fr.info.get("duration", im.info.get("duration", 100)) or 100))
    if not frames:
        raise ValueError(f"프레임이 없습니다: {webp_path}")
    return frames, durations


def to_palette(frame: Image.Image) -> Image.Image:
    """RGBA 한 장 → 0번이 투명인 P 이미지. 색은 255색 이하로 줄이고 디더링은 안 한다(평면 채색에 점이 낀다)."""
    rgba = frame.convert("RGBA")
    opaque = rgba.getchannel("A").point(lambda v: 255 if v > ALPHA_ON else 0)      # L, 보이면 255
    rgb = rgba.convert("RGB")
    if opaque.getbbox():
        # 투명 자리를 보이는 색의 평균으로 눕힌다 — 흰색 같은 엉뚱한 색에 팔레트 칸을 쓰지 않게.
        mean = tuple(int(round(v)) for v in ImageStat.Stat(rgb, opaque).mean)
        flat = Image.new("RGB", rgb.size, mean)
        flat.paste(rgb, mask=opaque)
        rgb = flat
    q = rgb.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    colors = q.getpalette()[:255 * 3]
    # 색 번호를 하나씩 밀어 0번을 비운다(0~254 → 1~255). P 를 L 로 읽어 번호 자체를 옮긴다.
    idx = Image.frombytes("L", q.size, q.tobytes()).point(lambda i: min(i + 1, 255))
    idx.paste(TRANSPARENT, mask=opaque.point(lambda v: 255 - v))
    out = Image.frombytes("P", idx.size, idx.tobytes())
    out.putpalette([0, 0, 0] + colors)
    out.info["transparency"] = TRANSPARENT
    return out


def save_gif(frames, path, duration) -> None:
    """RGBA 프레임들 → 무한 반복 투명 GIF. disposal=2 — 안 주면 앞 장이 남아 잔상이 진다."""
    pal = [to_palette(f) for f in frames]
    pal[0].save(path, format="GIF", save_all=True, append_images=pal[1:], duration=duration,
                loop=0, transparency=TRANSPARENT, disposal=2)


def webp_to_gif(webp_path, gif_path=None) -> str:
    """같은 프레임·같은 간격·무한 반복의 투명 GIF 를 쓴다. 쓴 경로를 돌려준다."""
    dst = Path(gif_path) if gif_path else gif_path_of(webp_path)
    frames, durations = read_frames(webp_path)
    # 간격이 모두 같으면 숫자 하나로 — 리스트로 주면 Pillow 가 프레임마다 따로 적는다(결과는 같다).
    duration = durations[0] if len(set(durations)) == 1 else durations
    tmp = dst.with_name(dst.stem + ".part.gif")
    save_gif(frames, tmp, duration)
    tmp.replace(dst)    # 반쯤 쓴 GIF 가 올라가지 않게 다 쓴 뒤에 이름을 붙인다
    return str(dst)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("사용법: gif_out.py <a.webp> [<b.webp> ...]", file=sys.stderr)
        sys.exit(2)
    for path in sys.argv[1:]:
        print(webp_to_gif(path))
