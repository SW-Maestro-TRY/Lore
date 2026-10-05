# 예시 작품 올리는 법 — 이어서 할 때 보는 순서

> 둘러보기에 깔리는 예시 작품을 각 환경(dev · staging · 운영)에 올리고, 빼고, 순서를 정하는 법.
> 번들 모양 · 관리자 API 의 자세한 규칙은 [images.md](images.md) 「예시 작품 — 번들과 관리자 API」.
> 마지막 갱신 2026-10-04.

## 지금 상태 (2026-10-04)

| 환경 | 둘러보기에 보이는 예시 | 비고 |
|---|---|---|
| **운영** | **17편** | 번들 18편 중 「서고의 여왕」 제외. 순서는 아직 안 정함(화면 기본 「최신순」) |
| staging | 17편 | 운영과 같은 17편. 예시 표시만 남은 비공개 3편(대신 전하는 마음 · 대리인의 밤 · 집안의 대장, 시하)과 내린 「서고의 여왕」이 있음 |
| dev | 18편 | 번들 18편. 비공개 예시 3편이 더 있음 |

번들 원본 18편은 하은 노트북의 `~/lore-example-bundles/` 에 있다(목록 · 크기는 그 폴더의 `README.md`).
**저장소에 넣지 않는다** — 사용자 입력이 들어 있다. 18편을 한눈에 보는 페이지는
`haeun/example-works-2026-10-04.html`.

첫 화면의 「완성 예시」는 「가면 아래의 조건」(`20260910T132240-ae8c28`)으로 고정돼 있다
(`webtoon/fe/screens/landing/Landing.tsx` 의 `DONE_EXAMPLE_RUN_ID`). **이 작품은 빼면 안 된다.**

## 준비물

1. **관리자 계정** — 환경마다 `lore-admin@example.invalid`. 비밀번호는 `webtoon/account/admin-accounts.txt`
   (git 에 안 올라가는 폴더 — `.git/info/exclude`). 도구는 같은 폴더의 `admin-accounts.env` 를 읽는다.
2. **AWS 로그인** — 관리자 승격 · DB 확인은 SSM 으로 서버 안에서 한다. 만료됐으면
   `aws sso login --profile lore --use-device-code`.
3. **staging 은 평소 꺼져 있다** — staging 에 PR 을 머지하면 켜지고 배포가 끝난 뒤 **60분 뒤 꺼진다**. 그 안에 한다.

## 도구 — `webtoon/tools/examples/`

모두 첫 인자가 환경 이름(`dev` · `staging` · `prod`)이다.

| 도구 | 하는 일 |
|---|---|
| `make-admin.sh <env>` | 관리자 계정 가입 + role 을 ADMIN 으로(이미 있으면 승격만 다시) |
| `list.sh <env>` | 그 환경의 예시 목록(순서 · 공개 · 쪽 · 작품 번호 · 제목) |
| `push.sh <env> dry\|real [zip…]` | 번들 올리기. `dry` 는 검사만. zip 을 안 주면 `~/lore-example-bundles/*.zip` 전부 |
| `set.sh <env> <run_id> example\|public\|private\|order N\|down` | 예시 지정 · 공개 · 비공개 · 순서 · 내리기 |
| `pull.sh <env> <run_id> [out.zip]` | 그 환경의 작품 하나를 번들로 내려받기 |
| `db-sql.sh <env> "<SQL>" [--write]` | 그 환경 DB 에 SQL(기본 읽기 전용) |

`push.sh` 결과 읽는 법: `PLANTED` 심음 · `EXISTS` 같은 작품 번호가 이미 있어 **아무것도 안 함**(예시 표시도
안 켬) · `DRY_RUN` 검사 통과.

## 자주 하는 일

### 1. 새 환경을 처음 채운다 (운영을 처음 채운 2026-10-04 순서)

```bash
cd webtoon/tools/examples
./make-admin.sh prod                     # 계정이 없으면. 「가입 200」 · role ADMIN 확인
./push.sh prod dry $(ls ~/lore-example-bundles/*.zip | grep -v 20260916T175734-587d92)   # 서고의 여왕 빼고 검사
./push.sh prod real $(ls ~/lore-example-bundles/*.zip | grep -v 20260916T175734-587d92)  # 실제로
./list.sh prod                           # 17편 · 공개 17편
```

그다음 둘러보기(`<주소>/webtoon?view=works`)에서 보이는지, 첫 화면 「완성 예시」가 뜨는지 본다.

### 2. 새 작품을 예시로 더한다

- **그 작품을 만든 환경**이면 그림을 옮길 필요 없이: `./set.sh <env> <run_id> example`
- **다른 환경**에서 만든 작품이면:
  ```bash
  ./pull.sh dev <run_id>                 # ~/lore-example-bundles/<run_id>.zip 으로 받음
  ./push.sh prod dry ~/lore-example-bundles/<run_id>.zip
  ./push.sh prod real ~/lore-example-bundles/<run_id>.zip
  ```
  받은 번들은 `~/lore-example-bundles/README.md` 표에도 한 줄 적는다.

### 3. 한 편 뺀다

`./set.sh <env> <run_id> down` — 예시 해제 + 비공개. 작품과 그림은 지우지 않는다(다시 `example` 로 되살릴 수
있다). 2026-10-04 staging 에서 「서고의 여왕」을 이렇게 뺐다.

### 4. 둘러보기 순서를 정한다

`./set.sh <env> <run_id> order 1` — 작을수록 앞. 순서를 안 준 작품은 그 뒤에 온다. 관리자 화면에서도 칸에
숫자를 넣고 칸을 벗어나면 저장된다.

### 5. 관리자 화면으로 한다

`<주소>/webtoon?view=admin-examples` (관리자 계정으로 로그인, 마이페이지 설정 칸의 「예시 작품 관리」).
번들 여러 개를 골라 「검사만 하기」를 켠 채 먼저 돌리고, 끈 뒤 올린다. 공개 스위치 · 순서 칸 · 번들 내보내기 ·
내리기가 같은 화면에 있다.

## 막힐 때

| 증상 | 원인 · 할 일 |
|---|---|
| `관리자 로그인 실패(401)` | 그 환경에 계정이 없다 → `make-admin.sh <env>` |
| `make-admin.sh` 의 승격 단계가 막힘 | Claude Code 자동 권한 검사가 공유 서버 DB 의 관리자 승격을 막을 수 있다 — 사람이 직접 실행(`! bash …`) |
| `Token has expired` | `aws sso login --profile lore --use-device-code` |
| `EXISTS` 인데 예시로 안 보인다 | 같은 작품 번호가 이미 있어 심지 않았고, 예시 표시도 안 켰다 → `set.sh <env> <run_id> example` |
| 그림만 바꾸고 싶다 | 다시 올려도 `EXISTS` 라 안 바뀐다 → 그 작품의 `webtoon_page` 줄을 지우고 다시 올리거나 새 작품 번호로 |
| 본문으로 올리면 403 | 운영 · staging 은 CloudFront WAF 가 큰 본문을 막는다 — `push.sh` 는 S3 로 직접 올리는 길을 쓴다 |
| staging 이 응답하지 않음 | 꺼져 있다 — staging 에 PR 을 머지하면 켜진다(60분) |

## 기록

- 2026-10-03 — 번들 18편 만듦(`~/lore-example-bundles`), dev 에 7편 새로 심음
- 2026-10-04 — staging: 관리자 계정, 5편 새로 심음, 배달통 · 카페 사장 예시 지정, 서고의 여왕 내림 → 공개 17편.
  운영: #635 배포 뒤 관리자 계정, 17편 심음(서고의 여왕 제외), 표지 · 2쪽 · 결과 화면 17편 모두 열림 확인
