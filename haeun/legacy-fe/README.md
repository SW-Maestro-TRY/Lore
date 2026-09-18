# webtoon/fe

이 탭의 화면은 **React 가 아니다.**

다른 도메인(`comic/fe`, `trailer/fe`)에는 `*.tsx` 화면 컴포넌트가 있는데 여기만
없다. 일부러 그렇다.

Webtoon 탭의 화면은 `haeun/landing` 의 프로토타입이다 — `index.html` 999줄 ·
`app.js` 3300줄로 이미 완성된 한 페이지고, 제 주소를 스스로 읽어 첫 화면을
고른다. 이것을 React 로 옮기는 데 며칠이 드는데, 지금 필요한 것은 배포된
자리에서 이 화면이 그대로 도는 것이다.

## 어떻게 이어져 있나

```
haeun/landing/web/          원본. 파이썬 서버(serve.py)가 읽는 그것 — 여기를 고친다
  ↓  bash webtoon/fe/sync-landing.sh
apps/web/public/static/     복사본. Next 가 서빙하는 것 (public 밖은 서빙이 안 된다)
  ↓  apps/web/next.config.mjs 의 rewrites
/webtoon · /webtoon/demo · /webtoon/editor ...
```

**복사본을 직접 고치지 않는다.** 다음 동기화에 그대로 덮인다.

원본이 뿌리 기준(`/works`)이고 배포는 `/webtoon` 아래라, 복사본에만
`<html data-lore-base="/webtoon">` 를 심는다. `web/base.js` 가 그것을 읽어
화면 안의 주소를 전부 그 아래로 옮긴다. 파이썬 서버에는 그 표시가 없으므로
같은 파일이 뿌리에서도 그대로 돈다.

## 데모 모드

실제 생성·결제·로그인은 파이썬 서버가 있어야 하는 일이라, 복사본에 함께 실리는
`demo-api.js` 가 그 호출을 막고 안내로 바꾼다. 화면에 뜨는 값(그림체 목록·비용·
크레딧)은 그 서버에서 받아 둔 스냅샷(`demo-api/*.json`)이다.
