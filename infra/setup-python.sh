#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 후처리용 파이썬 환경 준비 (Ubuntu)
#
# 후처리(격자 → 8종 움짤)는 numpy·scipy·pillow 를 쓰는 파이썬 스크립트다.
# 자바로 다시 쓰지 않은 이유는 그 로직이 실험에서 여러 사고를 잡아 가며 다듬은 것이라
# 다시 쓰면 그 사고들이 되살아날 위험이 크기 때문이다.
#
# 부르는 곳 둘
#   1) EC2 user data — 새 인스턴스가 처음 켜질 때. 무중단 배포·서버 증설 대비
#   2) /opt/lore/deploy-api.sh — 배포할 때마다. requirements.txt 가 바뀌었으면 갱신
#
# 두 번 이상 돌려도 안전하다(이미 있으면 넘어간다).
# ─────────────────────────────────────────────────────────────
set -euo pipefail

VENV=/opt/lore/venv
REQ=${1:-/opt/lore/requirements.txt}

if [[ ! -f "$REQ" ]]; then
  echo "requirements.txt 가 없습니다: $REQ" >&2
  exit 1
fi

# 시스템 파이썬 — Ubuntu 24.04 는 3.12 가 기본이다.
if ! command -v python3 >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y -qq python3 python3-venv
fi

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "가상환경 생성: $VENV"
  mkdir -p /opt/lore
  python3 -m venv "$VENV"
fi

# 이미 같은 목록이 깔려 있으면 넘어간다 — 배포마다 몇 분씩 걸리면 안 된다.
STAMP="$VENV/.requirements.sha"
NEW_SHA=$(sha256sum "$REQ" | cut -d' ' -f1)
if [[ -f "$STAMP" ]] && [[ "$(cat "$STAMP")" == "$NEW_SHA" ]]; then
  echo "파이썬 의존성 최신 — 건너뜀"
  exit 0
fi

echo "파이썬 의존성 설치"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet -r "$REQ"
echo "$NEW_SHA" > "$STAMP"

"$VENV/bin/python" - <<'PY'
import numpy, scipy, PIL
print(f"설치 확인 — numpy {numpy.__version__} · scipy {scipy.__version__} · pillow {PIL.__version__}")
PY
