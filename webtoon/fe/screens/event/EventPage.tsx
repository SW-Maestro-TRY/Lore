"use client";
/* 출석 이벤트 페이지 — /webtoon?view=event (#862). 홈 배너 · 팝업 · 인스타 글이 여기로 온다.
 *
 * 7칸 카드와 「오늘 받기」(본문 · 하단 고정 둘 다). 7일째를 받으면 완료 창이 뜨고
 * 「내 캐릭터로 웹툰 만들기」가 만들기로 간다. 로그인 안 했으면 받기 대신 로그인으로 보낸다. */
import { useEffect, useState } from "react";
import type { Attendance } from "@common/api/credits";
import { creditBalance } from "@common/api/credits";
import { readAttendRemind, type AttendRemindState } from "../../lib/api";
import { daysToAfford } from "../../lib/attendPlan";
import { inviteLink, inviteSummary, type InviteSummary } from "../../lib/invite";
import type { Go } from "../../lib/nav";
import { copyLink, shareNative } from "../../lib/share";
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
  /* 다음 화 화면에서 「출석으로 모으기」를 고른 사람 — 그 화까지 며칠인지 한 줄(2026-11-03) */
  const [remind, setRemind] = useState<AttendRemindState | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  useEffect(() => {
    if (!authenticated) return;
    readAttendRemind().then(setRemind).catch(() => {});
  }, [authenticated]);
  useEffect(() => {
    if (!authenticated) return;
    creditBalance().then((b) => setBalance(b.balance)).catch(() => {});
  }, [authenticated, card?.claimed]);
  /* 친구 초대(EVENT 2) — 내 코드 · 가입한 친구 · 첫 웹툰까지 간 친구 · 받은 크레딧. 로그인한 사람만 읽는다 */
  const [invite, setInvite] = useState<InviteSummary | null>(null);
  const [inviteSaid, setInviteSaid] = useState<string | null>(null);
  useEffect(() => {
    if (!authenticated) return;
    inviteSummary().then(setInvite).catch(() => {});
  }, [authenticated]);
  useEffect(() => {
    if (!inviteSaid) return;
    const id = window.setTimeout(() => setInviteSaid(null), 2000);
    return () => window.clearTimeout(id);
  }, [inviteSaid]);
  const sendInvite = async () => {
    if (!invite) return;
    const url = inviteLink(invite.code);
    /* 폰은 자기 공유 화면을 띄운다. PC(없음)는 복사한다. 어느 쪽이든 복사는 해 둔다 */
    const native = await shareNative(url, "LORE 친구 초대");
    track("invite_link_copy", { where: "event", target: native ? "native" : "copy" });
    const copied = await copyLink(url);
    if (!native) setInviteSaid(copied ? "링크를 복사했어요" : "복사하지 못했어요");
  };

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
  /* 칸마다의 크레딧 — 로그인 전(카드 없음)에는 지금 정한 값을 보여 준다 */
  const amt = (d: number) => card?.rewards.find((r) => r.day === d)?.amount ?? (d === 4 ? 5 : d === 7 ? 10 : 2);
  const total = [1, 2, 3, 4, 5, 6, 7].reduce((n, d) => n + amt(d), 0);
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
    : full ? `${th.key === "HALLOWEEN" ? "🎃" : "✦"} 웹툰 만들러 가기`
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

        <section className="wt-ev-sec ev1">
          <span className="wt-ev-pill">EVENT 1</span>
          <h2>{th.ev1Title}</h2>
          <p className="wt-ev-s">{th.ev1Desc}</p>
          {/* 다음 화 화면에서 「출석으로 모으기」를 고른 사람 — 그 화까지 얼마나 모였나(2026-11-03).
              막대는 지금 잔액 / 그 화 크레딧. 다 모이면 막대가 차고 단추가 「이어 만들기」가 된다 */}
          {(() => {
            if (!remind?.on || remind.episode == null || remind.cost == null || balance == null || !remind.run) return null;
            const left = daysToAfford(balance, remind.cost, card);
            if (left == null) return null;
            const pct = Math.min(100, Math.round((balance / remind.cost) * 100));
            const run = remind.run;
            return (
              <div className={`wt-ev-goal${left === 0 ? " ready" : ""}`} role="group" aria-label={`${remind.episode}화 모으기`}>
                <div className="wt-ev-goal-t">
                  <b>{left === 0 ? `${remind.episode}화 만들 수 있어요` : `${remind.episode}화까지 ${left}일`}</b>
                  <span>{balance} / {remind.cost}C</span>
                </div>
                <div className="wt-ev-goal-bar"><i style={{ width: `${pct}%` }} /></div>
                <button type="button" className="wt-ev-goal-go" onClick={() => { track("event_goal_click", { ep: remind.episode, n: left }); go("next", { run }); }}>
                  {left === 0 ? `${remind.episode}화 이어 만들기` : `${remind.episode}화 보기`}
                </button>
              </div>
            );
          })()}
          <div className="wt-ev-strip">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => {
              const got = d <= stamped;
              const today = authenticated && !claimed && !closed && d === stamped + 1;
              const sub = got ? `+${amt(d)}C 받음` : d === 7 ? `+${amt(d)}C · 이번 주 ${total}C` : `+${amt(d)}C`;
              return (
                <div key={d} className={`wt-ev-tk${got ? " got" : ""}${today ? " today" : ""}${d === 7 ? " big" : ""}${fresh === d ? " new" : ""}`}>
                  <div className="wt-ev-icw">
                    {got && d !== 4 && d !== 7 ? <span className="wt-ev-stamp">{th.stamp}</span> : th.token(d)}
                    {got && (d === 4 || d === 7) && <span className="wt-ev-stamp-mini">{th.stamp}</span>}
                  </div>
                  <b>DAY {d}</b>
                  <small>{sub}</small>
                </div>
              );
            })}
          </div>
          {/* 7일을 다 모았으면 만들기로 — PC 에서는 하단 고정 단추를 숨겨서 이 단추가 그 길을 맡는다 */}
          <button type="button" className={`wt-ev-cta${(claimed && !full) || closed ? " done" : ""}`} disabled={busy}
                  onClick={full ? () => go("create", { step: 1 }) : take}>{cta}</button>
          {error && <p className="wt-ev-err">{error}</p>}
        </section>

        {/* 친구 초대(2026-11-03) — 친구가 가입하면 10C, 그 친구가 첫 웹툰을 완성하면 10C 더. 한 달 10명까지.
            숫자는 서버 값(InviteSummary)을 그대로 쓴다. 로그인 전에는 기본값으로 규칙만 보이고 단추는 로그인으로 */}
        <section className="wt-ev-sec invite">
          <span className="wt-ev-pill">EVENT 2</span>
          <h2>{th.inviteTitle}</h2>
          <p className="wt-ev-s">친구가 가입하면 {invite?.signupCredits ?? 10}크레딧, 첫 웹툰을 완성하면 {invite?.completeCredits ?? 10}크레딧 더 — 한 달 {invite?.monthlyCap ?? 10}명까지.</p>
          <div className="wt-ev-inv">
            <div className="wt-ev-inv-steps">
              <div className="wt-ev-inv-step">
                <span className="wt-ev-inv-ic">{th.inviteIcon}</span>
                <b>{th.inviteStep[0]}</b>
                <em>+{invite?.signupCredits ?? 10}C</em>
              </div>
              <span className="wt-ev-inv-arrow" aria-hidden="true">→</span>
              <div className="wt-ev-inv-step">
                <span className="wt-ev-inv-ic">{th.finIcon}</span>
                <b>{th.inviteStep[1]}</b>
                <em>+{invite?.completeCredits ?? 10}C</em>
              </div>
            </div>
            <div className="wt-ev-inv-nums">
              <div><b>{invite?.joined ?? 0}</b><span>가입한 친구</span></div>
              <div><b>{invite?.completed ?? 0}</b><span>첫 웹툰 완성</span></div>
              <div className="earn"><b>{invite?.earned ?? 0}<small>C</small></b><span>받은 크레딧</span></div>
            </div>
            {authenticated && invite ? (
              <>
                <div className="wt-ev-inv-link">
                  <code>{inviteLink(invite.code).replace(/^https?:\/\//, "").replace(/&utm_.*$/, "")}</code>
                  <button type="button" onClick={() => void sendInvite()}>{inviteSaid ?? "초대 링크 보내기"}</button>
                </div>
                {invite.monthlyLeft < invite.monthlyCap && (
                  <small className="wt-ev-inv-left">이번 달 {invite.monthlyLeft}명 남음</small>
                )}
              </>
            ) : (
              <button type="button" className="wt-ev-inv-login" onClick={() => { track("event_login_click", { theme: th.key, where: "invite" }); go("mypage"); }}>
                로그인하고 초대 링크 받기
              </button>
            )}
          </div>
        </section>

        <section className="wt-ev-sec ev2">
          <span className="wt-ev-pill">EVENT 3</span>
          <h2>{th.ev2Title}</h2>
          <div className="wt-ev-ev2">
            <div className="n">+20<small>크레딧</small></div>
            <div><b>가입하면 바로 20크레딧</b><span>웹툰 한 편을 만들 수 있는 양 · 가입 즉시 지급</span></div>
          </div>
        </section>

        <section className="wt-ev-sec how">
          <span className="wt-ev-pill">HOW TO</span>
          <h2>참여 방법</h2>
          <ol className="wt-ev-how">
            <li><i>1</i>LORE에 로그인</li>
            <li><i>2</i>{th.howStep2}</li>
            <li><i>3</i>{th.howStep3}</li>
            <li><i>4</i>친구에게 초대 링크 보내기 · 가입 10 · 첫 웹툰 10크레딧</li>
            <li><i>5</i>받은 크레딧으로 내 캐릭터 웹툰 만들기</li>
          </ol>
        </section>

        <div className="wt-ev-notice">
          <b>유의사항</b>
          <ul>
            <li>· 로그인한 회원만 참여할 수 있어요.</li>
            <li>· 하루라도 쉬면 1일째부터 다시 시작해요. 날짜는 한국 시간 자정에 바뀌어요.</li>
            <li>· 받은 크레딧은 무료 크레딧이라 쓸 때 먼저 빠지고, 환불되지 않아요.</li>
            <li>· 친구 초대 보상은 초대 링크로 들어와 새로 가입한 계정만 세고, 한 달에 10명까지예요. 본인 계정·같은 기기 가입은 세지 않아요.</li>
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
            <span className="amt">+{amt(7)} 크레딧 · 이번 주 {total}크레딧</span>
            <button type="button" className="go" autoFocus onClick={() => { track("event_fin_create", { theme: th.key }); go("create", { step: 1 }); }}>내 캐릭터로 웹툰 만들기</button>
            <button type="button" className="later" onClick={() => setFin(false)}>나중에</button>
          </div>
        </div>
      )}
      {toast && <div className="wt-ev-toast" role="status">{toast}</div>}
    </div>
  );
}
