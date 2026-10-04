#!/usr/bin/env bash
#
# 랜딩페이지(/)가 쓰는 **그림 파일**을 apps/web/public/static/landing 으로 모은다.
#
# **손으로 돌릴 일이 거의 없다.** apps/web/package.json 의 prebuild·predev 가
# (webtoon/fe/sync-landing.sh 와 함께) 빌드와 개발 서버 시작 전에 이걸 부른다.
# 그래서 원본만 고치고 커밋하면 배포까지 따라온다.
#
#   bash common/fe/sync-landing-assets.sh      # 직접 돌리고 싶을 때
#
# ## 왜 복사인가
#
# Next 는 apps/web/public 밖의 파일을 서빙하지 않는데, apps/web/public/static
# 전체가 .gitignore 에 있다(webtoon 쪽 자산과 같은 자리를 쓰다 보니 통째로
# 빠졌다). 그래서 원본은 git 이 보는 common/fe/assets/landing 에 한 벌만 두고,
# 빌드 전에 이 스크립트가 public 으로 떠 온다.
#
# 그림 자체을 바꾸는 법(어느 파일이 화면 어디에 쓰이는지)은
# common/fe/landing/README.md 에 있다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/common/fe/assets/landing"
DST="$ROOT/apps/web/public/static/landing"

[ -d "$SRC" ] || { echo "원본이 없습니다: $SRC" >&2; exit 1; }

mkdir -p "$DST"
# --delete 는 이 폴더 안에서만 돈다 — public/static 의 다른 형제 폴더
# (lou·samples·badges·gallery, webtoon/fe/sync-landing.sh 소관)는 안 건드린다.
rsync -a --delete --exclude='.DS_Store' "$SRC/" "$DST/"

echo "랜딩 그림 동기화 완료: $DST"
