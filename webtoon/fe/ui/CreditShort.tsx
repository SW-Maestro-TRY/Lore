"use client";

/**
 * 크레딧이 모자랄 때의 한 줄(#548) — 오류처럼 보이지 않게 「크레딧 잔액이 부족해요」와
 * 「충전하기」를 같이 둔다. 크레딧을 쓰는 화면(만들기 · 캐릭터 · 조연 시트 · 장면 다시
 * 뽑기 · 1화 다시 만들기)이 모두 이것을 쓴다. 서버는 모자라면 402 를 준다.
 */
import { useState } from "react";
import CreditCharge from "@common/mypage/CreditCharge";
import { registerDict, useT } from "../lib/i18n";
import "./CreditShort.css";

registerDict({
  "크레딧 잔액이 부족해요": { en: "Not enough credits", ja: "クレジット残高が足りません", zh: "积分余额不足" },
  "필요 {n} · 보유 {m}": { en: "need {n} · have {m}", ja: "必要 {n} · 所持 {m}", zh: "需要 {n} · 持有 {m}" },
  "충전하기": { en: "Top up", ja: "チャージする", zh: "去充值" },
});

/** 서버가 크레딧이 모자란다고 했나 — 402 이고, 하루 무료 한도 이야기가 아닐 때. */
export function isCreditShort(e: unknown): boolean {
  const err = e as { status?: number; raw?: string; message?: string } | null;
  const msg = err?.raw || err?.message || "";
  return err?.status === 402 && !msg.includes("다 쓰셨어요");
}

/** 서버 원문에서 「필요 n · 보유 m」을 꺼낸다. 없으면 null. */
function needHave(raw: string): { n: string; m: string } | null {
  const got = raw.match(/필요\s*(\d+)\s*·\s*보유\s*(\d+)/);
  return got ? { n: got[1], m: got[2] } : null;
}

export default function CreditShort({ raw = "", onCharged }: { raw?: string; onCharged?: () => void }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const nm = needHave(raw);
  return (
    <span className="wt-credit-short" role="status">
      <span>
        <b>{t("크레딧 잔액이 부족해요")}</b>
        {nm && <small>{t("필요 {n} · 보유 {m}", { n: nm.n, m: nm.m })}</small>}
      </span>
      <button type="button" className="btn btn-p btn-sm" onClick={() => setOpen(true)}>{t("충전하기")}</button>
      {open && <CreditCharge onClose={() => { setOpen(false); onCharged?.(); }} />}
    </span>
  );
}

const MARK = "\u0000credit:";

/**
 * catch 에서 화면에 둘 글로 바꾼다. 크레딧 부족이면 표시를 붙여 두고, {@link ErrLine} 이
 * 그 표시를 보고 오류 대신 「크레딧 잔액이 부족해요 · 충전하기」를 그린다.
 */
export function errText(e: unknown, fallback: string): string {
  if (isCreditShort(e)) return MARK + ((e as { raw?: string }).raw || "");
  return e instanceof Error && e.message ? e.message : fallback;
}

/** 오류 한 줄 — 크레딧 부족이면 안내로, 아니면 빨간 오류 글로. */
export function ErrLine({ text, className = "err" }: { text: string; className?: string }) {
  if (!text) return null;
  if (text.startsWith(MARK)) return <CreditShort raw={text.slice(MARK.length)} />;
  return <span className={className}>{text}</span>;
}
