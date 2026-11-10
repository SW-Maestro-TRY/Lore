#!/usr/bin/env python3
"""
기존 펫 GIF 백필 — S3 의 움짤 webp 마다 옆에 저장·공유용 GIF 를 만들어 올린다(#713).

  python backfill_gif.py                 # dry-run(기본) — 무엇을 만들지 세기만 한다
  python backfill_gif.py --apply         # 실제로 만들어 올린다
      [--bucket lore-content] [--endpoint http://minio:9000]
      [--prefix images/zzal/pets/ ...] [--limit N]

★ 왜 필요한가 — 이번 변경부터 후처리가 webp 와 함께 GIF 를 굽는다. 그 전에 태어난 펫은
  GIF 가 없어 저장 버튼이 webp 로 폴백한다(휴대폰 갤러리에서 안 움직인다). 이 스크립트가 메운다.

★ 돈이 안 든다 — 이미지 생성 호출이 없다. 이미 있는 webp 를 받아 GIF 로 다시 묶을 뿐이다.

★ 몇 번을 돌려도 안전하다 — GIF 가 이미 있으면 건너뛴다(덮어쓰지 않는다). 중간에 끊겨도
  다시 돌리면 남은 것만 한다. 원본 webp 는 읽기만 한다.

★ 어디서 돌리나 — boto3 와 Pillow 가 있는 파이썬. 서버 이미지에서는 웹툰 venv
  (/opt/lore/venv-webtoon/bin/python)가 그렇다(zzal venv 에는 boto3 가 없다).
  열쇠는 넣지 않는다 — boto3 가 환경변수(AWS_ACCESS_KEY_ID …)나 인스턴스 역할에서 찾는다.
  dev(MinIO)는 --endpoint http://minio:9000 (또는 APP_S3_ENDPOINT 를 그대로 물려받는다).

종료코드: 0 정상 · 1 한 장이라도 실패(나머지는 끝까지 한다) · 2 인자 오류
"""
import argparse
import os
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import gif_out  # noqa: E402

DEFAULT_PREFIXES = ["images/zzal/pets/"]
GIF_TYPE = "image/gif"
# 자바 S3Storage.CACHE_CONTROL 과 같은 값 — 같은 키에 다른 그림을 다시 올리지 않으므로 오래 캐시한다.
CACHE_CONTROL = "public, max-age=31536000, immutable"


class S3Store:
    """boto3 로 한 버킷을 다룬다. 테스트는 같은 모양의 가짜를 넘긴다."""

    def __init__(self, bucket, endpoint=None, region=None):
        import boto3                       # 여기서 부른다 — 테스트·dry-run 계획만 볼 때도 필요 없게
        from botocore.config import Config
        kw = {"region_name": region or os.environ.get("AWS_REGION", "ap-northeast-2")}
        if endpoint:
            # MinIO 는 경로 방식이어야 붙는다(자바 S3Config 와 같은 선택).
            kw["endpoint_url"] = endpoint
            kw["config"] = Config(s3={"addressing_style": "path"})
        self.s3 = boto3.client("s3", **kw)
        self.bucket = bucket

    def list_keys(self, prefix):
        for page in self.s3.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=prefix):
            for o in page.get("Contents", []):
                yield o["Key"], o["Size"]

    def download(self, key, path):
        self.s3.download_file(self.bucket, key, str(path))

    def upload(self, key, path):
        self.s3.upload_file(str(path), self.bucket, key,
                            ExtraArgs={"ContentType": GIF_TYPE, "CacheControl": CACHE_CONTROL})


def gif_key_of(webp_key: str) -> str:
    """자바 MotionImageKeys.gifOf · 프론트 gifUrlOf 와 같은 규칙 — 확장자만 바꾼다."""
    return webp_key[: -len(".webp")] + ".gif"


def plan(store, prefixes):
    """(만들 것 [(webp 키, 크기)], 이미 GIF 가 있는 webp 수, 훑은 webp 수)."""
    todo, have, scanned = [], 0, 0
    for prefix in prefixes:
        keys = dict(store.list_keys(prefix))
        for key, size in sorted(keys.items()):
            if not key.lower().endswith(".webp"):
                continue
            scanned += 1
            if gif_key_of(key) in keys:
                have += 1
            else:
                todo.append((key, size))
    return todo, have, scanned


def run(store, prefixes, apply=False, limit=None, log=print):
    t0 = time.time()
    todo, have, scanned = plan(store, prefixes)
    if limit is not None:
        todo = todo[:limit]
    pets = sorted({k.split("/")[3] for k, _ in todo if k.count("/") >= 4 and k.startswith("images/zzal/pets/")})
    log(f"[백필] 훑은 webp {scanned} · 이미 GIF {have} · 만들 것 {len(todo)} (펫 {len(pets)}마리)"
        + ("" if apply else " — dry-run, 아무것도 안 올린다. 실제로 하려면 --apply"))
    made = failed = 0
    webp_bytes = gif_bytes = 0
    if apply:
        with tempfile.TemporaryDirectory(prefix="gif-backfill-") as tmp:
            for i, (key, size) in enumerate(todo, 1):
                src = Path(tmp) / "src.webp"
                try:
                    store.download(key, src)
                    dst = Path(gif_out.webp_to_gif(src))
                    store.upload(gif_key_of(key), dst)
                    made += 1
                    webp_bytes += src.stat().st_size
                    gif_bytes += dst.stat().st_size
                    log(f"  [{i}/{len(todo)}] {key} {src.stat().st_size // 1024}KB → GIF {dst.stat().st_size // 1024}KB")
                except Exception as e:     # 한 장 실패로 나머지를 멈추지 않는다 — 다시 돌리면 그것만 한다
                    failed += 1
                    log(f"  [{i}/{len(todo)}] ✗ {key} — {type(e).__name__}: {e}")
    sec = time.time() - t0
    log(f"[백필] 끝 — 만듦 {made} · 실패 {failed} · {sec:.1f}초"
        + (f" · webp 합 {webp_bytes // 1024}KB → GIF 합 {gif_bytes // 1024}KB" if made else ""))
    return {"scanned": scanned, "have": have, "todo": len(todo), "made": made, "failed": failed,
            "seconds": round(sec, 1), "pets": len(pets), "webp_bytes": webp_bytes, "gif_bytes": gif_bytes}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="S3 움짤 webp → 저장·공유용 GIF 백필(#713)")
    ap.add_argument("--apply", action="store_true", help="실제로 만들어 올린다(없으면 dry-run)")
    ap.add_argument("--bucket", default=os.environ.get("CONTENT_S3_BUCKET"))
    ap.add_argument("--endpoint", default=os.environ.get("APP_S3_ENDPOINT") or None)
    ap.add_argument("--prefix", action="append", help=f"훑을 접두사(여러 번 가능, 기본 {DEFAULT_PREFIXES})")
    ap.add_argument("--limit", type=int, help="이번에 만들 최대 개수(시험용)")
    ns = ap.parse_args(argv)
    if not ns.bucket:
        print("버킷이 없습니다 — --bucket 또는 CONTENT_S3_BUCKET", file=sys.stderr)
        return 2
    store = S3Store(ns.bucket, ns.endpoint)
    r = run(store, ns.prefix or DEFAULT_PREFIXES, apply=ns.apply, limit=ns.limit)
    return 1 if r["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
