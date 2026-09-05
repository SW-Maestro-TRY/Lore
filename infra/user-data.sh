#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# EC2 user data — 새 인스턴스가 처음 켜질 때 한 번 실행된다.
#
# ★ 왜 필요한가 — 서버가 자바만 있으면 되던 때는 jar 하나가 앱 전체였다.
#   후처리에 파이썬이 들어오면서, 새 서버를 띄울 때마다 사람이 손으로 깔아야 하는 상태가 됐다.
#   무중단 배포를 하거나 서버를 늘리면 그때마다 그래야 한다.
#
# ★ 지금 돌고 있는 서버에는 이미 user data 가 실행된 뒤라 적용되지 않는다.
#   그 서버는 배포 스크립트(deploy-api.sh)가 setup-python.sh 를 부르면서 따라온다.
#
# ★ 나중에 도커로 옮기면 이 파일은 필요 없어진다. 11/27 계정 삭제 뒤 운영 인프라를
#   새로 세울 때가 그 시점으로 적당하다.
# ─────────────────────────────────────────────────────────────
set -euo pipefail
exec > >(tee -a /var/log/lore-user-data.log) 2>&1

apt-get update -qq
apt-get install -y -qq python3 python3-venv openjdk-21-jre-headless awscli

mkdir -p /opt/lore
# requirements.txt 는 배포물에 들어 있다. 첫 부팅 시점에는 없을 수 있으므로,
# 있으면 미리 깔고 없으면 첫 배포 때 deploy-api.sh 가 처리한다.
if [[ -f /opt/lore/requirements.txt ]]; then
  bash /opt/lore/setup-python.sh /opt/lore/requirements.txt
fi

echo "user-data 완료"
