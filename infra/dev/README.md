# Lore 개발 스택 (docker compose)

RDS→postgres, S3→minio 로 통째로 대체해 **외부 AWS 자원 없이 앱 전체**(DB·스토리지·백엔드·프론트)를
한 번에 띄운다. 같은 compose 가 개발자 노트북과 dev EC2 양쪽에서 돌고, 차이는 `.env` 하나뿐이다.

## 로컬에서 띄우기 (노트북)

준비물 — **Docker Desktop**(compose v2 포함). 컨테이너에 **메모리 4GB 이상**을 주도록 설정해 둔다.
Next 빌드와 Gradle 빌드가 컨테이너 안에서 돌아 메모리가 모자라면 빌드가 조용히 죽는다.

```bash
cp infra/dev/env.example infra/dev/.env          # 값은 그대로 둬도 된다(노트북 기본값)
docker compose --env-file infra/dev/.env up --build -d
```

- 화면: **http://localhost:3100**  ← 여기 하나만 열면 된다
- 백엔드: http://localhost:8080/actuator/health  (화면의 `/api` 호출은 Next rewrite 로 app:8080 에 간다)
- MinIO 콘솔: http://localhost:9001  (S3 API 는 9000)

**첫 빌드는 오래 걸린다** — npm ci + Next 빌드 + Gradle 빌드를 컨테이너 안에서 처음부터 한다.
노트북 사양에 따라 **10~20분**을 잡아 두고, 두 번째부터는 레이어 캐시로 훨씬 짧다.
진행 상황은 `docker compose logs -f app web` 으로 본다.

내릴 때는 `docker compose --env-file infra/dev/.env down` (볼륨까지 비우려면 `-v`).
`.env` 는 **절대 커밋하지 않는다** — `.gitignore` 에 있고 팀 훅이 한 번 더 막는다.

## 그림은 어디서 오나 (씨뿌리기)

MinIO 는 빈 통으로 뜬다. 그런데 화면은 배경 16종·소품·여울 시연·온보딩 가이드·랜딩 썸네일이
버킷에 있다고 가정하고 `/images/zzal/...` 를 부른다 → 안 채우면 전 화면이 깨진 그림이다.

그래서 첫 기동에 **staging CloudFront(공개)에서 같은 키를 그대로 받아 MinIO 에 넣는다.**

- 목록: `infra/dev/seed-assets.txt` — **정적 자산 118개**(`assets` `bg` `demo` `landing` `onboarding`).
  사용자 업로드·펫 그림은 **안 받는다**(사람이 만들어야 나오는 것).
- 순서: `minio-init`(버킷 생성) → `seed-fetch`(curl 로 내려받기) → `minio-seed`(mc 로 업로드).
- **AWS 자격이 필요 없다.** 공개 CloudFront 주소만 쓴다(`SEED_BASE_URL`).
- **멱등**: 이미 받은 파일·이미 있는 키는 건너뛴다. 다시 올려도 안전하다.
- **오프라인이어도 스택은 뜬다**: 내려받기가 실패하면 경고만 찍고 넘어가고, 그림만 빈다.
  네트워크가 돌아온 뒤 다시 올리면 빠진 것만 채워진다.

## 실행 — dev EC2

override 가 nginx(80/443·TLS)를 얹어 한 도메인 아래로 묶는다.

```bash
docker compose -f docker-compose.yml -f docker-compose.dev-ec2.yml --env-file infra/dev/.env up -d --build
```

- 박스의 `.env` 는 `/opt/lore-dev/.env` 에만 있고 **배포가 절대 덮지 않는다.**
  `S3_PUBLIC_URL=https://dev.lorecomic.com`, `COOKIE_SECURE=true`,
  `ZZAL_SHARE_BASE_URL=https://dev.lorecomic.com/zzal/s` 로 둔다(`env.example` 의 「박스:」 표시 참고).
- 인증서는 호스트의 certbot 이 발급/갱신하고(webroot `/var/www/certbot`), nginx 는 `/etc/letsencrypt` 를
  읽기 전용으로 마운트만 한다. 첫 발급은 스택을 올리기 전(또는 80 포트로 챌린지가 뜬 상태)에 한 번 해 둔다.
- 실제 배포는 사람이 손으로 치지 않는다 — `develop` 에 푸시하면 CI 가 `infra/dev/deploy-dev.sh` 를 박스에서 돌린다.

## 주소가 갈리는 자리 — `/images/` 와 `/lore-content/`

화면은 그림을 `/images/zzal/...` 로 부른다(`zzal/fe/lib/assets.ts` 의 `NEXT_PUBLIC_CDN_BASE` 기본값 `/images`).
같은 주소가 환경마다 다른 것이 받는다.

| 환경 | `/images/...` 를 받는 것 |
|---|---|
| 노트북(base) | Next rewrite (`IMAGE_PROXY` 빌드 인자) → `minio:9000/<버킷>/images/...` |
| dev EC2 | nginx `location /images/` → `minio:9000/lore-content/images/...` |
| staging·prod | CloudFront `/images/*` → S3 |

`/lore-content/` 는 **업로드 쪽**이라 따로 남겨 둔다(presigned PUT).

## nginx → minio 를 왜 이렇게 두나

presigned URL 은 **서명이 주소(호스트+경로)에 묶인다.** MinIO 에 `MINIO_SERVER_URL=${S3_PUBLIC_URL}` 을
주면 MinIO 는 그 공개 주소 기준으로 서명을 계산한다. 그래서 dev EC2 에서는 nginx 가 `/lore-content/` 를
minio 로 넘길 때 **Host 헤더를 그대로 보존**해야 서명이 맞는다(안 그러면 업로드·서빙이 403). 콘텐츠 버킷
경로(`/lore-content/`·`/images/`)는 `.env` 의 `CONTENT_BUCKET` 과 반드시 같아야 한다 — nginx 는 env 치환을 하지 않는다.

## 알아둘 것

- 노트북에서 `S3_PUBLIC_URL=http://localhost:9000` 은 브라우저 기준 주소다. 브라우저→MinIO presigned
  업로드/다운로드는 정상이지만, **앱이 서버 안에서 직접 올리는 산출물**(실제 zzal 생성의 시트·격자·움짤)은
  컨테이너 안의 `localhost` 가 minio 가 아니라 자기 자신을 가리켜 닿지 않는다. 실제 생성 개발이 필요하면
  dev EC2(공개 도메인이 컨테이너에서도 같게 풀림)에서 돌리는 것을 권장한다. 노트북은 UI·API 개발용.
- 화면 포트가 **3100** 인 이유 — `application.yml` 의 `app.zzal.share.base-url` 기본값이
  `http://localhost:3100/zzal/s` 다. 3000 으로 띄우면 로컬에서 만든 공유 링크가 죽은 주소를 가리킨다.
- `ZZAL_GENERATION_REAL=true` 는 **부화 한 바퀴마다 과금**된다. 기본은 꺼진 채로 둔다.
- 이미지 안에 파이썬 가상환경이 **둘** 있다 — 짤 후처리용 `/opt/lore/venv`(numpy·scipy·pillow)와
  웹툰 하네스용 `/opt/lore/venv-webtoon`(openai·google-genai·PyYAML·boto3). staging·prod 박스와 같은 배치다.
  앱에는 `ZZAL_PYTHON_BIN`·`LORE_WEBTOON_PYTHON_BIN` 으로 각각 알려 준다. 웹툰 만들기를 실제로 돌리려면
  `.env` 의 `WEBTOON_API_KEY`·`LORE_WEBTOON_INTERNAL_TOKEN` 을 채운다(둘 다 비어 있으면 만들기만 멈추고 스택은 뜬다).
