#!/usr/bin/env bash
# haeun 개발 환경 준비 — 한 번만 돌리면 된다.
#
#     cd haeun && ./setup.sh
#
# 왜 venv 를 쓰는가: 맥의 시스템 파이썬(homebrew)은 PEP 668 로 잠겨 있어서
# `pip install` 이 거부된다("externally-managed-environment"). --break-system-packages
# 로 뚫으면 OS 가 쓰는 파이썬을 건드리게 되므로, 이 폴더 전용 환경을 따로 만든다.
#
# 윈도우(Git Bash / WSL)에서도 그대로 돈다. PowerShell 이면 아래를 손으로:
#     python -m venv .venv
#     .venv\Scripts\pip install -r landing\requirements.txt

set -euo pipefail
cd "$(dirname "$0")"

PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null || { echo "python3 가 없습니다."; exit 1; }

echo "1/3  .venv 만드는 중 ($($PY -V))"
[ -d .venv ] || "$PY" -m venv .venv

echo "2/3  패키지 설치 중"
# landing/requirements.txt 가 나머지 둘을 -r 로 끌어온다.
./.venv/bin/python -m pip install --quiet --upgrade pip
./.venv/bin/pip install --quiet -r landing/requirements.txt

echo "3/3  확인"
./.venv/bin/python - <<'PY'
import importlib
for m in ("PIL", "requests", "yaml", "openai"):
    importlib.import_module(m)
print("     라이브러리 OK")
PY
(cd story-harness && ../.venv/bin/python test_gates.py | tail -1 | sed 's/^/     게이트: /')
(cd webtoon-harness && ../.venv/bin/python test_charsheet.py | tail -1 | sed 's/^/     시트:  /')

cat <<'MSG'

준비됐습니다. 서버는 이렇게 띄웁니다 (venv 의 파이썬이어야 합니다):

    cd landing && ../.venv/bin/python serve.py --open

매번 경로를 치기 싫으면 먼저 이걸 한 번:

    source .venv/bin/activate      # 그 뒤로는 그냥 python serve.py

API 키는 story-harness/.env · webtoon-harness/.env 에 있어야 합니다.
MSG
