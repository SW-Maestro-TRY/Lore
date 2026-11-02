"use client";
/* 만들기 — 웹툰 만들기 / 세계관 만들기 둘 중 하나. 내 연재는 마이페이지에 있다. */
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import "./i18n";
import "./Play.css";

export default function MakeChoice({ go }: { go: Go }) {
  const t = useT();
  return (
    <div className="pl"><div className="pl-make">
      <button type="button" onClick={() => go("create", { step: 1 })}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/static/entry/webtoon-page.jpg" alt="" /><b>{t("웹툰 만들기")}</b>
      </button>
      <button type="button" onClick={() => go("world-new")}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/static/entry/character.jpg" alt="" /><b>{t("세계관 만들기")}</b>
      </button>
    </div></div>
  );
}
