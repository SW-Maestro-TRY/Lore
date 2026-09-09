#!/usr/bin/env bash
# 돌아가는 서버에서 OpenAPI 를 뽑아 프론트 타입을 만든다.
#
# ★ 왜 서버에서 뽑나 — 손으로 쓴 문서는 낡지만 서버는 안 낡는다. 백엔드가 응답을 바꾸면
#   이 타입도 같이 바뀌고, 프론트는 빌드가 깨지면서 즉시 안다. 눈으로 찾을 일이 없다.
#
# ★ 백엔드가 갱신한다 — 응답을 바꾸는 사람이 계약도 같이 갱신해야 어긋나지 않는다.
#
# 쓰는 법:  ./scripts/gen-api-types.sh [포트=8090]
set -euo pipefail

PORT="${1:-8090}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_TS="$ROOT/zzal/fe/lib/api-schema.ts"
OUT_JSON="$HOME/.claude/soma/lore/contract/openapi.json"

if ! curl -sf --max-time 5 "http://localhost:$PORT/actuator/health" >/dev/null; then
  echo "서버가 $PORT 에 없습니다. 먼저 띄우세요 — 계약은 돌아가는 서버가 정본입니다." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_JSON")"
curl -sf --max-time 20 "http://localhost:$PORT/api/v3/api-docs" -o "$OUT_JSON"
npx --yes openapi-typescript@7 "$OUT_JSON" -o "$OUT_TS" >/dev/null

# 생성물이라는 사실을 파일 맨 위에 박는다 — 손으로 고치면 다음 생성에 지워진다.
TMP="$(mktemp)"
{
  echo "/* eslint-disable */"
  echo "/**"
  echo " * ★ 자동 생성 파일 — 손으로 고치지 마세요."
  echo " *"
  echo " * 만드는 법:  ./scripts/gen-api-types.sh [포트]"
  echo " * 원본: 돌아가는 서버의 /api/v3/api-docs (백엔드가 갱신한다)"
  echo " *"
  echo " * ★ 이 파일이 계약이다. 백엔드가 응답을 바꾸면 여기가 바뀌고,"
  echo " *   그 값을 쓰던 화면이 빌드에서 깨진다 — 실행해 보고 알게 되는 일이 없다."
  echo " */"
  cat "$OUT_TS"
} > "$TMP"
mv "$TMP" "$OUT_TS"

echo "✅ $OUT_TS"
echo "   $(grep -c '' "$OUT_TS") 줄 · 명세 $OUT_JSON"
