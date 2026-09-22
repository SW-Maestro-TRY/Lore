# 환경 차이와 승격 — dev · staging · prod

배포 구조와 사용법은 `server.md` 에 있습니다. 이 문서는 **세 환경이 실제로 무엇이
다른지**, 그래서 **승격할 때 무엇이 dev 에서는 안 드러나는지**를 적습니다.
2026-09-23 조사 기준이고, 근거는 전부 파일:줄로 답니다.

## 1. 먼저 알아야 할 것 — 차이는 의도된 것입니다

dev 와 prod 가 많이 다른 것은 잘못된 게 아니라 **비용 때문에 일부러 그렇게 만든
것**입니다. dev 는 EC2 한 대 안에 전부 밀어 넣어 RDS·ALB·CloudFront 값을 안 냅니다
(`server.md:29-35`).

문제가 되는 것은 차이 자체가 아니라, **그 차이 때문에 dev 에서 통과한 것이
staging 에서 처음 걸리는 종류**가 있다는 점입니다. 그것만 아래 3절에 모았습니다.

## 2. 대조표

| | dev | staging | prod |
| --- | --- | --- | --- |
| 도메인 | `dev.lorecomic.com` | `staging.lorecomic.com` | `lorecomic.com` · www |
| 앱 실행 | **docker compose** | systemd `lore-api`·`lore-web` | 동일 |
| 배포물 | **소스 tar** → 박스에서 도커 빌드 | jar + Next standalone 번들 | 동일 |
| DB | **컨테이너 `postgres:17`** | RDS `lore-staging-db` | RDS `lore-prod-db` |
| 이미지 창고 | **MinIO** | S3 + CloudFront | S3 + CloudFront |
| 입구 | nginx(컨테이너) | nginx(EC2 안) + Let's Encrypt | **ALB** + CloudFront |
| 설정 주입 | 박스 `.env` → compose `environment:` | **SSM** `/lore/staging/*` | **SSM** `/lore/prod/*` |
| 전원 | 항상 | **평소 꺼짐**, 배포 시 기상 → 60분 뒤 종료 | 항상 |
| IAM role | 공용 `lore-github-deploy` | 공용 | **전용** `lore-prod-github-deploy` |

근거: `webtoon/docs/server.md:5-9`, `.github/workflows/deploy.yml:70-96`,
`docker-compose.yml`, `infra/dev/nginx.conf`, `infra/staging/`.

**`infra/` 에 `prod/` 폴더가 없는 것은 빠뜨린 게 아닙니다** — prod 는
`infra/staging/` 의 스크립트를 그대로 씁니다(`deploy.yml:309-325` 가 두 환경에 같은
것을 동기화).

## 3. dev 에서는 절대 안 드러나는 것 ★

승격할 때 여기서 걸립니다. dev 를 아무리 돌려도 안 나옵니다.

### 3-1. 설정 주입 경로가 아예 다릅니다

dev 는 **Parameter Store 를 안 읽습니다**(`server.md:70-78`). 박스 `.env` 를 읽되,
`docker-compose.yml` 의 `environment:` 에 **그 이름을 받는 줄이 있어야만** 컨테이너
안으로 들어갑니다.

그래서 새 설정값을 추가했을 때 손볼 곳이 환경마다 다릅니다.

| 환경 | 넣을 곳 | 추가로 할 일 |
| --- | --- | --- |
| dev | 박스 `/opt/lore-dev/.env` | `docker-compose.yml` 의 `environment:` 에 통로 추가 (프론트 `NEXT_PUBLIC_*` 는 `build.args`) |
| staging | SSM `/lore/staging/<이름>` | 없음 |
| prod | SSM `/lore/prod/<이름>` | 없음 |

2026-09-22 에 `WEBTOON_API_KEY` 를 Parameter Store 에 넣고 dev 에 반영되길 기다리다
빈 값으로 막힌 사고가 있었습니다(#364).

**반대 방향 사고가 승격 때 납니다** — dev `.env` 에만 넣고 SSM 에 안 넣으면,
staging·prod 에서 그 값이 빈 값이 됩니다.

### 3-2. 파이썬 venv 설치 실패가 배포를 안 멈춥니다

dev 는 도커 이미지에 venv 가 구워져 있습니다(`infra/dev/Dockerfile.api:47-63`).
staging·prod 는 배포할 때마다 S3 의 requirements 로 설치하는데,
**웹툰 venv 설치가 실패해도 배포가 그대로 성공 처리됩니다**
(`infra/staging/deploy-api.sh:52-54, 70-74`).

조용히 구버전 venv 로 도는 상태가 되므로, 승격 후 **웹툰 생성을 실제로 한 번
돌려 봐야** 합니다. 헬스 체크로는 안 잡힙니다.

### 3-3. 마이그레이션 순서

세 폴더(`apps/api` · `webtoon/be` · `trailer/be`)가 classpath `db/migration` 한 곳으로
합쳐지고 **같은 `flyway_schema_history` 를 공유**합니다. 번호가 겹치면 기동이
멈춥니다(`application.yml:36-50`).

`out-of-order: true` 라 develop 과 staging 에서 **적용 순서가 다릅니다**. 번호 규칙이
날짜형(`V<YYYYMMDD>_<HHMM>__설명.sql`)으로 바뀐 것도 이 때문입니다.

PR 검사(`scripts/check-migrations.sh`, `contract.yml:18-22`)가 중복은 잡지만,
**순서가 달라져서 생기는 문제는 안 잡습니다.**

### 3-4. MinIO 에 기댄 것

- `APP_S3_ENDPOINT` 가 채워져 있으면 S3 가 **path-style 로 강제**됩니다
  (`docker-compose.yml:127`, `S3Config.java:52-69`). prod 는 빈 값이어야 실제 S3 로
  갑니다.
- presign 서명이 `MINIO_SERVER_URL` 기준입니다(`docker-compose.yml:38-41`). dev 설정이
  따라가면 prod 에서 403.
- `infra/dev/nginx.conf` 에 버킷 이름(`lore-content`)과 도메인이 **리터럴로** 박혀
  있습니다(`:11-12, 22, 64`). prod 는 nginx 가 아니라 ALB 라 옮길 것도 아닙니다.

### 3-5. 홉 수에 기대는 값

`ZZAL_HATCH_XFF_HOPS` 기본값 1 은 **CloudFront→ALB→서버**(prod)를 전제로 한 값입니다
(`application.yml:286-288`). dev 는 nginx 한 겹, staging 은 CloudFront→nginx→앱 이라
구조가 다릅니다. 환경별로 다른 값을 넣는 근거는 저장소에서 못 찾았습니다 —
**확인이 필요합니다.**

### 3-6. dev 전용 nginx 버퍼

`proxy_buffer_size 16k` 가 dev 에만 필요합니다(`infra/dev/nginx.conf:88-92`).
ALB 에는 그 제한이 없어 prod 는 해당 없습니다. dev 에서 502 를 고친 nginx 설정은
prod 와 무관합니다.

## 4. 확인해 봤더니 괜찮았던 것

승격 전에 걱정할 필요 없는 것들입니다. 근거를 같이 적습니다.

| 걱정 | 실제 |
| --- | --- |
| Swagger 가 운영에 열려 있나 (`SPRINGDOC_ENABLED` 기본 true) | **안 열려 있습니다.** `lorecomic.com/api/swagger-ui.html` → 404 (dev 는 302). 2026-09-23 실측 |
| `ZZAL_DEV_TOOLS`·`ZZAL_ADMIN` 이 새어 나가나 | 기본 false 이고 **compose 에 통로 자체가 없어** dev 에서도 안 켜집니다 |
| CORS 설정이 환경마다 다른가 | **CORS 설정이 코드에 없습니다.** 세 환경 모두 같은 출처 구성 |

## 5. 아직 정하지 못한 것

- **예시 작품 시드** — `lore.webtoon.example.seed-works` 기본이 **true** 라
  운영에서도 돕니다(`ExampleWorks.java:91`). 멱등이라 중복은 안 생기지만,
  운영에 예시 작품을 둘지는 정해야 합니다. `BuiltinCharacters` 도 같습니다(`:110`).
- **`ZZAL_HATCH_XFF_HOPS`** — 3-5 참조.
- **문서 불일치** — dev 시드 에셋 개수가 `server.md:32` 와 `env.example:100` 에는
  118개, `infra/dev/README.md:34` 와 `docker-compose.yml:73` 에는 127개로 적혀
  있습니다. 실제 `infra/dev/seed-assets.txt` 는 **127줄**입니다.

## 6. 승격은 한 사람이 정할 일이 아닙니다

`develop` 은 **모두가 커밋하는 공용 통합 브랜치**입니다. 그래서 승격은 "내 작업을
올리는 것" 이 아니라 **"그 시점의 develop 전체를 올리는 것"** 입니다.

2026-09-23 기준:

```
develop → staging   292커밋 (30개 PR)
staging → main        8커밋
develop → main      299커밋
```

292커밋의 작성자: `sanghoon416` 185 · `haeun` 94 · `qwer` 7 · `yeonies` 3 · `0mmmmm0` 3.

**피처 브랜치를 staging 에 따로 PR 내도 마찬가지입니다.** 피처 브랜치가 `develop`
에서 갈라져 나왔다면 그 히스토리를 통째로 들고 갑니다 — 실제로
`feature/retention-purge` 를 staging 에 PR 내면 291커밋이 따라옵니다.

`server.md` 브랜치 규칙 3번("평소에 staging 으로 통째 머지 금지")이 말하는
선별 승격을 하려면, **피처 브랜치를 `develop` 이 아니라 `staging` 에서 따야**
합니다. 지금 브랜치들은 그렇게 안 돼 있습니다.

그래서 승격 전에 **develop 에 커밋한 사람들과 "지금 이 스냅샷을 올려도 되는지"를
맞춰야** 합니다. 진행 중이거나 검증 안 된 작업이 섞여 있을 수 있습니다.

## 7. 승격 점검표

`develop` → `staging` 을 할 때.

- [ ] develop 에 커밋한 사람들과 시점 합의 (6절)
- [ ] dev `.env` 에만 있고 SSM `/lore/staging/*` 에 없는 값이 있는지 대조 (3-1)
- [ ] 마이그레이션 번호가 날짜형인지, 중복이 없는지 (3-3)
- [ ] 배포 후 **웹툰 생성을 실제로 한 편** 돌려 보기 — venv 실패는 조용하다 (3-2)
- [ ] staging 은 60분 뒤 꺼짐. 더 볼 거면 `sudo /opt/lore/cancel-power-off.sh`
- [ ] 릴리스를 새로 자른 직후엔 CI 가 "바뀐 게 없다"고 볼 수 있음 →
      `gh workflow run deploy.yml --ref staging` 수동 실행 (`server.md:47`)

`staging` → `main` 은 위를 staging 에서 전부 확인한 뒤에.
