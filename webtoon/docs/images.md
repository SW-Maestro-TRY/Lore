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

### 예시 작품은 번들로, 예시 캐릭터는 저장소에

| | 어디에 두나 | 어떻게 들어가나 |
| --- | --- | --- |
| 예시 작품 | **저장소에 안 둡니다**(#616). 번들 zip 을 저장소 밖에 둡니다 | 관리자 API 로 그 환경에 올립니다 |
| 예시 캐릭터 | `webtoon/ai/assets/samples/ex-*.jpg` | 서버가 뜰 때 그 환경의 창고에 올리고 DB 에 심습니다 |

창고는 환경마다 다릅니다(노트북·dev 는 MinIO, staging·prod 는 각자 S3). 넣는 쪽은 어느 것인지 알 필요가
없습니다. **심는 것은 한 번뿐입니다.** 이미 있는 것은 건드리지 않으므로 심은 뒤 DB 에서 고친 것이 되돌아가지
않습니다(아래 「DB 에서 고치기」).

### 예시 작품 — 번들과 관리자 API (#614 · #616)

예시 작품을 저장소에 두면 편수가 늘수록 저장소·jar 가 커지고, 배포를 거쳐야 하고, 내릴 때 DB 를 만져야
합니다. **환경마다 S3 가 다르므로**(로컬 `~/lore-minio` · dev 박스 MinIO · staging · 운영 각자의 버킷) 작품은
키로 옮길 수 없고, 그림 **내용**을 담은 **번들(zip)** 로 옮깁니다. 받는 쪽이 자기 창고에 새 키로 다시 올리고,
**작품 번호(`run_id`)는 그대로** 둡니다.

**번들 모양**

```
manifest.json          제목 · 장르 · 캐릭터 · 그림체 · 줄거리 · 입력값 · 장면 설명 (옛 이름 meta.json 도 읽음)
pages/p01-w320.jpg     쪽 그림, 두 폭 다 — 목록 카드가 320, 본문이 1080. 없는 폭은 404
pages/p01-w1080.jpg    원본(w0)은 안 넣는다(다시 그릴 때만 쓰고 용량만 몇 배)
run/…                  (선택) 하네스 작품 폴더 — 이야기 후보 · 고른 번호 · 시트 · 장면 · 검수
```

```json
{
  "run_id": "20261003T133334-b5ccce",
  "title": "카페 사장에게는 비밀이 많다",
  "genre": "현대 느와르 드라마",
  "character": "사쿠라이 레나",
  "style": "frost",
  "logline": "…",
  "captions": ["2쪽 설명", "3쪽 설명"],
  "input": { "name": "사쿠라이 레나", "character": "…" }
}
```

- `captions` 는 **2쪽부터** 차례로 붙습니다(1쪽은 표지). `style` 은 그림체 키입니다.
- `run/` 에 이야기 후보(`directions.json`)가 있으면 후보 넷과 `pick.json` 의 고른 번호를 그대로 심습니다. 그래도
  **화면 제목·줄거리는 manifest 값이 정본**입니다(후보 원래 제목과 다르면 사람이 고친 값 자리에 적힘).
- 원본 사진은 담지 않습니다. `run/input.json` 의 사진 경로는 비웁니다.
- 번들을 만드는 법: 그 작품이 있는 환경에서 `GET /{runId}/bundle` 로 받습니다. 2026-10-03 에 만든 18편은
  하은 노트북의 `~/lore-example-bundles/` 에 있습니다(목록은 그 폴더의 `README.md`). **저장소에 넣지 않습니다**
  — 사용자 입력이 들어 있습니다.

**관리자 API** — 관리자 계정(`role = ADMIN`)만. 주소는 `/api/webtoon/v1/admin/examples`:

| 하는 일 | 주소 |
| --- | --- |
| 목록(순서대로, 비공개로 내린 것도) | `GET /` |
| 번들 내보내기 — 이 환경의 작품 하나를 zip 으로 | `GET /{runId}/bundle` |
| 번들 올리기(작은 것·로컬·dev) — 본문이 zip, `?dryRun=true` 면 검사만 | `POST /import` |
| 번들 올리기(운영·staging) — ① 주소 받기 ② S3 로 PUT ③ 키로 심기 | `POST /upload-url` → `PUT` → `POST /import-key` `{"key":…}` |
| 지정·해제·공개·순서 | `PATCH /{runId}` `{"example":true,"public":true,"order":1}` |
| 내리기(예시 해제 + 비공개, 작품은 안 지움) | `DELETE /{runId}` |

- **운영·staging 은 `upload-url` 길을 씁니다.** CloudFront 앞단 WAF 가 큰 요청 본문을 403 으로 막아서
  몇 MB 짜리 번들은 서버로 못 옵니다. 브라우저가 S3 로 직접 올리고 서버는 키만 받아 읽습니다. 키는 일회용이고
  심은 뒤 올린 파일은 지웁니다.
- 같은 `run_id` 가 이미 있으면 아무것도 안 하고 `EXISTS` 를 돌려줍니다. **예시 표시도 말없이 켜지 않습니다**
  — 같은 번호의 작품이 이미 누군가의 것일 수 있습니다. 예시로 지정하려면 `PATCH` 로 명시합니다.
- 같은 환경에서 만든 작품은 그림을 옮길 필요 없이 `PATCH {"example":true}` 한 번이면 예시가 됩니다.
- 받은 zip 은 믿지 않습니다: 경로 이탈 · `run/` 의 스크립트 · JPEG 가 아닌 그림 · 용량 폭탄(실제로 읽은 바이트로
  셉니다)을 거절합니다.
- 예시 표시는 `webtoon_work.is_example` · `example_order` 입니다. 예전에 시드로 심은 것은 마이그레이션이
  한 번에 켰습니다.

**한 편 빼기**: `DELETE /{runId}`(예시 해제 + 비공개). 작품을 아예 지우려면 DB 에서 지웁니다.

**그림만 바꾸기**: 이미 있는 작품은 다시 올려도 `EXISTS` 라 안 바뀝니다. 그 작품의 `webtoon_page` 줄을 지우고
다시 올리거나, 새 작품 번호로 올립니다.

코드: `webtoon/be/src/main/java/com/lore/webtoon/work/` 의 `ExampleBundle` · `ExampleBundles`(읽기·쓰기·검증) ·
`ExampleImporter`(심기) · `ExampleBundleExporter`(내보내기) · `ExampleAdmin`(관리) · `ExampleAdminController`.
예전의 저장소 폴더(`webtoon/ai/assets/examples/<run_id>/`)는 비워 두었습니다. 거기 폴더를 넣으면 부팅 시드
(`ExampleWorks`)가 지금도 같은 코드로 심지만, 쓰지 않는 것이 원칙입니다.

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
| 온보딩 04 「완성」 칸 | `webtoon/fe/screens/landing/Landing.tsx:38` | `20260910T132240-ae8c28` |

입구 화면 카드 두 장은 작품 번호가 아니라 정적 그림을 쓴다
(`webtoon/fe/static/entry/`). 그림은 카드 전체에 배경으로 옅게(55%) 깔린다.
왼쪽 「웹툰 만들기」는 `webtoon-page.jpg`(「마탑의 실험용 캔」 3쪽 위 세 컷,
말상자·말풍선까지 그대로, 1024×1225), 오른쪽 「캐릭터 만들기」는 `character.jpg`
(캐릭터 「흑설」 카드 그림, 512×768)다. 폰 카드(높이 230px)는 왼쪽에 위 두 컷만 둔
`webtoon-cut.jpg`(1024×970)를 따로 쓴다. 바꾸려면 같은 이름으로 덮어쓴다.

## 자주 하는 실수

- `apps/web/public/static/` 에 직접 넣기 → 커밋 안 되고 다음 빌드에 사라집니다.
- 캐릭터 그림만 바꾸고 서버를 안 띄우기 → 기동 시점 사본을 계속 씁니다.
- 예시 작품을 내리고 하드코딩 자리(첫 화면 고정 예시)를 안 고치기 → 빈칸이 생깁니다(첫 화면은 정적
  견본으로 대신 나옴).
- 예시 번들을 저장소에 넣기 → 사용자 입력이 들어 있고 저장소·jar 가 커집니다. 저장소 밖에 둡니다.
- 이미지 주소에 도메인 붙이기 → 상대경로만 씁니다.
