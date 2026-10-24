"use client";
import { useEffect, useRef, useState } from "react";
import { useT } from "../../lib/i18n";

/* 서비스 소개(확정 E안, haeun/service-intro-2026-10-24) — 왼쪽 폰 속에서 실제 1화가 천천히 흘러 내려가고,
 * 오른쪽에 무엇을 넣으면 무엇이 나오는지. 쪽마다 멈추지 않고 계속 흐른다(하은 결정, 2026-10-24).
 * 마우스를 올리거나 손으로 만지면 멈추고, 그때는 직접 내려 볼 수 있다. 끝까지 가면 처음으로 돌아간다.
 * 움직임 줄이기를 켠 사람에게는 흐르지 않고 손으로 내려 보는 화면이다. */
// 1쪽(표지)은 빼고 2쪽부터 — 표지 제목 글자가 소개 글과 겹쳐 보였다(하은, 2026-10-24)
const PAGES = Array.from({ length: 7 }, (_, i) => `/static/gallery/intro/mask-${i + 2}.jpg`);
const SPEED = 0.55; // 1/60초마다 내려가는 px — 0.9 는 빠르다는 피드백(2026-10-24)

export default function IntroReel() {
  const t = useT();
  const scr = useRef<HTMLDivElement>(null);
  const hold = useRef(false);
  /* 배경 시안 비교용(2026-10-24) — ?intro=wide 면 판 하나가 글까지 전체 폭, 아니면 판 없이 폰만. 고르면 하나만 남긴다. */
  const [wide, setWide] = useState(false);
  useEffect(() => { setWide(new URLSearchParams(location.search).get("intro") === "wide"); }, []);
  useEffect(() => {
    const el = scr.current;
    if (!el) return;
    const still = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    let raf = 0, last = 0, y = el.scrollTop, wait = 0;
    const tick = (now: number) => {
      const dt = last ? Math.min(50, now - last) : 0;
      last = now;
      const max = el.scrollHeight - el.clientHeight;
      if (!still && !hold.current && max > 0 && !document.hidden) {
        if (wait > 0) {
          wait -= dt;
          if (wait <= 0) { y = 0; el.scrollTop = 0; }
        } else {
          y = Math.min(max, y + (SPEED * dt) / 16);
          el.scrollTop = y;
          if (y >= max) wait = 1400;            // 끝에서 잠깐 머문 뒤 처음으로
        }
      } else y = el.scrollTop;                  // 멈춘 동안 손으로 내린 자리에서 이어 간다
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);
  const stop = () => { hold.current = true; };
  const go = () => { hold.current = false; };
  return (
    <section className={`wt-gallery-section wt-intro${wide ? " wide" : ""}`}>
      <div className="wt-intro-copy">
        <div className="wt-gallery-heading">
          <span>{t("LORE 웹툰")}</span>
          <h2>{t("내 캐릭터가 주인공인")}<br />{t("웹툰 1화")}</h2>
        </div>
        <p className="wt-intro-sub">{t("캐릭터와 하고 싶은 이야기를 넣으면,")}<br />{t("그 캐릭터로 그린 웹툰이 나와요.")}</p>
        <ol className="wt-intro-steps">
          <li><span>1</span><div><b>{t("캐릭터 넣기")}</b><small>{t("사진이나 설정, 내가 만든 캐릭터")}</small></div></li>
          <li><span>2</span><div><b>{t("이야기 적기")}</b><small>{t("하고 싶은 장면이나 줄거리")}</small></div></li>
          <li><span>3</span><div><b>{t("장면 확인하고 그리기")}</b><small>{t("그리기 전에 장면을 고칠 수 있어요")}</small></div></li>
        </ol>
      </div>
      <div className="wt-intro-stage">
        <div className="wt-intro-phone" onMouseEnter={stop} onMouseLeave={go} onTouchStart={stop} onTouchEnd={go}
             aria-label={t("가면을 쓰고 온 옛 약혼자 1화")}>
          <div className="wt-intro-screen" ref={scr}>
            {PAGES.map((src) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img key={src} src={src} alt="" loading="lazy" />
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
