"use client";

/* 순서 기다리기 팝업(#641) — 앞에 다른 사람의 작품이 있을 때 띄운다.
 *
 * 진행 화면 왼쪽 카드에 「현재 대기자 N명」이 글자로만 있어서, 기다리는지도 모르고 화면을 붙들고 있거나
 * 고장으로 보고 나갔다. 팝업으로 「앞에 몇 명 · 약 몇 분 뒤 시작」과 차오르는 게이지를 보여 주고,
 * 「여기서 기다리기」를 누르면 닫고 다른 웹툰을 둘러보게 한다 — 오른쪽 아래 진행 원(RunningBubble)이 남고,
 * 기다리는 동안 그 원을 누르면 이 팝업이 다시 뜬다.
 *
 * 게이지는 처음 본 대기 시간 대비 지금 남은 시간이다. 서버 값은 몇 초마다 오고, 그 사이는 시간이 흐른 만큼
 * 부드럽게 채운다. 시작 직전(97%)에서 멈춰 서버가 「시작했다」고 할 때까지 끝까지 차지 않는다 — 다 찼는데
 * 안 시작하면 거짓말이 된다. 처음 본 값은 이 브라우저에 작업마다 남겨서 화면을 옮겨도 이어진다. */
import { useEffect, useRef, useState } from "react";
import type { NhJob } from "../lib/api";
import { registerDict, useT } from "../lib/i18n";
import { Dialog } from "./Dialog";
import "./QueueDialog.css";

const START_KEY = "lore_wt_queue_start_";
const SEEN_KEY = "lore_wt_queue_seen_";

/** 이 작업이 지금 순서를 기다리는 중인가. */
export function isQueued(job: NhJob | null | undefined): boolean {
  return !!job && !!job.queue && job.queue.ahead > 0;
}

/** 이 작업의 팝업을 이미 한 번 띄웠나 — 진행 화면은 처음 한 번만 저절로 띄운다. */
export function queueSeen(jobId: string): boolean {
  try { return sessionStorage.getItem(SEEN_KEY + jobId) === "1"; } catch { return false; }
}

export function markQueueSeen(jobId: string): void {
  try { sessionStorage.setItem(SEEN_KEY + jobId, "1"); } catch { /* 이번만 */ }
}

function startOf(job: NhJob): number {
  const now = (job.queue?.minutes ?? 1) * 60;
  try {
    const got = Number(localStorage.getItem(START_KEY + job.id));
    if (got > 0) return Math.max(got, now);
    localStorage.setItem(START_KEY + job.id, String(now));
  } catch { /* 저장이 안 되면 지금 값으로 */ }
  return now;
}

export default function QueueDialog({ job, onWait, onOpenProgress }: {
  job: NhJob;
  /** 「여기서 기다리기」 — 닫고 둘러보게 한다 */
  onWait: () => void;
  /** 있으면 「진행 화면 보기」 단추를 둔다(진행 원에서 열었을 때) */
  onOpenProgress?: () => void;
}) {
  const t = useT();
  const ahead = job.queue?.ahead ?? 0;
  const minutes = job.queue?.minutes ?? 1;
  const total = useRef(startOf(job));
  const got = useRef({ sec: minutes * 60, at: Date.now() });
  const [fill, setFill] = useState(0);

  useEffect(() => {
    got.current = { sec: minutes * 60, at: Date.now() };
    total.current = Math.max(total.current, minutes * 60);
  }, [minutes, ahead]);

  useEffect(() => {
    const tick = () => {
      const left = Math.max(30, got.current.sec - (Date.now() - got.current.at) / 1000);
      setFill(Math.min(0.97, Math.max(0.03, 1 - left / total.current)));
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <Dialog title={t("앞에 {n}명이 만들고 있어요", { n: ahead })} onClose={onWait}>
      <div className="wt-queue">
        <p className="wt-queue-eta">{t("약 {m}분 뒤 내 차례예요", { m: minutes })}</p>
        <div className="wt-queue-gauge" role="progressbar" aria-valuemin={0} aria-valuemax={100}
             aria-valuenow={Math.round(fill * 100)} aria-label={t("내 차례까지")}>
          <i style={{ width: `${fill * 100}%` }} />
        </div>
        <div className="wt-queue-ends"><span>{t("줄을 섰어요")}</span><span>{t("내 차례")}</span></div>
        <p className="wt-dialog-sub">
          {t("기다리는 동안 다른 웹툰을 둘러보셔도 괜찮아요. 오른쪽 아래 동그라미를 누르면 언제든 다시 볼 수 있어요.")}
        </p>
      </div>
      <div className="wt-dialog-actions">
        {onOpenProgress && (
          <button type="button" className="btn btn-w" onClick={onOpenProgress}>{t("진행 화면 보기")}</button>
        )}
        <button type="button" className="btn btn-p" onClick={onWait}>{t("여기서 기다리기")}</button>
      </div>
    </Dialog>
  );
}

registerDict({
  "앞에 {n}명이 만들고 있어요": { en: "{n} ahead of you", ja: "前に{n}人が作成中です", zh: "前面有 {n} 人在制作" },
  "약 {m}분 뒤 내 차례예요": { en: "Your turn in about {m} min", ja: "約{m}分後にあなたの番です", zh: "大约 {m} 分钟后轮到你" },
  "내 차례까지": { en: "Until your turn", ja: "あなたの番まで", zh: "距离轮到你" },
  "줄을 섰어요": { en: "In line", ja: "列に並びました", zh: "已排队" },
  "내 차례": { en: "Your turn", ja: "あなたの番", zh: "轮到你" },
  "기다리는 동안 다른 웹툰을 둘러보셔도 괜찮아요. 오른쪽 아래 동그라미를 누르면 언제든 다시 볼 수 있어요.": {
    en: "Feel free to browse other webtoons while you wait. Tap the circle at the bottom right to check back anytime.",
    ja: "待っている間、ほかのウェブトゥーンを見ていても大丈夫です。右下の丸を押せばいつでも確認できます。",
    zh: "等待期间可以去看看其他漫画。点击右下角的圆圈随时回来查看。",
  },
  "진행 화면 보기": { en: "See progress", ja: "進行画面を見る", zh: "查看进度" },
  "여기서 기다리기": { en: "Wait here", ja: "ここで待つ", zh: "在这里等" },
});
