#!/usr/bin/env bash
# 로컬 스택 시드 — 테스트 계정·관리자 계정·샘플 펫 1마리. 있으면 건너뛴다(멱등).
# scripts/dev.sh 가 환경변수를 채워서 부른다(직접 부를 땐 dev.sh seed).
#
# SQL 로 행을 직접 넣지 않고 실제 API(가입·로그인·업로드·부화)를 탄다.
#   - 비밀번호 해시·약관 기록·펫 기본값을 코드가 정한 그대로 만든다(스키마가 바뀌어도 시드가 안 깨진다).
#   - 가짜 생성 모드(ZZAL_GENERATION_REAL=false)라 돈이 나가지 않는다.
# SQL 은 관리자 역할 부여(role='ADMIN') 한 줄만 쓴다 — 그걸 해 주는 API 가 없다.

set -euo pipefail
: "${API_URL:?}" "${PG_SERVICE_EXEC:?}" "${DB_USERNAME:?}"
: "${DEV_USER_EMAIL:?}" "${DEV_USER_PASSWORD:?}" "${DEV_ADMIN_EMAIL:?}" "${DEV_ADMIN_PASSWORD:?}"
DEV_PET_NAME="${DEV_PET_NAME:-테스트여울}"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
say() { echo "  [seed] $*"; }

# post <쿠키파일> <경로> <json> → 상태코드, 본문은 $TMP/body
post() { curl -s -o "$TMP/body" -w '%{http_code}' -b "$1" -c "$1" -H 'Content-Type: application/json' -X POST "$API_URL$2" -d "$3"; }
get()  { curl -s -o "$TMP/body" -w '%{http_code}' -b "$1" -c "$1" "$API_URL$2"; }
jq_py() { python3 -c "import json,sys; d=json.load(open('$TMP/body')); print($1)"; }
jstr()  { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

ensure_user() {   # ensure_user <이메일> <비번> <쿠키파일>
  local email=$1 pw=$2 jar=$3 body code
  body="{\"email\":$(jstr "$email"),\"password\":$(jstr "$pw")}"
  code=$(post "$jar" /api/v1/auth/login "$body")
  if [ "$code" = 200 ]; then say "$email 있음(로그인 200)"; return 0; fi
  code=$(post "$jar" /api/v1/auth/signup \
    "{\"email\":$(jstr "$email"),\"password\":$(jstr "$pw"),\"agreements\":{\"AGE_14\":true,\"TERMS\":true,\"PRIVACY\":true,\"MARKETING\":false}}")
  case "$code" in 200|409) ;; *) say "가입 실패 $code: $(cat "$TMP/body")"; return 1 ;; esac
  code=$(post "$jar" /api/v1/auth/login "$body")
  [ "$code" = 200 ] || { say "가입 뒤 로그인 실패 $code: $(cat "$TMP/body")"; return 1; }
  say "$email 만듦"
}

# 1) 테스트 계정
ensure_user "$DEV_USER_EMAIL" "$DEV_USER_PASSWORD" "$TMP/user.jar"

# 2) 관리자 계정 + 역할
ensure_user "$DEV_ADMIN_EMAIL" "$DEV_ADMIN_PASSWORD" "$TMP/admin.jar"
email_sql="${DEV_ADMIN_EMAIL//\'/\'\'}"
n=$($PG_SERVICE_EXEC psql -U "$DB_USERNAME" -d lore -qAtc \
      "update users set role='ADMIN' where email='$email_sql' and role<>'ADMIN' returning id" | grep -c . || true)
[ "$n" -gt 0 ] && say "$DEV_ADMIN_EMAIL 관리자 역할 부여" || say "$DEV_ADMIN_EMAIL 이미 관리자"

# 3) 샘플 펫 1마리(테스트 계정)
code=$(get "$TMP/user.jar" /api/zzal/v1/me/pets)
[ "$code" = 200 ] || { say "펫 목록 실패 $code: $(cat "$TMP/body")"; exit 1; }
count=$(jq_py 'len(d.get("data") or [])')
if [ "$count" -gt 0 ]; then
  say "펫 있음: $(jq_py '", ".join("#%s %s(%s)" % (p["petId"], p["name"], p["phase"]) for p in d["data"])')"
  exit 0
fi

# 업로드 표 받기 → 1x1 PNG 올리기(가짜 모드는 이 그림을 실제로 읽지 않지만, 흐름은 화면과 같게 탄다)
code=$(post "$TMP/user.jar" /api/v1/uploads/presign '{"domain":"zzal","contentType":"image/png"}')
[ "$code" = 200 ] || { say "presign 실패 $code: $(cat "$TMP/body")"; exit 1; }
key=$(jq_py 'd["data"]["key"]'); url=$(jq_py 'd["data"]["url"]')
python3 -c "import base64,sys; open(sys.argv[1],'wb').write(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='))" "$TMP/p.png"
put=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: image/png' --data-binary "@$TMP/p.png" "$url" || echo 000)
say "그림 업로드 PUT $put ($key)"

code=$(post "$TMP/user.jar" /api/zzal/v1/me/pets/draft "{\"imageKey\":$(jstr "$key")}")
[ "$code" = 200 ] || { say "draft 실패 $code: $(cat "$TMP/body")"; exit 1; }
pet=$(jq_py 'd["data"]["petId"]')
code=$(post "$TMP/user.jar" "/api/zzal/v1/me/pets/draft/$pet/character" "{\"name\":$(jstr "$DEV_PET_NAME")}")
[ "$code" = 200 ] || { say "character 실패 $code: $(cat "$TMP/body")"; exit 1; }
say "펫 #$pet 부화 시작(가짜 모드)"

phase=""
for _ in $(seq 1 60); do
  get "$TMP/user.jar" "/api/zzal/v1/me/pets/$pet/hatch" >/dev/null
  phase=$(jq_py '(d.get("data") or {}).get("phase")')
  case "$phase" in ALIVE|FAILED) break ;; esac
  sleep 3
done
say "펫 #$pet $DEV_PET_NAME → $phase"
[ "$phase" = ALIVE ] || { say "부화가 ALIVE 가 아님 — dev.sh logs app 확인"; exit 1; }
