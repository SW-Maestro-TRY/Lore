"use client";
/* 홈 소개 구역에 세계관 체험 넣기(하은 11/03, 시안 haeun/브랜드-UX-2026-11-02/시안-홈소개).
 * - PC(E안): 웹툰 소개(IntroReel) 위에 같은 모양의 체험 단락 — 왼쪽 글 · 단추, 오른쪽 세계 포스터 줄.
 * - 폰(D안): 체험 · 웹툰 두 카드(그림 · 세 단계 · 단추). 폰에서는 IntroReel 대신 이것만 보인다(CSS). */
import type { Go } from "../../lib/nav";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import type { PlayWorld } from "../play/playData";

export default function PlayIntro({ go, worlds }: { go: Go; worlds: PlayWorld[] }) {
  const t = useT();
  const play = (where: string) => { track("landing_cta", { where }); go("play"); };
  const first = worlds[0];
  const firstPic = first?.events.find((e) => e.image_url)?.image_url || first?.cover_url || "";
  return (
    <>
      <section className="wt-gallery-section wt-pintro">
        <div className="wt-pintro-copy">
          <div className="wt-gallery-heading">
            <span>{t("세계관 체험")}</span>
            <h2>{t("내 캐릭터를")}<br />{t("다른 세계에 넣어 보기")}</h2>
          </div>
          <p className="wt-intro-sub">{t("세계와 사건을 고르고 캐릭터를 넣으면,")}<br />{t("그 캐릭터가 겪는 장면이 만화로 나와요.")}</p>
          <button type="button" className="btn btn-p" onClick={() => play("intro_play")}>{t("세계관 체험하기")}</button>
        </div>
        <div className="wt-pintro-posters">
          {worlds.slice(0, 5).map((w) => (
            <button type="button" key={w.id} onClick={() => { track("landing_cta", { where: "intro_world" }); go("play", { world: w.id }); }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {w.cover_url ? <img src={w.cover_url} alt="" /> : <span />}
              <b>{t(w.name)}</b>
            </button>
          ))}
        </div>
      </section>

      <section className="wt-gallery-section wt-pcards">
        <h2>{t("내 캐릭터로 할 수 있는 것")}</h2>
        <div className="wt-pcard">
          <span className="k">{t("세계관 체험")}</span>
          <b className="t">{t("다른 세계에 넣어 보기")}</b>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {firstPic && <img src={firstPic} alt="" />}
          <ol><li><i>1</i>{t("세계 고르기")}</li><li><i>2</i>{t("사건 고르기")}</li><li><i>3</i>{t("내 캐릭터 넣기")}</li></ol>
          <button type="button" className="btn btn-p" onClick={() => play("cards_play")}>{t("세계관 체험하기")}</button>
        </div>
        <div className="wt-pcard">
          <span className="k">{t("LORE 웹툰")}</span>
          <b className="t">{t("내가 주인공인 웹툰")}</b>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/static/gallery/intro/mask-3.jpg" alt="" className="page" />
          <ol><li><i>1</i>{t("캐릭터 넣기")}</li><li><i>2</i>{t("이야기 적기")}</li><li><i>3</i>{t("장면 확인하고 그리기")}</li></ol>
          <button type="button" className="btn btn-w" onClick={() => { track("landing_cta", { where: "cards_webtoon" }); go("create", { step: 1 }); }}>{t("웹툰 만들기")}</button>
        </div>
      </section>
    </>
  );
}
