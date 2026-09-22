# Lore 서버 구조 · 현황 · 사용 설명서

## 1. 한눈에 — 3개 환경

| 환경 | 용도 | 브랜치 | 주소 | 구성 | 상태 (9/20 18:40) |
| --- | --- | --- | --- | --- | --- |
| dev | 테스트 서버. 아무거나 올려도 됨 | develop | https://dev.lorecomic.com | EC2 1대 안에 도커로 앱·DB·이미지창고(MinIO)·nginx 전부 | 운영 중 |
| staging | 운영 리허설. 운영에 올릴 것만 | staging | https://staging.lorecomic.com | EC2(안에 nginx 입구) + RDS + S3 + CloudFront | 기본 꺼 둠. 배포하면 자동으로 켜짐 |
| prod | 실제 서비스 | main | https://lorecomic.com · www | EC2 + RDS + ALB + S3 + CloudFront | 2026-09-20 18:20 오픈. 항상 켜 둠 |

## 2. 구조

**prod (운영)**

사용자 → CloudFront(CDN·HTTPS 입구, `E198BSQE3JQYCS`) → ALB `lore-prod-alb`(경로 분기) → EC2 `lore-prod` 1대(t3.small, `/api/*` → Spring 8080 · 나머지 → Next.js 3000) → RDS `lore-prod-db`(PostgreSQL)

- 이미지: 브라우저가 서명 URL 로 S3 `lore-prod-contents-…` 에 직접 업로드 → CloudFront `/images/*` 로 읽음
- 비밀값: Parameter Store `/lore/prod/*` 를 서버가 기동 시 읽음
- 항상 켜 둠

**staging (운영 리허설)**

사용자 → CloudFront(`E2DQOZKTRM3UGJ`) → EC2 `lore-staging` 안의 nginx 가 HTTPS 를 직접 받아 경로 분기 → 앱 → RDS `lore-staging-db`

- ALB 없음. 입구를 EC2 안의 nginx + Let's Encrypt 무료 인증서로 대체.
- 기본은 꺼 둠. 배포할 때 자동으로 켜지고 60분 뒤 다시 꺼짐

**dev (혼자 다 하는 박스)**

사용자 → nginx(HTTPS) → 앱(도커) → 같은 박스 안의 PostgreSQL · MinIO(S3 대체). 비밀값은 박스 안 `.env` 파일. RDS·ALB·CloudFront 없음 → 비용 최소.

- 이미지도 이 박스가 직접 냄(`/images/` → MinIO). 첫 기동 때 staging CloudFront 에서 공용 에셋 118개를 받아 자동으로 채움
- 내 노트북에서 똑같이 띄우기: `infra/dev/README.md` 대로 → `localhost:3100` · 환경값 예시는 `infra/dev/env.example`

## 3. 배포 흐름

피처 브랜치 → develop (= 개발 서버) → staging (= 리허설) → main (= 운영)

- 해당 브랜치에 PR 이 머지되면 GitHub Actions 가 자동 배포. 약 2분 (dev 는 도커 이미지를 다시 굽느라 5~10분)
- staging 은 꺼져 있어도 배포하면 알아서 켜짐 — 깨우는 데 5~8분이 더 걸림
- CI 는 바뀐 영역만 배포함 (백엔드 파일만 바뀌면 백엔드만). 전체를 맞춰야 할 때는 수동 실행 (아래 4-2)

**브랜치 규칙**

1. 피처는 브랜치 하나에, 다른 기능 코드에 기대지 않게 (그래야 하나만 골라 올릴 수 있음)
2. develop 머지 뒤에도 피처 브랜치를 지우지 않기 (staging 에 또 PR 낼 수 있게)
3. 평소에 staging 으로 통째 머지 금지. 같은 피처 브랜치로 staging 에 PR 을 한 번 더 내거나, 픽스는 cherry-pick
4. 릴리스는 통째 승격 PR 로 (`develop` → `staging`, `staging` → `main`). 릴리스 컷 = develop 이 정리된 그 시점으로 staging 을 맞추는 것
5. 릴리스를 새로 자른 직후에는 CI 가 "바뀐 게 없다"고 볼 수 있음 → `gh workflow run deploy.yml --ref <브랜치>` 를 한 번 수동 실행
6. develop · staging · main 세 브랜치 모두 보호 규칙 적용 — PR 로만 들어감, 강제 push·브랜치 삭제 금지
7. GitHub environment `prod` 는 `main` 브랜치에서만 사용 가능하도록 제한

## 4. 사용 설명서

### 4-1. 전체 다시 배포 (수동 실행)

GitHub → Actions → Deploy → Run workflow → 브랜치 선택 (develop / staging / main) → Run. 바뀐 영역과 상관없이 백엔드·프론트 모두 다시 올라감.

- 터미널로도 가능: `gh workflow run deploy.yml --ref <브랜치>`

### 4-2. 비밀값(API 키 등)을 넣고 싶다

| 환경 | 위치 |
| --- | --- |
| dev | 박스 안 `.env` |
| staging | AWS Systems Manager → Parameter Store → `/lore/staging/<이름>` |
| prod | `/lore/prod/<이름>` |
- 이름은 세 환경 모두 같음 (예: `webtoon_api_key`, `mail_username`, `zzal_openai_api_key`). 값만 환경별로 다르게
- dev `.env` 실물 확인: AWS 콘솔 → EC2 → `lore-dev` → Connect → Session Manager → `sudo cat /opt/lore-dev/.env`
- **dev 는 staging·prod 와 달리 Parameter Store 를 안 읽는다.** systemd 기동 훅
  (`load-env-params.sh`)이 없고, 대신 `docker compose --env-file`로 박스 `.env`를
  직접 읽는다. `docker-compose.yml`도 `env_file`이 아니라 각 서비스 `environment:`에
  변수 이름을 하나하나 적어 넘기는 방식이라, **dev `.env`에 새 값을 추가해도
  `docker-compose.yml`에 그 이름을 받는 줄이 없으면 컨테이너 안으로 안 들어간다.**
  새 비밀값·설정을 쓰려면 `.env`뿐 아니라 `docker-compose.yml`의 `environment:`
  (프론트 전용 `NEXT_PUBLIC_*`는 `next build`가 번들에 박아 넣으므로 `build.args`)에도
  통로를 추가해야 한다(2026-09-22, #364 — `WEBTOON_API_KEY`가 이 문서만 보고는
  Parameter Store 에 넣으면 자동 반영되는 줄 알았다가 빈 값으로 막힌 사고).

### 4-3. 이미지 창고 (S3)

| 환경 | 버킷 |
| --- | --- |
| dev | 박스 안 MinIO |
| staging | `lore-contents-staging-…` |
| prod | `lore-prod-contents-…` |
| (보관) | `lore-contents-…` (옛 버킷) |

같은 이름의 파일을 덮어써도 CloudFront 캐시 때문에 바로 안 바뀔 수 있음 → 새 파일은 새 이름(폴더 버전)으로.

### 4-4. 서버 안을 보고 싶다 (로그 등)

AWS 콘솔 → EC2 → 인스턴스 선택 → Connect → Session Manager (SSH 키 없이 브라우저 터미널)

- 앱 로그: `sudo journalctl -u lore-api -f` (백엔드) · `sudo journalctl -u lore-web -f` (프론트)
- 상태: `curl localhost:8080/actuator/health`
- dev 박스는 도커: `cd /opt/lore-dev && sudo docker compose logs -f app`

### 4-5. staging 전원 (자동)

staging 은 평소 꺼져 있고, 배포가 알아서 켜고 끔.

| 언제 | 무슨 일이 일어나나 |
| --- | --- |
| staging 에 PR 머지 또는 Actions 수동 실행 | RDS → EC2 순으로 자동 기동(5~8분) → 배포 진행 |
| 배포가 끝난 뒤 60분 | 서버가 스스로 꺼짐 |
| 계속 켜 두고 싶을 때 | Session Manager 로 들어가 `sudo /opt/lore/cancel-power-off.sh` 실행 |
- 손으로 켜고 끄기(비상용): EC2 → `lore-staging` → Instance state → Start / Stop, RDS → `lore-staging-db` → Actions → Start / Stop temporarily. **켤 때는 RDS 먼저, 끌 때는 EC2 먼저**
- RDS 는 꺼 둔 지 7일이 지나면 AWS 가 자동으로 다시 켬

### 4-7. prod 에 무엇이 언제 올라갔나

GitHub 의 해당 PR 화면에서 environment `prod` 표시로 확인. 어떤 PR 이 운영에 반영됐는지 PR 마다 배포 이력이 남음.

## 5. 조심할 것

- 비용 — 전부 켜 두면 월 약 $160, staging 을 꺼 둔 평소는 월 약 $105
- 서버 환경(패키지·서비스 설정)을 손으로 바꿀 땐 **staging 과 prod 둘 다** (코드는 CI 가 맞춰 주지만 환경은 아님)

## 6. 용어

| 용어 | 뜻 |
| --- | --- |
| CloudFront | AWS 의 CDN. 어디서 접속하든 가까운 곳에서 받게 해 주는 중계소이자 HTTPS 입구 |
| ALB | 로드밸런서. 들어온 요청을 경로(`/api/*` 등)에 따라 알맞은 곳으로 나눠 주는 분배기 |
| EC2 | 서버 컴퓨터 한 대 |
| RDS | AWS 가 관리해 주는 데이터베이스 |
| S3 | 파일 창고. 그림·움짤이 들어감 |
| MinIO | 박스 안에서 S3 흉내를 내는 프로그램. dev 전용 |
| Parameter Store | 비밀값(API 키 등) 보관함. 환경별로 서랍이 나뉨 |
| Session Manager | 브라우저에서 서버 안으로 들어가는 터미널. SSH 키가 필요 없음 |
| nginx | 서버 맨 앞에서 요청을 받아 안쪽 앱으로 넘겨 주는 입구 프로그램(역방향 프록시) |
| Let's Encrypt | 무료 HTTPS 인증서 발급처. 만료 전에 자동으로 갱신됨 |
| OIDC | 깃허브 액션이 비밀키를 들고 있지 않고 AWS 역할을 잠깐 맡는 신분증 방식 |