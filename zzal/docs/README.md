# Zzal 도메인 문서

담당: **상훈** — 이 폴더(`zzal/`) 안의 be / fe / docs 전부.

만화 캐릭터 치환 / 짤 관련 R&D, 프롬프트, 설계 메모를 여기 남긴다.

## 여울 샘플 스프라이트 판 되돌리기

지금 판 = **정본 v02 = `demo/v7`**(16 자세 · 312×349 · K=274). 되돌리려면 **세 곳을 같이** 되돌린다 —
그림과 앵커표가 다른 판이면 소품만 조용히 어긋난다.

1. `zzal/fe/tamagotchi/constants.ts` — `YEOUL_MOTION` 과 `YEOUL_ANCHORS_URL` 의 `demo/v7/` → `demo/v6/`
2. `zzal/fe/tamagotchi/props/anchors-fixed.ts` — `FIXED_ANCHORS` 를 v6 판(K=239 · Hw=95)으로
3. 그림 파일은 안 지웠다(`public/zzal/demo/v6/` 그대로) — 되돌리면 바로 뜬다

로컬에서 새 판이 안 보이면 `apps/web/.env.local` 의 `NEXT_PUBLIC_CDN_BASE` 를 **빈 값**으로 두어
`public/zzal/` 을 보게 한다(S3 에 아직 안 올린 판은 CDN 에 없다). e2e 는 이미 빈 값으로 띄운다.
