#!/usr/bin/env bash
# usage: db-sql.sh <dev|staging|prod> "<SQL>" [--write]
# 그 환경 DB 에 SSM 으로 SQL 을 보낸다. 기본은 읽기 전용, --write 를 붙여야 쓴다. DB 비밀번호는 서버 안에서만 읽는다.
. "$(dirname "$0")/env.sh" "$1"
SQL=${2:?SQL}; RO="--set=default_transaction_read_only=on"; [ "${3:-}" = "--write" ] && RO=""
SQL_B64=$(printf '%s' "$SQL" | base64 | tr -d '\n')
if [ "$LORE_ENV" = dev ]; then
  REMOTE="set -a; . /opt/lore-dev/.env; set +a; echo $SQL_B64 | base64 -d > /tmp/x.sql
docker exec -i -e PGPASSWORD=\"\$DB_PASSWORD\" lore-dev-postgres-1 psql -U \"\$DB_USERNAME\" -d lore -v ON_ERROR_STOP=1 $RO -f - < /tmp/x.sql; rm -f /tmp/x.sql"
else
  REMOTE="for f in /etc/lore/lore.env /etc/lore/secrets.env; do [ -f \"\$f\" ] && set -a && . \"\$f\" && set +a; done
export PGPASSWORD=\"\$SPRING_DATASOURCE_PASSWORD\"
HP=\$(echo \"\$SPRING_DATASOURCE_URL\" | sed -E 's#jdbc:postgresql://([^/]+)/.*#\1#'); DB=\$(echo \"\$SPRING_DATASOURCE_URL\" | sed -E 's#.*/([^/?]+).*#\1#')
echo $SQL_B64 | base64 -d > /tmp/x.sql
psql -h \"\${HP%%:*}\" -p \"\${HP##*:}\" -U \"\$SPRING_DATASOURCE_USERNAME\" -d \"\$DB\" -v ON_ERROR_STOP=1 $RO -f /tmp/x.sql; rm -f /tmp/x.sql"
fi
R64=$(printf '%s' "$REMOTE" | base64 | tr -d '\n')
CMD=$(aws ssm send-command --profile lore --region ap-northeast-2 --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
  --parameters "commands=[\"echo $R64 | base64 -d > /tmp/q.sh\",\"sudo bash /tmp/q.sh\",\"rm -f /tmp/q.sh\"]" --query Command.CommandId --output text) || exit 1
for i in $(seq 1 30); do sleep 2; st=$(aws ssm get-command-invocation --profile lore --region ap-northeast-2 --command-id "$CMD" --instance-id "$INSTANCE" --query Status --output text 2>/dev/null); [ "$st" != InProgress ] && [ "$st" != Pending ] && break; done
aws ssm get-command-invocation --profile lore --region ap-northeast-2 --command-id "$CMD" --instance-id "$INSTANCE" --query "[StandardOutputContent,StandardErrorContent]" --output text
