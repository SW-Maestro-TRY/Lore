"use client";

/* 입구 — 보드 Entry.dc.html(PC) · MEntry.dc.html(폰). 카드 둘 중 하나를 고른다. */
import * as api from "../../lib/api";
import { hrefOf, type Go } from "../../lib/nav";
import { IconArrow, IconBack, IconUser } from "../../ui/Icons";
import { usePhone } from "./usePhone";
import "./Entry.css";

const COVER_A = api.coverUrl("20260903T174524-309e57", 1, 1, true);
const COVER_B = api.coverUrl("20260906T124144-63e2a7", 1, 1, true);

const IconCamera = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
  </svg>
);

export default function Entry({ go }: { go: Go }) {
  const phone = usePhone();
  const to = (fn: () => void) => (ev: React.MouseEvent) => { ev.preventDefault(); fn(); };
  const arrow = <IconArrow size={phone ? 14 : 16} />;

  return (
    <div className="wt-wrap wt-page wt-entry">
      <div className="wt-entry-head">
        <h2>{phone ? <>LORE에서<br />무엇을 해볼까요?</> : "LORE에서 무엇을 해볼까요?"}</h2>
        <span className="muted">{phone ? "둘 다 마지막엔 웹툰이에요." : "둘 다 마지막엔 웹툰이에요. 만든 캐릭터는 저장돼서 다음엔 바로 웹툰으로 갑니다."}</span>
      </div>

      <div className="wt-entry-cards">
        <a href={hrefOf("create", { step: 1 })} className="wt-entry-card on" onClick={to(() => go("create", { step: 1 }))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_A} alt="" />
            <span className="wt-entry-tag">웹툰 만들기</span>
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconUser size={20} /></span><b>내 캐릭터로 바로 웹툰을 만들고 싶어요</b></div>
            <span className="muted">내가 가진 캐릭터, 최애, 이미지, 설정으로 바로 웹툰을 만들어요.</span>
            <span className="wt-entry-go">이걸로 만들기 {arrow}</span>
          </div>
        </a>

        <a href={hrefOf("try")} className="wt-entry-card" onClick={to(() => go("try"))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_B} alt="" />
            <span className="wt-entry-tag">캐릭터 만들어보기</span>
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconCamera /></span><b>캐릭터를 만들어보고 싶어요</b></div>
            <span className="muted">
              {phone ? "사진이든 설명이든, 아무것도 없어도 돼요. 뭐든 웹툰 속 캐릭터가 돼요." : "내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요. 뭐든 넣으면 웹툰 속 캐릭터가 돼요."}
            </span>
            <span className="wt-entry-go">이걸로 만들기 {arrow}</span>
          </div>
        </a>
      </div>

      <div className="wt-entry-back">
        <a href={hrefOf("landing")} onClick={to(() => go("landing"))}><IconBack size={16} /> 처음으로</a>
      </div>
    </div>
  );
}
