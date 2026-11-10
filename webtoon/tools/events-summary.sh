#!/usr/bin/env bash
# usage: events-summary.sh <dev|staging|prod> [--since <YYYY-MM-DDTHH:MM±hh>]
#                                             [--days <N>] [--exclude-uid <uid>]
#                                             [--exclude-admins]
#
# 그 환경의 webtoon_event · webtoon_feedback 을 긁어 발표 7번 장이 묻는 숫자를
# 한 번에 보여 준다. 관리자 홈(UI)이 보여 주는 것과 같은 집계를 CLI 로 꺼낸다 —
# UI 가 안 떠 있을 때 · 쿼리 하나 바꿔 돌리고 싶을 때 쓴다. db-sql.sh 가 쓰는
# SSM 길을 그대로 쓴다(비밀번호는 서버 안에서만 읽음).
#
# 기간
#   기본        최근 7일
#   --days N    최근 N 일
#   --since     그 시각부터 지금까지(ISO 8601, TZ 포함). --days 와 같이 못 쓴다.
#
# 내부 트래픽 제외
#   --exclude-uid <uid>   그 uid 하나만 뺀다. 브라우저 콘솔에 localStorage.lore_uid 로 뽑음.
#   --exclude-admins      role=ADMIN 계정으로 로그인한 적 있는 모든 uid 를 뺀다.
#                         (서버에 안 들어가도 쓸 수 있게 — 이미 로그인했던 브라우저의 uid 는 DB 안에 있다.)
#                         로그인 안 하고 쓴 시크릿 창 세션까지는 못 뺀다.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
LORE_ENV=""
SINCE=""
DAYS=7
EXCLUDE_UID=""
EXCLUDE_ADMINS=0
while [ $# -gt 0 ]; do
  case $1 in
    --since) SINCE=$2; shift 2 ;;
    --days)  DAYS=$2;  shift 2 ;;
    --exclude-uid) EXCLUDE_UID=$2; shift 2 ;;
    --exclude-admins) EXCLUDE_ADMINS=1; shift ;;
    -h|--help)
      sed -n '2,22p' "$0" | sed 's/^# //'
      exit 0 ;;
    *)
      if [ -z "$LORE_ENV" ]; then LORE_ENV=$1; shift; else echo "모르는 인자: $1" >&2; exit 1; fi ;;
  esac
done
[ -n "$LORE_ENV" ] || { echo "환경 이름이 없습니다 (dev|staging|prod)" >&2; exit 1; }

if [ -n "$SINCE" ]; then
  CUT_SQL="TIMESTAMP WITH TIME ZONE '$SINCE'"
  CUT_LABEL="$SINCE"
else
  CUT_SQL="now() - interval '$DAYS days'"
  CUT_LABEL="최근 ${DAYS}일"
fi

EXCL=""
if [ -n "$EXCLUDE_UID" ]; then
  # 작은따옴표를 두 번으로 바꿔 SQL 안전하게 끼운다
  SAFE=${EXCLUDE_UID//\'/\'\'}
  EXCL=" AND uid <> '$SAFE'"
fi
if [ "$EXCLUDE_ADMINS" = 1 ]; then
  # 관리자 계정으로 로그인한 적 있는 모든 uid 를 뺀다 — 로그인하지 않은 세션은 못 뺀다.
  # ADMIN 역할 user_id 를 users 에서 뽑고(공용 사용자 표), 그 user_id 가 붙은 모든 uid 를 webtoon_event 에서 찾는다.
  EXCL+=" AND (uid IS NULL OR uid NOT IN (SELECT DISTINCT uid FROM webtoon_event WHERE user_id IN (SELECT id FROM users WHERE role='ADMIN') AND uid IS NOT NULL))"
fi

SQL=$(cat <<SQLEND
\\echo
\\echo === 요약 ($CUT_LABEL) ===
SELECT COUNT(*) total, COUNT(DISTINCT uid) uids, MIN(occurred_at) first, MAX(occurred_at) last FROM webtoon_event WHERE occurred_at >= $CUT_SQL$EXCL;

\\echo
\\echo === 가설 체크보드 — unique uid ===
WITH r AS (SELECT * FROM webtoon_event WHERE occurred_at >= $CUT_SQL$EXCL)
SELECT 'landing(session_start)' k, COUNT(DISTINCT uid) FROM r WHERE name='session_start'
UNION ALL SELECT 'auth_done',           COUNT(DISTINCT uid) FROM r WHERE name='auth_done'
UNION ALL SELECT 'create_started',      COUNT(DISTINCT uid) FROM r WHERE name='create_started'
UNION ALL SELECT 'bake(완성)',          COUNT(DISTINCT uid) FROM r WHERE name='bake'
UNION ALL SELECT 'read_end',            COUNT(DISTINCT uid) FROM r WHERE name='read_end'
UNION ALL SELECT 'next_episode_click',  COUNT(DISTINCT uid) FROM r WHERE name='next_episode_click'
UNION ALL SELECT 'story_retry',         COUNT(DISTINCT uid) FROM r WHERE name='story_retry'
UNION ALL SELECT 'sheet_fix+restore',   COUNT(DISTINCT uid) FROM r WHERE name IN ('sheet_fix','sheet_restore')
UNION ALL SELECT 'scene_retry+restore', COUNT(DISTINCT uid) FROM r WHERE name IN ('scene_retry','scene_restore')
UNION ALL SELECT 'regen_start(장)',     COUNT(DISTINCT uid) FROM r WHERE name='regen_start'
UNION ALL SELECT 'feedback_submit',     COUNT(DISTINCT uid) FROM r WHERE name='feedback_submit'
UNION ALL SELECT 'create_failed',       COUNT(DISTINCT uid) FROM r WHERE name='create_failed'
UNION ALL SELECT 'create_blocked',      COUNT(DISTINCT uid) FROM r WHERE name='create_blocked';

\\echo
\\echo === UTM source 상위 10 ===
SELECT COALESCE(SUBSTRING(source FROM 'utm_source=([^&]+)'), source) k, COUNT(DISTINCT uid) uids
FROM webtoon_event
WHERE occurred_at >= $CUT_SQL AND source IS NOT NULL AND source <> '' AND uid IS NOT NULL$EXCL
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;

\\echo
\\echo === UTM medium 상위 10 ===
SELECT SUBSTRING(source FROM 'utm_medium=([^&]+)') k, COUNT(DISTINCT uid) uids
FROM webtoon_event
WHERE occurred_at >= $CUT_SQL AND source LIKE '%utm_medium=%' AND uid IS NOT NULL$EXCL
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;

\\echo
\\echo === UTM 없는 외부 유입 — 레퍼러 호스트 상위 10 ===
SELECT ref_host, COUNT(DISTINCT uid) uids
FROM webtoon_event
WHERE occurred_at >= $CUT_SQL AND (source IS NULL OR source = '') AND ref_host IS NOT NULL AND ref_host <> '' AND uid IS NOT NULL$EXCL
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;

\\echo
\\echo === 모든 이벤트 상위 30 ===
SELECT name, COUNT(DISTINCT uid) uids, COUNT(*) total
FROM webtoon_event
WHERE occurred_at >= $CUT_SQL$EXCL
GROUP BY 1 ORDER BY uids DESC, total DESC LIMIT 30;

\\echo
\\echo === 설문 답 요약 — 기간 안 ===
SELECT kind, COUNT(*) FROM webtoon_feedback WHERE created_at >= $CUT_SQL GROUP BY kind;

\\echo
\\echo === 설문 긍정 비율 — 기간 안, SCALE 는 4-5, YES_PARTLY_NO 는 YES ===
WITH f AS (SELECT answers FROM webtoon_feedback WHERE created_at >= $CUT_SQL)
SELECT 'S0 전체만족' k,
       COUNT(*) FILTER (WHERE substring(answers from '"S0"\\s*:\\s*(\\d)')::int >= 4) positive,
       COUNT(*) FILTER (WHERE answers LIKE '%"S0"%') total FROM f
UNION ALL SELECT 'S1 내 캐릭터 얘기 맞나',
       COUNT(*) FILTER (WHERE substring(answers from '"S1"\\s*:\\s*(\\d)')::int >= 4),
       COUNT(*) FILTER (WHERE answers LIKE '%"S1"%') FROM f
UNION ALL SELECT 'S2 캐릭터 일관성',
       COUNT(*) FILTER (WHERE substring(answers from '"S2"\\s*:\\s*(\\d)')::int >= 4),
       COUNT(*) FILTER (WHERE answers LIKE '%"S2"%') FROM f
UNION ALL SELECT 'S3 설정 반영 YES',
       COUNT(*) FILTER (WHERE answers LIKE '%"S3":"yes"%'),
       COUNT(*) FILTER (WHERE answers LIKE '%"S3"%') FROM f
UNION ALL SELECT 'S4 성격',
       COUNT(*) FILTER (WHERE substring(answers from '"S4"\\s*:\\s*(\\d)')::int >= 4),
       COUNT(*) FILTER (WHERE answers LIKE '%"S4"%') FROM f
UNION ALL SELECT 'S5 줄거리 반영 YES',
       COUNT(*) FILTER (WHERE answers LIKE '%"S5":"yes"%'),
       COUNT(*) FILTER (WHERE answers LIKE '%"S5"%') FROM f
UNION ALL SELECT 'S6 1화 재미',
       COUNT(*) FILTER (WHERE substring(answers from '"S6"\\s*:\\s*(\\d)')::int >= 4),
       COUNT(*) FILTER (WHERE answers LIKE '%"S6"%') FROM f
UNION ALL SELECT 'S7 다음 화 YES',
       COUNT(*) FILTER (WHERE answers LIKE '%"S7":"yes"%'),
       COUNT(*) FILTER (WHERE answers LIKE '%"S7"%') FROM f;
SQLEND
)

exec "$HERE/examples/db-sql.sh" "$LORE_ENV" "$SQL"
