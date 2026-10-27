"use client";
/* 출석 이벤트 — 홈 배너와 첫 진입 팝업(#862). 이벤트 페이지는 EventPage.tsx.
 *
 * 배너는 누구에게나 보인다(로그인 안 했으면 이벤트 페이지에서 로그인으로 안내한다).
 * 팝업은 로그인한 사람에게 하루 한 번 — 「사탕 받기」가 곧 그날 출석이고,
 * 「오늘 그만 보기」를 고르면 그날은 안 뜨지만 출석은 안 된다(이벤트 페이지에서 받을 수 있다). */
import { useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import type { Attendance } from "@common/api/credits";
import type { Go, View } from "../../lib/nav";
import { track } from "../../lib/track";
import { Hat, THEMES, popLine, type EventTheme } from "./theme";
import { useAttendance } from "./useAttendance";
import "./event.css";

/** 시안의 글꼴. 빌드 때 받지 않고 화면이 뜰 때 받는다 — 못 받아도 기본 글꼴로 보일 뿐이다. */
export function EventFonts({ th }: { th: EventTheme }) {
  return <link rel="stylesheet" href={th.fonts} precedence="default" />;
}

export function Witch({ th }: { th: EventTheme }) {
  return (
    <div className="wt-ev-witch">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={th.art.hero} alt="" />
      <Hat />
    </div>
  );
}

/** 첫 화면 맨 위 배너. 카드가 없으면(로그인 전 · 옛 서버) 날짜로 테마만 고른다. */
export function EventBanner({ go }: { go: Go }) {
  const { status } = useAuth();
  const { card } = useAttendance(status === "authenticated");
  const th = THEMES[card?.theme ?? guessTheme()];
  if (card && !card.open) return null;
  if (!card && !opened()) return null;
  return (
    <div className="wt-ev-root" data-theme={th.key}>
      <EventFonts th={th} />
      <button type="button" className="wt-ev-banner" onClick={() => { track("event_banner_click", { theme: th.key }); go("event"); }}>
        <Witch th={th} />
        <span className="wt-ev-banner-t">
          <strong>{th.title}</strong>
          <b>{th.bannerLine}</b>
          <span>{th.bannerSub}</span>
        </span>
        <span className="wt-ev-banner-go">{card?.claimed ? "출석 현황" : th.bannerGo}</span>
      </button>
    </div>
  );
}

const SKIP_KEY = "lore_attend_skip";
/* 만드는 중 · 편집실 · 만들기 · 이벤트 페이지 자신에서는 안 띄운다 — 일하는 도중에 가로막지 않는다. */
const QUIET: View[] = ["running", "editor", "create", "event", "admin", "admin-examples", "admin-works", "admin-payments"];

function todayKst(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date());
}

function skippedToday(): boolean {
  try { return localStorage.getItem(SKIP_KEY) === todayKst(); } catch { return false; }
}

/** 서버 카드가 없을 때만 쓰는 어림 — 한국 날짜로 11.04 부터 가면무도회. */
function guessTheme(): Attendance["theme"] {
  return todayKst() > "2026-11-03" ? "MASQUERADE" : "HALLOWEEN";
}

function opened(): boolean {
  return todayKst() >= "2026-10-28";
}

export function AttendancePopup({ authenticated, view, go }: { authenticated: boolean; view: View; go: Go }) {
  const { card, busy, error, claim } = useAttendance(authenticated);
  const [shown, setShown] = useState(false);
  const [done, setDone] = useState<Attendance | null>(null);

  useEffect(() => {
    if (!card || shown || QUIET.includes(view)) return;
    if (!card.open || card.claimed || skippedToday()) return;
    setShown(true);
    track("event_popup_view", { theme: card.theme, day: card.today });
  }, [card, view, shown]);

  useEffect(() => {
    if (!done) return;
    const id = window.setTimeout(() => setDone(null), 2400);
    return () => window.clearTimeout(id);
  }, [done]);

  if (done) {
    const th = THEMES[done.theme];
    return <div className="wt-ev-root" data-theme={th.key}><div className="wt-ev-toast" role="status">{th.toast(done.today, done.granted)}</div></div>;
  }
  if (!shown || !card || card.claimed) return null;
  const th = THEMES[card.theme];
  const close = () => setShown(false);
  const skip = () => {
    try { localStorage.setItem(SKIP_KEY, todayKst()); } catch { /* 다음에 또 뜰 뿐 */ }
    track("event_popup_skip", { theme: card.theme, day: card.today });
    close();
  };
  const take = async () => {
    track("event_popup_claim", { theme: card.theme, day: card.today });
    const after = await claim();
    if (!after) return;
    close();
    if (after.today === 7) go("event");      // 7일째는 이벤트 페이지의 완료 창으로
    else setDone(after);
  };
  return (
    <div className="wt-ev-root" data-theme={th.key}>
      <EventFonts th={th} />
      <div className="wt-ev-dim" onClick={skip}>
        <div className="wt-ev-pop" role="dialog" aria-modal="true" aria-labelledby="wt-ev-pop-title" onClick={(e) => e.stopPropagation()}>
          <div className="wt-ev-pop-ph">
            {th.key === "HALLOWEEN" ? (
              <div className="wt-ev-bust">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={th.art.pop} alt="" />
                <Hat />
              </div>
            ) : (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img className="wt-ev-kv" src={th.art.pop} alt="" />
            )}
            <span className="wt-ev-pop-day">DAY {card.today}</span>
            <span className="wt-ev-pop-ttl">{th.title}</span>
          </div>
          <div className="wt-ev-pop-bd">
            <h2 id="wt-ev-pop-title">{th.popTitle}</h2>
            <p>{popLine(th, card)}</p>
            <div className="wt-ev-dots" aria-hidden="true">
              {[1, 2, 3, 4, 5, 6, 7].map((d) => <span key={d} className={d <= card.stamped ? "" : "off"}>{th.dot(d)}</span>)}
            </div>
            <button type="button" className="wt-ev-pop-btn" autoFocus disabled={busy} onClick={take}>
              {busy ? "받는 중…" : `${th.item} 받기`}
            </button>
            {error && <p className="wt-ev-pop-err">{error}</p>}
            <button type="button" className="wt-ev-pop-skip" onClick={skip}>오늘 그만 보기</button>
          </div>
        </div>
      </div>
    </div>
  );
}
