"use client";

// 사이트 공통 상단 헤더.
//
// 랜딩(/)과 도메인 탭(/zzal, /trailer, /webtoon)이 같은 헤더를 쓴다.
// 헤더가 두 벌이면 탭을 넘나들 때 로고 위치·순서가 바뀌어 보이므로 컴포넌트 하나로 둔다.
// 탭 순서는 common/fe/links.ts 의 TABS 하나만 보면 된다 (Zzal → Trailer → Webtoon).
//
// "use client" 인 이유: 현재 경로를 알아야 열려 있는 탭을 표시할 수 있어서다.
// (로그인 상태와 모달도 클라이언트에서만 도는 것들이라 같은 이유로 여기 들어온다.)
import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import ThemeToggle from "./theme/ThemeToggle";
import TabLink from "./TabLink";
import AuthModal from "./auth/AuthModal";
import { useAuth } from "./auth/useAuth";
import { TABS } from "./links";
import styles from "./SiteHeader.module.css";

export default function SiteHeader() {
  const pathname = usePathname();
  const onZzal = pathname.startsWith("/zzal");

  const { status, user, isAuthenticated, signOut } = useAuth();
  const [authOpen, setAuthOpen] = useState(false);

  // 이메일 전체를 다 그리면 좁은 화면에서 헤더가 밀린다. 아이디 부분만 보여 주고
  // 전체는 title 로 남긴다(마우스를 올리면 보인다).
  const displayName = user ? user.email.split("@")[0] : "";

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

          {/* 로그인 여부를 아직 모르는 동안(첫 /users/me 조회)에는 같은 크기의 빈 자리를 둔다.
              "로그인" 을 먼저 그렸다가 로그인 상태로 바뀌면 헤더가 한 번 덜컹거린다.

              status 가 "unknown"(서버·네트워크 문제로 확인 실패)일 때도 로그인 버튼을 그린다.
              화면에는 누를 것이 있어야 하고, 정말 서버가 죽었다면 모달이 그 오류를 그대로
              보여 준다 — 헤더에서 미리 에러를 띄우는 것보다 그쪽이 맥락이 맞다. */}
          {status === "loading" ? (
            <span className={styles.authPlaceholder} aria-hidden="true" />
          ) : isAuthenticated ? (
            <>
              <span className={styles.userEmail} title={user?.email}>
                {displayName}
              </span>
              <button type="button" className={styles.authButton} onClick={() => void signOut()}>
                로그아웃
              </button>
            </>
          ) : (
            <button type="button" className={styles.authButton} onClick={() => setAuthOpen(true)}>
              로그인
            </button>
          )}

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

      {/* 모달은 항상 마운트해 둔다. 열림 상태만 넘겨서, 여는 순간의 이벤트 기록과
          포커스 되돌리기를 모달이 스스로 관리하게 한다. */}
      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)} />
    </header>
  );
}
