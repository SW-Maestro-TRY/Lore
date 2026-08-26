// 탭으로 가는 링크.
//
// 도메인 탭이 전부 React 페이지는 아니다. Webtoon 은 next.config.mjs 의
// rewrites 가 public 의 정적 HTML 로 이어 준 자리라(webtoon/fe/README.md),
// next/link 로는 못 간다 — 옮겨 갈 React 화면을 못 찾고 아무 일도 안 일어난다.
//
// 헤더·푸터·랜딩 카드 세 군데가 같은 판단을 해야 해서 여기 한 곳에 둔다.
// 링크를 하나 더 놓을 때 이 사실을 다시 알아낼 필요가 없게.
import Link from "next/link";

type Props = {
  href: string;
  hardNav?: boolean;
  className?: string;
  "aria-current"?: "page";
  children: React.ReactNode;
};

export default function TabLink({ href, hardNav, children, ...rest }: Props) {
  if (hardNav) {
    return (
      <a href={href} {...rest}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} {...rest}>
      {children}
    </Link>
  );
}
