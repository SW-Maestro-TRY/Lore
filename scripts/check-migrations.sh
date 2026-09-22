#!/usr/bin/env bash
# 마이그레이션 번호 검사 — 같은 번호 두 개가 머지되는 것을 PR 에서 막는다.
#
# ★ 왜 필요한가 — 2026-09-22 dev 가 이 사고로 멈췄다. trailer 가 `V23__foreshadowings.sql` 을,
#   zzal 이 `V23__zzal_motion_wish.sql` 을 각자 붙였는데 둘 다 classpath `db/migration` 한 곳으로
#   합쳐진다. Flyway 가 기동 때 "Found more than one migration with version 23" 으로 멈추고
#   API 가 안 뜬다. 폴더가 셋이라 사람 눈에는 안 겹쳐 보이는 것이 함정이다.
#
# ★ 무엇을 보는가 (규칙은 common/docs/git-convention.md 9절)
#   (a) 번호 중복      — `V` 와 `__` 사이 문자열이 겹치면 빨간불. 이것이 진짜 사고를 막는 검사다.
#   (b) 이름 형식      — `V<날짜8>_<시각4~6>__<설명>.sql` 또는 기존 순번 `V<숫자>__<설명>.sql`.
#                        둘 다 아니면 빨간불(설명은 소문자·숫자·밑줄만).
#   (c) 새 파일의 순번 — 이번 PR 이 새로 더한 파일이 순번 형식이면 경고만 한다. 빨간불은 아니다.
#                        기존 V1~V23 은 재번호 금지라 그대로 둬야 하므로 (b) 로는 못 가른다.
#
# ★ 왜 (c) 가 경고인가 — 순번도 겹치지만 않으면 당장은 돈다. 여기서 막으면 급한 수정이 멈춘다.
#   막는 것은 (a)·(b) 뿐이고, (c) 는 "다음엔 날짜 번호로" 라고 알려 주는 자리다.
#
# 쓰는 법
#   ./scripts/check-migrations.sh                    # 중복·형식만 (a·b)
#   ./scripts/check-migrations.sh --base origin/develop   # 새 파일 경고(c)까지
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 마이그레이션이 사는 폴더 셋. 전부 classpath `db/migration` 한 곳으로 합쳐진다 —
# 그래서 폴더가 달라도 번호는 하나의 이름공간이다.
DIRS=(
  "apps/api/src/main/resources/db/migration"
  "trailer/be/src/main/resources/db/migration"
  "webtoon/be/src/main/resources/db/migration"
)

BASE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "모르는 인자: $1" >&2; exit 2 ;;
  esac
done

# 날짜 번호 `V20260922_1901__trailer_foreshadowings.sql` 또는 기존 순번 `V23__zzal_motion_wish.sql`.
NAME_RE='^V([0-9]+|[0-9]{8}_[0-9]{4,6})__[a-z0-9_]+\.sql$'
SEQ_RE='^V[0-9]+__'

fail_count=0
warn_count=0

# GitHub Actions 안에서는 파일에 붙는 주석으로도 띄운다(로그를 안 뒤져도 보이게).
annotate() {  # annotate <level> <path> <message>
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$2::$3"
  fi
}

# ── 파일 모으기 ──────────────────────────────────────────────
# "버전<TAB>경로" 한 줄씩. 버전은 맨 앞 `V` 를 뗀 뒤 첫 `__` 앞까지다.
list_file="$(mktemp)"
trap 'rm -f "$list_file"' EXIT

total=0
for d in "${DIRS[@]}"; do
  [ -d "$ROOT/$d" ] || continue
  for f in "$ROOT/$d"/V*.sql; do
    [ -e "$f" ] || continue          # 매치가 없으면 glob 이 그대로 남는다
    base="$(basename "$f")"
    rel="$d/$base"
    total=$((total + 1))

    # (b) 이름 형식
    if [[ ! "$base" =~ $NAME_RE ]]; then
      echo "$rel: 이름 형식이 규칙과 다릅니다 — V<날짜8>_<시각4~6>__<소문자설명>.sql (git-convention.md 9절)"
      annotate error "$rel" "마이그레이션 이름 형식이 규칙과 다릅니다"
      fail_count=$((fail_count + 1))
      continue                       # 형식이 깨진 파일은 번호를 못 믿으니 중복 검사에서 뺀다
    fi

    ver="${base#V}"
    ver="${ver%%__*}"
    printf '%s\t%s\n' "$ver" "$rel" >> "$list_file"
  done
done

# ── (a) 번호 중복 ────────────────────────────────────────────
dups="$(cut -f1 "$list_file" | sort | uniq -d)"
if [ -n "$dups" ]; then
  while IFS= read -r ver; do
    [ -n "$ver" ] || continue
    while IFS= read -r rel; do
      echo "$rel: 번호 V$ver 이(가) 다른 파일과 겹칩니다 — 세 폴더는 classpath 한 곳으로 합쳐집니다"
      annotate error "$rel" "마이그레이션 번호 V$ver 중복 — Flyway 가 기동 때 멈춥니다"
      fail_count=$((fail_count + 1))
    done < <(awk -F'\t' -v v="$ver" '$1 == v { print $2 }' "$list_file")
  done <<< "$dups"
fi

# ── (c) 이번 PR 이 새로 더한 파일이 순번이면 경고 ─────────────
if [ -n "$BASE" ]; then
  if git -C "$ROOT" rev-parse --verify --quiet "$BASE" >/dev/null; then
    mb="$(git -C "$ROOT" merge-base "$BASE" HEAD 2>/dev/null || echo "$BASE")"
    added="$(git -C "$ROOT" diff --name-only --diff-filter=AR "$mb" HEAD -- \
              "${DIRS[@]/%//}" 2>/dev/null || true)"
    while IFS= read -r rel; do
      [ -n "$rel" ] || continue
      base="$(basename "$rel")"
      if [[ "$base" =~ $SEQ_RE ]]; then
        echo "$rel: 새 파일인데 순번 번호입니다 — 날짜 번호 V<YYYYMMDD>_<HHMM>__ 로 바꿔 주세요 (경고)"
        annotate warning "$rel" "새 마이그레이션은 날짜 번호로 붙여 주세요"
        warn_count=$((warn_count + 1))
      fi
    done <<< "$added"
  else
    echo "· 기준 브랜치 '$BASE' 를 못 찾아 새 파일 검사(c)는 건너뜁니다."
  fi
fi

# ── 결과 ─────────────────────────────────────────────────────
echo
if [ "$fail_count" -gt 0 ]; then
  echo "❌ 마이그레이션 $total 개 중 문제 $fail_count 건 (경고 $warn_count 건)"
  exit 1
fi
echo "✅ 마이그레이션 $total 개 — 번호 중복 없음, 이름 형식 정상 (경고 $warn_count 건)"
