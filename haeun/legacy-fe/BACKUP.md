# legacy-fe — 옛 웹툰 화면 백업 (2026-09-19)

`webtoon/fe` 를 **전면 교체하기 직전에 그대로 떠 둔 사본**이다. 새 화면을
만들다 "예전엔 이걸 어떻게 했더라" 할 때 열어 보라고 남긴 것이고,
**여기서 빌드되지 않는다** — `@common/*` 별칭과 `apps/web` 의 라우트 배선에
기대고 있어서 원래 자리에서만 돈다.

## 왜 갈아엎었나

이 화면은 옛 프로토타입(`haeun/landing/web` 의 index.html + app.js)을 React 로
기계적으로 옮긴 것이다. 그래서:

- `webtoon.css` 가 전역 CSS 4,735줄이고 CSS 모듈이 0개다. 공용 디자인 토큰
  (`common/fe/styles/tokens.css`)을 안 쓴다.
- `lib/editorCore.ts` (1,712줄)가 React 가 아니라 DOM 을 직접 만지는
  바닐라 JS 엔진이다.
- 죽은 파일이 섞여 있다 — `sections/TopBar.tsx`(어디서도 import 안 됨),
  `sections/Progress/useFakeProgress.ts`(실제 폴링으로 대체됨),
  `port-css.mjs`(이미 없어진 `haeun/landing/web` 를 가리킴).
- `README.md` 는 "이 탭은 React 가 아니다"라고 적힌 낡은 문서다.

## 그래도 여기 있는 값진 것 세 가지

새로 쓰더라도 이 셋은 보고 써야 한다:

1. **`lib/nhApi.ts`** — 백엔드 엔드포인트 39개의 사실상 유일한 계약 문서.
   하네스가 없는 배포 환경에서 `demo-api/*.json` · `static/gallery` 로
   떨어지는 폴백 경로도 여기 있다.
2. **`lib/editorCore.ts`** — 편집실 오버레이·굽기·재생성·버전 되돌리기의
   유일한 클라이언트 구현. `be` 의 `/overlay` · `/bake` · `/regen` 과 짝이다.
3. **`lib/progressData.ts`** — 진행 단계 표시 데이터. `webtoon/ai` 의 실제
   파이프라인 단계와 대응한다.

## 새 화면에서도 반드시 살려야 하는 것

- `sync-landing.sh` — `apps/web/package.json` 의 predev·prebuild 가 자동으로
  부른다. 마스코트·견본 그림을 `apps/web/public/static` 으로 떠 오는 일이라,
  없으면 화면이 빈다. 원본은 `webtoon/ai/assets` 라 위치를 못 옮긴다.
- `static/badges` · `static/gallery` — 위 스크립트의 원본. gallery 는 데모가
  아니라 하네스 없는 배포 환경의 실제 예시 작품이다.
- `demo-api/*.json` — 같은 이유로 실제 동작에 쓰인다.

## 뜬 상태 그대로다

`sections/Result/Result.tsx` 에 커밋 안 된 수정이 하나 있는 채로 복사했다.
