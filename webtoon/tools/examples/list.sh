#!/usr/bin/env bash
# usage: list.sh <dev|staging|prod> — 그 환경의 예시 작품(순서 · 공개 · 쪽 · 제목)
. "$(dirname "$0")/env.sh" "$1"; login
curl -s -b "$JAR" "$API" | data | python3 -c "
import json,sys
e=json.load(sys.stdin)['examples']
print(f'예시 {len(e)}편 · 공개 {sum(x[\"isPublic\"] for x in e)}편')
for x in e: print(f\"  {str(x.get('order') or '-'):>3}  {'공개' if x['isPublic'] else '비공개'}  {x['pages']}쪽  {x['runId']}  {x['title']}\")"
