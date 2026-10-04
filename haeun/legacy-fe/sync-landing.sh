#!/usr/bin/env bash
#
# Webtoon 탭이 쓰는 **그림 파일**을 apps/web/public/static 으로 모은다.
#
# **손으로 돌릴 일이 거의 없다.** apps/web/package.json 의 prebuild·predev 가
# 빌드와 개발 서버 시작 전에 이걸 부른다. 그래서 원본만 고치고 커밋하면
# 배포까지 따라온다. (예전엔 사람이 기억해야 했고, 실제로 놓친 적이 있다 —
# 크레딧 상품 개편이 원본만 고쳐져서 배포된 화면이 한동안 개편 전이었다.)
#
#   bash webtoon/fe/sync-landing.sh      # 직접 돌리고 싶을 때
#
# ## 왜 복사인가
#
# Next 는 apps/web/public 밖의 파일을 서빙하지 않는다. 그런데 원본을 public 에
# 둘 수도 없다 — 마스코트와 견본 그림은 **파이썬 파이프라인과 자바 서버도 읽는
# 것**이라(EpisodeExport 의 워터마크, BuiltinCharacters 의 캐릭터 탭) 도메인
# 폴더에 있어야 맞다. 그래서 원본은 한 벌만 두고 빌드가 떠 온다. 만들어지는
# 자리(apps/web/public/static)는 .gitignore 에 있다.
#
# ## 예전과 달라진 것 (2026-09-12)
#
# 예전 원본은 `haeun/landing/web` 한 곳이었다 — 프로토타입 웹서버가 띄우던
# HTML·CSS·JS 한 벌. 그 화면은 React(webtoon/fe)로 옮겨졌고 프로토타입은
# 지웠으므로, 이제 떠 올 것은 **그림뿐이고 원본도 여러 곳**이다. HTML 을
# 손보던 부분(data-lore-base 심기, 데모 셰임 끼우기)도 같이 없앴다 — 고칠
# HTML 이 없고, 데모용 값은 React 가 demo-api/*.json 을 직접 import 한다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DST="$ROOT/apps/web/public/static"

# 떠 올 것: <원본>:<public/static 안에서의 이름>
#   lou     마스코트. 파이썬·자바도 읽으므로 webtoon/ai/assets 가 원본이다.
#   samples 그림체 예시. 기본 캐릭터로도 쓰여서 같은 자리에 있다.
#   badges  후원 기관 배지. 화면에만 쓰이므로 webtoon/fe 가 원본이다.
#   gallery 둘러보기 예시 작품. 위와 같다.
SRCS=(
  "$ROOT/webtoon/ai/assets/lou:lou"
  "$ROOT/webtoon/ai/assets/samples:samples"
  "$HERE/static/badges:badges"
  "$HERE/static/gallery:gallery"
)

for pair in "${SRCS[@]}"; do
  src="${pair%%:*}"
  name="${pair##*:}"
  [ -d "$src" ] || { echo "원본이 없습니다: $src" >&2; exit 1; }
  mkdir -p "$DST/$name"
  # --delete 는 이 폴더 안에서만 돈다 — 위 목록에 없는 형제 폴더는 안 건드린다.
  rsync -a --delete --exclude='*.bak' --exclude='.DS_Store' "$src/" "$DST/$name/"
  echo "  $name  <- $src"
done

echo "랜딩 그림 동기화 완료: $DST"
