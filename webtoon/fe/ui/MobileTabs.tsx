"use client";
/* 폰에서만 보이는 아래 탭(2026-11-03). PC 는 공용 헤더 하나뿐이다. */
import type { Go, View } from "../lib/nav";
import { useT } from "../lib/i18n";
import { IconGlobe, IconGrid, IconPlus, IconUser } from "./Icons";
import "./MobileTabs.css";

const HomeIcon = () => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z" />
  </svg>
);
const SHOWN: View[] = ["landing", "play", "make", "world-new", "works", "characters", "mypage"];

export default function MobileTabs({ view, stage, go }: { view: View; stage?: string; go: Go }) {
  const t = useT();
  if (!SHOWN.includes(view)) return null;
  const tab = (id: View, label: string, icon: React.ReactNode, onClick: () => void) => (
    <button type="button" className={view === id ? "on" : ""} onClick={onClick}>{icon}<span>{t(label)}</span></button>
  );
  return (
    <nav className="wt-tabs" aria-label={t("주요 메뉴")}>
      {tab("landing", "홈", <HomeIcon />, () => go("landing"))}
      {tab("play", "세계관", <IconGlobe />, () => go("play"))}
      <button type="button" className="mk" onClick={() => go("make")}><i><IconPlus /></i><span>{t("만들기")}</span></button>
      {tab("works", "둘러보기", <IconGrid />, () => go("works"))}
      {tab("mypage", "내 것", <IconUser />, () => go("mypage"))}
    </nav>
  );
}
