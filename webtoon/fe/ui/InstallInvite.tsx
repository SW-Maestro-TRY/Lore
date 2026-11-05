"use client";

import { useEffect, useState } from "react";
import { registerDict, useT } from "../lib/i18n";
import { canInstall, isIos, isStandalone, onInstallChange, promptInstall } from "../lib/push";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";
import "./InstallInvite.css";

registerDict({
  "LORE를 홈 화면에 추가해요": { en: "Add LORE to your Home Screen", ja: "LOREをホーム画面に追加", zh: "将 LORE 添加到主屏幕" },
  "만드는 동안 추가해두면 다음에 바로 열 수 있어요.": { en: "Add it while your comic is being made, then open it instantly next time.", ja: "制作を待つ間に追加すると、次からすぐ開けます。", zh: "制作期间添加，下次就能直接打开。" },
  "홈 화면에 추가": { en: "Add to Home Screen", ja: "ホーム画面に追加", zh: "添加到主屏幕" },
  "추가 방법 보기": { en: "Show me how", ja: "追加方法を見る", zh: "查看添加方法" },
  "나중에": { en: "Later", ja: "あとで", zh: "稍后" },
  "공유 버튼을 누른 뒤 ‘홈 화면에 추가’를 선택하세요.": { en: "Tap Share, then Add to Home Screen.", ja: "共有をタップし、「ホーム画面に追加」を選んでください。", zh: "点击分享，然后选择“添加到主屏幕”。" },
  "브라우저 메뉴(⋮)에서 ‘홈 화면에 추가’를 선택하세요.": { en: "In your browser menu (⋮), choose Add to Home Screen.", ja: "ブラウザのメニュー（⋮）から「ホーム画面に追加」を選んでください。", zh: "在浏览器菜单（⋮）中选择“添加到主屏幕”。" },
  "알겠어요": { en: "Got it", ja: "わかりました", zh: "知道了" },
});

const SEEN_KEY = "lore_install_invite_seen_v1";

export default function InstallInvite({ active, where, job }: { active: boolean; where: "progress" | "play"; job?: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [guide, setGuide] = useState(false);
  const [installable, setInstallable] = useState(false);
  const [ios, setIos] = useState(false);
  const [mobile, setMobile] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setInstallable(canInstall());
    setIos(isIos());
    setMobile(window.matchMedia("(max-width: 767px)").matches);
    const off = onInstallChange(() => setInstallable(canInstall()));
    const installed = () => setOpen(false);
    window.addEventListener("appinstalled", installed);
    return () => { off(); window.removeEventListener("appinstalled", installed); };
  }, []);

  useEffect(() => {
    if (!active) { setOpen(false); return; }
    /* 홈 화면은 폰·태블릿에만 있다 — PC 에서는 띄우지 않는다 */
    if (isStandalone() || !(isIos() || window.matchMedia("(pointer: coarse)").matches)) return;
    try { if (sessionStorage.getItem(SEEN_KEY)) return; } catch { /* 저장을 못 해도 안내는 가능 */ }
    const timer = window.setTimeout(() => {
      if (isStandalone()) return;
      try { sessionStorage.setItem(SEEN_KEY, "1"); } catch { /* 없어도 진행 */ }
      setOpen(true);
      track("install_invite_shown", { where, job });
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [active, where, job]);

  const dismiss = () => {
    track("install_invite_dismissed", { where, job, kind: guide ? "guide" : "invite" });
    setOpen(false);
  };
  const showGuide = () => {
    track("install_guide_view", { where, job, kind: ios ? "ios" : mobile ? "mobile" : "desktop" });
    setGuide(true);
  };
  const choose = async () => {
    track("install_invite_click", { where, job, kind: installable ? "prompt" : "guide" });
    if (!installable) { showGuide(); return; }
    setBusy(true);
    try {
      const accepted = await promptInstall();
      track("install_prompt_choice", { where, job, result: accepted ? "accepted" : "dismissed" });
      setOpen(false);
    } catch {
      showGuide();
    } finally {
      setBusy(false);
    }
  };

  if (!open || !active) return null;
  /* iOS 16.4 부터는 Safari 가 아닌 브라우저도 공유 메뉴에서 홈 화면에 추가할 수 있다 */
  const instructions = ios
    ? t("공유 버튼을 누른 뒤 ‘홈 화면에 추가’를 선택하세요.")
    : t("브라우저 메뉴(⋮)에서 ‘홈 화면에 추가’를 선택하세요.");
  return <Dialog title={t("LORE를 홈 화면에 추가해요")} onClose={dismiss} busy={busy} className="wt-install-invite">
    <div className="wt-install-invite-body">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/icons/icon-192.png" alt="" />
      <p>{guide ? instructions : t("만드는 동안 추가해두면 다음에 바로 열 수 있어요.")}</p>
    </div>
    <div className="wt-dialog-actions">
      {guide ? <button type="button" className="btn btn-p" onClick={dismiss}>{t("알겠어요")}</button> : <>
        <button type="button" className="btn btn-w" onClick={dismiss}>{t("나중에")}</button>
        <button type="button" className="btn btn-p" disabled={busy} onClick={() => void choose()}>{t(installable ? "홈 화면에 추가" : "추가 방법 보기")}</button>
      </>}
    </div>
  </Dialog>;
}
