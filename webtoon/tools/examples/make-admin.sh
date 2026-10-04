#!/usr/bin/env bash
# usage: make-admin.sh <dev|staging|prod> — 관리자 계정을 가입시키고 role 을 ADMIN 으로. 이미 있으면 승격만 다시.
# 계정 · 비밀번호는 ~/lore-secrets/lore-admin-accounts.env 에 먼저 적어 둔다(examples-runbook.md).
. "$(dirname "$0")/env.sh" "$1"
[ -n "$PW" ] || { echo "$SECRETS 에 $LORE_ENV 비밀번호가 없습니다"; exit 1; }
curl -s -XPOST "$HOST/api/v1/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LORE_ADMIN_EMAIL\",\"password\":\"$PW\",\"agreements\":{\"AGE_14\":true,\"TERMS\":true,\"PRIVACY\":true,\"MARKETING\":false}}" \
  -o /dev/null -w "가입 %{http_code} (이미 있으면 400 이어도 괜찮다)\n"
bash "$(dirname "$0")/db-sql.sh" "$LORE_ENV" "update users set role='ADMIN' where email='$LORE_ADMIN_EMAIL' returning id, email, role" --write | grep -v '^$'
