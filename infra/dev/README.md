# Lore 개발 스택 (docker compose)

RDS→postgres, S3→minio 로 통째로 대체해 **외부 AWS 자원 없이 앱 전체**(DB·스토리지·백엔드·프론트)를
한 번에 띄운다. 같은 compose 가 개발자 노트북과 dev EC2 양쪽에서 돌고, 차이는 `.env` 하나뿐이다.

## 준비

```bash
cp infra/dev/.env.example infra/dev/.env   # 값 채우기 (노트북/박스 안내는 파일 주석 참고)
```

## 실행 — 노트북

nginx 없이 컨테이너를 localhost 로 직접 부른다.

```bash
docker compose --env-file infra/dev/.env up --build
```

- 프론트: http://localhost:3000  (프론트의 `/api` 호출은 Next rewrite 로 app:8080 에 프록시된다)
- 백엔드: http://localhost:8080/actuator/health
- MinIO 콘솔: http://localhost:9001  (S3 API 는 9000)

## 실행 — dev EC2

override 가 nginx(80/443·TLS)를 얹어 한 도메인 아래로 묶는다.

```bash
docker compose -f docker-compose.yml -f docker-compose.dev-ec2.yml --env-file infra/dev/.env up -d --build
```

- `.env` 에서 `S3_PUBLIC_URL=https://dev.lorecomic.com`, `COOKIE_SECURE=true` 로 바꾼다.
- 인증서는 호스트의 certbot 이 발급/갱신하고(webroot `/var/www/certbot`), nginx 는 `/etc/letsencrypt` 를
  읽기 전용으로 마운트만 한다. 첫 발급은 스택을 올리기 전(또는 80 포트로 챌린지가 뜬 상태)에 한 번 해 둔다.

## nginx → minio 를 왜 이렇게 두나

presigned URL 은 **서명이 주소(호스트+경로)에 묶인다.** MinIO 에 `MINIO_SERVER_URL=${S3_PUBLIC_URL}` 을
주면 MinIO 는 그 공개 주소 기준으로 서명을 계산한다. 그래서 dev EC2 에서는 nginx 가 `/lore-content/` 를
minio 로 넘길 때 **Host 헤더를 그대로 보존**해야 서명이 맞는다(안 그러면 업로드·서빙이 403). 콘텐츠 버킷
경로(`/lore-content/`)는 `.env` 의 `CONTENT_BUCKET` 과 반드시 같아야 한다 — nginx 는 env 치환을 하지 않는다.

## 알아둘 것

- 노트북에서 `S3_PUBLIC_URL=http://localhost:9000` 은 브라우저 기준 주소다. 브라우저→MinIO presigned
  업로드/다운로드는 정상이지만, **앱이 서버 안에서 직접 올리는 산출물**(실제 zzal 생성의 시트·격자·움짤)은
  컨테이너 안의 `localhost` 가 minio 가 아니라 자기 자신을 가리켜 닿지 않는다. 실제 생성 개발이 필요하면
  dev EC2(공개 도메인이 컨테이너에서도 같게 풀림)에서 돌리는 것을 권장한다. 노트북은 UI·API 개발용.
- 슬림 JRE 이미지라 파이썬이 없다. `app.zzal.generation.real-postprocess` 를 켜면 numpy·scipy·pillow 가
  필요하니, 그때는 파이썬을 심은 이미지가 따로 있어야 한다(기본값은 꺼짐).
