#!/usr/bin/env bash
#
# 랜딩 프로토타입(haeun/landing/web)을 /story 가 띄울 수 있는 자리로 옮긴다.
#
# 왜 복사인가 — Next 는 apps/web/public 밖의 파일을 서빙하지 않는다.
# 프로토타입 원본은 haeun/landing/web 에 그대로 두고(파이썬 서버가 그걸 쓴다),
# 여기서 public 으로 떠 온다. 손으로 복사하면 둘이 갈라지므로 스크립트로 둔다.
#
#   bash story/fe/sync-landing.sh
#
# 원본의 에셋 경로가 전부 /static/... 절대경로라, public/static 에 그대로
# 얹으면 경로를 한 줄도 안 고쳐도 맞는다. 딱 한 가지만 더 한다 —
# 데모 모드 셰임(demo-api.js)을 app.js 보다 먼저 실리게 끼워 넣는 일.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/haeun/landing/web"
DST="$ROOT/apps/web/public/static"

[ -d "$SRC" ] || { echo "원본이 없습니다: $SRC" >&2; exit 1; }

# demo-api* 는 이쪽에만 있는 것이라 --delete 에 쓸려 나가면 안 된다.
rsync -a --delete \
  --exclude='*.bak' \
  --exclude='.DS_Store' \
  --exclude='demo-api.js' \
  --exclude='demo-api/' \
  "$SRC/" "$DST/"

# 셰임을 첫 스크립트(lou-art.js) 앞에 끼운다. app.js·editor.js 가 뜨기 전에
# window.fetch 가 바뀌어 있어야 한다.
TAG='<script src="/static/demo-api.js"></script>'
for f in index.html demo.html editor.html; do
  p="$DST/$f"
  [ -f "$p" ] || continue
  grep -q 'demo-api.js' "$p" && continue
  # BSD/GNU sed 양쪽에서 도는 형태로 쓴다 (맥에서 개발, 리눅스에서 빌드).
  awk -v tag="$TAG" '
    !done && /<script src="\/static\/lou-art\.js"><\/script>/ { print tag; done=1 }
    { print }
  ' "$p" > "$p.tmp" && mv "$p.tmp" "$p"
done

echo "동기화 완료: $DST"
du -sh "$DST"
