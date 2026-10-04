#!/usr/bin/env bash
# usage: push.sh <dev|staging|prod> <dry|real> [번들.zip ...]   (zip 을 안 주면 ~/lore-example-bundles/*.zip 전부)
# 관리자 API 로 번들을 올린다 — S3 로 직접 올리고 키로 심는 길(운영 · staging 의 WAF 가 큰 본문을 막는다).
# dry 는 검사만. 결과: PLANTED(심음) · EXISTS(같은 작품 번호가 이미 있어 아무것도 안 함) · DRY_RUN(검사 통과)
. "$(dirname "$0")/env.sh" "$1"
MODE=${2:?dry|real}; shift 2; DRY=$([ "$MODE" = real ] && echo false || echo true)
[ $# -eq 0 ] && set -- "$HOME"/lore-example-bundles/*.zip
login
for z in "$@"; do
  up=$(curl -s -b "$JAR" -XPOST "$API/upload-url" -H 'Content-Type: application/json' -d '{}' | data)
  key=$(echo "$up" | python3 -c "import json,sys;print(json.load(sys.stdin)['key'])" 2>/dev/null)
  url=$(echo "$up" | python3 -c "import json,sys;print(json.load(sys.stdin)['url'])" 2>/dev/null)
  [ -n "$key" ] || { echo "$(basename "$z") 올릴 주소를 못 받음: $up"; continue; }
  pc=$(curl -s -o /dev/null -w "%{http_code}" -XPUT "$url" -H 'Content-Type: application/zip' --data-binary @"$z")
  [ "$pc" = 200 ] || { echo "$(basename "$z") S3 PUT $pc"; continue; }
  r=$(curl -s -b "$JAR" -XPOST "$API/import-key" -H 'Content-Type: application/json' -d "{\"key\":\"$key\",\"dryRun\":$DRY}" | data)
  echo "$(basename "$z") $(echo "$r" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('status'),d.get('pages'),'쪽',d.get('title') or d)" 2>/dev/null || echo "$r")"
done
