"use client";
import "./i18n";
import Link from "next/link";
import {SELLER,SELLER_READY} from "@common/links";
import {LangSwitch,useT} from "../../lib/i18n";
import type {Go} from "../../lib/nav";
import GalleryHome from "./GalleryHome";
import "./Landing.css";
/* 첫 화면 아래 — 소개 · 서비스 · 법적 고지 · 고객지원 네 칸, 그 아래 저작권 줄(2026-10-26).
 * 실제로 파는 서비스처럼 보이려면 누가 운영하고 어디로 연락하는지가 있어야 한다(#252).
 * 유료 충전을 받으면 판매자 정보(상호·대표·주소·연락처)를 표시해야 한다(전자상거래법 제10조) —
 * 값은 common/links 의 SELLER 하나에서 읽고, 주소가 비면 운영·문의만 남긴다. */
export default function Landing({go}:{go:Go}){const t=useT();return <GalleryHome go={go}>
      <footer className="wt-landing-foot">
        <div className="wt-foot-cols">
          <div className="wt-foot-brand">
            <b>{t("LORE 웹툰 스튜디오")}</b>
            <p>{t("내 캐릭터가 주인공인 웹툰을 만드는 곳")}</p>
            <div className="wt-landing-badges">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <span><img src="/static/badges/asm-icon.png" alt="" />AI SW MAESTRO</span>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <span><img src="/static/badges/msit-icon.png" alt="" />{t("과학기술정보통신부")}</span>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <span><img src="/static/badges/iitp-icon.png" alt="" />{t("정보통신기획평가원(IITP)")}</span>
            </div>
            <span className="dim">{t("AI SW MAESTRO 17기 프로젝트")}</span>
          </div>
          <nav aria-label={t("서비스")}>
            <h3>{t("서비스")}</h3>
            <button type="button" onClick={() => go("create", { step: 1 })}>{t("웹툰 만들기")}</button>
            <button type="button" onClick={() => go("works")}>{t("둘러보기")}</button>
            <button type="button" onClick={() => go("mypage")}>{t("내 웹툰")}</button>
            <a href="#wt-faq">{t("자주 묻는 질문")}</a>
          </nav>
          <nav aria-label={t("법적 고지")}>
            <h3>{t("법적 고지")}</h3>
            <Link href="/legal/terms">{t("이용약관")}</Link>
            <Link href="/legal/privacy">{t("개인정보처리방침")}</Link>
          </nav>
          <div className="wt-foot-support">
            <h3>{t("고객지원")}</h3>
            <dl>
              <div><dt>{t("운영")}</dt><dd>{SELLER.name}</dd></div>
              {SELLER_READY && <div><dt>{t("대표")}</dt><dd>{SELLER.owner}</dd></div>}
              {SELLER_READY && <div><dt>{t("주소")}</dt><dd>{SELLER.address}</dd></div>}
              {SELLER.phone.trim() && <div><dt>{t("전화")}</dt><dd>{SELLER.phone}</dd></div>}
              <div><dt>{t("이메일")}</dt><dd><a href={`mailto:${SELLER.email}`}>{SELLER.email}</a></dd></div>
              {SELLER.mailOrderNo.trim() && <div><dt>{t("통신판매업")}</dt><dd>{SELLER.mailOrderNo}</dd></div>}
            </dl>
          </div>
        </div>
        <div className="wt-foot-bottom">
          <span className="wt-landing-copy">{t("© 2026 LORE · TRY팀. All rights reserved.")}</span>
          <LangSwitch className="wt-landing-lang" />
        </div>
        {/* 이용약관 제10조 3항(올린 자료의 책임)·제11조(생성물의 이용 범위)를 한 줄로 */}
        <p className="dim wt-foot-notice">{t("LORE는 AI 창작 도구를 제공해요. 올린 사진·그림과 만든 작품의 저작권 관련 책임은 이용자에게 있어요.")}</p>
      </footer>
</GalleryHome>;}
