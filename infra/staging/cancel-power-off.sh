#!/bin/bash
# staging 전원 차단 예약 취소 — 지금 켜 둔 서버를 계속 쓰고 싶을 때.
#
#   sudo /opt/lore/cancel-power-off.sh
#
# Session Manager 로 박스에 붙어 이 한 줄만 치면 된다. 취소한 뒤로는 다음 배포가
# 들어올 때까지 아무도 서버를 끄지 않으므로, 다 쓰고 나면 직접 끄거나
# schedule-power-off.sh 로 다시 예약해 둔다.
set -uo pipefail

UNIT=lore-power-off

if ! systemctl is-active --quiet "$UNIT.timer"; then
  echo "예약된 전원 차단이 없다"
  exit 0
fi

systemctl stop "$UNIT.timer"
systemctl stop "$UNIT.service" >/dev/null 2>&1 || true
systemctl reset-failed "$UNIT.timer" "$UNIT.service" >/dev/null 2>&1 || true

echo "전원 차단 예약을 취소했다"
systemctl list-timers --all --no-pager "$UNIT.timer"
