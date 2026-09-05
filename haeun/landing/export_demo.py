"""공개해 둔 작품을 **정적 파일 한 벌**로 뽑는다.

실제 서버(lorecomic.com)에는 생성 하네스가 없다. 그래서 둘러보기가 부르는
`/api/webtoon/runs` 가 502 로 죽고, 웹툰 탭에 걸린 작품이 하나도 안 보인다.
하네스를 통째로 올리면 API 키·비용 상한·무한 생성이 한꺼번에 따라오므로,
**이미 다 그려 둔 공개본만** 그림과 함께 떠서 파일로 둔다. 서버도 키도
돈도 안 든다.

뽑는 값은 **도는 서버에서 그대로 받아 적는다.** 여기서 run 폴더를 다시
읽어 JSON 을 새로 만들면, 화면이 보는 값과 조용히 어긋난다(표지 장 번호,
장 사이 여백, 미리보기 여부 …). 같은 주소로 받아 그대로 쓰면 어긋날 구석이
없다. 그림도 서버의 줄이는 길(`?w=`)로 받는다 — 워터마크·얹은 말풍선까지
화면과 똑같이 구워져 나온다.

    python3 serve.py            # 다른 창에서 띄워 두고
    python3 export_demo.py      # 이걸 돌린다

나오는 자리는 `web/gallery/` 다 — **이 스크립트만 쓰는 폴더**다(통째로 지우고
새로 쓰므로 다른 것을 같이 두면 안 된다. `web/demo/` 에 손으로 둔 샘플 그림이
있어서 처음엔 거기 쓰려다 지울 뻔했다). `web/` 아래는 빌드가 통째로
`apps/web/public/static/` 으로 떠 가므로(webtoon/fe/sync-landing.sh),
배포에서는 `/static/demo/...` 로 열린다 — 따로 옮길 것이 없다.

**비공개 작품은 안 나온다.** 목록을 서버의 공개 목록에서 받으므로,
마이페이지에서 내린 것은 여기에 아예 안 실린다.
"""

from __future__ import annotations

import json
import shutil
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "web" / "gallery"

# 표지는 카드 크기(작게), 본문은 읽는 크기. 화면이 부르는 폭과 같은 값이라
# (nhApi 의 coverUrl=320 · pageUrl=1080) 배포에서도 같은 그림이 뜬다.
COVER_W = 320
PAGE_W = 1080


def get(base: str, path: str) -> bytes:
    with urllib.request.urlopen(base + path, timeout=60) as res:
        return res.read()


def get_json(base: str, path: str):
    return json.loads(get(base, path).decode("utf-8"))


def write_json(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8800"
    base = base.rstrip("/") + "/api"

    try:
        listing = get_json(base, "/runs")
    except (urllib.error.URLError, OSError) as e:
        print(f"서버에 못 붙었습니다 ({base}): {e}", file=sys.stderr)
        print("다른 창에서 `python3 serve.py` 를 띄운 뒤 다시 돌리세요.",
              file=sys.stderr)
        return 1

    runs = listing.get("runs") or []
    if not runs:
        print("공개된 작품이 없습니다. 마이페이지에서 공개로 올린 뒤 다시 돌리세요.",
              file=sys.stderr)
        return 1

    # 지난 판을 지우고 새로 쓴다 — 비공개로 내린 작품이 폴더에 남아 있으면
    # 목록에는 없는데 주소를 아는 사람에게는 계속 열린다.
    if OUT.exists():
        shutil.rmtree(OUT)

    total = 0
    for card in runs:
        rid = card["run_id"]
        ep = card.get("cover_episode") or (card.get("episodes") or [1])[0]
        folder = OUT / rid

        result = get_json(base, f"/runs/{rid}/result?ep={ep}")
        write_json(folder / "result.json", result)

        cover = card.get("cover_page")
        if cover:
            (folder / "cover.jpg").write_bytes(
                get(base, f"/runs/{rid}/page/{cover}?w={COVER_W}&ep={ep}"))
            total += 1

        for page in result.get("pages") or []:
            no = page["no"]
            (folder / f"p{no:02d}.jpg").write_bytes(
                get(base, f"/runs/{rid}/page/{no}?w={PAGE_W}&ep={ep}"))
            total += 1

        print(f"  {card.get('character','?')} · {card.get('title','?')}"
              f"  ({len(result.get('pages') or [])}장)")

    write_json(OUT / "runs.json", {"runs": runs})

    size = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    print(f"\n{len(runs)}편 · 그림 {total}장 · {size / 1048576:.1f} MB")
    print(f"나온 자리: {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
