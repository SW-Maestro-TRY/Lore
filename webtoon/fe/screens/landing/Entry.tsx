"use client";

/* 입구 — 보드 Entry.dc.html(PC) · MEntry.dc.html(폰). 카드 둘 중 하나를 고른다. */
import "./i18n";
import * as api from "../../lib/api";
import { useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { IconUser } from "../../ui/Icons";
import { usePhone } from "./usePhone";
import "./Entry.css";

/* 카드 표지는 고정 예시 두 편을 지정해서 쓴다. 그림 자체가 안 나오면(작품이
   빠지는 등) 아래 견본 그림으로 대체한다. */
const COVER_A = api.coverUrl("20260919T153128-5f3882", 5); // 그림자 위의 장미
const COVER_B = api.coverUrl("20260919T153345-f366ce", 1); // 가면 아래의 대리인
const FALLBACK_A = "/static/samples/onboarding-page.jpg";
const FALLBACK_B = "/static/samples/ex-romance-2.jpg";

const IconCamera = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
  </svg>
);

export default function Entry({ go }: { go: Go }) {
  const t = useT();
  const phone = usePhone();
  const to = (fn: () => void) => (ev: React.MouseEvent) => { ev.preventDefault(); fn(); };
  const onImgError = (fallback: string) => (ev: React.SyntheticEvent<HTMLImageElement>) => {
    ev.currentTarget.onerror = null;
    ev.currentTarget.src = fallback;
  };

  return (
    <div className="wt-wrap wt-page wt-entry">
      <div className="wt-entry-head">
        <h2 style={phone ? { whiteSpace: "pre-line" } : undefined}>{t(phone ? "LORE에서\n무엇을 해볼까요?" : "LORE에서 무엇을 해볼까요?")}</h2>
      </div>

      <div className="wt-entry-cards">
        <a href={hrefOf("create", { step: 1 })} className="wt-entry-card on" onClick={to(() => go("create", { step: 1 }))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_A} alt="" onError={onImgError(FALLBACK_A)} />
            <span className="wt-entry-tag">{t("웹툰 만들기")}</span>
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconUser size={20} /></span><b>{t("내 캐릭터로 바로 웹툰을 만들고 싶어요")}</b></div>
            <span className="muted">{t("내가 가진 캐릭터, 최애, 이미지, 설정으로 바로 웹툰을 만들어요.")}</span>
          </div>
        </a>

        <a href={hrefOf("try")} className="wt-entry-card" onClick={to(() => go("try"))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_B} alt="" onError={onImgError(FALLBACK_B)} />
            <span className="wt-entry-tag">{t("캐릭터 만들어보기")}</span>
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconCamera /></span><b>{t("캐릭터를 만들어보고 싶어요")}</b></div>
            <span className="muted">
              {t(phone ? "사진이든 설명이든, 아무것도 없어도 돼요. 뭐든 웹툰 속 캐릭터가 돼요." : "내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요. 뭐든 넣으면 웹툰 속 캐릭터가 돼요.")}
            </span>
          </div>
        </a>
      </div>
    </div>
  );
}
