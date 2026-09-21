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

### 예시 웹툰 (홈 마퀴 · 둘러보기에 「예시」 배지로 뜨는 작품)

지금 네 편이 정적 파일로 들어 있습니다. DB 와 무관하고 로그인도 필요 없습니다.

```
webtoon/fe/static/gallery/
  runs.json                  ← 목록
  <run_id>/cover.jpg         ← 표지
  <run_id>/p01.jpg … pNN.jpg ← 본문 (2자리 zero-pad)
  <run_id>/result.json       ← 제목·로그라인·쪽 정보
```

읽는 코드는 `webtoon/fe/lib/api.ts:267-278`(목록),
`:299-302`(표지 주소), `:329-332`(본문 주소), `:319-327`(`result.json` 폴백)
입니다.

**한 편 추가하기**

1. `webtoon/fe/static/gallery/<run_id>/` 를 만들고 `cover.jpg`,
   `p01.jpg`…, `result.json` 을 넣습니다. `result.json` 모양은 기존 폴더
   것을 복사해 고치는 게 빠릅니다(필드는 `api.ts:304-317` 의 `RunResult`).
2. `runs.json` 의 `runs[]` 에 항목을 추가합니다. 실제로 쓰이는 필드는
   `run_id` · `title` · `genre` · `episodes` · `cover_episode` ·
   `cover_page` · `page_count` · `style_label` 입니다.
3. `bash webtoon/fe/sync-landing.sh` 로 복사합니다.

이렇게 하면 홈 마퀴와 둘러보기에 자동으로 붙습니다. **다만 아래 "하드코딩된
자리" 두 곳은 따로 고쳐야 합니다.**

**한 편 빼기**: 폴더를 지우고 `runs.json` 에서 항목을 뺍니다. 그 `run_id` 를
하드코딩해 쓰는 자리가 있으면 거기도 같이 고쳐야 조용히 빈칸이 안 됩니다.

예시와 같은 `run_id` 의 작품이 DB 에도 살아 있으면 실제 것만 나옵니다
(`api.ts:282-291`).

### 예시 캐릭터 (위저드 1걸음의 「기본 제공」 카드)

여기만 **DB + S3** 로 돕니다. 정적 파일이 아닙니다.

지금 여덟 명이 서버 부팅 때 DB 에 심어지고, 그때 그림이 S3 공개 자리
(`images/webtoon/char/<uuid>.jpg`)로 올라갑니다
(`webtoon/be/.../character/BuiltinCharacters.java:66-101` 의 `SEEDS`,
업로드는 `:205-207`). 화면에는 JSON 의 `art_url` 로 나갑니다
(`webtoon/be/.../character/CharacterService.java:389-402`).

**추가/교체하기**

1. 그림을 `webtoon/ai/assets/samples/ex-<이름>-1.jpg` 로 넣습니다.
2. `BuiltinCharacters.java` 의 `SEEDS` 에 `new Seed(그림체, 파일명, 이름, 설명)`
   을 한 줄 추가합니다.
3. **`webtoon/be` 를 다시 빌드·기동해야 반영됩니다.** `webtoon/ai/` 는 jar
   리소스로 담겨서 기동할 때 풀리기 때문에, 파일만 바꿔 놓고 반영됐다고
   보면 안 됩니다(`webtoon/CLAUDE.md` 의 "ai/ 를 고쳤을 때" 절).
4. 그림체 고르개 썸네일로도 쓰려면 `webtoon/fe/lib/styleThumbs.ts:17-24` 에
   키를 추가합니다.

**빼기**: `SEEDS` 에서 줄을 지우면 부팅 때 DB 에서 자동으로 지워집니다
(`BuiltinCharacters.java:239-262`). 파일을 지울 필요는 없습니다.

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

## 자주 하는 실수

- `apps/web/public/static/` 에 직접 넣기 → 커밋 안 되고 다음 빌드에 사라집니다.
- 캐릭터 그림만 바꾸고 서버를 안 띄우기 → 기동 시점 사본을 계속 씁니다.
- 예시 작품 폴더만 지우고 `runs.json` 이나 하드코딩 자리를 안 고치기 →
  빈칸이 생깁니다.
- 이미지 주소에 도메인 붙이기 → 상대경로만 씁니다.
