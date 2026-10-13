#!/usr/bin/env bash
# Lore 로컬 풀스택 — 브랜치(기능)마다 따로 뜨는 도커 스택.
#
#   scripts/dev.sh up [서비스...]   빌드하고 띄운다 → 헬스 대기 → 테스트 계정·펫 시드(없을 때만)
#   scripts/dev.sh down            컨테이너만 내린다(DB·MinIO 볼륨은 남는다 → 테스트 계정 유지)
#   scripts/dev.sh reset [--yes]   볼륨·이미지까지 지우고 장부에서 뺀다("깨끗이"는 이것 하나)
#   scripts/dev.sh logs [서비스]   로그 따라 보기
#   scripts/dev.sh status          컨테이너·헬스·포트·마지막 Flyway·접속 주소
#   scripts/dev.sh ls [--pr]       모든 스택 장부(어느 포트에 어느 브랜치가 떠 있나)
#   scripts/dev.sh seed            시드만 다시(멱등)
#
# 스택 이름 = 현재 브랜치 slug(feat/dev-env-1012 → feat-dev-env-1012).
# 다른 이름을 쓰려면 --stack <이름> 또는 LORE_STACK=<이름>.
# 장부 = ${LORE_DEV_HOME:-~/.lore-dev}/stacks.json. 자세한 건 scripts/README-dev.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
COMPOSE_FILE="$REPO_ROOT/infra/local/docker-compose.local.yml"
ENV_EXAMPLE="$REPO_ROOT/infra/local/env.dev.example"
ENV_LOCAL="$REPO_ROOT/infra/local/.env.dev.local"
STATE_DIR="${LORE_DEV_HOME:-$HOME/.lore-dev}"
LEDGER="$STATE_DIR/stacks.json"
REQUIRED_KEYS=(DB_USERNAME DB_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY CONTENT_BUCKET
               DEV_USER_EMAIL DEV_USER_PASSWORD DEV_ADMIN_EMAIL DEV_ADMIN_PASSWORD)

die()  { echo "✗ $*" >&2; exit 1; }
info() { echo "▸ $*"; }

command -v python3 >/dev/null || die "python3 가 필요합니다(장부 처리)."

# ── 인자 ────────────────────────────────────────────────────────────
STACK_OVERRIDE="${LORE_STACK:-}"
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --stack) STACK_OVERRIDE="${2:?--stack 뒤에 이름}"; shift 2 ;;
    --stack=*) STACK_OVERRIDE="${1#--stack=}"; shift ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"
CMD="${1:-help}"; [ $# -gt 0 ] && shift

slugify() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' | cut -c1-40 | sed -E 's/-+$//'; }

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "HEAD" ] && BRANCH="detached-$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
STACK="$(slugify "${STACK_OVERRIDE:-$BRANCH}")"
[ -n "$STACK" ] || die "스택 이름을 만들 수 없습니다(브랜치: $BRANCH)."
PROJECT="lore-$STACK"
STACK_DIR="$STATE_DIR/$STACK"
STACK_ENV="$STACK_DIR/.env"

# ── 장부(stacks.json) ───────────────────────────────────────────────
# 동시에 두 개가 번호를 잡지 않도록 mkdir 잠금을 건다.
ledger() {
  mkdir -p "$STATE_DIR"
  local lock="$STATE_DIR/.lock" i=0
  until mkdir "$lock" 2>/dev/null; do
    i=$((i+1)); [ $i -gt 50 ] && die "장부 잠금이 풀리지 않습니다: $lock (다른 dev.sh 가 없으면 지우세요)"
    sleep 0.2
  done
  local rc=0
  python3 - "$LEDGER" "$@" <<'PY' || rc=$?
import json, os, socket, sys, time
path, op, *a = sys.argv[1:]
try:
    data = json.load(open(path))
except FileNotFoundError:
    data = {"stacks": {}}
S = data.setdefault("stacks", {})
now = time.strftime("%Y-%m-%d %H:%M")
BASE = {"web": 3200, "api": 8200, "pg": 5400, "minio": 9000}

def port_free(p):
    s = socket.socket()
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", p)); return True
    except OSError:
        return False
    finally:
        s.close()

def save():
    tmp = path + ".tmp"
    json.dump(data, open(tmp, "w"), ensure_ascii=False, indent=2)
    os.replace(tmp, path)

if op == "alloc":                       # alloc <stack> <branch> <worktree>
    st, br, wt = a
    if st in S:
        e = S[st]; e["branch"] = br; e["worktree"] = wt
    else:
        used = {e["nn"] for e in S.values()}
        nn = next((n for n in range(1, 100) if n not in used
                   and all(port_free(b + n) for b in BASE.values())), None)
        if nn is None:
            sys.exit("빈 번호(01~99)가 없습니다. dev.sh ls 로 안 쓰는 스택을 reset 하세요.")
        S[st] = e = {"nn": nn, "branch": br, "worktree": wt,
                     "ports": {k: b + nn for k, b in BASE.items()},
                     "created": now, "last_up": None, "status": "new", "commit": None}
    save()
    print(" ".join(str(e["ports"][k]) for k in ("web", "api", "pg", "minio")))
elif op == "set":                       # set <stack> key=value ...
    st, *kv = a
    if st in S:
        for x in kv:
            k, v = x.split("=", 1); S[st][k] = v
        save()
elif op == "remove":
    S.pop(a[0], None); save()
elif op == "get":                       # get <stack> → ports 한 줄(없으면 빈 줄)
    e = S.get(a[0])
    print(" ".join(str(e["ports"][k]) for k in ("web", "api", "pg", "minio")) if e else "")
elif op == "dump":                      # 탭 구분: stack nn branch worktree web api pg minio created last_up status commit
    for st, e in sorted(S.items(), key=lambda kv: kv[1]["nn"]):
        p = e["ports"]
        print("\t".join(str(x) for x in (st, e["nn"], e["branch"], e["worktree"], p["web"], p["api"],
                                         p["pg"], p["minio"], e["created"], e["last_up"] or "-",
                                         e["status"], e.get("commit") or "-")))
PY
  rmdir "$lock" 2>/dev/null || true
  return $rc
}

# ── 스택 env 파일 만들기 ────────────────────────────────────────────
# 예시값 → .env.dev.local(있으면) → 스택마다 다른 값(포트·주소·JWT) 순서로 쌓는다. 뒤가 이긴다.
write_env() {
  local web=$1 api=$2 pg=$3 minio=$4
  mkdir -p "$STACK_DIR"
  {
    echo "# dev.sh 가 만든 파일 — 손으로 고치지 말고 infra/local/.env.dev.local 을 고칠 것"
    cat "$ENV_EXAMPLE"
    [ -f "$ENV_LOCAL" ] && { echo; echo "# ── .env.dev.local ──"; cat "$ENV_LOCAL"; }
    echo
    echo "# ── 스택 $STACK (dev.sh) ──"
    echo "WEB_PORT=$web"
    echo "API_PORT=$api"
    echo "PG_PORT=$pg"
    echo "MINIO_PORT=$minio"
    echo "S3_PUBLIC_URL=http://s3.lore.localhost:$minio"
    echo "SITE_URL=http://$STACK.localhost:$web"
    echo "JWT_SECRET=lore-local-$(printf '%s' "$STACK" | shasum -a 256 | cut -c1-40)"
  } > "$STACK_ENV"
  chmod 600 "$STACK_ENV"
}

# .env 필수 키 검사 — 비어 있으면 이름을 말하고 멈춘다(조용히 빈 값으로 뜨지 않게).
check_env() {
  local k v missing=()
  for k in "${REQUIRED_KEYS[@]}"; do
    v="$(grep -E "^$k=" "$STACK_ENV" | tail -1 | cut -d= -f2-)"
    [ -n "$v" ] || missing+=("$k")
  done
  [ ${#missing[@]} -eq 0 ] || die "필수 값이 비었습니다: ${missing[*]} (infra/local/env.dev.example 또는 .env.dev.local)"
}

envval() { grep -E "^$1=" "$STACK_ENV" | tail -1 | cut -d= -f2-; }

dc() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" --env-file "$STACK_ENV" "$@"; }

need_docker() { docker info >/dev/null 2>&1 || die "도커가 꺼져 있습니다(맥미니: colima start / 맥북: Docker Desktop 실행)."; }

load_existing() {
  local p; p="$(ledger get "$STACK")"
  [ -n "$p" ] || die "장부에 '$STACK' 스택이 없습니다. scripts/dev.sh up 으로 먼저 만드세요(또는 dev.sh ls)."
  read -r WEB API PG MINIO <<<"$p"
  [ -f "$STACK_ENV" ] || write_env "$WEB" "$API" "$PG" "$MINIO"
}

wait_healthy() {   # wait_healthy <서비스> <초>
  local svc=$1 limit=$2 t=0 cid h
  cid="$(dc ps -q "$svc" 2>/dev/null)"; [ -n "$cid" ] || return 0
  while :; do
    h="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo gone)"
    if [ "$h" = healthy ] || { [ "$h" = running ] && [ "$svc" = minio ]; }; then
      info "$svc: $h (${t}s)"; return 0
    fi
    case "$h" in unhealthy|exited|dead|gone) echo "✗ $svc: $h"; dc logs --tail=40 "$svc"; return 1 ;; esac
    [ $t -ge "$limit" ] && { echo "✗ $svc: ${limit}s 안에 healthy 가 안 됨(지금 $h)"; dc logs --tail=40 "$svc"; return 1; }
    sleep 5; t=$((t+5))
  done
}

print_urls() {
  echo "  웹     http://$STACK.localhost:$WEB   (= http://localhost:$WEB, 스택별 쿠키 분리는 앞 주소)"
  echo "  API    http://localhost:$API   (swagger: /api/swagger-ui.html)"
  echo "  DB     psql -h localhost -p $PG -U $(envval DB_USERNAME) lore   (비번 $(envval DB_PASSWORD))"
  echo "  MinIO  http://s3.lore.localhost:$MINIO"
  echo "  계정   $(envval DEV_USER_EMAIL) / $(envval DEV_USER_PASSWORD)    관리자 $(envval DEV_ADMIN_EMAIL) / $(envval DEV_ADMIN_PASSWORD)"
}

# ── 명령 ────────────────────────────────────────────────────────────
cmd_up() {
  need_docker
  local p; p="$(ledger alloc "$STACK" "$BRANCH" "$REPO_ROOT")" || die "포트 배정 실패"
  read -r WEB API PG MINIO <<<"$p"
  write_env "$WEB" "$API" "$PG" "$MINIO"
  check_env
  info "스택 $STACK ($PROJECT) · 브랜치 $BRANCH · web $WEB api $API pg $PG minio $MINIO"
  dc up -d --build "$@"
  local commit; commit="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  ledger set "$STACK" "status=up" "last_up=$(date '+%Y-%m-%d %H:%M')" "commit=$commit"
  local svcs=("$@"); [ ${#svcs[@]} -eq 0 ] && svcs=(postgres minio app web)
  local s ok=1
  for s in "${svcs[@]}"; do
    case "$s" in postgres) wait_healthy postgres 120 || ok=0 ;;
                 minio)    wait_healthy minio 60 || ok=0 ;;
                 app)      wait_healthy app 600 || ok=0 ;;
                 web)      wait_healthy web 300 || ok=0 ;; esac
  done
  [ $ok -eq 1 ] || die "헬스 대기 실패 — scripts/dev.sh logs <서비스>"
  if [[ " ${svcs[*]} " == *" app "* ]]; then cmd_seed; fi
  echo; info "떴습니다."; print_urls
}

cmd_down() {
  need_docker; load_existing
  dc down --remove-orphans
  ledger set "$STACK" "status=down"
  info "내렸습니다(볼륨 유지 — 다음 up 에 같은 계정·펫). 지우려면 dev.sh reset"
}

cmd_reset() {
  need_docker; load_existing
  if [ "${1:-}" != "--yes" ]; then
    if [ -t 0 ]; then
      read -r -p "스택 '$STACK' 의 DB·MinIO 볼륨과 이미지를 지웁니다. 계속할까요? [y/N] " ans
      [ "$ans" = y ] || [ "$ans" = Y ] || die "취소"
    else
      die "되돌릴 수 없는 작업이라 --yes 가 필요합니다: scripts/dev.sh reset --yes"
    fi
  fi
  dc down -v --rmi local --remove-orphans
  ledger remove "$STACK"
  rm -rf "$STACK_DIR"
  info "스택 '$STACK' 을 지우고 장부에서 뺐습니다."
}

cmd_logs() { need_docker; load_existing; dc logs -f --tail=200 "$@"; }

cmd_status() {
  need_docker; load_existing
  echo "스택 $STACK ($PROJECT) · 브랜치 $BRANCH · 지금 소스 $(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  { printf 'SERVICE\tSTATE\tHEALTH\tPORTS\n'
    dc ps --format '{{.Service}}\t{{.State}}\t{{if .Health}}{{.Health}}{{else}}-{{end}}\t{{.Ports}}'; } | column -t -s $'\t' || true
  local fw
  fw="$(dc exec -T postgres psql -U "$(envval DB_USERNAME)" -d lore -Atc \
        "select version || '  ' || description || '  (' || to_char(installed_on,'MM-DD HH24:MI') || ')' from flyway_schema_history where success and version is not null order by installed_rank desc limit 1" 2>/dev/null || true)"
  echo "Flyway 마지막: ${fw:-(조회 불가 — postgres 가 안 떠 있거나 아직 마이그레이션 전)}"
  local h
  h="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$API/actuator/health" || true)"
  echo "API 헬스: ${h:-000}"
  print_urls
}

cmd_ls() {
  local want_pr=0; [ "${1:-}" = "--pr" ] && want_pr=1
  local running=""
  if docker info >/dev/null 2>&1; then
    running="$(docker ps --format '{{.Label "com.docker.compose.project"}} {{.Label "com.docker.compose.service"}}' | awk '$1 ~ /^lore-/' )"
  fi
  { printf 'STACK\tBRANCH\tWEB\tAPI\tPG\tMINIO\tCREATED\tLAST_UP\tNOW\tREMOTE\n'
  ledger dump | while IFS=$'\t' read -r st _nn br wt web api pg minio created last _status _commit; do
    local live n remote=""
    n="$(printf '%s\n' "$running" | awk -v p="lore-$st" '$1==p' | wc -l | tr -d ' ')"
    if [ "$n" -gt 0 ]; then live="up($n)"; else live="down"; fi
    if [ -d "$wt" ] && git -C "$wt" rev-parse --verify -q "refs/remotes/origin/$br" >/dev/null 2>&1; then
      remote="push됨, 미push $(git -C "$wt" rev-list --count "origin/$br..$br" 2>/dev/null || echo ?)"
    elif [ -d "$wt" ]; then
      remote="원격 없음"
    else
      remote="워크트리 없음"
    fi
    if [ $want_pr -eq 1 ] && command -v gh >/dev/null; then
      local pr; pr="$(cd "$REPO_ROOT" && gh pr list --head "$br" --state all --json number,state,baseRefName \
                      -q 'map("#\(.number) \(.state)→\(.baseRefName)")|join(",")' 2>/dev/null || true)"
      remote="$remote, PR ${pr:-없음}"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$st" "$br" "$web" "$api" "$pg" "$minio" "$created" "$last" "$live" "$remote"
  done; } | column -t -s $'\t'
  echo "(장부: $LEDGER)"
}

cmd_seed() {
  load_existing
  API_URL="http://localhost:$API" PG_SERVICE_EXEC="docker compose -p $PROJECT -f $COMPOSE_FILE --env-file $STACK_ENV exec -T postgres" \
  DB_USERNAME="$(envval DB_USERNAME)" \
  DEV_USER_EMAIL="$(envval DEV_USER_EMAIL)" DEV_USER_PASSWORD="$(envval DEV_USER_PASSWORD)" \
  DEV_ADMIN_EMAIL="$(envval DEV_ADMIN_EMAIL)" DEV_ADMIN_PASSWORD="$(envval DEV_ADMIN_PASSWORD)" \
  DEV_PET_NAME="$(envval DEV_PET_NAME)" \
    bash "$SCRIPT_DIR/dev-seed.sh"
}

case "$CMD" in
  up)     cmd_up "$@" ;;
  down)   cmd_down ;;
  reset)  cmd_reset "$@" ;;
  logs)   cmd_logs "$@" ;;
  status) cmd_status ;;
  ls)     cmd_ls "$@" ;;
  seed)   cmd_seed ;;
  help|-h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "모르는 명령: $CMD (up|down|reset|logs|status|ls|seed)" ;;
esac
