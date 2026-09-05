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

# 시스템 파이썬 — Ubuntu 24.04 는 3.12 가 기본으로 깔려 있다.
#
# ★★ python3 이 있는지로 판단하면 안 된다. Ubuntu 는 python3 을 기본 제공하지만
#    가상환경을 만드는 데 필요한 python3-venv 는 별도 패키지다. 그래서 "python3 있으니 넘어감"
#    으로 처리하면 venv 를 만들 수단이 없는 상태로 다음 줄에 간다.
#
#    더 나쁜 것은 그 다음이다 — `python3 -m venv` 는 ensurepip 없이도 <b>폴더는 만들고</b>
#    0 으로 끝난다. 그래서 "가상환경 생성" 이 성공한 것처럼 보이는데 그 안에 pip 이 없고,
#    실패는 한참 뒤 `venv/bin/pip: No such file or directory` 로 나타난다.
#    (2026-09-04 배포 실패의 실제 원인)
if ! python3 -c 'import ensurepip' >/dev/null 2>&1; then
  echo "python3-venv 설치"
  apt-get update -qq
  apt-get install -y -qq python3-venv
fi

if [[ ! -x "$VENV/bin/pip" ]]; then
  echo "가상환경 생성: $VENV"
  mkdir -p /opt/lore
  # 반쯤 만들어진 것이 남아 있으면 지우고 다시 만든다. 남겨 두면 위 조건이 계속 참이라
  # 배포할 때마다 같은 자리에서 실패한다.
  rm -rf "$VENV"
  python3 -m venv "$VENV"
fi

# ★ 여기서 한 번 더 확인한다 — 위가 조용히 실패해도 여기서 멈춰야 원인이 드러난다.
if [[ ! -x "$VENV/bin/pip" ]]; then
  echo "가상환경에 pip 이 없습니다: $VENV/bin/pip" >&2
  exit 1
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
