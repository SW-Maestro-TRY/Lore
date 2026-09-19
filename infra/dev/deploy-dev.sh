#!/usr/bin/env bash
#
# dev EC2(도커 박스) 배포 — SSM 으로 박스 위에서 실행된다.
#
#   sudo bash /opt/lore-dev/deploy-dev.sh <bucket> <s3-key> <region> <git-sha> [health-timeout-sec]
#
# staging·prod 는 jar/번들을 systemd 서비스에 꽂는 방식(/opt/lore/deploy-api.sh)이지만
# dev 는 compose 스택 한 덩어리라 절차가 다르다 — "소스 tar 를 통째로 받아 다시 빌드".
# 그래서 dev 만 이 스크립트를 쓴다.
#
# 뼈대
#   1. 배포 버킷에서 소스 tar 내려받기(인스턴스 역할 자격으로, 키 안 심음)
#   2. 새 디렉터리에 풀고 → 다 풀린 뒤에야 /opt/lore-dev/src 를 한 번에 갈아끼움
#      (중간에 실패해도 돌아가던 소스가 반쪽으로 망가지지 않는다)
#   3. compose up -d --build
#   4. 헬스 통과까지 대기 → 실패하면 로그 찍고 exit 1
#   5. 성공해야 VERSION 기록 + 옛 src.* 정리(최근 2개만 보존)
#
# ★ /opt/lore-dev/.env 는 박스에만 있는 비밀이다. 이 스크립트는 읽기만 하고 절대 안 덮는다.
set -euo pipefail

BUCKET="${1:?사용법: deploy-dev.sh <bucket> <s3-key> <region> <git-sha> [health-timeout-sec]}"
KEY="${2:?s3 키가 필요합니다}"
REGION="${3:?리전이 필요합니다}"
SHA="${4:?git sha 가 필요합니다}"
HEALTH_TIMEOUT="${5:-300}"          # 앱 기동 대기 상한(초)

ROOT=/opt/lore-dev
ENV_FILE="$ROOT/.env"
HTPASSWD="$ROOT/htpasswd"
SRC_LINK="$ROOT/src"
NEW_DIR="$ROOT/src.$SHA"
TMP_DIR="$ROOT/.src.$SHA.tmp"
TARBALL="$ROOT/.src.$SHA.tar.gz"
KEEP_SRC=2                          # 되돌릴 때 필요한 직전 판까지만 남긴다

log() { echo "[deploy-dev $(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"; }

cleanup() { rm -rf "$TMP_DIR" "$TARBALL"; }
trap cleanup EXIT

# ── 0. 준비물 확인 ───────────────────────────────────────────────
# 없는 걸 여기서 안 잡으면 한참 뒤 엉뚱한 자리에서 터진다.
command -v docker >/dev/null || { echo "docker 가 없습니다"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose(v2) 플러그인이 없습니다"; exit 1; }
command -v aws >/dev/null || { echo "aws CLI 가 없습니다 (인스턴스 역할로 S3 를 읽어야 합니다)"; exit 1; }
command -v curl >/dev/null || { echo "curl 이 없습니다"; exit 1; }

# .env 는 박스에만 있는 비밀 — 없으면 만들지 말고 멈춘다(빈 값으로 뜨면 조용히 망가진다).
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 이 없습니다. 박스에 먼저 만들어 두세요(배포가 만들지 않습니다)."; exit 1; }
# nginx 가 읽기전용으로 무는 파일. 없으면 도커가 '디렉터리'로 만들어 버려 nginx 가 기동에 실패한다.
[ -f "$HTPASSWD" ] || { echo "$HTPASSWD 이 없습니다(basic auth 자격). 박스에서 먼저 생성하세요."; exit 1; }

mkdir -p "$ROOT"

# ── 1. 소스 tar 내려받기 ─────────────────────────────────────────
log "소스 내려받기: s3://$BUCKET/$KEY"
rm -f "$TARBALL"
aws s3 cp "s3://$BUCKET/$KEY" "$TARBALL" --region "$REGION"

# ── 2. 새 디렉터리에 풀고 원자적으로 갈아끼우기 ──────────────────
log "압축 해제: $TMP_DIR"
rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR"
tar xzf "$TARBALL" -C "$TMP_DIR"

# 풀린 내용이 진짜 레포인지 확인 — 반쪽짜리 tar 를 src 로 올리면 다음 배포까지 못 돌아온다.
for f in docker-compose.yml docker-compose.dev-ec2.yml infra/dev/nginx.conf infra/dev/Dockerfile.api; do
  [ -f "$TMP_DIR/$f" ] || { echo "받은 소스에 $f 이 없습니다 — 배포 중단(기존 스택 그대로 둡니다)"; exit 1; }
done

rm -rf "$NEW_DIR"
mv "$TMP_DIR" "$NEW_DIR"

# src 가 (예전 손복사본처럼) 진짜 디렉터리면 심볼릭 링크로 바꾸기 전에 비켜 둔다.
if [ -e "$SRC_LINK" ] && [ ! -L "$SRC_LINK" ]; then
  LEGACY="$ROOT/src.legacy.$(date -u '+%Y%m%d%H%M%S')"
  log "기존 $SRC_LINK 가 실제 디렉터리 → $LEGACY 로 비켜 둠"
  mv "$SRC_LINK" "$LEGACY"
fi
# 링크를 새로 만든 뒤 mv -T 로 갈아끼운다(rm→ln 사이에 src 가 사라지는 순간이 없다).
ln -sfn "$NEW_DIR" "$ROOT/.src.next"
mv -T "$ROOT/.src.next" "$SRC_LINK"
log "src → $NEW_DIR"

# ── 3. 스택 올리기 ───────────────────────────────────────────────
# 경로는 심볼릭 링크(고정 경로)로 들어간다 — 컨테이너의 bind mount 가 src.<sha> 에 묶이지 않게.
cd "$SRC_LINK"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev-ec2.yml --env-file "$ENV_FILE")

log "compose up -d --build (빌드 때문에 몇 분 걸립니다)"
"${COMPOSE[@]}" up -d --build

# ── 4. 헬스 대기 ─────────────────────────────────────────────────
# nginx(443)는 basic auth 가 걸려 있어 판정에 못 쓴다. compose 가 호스트로 내보낸
# app:8080 을 직접 두드린다(= 실제로 요청을 받을 수 있는 상태인지).
log "앱 헬스 대기: http://localhost:8080/actuator/health (상한 ${HEALTH_TIMEOUT}s)"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
healthy=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  if body=$(curl -fsS --max-time 10 http://localhost:8080/actuator/health 2>/dev/null) \
     && printf '%s' "$body" | grep -q '"status":"UP"'; then
    healthy=1
    break
  fi
  sleep 5
done

if [ "$healthy" -ne 1 ]; then
  echo "앱 헬스 실패 — 아래 로그 확인"
  "${COMPOSE[@]}" ps || true
  "${COMPOSE[@]}" logs --tail=80 app || true
  exit 1
fi
log "앱 헬스 OK"

# 프론트는 상태코드를 가리지 않는다(라우팅에 따라 404 일 수 있음). 응답 자체가 없으면 기동 실패로 본다.
log "프론트 응답 확인: http://localhost:3000/"
web_ok=0
web_deadline=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$web_deadline" ]; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 http://localhost:3000/ 2>/dev/null || echo 000)
  if [ "$code" != "000" ]; then
    web_ok=1
    log "프론트 응답 코드 $code"
    break
  fi
  sleep 5
done

if [ "$web_ok" -ne 1 ]; then
  echo "프론트가 응답하지 않습니다 — 아래 로그 확인"
  "${COMPOSE[@]}" ps || true
  "${COMPOSE[@]}" logs --tail=80 web || true
  exit 1
fi

# ── 5. 성공 표식 + 청소 ──────────────────────────────────────────
# 박스에 "지금 뜬 게 어느 커밋인지"가 없어서 매번 사람이 추측했다. 그 표식을 여기서 남긴다.
{
  echo "sha=$SHA"
  echo "deployed_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "source=s3://$BUCKET/$KEY"
  echo "src_dir=$NEW_DIR"
} > "$ROOT/VERSION"
log "VERSION 기록"
cat "$ROOT/VERSION"

# 옛 src.* 는 최근 것부터 KEEP_SRC 개만 남긴다(현재 것 포함). 싼 박스라 디스크가 금방 찬다.
log "옛 소스 정리(최근 ${KEEP_SRC}개 보존)"
current="$(readlink -f "$SRC_LINK")"
# shellcheck disable=SC2012  # 이름이 아니라 수정시각 순서가 필요해 ls -t 를 쓴다(경로에 공백 없음).
ls -1dt "$ROOT"/src.* 2>/dev/null | tail -n "+$((KEEP_SRC + 1))" | while read -r old; do
  [ -e "$old" ] || continue
  [ "$(readlink -f "$old")" = "$current" ] && continue   # 지금 돌고 있는 판은 절대 안 지운다
  log "삭제: $old"
  rm -rf "$old"
done

# --build 를 반복하면 태그 없는 옛 이미지가 쌓여 디스크를 먹는다(댕글링만 지운다).
docker image prune -f >/dev/null 2>&1 || true

log "배포 완료: $SHA"
