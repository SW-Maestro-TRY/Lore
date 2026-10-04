#!/usr/bin/env bash
# 예시 작품 도구 공통 — 환경 이름(dev|staging|prod) → 주소 · 서버 · 관리자 비밀번호.
# 비밀번호는 저장소에 두지 않는다 — git 에서 빠진 webtoon/account/admin-accounts.env 를 읽는다
#   LORE_ADMIN_EMAIL=...  LORE_ADMIN_PW_DEV=...  LORE_ADMIN_PW_STAGING=...  LORE_ADMIN_PW_PROD=...
# 워크트리에서 돌려도 원본 저장소의 그 파일을 찾는다. 다른 자리면 LORE_ADMIN_SECRETS 로 준다.
# AWS 는 SSO 프로필 lore (만료되면 `aws sso login --profile lore --use-device-code`).
set -uo pipefail
LORE_ENV=${1:?환경 이름: dev | staging | prod}
MAIN_REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")" && cd "$(git rev-parse --git-common-dir)/.." && pwd)
SECRETS=${LORE_ADMIN_SECRETS:-$MAIN_REPO/webtoon/account/admin-accounts.env}
[ -f "$SECRETS" ] || SECRETS=$HOME/lore-secrets/lore-admin-accounts.env
[ -f "$SECRETS" ] || { echo "관리자 계정 파일이 없습니다: $SECRETS (webtoon/docs/examples-runbook.md)"; exit 1; }
set -a; . "$SECRETS"; set +a
case $LORE_ENV in
  dev)     HOST=https://dev.lorecomic.com;     INSTANCE=i-0169c150550d91895; PW=${LORE_ADMIN_PW_DEV:-} ;;
  staging) HOST=https://staging.lorecomic.com; INSTANCE=i-0204d071a9c94bc08; PW=${LORE_ADMIN_PW_STAGING:-} ;;
  prod)    HOST=https://lorecomic.com;         INSTANCE=i-024486651d2c29cf8; PW=${LORE_ADMIN_PW_PROD:-} ;;
  *) echo "모르는 환경: $LORE_ENV"; exit 1 ;;
esac
API=$HOST/api/webtoon/v1/admin/examples
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT

# 관리자로 로그인해 쿠키를 JAR 에 담는다
login() {
  local code
  code=$(curl -s -c "$JAR" -o /dev/null -w "%{http_code}" -XPOST "$HOST/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d "{\"email\":\"$LORE_ADMIN_EMAIL\",\"password\":\"$PW\"}")
  [ "$code" = 200 ] || { echo "관리자 로그인 실패($code) — make-admin.sh $LORE_ENV 를 먼저"; exit 1; }
}

# 우리 서버 응답의 data 를 꺼낸다(봉투가 없으면 그대로)
data() { python3 -c "import json,sys;d=json.load(sys.stdin);print(json.dumps(d.get('data',d) if isinstance(d,dict) else d,ensure_ascii=False))"; }
