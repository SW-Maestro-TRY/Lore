"use client";

import { useEffect, useState } from "react";
import { continuePages, readAllowance, WebtoonApiError, type RunResult } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { IconLock } from "../../ui/Icons";

/**
 * 크레딧이 모자라 아직 안 그린 장 — 작가에게만, 그린 장 바로 뒤에 잠긴 흰 장으로 이어 붙인다.
 *
 * 한 편 값은 기본 장 수(base_pages)까지라, 장면이 더 많은데 잔액이 모자라면 앞 장까지만 그려 완성한다.
 * 잠긴 장마다 한 장 값을 적고, 누르면 그 장까지 앞에서부터 연다 — 중간 장을 건너뛰면 이야기가 끊긴다.
 * 그 장 수 값이 다 있어야 열고, 모자라면 "크레딧이 모자라요!"와 함께 내일 매일 지급으로 열 수 있다고 알린다.
 * 그리는 동안은 완성본을 다시 읽어 그린 장이 잠긴 자리에 붙는 것을 보여 준다.
 */
export default function PendingPages({ runId, data, onRefresh }: {
  runId: string;
  data: RunResult;
  onRefresh: () => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState<number | null>(null);
  const [short, setShort] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

  const pending = data.pending_pages ?? [];
  const drawing = (data.drawing_pages ?? []).filter((n) => pending.includes(n));
  const locked = pending.filter((n) => !drawing.includes(n));
  const per = data.extra_page_credits ?? 0;

  /* 그리는 중이면 완성본을 다시 읽는다 — 한 장에 1~2분이라 5초 간격이면 충분하다. */
  useEffect(() => {
    if (drawing.length === 0) return;
    const timer = setTimeout(onRefresh, 5000);
    return () => clearTimeout(timer);
  }, [drawing.length, data, onRefresh]);

  /* 잔액은 그날 무료 크레딧까지 채운 값 — 장을 열고 나면 다시 읽는다 */
  useEffect(() => {
    let alive = true;
    readAllowance().then((a) => { if (alive) setBalance(a.balance ?? 0); }).catch(() => {});
    return () => { alive = false; };
  }, [runId, drawing.length, pending.length]);

  useEffect(() => { setShort(false); setFailed(null); }, [runId]);

  if (pending.length === 0) return null;

  const open = async (count: number) => {
    if (busy != null) return;
    setBusy(count);
    setShort(false);
    setFailed(null);
    try {
      const out = await continuePages(runId, count);
      track("pending_pages_continue", { run: runId, started: out.started.length, left: out.left });
      onRefresh();
    } catch (e) {
      if (e instanceof WebtoonApiError && e.status === 402) {
        setShort(true);
        track("pending_pages_short", { run: runId, pending: locked.length });
      } else {
        setFailed(e instanceof Error ? e.message : t("이어 그리지 못했습니다"));
      }
    } finally {
      setBusy(null);
    }
  };

  const canOpen = (count: number) => balance == null || balance >= count * per;

  return (
    <>
      <div className="wt-lock-head" role="status">
        <div>
          <b><IconLock size={16} /> {t("뒷장면 {n}장이 잠겨 있어요", { n: pending.length })}</b>
          <span>{t("한 편은 {base}장까지 기본이에요. 그 뒤 장면은 한 장에 {per}크레딧으로 열 수 있어요.", { base: data.base_pages ?? 8, per })}</span>
        </div>
        {balance != null && <small>{t("보유 {n}크레딧", { n: balance })}</small>}
      </div>
      {(short || (balance != null && locked.length > 0 && !canOpen(1))) && (
        <p className="wt-lock-short" role="alert">{t("크레딧이 모자라요! 매일 무료 크레딧이 채워지면 내일 이어서 그릴 수 있어요.")}</p>
      )}
      {failed && <p className="wt-lock-short" role="alert">{failed}</p>}
      {pending.map((no) => {
        const now = drawing.includes(no);
        const count = locked.indexOf(no) + 1;
        const cost = count * per;
        const ok = canOpen(count);
        return (
          <div key={no} className={`wt-lock-pg${now ? " is-drawing" : ""}`}>
            <span className="wt-lock-no">{t("{n}쪽", { n: no })}</span>
            {now ? (
              <div className="wt-lock-body">
                <span className="wt-lock-spin" aria-hidden="true" />
                <b>{t("그리는 중이에요")}</b>
                <small>{t("한 장에 1~2분 걸려요")}</small>
              </div>
            ) : (
              <div className="wt-lock-body">
                <span className="wt-lock-icon"><IconLock size={26} /></span>
                <b className="wt-lock-price">{t("{per}크레딧", { per })}</b>
                <button type="button" className="btn btn-p btn-sm" disabled={busy != null || !ok}
                        onClick={() => open(count)}>
                  {busy === count ? t("여는 중…")
                    : !ok ? t("크레딧이 모자라요")
                    : count === 1 ? t("이 장면 열기")
                    : t("여기까지 {n}장 열기 · {c}크레딧", { n: count, c: cost })}
                </button>
                {!ok && canOpen(1) && <small>{t("매일 무료 크레딧이 채워지면 내일 열 수 있어요")}</small>}
              </div>
            )}
          </div>
        );
      })}
    </>
  );
}
