'use client';

// 관리자 "작업" 탭 — 사용자가 결과를 못 받은 부화·맞춤 8종을 한 화면에(설계 spec-zzal-admin-failures-retry-20261027).
//
// 머리: 러너 생존(30초마다, 탭이 보일 때만) · 기간(1·7·30일) · 숫자(성공·실패) · 시간대 막대.
// ★ 성공은 숫자로만 — 목록에는 실패만 둔다(결정 1).

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@common/api/client';
import { DIM, NOTICE, NOTICE_BAD } from './AdminReviewScreen';
import AdminFailureHourChart from './AdminFailureHourChart';
import {
  FAILURES_PATH,
  fetchFailures,
  fetchRunner,
  type FailureDays,
  type FailurePage,
  type RunnerView,
} from './failuresApi';

type Load = { kind: 'loading' } | { kind: 'ready'; page: FailurePage } | { kind: 'error'; message: string };

const DAYS: readonly FailureDays[] = [1, 7, 30];
const RUNNER_POLL_MS = 30_000;
const BAD = 'rgb(200,70,50)';

export function explain(e: unknown, where: string): string {
  if (e instanceof ApiError) {
    console.warn('[zzal/admin/work] 요청 실패', { where, status: e.status, code: e.code, message: e.message });
    if (e.code === 'ADMIN_ONLY') return '관리자 계정이 아닙니다.';
    if (e.status === 404 && where === FAILURES_PATH) {
      return `관리자 기능이 꺼져 있거나 주소가 없습니다 (${where}). ZZAL_ADMIN 설정을 확인해 주세요.`;
    }
    return e.message;
  }
  console.warn('[zzal/admin/work] 요청 실패', { where, error: e });
  return e instanceof Error ? e.message : '요청을 처리하지 못했습니다.';
}

/** 러너 칸 — "n분 전 확인" · 10분 넘으면 빨강 "꺼짐" · 기록 없음. */
export function RunnerBadge({ runner }: { runner: RunnerView | null }) {
  if (!runner) return <span data-part="runner" data-state="unknown" style={DIM}>러너: 확인 중</span>;
  if (runner.lastSeenAt == null) {
    return <span data-part="runner" data-state="none" style={{ fontSize: 13, fontWeight: 700, color: BAD }}>러너: 확인 기록 없음</span>;
  }
  if (runner.stale) {
    return (
      <span data-part="runner" data-state="stale" style={{ fontSize: 13, fontWeight: 700, color: BAD }}>
        러너: 꺼짐 ({runner.minutesAgo}분 전 마지막 확인)
      </span>
    );
  }
  return <span data-part="runner" data-state="ok" style={{ fontSize: 13 }}>러너: {runner.minutesAgo}분 전 확인</span>;
}

function useRunner(): RunnerView | null {
  const [runner, setRunner] = useState<RunnerView | null>(null);
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const tick = () => {
      if (document.visibilityState === 'visible') {
        fetchRunner().then((r) => alive && setRunner(r)).catch(() => alive && setRunner(null));
      }
      timer = setTimeout(tick, RUNNER_POLL_MS);
    };
    tick();
    const onVis = () => {
      if (document.visibilityState === 'visible') fetchRunner().then((r) => alive && setRunner(r)).catch(() => undefined);
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
  return runner;
}

function Stat({ label, value, bad, part }: { label: string; value: number; bad?: boolean; part: string }) {
  return (
    <div data-part={part} data-value={value} style={{ flex: '1 1 120px', minWidth: 0, padding: '6px 8px', border: '1px solid rgba(127,127,127,0.25)', borderRadius: 8 }}>
      <div style={{ fontSize: 12, color: 'var(--muted, #7a7a7a)' }}>{label}</div>
      <div style={{ fontSize: 20, fontWeight: 800, color: bad && value > 0 ? BAD : 'inherit' }}>{value}</div>
    </div>
  );
}

export default function AdminWorkTab() {
  const [days, setDays] = useState<FailureDays>(7);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const runner = useRunner();

  const reload = useCallback((d: FailureDays, signal?: AbortSignal) => {
    setLoad((prev) => (prev.kind === 'ready' ? prev : { kind: 'loading' }));
    fetchFailures(d, signal)
      .then((page) => setLoad({ kind: 'ready', page }))
      .catch((e) => {
        if (!signal?.aborted) setLoad({ kind: 'error', message: explain(e, FAILURES_PATH) });
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    reload(days, ac.signal);
    return () => ac.abort();
  }, [days, reload]);

  return (
    <section data-part="admin-work">
      <header style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <RunnerBadge runner={runner} />
        <div role="group" aria-label="기간" style={{ display: 'flex', gap: 6 }}>
          {DAYS.map((d) => (
            <button
              key={d}
              type="button"
              data-action="failures-days"
              data-days={d}
              aria-pressed={days === d}
              onClick={() => setDays(d)}
              style={{
                minHeight: 44, minWidth: 52, padding: '6px 10px', fontSize: 13, fontWeight: 700, borderRadius: 6, cursor: 'pointer',
                border: '1px solid rgba(127,127,127,0.35)', color: 'inherit',
                background: days === d ? 'rgba(127,127,127,0.18)' : 'transparent',
              }}
            >
              {d}일
            </button>
          ))}
        </div>
      </header>

      {load.kind === 'loading' && <p style={{ ...DIM, marginTop: 12 }}>불러오는 중…</p>}
      {load.kind === 'error' && <p data-part="work-error" style={{ ...NOTICE, ...NOTICE_BAD, marginTop: 12 }}>{load.message}</p>}
      {load.kind === 'ready' && (
        <>
          <div data-part="work-stats" style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 }}>
            <Stat part="stat-hatch-ok" label="부화 성공" value={load.page.hatchSucceeded} />
            <Stat part="stat-hatch-fail" label="부화 실패" value={load.page.hatchFailed} bad />
            <Stat part="stat-custom-ok" label="맞춤 지급" value={load.page.customGranted} />
            <Stat part="stat-custom-fail" label="맞춤 실패" value={load.page.customFailed} bad />
          </div>
          <AdminFailureHourChart hours={load.page.hours} />
          <p data-part="failure-count" style={{ ...DIM, marginTop: 8 }}>실패 {load.page.rows.length}건</p>
        </>
      )}
    </section>
  );
}
