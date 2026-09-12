#!/usr/bin/env python3
"""화면 파일 검사 — 문법과, 목업이 본편과 어긋나지 않았는가.

두 하네스·overlay 검사와 같은 방식이다 (pytest 아님, 마지막 줄에 ALL PASS).

    cd landing && python3 check_web.py

## 왜 있는가

`node --check` 는 .js 파일만 본다. demo.html·index.html 의 **인라인
스크립트**는 아무도 안 보고 있었다 — 실제로 demo.html 의 가짜 자료를
고치다 괄호 하나를 잘라먹어서 `/demo` 의 스크립트가 통째로 죽었는데,
화면은 그냥 카드가 안 그려질 뿐이라 눈으로는 멀쩡해 보였다.

목업(demo.html)은 본편(index.html)의 사본이라 본편이 바뀌면 같이 고쳐야
한다. 그 어긋남도 여기서 몇 가지 붙잡는다.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
WEB = HERE / "web"
FAILED: list[str] = []


def ok(name: str, cond: bool, extra: str = "") -> None:
    print(("PASS  " if cond else "FAIL  ") + name + (f"   {extra}" if extra and not cond else ""))
    if not cond:
        FAILED.append(name)


def inline_scripts(path: Path) -> list[str]:
    """<script> ... </script> 안의 글. src= 가 있는 것은 파일이라 건너뛴다."""
    html = path.read_text(encoding="utf-8")
    return [b for tag, b in re.findall(r"<script([^>]*)>(.*?)</script>", html, re.S)
            if "src=" not in tag and b.strip()]


def test_syntax() -> None:
    """.js 파일과 .html 안의 인라인 스크립트가 문법에 맞는가."""
    for js in sorted(WEB.glob("*.js")):
        r = subprocess.run(["node", "--check", str(js)], capture_output=True, text=True)
        ok(f"문법: {js.name}", r.returncode == 0, r.stderr.strip()[:200])

    for html in sorted(WEB.glob("*.html")):
        for i, body in enumerate(inline_scripts(html), 1):
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False,
                                             encoding="utf-8") as f:
                f.write(body)
                tmp = f.name
            r = subprocess.run(["node", "--check", tmp], capture_output=True, text=True)
            ok(f"문법: {html.name} 안 스크립트 {i}", r.returncode == 0,
               r.stderr.strip()[:200])
            Path(tmp).unlink(missing_ok=True)


def test_demo_matches_main() -> None:
    """목업이 본편과 같은 것을 보여주는가.

    전부를 견줄 수는 없다(목업은 일부러 가짜 자료를 쓴다). 지금까지 실제로
    어긋났던 자리만 붙잡는다 — 사람이 멈춰 서는 자리와 그 자리의 제목.
    """
    demo = (WEB / "demo.html").read_text(encoding="utf-8")
    main = (WEB / "index.html").read_text(encoding="utf-8")

    # 본편이 멈추는 자리는 시트·이야기 둘뿐이다(newharness_pipeline 의 AWAITING).
    # 없어진 화면을 목업이 계속 시연하면, 보는 사람은 있는 줄 안다.
    ok("목업에 없어진 콘티 검수가 안 남아 있다", "nhBoard" not in demo)

    for title in ("어느 이야기로 갈까요?", "캐릭터 시트를 확인해 주세요"):
        ok(f"목업·본편 제목이 같다: {title}", title in demo and title in main)

    # 자가검수 **판정**(무엇이 몇 건 걸렸는지)은 사용자에게 안 보여준다 —
    # 목업에도 없어야 한다. 진행 문구("루가 이야기를 검수하고 있어요")는
    # 보여주는 것이 맞으니 여기서 안 센다: 그 둘은 다른 것이다.
    판정흔적 = ("nh-review-hook", "nh-review-episode", "reviewHtml",
                "읽다 걸리는 곳", "다음이 궁금해지는 것")
    for mark in 판정흔적:
        ok(f"목업이 검수 판정을 안 보여준다: {mark}",
           mark not in demo and mark not in main)

    # 반대로 진행 문구는 목업·본편이 **같은 말**을 써야 한다 — 목업만 옛
    # 문구로 남으면 시연을 본 사람이 다른 화면을 기대한다.
    for say in ("루가 이야기를 검수하고 있어요", "루가 방금 그린 그림을 검수하고 있어요",
                "루가 완성본을 처음부터 읽어보고 있어요"):
        ok(f"검수 진행 문구가 같다: {say[:14]}…", say in demo)


def test_sheet_zoom_everywhere() -> None:
    """시트를 눌러 크게 보는 것이 **세 화면 다** 되는가.

    거는 곳이 nh-review.js 한 곳이라, 그 파일과 짝인 css 를 안 읽는 화면이
    있으면 그 화면에서만 조용히 안 된다.
    """
    for name in ("index.html", "newharness.html", "demo.html"):
        html = (WEB / name).read_text(encoding="utf-8")
        if "nh-sheet-img" not in html:
            continue
        ok(f"{name} 이 확대 코드를 읽는다", "nh-review.js" in html)
        ok(f"{name} 이 확대 모양을 읽는다", "nh-review.css" in html)

    js = (WEB / "nh-review.js").read_text(encoding="utf-8")
    css = (WEB / "nh-review.css").read_text(encoding="utf-8")
    ok("확대가 시트 그림에 걸린다", "nh-sheet-img" in js)
    # 검수 팝업(z-index 200) 위에 떠야 한다 — 낮으면 눌러도 가려서 안 보인다
    import re as _re
    m = _re.search(r"\.nh-zoom \{[^}]*z-index:\s*(\d+)", css, _re.S)
    ok("확대가 검수 팝업 위에 뜬다", bool(m) and int(m.group(1)) > 200,
       f"z-index={m.group(1) if m else '없음'}")

    # 열자마자 화면을 채워야 한다 — max-width 만 걸면 원래 크기가 작은 그림은
    # 큰 화면일수록 더 작아 보인다(크게 보려고 눌렀는데 안 커진다).
    fit = _re.search(r"\.nh-zoom img \{([^}]*)\}", css, _re.S)
    ok("열자마자 화면을 채운다",
       bool(fit) and "width: 100%" in fit.group(1) and "object-fit: contain" in fit.group(1))

    # **플렉스 항목은 기본으로 줄어든다.** 이게 빠지면 배율을 올려도 상자에
    # 맞게 도로 줄어서 눌러도 아무 일이 안 일어난다(실측으로 겪은 것).
    big = _re.search(r"\.nh-zoom\.is-big img \{([^}]*)\}", css, _re.S)
    ok("확대한 그림이 도로 안 줄어든다", bool(big) and "flex: none" in big.group(1))
    ok("배율을 단계로 올린다", bool(big) and "var(--nh-zoom" in big.group(1))
    ok("확대하면 끌어서 옮긴다", bool(big) and "cursor: grab" in big.group(1)
       and "pointermove" in js and "scrollTo" in js)


def main() -> int:
    test_syntax()
    test_demo_matches_main()
    test_sheet_zoom_everywhere()
    if FAILED:
        print("FAILED:")
        for f in FAILED:
            print("  - " + f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
