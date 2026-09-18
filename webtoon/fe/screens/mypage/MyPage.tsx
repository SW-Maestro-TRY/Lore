"use client";

/* 마이페이지 — **백로그.** (2026-09-19 결정) 지금은 내 작품·내 캐릭터로 가는
 * 문만 둔다. 계정·크레딧·로그아웃은 공용 헤더와 마이페이지 공용 화면이 맡는다. */
import { useAuth } from "@common/auth/useAuth";
import type { Go } from "../../lib/nav";

export default function MyPage({ go }: { go: Go }) {
  const { user, isAuthenticated, signOut } = useAuth();
  return (
    <div className="wt-wrap wt-page" style={{ gap: 18, maxWidth: 720 }}>
      <h2 style={{ fontSize: 28 }}>마이페이지</h2>
      <p className="muted" style={{ fontSize: 15 }}>
        {isAuthenticated ? `${user?.email} 로 로그인했어요.` : "로그인하면 만든 것이 계정에 남아요."}
      </p>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
        <button type="button" className="btn btn-p" onClick={() => go("works")}>내 작품 보기</button>
        <button type="button" className="btn btn-w" onClick={() => go("characters")}>내 캐릭터</button>
        {isAuthenticated && (
          <button type="button" className="btn btn-w" onClick={() => void signOut()}>로그아웃</button>
        )}
      </div>
    </div>
  );
}
