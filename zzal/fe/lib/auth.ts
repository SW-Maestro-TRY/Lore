// 가입·로그인·로그아웃·내 정보. 실제 코드는 common/fe/auth/api.ts 에 있다.
//
// 로그인은 세 탭(zzal·trailer·webtoon)이 함께 쓰는 것이라 common 으로 올렸다(2026-09-03).
// 이 자리는 이미 zzal 화면이 부르고 있을 수 있어 껍데기로 남겨 둔다.
//
// 새 코드는 @common/auth/api 를 직접 import 할 것. 화면에서 로그인 상태를 보고 싶은 거라면
// 이 파일이 아니라 @common/auth/useAuth 의 useAuth() 다 — 호출과 상태는 다른 층이다.
export * from '@common/auth/api';
