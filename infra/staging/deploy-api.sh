#!/bin/bash
# 백엔드 배포 — S3 에서 jar 를 받아 교체·재시작하고, 헬스체크가 실패하면 되돌린다.
#
# CI/CD 가 SSM 으로 이 스크립트를 부른다.
#   인자: <버킷> <jar 키> [리전] [커밋 SHA]
#
# 이 파일이 정본이다. 배포마다 CI 가 S3 를 거쳐 /opt/lore/deploy-api.sh 로 덮어쓰므로
# 서버에서 직접 고치면 다음 배포에 지워진다.
set -e
BUCKET="$1"; KEY="$2"; REGION="${3:-ap-northeast-2}"; SHA="$4"
[ -z "$BUCKET" ] || [ -z "$KEY" ] && { echo "usage: deploy-api.sh <bucket> <key> [region] [sha]"; exit 2; }

# ── 환경 이름 ────────────────────────────────────────────────────────
# CI 는 산출물을 builds/{env}/ 아래에 올린다. 옛 배포는 builds/ 바로 아래였으므로
# 둘 다 찾아본다 — 환경 폴더가 먼저다.
# jar 키($KEY)는 CI 가 통째로 넘겨주므로 여기서 조립하지 않는다. 파이썬 준비물만 조립한다.
LORE_ENV="$(awk -F= '/^LORE_ENV=/{print $2; exit}' /etc/lore/lore.env 2>/dev/null)"
LORE_ENV="${LORE_ENV:-staging}"

# ── 파이썬 준비 ──────────────────────────────────────────────────────
# 짤 후처리(격자 → 움짤)가 numpy·scipy·pillow 를 쓴다. 없으면 배포도 기동도 성공한 채
# 부화 마지막 단계에서만 터진다. 목록이 바뀌었을 때만 설치하므로 평소 배포는 안 느려진다.
# SHA 가 넘어온 경우에만 돈다 — 인자를 안 주던 옛 워크플로와도 호환된다.
fetch_python_assets() {
  for base in "builds/$LORE_ENV" "builds"; do
    if aws s3 cp "s3://$BUCKET/$base/setup-python-$SHA.sh" /tmp/setup-python.sh --region "$REGION" 2>/dev/null \
       && aws s3 cp "s3://$BUCKET/$base/requirements-$SHA.txt" /opt/lore/requirements.txt --region "$REGION" 2>/dev/null; then
      echo "python assets: s3://$BUCKET/$base/"
      return 0
    fi
  done
  return 1
}

if [ -n "$SHA" ]; then
  if fetch_python_assets; then
    cp /tmp/setup-python.sh /opt/lore/setup-python.sh
    chmod +x /opt/lore/setup-python.sh
    bash /opt/lore/setup-python.sh /opt/lore/requirements.txt || {
      echo "PYTHON_SETUP_FAIL"; exit 1; }
  else
    echo "python assets 없음 — 건너뜀"
  fi
fi

# ── 웹툰 하네스 파이썬 준비 ──────────────────────────────────────────
# 웹툰 생성 하네스는 짤과 쓰는 패키지가 달라 가상환경을 따로 둔다(/opt/lore/venv-webtoon).
# 그래서 CI 도 목록과 설치 스크립트를 짤 것과 다른 이름으로 올린다
# (webtoon-requirements-<sha>.txt · webtoon-setup-python-<sha>.sh).
# 설치 스크립트는 목록 경로를 인자로 받고, 목록이 안 바뀌었으면 스스로 건너뛴다.
#
# ★ 여기서 실패해도 배포를 멈추지 않는다. 웹툰은 짤과 다른 도메인이라, 그쪽 준비물이
#   없거나 설치가 깨졌다는 이유로 짤 배포까지 되돌리면 손해가 더 크다.
#   무슨 일이 있었는지 한 줄 남기고 지나간다.
fetch_webtoon_assets() {
  for base in "builds/$LORE_ENV" "builds"; do
    if aws s3 cp "s3://$BUCKET/$base/webtoon-setup-python-$SHA.sh" /tmp/webtoon-setup-python.sh --region "$REGION" 2>/dev/null \
       && aws s3 cp "s3://$BUCKET/$base/webtoon-requirements-$SHA.txt" /opt/lore/webtoon-requirements.txt --region "$REGION" 2>/dev/null; then
      echo "webtoon python assets: s3://$BUCKET/$base/"
      return 0
    fi
  done
  return 1
}

if [ -n "$SHA" ]; then
  if fetch_webtoon_assets; then
    cp /tmp/webtoon-setup-python.sh /opt/lore/webtoon-setup-python.sh
    chmod +x /opt/lore/webtoon-setup-python.sh
    if bash /opt/lore/webtoon-setup-python.sh /opt/lore/webtoon-requirements.txt; then
      echo "WEBTOON_PYTHON_OK"
    else
      echo "WEBTOON_PYTHON_FAIL — 웹툰 가상환경 갱신 실패, 배포는 그대로 진행한다"
    fi
  else
    echo "webtoon python assets 없음 — 건너뜀"
  fi
fi

aws s3 cp "s3://$BUCKET/$KEY" /tmp/app.jar --region "$REGION"
cp /opt/lore/lore.jar /opt/lore/lore.jar.bak 2>/dev/null || true
mv /tmp/app.jar /opt/lore/lore.jar
chown ubuntu:ubuntu /opt/lore/lore.jar
systemctl restart lore-api

# 헬스체크가 통과할 때까지 최대 60초 기다린다. 재시작 직후엔 아직 안 뜬다.
for i in $(seq 1 12); do
  sleep 5
  if curl -sf -m 3 localhost:8080/actuator/health >/dev/null 2>&1; then
    # 지금 서버에 어느 판이 올라가 있는가 — 배포 검증의 유일한 증거다.
    if [ -n "$SHA" ]; then echo "$SHA" > /opt/lore/VERSION; fi
    echo "DEPLOY_OK"; rm -f /opt/lore/lore.jar.bak; exit 0
  fi
done

echo "DEPLOY_FAIL_ROLLBACK"
if [ -f /opt/lore/lore.jar.bak ]; then
  mv /opt/lore/lore.jar.bak /opt/lore/lore.jar
  systemctl restart lore-api
fi
exit 1
