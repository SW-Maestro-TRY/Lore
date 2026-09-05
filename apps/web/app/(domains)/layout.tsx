// 도메인 탭(/webtoon, /zzal, /trailer) 공용 레이아웃.
//
// (domains) 는 괄호로 감싼 라우트 그룹이라 URL 에 영향을 주지 않는다. /zzal 은 그대로 /zzal 이다.
// 이 그룹을 둔 이유: 랜딩(/)은 LandingPage 안에서 헤더를 직접 렌더링하기 때문에,
// 도메인 탭에만 헤더를 붙일 자리가 필요해서다.
//
// 헤더는 랜딩과 완전히 같은 컴포넌트다. 탭을 넘나들 때 로고 위치나 탭 순서가 달라지면 안 된다.
import SiteHeader from "@common/SiteHeader";

export default function DomainsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <>
      <SiteHeader />
      {/* 폭·여백을 변수로 열어 둔다. 화면 전체를 쓰는 페이지(zzal 다마고치)가
          이 변수를 0/none 으로 덮어 껍데기를 벗을 수 있게 하기 위함이다.
          변수를 안 덮으면 기본값 그대로라 다른 탭은 달라지는 게 없다. */}
      <main
        style={{
          maxWidth: "var(--main-max, var(--container))",
          margin: "0 auto",
          padding: "var(--main-pad-y, clamp(28px, 5vw, 56px)) var(--main-pad-x, var(--gutter))",
        }}
      >
        {children}
      </main>
    </>
  );
}
