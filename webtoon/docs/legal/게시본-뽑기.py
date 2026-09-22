#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""작업본(webtoon/docs/legal/*-v1-초안.md)에서 게시본(apps/web/content/legal/*.md)을 뽑는다.

작업본에는 게시하면 안 되는 것이 섞여 있다 — 맨 위의 "법률 검토 전 초안" 경고,
`<!--내부--> … <!--/내부-->` 로 감싼 정책 결정 메모, 맨 끝의 "채워야 할 것" 목록.
전에는 작업본을 화면이 통째로 읽어서, "그대로 서비스에 게시해서는 안 됩니다" 라는
줄이 사용자에게 그대로 보였다.

두 벌을 손으로 맞추면 반드시 어긋나므로, 작업본만 고치고 이 스크립트를 돌린다.

    python3 webtoon/docs/legal/게시본-뽑기.py

아직 안 채운 칸(`[[...]]`)은 일부러 그대로 남긴다. 화면이 그 칸을 보고 게시를
막기 때문이다(apps/web/app/legal/[doc]/page.tsx).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
SRC = ROOT / "webtoon" / "docs" / "legal"
DST = ROOT / "apps" / "web" / "content" / "legal"

DOCS = [("이용약관-v1-초안.md", "이용약관.md"), ("개인정보처리방침-v1-초안.md", "개인정보처리방침.md")]


def extract(text: str) -> tuple[str, str]:
    lines = text.split("\n")
    if not lines[0].startswith("# "):
        raise SystemExit("첫 줄이 제목(# ...)이 아니다")
    title = lines[0][2:].replace(" (v1 초안)", "")

    # 제목 바로 뒤의 경고 인용구와 구분선을 건너뛴다
    i = 1
    while i < len(lines) and (lines[i].strip() == "" or lines[i].startswith(">")):
        i += 1
    while i < len(lines) and lines[i].strip() in ("", "---"):
        i += 1
    body = "\n".join(lines[i:])

    cut = re.search(r"\n---\n\n## 채워야 할 것", body)
    if not cut:
        raise SystemExit("맨 끝 '채워야 할 것' 절을 못 찾았다 — 형식이 바뀌었는지 확인할 것")
    body = body[: cut.start()]

    body = re.sub(r"<!--내부-->.*?<!--/내부-->\n?", "", body, flags=re.S)
    body = re.sub(r"\n{3,}", "\n\n", body).rstrip() + "\n"
    return title, body


def main() -> int:
    DST.mkdir(parents=True, exist_ok=True)
    for src_name, dst_name in DOCS:
        title, body = extract((SRC / src_name).read_text(encoding="utf-8"))
        (DST / dst_name).write_text(f"# {title}\n\n{body}", encoding="utf-8")
        blanks = sorted(set(re.findall(r"\[\[[^\]]+\]\]", body)))
        print(f"{dst_name} — 남은 칸 {len(blanks)}개")
        for b in blanks:
            print(f"    {b}")
    if any(re.search(r"\[\[", (DST / d).read_text(encoding="utf-8")) for _, d in DOCS):
        print("\n칸이 남아 있어 화면에는 아직 '준비 중'으로 나간다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
