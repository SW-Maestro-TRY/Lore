"use client";

// 사이트 공통 상단 헤더.
//
// 랜딩(/)과 도메인 탭(/zzal, /trailer, /webtoon)이 같은 헤더를 쓴다.
// 헤더가 두 벌이면 탭을 넘나들 때 로고 위치·순서가 바뀌어 보이므로 컴포넌트 하나로 둔다.
// 탭 순서는 common/fe/links.ts 의 TABS 하나만 보면 된다 (Zzal → Trailer → Webtoon).
//
// "use client" 인 이유: 현재 경로를 알아야 열려 있는 탭을 표시할 수 있어서다.
import Link from "next/link";
import { usePathname } from "next/navigation";
import ThemeToggle from "./theme/ThemeToggle";
import TabLink from "./TabLink";
import { TABS } from "./links";
import styles from "./SiteHeader.module.css";

export default function SiteHeader() {
  const pathname = usePathname();
  const onZzal = pathname.startsWith("/zzal");

  return (
    <header className={styles.header}>
      <div className={styles.headerInner}>
        <Link href="/" className={styles.wordmark}>
          LORE<span className={styles.wordmarkDot}>.</span>
        </Link>

        <nav className={styles.nav}>
          {TABS.map((tab) => {
            const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
            return (
              <TabLink
                key={tab.href}
                href={tab.href}
                hardNav={tab.hardNav}
                aria-current={active ? "page" : undefined}
                className={`${styles.navLink} ${active ? styles.navLinkActive : ""}`}
              >
                {tab.label}
              </TabLink>
            );
          })}
        </nav>

        <div className={styles.headerActions}>
          <ThemeToggle />
          {/* 시작점은 Zzal 탭 (사용자 여정상 가장 가벼운 진입).
              이미 zzal 에 들어와 있으면 가리킬 곳이 자기 자신이라 접는다 —
              좁은 화면에서 그 자리를 탭(Trailer·Webtoon)에 내준다. */}
          {!onZzal && (
            <Link href="/zzal" className={styles.headerCta}>
              시작하기
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}
