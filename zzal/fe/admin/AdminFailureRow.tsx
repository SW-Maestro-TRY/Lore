'use client';

// 실패 한 줄 — 줄 전체가 버튼(누르면 상세). 높이 44 이상.

import type { AutoCliState, FailureRow, RequestState } from './failuresApi';

const BAD = 'rgb(200,70,50)';

export const REQUEST_LABEL: Record<RequestState, string> = {
  NONE: '요청 없음',
  WAITING: '대기',
  BAKING: '굽는 중',
  CANDIDATES: '후보 도착',
  NO_CANDIDATES: '후보 없음',
  CANCELLED: '취소',
  APPLIED: '반영됨',
  APPLY_FAILED: '반영 실패',
};

export const AUTO_LABEL: Record<AutoCliState, string> = {
  NONE: '자동 없음',
  BAKING: '자동 굽는 중',
  RECOVERED: '자동 복구됨',
  FAILED: '자동 실패',
};

/** 한국 시각 `10/28 15:20`. */
export function when(iso: string | null): string {
  if (!iso) return '';
  const k = new Date(Date.parse(iso) + 9 * 3600_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(k.getUTCMonth() + 1)}/${p(k.getUTCDate())} ${p(k.getUTCHours())}:${p(k.getUTCMinutes())}`;
}

export function money(v: number | null): string {
  return v == null ? '—' : `$${v.toFixed(2)}`;
}

export const BADGE: React.CSSProperties = {
  display: 'inline-block',
  padding: '1px 6px',
  borderRadius: 999,
  fontSize: 11,
  fontWeight: 700,
  border: '1px solid rgba(127,127,127,0.4)',
  whiteSpace: 'nowrap',
};

export default function AdminFailureRow({ row, open, onOpen }: { row: FailureRow; open: boolean; onOpen: () => void }) {
  const bad = row.request === 'APPLY_FAILED' || row.autoCli === 'FAILED';
  return (
    <button
      type="button"
      data-part="failure-row"
      data-action="failure-open"
      data-kind={row.kind}
      data-id={row.id}
      aria-expanded={open}
      onClick={onOpen}
      style={{
        display: 'block', width: '100%', minHeight: 44, textAlign: 'left', padding: '8px 10px', boxSizing: 'border-box',
        border: '1px solid rgba(127,127,127,0.3)', borderRadius: 8, background: open ? 'rgba(127,127,127,0.12)' : 'transparent',
        color: 'inherit', cursor: 'pointer', marginTop: 6,
      }}
    >
      <span style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}>
        <span data-part="kind-badge" style={{ ...BADGE, borderColor: row.kind === 'HATCH' ? 'rgba(200,90,60,0.7)' : 'rgba(90,110,200,0.7)' }}>
          {row.kind === 'HATCH' ? '부화' : '맞춤'}
        </span>
        <b style={{ fontSize: 14, minWidth: 0, overflowWrap: 'anywhere' }}>{row.petName ?? '이름 없음'}</b>
        <span style={{ fontSize: 12, color: 'var(--muted, #7a7a7a)' }}>펫 {row.petId} · {when(row.failedAt)}</span>
      </span>
      <span data-part="reason" style={{ display: 'block', fontSize: 13, marginTop: 3, overflowWrap: 'anywhere' }}>{row.reasonText}</span>
      <span style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 4, fontSize: 12 }}>
        <span>시도 {row.attempts}</span>
        <span>비용 {money(row.costUsd)}</span>
        <span data-part="auto-badge" data-state={row.autoCli} style={BADGE}>{AUTO_LABEL[row.autoCli]}</span>
        <span data-part="request-badge" data-state={row.request} style={{ ...BADGE, color: bad ? BAD : 'inherit' }}>{REQUEST_LABEL[row.request]}</span>
      </span>
    </button>
  );
}
