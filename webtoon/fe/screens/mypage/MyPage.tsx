"use client";

/* 마이페이지 — **백로그.** (2026-09-19 결정) 지금은 내 작품·내 캐릭터로 가는
 * 문만 둔다. 계정·크레딧·로그아웃은 공용 헤더와 마이페이지 공용 화면이 맡는다. */
import { useAuth } from "@common/auth/useAuth";
import type { Go } from "../../lib/nav";
import { LangSwitch, useT } from "../../lib/i18n";

import { registerDict } from "../../lib/i18n";
registerDict({
  "마이페이지": { en: "My page", ja: "マイページ", zh: "我的页面" },
  "{email} 로 로그인했어요.": { en: "Signed in as {email}.", ja: "{email} でログイン中です。", zh: "已以 {email} 登录。" },
  "로그인하면 만든 것이 계정에 남아요.": { en: "Sign in to keep what you make on your account.", ja: "ログインすると、作ったものがアカウントに残ります。", zh: "登录后，你创作的内容会保存在账号里。" },
  "내 작품 보기": { en: "My webtoons", ja: "マイ作品を見る", zh: "查看我的作品" },
  "내 캐릭터": { en: "My characters", ja: "マイキャラクター", zh: "我的角色" },
  "로그아웃": { en: "Sign out", ja: "ログアウト", zh: "退出登录" },
  "언어": { en: "Language", ja: "言語", zh: "语言" },
});

export default function MyPage({ go }: { go: Go }) {
  const { user, isAuthenticated, signOut } = useAuth();
  const t = useT();
  return (
    <div className="wt-wrap wt-page" style={{ gap: 18, maxWidth: 720 }}>
      <h2 style={{ fontSize: 28 }}>{t("마이페이지")}</h2>
      <p className="muted" style={{ fontSize: 15 }}>
        {isAuthenticated ? t("{email} 로 로그인했어요.", { email: user?.email ?? "" }) : t("로그인하면 만든 것이 계정에 남아요.")}
      </p>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
        <button type="button" className="btn btn-p" onClick={() => go("works")}>{t("내 작품 보기")}</button>
        <button type="button" className="btn btn-w" onClick={() => go("characters")}>{t("내 캐릭터")}</button>
        {isAuthenticated && (
          <button type="button" className="btn btn-w" onClick={() => void signOut()}>{t("로그아웃")}</button>
        )}
      </div>
      <div className="fieldset" style={{ marginTop: 8 }}>
        <label>{t("언어")}</label>
        <LangSwitch />
      </div>
    </div>
  );
}
