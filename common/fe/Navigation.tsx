// 공용 상단 탭 내비게이션 바.
// 3개 도메인이 함께 쓰는 UI 라서 특정 도메인 폴더가 아니라 common/fe 에 둔다.
// 새 탭이 생기면 아래 TABS 배열에만 추가하면 된다.
import Link from "next/link";

const TABS = [
  { href: "/story", label: "Story" },
  { href: "/comic", label: "Comic" },
  { href: "/trailer", label: "Trailer" },
];

export default function Navigation() {
  return (
    <nav
      style={{
        display: "flex",
        alignItems: "center",
        gap: "1.5rem",
        padding: "1rem 1.5rem",
        borderBottom: "1px solid #e5e5e5",
      }}
    >
      {/* 로고 / 홈 링크 */}
      <Link href="/" style={{ fontWeight: 700, textDecoration: "none" }}>
        Lore
      </Link>

      {/* 도메인 탭 링크들 */}
      <div style={{ display: "flex", gap: "1rem" }}>
        {TABS.map((tab) => (
          <Link key={tab.href} href={tab.href} style={{ textDecoration: "none" }}>
            {tab.label}
          </Link>
        ))}
      </div>
    </nav>
  );
}
