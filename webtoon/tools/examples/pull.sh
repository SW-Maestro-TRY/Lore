#!/usr/bin/env bash
# usage: pull.sh <env> <run_id> [저장할.zip] — 그 환경의 작품 하나를 번들(zip)로 내려받는다(다른 환경에 올리려고).
. "$(dirname "$0")/env.sh" "$1"; RUN=${2:?run_id}; OUT=${3:-$HOME/lore-example-bundles/$RUN.zip}; login
code=$(curl -s -b "$JAR" -o "$OUT" -w "%{http_code}" "$API/$RUN/bundle")
[ "$code" = 200 ] && { chmod 600 "$OUT"; echo "받음 $OUT ($(du -h "$OUT" | cut -f1))"; } || { echo "실패 $code"; rm -f "$OUT"; }
