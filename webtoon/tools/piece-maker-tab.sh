#!/usr/bin/env bash
# 헤더의 Piece Maker 탭 숨기기·보이기.
#
#   bash /Users/mint/project/Lore/webtoon/tools/piece-maker-tab.sh prod hide
#   bash /Users/mint/project/Lore/webtoon/tools/piece-maker-tab.sh prod show
#   bash /Users/mint/project/Lore/webtoon/tools/piece-maker-tab.sh prod status
#
# 환경: dev | staging | prod
# 서버의 /opt/lore/flags/hide-piece-maker 파일을 만들거나 지운다. 프론트(apps/web/app/layout.tsx)가
# 요청마다 이 파일을 보므로 재배포·재시작이 필요 없다. 다음 페이지 요청부터 바뀐다.
# /piece-maker 주소는 그대로 열린다. 헤더 입구만 감춘다.
# AWS 는 SSO 프로필 lore. 만료되면 aws sso login --profile lore --use-device-code
set -uo pipefail
ENV_NAME=${1:-}
ACTION=${2:-}
case $ENV_NAME in
  dev)     HOST=https://dev.lorecomic.com;     INSTANCE=i-0169c150550d91895 ;;
  staging) HOST=https://staging.lorecomic.com; INSTANCE=i-0204d071a9c94bc08 ;;
  prod)    HOST=https://lorecomic.com;         INSTANCE=i-024486651d2c29cf8 ;;
  *) echo "사용법: piece-maker-tab.sh <dev|staging|prod> <hide|show|status>"; exit 1 ;;
esac
FLAG=/opt/lore/flags/hide-piece-maker
case $ACTION in
  hide)   REMOTE="mkdir -p /opt/lore/flags && chmod 755 /opt/lore/flags && touch $FLAG && echo 숨김" ;;
  show)   REMOTE="rm -f $FLAG && echo 보임" ;;
  status) REMOTE="if [ -e $FLAG ]; then echo 숨김; else echo 보임; fi" ;;
  *) echo "사용법: piece-maker-tab.sh <dev|staging|prod> <hide|show|status>"; exit 1 ;;
esac

AWS="aws --profile lore --region ap-northeast-2"
CMD=$($AWS ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
  --parameters "commands=[\"$REMOTE\"]" --query Command.CommandId --output text) || {
  echo "SSM 실패. AWS 로그인이 만료됐을 수 있다: aws sso login --profile lore --use-device-code"; exit 1; }
for _ in $(seq 1 30); do
  sleep 2
  ST=$($AWS ssm get-command-invocation --command-id "$CMD" --instance-id "$INSTANCE" --query Status --output text 2>/dev/null)
  [ "$ST" != InProgress ] && [ "$ST" != Pending ] && break
done
OUT=$($AWS ssm get-command-invocation --command-id "$CMD" --instance-id "$INSTANCE" \
  --query "[Status,StandardOutputContent,StandardErrorContent]" --output text)
echo "서버($ENV_NAME): $OUT"

# 실제 사이트가 그린 HTML 로 한 번 더 본다. <html> 태그만 본다 — 탭이 보일 때도 페이지 데이터에
# "data-hide-piece-maker":"$undefined" 글자가 실려 있어서 전체를 grep 하면 늘 숨김으로 읽힌다.
if curl -s --max-time 15 "$HOST/" | grep -o '<html[^>]*>' | head -1 | grep -q 'data-hide-piece-maker'; then
  echo "사이트($HOST): Piece Maker 탭 숨김"
else
  echo "사이트($HOST): Piece Maker 탭 보임"
fi
