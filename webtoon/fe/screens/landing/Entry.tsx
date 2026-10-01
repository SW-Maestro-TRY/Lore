"use client";

/* 입구 — 보드 Entry.dc.html(PC) · MEntry.dc.html(폰). 카드 둘 중 하나를 고른다. */
import { useEffect, useState } from "react";
import "./i18n";
import * as api from "../../lib/api";
import { useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { usePhone } from "./usePhone";
import "./Entry.css";

/* 두 카드 그림은 정적 그림이다(webtoon/fe/static/entry). 카드 전체에 배경으로
   옅게 깔고 그 위에 글을 올린다.
   왼쪽(웹툰 만들기)은 「마탑의 실험용 캔」 3쪽의 위 세 컷을 말상자·말풍선까지 그대로
   — 실제 웹툰 한 장이 보이게 한다.
   오른쪽(캐릭터 만들어보기)은 캐릭터 「흑설」(노란 후드티 검은 여우) 카드 그림이다
   — "강아지도, 아무것도 없어도" 문구와 맞는 예시라 골랐다.
   창고의 쪽 그림(w=320)을 쓰면 카드 폭에 늘어나 흐려져서 정적 그림으로 뒀다. */
const COVER_A = "/static/entry/webtoon-page.jpg";
/* 폰 카드는 폭이 좁아서, 같은 쪽의 위 두 컷만 둔 그림을 쓴다. */
const COVER_A_PHONE = "/static/entry/webtoon-cut.jpg";
const COVER_B = "/static/entry/character.jpg";
const FALLBACK_A = "/static/samples/onboarding-page.jpg";
const FALLBACK_B = "/static/samples/ex-romance-2.jpg";

export default function Entry({ go }: { go: Go }) {
  const t = useT();
  const phone = usePhone();
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
        <h2 style={phone ? { whiteSpace: "pre-line" } : undefined}>{t(phone ? "LORE에서\n무엇을 해볼까요?" : "LORE에서 무엇을 해볼까요?")}</h2>
      </div>

      <div className="wt-entry-cards">
        <a href={hrefOf("create", { step: 1 })} className="wt-entry-card on" onClick={to(() => go("create", { step: 1 }))}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="wt-entry-bg" src={phone ? COVER_A_PHONE : COVER_A} alt="" onError={onImgError(FALLBACK_A)} />
          {createFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: createFree })}</span>}
          <div className="wt-entry-body">
            <span className="wt-entry-big">{t("웹툰 만들기")}</span>
            <span className="wt-entry-desc">{t("캐릭터와 스토리로 웹툰 1화를 생성해보아요")}</span>
          </div>
        </a>

        <a href={hrefOf("try")} className="wt-entry-card" onClick={to(() => go("try"))}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="wt-entry-bg wt-entry-bg-char" src={COVER_B} alt="" onError={onImgError(FALLBACK_B)} />
          {charFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: charFree })}</span>}
          <div className="wt-entry-body">
            <span className="wt-entry-big">{t("캐릭터 만들기")}</span>
            <span className="wt-entry-desc">{t("내가 ○○에 들어간다면? 재미있는 캐릭터를 만들어보아요")}</span>
          </div>
        </a>
      </div>
    </div>
  );
}
