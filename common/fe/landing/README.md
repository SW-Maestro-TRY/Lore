# 랜딩페이지(`/`) 그림 바꾸는 법

2026-09-20, 시안 C(갈림길)를 확정안으로 코드에 반영하면서 같이 적어 둔다.
프로토타입: `haeun/landing-concepts/c-split.html`.

랜딩페이지에 쓰는 그림은 전부 **`common/fe/assets/landing/`** 폴더 하나에
모여 있다. 코드를 몰라도, **같은 파일 이름으로 다른 그림을 덮어쓰기만 하면
그대로 바뀐다.**

> ⚠️ `apps/web/public/static/landing/`(브라우저가 실제로 받는 자리)을 직접
> 고치지 않는다 — `apps/web/public/static/` 전체가 `.gitignore` 에 있어서
> 커밋이 안 되고, `npm run dev`/`npm run build` 를 다시 돌리면
> `common/fe/sync-landing-assets.sh` 가 `common/fe/assets/landing/` 내용으로
> 덮어써 버린다. **원본은 항상 `common/fe/assets/landing/`.**

## 1. 히어로 — 첫 화면 웹툰/키우기 두 칸의 배경

| 자리 | 파일 |
|---|---|
| 왼쪽(웹툰 한 화) 배경 | `common/fe/assets/landing/hero-webtoon.jpg` |
| 오른쪽(우리 애랑 같이 살기) 배경 | `common/fe/assets/landing/hero-tama.webp` |

세로로 긴 그림이 잘 어울린다(칸이 화면 왼쪽/오른쪽 절반을 세로로 채운다).
파일 이름과 확장자를 그대로 유지하면서 덮어쓰면 된다. 다른 확장자로 바꾸고
싶으면(예: jpg → webp) `common/fe/landing/sections/Hero.tsx`에서 그 두 줄의
`src="/static/landing/hero-....."`만 고치면 된다.

## 2. 세 칸 아코디언 — 캐릭터 생성 / 웹툰 1화 / 캐릭터 키우기

| 칸 | 파일 |
|---|---|
| 01 캐릭터 생성 | `common/fe/assets/landing/trio-character.jpg` |
| 02 웹툰 1화 | `common/fe/assets/landing/trio-webtoon.jpg` |
| 03 캐릭터 키우기 | `common/fe/assets/landing/trio-tama.webp` |

이 세 칸은 그림 비율이 서로 달라도(세로 사진이든 가로로 넓은 사진이든) 잘리지
않고 칸 안에 전체가 다 보이도록 만들어져 있다(`object-fit: contain`) — 남는
자리는 옅은 물색으로 채워진다. 그러니 비율 걱정 없이 아무 그림이나 넣어도 된다.

## 3. 맨 아래 작품 벽 — 좌우로 흐르는 두 줄

폴더: **`common/fe/assets/landing/wall/`**

지금은 `wall-01.jpg` ~ `wall-12.jpg`(+ `wall-05.png`) 12장이 들어 있고, 실제
코드는 `common/fe/landing/sections/Wall.tsx`의 `WALL_IMAGES` 배열이 그 파일
이름을 순서대로 갖고 있다.

- **그림만 바꾸고 싶으면**: 같은 파일 이름(`wall-01.jpg` 등)으로 덮어쓰기만
  하면 된다. 코드를 열 필요가 없다.
- **장 수를 늘리거나 줄이고 싶으면**: 이 폴더에 파일을 넣고(또는 빼고),
  `Wall.tsx`의 `WALL_IMAGES` 배열에 그 파일 이름을 한 줄 추가(또는 삭제)하면
  된다. 몇 장을 넣든 상관없다 — 코드가 그 목록을 그대로 두 번 이어 붙여
  끊김 없이 돌린다.
- 세로로 긴 사진이 이 자리에 잘 어울린다(칸이 좁고 길다, 128×182).

## 그림을 바꾼 뒤

파일만 바꿨다면 그걸로 끝이다 — `npm run dev`(또는 `build`)를 다시 돌리면
`predev`/`prebuild`가 `common/fe/sync-landing-assets.sh`를 불러 자동으로
`apps/web/public/static/landing/`에 복사해 온다. 지금 개발 서버가 떠 있는
채로 급하게 반영하고 싶을 때만 아래를 직접 돌리면 된다:

```
bash common/fe/sync-landing-assets.sh
```

## 참고

- 지금 들어 있는 그림은 전부 이 저장소에 이미 있던 실제 결과물을 그대로
  옮겨 둔 것이다(자리표시자가 아니다) — 둘러보기 표지 4장은
  `apps/web/public/static/gallery/*/cover.jpg`(원본은
  `webtoon/ai/assets` 쪽), 그림체 견본은 `apps/web/public/static/samples/`,
  캐릭터 키우기 배경은 `zzal/fe/assets/season-panorama.webp` — 실제 `/zzal`
  화면 히어로가 쓰는 그림과 같다. 급하면 이대로 배포해도 된다.
- 색·글꼴(잉크·바다색 팔레트, Gmarket Sans + SUIT)은 웹툰 탭 온보딩과 같은
  톤이다. `common/fe/landing/landing.module.css`의 `.page` 블록에 이 페이지
  전용 변수(`--l-*`)로 따로 심어 뒀다 — 다른 탭·헤더·푸터 색과는 무관하다.
