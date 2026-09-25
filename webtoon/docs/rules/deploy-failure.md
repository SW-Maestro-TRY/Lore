# 배포가 실패했을 때

GitHub Actions 의 `Deploy to EC2` 가 실패했을 때 읽습니다. 핵심은 **무작정 다시 돌리지
않는 것**입니다. 코드 문제면 몇 번을 돌려도 같은 자리에서 실패합니다.

```
① 실패한 커밋 확인
② 로컬에서 그 커밋 빌드  ── 실패 → 코드 문제. 고치는 PR 을 낸다 (다시 돌리지 않는다)
        │ 성공
③ 서버에서 같은 커밋 build ── 실패 → 서버 쪽 문제. 그 build 로그를 가지고 원인부터
        │ 성공
④ Actions 에서 최신 develop run 하나만 Re-run
```

## ① 실패한 커밋 확인

```
gh run list --branch develop --workflow "Deploy to EC2" --limit 5
gh run view <run-id> --log-failed
```

실패한 job·step 과 커밋 SHA 를 봅니다. dev 는 `dev_deploy` job 의 `SSM 으로 배포` step
이 박스 안에서 도커 이미지를 빌드합니다.

로그에 `---Output truncated---` 가 보이면 서버 출력이 AWS 쪽에서 잘린 것입니다.
그러면 `Dockerfile.web:42 RUN npm run build --workspace lore-web ... exit code: 1`
처럼 **어느 줄에서 멈췄는지만 남고 진짜 에러는 안 보입니다.** 그래서 ②로 갑니다.

## ② 로컬에서 같은 커밋을 빌드

작업 중인 폴더를 건드리지 않게 별도 워크트리에서 합니다.

```
git fetch origin
git worktree add --detach ../deploy-check <SHA>
cd ../deploy-check
```

실패한 서비스에 맞춰 돌립니다.

| 로그에서 멈춘 곳 | 로컬 명령 |
| --- | --- |
| `web` (`Dockerfile.web`) | `npm ci && npm run build --workspace lore-web` |
| `app` (`Dockerfile.api`) | `./gradlew --no-daemon clean bootJar -x test` |

- **로컬에서도 실패하면 코드 문제입니다.** 에러 메시지에 파일과 줄이 나옵니다. 고치는
  이슈·PR 을 내고, Actions 는 다시 돌리지 않습니다. 고친 PR 이 머지되면 배포가 새로
  돕니다.
- 로컬에서 성공하면 ③으로 갑니다.
- 끝나면 `git worktree remove ../deploy-check` 로 치웁니다.

2026-09-24 사례: 로그에는 `exit code: 1` 뿐이었는데, 로컬 빌드에서
`Result.tsx:264 Cannot find name 'IconClose'` 가 바로 나왔습니다(#397).

## ③ 서버에서 같은 커밋을 build

로컬은 되는데 서버에서만 실패할 때입니다. 서버에는 AWS 콘솔 → EC2 → `lore-dev` →
Connect → Session Manager 로 들어갑니다.

```
# 서버의 소스가 실패한 커밋을 가리키는지 확인 — Actions 의 SHA 와 같아야 한다
readlink -f /opt/lore-dev/src          # /opt/lore-dev/src.<SHA>

# compose 설정이 읽히는지
sudo docker compose -f /opt/lore-dev/src/docker-compose.yml \
  -f /opt/lore-dev/src/docker-compose.dev-ec2.yml \
  --env-file /opt/lore-dev/.env config --services

# 실패한 서비스를 build 만 한다 (web 이면 web, 이어서 app 도)
sudo docker compose -f /opt/lore-dev/src/docker-compose.yml \
  -f /opt/lore-dev/src/docker-compose.dev-ec2.yml \
  --env-file /opt/lore-dev/.env build web --progress=plain

# 이미지가 생겼는지
sudo docker images | grep lore-dev
```

- SHA 가 다르면 실패한 소스가 아직 서버에 안 풀린 것입니다. 여기서 멈추고 그 원인부터
  봅니다.
- `--progress is a global compose flag` 경고는 실패가 아닙니다.
- `Image lore-dev-web Built` · `Image lore-dev-app Built` 가 나오면 ④로 갑니다.
- 서버에서도 실패하면 그 build 로그로 원인을 봅니다. 다시 돌리지 않습니다.

## ④ Actions 재실행

GitHub → Actions → 실패한 run → **Re-run failed jobs**.

**가장 최신 develop run 하나만** 다시 돌립니다. 앞선 PR 들의 실패한 run 은 돌리지
않습니다. 최신 develop 커밋에 앞선 머지가 이미 다 들어 있습니다.

## 원인을 알기 전에 하지 않는 것

```
docker compose down
docker compose up -d --build     # 실제 서비스 컨테이너를 바꾼다
docker system prune
--no-cache
```

확인 단계에서는 `build` 만 씁니다. `build` 와 `inspect` 는 떠 있는 서비스를 건드리지
않습니다.
