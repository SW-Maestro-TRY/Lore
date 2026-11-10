"use client";

import { useEffect, useState } from "react";
import { continuePages, pageUrl, readAllowance, WebtoonApiError, type RunResult } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { IconLock } from "../../ui/Icons";

/** 시안 — a: 흐린 다음 장면, b: 콘티(밑그림), c: 이어지는 여백. 주소에 ?lock=b 처럼 붙여 고른다. */
type Look = "a" | "b" | "c";

function lookFromUrl(): Look {
  if (typeof window === "undefined") return "a";
  const v = new URLSearchParams(window.location.search).get("lock");
  return v === "b" || v === "c" ? v : "a";
}

/**
 * 크레딧이 모자라 아직 안 그린 장 — 작가에게만, 그린 장 바로 뒤에 웹툰 장면처럼 이어 붙인다.
 *
 * 따로 떼어 낸 안내 칸을 두지 않는다. 장마다 그 장면 한 줄을 내레이션 상자로 보여 주고, 열기 단추에
 * 한 장 값을 적는다. 누르면 그 장까지 앞에서부터 연다 — 중간 장을 건너뛰면 이야기가 끊긴다.
 * 그 장 수 값이 다 있어야 열고, 모자라면 "크레딧이 모자라요!"와 함께 내일 매일 지급으로 열 수 있다고 알린다.
 */
export default function PendingPages({ runId, data, onRefresh }: {
  runId: string;
  data: RunResult;
  onRefresh: () => void;
}) {
  const t = useT();
  const [look, setLook] = useState<Look>("a");
  const [busy, setBusy] = useState<number | null>(null);
  const [short, setShort] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

  const pending = data.pending_pages ?? [];
  const drawing = (data.drawing_pages ?? []).filter((n) => pending.includes(n));
  const locked = pending.filter((n) => !drawing.includes(n));
  const per = data.extra_page_credits ?? 0;
  const captions = new Map((data.pending_scenes ?? []).map((s) => [s.no, s.caption]));
  const lastDrawn = data.pages[data.pages.length - 1]?.no;

  useEffect(() => { setLook(lookFromUrl()); }, []);

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
      track("pending_pages_continue", { run: runId, started: out.started.length, left: out.left, look });
      onRefresh();
    } catch (e) {
      if (e instanceof WebtoonApiError && e.status === 402) {
        setShort(true);
        track("pending_pages_short", { run: runId, pending: locked.length, look });
      } else {
        setFailed(e instanceof Error ? e.message : t("이어 그리지 못했습니다"));
      }
    } finally {
      setBusy(null);
    }
  };

  const canOpen = (count: number) => !short && (balance == null || balance >= count * per);

  return (
    <div className={`wt-lock wt-lock-${look}`}>
      {look === "c" && <div className="wt-lock-fade" aria-hidden="true" />}
      {pending.map((no, i) => {
        const now = drawing.includes(no);
        const count = locked.indexOf(no) + 1;
        const ok = canOpen(count);
        const first = count === 1;
        const caption = captions.get(no) || t("다음 장면");
        return (
          <section key={no} className={`wt-lock-pg${now ? " is-drawing" : ""}`} aria-label={t("{n}쪽", { n: no })}>
            {look === "a" && lastDrawn != null && (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img className="wt-lock-ghost" src={pageUrl(runId, lastDrawn, 320)} alt="" aria-hidden="true"
                   style={{ objectPosition: `center ${(i * 37) % 100}%` }} />
            )}
            {look === "b" && (
              <div className="wt-lock-conti" aria-hidden="true"><i /><i /><i /></div>
            )}
            <p className="wt-lock-narr"><span>{caption}</span></p>
            <div className="wt-lock-act">
              {now ? (
                <span className="wt-lock-now"><span className="wt-lock-spin" aria-hidden="true" />{t("그리는 중이에요 · 1~2분")}</span>
              ) : (
                <>
                  <button type="button" className="wt-lock-btn" disabled={busy != null || !ok} onClick={() => open(count)}>
                    <IconLock size={19} />
                    {busy === count ? t("여는 중…")
                      : first ? t("이 장면 열기 · {c}크레딧", { c: per })
                      : t("여기까지 {n}장 열기 · {c}크레딧", { n: count, c: count * per })}
                  </button>
                  {first && balance != null && ok && (
                    <small className="wt-lock-hint">{t("기본 {base}장 뒤로는 한 장에 {per}크레딧 · 보유 {n}", { base: data.base_pages ?? 8, per, n: balance })}</small>
                  )}
                  {!ok && (first || canOpen(1)) && (
                    <small className="wt-lock-hint is-short">{t("크레딧이 모자라요!")}<br />{t("출석 이벤트로 크레딧을 받으면 이어서 그릴 수 있어요.")}</small>
                  )}
                  {first && failed && <small className="wt-lock-hint is-short">{failed}</small>}
                </>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}
