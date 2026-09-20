// /zzal/admin — 관리자 검수 화면(구워진 움짤을 보고 좋음/다시굽기를 남긴다).
//
// ★★ 이 파일이 세 겹 잠금의 셋째 겹이다.
//    (1) 서버 스위치 app.zzal.admin.enabled — 꺼져 있으면 API 주소 자체가 없다(404)
//    (2) AdminGuard — 관리자 계정이 아니면 403(ADMIN_ONLY)
//    (3) 여기 noindex — 관리자 화면이 발견되는 실제로 가장 흔한 경로가 검색이다.
//        앞의 둘은 서버를 지키고, 이 한 겹은 "존재를 알게 되는 것" 을 지킨다.
//
// ★ 화면을 클라이언트 컴포넌트로 따로 둔 이유 — metadata 는 서버 컴포넌트에서만 내보낼 수
//   있는데, 검수 화면은 눌러서 판정하는 화면이라 'use client' 가 필요하다. 한 파일에
//   둘 다 넣을 수 없어서 이 자리는 껍데기로 남기고 알맹이는 @zzal/admin 에 둔다.
//   (라우팅 파일에 화면 로직을 안 두는 것은 /zzal 과 같은 규칙이기도 하다)
import type { Metadata } from 'next';
import AdminReviewScreen from '@zzal/admin/AdminReviewScreen';

export const metadata: Metadata = {
  title: '움짤 검수',
  // index: false 만으로는 부족하다. follow 까지 막아야 이 화면에서 나가는 링크를 타고
  // 크롤러가 관리자 주소 체계를 훑는 일이 없다.
  robots: { index: false, follow: false, nocache: true },
};

export default function Page() {
  return <AdminReviewScreen />;
}
