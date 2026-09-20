#!/usr/bin/env python3
"""파이프라인이 **없는 모듈을 부르고 있지 않은지** 본다.

    python3 webtoon/ai/test_imports.py        # 마지막 줄에 ALL PASS 또는 FAILED

## 왜 있나

2026-09-12에 랜딩 웹서버(`landing/serve.py`)를 지웠는데 `s3_upload.py` 가
`from serve import thumbnail` 로 아직 부르고 있었다. 그 한 줄이 **함수 안에**
있어서 파일을 열어도 눈에 안 띄고, 부르는 자리가 다 그리고 나서 올릴 것을
만드는 **마지막 걸음**이라 앞 단계는 전부 성공했다.

그래서 이렇게 터졌다 — 배포하고, 웹툰 한 편을 끝까지 그리고(돈이 다 나가고),
그 다음에 `ModuleNotFoundError: No module named 'serve'`. 자바 쪽은 이 실패를
삼키므로(AfterRun#finish) 화면은 "만들지 못했습니다" 만 말하고, 그림이 DB 에
하나도 안 적혀 결과 화면도 비어 있었다.

빌드도 기동도 검사도 전부 통과한 채로 **사람이 한 편을 다 만든 뒤에** 처음
드러나는 종류다. 그래서 여기서 먼저 잡는다.

## 무엇을 보나

파이썬 파일마다 `import X` · `from X import ...` 를 전부 모아(함수 안에 숨은
것 포함), X 가 셋 중 하나에도 안 걸리면 잡는다.

1. 표준 라이브러리 (`sys.stdlib_module_names`)
2. 아래 THIRD_PARTY — requirements.txt 로 까는 것
3. 우리 파일 (`webtoon/ai/**/X.py`)

**모듈을 실제로 import 하지 않는다.** 그러면 openai·Pillow 가 깔려 있어야만
돌아서, 정작 CI 에서 못 돌린다. 이름만 본다.
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# 파이프라인이 실제로 도는 자리. 실험·보관용 폴더는 안 본다.
ROOTS = ("new_harness", "upload", "story-harness", "webtoon-harness")

# requirements.txt 가 까는 것들. 여기 없는 바깥 라이브러리를 새로 쓰기 시작하면
# 이 검사가 먼저 걸린다 — requirements.txt 에 적으라는 뜻이다.
THIRD_PARTY = {
    "PIL", "openai", "google", "anthropic", "yaml", "requests",
    "boto3", "botocore",
}

# 안 보는 것: 백업본과 실행하며 쌓이는 것.
def _skip(p: Path) -> bool:
    parts = set(p.parts)
    return (
        p.suffix != ".py"
        or ".bak" in p.name or p.name.endswith(".bak2")
        or bool(parts & {"__pycache__", "runs", "runs_backup", "outputs",
                         "work", "jobs", "jobs_nh", "jobs_spring"})
    )


def top_level_imports(tree: ast.AST) -> set[str]:
    """이 파일이 부르는 모듈 이름 — 함수 안에 숨은 것까지 전부."""
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                names.add(a.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            # `from . import x` 는 상대경로라 이름이 없다 — 볼 것이 없다.
            if node.level == 0 and node.module:
                names.add(node.module.split(".")[0])
    return names


def main() -> int:
    # 우리 파일 — 모듈(x.py)과 패키지(x/__init__.py) 둘 다.
    ours = {p.stem for root in ROOTS for p in (HERE / root).rglob("*.py")
            if not _skip(p)}
    ours |= {p.parent.name for root in ROOTS
             for p in (HERE / root).rglob("__init__.py") if not _skip(p)}
    known = set(sys.stdlib_module_names) | THIRD_PARTY | ours

    problems: list[str] = []
    checked = 0
    for root in ROOTS:
        for p in sorted((HERE / root).rglob("*.py")):
            if _skip(p):
                continue
            checked += 1
            try:
                tree = ast.parse(p.read_text(encoding="utf-8"), filename=str(p))
            except SyntaxError as exc:
                problems.append(f"{p.relative_to(HERE)}: 문법 오류 — {exc}")
                continue
            for name in sorted(top_level_imports(tree) - known):
                problems.append(
                    f"{p.relative_to(HERE)}: '{name}' 을 부르는데 그런 모듈이 없습니다")

    print(f"파이썬 {checked}개 파일의 import 를 봤습니다")
    if problems:
        for line in problems:
            print(f"  - {line}")
        print(f"FAILED: {len(problems)}건")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
