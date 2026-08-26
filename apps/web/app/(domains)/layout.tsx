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
      <main
        style={{
          maxWidth: "var(--container)",
          margin: "0 auto",
          padding: "clamp(28px, 5vw, 56px) var(--gutter)",
        }}
      >
        {children}
      </main>
    </>
  );
}
