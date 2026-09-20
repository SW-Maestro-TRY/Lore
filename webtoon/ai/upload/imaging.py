#!/usr/bin/env python3
"""내려보낼 크기로 그림을 줄이는 한 가지 일만 하는 모듈.

## 왜 따로 뽑았나

이 함수는 원래 랜딩 웹서버(`landing/serve.py`)에 있었다. 그 서버를
2026-09-12에 지웠는데 `s3_upload.py` 가 아직 `from serve import thumbnail`
로 부르고 있었다 — **배포된 서버에서 그림을 다 그린 뒤에야 터졌다.**
`--prepare` 는 다 그리고 나서 올릴 것을 만드는 마지막 걸음이라, 그 앞은
전부 성공한 뒤에 거기서만 `ModuleNotFoundError: No module named 'serve'`
가 났다. 자바 쪽은 이 실패를 삼키므로(AfterRun#finish) 만들기는 "끝났다" 고
답하고, 그림이 DB 에 하나도 안 적혀 **결과 화면이 비어 있었다.**

옮겨 적은 것은 동작이 같다. 옮기면서 고치지 않았다.

## 왜 s3_upload.py 안에 안 두나

`--prepare` 는 S3 도 boto3 도 안 본다(자바가 올린다). 줄이는 일은 그 자체로
쓸 데가 있고 앞으로도 그럴 것이라, 올리는 코드와 섞지 않는다.
"""

from __future__ import annotations

import threading
from pathlib import Path

# 한 번에 한 장씩만 줄인다. 여러 요청이 같은 파일을 동시에 쓰면 반쯤 쓰인
# 그림을 읽는 일이 생긴다.
_thumb_lock = threading.Lock()
_warned_no_pillow = False


def warn_no_pillow() -> None:
    """Pillow 가 없다는 것을 **한 번만** 알린다.

    매번 찍으면 로그가 못 쓰게 되므로 한 번만 찍는다.
    """
    global _warned_no_pillow
    if not _warned_no_pillow:
        _warned_no_pillow = True
        print("[경고] Pillow 가 없어 그림을 줄이지 못합니다.\n"
              "        pip install Pillow")


def thumbnail(src: Path, dest: Path, width: int) -> Path:
    """웹으로 내려보낼 크기로 줄여 둔다.

    원본 컷은 2752x1536 짜리 PNG 다. 12장이면 30MB 가 넘어서 그대로 내려보내면
    결과 화면이 열리는 데만 한참 걸린다. 줄인 것은 작품 폴더에 캐시한다.
    """
    if dest.exists() and dest.stat().st_mtime >= src.stat().st_mtime:
        return dest
    with _thumb_lock:
        try:
            from PIL import Image
        except ImportError:
            warn_no_pillow()
            raise
        im = Image.open(src)
        im.load()
        if im.width > width:
            h = round(im.height * width / im.width)
            im = im.resize((width, h), Image.LANCZOS)
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.convert("RGB").save(dest, "JPEG", quality=88, optimize=True)
    return dest
