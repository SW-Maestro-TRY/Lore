# 로컬에서 띄우기

노트북에서 서버·화면을 띄우거나, 파이썬 파이프라인을 직접 돌리거나, 로컬에서 그림이
안 뜰 때 읽습니다.

## 띄우기

```
./gradlew bootRun          # 저장소 루트에서. webtoon/ai 동기화까지 자동으로 따라온다
```

- 루트 `.env` 가 bootRun 환경으로 들어갑니다(`DB_USERNAME` · `AWS_PROFILE` ·
  `WEBTOON_API_KEY` 등). 셸에 이미 있는 값이 이깁니다.
- `AWS_PROFILE` 이 없으면 자바 SDK 가 `default` 프로파일을 찾다가 S3 관련 기능이 전부
  실패합니다(캐릭터 목록부터 막힘).
- 화면은 `apps/web` 개발 서버(3000), API 는 8080 입니다.

## 만든 결과는 어디 남나 (#157)

노트북에서 만든 작품은 **한 편의 결과가 폴더에 전부 남습니다.** 나중에 열어 보며
작업하는 자리라서입니다.

| 무엇 | 어디 (저장소 루트 기준) |
| --- | --- |
| 작품 한 편 — 이야기·프롬프트·`meta.json`(단계별 비용)·장 그림 `pages/page01.png`·이어 붙인 `episode.png`·다시 그린 판 `pages/versions/` | `webtoon/ai/work/runs/<run_id>/` |
| 캐릭터 카드 | `webtoon/ai/work/characters/<id>/` |
| MinIO 창고에 올라간 사본 | `~/lore-minio/lore-dev-contents/images/webtoon/` |

- 워크트리에서 띄웠으면 그 워크트리 안의 `webtoon/ai/work/` 입니다(gitignore 라
  원본 저장소와 따로 쌓입니다. [worktree.md](worktree.md)).
- 끝까지 못 간 작품(실패·취소)도 지우지 않습니다.
- **배포 서버는 다릅니다.** 그림 원본은 S3 에 있고 추적은 DB 로 하므로, 올린 뒤
  서버 사본(장 그림·`episode.png`·줄인 사본)을 치우고 글·JSON 만 남깁니다. 끝까지
  못 간 작품 폴더는 7일 뒤 통째로 치웁니다. 다시 그리기·되돌리기 전에는 필요한
  그림을 S3 에서 되받습니다.
- 어느 쪽인지는 설정 없이 정해집니다: 버킷이 없거나 창고 주소(`APP_S3_ENDPOINT`)가
  `localhost`·`127.0.0.1` 이면 남기고, 그 밖은 치웁니다(`RunFiles`). 굳이 바꾸려면
  `LORE_WEBTOON_RUNS_KEEP_FILES=true|false` 를 적습니다.

## 모델 API 키는 어디 있나

**실제 키는 저장소 루트 `.env` 의 `WEBTOON_API_KEY`(OpenAI) 하나뿐입니다.**
`webtoon/ai/{new_harness,story-harness,webtoon-harness}/.env` 는 없고 `.env.example`
만 있습니다.

- **`./gradlew bootRun` 으로 돌릴 때는 자동입니다.** 루트 `.env` 의 값이 자바 환경으로
  들어가고, 자바가 파이썬을 부를 때 그대로 물려주고, `llm.py` 가 이를 `OPENAI_API_KEY`
  로 바꿔 씁니다.
- **`python3 run.py` 를 터미널에서 직접 돌릴 때는 이 변환이 안 일어납니다.** 셸에
  `WEBTOON_API_KEY` 가 없으면 `OPENAI_API_KEY 가 없습니다` 로 바로 멈춥니다
  (2026-09-14 실측). 돌리기 전에 셸에 얹습니다.

  ```
  export WEBTOON_API_KEY=$(grep '^WEBTOON_API_KEY=' <저장소 루트>/.env | cut -d= -f2-)
  ```

  실제 모델을 부르는 실행은 매번 먼저 승인받습니다([dev.md](dev.md)).

## 그림 창고는 환경마다 다르다

| 환경 | 그림이 실제로 있는 곳 |
| --- | --- |
| 내 노트북 | `~/lore-minio/lore-dev-contents/` (아래 절) |
| dev | 박스 안 MinIO (`/images/` → MinIO, nginx 가 이음) |
| staging · prod | 각 환경의 S3 + CloudFront |

코드는 어느 창고인지 몰라야 합니다([dev.md](dev.md) 「그림 주소와 창고」).

## 노트북에서 그림이 안 뜰 때 (2026-09-23)

노트북의 그림 실물은 **`~/lore-minio/lore-dev-contents/`** 에 있고, DB
(`webtoon_page.s3_key`)의 키가 그 폴더 안에 그대로 있습니다. **AWS 버킷 세 개(옛
`lore-contents-…` · staging · prod)에는 이 그림이 하나도 없습니다**(2026-09-23에 키
104개를 전부 대조).

**증상** — 화면은 깨진 그림, 서버 로그에는 에러가 없습니다. 예시 작품 목록도 글자만
나옵니다. `/api/webtoon/v1/runs/<run>/page/<n>` 은 302 를 잘 내주기 때문에 API 만
보면 정상으로 보입니다.

**AWS 로그인 문제가 아닙니다.** `aws sso login --profile lore` 를 해도 그대로입니다.
자격증명이 만료되면 502·500 으로 죽지만, 이 경우는 presign 주소가 멀쩡히 나오고 그
주소를 열면 `NoSuchKey` 가 옵니다.

**어디를 보고 있는지 확인** — presign 주소의 호스트를 봅니다. `…amazonaws.com` 이면
잘못된 창고이고, `localhost:9000` 이어야 합니다.

```
curl -sD - -o /dev/null "http://localhost:8080/api/webtoon/v1/runs/<run_id>/page/1?w=320" | grep -i location
```

### 한 번만 해 두면 되는 것 — 루트 `.env`

```
APP_S3_ENDPOINT=http://localhost:9000
CONTENT_S3_BUCKET=lore-dev-contents
AWS_ACCESS_KEY_ID=lore-minio
AWS_SECRET_ACCESS_KEY=lore-minio-secret
```

- 이 체크아웃의 `.env` 에는 2026-09-23에 넣어 뒀습니다. 새로 체크아웃한 사람은 직접
  넣어야 합니다.
- 키 두 개는 아래 창고의 `--auth-key` 와 같은 값이면 됩니다. 환경변수가
  `AWS_PROFILE` 보다 먼저 읽히므로 `AWS_PROFILE=lore` 줄은 그대로 둬도 됩니다.
- 진짜 AWS 를 봐야 할 때만 이 네 줄을 주석 처리합니다.
- bootRun 할 때 셸에서 이 값들을 덮어쓰지 않습니다.

**이 설정은 webtoon 만의 것이 아닙니다.** `app.s3.content-bucket` 은 zzal 도 같이
써서, 창고를 로컬로 돌리면 zzal 그림도 같은 창고에서 찾습니다. 그래서 zzal·comic
그림(약 130MB)을 옛 버킷에서 `~/lore-minio` 로 내려받아 뒀습니다. 새로 받는 사람은:

```
aws s3 sync s3://lore-contents-046797548177-ap-northeast-2-an/images/zzal/ \
  ~/lore-minio/lore-dev-contents/images/zzal/
```

### 창고는 rclone 으로 띄운다 — 서버 띄우기 전에 매번

`.env` 만 고치고 창고를 안 띄우면 그림 대신 연결 거부가 납니다.

```
rclone serve s3 ~/lore-minio --addr 127.0.0.1:9000 \
  --auth-key lore-minio,lore-minio-secret --force-path-style
```

- `~/lore-minio` 아래 폴더 이름이 그대로 버킷이 됩니다(`lore-dev-contents`).
- 없으면 `brew install rclone`.
- **MinIO 는 이 맥에서 안 됩니다.** brew 로 깐 것이 뜨자마자 SIGSEGV 로 죽습니다
  (go-m1cpu 가 macOS 26 에서 터짐). upstream 이 archived 라 재설치·업그레이드로 안
  고쳐집니다. rclone 은 presigned GET 과 업로드(PUT) 둘 다 됩니다(2026-09-23 확인).

보기만 할 거면 정적 서버로도 됩니다. 다만 업로드가 안 되고, `python3 -m http.server`
는 한 번에 하나만 받아서 표지를 여러 장 동시에 부르는 목록 화면이 멈추니 스레드
버전을 써야 합니다.
