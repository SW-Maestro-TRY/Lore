"""오늘·이번 달 얼마나 나갔나.

    python3 spend_report.py                    # 로컬 스프링(127.0.0.1:8080)
    python3 spend_report.py https://lorecomic.com

**왜 화면이 아니라 여기인가.** 단계별·모델별 원가는 우리 원가 구조가 그대로
드러나는 값이다. 브라우저에서 보려면 그 화면을 여는 열쇠를 브라우저에 심어야
하는데, 심는 순간 더 이상 비밀이 아니다. 이 저장소에는 아직 관리자 로그인이
없고, #228 하나 때문에 그걸 새로 만드는 것은 일이 너무 커진다.

그래서 서버와 미리 나눠 가진 한 마디(`LORE_WEBTOON_INTERNAL_TOKEN`)를 쓰는
사람 손에 두고, 그 한 마디를 아는 사람만 본다. 값을 올리는 쪽(usage_report.py)
과 같은 한 마디다.

만들려는 사람에게 보이는 값은 이것과 다르다 — 그쪽은 돈이 없고 "오늘 몇 편
남았나" 만 있다(`/api/webtoon/internal/today`).
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

TOKEN = os.environ.get("LORE_WEBTOON_INTERNAL_TOKEN", "").strip()
TIMEOUT = 10


def won(value) -> str:
    return f"{int(value or 0):,}원"


def bar(used: int, limit: int, width: int = 24) -> str:
    """남은 몫을 눈으로. 상한이 0이면(안 셈) 막대가 뜻이 없으므로 안 그린다."""
    if limit <= 0:
        return "(상한 없음)"
    filled = min(width, round(width * used / limit)) if limit else 0
    return "█" * filled + "·" * (width - filled)


def main() -> int:
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
    if not TOKEN:
        print("LORE_WEBTOON_INTERNAL_TOKEN 이 비어 있습니다.", file=sys.stderr)
        print("서버에 넣은 것과 같은 값을 환경변수로 주세요.", file=sys.stderr)
        return 1

    req = urllib.request.Request(f"{base}/api/webtoon/internal/spend",
                                 headers={"X-Lore-Internal": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            doc = json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 403 은 한 마디가 틀린 것이다 — 서버가 안 죽은 것과 구별해 준다.
        hint = " (한 마디가 서버 것과 다릅니다)" if e.code == 403 else ""
        print(f"못 읽었습니다: HTTP {e.code}{hint}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError, ValueError) as e:
        print(f"못 읽었습니다: {type(e).__name__} — {base} 가 떠 있나요?", file=sys.stderr)
        return 1

    d = doc.get("data") or {}
    print()
    print("  오늘")
    runs, run_limit = d.get("runs", 0), d.get("runLimit", 0)
    krw, krw_limit = d.get("krw", 0), d.get("krwLimit", 0)
    print(f"    편수   {bar(runs, run_limit)}  {runs}/{run_limit or '—'}편")
    print(f"    금액   {bar(krw, krw_limit)}  {won(krw)} / {won(krw_limit) if krw_limit else '—'}")
    print(f"    지금   {'만들 수 있습니다' if d.get('canCreate') else '오늘 몫이 다 찼습니다'}")
    print()
    print("  이번 달")
    print(f"    {d.get('monthRuns', 0)}편   {won(d.get('monthKrw'))}")

    lines = d.get("breakdown") or []
    if lines:
        print()
        print("  오늘 무엇에 (많이 쓴 순)")
        lines.sort(key=lambda r: -(r.get("krw") or 0))
        total = sum(r.get("krw") or 0 for r in lines) or 1
        for row in lines:
            krw = row.get("krw") or 0
            share = krw * 100 // total
            print(f"    {row.get('stage', '?'):<12} {row.get('model', '?'):<28}"
                  f" {won(krw):>10}  {share:>3}%  ({row.get('calls', 0)}콜)")
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
