#!/bin/sh
#
# dev 스택 정적 그림 씨뿌리기 — 1단계(내려받기).
#
# MinIO 는 빈 통으로 뜬다. 그런데 화면(배경·소품·여울 시연·온보딩·랜딩 썸네일)은
# 버킷에 그림이 있다고 가정하고 `/images/zzal/...` 를 부른다 → 안 채우면 전부 깨진 그림이다.
# 그래서 첫 기동에 staging CloudFront(공개)에서 같은 키를 그대로 받아 온다. AWS 자격이 필요 없다.
#
# 목록은 infra/dev/seed-assets.txt (키 한 줄에 하나, 정렬됨). 사용자 업로드·펫 그림은 없다 —
# 그건 사람이 만들어야 나오는 것이고, 정적 자산만 미리 깔아 둔다.
#
# 2단계(업로드)는 compose 의 minio-seed 가 mc mirror 로 한다.
#
# ★ 오프라인이어도 스택이 떠야 한다 — 내려받기 실패는 경고만 찍고 넘어간다(exit 0).
# ★ 두 번째 기동부터는 이미 받은 파일을 건너뛴다(볼륨에 남아 있다).

BASE_URL="${SEED_BASE_URL:-https://staging.lorecomic.com}"
LIST="${SEED_LIST:-/seed/seed-assets.txt}"
DEST="${SEED_DIR:-/seed/data}"

if [ ! -f "$LIST" ]; then
  echo "[seed] 목록이 없습니다: $LIST — 씨뿌리기를 건너뜁니다"
  exit 0
fi

total=0; got=0; skipped=0; failed=0

while IFS= read -r key; do
  case "$key" in ''|\#*) continue ;; esac
  total=$((total + 1))
  out="$DEST/$key"

  # 이미 받아 둔 것(크기 > 0)은 다시 받지 않는다.
  if [ -s "$out" ]; then
    skipped=$((skipped + 1))
    continue
  fi

  mkdir -p "$(dirname "$out")"
  # 반쪽 파일이 남으면 다음 기동에서 "받았다"고 착각한다 → tmp 에 받고 성공했을 때만 옮긴다.
  if curl -fsS --max-time 60 -o "$out.tmp" "$BASE_URL/$key"; then
    mv "$out.tmp" "$out"
    got=$((got + 1))
  else
    rm -f "$out.tmp"
    failed=$((failed + 1))
    echo "[seed] 내려받기 실패(넘어감): $key"
  fi
done < "$LIST"

echo "[seed] 내려받기 완료 — 목록 $total · 새로 받음 $got · 이미 있음 $skipped · 실패 $failed"
[ "$failed" -gt 0 ] && echo "[seed] 실패분은 화면에서 빈 그림으로 보입니다. 네트워크 복구 후 스택을 다시 올리면 채워집니다."
exit 0
