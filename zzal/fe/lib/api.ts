// 백엔드 호출의 밑바닥. 실제 코드는 common/fe/api/client.ts 에 있다.
//
// 2026-09-03 에 옮겼다 — 봉투(ApiResponse)도 401 refresh 회전도 common/be 의 약속이라
// zzal 소유가 아니고, 로그인 모달이 공통 헤더로 올라오면서 common 이 zzal 을 import 하는
// 역참조가 생길 참이었다. 옮기면서 이 자리를 지운 게 아니라 껍데기로 남긴 이유는,
// pet.ts · upload.ts · usePet.ts 가 `./api` 를 그대로 부르고 있어서다. 부르는 쪽을
// 건드리지 않으면 이사 때문에 생기는 diff 가 0 이 된다.
//
// 새 코드는 이 껍데기 말고 @common/api/client 를 직접 import 할 것.
export * from '@common/api/client';
