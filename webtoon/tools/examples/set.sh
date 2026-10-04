#!/usr/bin/env bash
# usage: set.sh <env> <run_id> example|public|private|order <n>|down
#   example   예시로 지정(공개도 같이 켜짐) — 같은 환경에서 만든 작품을 예시로 만들 때
#   public / private   공개 · 비공개
#   order N   둘러보기 순서(작을수록 앞). 순서를 안 준 것은 그 뒤
#   down      내리기 = 예시 해제 + 비공개(작품은 안 지움)
. "$(dirname "$0")/env.sh" "$1"; RUN=${2:?run_id}; ACT=${3:?동작}; login
case $ACT in
  example) body='{"example":true,"public":true}';; public) body='{"public":true}';; private) body='{"public":false}';;
  order) body="{\"example\":true,\"order\":${4:?순서 번호}}";;
  down) curl -s -b "$JAR" -XDELETE "$API/$RUN" | data; exit;;
  *) echo "모르는 동작: $ACT"; exit 1;;
esac
curl -s -b "$JAR" -XPATCH "$API/$RUN" -H 'Content-Type: application/json' -d "$body" | data
