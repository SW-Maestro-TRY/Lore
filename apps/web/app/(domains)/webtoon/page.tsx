// /webtoon 라우트 연결 파일.
// Next.js App Router 규칙상 이 위치에 page.tsx 가 있어야 URL 이 잡히기 때문에 존재하는 파일이다.
// 화면 내용은 webtoon/fe 에 있으니 이 파일은 건드릴 일이 거의 없다.
//
// 지금은 홈 화면만 이 자리를 쓴다 — 나머지(/webtoon/works 등)는 아직
// next.config.mjs 의 rewrites 가 정적 프로토타입으로 보낸다.
export { default } from "@webtoon/WebtoonPage";
