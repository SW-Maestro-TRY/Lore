# 화면에 이미지 넣는 법

웹툰 화면에 보이는 그림이 어디서 오는지, 새 그림을 넣거나 바꾸려면 무엇을
고쳐야 하는지 정리한 문서입니다.

넣는 자리가 갈래마다 다릅니다. 예시 웹툰은 정적 파일이고, 예시 캐릭터는
DB 시드라 백엔드를 다시 띄워야 하고, 둘러보기 카드는 둘이 섞여서 나옵니다.
그래서 "이미지를 바꾼다"는 요청이 와도 어느 갈래인지부터 가려야 합니다.

## 먼저 알아야 할 두 가지

### 1. 원본 폴더에 넣습니다 — `apps/web/public/static/` 이 아니라

정적 그림의 원본은 도메인 폴더에 있고, 빌드가 `apps/web/public/static/` 으로
복사합니다. `apps/web/package.json:7,9` 의 `predev`/`prebuild` 가
`webtoon/fe/sync-landing.sh` 를 부릅니다.

| 원본 폴더 | 화면에서의 주소 |
| --- | --- |
| `webtoon/ai/assets/lou/` | `/static/lou/…` |
| `webtoon/ai/assets/samples/` | `/static/samples/…` |
| `webtoon/fe/static/badges/` | `/static/badges/…` |
| `webtoon/fe/static/gallery/` | `/static/gallery/…` |
| `common/fe/assets/landing/` | `/static/landing/…` (루트 랜딩 `/`, `common/` 담당) |

`webtoon/fe/sync-landing.sh:38-43` 에 이 목록이 있습니다. `lou` 와 `samples`
가 `webtoon/fe` 가 아니라 `webtoon/ai/assets` 에 있는 이유는 파이썬 파이프라인과
자바도 같은 파일을 읽기 때문입니다 — 사본을 뜨지 말고 이 자리를 고치세요.

**`apps/web/public/static/` 에 직접 넣으면 안 됩니다.** `.gitignore:49` 에
걸려 커밋도 안 되고, 다음 `npm run dev`/`build` 때 `rsync --delete` 로
지워집니다. 개발 서버를 켜 둔 채 원본을 고쳤다면 `bash webtoon/fe/sync-landing.sh`
를 한 번 돌려야 반영됩니다.

### 2. 이미지 주소에 도메인을 붙이지 않습니다

S3 에 올라간 그림의 주소는 언제나 `/` 로 시작하는 상대경로입니다
(`webtoon/be/.../art/PageStore.java:245-285`). 예외는 비공개 그림으로,
키가 `private/webtoon/` 으로 시작하면 presigned GET 주소가 나갑니다
(`webtoon/be/.../art/PrivateArt.java:28,31,136-141`, 기본 10분).

로컬에서 그림이 안 뜨면 `.env` 에 `LORE_WEBTOON_PRESIGN_LOCALLY=true` 를
넣으세요.

---

## 갈래별로 넣는 법

### 예시는 둘 다 "저장소에 그림, 부팅 때 심기" 입니다

예시 작품과 예시 캐릭터는 같은 방식으로 돕니다. **그림은 저장소에 두고,
서버가 뜰 때 그 환경의 창고에 올린 뒤 DB 에 심습니다.**

```
webtoon/ai/assets/examples/<run_id>/   예시 작품 (쪽 그림 + meta.json)
webtoon/ai/assets/samples/ex-*.jpg     예시 캐릭터 (그림 한 장)
```

**심는 것은 한 번뿐입니다.** 이미 심긴 것은 건드리지 않습니다 — 그래서
심은 뒤 **DB 에서 고친 것이 다음 기동에 안 되돌아갑니다.** 예시를 다듬는
자리는 시드 목록이 아니라 DB 입니다(아래 「DB 에서 고치기」).

창고는 환경마다 다릅니다(노트북·dev 는 MinIO, staging·prod 는 각자 S3).
넣는 쪽은 어느 것인지 알 필요가 없습니다.

### 예시 웹툰 — 한 편 추가하기

1. `webtoon/ai/assets/examples/<run_id>/` 를 만듭니다. 폴더 이름이 그대로
   작품 번호로 쓰입니다(겹치지만 않으면 됩니다).
2. 쪽 그림을 **`pNN-w320.jpg` 와 `pNN-w1080.jpg` 두 폭으로** 넣습니다.
   - **두 폭 다 필요합니다.** 목록 카드가 320 을, 본문이 1080 을 부르는데
     **없는 폭은 대체하지 않고 404** 가 됩니다(`PageStore.java:114-123`).
   - 원본(`w0`)은 넣지 않습니다. 다시 그릴 때만 쓰는 것이라 예시에는 필요
     없고, 용량이 몇 배가 됩니다(아홉 편 기준 18MB 대 115MB).
3. 같은 폴더에 `meta.json` 을 둡니다:

   ```json
   {
     "title": "대리인의 밤",
     "genre": "스릴러",
     "character": "제하",
     "style": "frost",
     "logline": "밤에만 여는 상담소에 오늘은 주인이 오지 않는다…",
     "captions": ["2쪽 설명", "3쪽 설명"]
   }
   ```

   - `captions` 는 **2쪽부터** 차례로 붙습니다(1쪽은 표지라 설명이 없습니다).
     없으면 설명만 빕니다.
   - `style` 은 그림체 키입니다(`romance_fantasy` · `frost` · `noir` 등).
     모르는 값이면 카드에 그림체 이름이 안 뜹니다.
4. 서버를 다시 빌드·기동합니다.

**한 편 빼기**: 폴더를 지우는 것으로는 **안 없어집니다** — 이미 DB 에 심겨
있기 때문입니다. 비공개로 돌리거나 DB 에서 지웁니다. 폴더도 같이 지우면
다음에 빈 DB 로 시작할 때 안 심깁니다.

**그림만 바꾸기**: 파일을 바꿔도 이미 심긴 작품은 안 올라갑니다. 그 작품의
`webtoon_page` 줄을 지우고 다시 띄우면 새 그림으로 올라갑니다.

심는 코드: `webtoon/be/src/main/java/com/lore/webtoon/work/ExampleWorks.java`

### 예시 캐릭터 — 추가·교체하기

1. 그림을 `webtoon/ai/assets/samples/ex-<이름>-1.jpg` 로 넣습니다.
2. `BuiltinCharacters.java` 의 `SEEDS` 에 `new Seed(파일명, 이름, 설명)` 을
   한 줄 추가합니다. **이름과 설명만 씁니다** — 카드 설정(세계관·한 줄 반전
   등)은 시드에 넣지 않고, 필요하면 심은 뒤 DB 에서 붙입니다.
3. 서버를 다시 빌드·기동합니다.

**빼기**: `SEEDS` 에서 줄을 지워도 **DB 에서는 안 지워집니다**(기동할 때
로그로만 알려 줍니다). DB 에서 직접 지웁니다:
`delete from webtoon_character where source='BUILTIN' and name='…';`

**그림만 바꾸기**: 같은 파일명으로 덮어쓰고, 그 캐릭터의 `art_key` 를 비운
뒤 다시 띄웁니다 — 그림이 비어 있는 줄은 시더가 다시 채웁니다.
`update webtoon_character set art_key=null where name='…';`

**그림체 고르개 썸네일**(`webtoon/fe/lib/styleThumbs.ts:17-24`)은 예시
캐릭터와 **별개**입니다. `ex-webtoon-1.jpg` 같은 옛 견본 파일을 아직 그쪽이
쓰고 있으니 지우지 마세요.

### DB 에서 고치기 — 예시를 다듬는 자리

심은 뒤에는 DB 가 원본입니다. 서버를 다시 띄워도 안 되돌아갑니다.

**캐릭터 이름·설명 바꾸기**

```sql
update webtoon_character
   set name = '시연',
       description = '연습실 불을 마지막으로 끄는 사람.',
       updated_at = now()
 where source = 'BUILTIN' and name = '세아';
```

**캐릭터에 카드 설정 붙이기** — `twist` 가 차면 「카드 보기」가 생깁니다
(`WebtoonCharacter.java:201-203`).

```sql
update webtoon_character
   set world = 'modern', world_label = '현대 드라마', genre = '드라마',
       role_name = '연습생',
       twist = '무대에 서는 날, 거울이 먼저 깨진다',
       quote = '나는 아직 아무것도 아니야.',
       fate = E'데뷔조에서 밀려난다\n대신 무대를 완성한다',
       updated_at = now()
 where name = '시연';
```

| 칸 | 화면에서 |
| --- | --- |
| `twist` | 카드 큰 제목 · 목록의 한 줄 요약 |
| `role_name` | 제목 아래 역할 |
| `genre` | 이름과 나란히 |
| `world_label` | 세계관 배지 |
| `quote` | 대사 |
| `fate` | 운명 줄들 (줄바꿈으로 나눔) |
| `world` | 이 캐릭터로 1화 만들 때 넘어감 |
| `style` | **지금은 화면에서 안 씁니다** |

**작품 제목·장르·로그라인 바꾸기** — 「고른 이야기」 줄을 고칩니다.

```sql
update webtoon_story
   set title = '새 제목', genre = '스릴러', plot = '새 로그라인'
 where run_id = '20260909T153021-9e927b' and chosen;
```

**작품 감추기**

```sql
update webtoon_work set is_public = false where run_id = '…';
```

### 온보딩·홈의 목업 그림

전부 `/static/samples/` 의 정적 파일이고, 상수 네 개로 모입니다
(`webtoon/fe/screens/landing/EditorMock.tsx:13-16`):

| 상수 | 파일 | 쓰이는 곳 |
| --- | --- | --- |
| `PAGE_IMG` | `onboarding-page.jpg` | 03 컷 칸, 니즈 카드 |
| `SHEET_IMG` | `onboarding-sheet.png` | 02 캐릭터 칸 |
| `REGEN_IMG` | `onboarding-regen.jpg` | 다시 그리기 설명 |
| `MONGI_IMG` | `ex-mongi-1.jpg` | 예시 캐릭터 소개 |

**같은 파일명으로 덮어쓰면 코드를 안 고쳐도 됩니다.** 파일명을 바꾸려면
위 상수만 고치면 화면 전체에 반영됩니다.

그 밖의 낱개 그림: 마스코트 알약 `Landing.tsx:142`, 마지막 CTA
`Landing.tsx:321`, 푸터 배지 `Landing.tsx:348-352`.

### 둘러보기에 실제 작품 띄우기

작품을 만들고 공개로 돌리면 코드 수정 없이 뜹니다. 공개 토글은 화면의
버튼(`webtoon/fe/screens/works/Works.tsx:195`)이고, 서버는
`WebtoonWork.isPublic = true` 인 것만 내려줍니다
(`webtoon/be/.../WebtoonWorkRepository.java:49-54`).

표지는 **가진 페이지 중 첫 번째 쪽**이 자동으로 쓰입니다
(`webtoon/be/.../runs/RunService.java:143`). 다른 쪽을 표지로 쓰고 싶으면
서버를 고쳐야 합니다 — 프론트는 서버가 준 `cover_page` 를 그대로 씁니다.

---

## 하드코딩된 자리 (예시 작품을 갈아엎을 때 같이 고칠 곳)

예시 작품의 `run_id` 를 코드에 직접 적어 둔 곳이 두 군데입니다. 그 작품을
빼면 **오류 없이 조용히 빈칸**이 되니 반드시 같이 고치세요.

| 자리 | 파일:줄 | 지금 값 |
| --- | --- | --- |
| 입구 화면 카드 2장 | `webtoon/fe/screens/landing/Entry.tsx:12-13` | `20260903T174524-309e57`, `20260906T124144-63e2a7` |
| 온보딩 04 「완성」 칸 | `webtoon/fe/screens/landing/Landing.tsx:38` | `20260910T132240-ae8c28` |

## staging·prod 에서 그림이 깨지면 ★

**로컬 문제와 원인이 다릅니다.** 로컬은 노트북 전용 설정(`IMAGE_PROXY`·
`APP_S3_ENDPOINT`·MinIO 대체)이 문제지만, staging·prod 는 처음부터 진짜
S3 + CloudFront 라 그 종류의 사고가 구조적으로 없습니다. 여기서 그림이
깨진다면 다음 중 하나입니다.

1. **버킷 이름이 잘못됐다** — `CONTENT_S3_BUCKET`(SSM `/lore/<env>/...`)이
   그 환경의 진짜 버킷(`lore-contents-staging-…` / `lore-prod-contents-…`)을
   가리키는지 확인합니다. 옛 버킷(`lore-contents-046797548177-…`)을 보고
   있으면 새 그림이 전부 안 뜹니다.
2. **CloudFront 캐시** — 같은 이름의 파일을 덮어써도 바로 안 바뀔 수
   있습니다(위 4-3절). 새 파일은 새 이름(폴더 버전)으로 올리는 게 원칙이고,
   급하면 CloudFront invalidation을 겁니다.
3. **presign 설정이 dev 값으로 새어 들어감** — `LORE_WEBTOON_PRESIGN_LOCALLY`
   나 `APP_S3_ENDPOINT` 가 staging·prod SSM 에 실수로 들어가면(로컬 편의
   설정을 그대로 복사한 경우) 실제 S3 대신 존재하지 않는 주소를 보게 됩니다.
   이 두 값은 SSM 에 **있으면 안 됩니다**(`webtoon/docs/env-diff.md` 3-4절).
4. **IAM/버킷 정책** — 공개 그림(`images/` 접두사)에 대한 읽기 권한이
   막혀 있으면 CloudFront 가 403/404 를 냅니다.

**배포마다 자동으로 확인합니다.** `.github/workflows/deploy.yml` 의
`verify_images` 잡이 배포 뒤 공개된 작품 하나를 골라 표지 그림이 실제로
열리는지 확인하고, 안 열리면 배포 실패로 잡아 텔레그램으로 알립니다
(dev 는 CloudFront 가 없어 대상에서 뺍니다). API 가 200 을 내는 것과
그림이 실제로 열리는 것은 다른 이야기입니다 — 주소만 만들어 돌려주는
API 는 버킷 설정이 틀려도 200 을 내기 때문에, 이 잡이 실제로 그 주소를
열어 봅니다.

## 자주 하는 실수

- `apps/web/public/static/` 에 직접 넣기 → 커밋 안 되고 다음 빌드에 사라집니다.
- 캐릭터 그림만 바꾸고 서버를 안 띄우기 → 기동 시점 사본을 계속 씁니다.
- 예시 작품 폴더만 지우고 `runs.json` 이나 하드코딩 자리를 안 고치기 →
  빈칸이 생깁니다.
- 이미지 주소에 도메인 붙이기 → 상대경로만 씁니다.
