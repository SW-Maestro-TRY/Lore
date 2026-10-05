"use client";

import { useEffect, useRef } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { trackConversion } from '@common/analytics';
import { useAuth } from '@common/auth/useAuth';
import { capturePending, captureReceipt, claimPending, claimReceipt, paidUrlIdentity, prepareAdLanding, retryableLandingError, saveAdLanding } from '../lib/ad-landing';

/** 서버 접수 → 로그인 계정 연결. 접수 성공 뒤 방문 진단을 보내 초기 익명 쿠키 경합을 줄인다. */
export default function AdLandingTracker() {
  const pathname = usePathname();
  const search = useSearchParams().toString();
  const { status, user } = useAuth();
  const visitedUrl = useRef<string | null>(null);
  const arrival = useRef<{ url: string; at: number } | null>(null);
  const userId = status === 'authenticated' ? user?.userId ?? null : null;

  useEffect(() => {
    const url = paidUrlIdentity(pathname, search);
    if (arrival.current?.url !== url) arrival.current = { url, at: Date.now() };
    if (status !== 'authenticated' && status !== 'anonymous') return;
    const state = prepareAdLanding(url, search, userId, arrival.current.at);
    const receipt = state.receipt;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const current = () => active && state.receipt === receipt && state.owner === userId;
    const visit = () => {
      if (!current() || visitedUrl.current === url) return;
      visitedUrl.current = url;
      void trackConversion('piece_maker_visit');
    };
    const later = (attempt: number) => new Promise<void>(resolve => {
      timer = setTimeout(resolve, attempt === 1 ? 1_000 : 3_000);
    });
    const run = async () => {
      if (!receipt || receipt.stopped) { visit(); return; }
      while (current() && !receipt.landingId && (receipt.captureAttempts < 3 || capturePending(receipt))) {
        try {
          const received = await captureReceipt(receipt, userId, current);
          if (!current()) return;
          receipt.landingId = received.landingId;
          receipt.landedAt = received.landedAt;
          saveAdLanding();
        } catch (error) {
          if (!current()) return;
          if (!retryableLandingError(error)) receipt.stopped = true;
          saveAdLanding();
        } finally { visit(); }
        if (receipt.stopped) return;
        if (!receipt.landingId && (receipt.captureAttempts < 3 || capturePending(receipt))) await later(receipt.captureAttempts);
      }
      visit();
      while (current() && userId !== null && receipt.landingId && !receipt.claimed && (receipt.claimAttempts < 3 || claimPending(receipt))) {
        try {
          await claimReceipt(receipt, userId);
          if (!current()) return;
          receipt.claimed = true;
          saveAdLanding();
        } catch (error) {
          if (!current()) return;
          if (!retryableLandingError(error)) receipt.stopped = true;
          saveAdLanding();
        }
        if (receipt.stopped) return;
        if (!receipt.claimed && (receipt.claimAttempts < 3 || claimPending(receipt))) await later(receipt.claimAttempts);
      }
    };
    void run();
    return () => { active = false; if (timer) clearTimeout(timer); };
  }, [pathname, search, status, userId]);
  return null;
}
