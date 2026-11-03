"use client";

/**
 * 다음 화 크레딧이 모자랄 때의 두 갈래(2026-11-03, 윤지환 멘토링) — 지금 충전하고 바로 만들기,
 * 또는 출석으로 모아서 며칠 뒤 만들기. 1화를 만든 사람이 2화에서 가장 많이 멈춰서, 그 자리에서
 * 어느 쪽을 고르는지(클릭률)와 충전 창에서 실제로 결제하기를 누르는지(구매 전환율)를 센다.
 *
 * 기록: series_fork_view(보임) · series_fork_pick(pick: charge | attend) · charge_open / charge_submit
 * (where: series_fork) · series_remind_on(출석 알림을 걸었나). 키는 EventService.ALLOWED_KEYS 안의 것만 쓴다.
 *
 * 출석을 고르면 오늘 몫을 바로 받고, 출석 알림(AttendRemindService)을 건다. 이 기기 푸시가 꺼져 있으면
 * 그 자리에서 켜기를 묻는다 — 권한은 단추를 누른 순간에만 물을 수 있다(lib/push.ts).
 */
import { useEffect, useRef, useState } from "react";
import CreditCharge from "@common/mypage/CreditCharge";
import { attendRemindOff, attendRemindOn, readAttendRemind, type AttendRemindState } from "../../lib/api";
import { daysToAfford } from "../../lib/attendPlan";
import { registerDict, useLang, useT } from "../../lib/i18n";
import { enablePush, pushState } from "../../lib/push";
import { track } from "../../lib/track";
import PayNotReady from "../../ui/PayNotReady";
import { useAttendance } from "../event/useAttendance";
import "./SeriesFork.css";

registerDict({
  "충전하고 바로 {n}화 만들기": { en: "Top up and make episode {n} now", ja: "チャージしてすぐ第{n}話を作る", zh: "充值并立即制作第{n}话" },
  "출석으로 모아서 {d}일 뒤 만들기": { en: "Collect by checking in, make it in {d} days", ja: "出席で貯めて{d}日後に作る", zh: "签到攒积分，{d}天后制作" },
  "오늘 출석하고 {n}화 만들기": { en: "Check in today and make episode {n}", ja: "今日出席して第{n}話を作る", zh: "今天签到并制作第{n}话" },
  "{n}화를 만들 수 있어요": { en: "You can make episode {n}", ja: "第{n}話を作れます", zh: "可以制作第{n}话了" },
  "{d}일 뒤 · 매일 낮 12시 출석 알림": { en: "In {d} days · check-in reminder daily at noon", ja: "{d}日後 · 毎日正午に出席通知", zh: "{d}天后 · 每天中午签到提醒" },
  "오늘 출석 +{n}C": { en: "Check in today +{n}C", ja: "今日の出席 +{n}C", zh: "今日签到 +{n}C" },
  "알림 끄기": { en: "Turn off reminders", ja: "通知をオフ", zh: "关闭提醒" },
  "크레딧 잔액이 부족해요": { en: "Not enough credits", ja: "クレジット残高が足りません", zh: "积分余额不足" },
  "필요 {n} · 보유 {m}": { en: "need {n} · have {m}", ja: "必要 {n} · 所持 {m}", zh: "需要 {n} · 持有 {m}" },
});

export default function SeriesFork({ run, episode, cost, balance, onBalance }: {
  run: string; episode: number; cost: number; balance: number;
  /** 충전·출석으로 잔액이 바뀌었을 수 있다 — 부른 쪽이 다시 읽는다. */
  onBalance: () => void;
}) {
  const t = useT();
  const { lang } = useLang();
  const { card, claim, busy: claiming } = useAttendance(true);
  const [charging, setCharging] = useState(false);
  const [notReady, setNotReady] = useState(false);
  const [remind, setRemind] = useState<AttendRemindState | null>(null);
  const [working, setWorking] = useState(false);
  const days = daysToAfford(balance, cost, card);
  const seen = useRef(false);

  useEffect(() => {
    readAttendRemind().then(setRemind).catch(() => { /* 알림 상태가 안 보일 뿐 */ });
  }, []);
  useEffect(() => {
    if (seen.current || !card) return;
    seen.current = true;
    track("series_fork_view", { run, ep: episode, cost, balance, n: days ?? -1 });
  }, [card]);

  const remindHere = !!remind?.on && remind.run === run;

  const pickCharge = () => {
    track("series_fork_pick", { pick: "charge", run, ep: episode, n: days ?? -1 });
    track("charge_open", { where: "series_fork" });
    setCharging(true);
  };

  const pickAttend = async () => {
    if (working) return;
    setWorking(true);
    track("series_fork_pick", { pick: "attend", run, ep: episode, n: days ?? -1 });
    try {
      /* 오늘 출석으로 바로 모이면 알림이 필요 없다 — 받고 잔액만 다시 읽는다. */
      if (days === 0) {
        await claim();
        onBalance();
        return;
      }
      /* 푸시 권한은 단추를 누른 이 순간에만 물을 수 있다 — 그래서 먼저 묻기 시작한다. 다만 사람이 권한 창에
         답할 때까지 기다리지 않는다. 출석 받기·알림 걸기는 그동안 끝내고, 답은 기록에만 쓴다. */
      const asking = typeof Notification !== "undefined" && Notification.permission === "default";
      const pushing = (asking ? enablePush(lang) : pushState()).catch(() => "off" as const);
      if (card && !card.claimed) await claim();
      setRemind(await attendRemindOn(run, episode, cost));
      onBalance();
      track("series_remind_on", { run, ep: episode, how: asking ? "ask" : "known" });
      /* 권한 창의 답은 사람이 누를 때 온다(안 누르면 안 온다) — notify_ask_push 와 같은 이름으로 남긴다 */
      void pushing.then((push) => track("notify_ask_push", { result: push, where: "series_fork" }));
    } catch {
      /* 알림을 못 걸어도 출석은 받았을 수 있다 */
      onBalance();
    } finally {
      setWorking(false);
    }
  };

  const stopRemind = async () => {
    try { setRemind(await attendRemindOff()); track("series_remind_off", { run, ep: episode }); } catch { /* 그대로 둔다 */ }
  };

  const attendLabel = days === 0
    ? t("오늘 출석하고 {n}화 만들기", { n: episode })
    : t("출석으로 모아서 {d}일 뒤 만들기", { d: days ?? 0 });

  return (
    <div className="wt-series-fork" role="group" aria-label={t("{n}화 만들기", { n: episode })}>
      <div className="wt-series-fork-head">
        <b>{t("크레딧 잔액이 부족해요")}</b>
        <small>{t("필요 {n} · 보유 {m}", { n: cost, m: balance })}</small>
      </div>
      <div className="wt-series-fork-picks">
        <button type="button" className="btn btn-p" onClick={pickCharge}>{t("충전하고 바로 {n}화 만들기", { n: episode })}</button>
        {days != null && !remindHere && (
          <button type="button" className="btn btn-w" disabled={working || claiming} onClick={() => void pickAttend()}>
            {working ? t("처리 중…") : attendLabel}
          </button>
        )}
      </div>
      {remindHere && (
        <div className="wt-series-fork-remind" role="status">
          <span>{days === 0 ? t("{n}화를 만들 수 있어요", { n: episode })
            : t("{d}일 뒤 · 매일 낮 12시 출석 알림", { d: days ?? 0 })}</span>
          {card && !card.claimed && (
            <button type="button" className="btn btn-w btn-sm" disabled={claiming}
                    onClick={() => void claim().then(() => onBalance())}>{t("오늘 출석 +{n}C", { n: card.reward })}</button>
          )}
          <button type="button" className="btn-ghost" onClick={() => void stopRemind()}>{t("알림 끄기")}</button>
        </div>
      )}
      {charging && <CreditCharge onClose={() => { setCharging(false); onBalance(); }}
                                 onEvent={(n, p) => track(n, { ...p, where: "series_fork" })}
                                 onNotReady={() => setNotReady(true)} />}
      {notReady && <PayNotReady where="series_fork" onClose={() => setNotReady(false)}
                                onEmail={() => { window.location.href = "/webtoon?view=mypage&tab=contact"; }} />}
    </div>
  );
}
