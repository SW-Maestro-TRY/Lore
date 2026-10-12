# 로컬 풀스택 — `scripts/dev.sh`

브랜치(기능)마다 postgres·MinIO·백엔드·프론트가 따로 뜬다. 포트가 겹치지 않고, 어느 포트에 어느 브랜치가 떠 있는지 장부에 남는다.

## 쓰는 법

```bash
scripts/dev.sh up          # 지금 브랜치 스택을 빌드해서 띄움 → 헬스 대기 → 테스트 계정·펫 시드 → 주소 출력
scripts/dev.sh status      # 컨테이너·헬스·포트·마지막 Flyway 버전·접속 주소
scripts/dev.sh ls          # 모든 스택 장부(브랜치·포트·만든 시각·마지막 up·지금 상태·원격 push 여부)
scripts/dev.sh ls --pr     # 위 + 그 브랜치의 PR 번호·상태(gh 필요, 느림)
scripts/dev.sh logs app    # 로그 따라 보기(서비스: postgres minio app web)
scripts/dev.sh down        # 컨테이너만 내림 — DB·MinIO 볼륨은 남아 다음 up 에 같은 계정·펫
scripts/dev.sh reset       # 볼륨·이미지까지 지우고 장부에서 뺌("깨끗이"는 이것 하나. 비대화식은 --yes)
scripts/dev.sh seed        # 시드만 다시(멱등)
scripts/dev.sh up app      # 서비스 일부만(뒤에 이름)
scripts/dev.sh --stack 이름 up   # 브랜치 이름 대신 다른 스택 이름(LORE_STACK=이름 도 같음)
```

준비물: Docker(맥북 Docker Desktop, 맥미니 `colima start`), python3, curl. 첫 `up` 은 Gradle·Next 빌드 때문에 10~20분, 그다음은 캐시로 짧다.

## 스택 이름과 포트

- 스택 이름 = 현재 브랜치 slug. `feat/dev-env-1012` → `feat-dev-env-1012`, compose project 는 `lore-feat-dev-env-1012`.
- 번호 NN 은 01부터 비어 있는 것(장부에 없고 호스트에서도 안 쓰는 포트)을 잡는다. 한 번 잡으면 reset 전까지 그대로다.

| 서비스 | 포트 | 예(NN=01) |
|---|---|---|
| 웹 | 32NN | http://feat-dev-env-1012.localhost:3201 |
| API | 82NN | http://localhost:8201/actuator/health |
| postgres | 54NN | `psql -h localhost -p 5401 -U lore lore` |
| MinIO(S3) | 90NN | http://s3.lore.localhost:9001 |

- 웹은 `<스택>.localhost:32NN` 으로 연다. 쿠키는 포트를 가리지 않아서 `localhost` 로 여러 스택을 열면 로그인이 섞인다. 호스트 이름을 스택마다 달리 하면 쿠키가 따로 산다(`*.localhost` 는 맥이 127.0.0.1 로 푼다). 서명 키(JWT)도 스택마다 달라서 섞여도 남의 스택 사용자로 붙지는 않는다.
- 화면은 `/api` 를 상대경로로 부르고 Next 가 스택 안의 `app:8080` 으로 넘긴다. 그래서 프론트에 API 주소를 따로 넣지 않는다.
- MinIO 는 컨테이너 안에서도 90NN 으로 듣고 `s3.lore.localhost` 별명을 단다. 브라우저와 앱이 같은 주소로 닿으므로 presign 업로드와 앱의 직접 업로드가 둘 다 맞는다.
- 포트는 127.0.0.1 에만 열린다(같은 네트워크의 다른 기기에서 안 보임).

## 장부

`~/.lore-dev/stacks.json`(바꾸려면 `LORE_DEV_HOME`). 스택마다 번호·브랜치·워크트리 경로·포트·만든 시각·마지막 up·상태·커밋. 스택별 env 파일은 `~/.lore-dev/<스택>/.env`(dev.sh 가 만든다, 손으로 고치지 않는다). `dev.sh ls` 예:

```
스택                         브랜치                         웹    API   DB    MinIO 만든시각         마지막up         지금           원격
feat-dev-env-1012            feat/dev-env-1012              3201  8201  5401  9001  2026-10-12 15:26 2026-10-12 16:05 up(4)          push됨, 미push 0
```

## 테스트 계정

`up` 마다 없으면 만들고 있으면 건너뛴다. 볼륨이 남아 있는 한(=reset 전까지) 계속 같다. 값은 `infra/local/env.dev.example`(비밀 아님).

| 계정 | 이메일 / 비번 | 비고 |
|---|---|---|
| 테스트 | `dev@lore.test` / `lore-dev-1234` | 샘플 펫 1마리(가짜 생성 모드로 부화, 돈 안 나감) |
| 관리자 | `admin@lore.test` / `lore-admin-1234` | role=ADMIN, 관리자 화면 확인용 |

로컬 기본값은 `ZZAL_DEV_TOOLS=true`(리모컨 dev API)·`ZZAL_ADMIN=true`. 바꾸려면 `infra/local/.env.dev.local` 에 바꿀 줄만 적는다(커밋 안 됨). `OPENAI_KEY`+`ZZAL_GENERATION_REAL=true` 는 부화마다 과금된다.

## 리셋과 정리

- `down`: 컨테이너만. 메모리를 돌려받고 데이터는 둔다. 브랜치 작업을 쉬는 동안은 이것.
- `reset`: DB·MinIO·작업 폴더 볼륨과 이 스택의 이미지(약 1.5GB)를 지우고 장부에서 뺀다. 브랜치가 머지됐거나 버릴 때.
- 메모리 상한: app 2g·web 1.5g·postgres 512m·minio 512m. 스택 하나가 실제로 쓰는 건 1.5GB 안팎이라 동시에 여러 개를 띄우기보다 안 보는 스택은 `down` 한다.

## dev 박스와의 관계

`infra/local/docker-compose.local.yml` 은 로컬 전용이다. dev.lorecomic.com 은 계속 루트 `docker-compose.yml` + `infra/dev/` 로 배포된다.
