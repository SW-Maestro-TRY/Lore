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

## 개발 리모컨(여울 방의 「리모컨」 탭)

서버 상태를 진짜로 바꾸는 리모컨이다. 화면에 덮어쓰지 않고 dev API 가 DB 값을 바꾼 뒤 그 서버 상태를 그린다 — 새로고침해도 남고, 시계를 넘기면 운영 규칙대로 이어진다(똥 4 → 6시간 → 더러움 병 → 케어 미스 → 떠남 예고 → 여행). 범위는 `remote-control-spec-1011`(상태 41·이벤트 47 전수표).

### 켜는 법

- 서버: `ZZAL_DEV_TOOLS=true`(로컬 스택 기본값). 꺼져 있으면 아래 주소가 전부 404 이고 빈도 안 뜬다(`DevToolsOffIT`).
- 화면: `GET /api/zzal/v1/dev/ping` 이 200 이면 방 오른쪽 가장자리에 「리모컨」 탭이 뜬다. 도메인으로 가르지 않는다 — 로그인 전(401)·꺼진 서버(404)면 안 뜬다.
- 탭: 상태(값 세팅) · 이벤트(트리거) · 시계(방문 없이 넘기기 포함) · 굽기 · 화면(그림만 보는 손잡이 — 서버 안 바뀜). 누를 때마다 서버가 돌려준 "이번에 일어난 일"(`effects[]`)이 위에 토스트로 뜬다.

### 엔드포인트(모두 `app.zzal.dev-tools=true` 일 때만 존재, 내 펫만 — 남의 펫 404)

| 주소 | 하는 일 |
|---|---|
| `GET /api/zzal/v1/dev/ping` | 리모컨 노출 판정. `{devTools:true, generationReal}` |
| `GET·PATCH /api/zzal/v1/dev/pets/{id}/state` | 내부 칸 읽기 / 칸 값 직접 세팅(부분 갱신, 범위 밖이면 400 이고 아무것도 안 바뀜, 연쇄는 안 돌림) |
| `POST /api/zzal/v1/dev/pets/{id}/events/{kind}?arg=` | 운영 전이 메서드 실행(재우기·발병·떠남·부르기·게임 승·조각 완주·선물 도착·놓아주기 등 41종) |
| `POST .../advance-clock`·`.../set-clock` `{..., visit:false}` | 시계 이동. `visit:false` 면 방문으로 안 쳐서 하루씩 밀어도 결석 떠남이 난다. 응답에 `effects[]` |
| `POST /api/zzal/v1/dev/pets/{id}/bake {kind, real, seq}` · `GET .../bake/{jobId}` | 부화(다시)·2층·선물 한 판을 운영 길로 굽기 + 진행·비용 조회 |

응답은 `{effects, before, after, state}` — `after` 는 방문 없이 정산한 화면 상태다(다시 조회하면 그 조회가 방문이라 떠남 예고가 취소된다).

### 진짜 굽기 조건

- 그림 클라이언트는 서버 전체에 하나(`ZZAL_GENERATION_REAL`). 요청의 `real` 은 "진짜인 줄 안다" 는 확인이고 서버 스위치와 다르면 409 — `real:true` 인데 꺼져 있으면 "과금 스위치 꺼짐", `real:false` 인데 켜져 있으면 가짜로 못 굽는다고 거절.
- 예상 비용: 부화 $0.19(+ 뒤따르는 2층 격자 약 $0.09), 2층·선물 한 판 약 $0.09. 하루 상한은 없다(결정 1). 스위치를 켜는 것은 과금 설정이라 사람이 직접 한다.

### curl 한 바퀴(로컬 스택, 테스트 계정)

```bash
API=http://localhost:8201; J=/tmp/lore-dev.cookies
curl -s -c $J -H 'Content-Type: application/json' -d '{"email":"dev@lore.test","password":"lore-dev-1234"}' $API/api/v1/auth/login >/dev/null
PET=$(curl -s -b $J $API/api/zzal/v1/me/pets | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"][0]["petId"])')
curl -s -b $J $API/api/zzal/v1/dev/ping
curl -s -b $J -X PATCH -H 'Content-Type: application/json' -d '{"trash":4}' $API/api/zzal/v1/dev/pets/$PET/state
curl -s -b $J -X POST "$API/api/zzal/v1/dev/pets/$PET/events/night-settle"
curl -s -b $J -H 'Content-Type: application/json' -d '{"minutes":1440,"visit":false}' $API/api/zzal/v1/dev/pets/$PET/advance-clock
```
