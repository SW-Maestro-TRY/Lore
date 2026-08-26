#!/usr/bin/env bash
#
# 랜딩 프로토타입(haeun/landing/web)을 /webtoon 이 띄울 수 있는 자리로 옮긴다.
#
# 왜 복사인가 — Next 는 apps/web/public 밖의 파일을 서빙하지 않는다.
# 프로토타입 원본은 haeun/landing/web 에 그대로 두고(파이썬 서버가 그걸 쓴다),
# 여기서 public 으로 떠 온다. 손으로 복사하면 둘이 갈라지므로 스크립트로 둔다.
#
#   bash webtoon/fe/sync-landing.sh
#
# 원본의 에셋 경로가 전부 /static/... 절대경로라, public/static 에 그대로
# 얹으면 경로를 한 줄도 안 고쳐도 맞는다. 복사본에만 두 가지를 더 심는다.
#
#   1) data-lore-base="/webtoon"  — 이 화면이 어느 주소 아래에 얹혔는지.
#      web/base.js 가 이걸 읽어서 화면 안의 주소를 전부 그 아래로 옮긴다.
#      원본에는 안 심는다 — serve.py 는 뿌리에 얹으므로 표시가 없어야 맞다.
#   2) demo-api.js  — 서버가 있어야 도는 호출을 막는 데모 모드 셰임.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/haeun/landing/web"
DST="$ROOT/apps/web/public/static"
BASE="/webtoon"

[ -d "$SRC" ] || { echo "원본이 없습니다: $SRC" >&2; exit 1; }

# demo-api* 는 이쪽에만 있는 것이라 --delete 에 쓸려 나가면 안 된다.
rsync -a --delete \
  --exclude='*.bak' \
  --exclude='.DS_Store' \
  --exclude='demo-api.js' \
  --exclude='demo-api/' \
  "$SRC/" "$DST/"

for f in index.html demo.html editor.html; do
  p="$DST/$f"
  [ -f "$p" ] || continue

  # base 를 <html> 에 심는다. base.js 가 여기서 읽는다.
  # BSD/GNU sed 양쪽에서 도는 형태로 쓴다 (맥에서 개발, 리눅스에서 빌드).
  awk -v base="$BASE" '
    !done && /^<html / { sub(/^<html /, "<html data-lore-base=\"" base "\" "); done=1 }
    { print }
  ' "$p" > "$p.tmp" && mv "$p.tmp" "$p"

  # 데모 모드 셰임을 base.js 바로 뒤에 끼운다 — app.js 가 뜨기 전에
  # window.fetch 가 바뀌어 있어야 한다.
  grep -q 'demo-api.js' "$p" && continue
  awk '
    !done && /<script src="\/static\/base\.js"><\/script>/ { print; print "<script src=\"/static/demo-api.js\"></script>"; done=1; next }
    { print }
  ' "$p" > "$p.tmp" && mv "$p.tmp" "$p"
done

echo "동기화 완료: $DST  (base=$BASE)"
du -sh "$DST"
