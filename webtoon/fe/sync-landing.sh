#!/usr/bin/env bash
#
# 랜딩 프로토타입(haeun/landing/web)을 /webtoon 이 띄울 수 있는 자리로 옮긴다.
#
# **손으로 돌릴 일이 거의 없다.** apps/web/package.json 의 prebuild·predev 가
# 빌드와 개발 서버 시작 전에 이걸 부른다. 그래서 원본만 고치고 커밋하면
# 배포까지 따라온다. (예전엔 사람이 기억해야 했고, 실제로 놓친 적이 있다 —
# 크레딧 상품 개편이 원본만 고쳐져서 배포된 화면이 한동안 개편 전이었다.)
#
#   bash webtoon/fe/sync-landing.sh      # 직접 돌리고 싶을 때
#
# 왜 복사인가 — Next 는 apps/web/public 밖의 파일을 서빙하지 않는다.
# 프로토타입 원본은 haeun/landing/web 에 그대로 두고(파이썬 서버가 그걸 쓴다),
# 여기서 public 으로 떠 온다. 복사가 불가피한 것은 Next 의 제약이라 그대로 두고,
# **복사하는 시점**만 사람에게서 빌드로 옮겼다.
#
# 만들어지는 자리(apps/web/public/static)는 .gitignore 에 있다 — 저장소에는
# 원본 한 벌만 둔다.
#
# 원본의 에셋 경로가 전부 /static/... 절대경로라, public/static 에 그대로
# 얹으면 경로를 한 줄도 안 고쳐도 맞는다. 복사본에만 두 가지를 더 심는다.
#
#   1) data-lore-base="/webtoon"  — 이 화면이 어느 주소 아래에 얹혔는지.
#      web/base.js 가 이걸 읽어서 화면 안의 주소를 전부 그 아래로 옮긴다.
#      원본에는 안 심는다 — serve.py 는 뿌리에 얹으므로 표시가 없어야 맞다.
#   2) demo-api/  — 서버가 있어야 도는 호출을 막는 데모 모드 셰임.
#      원본에 없는 것이라 이 폴더(webtoon/fe/demo-api)에서 가져온다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/haeun/landing/web"
DST="$ROOT/apps/web/public/static"
BASE="/webtoon"

[ -d "$SRC" ] || { echo "원본이 없습니다: $SRC" >&2; exit 1; }

mkdir -p "$DST"
rsync -a --delete --exclude='*.bak' --exclude='.DS_Store' "$SRC/" "$DST/"

# 데모 모드 셰임. --delete 뒤에 넣는다 — 원본에 없는 파일이라 앞에 두면 지워진다.
mkdir -p "$DST/demo-api"
cp "$HERE/demo-api/demo-api.js" "$DST/demo-api.js"
for f in "$HERE"/demo-api/*.json; do cp "$f" "$DST/demo-api/"; done

for f in index.html demo.html editor.html; do
  p="$DST/$f"
  [ -f "$p" ] || continue

  # base 를 <html> 에 심는다. base.js 가 여기서 읽는다.
  # BSD/GNU sed 양쪽에서 도는 형태로 쓴다 (맥에서 개발, 리눅스에서 빌드).
  awk -v base="$BASE" '
    !done && /^<html / { sub(/^<html /, "<html data-lore-base=\"" base "\" "); done=1 }
    { print }
  ' "$p" > "$p.tmp" && mv "$p.tmp" "$p"

  # 셰임을 base.js 바로 뒤에 끼운다 — app.js 가 뜨기 전에 window.fetch 가
  # 바뀌어 있어야 한다.
  awk '
    !done && /<script src="\/static\/base\.js"><\/script>/ { print; print "<script src=\"/static/demo-api.js\"></script>"; done=1; next }
    { print }
  ' "$p" > "$p.tmp" && mv "$p.tmp" "$p"
done

echo "랜딩 동기화: $SRC -> $DST  (base=$BASE)"
