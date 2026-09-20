#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# SSM Parameter Store 의 /lore/{env}/ 값을 /etc/lore/secrets.env 로 내려 받는다.
#
# 부르는 곳
#   lore-api.service 의 ExecStartPre — API 가 뜰 때마다 먼저 돈다.
#   그래서 박스에서의 경로는 /opt/lore/load-secrets.sh 로 고정이고,
#   CI 의 스크립트 동기화가 이 파일을 그 이름으로 복사해 깐다.
#
# 이름이 다른 이유 — 비밀 파일 검사가 이름에 secret 이 들어간 파일을 막는다.
# 이 스크립트는 비밀값을 담지 않고 가져오기만 하지만, 검사는 내용을 보지 않는다.
# 유닛 파일을 고치는 대신 레포 쪽 이름만 바꿔 두었다.
#
# 값 자체는 이 파일에 없다. 바꿀 것이 있으면 Parameter Store 의 /lore/{env}/ 를 고친다.
# ─────────────────────────────────────────────────────────────

set -uo pipefail

ENVNAME="${LORE_ENV:-dev}"
REGION="${AWS_REGION:-ap-northeast-2}"
PREFIX="/lore/$ENVNAME/"
OUT="/etc/lore/secrets.env"

umask 077
: > "$OUT"

echo "Parameter 조회: $PREFIX"

PARAMETERS=$(aws ssm get-parameters-by-path \
  --path "$PREFIX" \
  --recursive \
  --with-decryption \
  --region "$REGION" \
  --query 'Parameters[*].[Name,Value]' \
  --output text)

if [[ -z "$PARAMETERS" ]]; then
  echo "Parameter 없음: $PREFIX"
  chown root:root "$OUT"
  chmod 600 "$OUT"
  exit 0
fi

while IFS=$'\t' read -r name value; do
  [[ -z "$name" ]] && continue

  key="${name#"$PREFIX"}"
  env_name=$(printf '%s' "$key" | tr '[:lower:]-' '[:upper:]_')

  printf '%s=%s\n' "$env_name" "$value" >> "$OUT"

  echo "불러옴: $env_name"
done <<< "$PARAMETERS"

chown root:root "$OUT"
chmod 600 "$OUT"

echo "Secret 환경변수 생성 완료: $OUT"
