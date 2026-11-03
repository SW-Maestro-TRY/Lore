"use client";
/* 홈 소개 구역에 세계관 체험 넣기(하은 11/03, 시안 haeun/브랜드-UX-2026-11-02/시안-홈소개).
 * - PC(E안): 웹툰 소개(IntroReel) 위에 같은 모양의 체험 단락 — 왼쪽 글 · 단추, 오른쪽 세계 포스터 줄.
 * - 폰(D안): 체험 · 웹툰 두 카드(그림 · 세 단계 · 단추). 폰에서는 IntroReel 대신 이것만 보인다(CSS). */
import { useEffect, useRef } from "react";
import type { Go } from "../../lib/nav";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import type { PlayWorld } from "../play/playData";

/* 웹툰 카드 — 실제 1화 장이 천천히 흘러 내려간다(IntroReel 과 같은 속도 · 손대면 멈춤 · 끝에서 처음으로) */
const PAGES = Array.from({ length: 7 }, (_, i) => `/static/gallery/intro/mask-${i + 2}.jpg`);
function Reel() {
  const scr = useRef<HTMLDivElement>(null);
  const hold = useRef(false);
  useEffect(() => {
    const el = scr.current;
    if (!el) return;
    const still = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    let raf = 0, last = 0, y = el.scrollTop, wait = 0;
    const tick = (now: number) => {
      const dt = last ? Math.min(50, now - last) : 0;
      last = now;
      const max = el.scrollHeight - el.clientHeight;
      if (!still && !hold.current && max > 0 && !document.hidden && el.offsetParent) {
        if (wait > 0) { wait -= dt; if (wait <= 0) { y = 0; el.scrollTop = 0; } }
        else { y = Math.min(max, y + (0.55 * dt) / 16); el.scrollTop = y; if (y >= max) wait = 1400; }
      } else y = el.scrollTop;
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);
  const stop = () => { hold.current = true; };
  const go = () => { hold.current = false; };
  return (
    <div className="reel" ref={scr} onTouchStart={stop} onTouchEnd={go} onMouseEnter={stop} onMouseLeave={go}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {PAGES.map((src) => <img key={src} src={src} alt="" loading="lazy" />)}
    </div>
  );
}

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
            <h2>{t("내 캐릭터로")}<br />{t("새로운 세계를 경험하기")}</h2>
          </div>
          <p className="wt-intro-sub">{t("세계와 사건을 고르고 내 캐릭터를 넣으면,")}<br />{t("캐릭터의 성격과 설정에 따라 이야기가 펼쳐져요.")}</p>
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
          <Reel />
          <ol><li><i>1</i>{t("캐릭터 넣기")}</li><li><i>2</i>{t("이야기 적기")}</li><li><i>3</i>{t("장면 확인하고 그리기")}</li></ol>
          <button type="button" className="btn btn-w" onClick={() => { track("landing_cta", { where: "cards_webtoon" }); go("create", { step: 1 }); }}>{t("웹툰 만들기")}</button>
        </div>
      </section>
    </>
  );
}
