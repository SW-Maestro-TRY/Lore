#!/usr/bin/env bash
# 마이그레이션 파일 만들기 — 이름을 손으로 짓지 않게 한다.
#
# ★ 왜 필요한가 — 규칙(git-convention.md 9절)은 `V<YYYYMMDD>_<HHMM>__<서비스>_<설명>.sql` 인데,
#   날짜·시각을 사람이 적으면 오타가 나고 폴더도 헷갈린다. 2026-09-22 dev 가 멈춘 번호 중복도
#   결국 사람이 번호를 골랐기 때문이다. 이 스크립트가 시각을 읽고 폴더를 찾아 빈 파일을 만든다.
#
# ★ 시각은 KST 기준이다 — 팀이 한국 시각으로 이야기하므로 파일 이름도 같은 시각이어야
#   "아까 그 파일"을 찾을 수 있다. 서버가 UTC 여도 여기서는 Asia/Seoul 로 읽는다.
#
# 쓰는 법
#   ./scripts/new-migration.sh zzal     motion_wish
#   ./scripts/new-migration.sh trailer  foreshadowings
#   ./scripts/new-migration.sh webtoon  notify_setting
#
#   서비스 → 폴더
#     zzal    → apps/api/src/main/resources/db/migration
#     webtoon → webtoon/be/src/main/resources/db/migration
#     trailer → trailer/be/src/main/resources/db/migration
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
}

if [ $# -eq 1 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
  usage; exit 0
fi

if [ $# -ne 2 ]; then
  echo "인자가 둘이어야 합니다 — 서비스와 설명." >&2
  echo >&2
  usage >&2
  exit 2
fi

SERVICE="$1"
DESC="$2"

# ── 서비스 → 폴더 ────────────────────────────────────────────
case "$SERVICE" in
  zzal)    DIR="apps/api/src/main/resources/db/migration" ;;
  webtoon) DIR="webtoon/be/src/main/resources/db/migration" ;;
  trailer) DIR="trailer/be/src/main/resources/db/migration" ;;
  *)
    echo "모르는 서비스: '$SERVICE' — zzal · webtoon · trailer 중 하나여야 합니다." >&2
    exit 2 ;;
esac

# ── 설명 검사 ────────────────────────────────────────────────
# 검사 스크립트(check-migrations.sh)가 소문자·숫자·밑줄만 받는다. 여기서 먼저 걸러
# "만들고 나서 PR 에서 빨간불" 을 없앤다.
if [ -z "$DESC" ]; then
  echo "설명이 비었습니다 — 무엇을 바꾸는 마이그레이션인지 snake_case 로 적어 주세요 (예: motion_wish)." >&2
  exit 2
fi
if ! [[ "$DESC" =~ ^[a-z0-9_]+$ ]]; then
  echo "설명은 소문자·숫자·밑줄만 씁니다: '$DESC' (예: motion_wish)" >&2
  exit 2
fi

# 서비스 접두를 이미 적었으면 두 번 붙이지 않는다 (trailer + trailer_foreshadowings).
SLUG="$DESC"
if [ "${DESC#"${SERVICE}"_}" = "$DESC" ]; then
  SLUG="${SERVICE}_${DESC}"
fi

# ── 이름 짓기 ────────────────────────────────────────────────
# ★ 번호가 쓰였는지는 **세 폴더를 모두** 봐야 한다. 내 폴더만 보면 같은 분에 다른 서비스가
#   만든 파일과 겹치는데, 그게 바로 이 규칙이 막으려던 사고다(폴더는 셋이지만 실행될 때는
#   classpath db/migration 한 곳이다).
ALL_DIRS=(
  "apps/api/src/main/resources/db/migration"
  "webtoon/be/src/main/resources/db/migration"
  "trailer/be/src/main/resources/db/migration"
)

version_taken() {  # version_taken <번호>
  local d f
  for d in "${ALL_DIRS[@]}"; do
    [ -d "$ROOT/$d" ] || continue
    for f in "${ROOT}/${d}/V${1}__"*.sql; do
      [ -e "$f" ] && return 0
    done
  done
  return 1
}

# 같은 분에 두 개가 나면 초까지 붙여 피한다(9절: "뒤에 만든 쪽이 시각을 올려서 회피").
STAMP="$(TZ=Asia/Seoul date +%Y%m%d_%H%M)"
if version_taken "$STAMP"; then
  tries=0
  while STAMP="$(TZ=Asia/Seoul date +%Y%m%d_%H%M%S)"; version_taken "$STAMP"; do
    tries=$((tries + 1))
    if [ "$tries" -ge 5 ]; then
      echo "번호 $STAMP 이(가) 계속 쓰여 있습니다 — 잠시 뒤 다시 시도해 주세요." >&2
      exit 1
    fi
    sleep 1
  done
fi
NAME="V${STAMP}__${SLUG}.sql"
if [ -e "$ROOT/$DIR/$NAME" ]; then
  echo "이미 있는 파일입니다: $DIR/$NAME" >&2
  exit 1
fi

# ── 만들기 ───────────────────────────────────────────────────
AUTHOR="$(git -C "$ROOT" config user.name 2>/dev/null || true)"
[ -n "$AUTHOR" ] || AUTHOR="(작성자)"

mkdir -p "$ROOT/$DIR"
cat > "$ROOT/$DIR/$NAME" <<EOF
-- $AUTHOR · $(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M') KST
-- 목적:

EOF

echo "$DIR/$NAME"
