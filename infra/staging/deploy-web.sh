#!/bin/bash
# 프론트 배포 — S3 에서 standalone 번들을 받아 교체·재시작하고, 헬스체크가 실패하면 되돌린다.
#
#   인자: <버킷> <번들 키> [리전]
#
# 이 파일이 정본이다. 배포마다 CI 가 S3 를 거쳐 /opt/lore/deploy-web.sh 로 덮어쓴다.
set -e
BUCKET="$1"; KEY="$2"; REGION="${3:-ap-northeast-2}"
[ -z "$BUCKET" ] || [ -z "$KEY" ] && { echo "usage: deploy-web.sh <bucket> <key> [region]"; exit 2; }

aws s3 cp "s3://$BUCKET/$KEY" /tmp/web.tar.gz --region "$REGION"

# 이전 판을 통째로 백업해둔다. 번들은 파일이 많아 개별 백업이 의미 없다.
rm -rf /opt/lore/web.bak
[ -d /opt/lore/web ] && cp -r /opt/lore/web /opt/lore/web.bak

rm -rf /opt/lore/web && mkdir -p /opt/lore/web
tar xzf /tmp/web.tar.gz -C /opt/lore/web
chown -R ubuntu:ubuntu /opt/lore/web
rm -f /tmp/web.tar.gz
systemctl restart lore-web

for i in $(seq 1 12); do
  sleep 5
  if curl -sf -m 3 localhost:3000 >/dev/null 2>&1; then
    # 프론트는 SHA 인자를 안 받는다 — 키 이름(web-{sha}.tar.gz)에서 뽑아 남긴다.
    WEB_SHA="$(basename "$KEY" .tar.gz | sed -n 's/^web-//p')"
    if [ -n "$WEB_SHA" ]; then echo "$WEB_SHA" > /opt/lore/VERSION.web; fi
    echo "DEPLOY_OK"; rm -rf /opt/lore/web.bak; exit 0
  fi
done

echo "DEPLOY_FAIL_ROLLBACK"
if [ -d /opt/lore/web.bak ]; then
  rm -rf /opt/lore/web && mv /opt/lore/web.bak /opt/lore/web
  systemctl restart lore-web
fi
exit 1
