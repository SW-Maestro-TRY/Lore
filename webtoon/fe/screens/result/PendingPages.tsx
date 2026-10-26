"use client";

import { useEffect, useState } from "react";
import { continuePages, WebtoonApiError, type RunResult } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";

/**
 * 크레딧이 모자라 아직 안 그린 장 — 작가에게만 보인다.
 *
 * 한 편 값은 기본 장 수(base_pages)까지라, 장면이 더 많은데 잔액이 모자라면 앞 장까지만 그려 완성한다.
 * 다음 날 매일 지급으로 잔액이 생기면 여기서 이어 그린다. 서버가 잔액이 닿는 장까지만 앞에서부터 그리고,
 * 한 장도 못 그리면 402 를 준다. 그리는 동안은 완성본을 다시 읽어 그린 장이 붙는 것을 보여 준다.
 */
export default function PendingPages({ runId, data, onRefresh }: {
  runId: string;
  data: RunResult;
  onRefresh: () => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [short, setShort] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);

  const pending = data.pending_pages ?? [];
  const drawing = (data.drawing_pages ?? []).filter((n) => pending.includes(n));
  const waiting = pending.length - drawing.length;
  const per = data.extra_page_credits ?? 0;

  /* 그리는 중이면 완성본을 다시 읽는다 — 한 장에 1~2분이라 5초 간격이면 충분하다. */
  useEffect(() => {
    if (drawing.length === 0) return;
    const timer = setTimeout(onRefresh, 5000);
    return () => clearTimeout(timer);
  }, [drawing.length, data, onRefresh]);

  useEffect(() => { setShort(false); setFailed(null); setLeft(null); }, [runId]);

  if (pending.length === 0) return null;

  const start = async () => {
    if (busy) return;
    setBusy(true);
    setShort(false);
    setFailed(null);
    try {
      const out = await continuePages(runId);
      track("pending_pages_continue", { run: runId, started: out.started.length, left: out.left });
      setLeft(out.left);
      onRefresh();
    } catch (e) {
      if (e instanceof WebtoonApiError && e.status === 402) {
        setShort(true);
        track("pending_pages_short", { run: runId, pending: waiting });
      } else {
        setFailed(e instanceof Error ? e.message : t("이어 그리지 못했습니다"));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="wt-result-pending" role="status">
      <b>{t("뒷장면 {n}장이 남았어요", { n: pending.length })}</b>
      <p>{t("한 편은 {base}장까지 기본으로 그려요. 그 뒤 장면은 한 장에 {per}크레딧이에요.", { base: data.base_pages ?? 8, per })}</p>
      {drawing.length > 0 && (
        <p className="wt-result-pending-now">{t("{n}장을 그리는 중이에요. 한 장에 1~2분 걸려요.", { n: drawing.length })}</p>
      )}
      {short && (
        <p className="wt-result-pending-short">{t("크레딧이 모자라요! 매일 무료 크레딧이 채워지면 내일 이어서 그릴 수 있어요.")}</p>
      )}
      {!short && left !== null && left > 0 && drawing.length > 0 && (
        <p>{t("나머지 {n}장은 크레딧이 채워지면 이어서 그릴 수 있어요.", { n: left })}</p>
      )}
      {failed && <p className="wt-result-pending-short">{failed}</p>}
      {waiting > 0 && (
        <button type="button" className="btn btn-p btn-sm" disabled={busy} onClick={start}>
          {busy ? t("시작하는 중…") : t("이어 그리기 · 한 장 {per}크레딧", { per })}
        </button>
      )}
    </div>
  );
}
