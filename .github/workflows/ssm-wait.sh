#!/usr/bin/env bash
# SSM send-command 가 끝날 때까지 기다리고, 대상별 출력·상태를 찍고,
# 하나라도 실패거나 대상이 0이면 exit 1 한다.
#
# 대상이 1대(--instance-ids)든 여러 대(--targets tag)든 list-command-invocations 로 다 커버한다.
# (기존 get-command-invocation --instance-id 는 인스턴스 1대에만 되어 태그·스케일에서 못 쓴다.)
set -uo pipefail

CMD_ID="${1:?command-id 인자가 필요합니다}"
REGION="${AWS_REGION:-ap-northeast-2}"

# 명령이 대상에 퍼지고 끝날 때까지 폴링(최대 ~10분).
for _ in $(seq 1 120); do
  sleep 5
  STATUSES=$(aws ssm list-command-invocations \
    --command-id "$CMD_ID" --region "$REGION" \
    --query 'CommandInvocations[].Status' --output text 2>/dev/null || true)
  [ -z "$STATUSES" ] && continue                       # 아직 대상에 안 퍼짐
  echo "$STATUSES" | grep -Eqw 'Pending|InProgress|Delayed' || break
done

echo "===== 서버 출력 ====="
aws ssm list-command-invocations \
  --command-id "$CMD_ID" --region "$REGION" --details \
  --query 'CommandInvocations[].CommandPlugins[].Output' --output text 2>/dev/null || true

TOTAL=$(aws ssm list-command-invocations \
  --command-id "$CMD_ID" --region "$REGION" \
  --query 'length(CommandInvocations)' --output text 2>/dev/null || echo 0)
FAILED=$(aws ssm list-command-invocations \
  --command-id "$CMD_ID" --region "$REGION" \
  --query "length(CommandInvocations[?Status!='Success'])" --output text 2>/dev/null || echo 1)

echo "대상 ${TOTAL} 건 중 실패 ${FAILED} 건"
if [ "${TOTAL:-0}" = "0" ]; then
  echo "배포 대상이 0 건 — 타깃(인스턴스ID/태그)이 맞는지 확인 필요"; exit 1
fi
if [ "${FAILED:-1}" != "0" ]; then
  echo "배포 실패 — 위 서버 출력 확인"; exit 1
fi
echo "배포 성공"
