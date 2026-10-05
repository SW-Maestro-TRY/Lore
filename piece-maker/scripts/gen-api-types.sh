#!/usr/bin/env bash
# Piece Maker 전용 계약 대조용 타입을 만든다. 다른 서비스의 타입이나 공용 스냅샷은 변경하지 않는다.
# 입력은 PieceMakerOpenApiSnapshotIT가 실제 springdoc 시험 그룹에서 생성한 명세다.
# 이 생성·대조는 공용 전체 API 명세 검사나 그 명세의 기존 불일치 해결을 대신하지 않는다.
#
# 명세 갱신: PIECE_MAKER_OPENAPI_UPDATE=true ./gradlew test --tests '*PieceMakerOpenApiSnapshotIT*'
# 타입 생성: ./piece-maker/scripts/gen-api-types.sh
# 재현 확인: ./piece-maker/scripts/gen-api-types.sh --check
set -euo pipefail

PM_REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PM_SNAPSHOT="$PM_REPO_ROOT/piece-maker/be/src/test/resources/openapi.json"
PM_TYPES="$PM_REPO_ROOT/piece-maker/fe/lib/api-schema.ts"
PM_MODE="${1-}"
if [ "$#" -gt 1 ] || { [ -n "$PM_MODE" ] && [ "$PM_MODE" != "--check" ]; }; then
  echo "사용법: $0 [--check]" >&2
  exit 2
fi
if [ ! -f "$PM_SNAPSHOT" ]; then
  echo "Piece Maker 전용 명세가 없습니다. PieceMakerOpenApiSnapshotIT 갱신을 먼저 실행하세요." >&2
  exit 1
fi

PM_TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$PM_TEMP_DIR"' EXIT
npx --yes openapi-typescript@7.13.0 "$PM_SNAPSHOT" -o "$PM_TEMP_DIR/generated.ts" >/dev/null
{
  cat <<'HEADER'
/* eslint-disable */
/**
 * 자동 생성 — Piece Maker 전용 API 계약 대조용 타입. 손으로 편집하지 않는다.
 * 생성: ./piece-maker/scripts/gen-api-types.sh
 * 원본: piece-maker/be/src/test/resources/openapi.json
 * 원본은 시험 전용 springdoc 그룹이 실제로 생성한다. 공용 전체 API 스냅샷과는 범위가 다르다.
 */
HEADER
  cat "$PM_TEMP_DIR/generated.ts"
} > "$PM_TEMP_DIR/api-schema.ts"

if [ "$PM_MODE" = "--check" ]; then
  if ! cmp -s "$PM_TEMP_DIR/api-schema.ts" "$PM_TYPES"; then
    echo "Piece Maker 전용 타입이 명세와 다릅니다. 인자 없이 다시 생성하세요." >&2
    exit 1
  fi
  echo "Piece Maker 전용 타입 재현 확인 완료"
else
  mv "$PM_TEMP_DIR/api-schema.ts" "$PM_TYPES"
  echo "Piece Maker 전용 타입 생성 완료: $PM_TYPES"
fi
