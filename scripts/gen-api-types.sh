#!/usr/bin/env bash
# 서버 명세(OpenAPI)에서 프론트 타입을 만든다.
#
# ★ 왜 만드나 — 손으로 쓴 문서는 낡지만 명세는 안 낡는다. 백엔드가 응답을 바꾸면
#   이 타입도 같이 바뀌고, 프론트는 빌드가 깨지면서 즉시 안다. 눈으로 찾을 일이 없다.
#
# ★ 입력이 둘이다.
#   기본(오프라인) — 레포에 커밋된 스냅샷 `common/docs/openapi.json`.
#                    **백엔드가 갱신하고 커밋한다.** CI 가 서버를 못 띄우므로 이쪽이 정본이다.
#   --server [포트] — 돌아가는 서버의 `/api/v3/api-docs`. 손에 서버가 있을 때만.
#                    받아 온 명세를 스냅샷 자리에 같이 써 둔다(그래야 둘이 안 갈린다).
#
# 쓰는 법
#   ./scripts/gen-api-types.sh              # 레포 스냅샷에서 (CI 가 쓰는 길)
#   ./scripts/gen-api-types.sh --server     # 로컬 8090 서버에서
#   ./scripts/gen-api-types.sh --server 8091
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_TS="$ROOT/zzal/fe/lib/api-schema.ts"
SNAPSHOT="$ROOT/common/docs/openapi.json"

# ★ 정확한 버전을 박는다 — `@7` 로 두면 7.x 새 판이 나오는 날 출력 형식이 달라져
#   아무도 아무것도 안 바꿨는데 CI 가 빨간불을 켠다. 올릴 때는 여기를 고치고
#   생성물을 같이 커밋한다.
GENERATOR="openapi-typescript@7.13.0"

MODE="file"
PORT="8090"
while [ $# -gt 0 ]; do
  case "$1" in
    --server) MODE="server"; [ "${2-}" ] && case "$2" in [0-9]*) PORT="$2"; shift;; esac ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    [0-9]*) MODE="server"; PORT="$1" ;;   # 옛 사용법(`gen-api-types.sh 8091`)을 그대로 받는다
    *) echo "모르는 인자: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ "$MODE" = "server" ]; then
  # ★ 살아 있는지는 명세 그 자체로 확인한다 — actuator/health 는 계약과 상관없는 이유(메일·디스크)로도
  #   DOWN 이 되는데, 그때 "서버가 없다" 고 하면 멀쩡한 서버를 두고 헤매게 된다.
  if ! curl -sf --max-time 5 "http://localhost:$PORT/api/v3/api-docs" -o /dev/null; then
    echo "서버가 $PORT 에 없습니다. 먼저 띄우거나, 인자 없이 돌려 레포 스냅샷을 쓰세요." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$SNAPSHOT")"
  curl -sf --max-time 20 "http://localhost:$PORT/api/v3/api-docs" -o "$SNAPSHOT"
  echo "명세를 서버에서 받아 스냅샷에 썼습니다 — $SNAPSHOT"
elif [ ! -f "$SNAPSHOT" ]; then
  cat >&2 <<MSG
명세 스냅샷이 없습니다 — $SNAPSHOT

  이 파일은 **백엔드가 갱신해 커밋합니다**(서버가 계약의 주인이라서).
  손에 서버가 있다면 직접 뽑을 수도 있습니다:  ./scripts/gen-api-types.sh --server
MSG
  exit 1
fi

npx --yes "$GENERATOR" "$SNAPSHOT" -o "$OUT_TS" >/dev/null

# 생성물이라는 사실을 파일 맨 위에 박는다 — 손으로 고치면 다음 생성에 지워진다.
TMP="$(mktemp)"
{
  echo "/* eslint-disable */"
  echo "/**"
  echo " * ★ 자동 생성 파일 — 손으로 고치지 마세요."
  echo " *"
  echo " * 만드는 법:  ./scripts/gen-api-types.sh        (레포 스냅샷 common/docs/openapi.json 에서)"
  echo " *             ./scripts/gen-api-types.sh --server (돌아가는 서버에서)"
  echo " * 원본: common/docs/openapi.json — **백엔드가 갱신해 커밋한다.**"
  echo " *"
  echo " * ★ 이 파일이 계약이다. 백엔드가 응답을 바꾸면 여기가 바뀌고,"
  echo " *   그 값을 쓰던 화면이 빌드에서 깨진다 — 실행해 보고 알게 되는 일이 없다."
  echo " */"
  cat "$OUT_TS"
} > "$TMP"
mv "$TMP" "$OUT_TS"

echo "✅ $OUT_TS"
echo "   $(grep -c '' "$OUT_TS") 줄 · 명세 $SNAPSHOT"
