"use client";
import "./i18n";
import Link from "next/link";
import {SELLER_READY,sellerLine,CONTACT_CHANNEL} from "@common/links";
import {LangSwitch,useT} from "../../lib/i18n";
import type {Go} from "../../lib/nav";
import GalleryHome from "./GalleryHome";
import "./Landing.css";
export default function Landing({go}:{go:Go}){const t=useT();return <GalleryHome go={go}>
      <footer className="wt-landing-foot">
        <b>{t("LORE 웹툰 스튜디오")}</b>
        <nav>
          <Link href="/legal/terms">{t("이용약관")}</Link>
          <Link href="/legal/privacy">{t("개인정보처리방침")}</Link>
          <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">{t("1:1 문의")}</a>
        </nav>
        <div className="wt-landing-badges">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/asm-icon.png" alt="" />AI SW MAESTRO</span>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/msit-icon.png" alt="" />{t("과학기술정보통신부")}</span>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/iitp-icon.png" alt="" />{t("정보통신기획평가원(IITP)")}</span>
        </div>
        <LangSwitch className="wt-landing-lang" />
        {/* 실제로 파는 서비스처럼 보이려면 누가 운영하고 어디로 연락하는지가 있어야 한다(#252).
            운영 주체는 약관 제1조의 「TRY팀」, 연락처는 약관·처리방침에 적힌 주소와 같다.
            사업자등록번호·통신판매업 신고번호는 등록되면 여기 한 줄 더 붙인다. */}
        <div className="dim wt-landing-biz">
          {/* 판매자 정보 줄(아래)에 상호·문의가 같이 있으면 여기서는 빼서 두 번 안 보이게 한다. */}
          {!SELLER_READY && <span>{t("운영")} {t("TRY팀")}</span>}
          {!SELLER_READY && <span>{t("문의")} <a href="mailto:lore@lorecomic.com">lore@lorecomic.com</a></span>}
          <span>{t("AI SW MAESTRO 17기 프로젝트")}</span>
        </div>
        {/* 유료 충전을 받으면 판매자 정보를 표시해야 한다(전자상거래법 제10조). 주소가 비면 안 그린다. */}
        {SELLER_READY && <div className="dim wt-landing-biz">{sellerLine()}</div>}
        <span className="dim wt-landing-copy">{t("© 2026 LORE · TRY팀")}</span>
      </footer>
</GalleryHome>;}
