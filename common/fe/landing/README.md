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
| 왼쪽(웹툰 한 화) 배경 — 페이지를 열 때마다 세 장 중 하나가 무작위로 나온다 | `common/fe/assets/landing/hero-webtoon-1.jpg` · `-2.jpg` · `-3.jpg` |
| 오른쪽(우리 애랑 같이 살기) 배경 | `common/fe/assets/landing/hero-tama.webp` |

**1080×1620(2:3) 세로 그림**으로 만든다. 칸은 모바일에서는 세로로 길지만
(390×약 660), PC에서는 거의 정사각형(1440px 화면에서 720×720)이고 큰 화면일수록
납작해져(1920px에서 960×720) 그림 위아래가 잘린다. 그래서 주인공은 **세로 가운데
(위에서 30%~70%)** 에 둔다. 위쪽에는 가운데 제목이, 아래쪽에는 칸 설명 글자와
짙은 색 막이 덮인다.

파일 이름과 확장자를 그대로 유지하면서 덮어쓰면 된다. 웹툰 쪽 장 수를 바꾸려면
`sections/HeroWebtoonBg.tsx` 의 `COUNT` 를 고치고 `hero-webtoon-4.jpg` 처럼 번호를
이어 붙인다.

## 2. 세 칸 아코디언 — 캐릭터 생성 / 웹툰 1화 / 캐릭터 키우기

| 칸 | 파일 |
|---|---|
| 01 캐릭터 생성 | `common/fe/assets/landing/trio-character.jpg` |
| 02 웹툰 1화 | `common/fe/assets/landing/trio-webtoon.jpg` |
| 03 캐릭터 키우기 | `common/fe/assets/landing/trio-tama.webp` |

이 세 칸은 그림 비율이 서로 달라도 잘리지 않고 칸 안에 전체가 다 보이도록
만들어져 있다(`object-fit: contain`) — 남는 자리는 카드와 같은 흰색으로
채워진다.

칸 높이는 200px 고정이고 폭만 바뀐다(평소 약 382px, 마우스를 올려 넓어지면 약
601px, 모바일 약 358px). 지금 그림은 **1500×600(2.5:1)** 에 바탕을 흰색으로 칠해
두어서, 칸 폭이 바뀌어 남는 자리가 생겨도 이음매가 안 보인다. 새로 만들 때도 바탕을
흰색으로 둔다.

세 칸은 누르면 바로 그 기능으로 간다 — 01 캐릭터 생성은 `/webtoon?view=try`,
02 웹툰 1화는 `/webtoon?view=create`, 03 캐릭터 키우기는 `/zzal`.

## 3. 맨 아래 작품 벽 — 좌우로 흐르는 두 줄

폴더: **`common/fe/assets/landing/wall/`**

두 줄이 서로 다른 그림이다. 윗줄은 `webtoon-01.jpg` ~ `webtoon-27.jpg`(예시 작품
표지 9장, 예시 작품 속 컷 6장, 예시 캐릭터 12장을 섞은 것), 아랫줄은 `zzal-01.jpg` ~ `zzal-15.jpg`(짤
캐릭터가 여러 배경에서 여러 동작을 하는 타일)이다. 코드는
`common/fe/landing/sections/Wall.tsx`의 `WEBTOON_IMAGES` · `ZZAL_IMAGES` 배열이 그
파일 이름을 순서대로 갖고 있다. 마우스를 올려도 멈추지 않는다.

- **그림만 바꾸고 싶으면**: 같은 파일 이름(`wall-01.jpg` 등)으로 덮어쓰기만
  하면 된다. 코드를 열 필요가 없다.
- **장 수를 늘리거나 줄이고 싶으면**: 이 폴더에 파일을 넣고(또는 빼고),
  `Wall.tsx`의 해당 줄 배열에 그 파일 이름을 한 줄 추가(또는 삭제)하면
  된다. 몇 장을 넣든 상관없다 — 코드가 그 목록을 그대로 두 번 이어 붙여
  끊김 없이 돌린다.
- 칸은 128×182 이다. 파일은 그 2배인 **256×364** 로 만든다.

## 그림을 바꾼 뒤

파일만 바꿨다면 그걸로 끝이다 — `npm run dev`(또는 `build`)를 다시 돌리면
`predev`/`prebuild`가 `common/fe/sync-landing-assets.sh`를 불러 자동으로
`apps/web/public/static/landing/`에 복사해 온다. 지금 개발 서버가 떠 있는
채로 급하게 반영하고 싶을 때만 아래를 직접 돌리면 된다:

```
bash common/fe/sync-landing-assets.sh
```

## 참고

- 첫 화면과 세 칸 그림(2026-09-25 교체)은 실제 결과물을 조합해 새로 만든
  것이다 — 웹툰 쪽은 예시 작품(`webtoon/ai/assets/examples/`)의 표지와 쪽,
  캐릭터는 예시 캐릭터(`webtoon/ai/assets/samples/ex-*`), 키우기는 짤의 동작
  그림(`images/zzal/demo/v6/`)과 소품 그림이다.
- 색·글꼴(잉크·바다색 팔레트, Gmarket Sans + SUIT)은 웹툰 탭 온보딩과 같은
  톤이다. `common/fe/landing/landing.module.css`의 `.page` 블록에 이 페이지
  전용 변수(`--l-*`)로 따로 심어 뒀다 — 다른 탭·헤더·푸터 색과는 무관하다.
