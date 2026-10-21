"use client";

// 사이트 공통 상단 헤더.
//
// 랜딩(/)과 도메인 탭(/zzal, /piece-maker, /webtoon)이 같은 헤더를 쓴다.
// 헤더가 두 벌이면 탭을 넘나들 때 로고 위치·순서가 바뀌어 보이므로 컴포넌트 하나로 둔다.
// 탭 순서는 common/fe/links.ts 의 TABS 하나만 보면 된다 (Zzal → Piece Maker → Webtoon).
//
// "use client" 인 이유: 현재 경로를 알아야 열려 있는 탭을 표시할 수 있어서다.
// (로그인 상태와 모달도 클라이언트에서만 도는 것들이라 같은 이유로 여기 들어온다.)
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import TabLink from "./TabLink";
import AuthModal from "./auth/AuthModal";
import { creditBalance } from "./api/credits";
import { useAuth } from "./auth/useAuth";
import { TABS } from "./links";
import styles from "./SiteHeader.module.css";

/** 웹툰 탭의 마이페이지. 화면이 주소를 하나 갖고 있어서 헤더가 그냥 가리킨다. */
const MY_PAGE = "/webtoon?view=mypage";

export default function SiteHeader() {
  const pathname = usePathname();
  const router = useRouter();

  /* 웹툰 탭은 헤더에서 **로그인 하나만** 본다. (담당: 하은, #223) */
  const onWebtoon = pathname.startsWith("/webtoon");

  const { status, user, isAuthenticated, signOut } = useAuth();

  /* 헤더에 크레딧 칩은 두지 않는다(2026-10-12) — 좁은 폰에서 칩 때문에 탭이 밀려 Webtoon 탭이
     안 보였고, 잔액·충전은 이름을 누르면 가는 마이페이지에 이미 있다.
     잔액을 한 번 묻는 것만 남긴다: 그 질문(`/api/v1/credits/me`)이 오늘 몫을 챙겨 주는 길이라
     들어오자마자 부르면 오늘 것이 들어와 있다. */
  useEffect(() => {
    if (isAuthenticated) creditBalance().catch(() => { /* 못 받아도 화면엔 영향 없다 */ });
  }, [isAuthenticated]);
  const [authOpen, setAuthOpen] = useState(false);

  /* ★ 로그아웃은 한 번 묻는다(2026-10-08 #690). 운영에서 선물 화면을 닫으려다 헤더 "로그아웃" 을
     누르고 1초 뒤 같은 자리의 "로그인" 을 다시 누른 사람이 있었다(10/7 실측). 브라우저 confirm 은
     인앱 브라우저에서 막히거나 화면 전체를 덮어서, 버튼 밑에 작은 확인 칸을 띄운다. */
  const [confirmingLogout, setConfirmingLogout] = useState(false);
  /* 로그아웃 직후 1초 동안 같은 자리의 "로그인" 을 잠근다 — 연달아 누른 두 번째 탭이 곧장
     로그인 창을 여는 것을 막는다. */
  const [loginLocked, setLoginLocked] = useState(false);
  const confirmRef = useRef<HTMLDivElement>(null);

  /* 좁은 폰에서는 탭 줄이 옆으로 스크롤된다(헤더는 한 줄 고정). 그런데 스크롤이 늘 맨 앞이라
     갤럭시 S26(360px, 글꼴 크게)에서 지금 보는 Webtoon 탭이 화면 밖에 있었고, 잘린 탭 글자가
     오른쪽 단추 밑에 붙어 깨진 것처럼 보였다(2026-10-12 캡처). 그래서
     - 지금 탭이 보이게 스크롤을 옮기고
     - 잘린 쪽 가장자리를 흐리게 해서 더 있다는 것을 보인다(data-more-left / data-more-right). */
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const nav = navRef.current;
    if (!nav) return;
    const edges = () => {
      const more = nav.scrollWidth - nav.clientWidth > 1;
      nav.toggleAttribute("data-more-left", more && nav.scrollLeft > 1);
      nav.toggleAttribute("data-more-right", more && nav.scrollLeft + nav.clientWidth < nav.scrollWidth - 1);
    };
    const active = nav.querySelector<HTMLElement>('[aria-current="page"]');
    if (active && nav.scrollWidth > nav.clientWidth) {
      const right = active.offsetLeft + active.offsetWidth - nav.offsetLeft;
      if (right > nav.scrollLeft + nav.clientWidth) nav.scrollLeft = right - nav.clientWidth + 4;
    }
    edges();
    nav.addEventListener("scroll", edges, { passive: true });
    window.addEventListener("resize", edges);
    return () => {
      nav.removeEventListener("scroll", edges);
      window.removeEventListener("resize", edges);
    };
    // 로그인 단추가 이름으로 바뀌면 탭 줄 폭이 바뀐다 — 그때도 다시 맞춘다.
  }, [pathname, status]);

  useEffect(() => {
    if (!confirmingLogout) return;
    // 확인 칸 밖을 누르거나 Esc 면 그냥 접는다(취소와 같다).
    const onDown = (e: PointerEvent) => {
      if (confirmRef.current && !confirmRef.current.contains(e.target as Node)) setConfirmingLogout(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setConfirmingLogout(false); };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [confirmingLogout]);

  useEffect(() => {
    if (!loginLocked) return;
    const t = window.setTimeout(() => setLoginLocked(false), 1000);
    return () => window.clearTimeout(t);
  }, [loginLocked]);

  const doSignOut = () => {
    setConfirmingLogout(false);
    setLoginLocked(true);
    void signOut();
  };

  // 이메일 전체를 다 그리면 좁은 화면에서 헤더가 밀린다. 아이디 부분만 보여 주고
  // 전체는 title 로 남긴다(마우스를 올리면 보인다).
  const displayName = user ? user.email.split("@")[0] : "";

  return (
    /* 딸림창은 <header> **밖**에 둔다. 헤더에 backdrop-filter 가 걸려 있어서
       그 안에 있으면 position: fixed 가 뷰포트가 아니라 헤더(높이 60px)를
       기준으로 잡힌다 — 창이 위쪽으로 잘리고 어두운 막도 헤더만 덮는다. */
    <>
    <header className={styles.header}>
      <div className={styles.headerInner}>
        <Link href="/" className={styles.wordmark}>
          LORE<span className={styles.wordmarkDot}>.</span>
        </Link>

        <nav className={styles.nav} ref={navRef}>
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
          {/* 로그인 여부를 아직 모르는 동안(첫 /users/me 조회)에는 같은 크기의 빈 자리를 둔다.
              "로그인" 을 먼저 그렸다가 로그인 상태로 바뀌면 헤더가 한 번 덜컹거린다.

              status 가 "unknown"(서버·네트워크 문제로 확인 실패)일 때도 로그인 버튼을 그린다.
              화면에는 누를 것이 있어야 하고, 정말 서버가 죽었다면 모달이 그 오류를 그대로
              보여 준다 — 헤더에서 미리 에러를 띄우는 것보다 그쪽이 맥락이 맞다. */}
          {status === "loading" ? (
            <span className={styles.authPlaceholder} aria-hidden="true" />
          ) : isAuthenticated ? (
            /* 웹툰 탭에서는 **이름 하나**다. 눌러서 마이페이지로 가고,
               로그아웃은 그 안에 있다.

               이름이 곧 그 문인 이유: 헤더에 이름·마이페이지·로그아웃을
               늘어놓으면 만들던 사람 앞에 나가는 길만 셋이 된다. 그리고
               로그인한 사람이 자기 이름을 누르는 것은 어디서나 "내 자리로"
               라는 뜻이라, 라벨을 따로 달지 않아도 읽힌다. */
            onWebtoon ? (
              <Link href={MY_PAGE} className={styles.authButton} title={user?.email}>
                {displayName}
              </Link>
            ) : (
              <>
                <span className={styles.userEmail} title={user?.email}>
                  {displayName}
                </span>
                <div className={styles.logoutWrap} ref={confirmRef}>
                  <button type="button" className={styles.authButton}
                          data-action="logout"
                          aria-expanded={confirmingLogout}
                          onClick={() => setConfirmingLogout(true)}>
                    로그아웃
                  </button>
                  {confirmingLogout && (
                    <div className={styles.logoutConfirm} role="group" aria-label="로그아웃 확인">
                      <span className={styles.logoutConfirmText}>로그아웃할까요?</span>
                      <div className={styles.logoutConfirmActions}>
                        <button type="button" className={styles.logoutConfirmYes}
                                data-action="logout-confirm" onClick={doSignOut}>
                          로그아웃
                        </button>
                        <button type="button" className={styles.logoutConfirmNo}
                                data-action="logout-cancel" autoFocus
                                onClick={() => setConfirmingLogout(false)}>
                          취소
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </>
            )
          ) : (
            <button type="button" className={styles.authButton} data-action="login"
                    disabled={loginLocked}
                    onClick={() => setAuthOpen(true)}>
              로그인
            </button>
          )}
        </div>
      </div>

      {/* 모달은 항상 마운트해 둔다. 열림 상태만 넘겨서, 여는 순간의 이벤트 기록과
          포커스 되돌리기를 모달이 스스로 관리하게 한다. */}
      {/* 로그인에 성공하면 웹툰 탭에서는 곧장 마이페이지로 간다 — 여기서
          로그인하는 이유가 대개 "내가 만든 것을 보려고" 라서다. 다른 탭은
          하던 자리에 그대로 남는다(모달의 원래 뜻). */}
    </header>


    <AuthModal
      locale={pathname.startsWith("/zzal") ? "document" : undefined}
      open={authOpen}
      onClose={() => setAuthOpen(false)}
      onSuccess={onWebtoon ? () => router.push(MY_PAGE) : undefined}
    />
    </>
  );
}
