#!/bin/bash
# staging 자동 전원 차단 — RDS 를 먼저 재우고 박스 자신을 끈다.
#
# schedule-power-off.sh 가 만든 systemd 타이머가 예약 시각에 이 스크립트를 부른다.
# 사람이 직접 불러도 된다(`sudo /opt/lore/power-off.sh`). 인자는 없다.
#
# 왜 shutdown 인가 — 박스가 스스로 꺼지면 EC2 권한이 한 줄도 필요 없다.
# 인스턴스의 InstanceInitiatedShutdownBehavior 가 stop 이라 종료가 아니라 정지로 끝난다.
# 그 설정이 언젠가 terminate 로 바뀌면 아래 ec2 stop-instances 갈래로 갈아탈 수 있게
# 역할에 ec2:StopInstances(자기 자신만)를 같이 열어 뒀다.
set -uo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
DB_ID="${LORE_STAGING_DB:-lore-staging-db}"
TAG=lore-power-off

log() { echo "$*"; logger -t "$TAG" -- "$*"; }

log "전원 차단 시작 — RDS=$DB_ID region=$REGION"

# RDS 정지. 이미 stopping·stopped 면 InvalidDBInstanceState 가 떨어지는데 그건 정상이다.
STOP_OUT="$(aws rds stop-db-instance \
  --db-instance-identifier "$DB_ID" \
  --region "$REGION" 2>&1)"
STOP_RC=$?

if [ "$STOP_RC" -eq 0 ]; then
  log "RDS 정지 요청 완료"
elif printf '%s' "$STOP_OUT" | grep -q 'InvalidDBInstanceState'; then
  log "RDS 는 이미 정지 중이거나 정지됨 — 넘어간다"
else
  # 여기서 멈추면 박스와 DB 가 둘 다 켜진 채로 남는다. 더 비싼 쪽(박스)이라도 끈다.
  log "RDS 정지 실패 — 박스는 그대로 끈다: $STOP_OUT"
fi

log "박스 종료(shutdown -h now) — 인스턴스는 stop 상태가 된다"
sync
shutdown -h now
