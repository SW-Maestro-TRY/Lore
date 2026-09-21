#!/bin/bash
# staging 전원 차단 예약 — N 분 뒤에 power-off.sh 를 돌리는 systemd 타이머를 건다.
#
#   sudo /opt/lore/schedule-power-off.sh 60
#
# 배포가 끝날 때마다 CI 가 이걸 부른다. 이미 걸린 예약은 먼저 지우고 새로 거므로
# 한 시간 안에 배포가 또 들어오면 카운트다운이 그 시점부터 다시 60 분이 된다.
#
# 타이머는 systemd-run 이 만드는 일회성(transient) 유닛이다. 파일로 남지 않아서
# 박스가 꺼졌다 켜지면 예약도 같이 사라진다 — 켜 둔 채 잊어버릴 위험이 없다.
set -uo pipefail

MIN="${1:-60}"
UNIT=lore-power-off
SCRIPT="${LORE_POWER_OFF:-/opt/lore/power-off.sh}"

case "$MIN" in
  ''|*[!0-9]*) echo "usage: schedule-power-off.sh <분>"; exit 2 ;;
esac
[ "$MIN" -lt 1 ] && { echo "1 분 이상이어야 한다"; exit 2; }
[ -x "$SCRIPT" ] || { echo "실행할 스크립트가 없다: $SCRIPT"; exit 2; }

# 이미 걸린 예약 제거. 없으면 조용히 넘어간다.
systemctl stop "$UNIT.timer"   >/dev/null 2>&1 || true
systemctl stop "$UNIT.service" >/dev/null 2>&1 || true
systemctl reset-failed "$UNIT.timer" "$UNIT.service" >/dev/null 2>&1 || true

systemd-run \
  --collect \
  --unit="$UNIT" \
  --description="Lore staging 자동 전원 차단" \
  --on-active="${MIN}m" \
  --timer-property=AccuracySec=10s \
  "$SCRIPT" >/dev/null || { echo "타이머 등록 실패"; exit 1; }

WHEN="$(date -d "+${MIN} minutes" '+%Y-%m-%d %H:%M:%S %Z')"
echo "전원 차단 예약: ${MIN}분 뒤 ($WHEN)"
systemctl list-timers --all --no-pager "$UNIT.timer"
