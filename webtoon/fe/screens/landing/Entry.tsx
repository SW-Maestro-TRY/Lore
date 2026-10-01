"use client";

/* 입구 — 보드 Entry.dc.html(PC) · MEntry.dc.html(폰). 카드 둘 중 하나를 고른다. */
import { useEffect, useState } from "react";
import "./i18n";
import * as api from "../../lib/api";
import { useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { IconEdit, IconUser } from "../../ui/Icons";
import { usePhone } from "./usePhone";
import "./Entry.css";

/* 두 카드 그림은 「가면 아래의 대리인」에서 가져온 정적 그림이다(webtoon/fe/static/entry).
   왼쪽(웹툰 만들기)은 2쪽을 말상자까지 그대로 — 실제 웹툰 한 장이 보이게 한다.
   오른쪽(캐릭터 만들어보기)은 같은 쪽 첫 컷의 인물만 잘라 둔 것이다.
   창고의 쪽 그림(w=320)을 쓰면 카드 폭에 늘어나 흐려져서 정적 그림으로 뒀다. */
const COVER_A = "/static/entry/webtoon-page.jpg";
const COVER_B = "/static/entry/character.jpg";
const FALLBACK_A = "/static/samples/onboarding-page.jpg";
const FALLBACK_B = "/static/samples/ex-romance-2.jpg";

const IconCamera = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
  </svg>
);

/* 「만들고 싶은 내용이 있어요」 카드 그림 — 온보딩에 쓰는 「가면 아래의 조건」 한 장(정적). */
const COVER_OWN = "/static/samples/onboarding-page.jpg";

export default function Entry({ go, authenticated }: { go: Go; authenticated: boolean }) {
  const t = useT();
  const phone = usePhone();
  /* 「내 내용으로 시작하기」는 로그인한 사람만(#548) — 장면 확인에서 며칠이고 멈춰 있을 수
     있어서, 브라우저가 바뀌어도 찾아올 수 있어야 한다. 안내는 한 줄만(로그인 창은 공용 헤더 것). */
  const [ownNote, setOwnNote] = useState("");
  const startOwn = () => {
    if (!authenticated) {
      track("login_prompt", { where: "entry_own" });
      setOwnNote(t("로그인하면 내 내용으로 만들 수 있어요"));
      return;
    }
    go("create", { step: 1, mode: "own" });
  };
  /* 카드마다 남은 무료 횟수 — 왼쪽 카드는 웹툰 만들기(허용량), 오른쪽 카드는
     캐릭터 만들기(캐릭터 목록)가 각자 다른 자원이라 API 도 둘로 나뉜다. */
  const [createFree, setCreateFree] = useState<number | null>(null);
  const [charFree, setCharFree] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    api.readAllowance().then((a) => { if (alive) setCreateFree(a.free_left ?? null); }).catch(() => {});
    api.listCharacters().then((r) => { if (alive) setCharFree(r.free_left ?? null); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  const to = (fn: () => void) => (ev: React.MouseEvent) => { ev.preventDefault(); fn(); };
  const onImgError = (fallback: string) => (ev: React.SyntheticEvent<HTMLImageElement>) => {
    ev.currentTarget.onerror = null;
    ev.currentTarget.src = fallback;
  };

  return (
    <div className="wt-wrap wt-page wt-entry">
      <div className="wt-entry-head">
        <h2>{t("무엇을 만들고 싶나요?")}</h2>
      </div>

      {/* 웹툰 카드 둘은 「만들고 싶은 내용이 있나요?」로 갈린다(#548). 세 번째는 캐릭터 만들어보기 그대로. */}
      <div className="wt-entry-cards three">
        <a href={hrefOf("create", { step: 1 })} className="wt-entry-card on" onClick={to(() => go("create", { step: 1 }))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_A} alt="" onError={onImgError(FALLBACK_A)} />
            <span className="wt-entry-tag">{t("웹툰 만들기")}</span>
            {createFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: createFree })}</span>}
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconUser size={20} /></span><b>{t("아이디어부터 시작할게요")}</b></div>
            <span className="muted">{t("캐릭터를 바탕으로 AI 가 스토리를 만들어드려요.")}</span>
            <span className="wt-entry-go">{t("AI 와 함께 만들기")}</span>
          </div>
        </a>

        <a href={hrefOf("create", { step: 1, mode: "own" })} className="wt-entry-card" onClick={to(startOwn)}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_OWN} alt="" onError={onImgError(FALLBACK_B)} />
            <span className="wt-entry-tag">{t("웹툰 만들기")}</span>
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconEdit size={20} /></span><b>{t("만들고 싶은 내용이 있어요")}</b></div>
            <span className="muted">{t("내가 생각한 내용을 바탕으로 장면을 만들고 웹툰으로 완성해요.")}</span>
            <span className="wt-entry-go">{t("내 내용으로 시작하기")}</span>
            {ownNote && <span className="err">{ownNote}</span>}
          </div>
        </a>

        <a href={hrefOf("try")} className="wt-entry-card" onClick={to(() => go("try"))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_B} alt="" className="wt-entry-pic-left" onError={onImgError(FALLBACK_B)} />
            <span className="wt-entry-tag">{t("캐릭터 만들어보기")}</span>
            {charFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: charFree })}</span>}
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
