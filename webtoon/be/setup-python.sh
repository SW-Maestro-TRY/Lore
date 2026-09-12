#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 웹툰 생성 하네스(new_harness)용 파이썬 환경 준비 (Ubuntu)
#
# infra/setup-python.sh(짤 전용, numpy·scipy·pillow)는 안 건드린다 — 담당이
# 다른 도메인 스크립트다. 웹툰은 필요한 패키지가 다르므로(openai·google-genai·
# PyYAML·requests) 가상환경도 별도(/opt/lore/venv-webtoon)로 둔다. 한 가상환경을
# 같이 쓰면 한쪽이 패키지를 올릴 때 다른 쪽 것과 버전이 부딪힐 수 있다.
#
#   bash webtoon/be/setup-python.sh
#
# 부르는 곳
#   /opt/lore/deploy-api.sh — 배포할 때마다. requirements.txt 가 바뀌었으면 갱신
#
# 두 번 이상 돌려도 안전하다(이미 있으면 넘어간다).
# ─────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

VENV=/opt/lore/venv-webtoon
REQ=${1:-"$ROOT/webtoon/ai/requirements.txt"}

if [[ ! -f "$REQ" ]]; then
  echo "requirements.txt 가 없습니다: $REQ" >&2
  exit 1
fi

# ★★ python3 이 있는지로 판단하면 안 된다 — python3-venv 는 별도 패키지고,
#    없어도 `python3 -m venv` 는 폴더만 만들고 0 으로 끝난다(infra/setup-python.sh
#    의 2026-09-04 배포 실패와 같은 함정).
if ! python3 -c 'import ensurepip' >/dev/null 2>&1; then
  echo "python3-venv 설치"
  apt-get update -qq
  apt-get install -y -qq python3-venv
fi

if [[ ! -x "$VENV/bin/pip" ]]; then
  echo "가상환경 생성: $VENV"
  mkdir -p /opt/lore
  rm -rf "$VENV"
  python3 -m venv "$VENV"
fi

if [[ ! -x "$VENV/bin/pip" ]]; then
  echo "가상환경에 pip 이 없습니다: $VENV/bin/pip" >&2
  exit 1
fi

# 이미 같은 목록이 깔려 있으면 넘어간다.
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
import openai, yaml, requests, PIL
print(f"설치 확인 — openai {openai.__version__} · PyYAML {yaml.__version__} "
      f"· requests {requests.__version__} · pillow {PIL.__version__}")
PY
