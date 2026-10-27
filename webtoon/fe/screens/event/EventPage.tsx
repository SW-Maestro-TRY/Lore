"use client";
/* 출석 이벤트 페이지 — /webtoon?view=event (#862). 홈 배너 · 팝업 · 인스타 글이 여기로 온다.
 *
 * 7칸 카드와 「오늘 받기」(본문 · 하단 고정 둘 다). 7일째를 받으면 완료 창이 뜨고
 * 「내 캐릭터로 웹툰 만들기」가 만들기로 간다. 로그인 안 했으면 받기 대신 로그인으로 보낸다. */
import { useEffect, useState } from "react";
import type { Attendance } from "@common/api/credits";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { EventFonts, Witch } from "./EventBits";
import { THEMES, type EventTheme } from "./theme";
import { useAttendance } from "./useAttendance";
import "./event.css";

function guessTheme(): Attendance["theme"] {
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date());
  return today > "2026-11-03" ? "MASQUERADE" : "HALLOWEEN";
}

export default function EventPage({ go, authenticated }: { go: Go; authenticated: boolean }) {
  const { card, busy, error, claim } = useAttendance(authenticated);
  const [fresh, setFresh] = useState<number | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [fin, setFin] = useState(false);
  const th: EventTheme = THEMES[card?.theme ?? guessTheme()];

  useEffect(() => { track("event_page_view", { theme: th.key, signed_in: authenticated }); }, [th.key, authenticated]);
  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(null), 2400);
    return () => window.clearTimeout(id);
  }, [toast]);
  /* 팝업에서 7일째를 받고 넘어온 경우에도 완료 창을 띄운다. */
  useEffect(() => {
    if (card?.claimed && card.today === 7 && card.stamped === 7) setFin(true);
  }, [card?.claimed, card?.today, card?.stamped]);

  const stamped = card?.stamped ?? 0;
  const claimed = card?.claimed ?? false;
  const closed = card != null && !card.open;
  const full = claimed && stamped === 7;

  const take = async () => {
    if (!authenticated) { track("event_login_click", { theme: th.key }); go("mypage"); return; }
    if (claimed || busy || closed) return;
    track("event_claim_click", { theme: th.key, day: card?.today });
    const after = await claim();
    if (!after) return;
    setFresh(after.today);
    if (after.today === 7) setFin(true);
    else setToast(th.toast(after.today, after.granted));
  };

  const cta = !authenticated ? "로그인하고 받기"
    : closed ? "10.28부터 받을 수 있어요"
    : full ? `이번 주 ${th.item} 다 모았어요`
    : claimed ? `오늘 ${th.item} 받음`
    : busy ? "받는 중…" : `오늘 ${th.item} 받기`;
  const sticky = !authenticated ? `${th.key === "HALLOWEEN" ? "🍬" : "✦"} 로그인하고 받기`
    : full ? `${th.key === "HALLOWEEN" ? "🎃" : "✦"} 웹툰 만들러 가기`
    : claimed ? `오늘 ${th.item} 받음 · 내일 또 와요` : cta;

  return (
    <div className="wt-ev-root" data-theme={th.key}>
      <EventFonts th={th} />
      <main className="wt-ev-page">
        <div className="wt-ev-hero">
          {th.key === "HALLOWEEN" ? <Witch th={th} /> : (
            /* eslint-disable-next-line @next/next/no-img-element */
            <img className="wt-ev-kv" src={th.art.hero} alt="" />
          )}
          <div className="wt-ev-ov" />
          <div className="wt-ev-hero-t">
            <span className="wt-ev-badge">{th.badge}</span>
            <span className="wt-ev-eyebrow">{th.eyebrow}</span>
            <h1>{th.heroTitle}</h1>
            <div className="wt-ev-period"><b>참여 기간</b><span>{th.period}</span> · <span>LORE 회원 전원</span></div>
          </div>
        </div>

        <section className="wt-ev-sec">
          <span className="wt-ev-pill">EVENT 1</span>
          <h2>{th.ev1Title}</h2>
          <p className="wt-ev-s">{th.ev1Desc}</p>
          <div className="wt-ev-strip">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => {
              const got = d <= stamped;
              const today = authenticated && !claimed && !closed && d === stamped + 1;
              const sub = d === 4 ? "+5C" : d === 7 ? "+20C · 웹툰 한 편" : got ? "받음" : "";
              return (
                <div key={d} className={`wt-ev-tk${got ? " got" : ""}${today ? " today" : ""}${d === 7 ? " big" : ""}${fresh === d ? " new" : ""}`}>
                  <div className="wt-ev-icw">{th.token(d, got)}</div>
                  <b>DAY {d}</b>
                  <small>{sub}</small>
                </div>
              );
            })}
          </div>
          <button type="button" className={`wt-ev-cta${claimed || closed ? " done" : ""}`} disabled={busy} onClick={take}>{cta}</button>
          {error && <p className="wt-ev-err">{error}</p>}
        </section>

        <section className="wt-ev-sec">
          <span className="wt-ev-pill">EVENT 2</span>
          <h2>{th.ev2Title}</h2>
          <div className="wt-ev-ev2">
            <div className="n">+15<small>크레딧</small></div>
            <div><b>가입하면 바로 15크레딧</b><span>웹툰 한 편을 만들 수 있는 양 · 가입 즉시 지급</span></div>
          </div>
        </section>

        <section className="wt-ev-sec">
          <span className="wt-ev-pill">HOW TO</span>
          <h2>참여 방법</h2>
          <ol className="wt-ev-how">
            <li><i>1</i>LORE에 로그인</li>
            <li><i>2</i>{th.howStep2}</li>
            <li><i>3</i>{th.howStep3}</li>
            <li><i>4</i>받은 크레딧으로 내 캐릭터 웹툰 만들기</li>
          </ol>
        </section>

        <div className="wt-ev-notice">
          <b>유의사항</b>
          <ul>
            <li>· 로그인한 회원만 참여할 수 있어요.</li>
            <li>· 하루라도 쉬면 1일째부터 다시 시작해요. 날짜는 한국 시간 자정에 바뀌어요.</li>
            <li>· 받은 크레딧은 무료 크레딧이라 쓸 때 먼저 빠지고, 환불되지 않아요.</li>
            <li>· 부정한 방법으로 받은 크레딧은 회수할 수 있어요.</li>
            <li>· 이벤트 내용은 사정에 따라 바뀔 수 있어요.</li>
          </ul>
        </div>

        <div className="wt-ev-sticky">
          <button type="button" className={claimed && !full ? "done" : ""} disabled={busy}
                  onClick={full ? () => go("create", { step: 1 }) : take}>{sticky}</button>
        </div>
      </main>

      {fin && (
        <div className="wt-ev-fin" onClick={() => setFin(false)}>
          <div className="wt-ev-fin-box" role="dialog" aria-modal="true" aria-labelledby="wt-ev-fin-title" onClick={(e) => e.stopPropagation()}>
            <div className="big">{th.finIcon}</div>
            <h2 id="wt-ev-fin-title">{th.finTitle}</h2>
            <p>{th.finDesc}</p>
            <span className="amt">+20 크레딧</span>
            <button type="button" className="go" autoFocus onClick={() => { track("event_fin_create", { theme: th.key }); go("create", { step: 1 }); }}>내 캐릭터로 웹툰 만들기</button>
            <button type="button" className="later" onClick={() => setFin(false)}>나중에</button>
          </div>
        </div>
      )}
      {toast && <div className="wt-ev-toast" role="status">{toast}</div>}
    </div>
  );
}
