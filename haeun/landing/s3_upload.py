"""다 그린 작품을 S3 에 올린다.

**왜 올리는가.** 지금 그림은 하네스 디스크에 있고 하네스가 가공해서 내보낸다
(원본 -> 얹은 것 굽기 -> 폭 줄이기). 그래서 <b>하네스가 없으면 그림도 없다</b> —
실서버가 지금 그 상태고, 그 자리를 공개본 세 편을 구워 넣은 임시 전시판
(`web/gallery/`)이 메우고 있다. 새 작품이 생길 때마다 다시 뽑아 커밋해야 하니
확장이 안 된다.

올려 두면 화면이 CloudFront 에서 바로 읽는다. 하네스는 만드는 일만 하면 된다.

**주소를 못 맞히게 한다.** 키를 `images/webtoon/<run_id>/p02.jpg` 처럼 지으면
작품 번호를 아는 사람이 비공개 작품의 그림 주소까지 지어낼 수 있다. 이 저장소가
이미 쓰는 규칙(`images/<도메인>/<uuid>` — common 의 S3Service)을 그대로 따라
파일마다 임의의 이름을 준다. 어느 그림이 어느 장인지는 DB 가 안다.

**자격증명은 코드에 안 넣는다.** boto3 가 알아서 찾는다 — 서버에서는 EC2 에
붙은 역할(lore-ec2-role, s3:PutObject 있음), 로컬에서는 `AWS_PROFILE` 이나
환경변수. 그래서 이 파일에는 열쇠가 한 줄도 없다.

    python3 s3_upload.py <run_id> [<run_id> ...]      # 손으로 올릴 때
"""

from __future__ import annotations

import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import newharness_pipeline as nh

HERE = Path(__file__).resolve().parent

# CloudFront 와 맺은 계약이다 — `/images/*` 만 S3 로 가고, 그때 경로가 그대로
# S3 키가 된다. 이 접두사가 빠지면 올라가긴 하는데 읽을 때 403 이 난다
# (common 의 S3Service 에 2026-08-25 실제 사고가 적혀 있다).
PREFIX = "images/webtoon"

BUCKET = os.environ.get("CONTENT_S3_BUCKET", "").strip()
REGION = os.environ.get("AWS_REGION", "ap-northeast-2")

# 올린 주소를 알릴 곳. 비용 올리는 것과 **같은 한 마디**를 쓴다.
API_BASE = os.environ.get("LORE_API_BASE", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("LORE_WEBTOON_INTERNAL_TOKEN", "").strip()

# 화면이 부르는 폭과 같은 값이어야 한다(webtoon/fe 의 coverUrl=320 · pageUrl=1080).
# 다르면 올려 둔 것을 놔두고 또 줄이게 된다.
WIDTHS = (320, 1080)

# 1년. 키에 임의의 이름이 들어가 같은 주소가 다른 그림이 될 일이 없으므로
# 오래 담아 둬도 된다. 다시 구우면 새 키로 올라간다.
CACHE = "public, max-age=31536000, immutable"


def _client():
    import boto3                                    # 서버에만 있으면 된다
    return boto3.client("s3", region_name=REGION)


def _put(s3, path: Path) -> str:
    """파일 하나. -> S3 키"""
    kind = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    key = f"{PREFIX}/{uuid.uuid4().hex}{path.suffix.lower()}"
    s3.upload_file(str(path), BUCKET, key,
                   ExtraArgs={"ContentType": kind, "CacheControl": CACHE})
    return key


def upload_run(run_id: str, on_log=None) -> list[dict]:
    """이 작품의 장들을 올린다. -> [{page_no, width, key, bytes}, ...]

    폭마다 따로 올린다. 원본만 올리면 폭을 줄여 줄 서버가 여전히 필요해서
    지금 문제가 그대로 남는다 — 화면이 S3 만 보고 끝나야 뜻이 있다.

    `width=0` 은 원본이다. 편집실에서 다시 굽거나 나중에 다른 폭이 필요할 때
    쓰려고 같이 올린다.
    """
    if not BUCKET:
        raise RuntimeError("CONTENT_S3_BUCKET 이 비어 있습니다 — 어느 버킷에 올릴지 모릅니다")

    # 줄이는 함수는 **여기서** 가져온다. 파일 맨 위에서 가져오면
    # newharness_pipeline -> s3_upload -> serve -> newharness_pipeline 으로
    # 돌아서 서버가 아예 안 뜬다(실제로 그랬다).
    from serve import thumbnail

    s3 = _client()
    out: list[dict] = []
    cache_dir = nh.run_dir(run_id) / "cache"

    for no in nh.page_numbers(run_id):
        src = nh.final_unit(run_id, no)             # 얹은 것이 있으면 구운 것
        if not src:
            continue
        out.append({"page_no": no, "width": 0, "key": _put(s3, src),
                    "bytes": src.stat().st_size})
        for width in WIDTHS:
            small = thumbnail(src, cache_dir / f"s3_p{no}_w{width}.jpg", width)
            out.append({"page_no": no, "width": width, "key": _put(s3, small),
                        "bytes": small.stat().st_size})
        if on_log:
            on_log(f"[S3] {no}장 올림")

    return out


def report(run_id: str, uploads: list[dict], on_log=None) -> int:
    """올린 주소를 앱 서버에 알린다. -> 새로 적힌 줄 수 (실패하면 0)

    **예외를 밖으로 안 던진다.** 알리는 일 때문에 만들기가 멈추면 안 된다 —
    이미 돈을 치른 작업이다. 못 알린 것은 다시 올리면 그때 같이 알려진다
    (서버가 같은 자리를 덮어써 주므로 두 번 보내도 줄이 안 는다).
    """
    if not uploads:
        return 0
    if not TOKEN:
        # **조용히 넘어가면 안 된다.** 그림은 S3 에 올라갔는데 주소만 DB 에
        # 안 적히는 상태가 되고, 화면은 "올렸습니다" 를 그대로 보여 준다.
        # 나중에 DB 로 작품을 찾을 때 그림이 없는 줄만 남는다 — 실제로 한 번
        # 겪었고, 원인이 이 한 줄이라는 것을 알아내는 데 한참 걸렸다.
        if on_log:
            on_log("[S3] LORE_WEBTOON_INTERNAL_TOKEN 이 없어 주소를 DB 에 못 적었습니다"
                   " — 그림은 올라갔지만 서버는 그것을 모릅니다."
                   " 환경변수를 넣고 이 작업을 다시 올리면 그때 같이 적힙니다.")
        print("[S3] ⚠ LORE_WEBTOON_INTERNAL_TOKEN 없음 — S3 에는 올라갔으나"
              " DB 에 주소를 안 적었습니다", file=sys.stderr)
        return 0
    body = json.dumps({"runId": run_id, "pages": [
        {"pageNo": u["page_no"], "width": u["width"],
         "key": u["key"], "bytes": u["bytes"]} for u in uploads]},
        ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{API_BASE}/api/webtoon/v1/internal/pages", data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Lore-Internal": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            saved = (json.loads(res.read().decode("utf-8")).get("data") or {}).get("saved", 0)
    except (urllib.error.URLError, OSError, ValueError) as exc:
        if on_log:
            on_log(f"[S3] 주소를 못 알렸습니다 ({type(exc).__name__}) — 다음에 다시 보냅니다")
        return 0
    if on_log:
        on_log(f"[S3] 그림 {saved}개 주소를 알렸습니다")
    return int(saved)


def publish(run_id: str, on_log=None) -> int:
    """올리고 알리기까지. 만들기가 끝나면 이것을 부른다.

    **여기서 실패해도 만들기는 성공이다.** 그림은 이미 하네스 디스크에 있고
    화면도 그것으로 볼 수 있다 — S3 는 하네스 없이도 보이게 하려는 것이지,
    만드는 데 필요한 것이 아니다.
    """
    if not BUCKET:
        return 0                                # 안 켜 둔 환경(로컬 등)에서는 그냥 지나간다
    try:
        got = upload_run(run_id, on_log=on_log)
    except Exception as exc:                    # noqa: BLE001
        if on_log:
            on_log(f"[S3] 못 올렸습니다 ({type(exc).__name__}) — 그림은 서버에 그대로 있습니다")
        return 0
    return report(run_id, got, on_log=on_log)


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 1
    if not BUCKET:
        print("CONTENT_S3_BUCKET 이 비어 있습니다.", file=sys.stderr)
        return 1
    for run_id in argv:
        got = upload_run(run_id, on_log=lambda line: print("  " + line))
        total = sum(x["bytes"] for x in got)
        print(f"{run_id}: {len(got)}개 · {total / 1048576:.1f} MB")
        report(run_id, got, on_log=lambda line: print("  " + line))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
