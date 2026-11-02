'use client';

// 관리자 「문의·후기」 탭(목업 34-5, 2026-10-28).
//
// ★ 왼쪽 목록(미답은 붉은 띠·위로, 고른 줄은 초록 테두리 — 기존 THUMB_ON), 오른쪽 상세 + 답장 쓰기, 아래 같은 사용자의 후기.
// ★ 답장은 사용자 화면에 「로어 운영팀」 이름으로 보이고 그 사용자의 메뉴에 점이 찍힌다. 한 문의에 한 번(두 번째는 409).
// ★ 폭 — 다른 탭은 880 이지만 목록과 상세를 나란히 보려고 이 탭만 1120 까지 넓힌다(AdminScreen 은 손대지 않고 여기서 벗어난다).

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '@common/api/client';
import { CARD, DIM, INPUT, NOTICE, NOTICE_BAD, THUMB_ON } from './AdminReviewScreen';
import {
  INQUIRY_LIST_PATH,
  getInquiry,
  listFeedback,
  listInquiries,
  replyInquiry,
  type AdminFeedbackRow,
  type AdminInquiryDetail,
  type AdminInquiryRow,
} from './inquiryApi';

const DAYS = 30;
const MAX_REPLY = 2000;
const LOCALE_NAME: Record<string, string> = { ko: '한국어', en: '영어', ja: '일본어' };

function explain(e: unknown, where: string): string {
  if (e instanceof ApiError) {
    console.warn('[zzal/admin/inquiry] 요청 실패', { where, status: e.status, code: e.code });
    if (e.code === 'ADMIN_ONLY') return '관리자 계정으로 로그인해야 합니다(봇 토큰은 이 탭을 못 씁니다).';
    if (e.code === 'ZZAL_INQUIRY_ALREADY_REPLIED') return '이미 답장한 문의입니다. 목록을 다시 불러옵니다.';
    if (e.status === 404 && where === INQUIRY_LIST_PATH) return `관리자 기능이 꺼져 있거나 주소가 없습니다 (${where}).`;
    return e.message;
  }
  return e instanceof Error ? e.message : '요청을 처리하지 못했습니다.';
}

/** 한국 시각 `10월 28일 09:12`. */
function when(iso: string | null): string {
  if (!iso) return '';
  const k = new Date(new Date(iso).getTime() + 9 * 3600_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${k.getUTCMonth() + 1}월 ${k.getUTCDate()}일 ${p(k.getUTCHours())}:${p(k.getUTCMinutes())}`;
}

/** 탭 이름 옆 미답 수 배지. 탭을 안 열어도 보이게 이름 자리에서 따로 센다. */
export function InquiryTabLabel() {
  const [open, setOpen] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    listInquiries(DAYS).then(rows => { if (live) setOpen(rows.filter(r => !r.replied).length); }).catch(() => {});
    const onChange = (e: Event) => setOpen((e as CustomEvent<number>).detail);
    window.addEventListener('zzal-admin-inquiry-open', onChange);
    return () => { live = false; window.removeEventListener('zzal-admin-inquiry-open', onChange); };
  }, []);
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      문의·후기
      {open ? <span data-part="inquiry-open-badge" style={{ ...BADGE, ...BADGE_OPEN }}>미답 {open}</span> : null}
    </span>
  );
}

function Stars({ rating }: { rating: number }) {
  return (
    <span aria-label={`별점 ${rating}`} style={{ color: '#C9A227', letterSpacing: 1 }}>
      {'★★★★★'.slice(0, rating)}<span style={{ color: 'rgba(127,127,127,0.4)' }}>{'★★★★★'.slice(rating)}</span>
    </span>
  );
}

function FeedbackLine({ f, showPet }: { f: AdminFeedbackRow; showPet?: boolean }) {
  return (
    <div data-part="feedback-row" style={{ padding: '8px 0', borderTop: '1px solid rgba(127,127,127,0.18)', minWidth: 0 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, fontSize: 13 }}>
        <Stars rating={f.rating} /> <b>{f.rating}</b>
        {f.tags.map(tag => <span key={tag} style={CHIP}>{tag}</span>)}
        <span style={{ ...DIM, marginLeft: 'auto' }}>{showPet ? `${f.petName ?? '(지운 펫)'} · 사용자 #${f.userId} · ` : ''}{when(f.createdAt)}</span>
      </div>
      {f.text ? <p style={{ margin: '4px 0 0', fontSize: 13, overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{f.text}</p> : null}
    </div>
  );
}

export default function AdminInquiryTab() {
  const [rows, setRows] = useState<AdminInquiryRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<'open' | 'all'>('open');
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<AdminInquiryDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [feedback, setFeedback] = useState<AdminFeedbackRow[] | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const next = await listInquiries(DAYS);
      setRows(next);
      window.dispatchEvent(new CustomEvent('zzal-admin-inquiry-open', { detail: next.filter(r => !r.replied).length }));
      setSelected(cur => cur ?? next[0]?.id ?? null);
    } catch (e) { setError(explain(e, INQUIRY_LIST_PATH)); }
  }, []);
  useEffect(() => { void load(); listFeedback(DAYS).then(setFeedback).catch(() => setFeedback([])); }, [load]);

  useEffect(() => {
    if (selected == null) { setDetail(null); return; }
    let live = true;
    setDetailError(null); setDraft('');
    getInquiry(selected).then(d => { if (live) setDetail(d); }).catch(e => { if (live) { setDetail(null); setDetailError(explain(e, `${INQUIRY_LIST_PATH}/${selected}`)); } });
    return () => { live = false; };
  }, [selected]);

  const openCount = rows?.filter(r => !r.replied).length ?? 0;
  const shown = useMemo(() => (rows ?? []).filter(r => filter === 'all' || !r.replied), [rows, filter]);
  const target = detail?.chain.find(c => c.id === selected) ?? null;
  const text = draft.trim();

  const send = async () => {
    if (selected == null || !text || text.length > MAX_REPLY || sending) return;
    setSending(true); setDetailError(null);
    try {
      const d = await replyInquiry(selected, text);
      setDetail(d); setDraft('');
      await load();
    } catch (e) {
      setDetailError(explain(e, `${INQUIRY_LIST_PATH}/${selected}/reply`));
      if (e instanceof ApiError && e.code === 'ZZAL_INQUIRY_ALREADY_REPLIED') { await load(); getInquiry(selected).then(setDetail).catch(() => {}); }
    } finally { setSending(false); }
  };

  return (
    <section data-part="admin-inquiry" style={WIDE}>
      <h2 style={{ fontSize: 20, margin: '4px 0 8px' }}>문의·후기</h2>
      <p style={{ ...NOTICE, margin: '0 0 12px' }}>답장은 사용자 문의 화면에 「로어 운영팀」 이름으로 보이고, 그 사용자의 메뉴에 점이 찍힙니다. 답장 전 미답 문의가 위로 옵니다. 최근 {DAYS}일.</p>
      {error ? <p role="alert" style={{ ...NOTICE, ...NOTICE_BAD }}>{error}</p> : null}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'flex-start' }}>
        <div data-part="inquiry-list" style={{ flex: '0 1 380px', minWidth: 280 }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            {([['open', `미답 ${openCount}`], ['all', `전체 ${rows?.length ?? 0}`]] as const).map(([k, label]) => (
              <button key={k} type="button" data-action="inquiry-filter" data-filter={k} onClick={() => setFilter(k)}
                style={{ ...BTN, background: filter === k ? 'rgba(127,127,127,0.18)' : 'transparent' }}>{label}</button>
            ))}
          </div>
          {rows === null && !error ? <p style={DIM}>불러오는 중…</p> : null}
          {rows !== null && shown.length === 0 ? <p style={DIM}>{filter === 'open' ? '미답 문의가 없습니다.' : '문의가 없습니다.'}</p> : null}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {shown.map(r => (
              <button key={r.id} type="button" data-action="inquiry-pick" data-inquiry={r.id} data-replied={r.replied} data-active={selected === r.id}
                onClick={() => setSelected(r.id)}
                style={{ ...ROW, ...(r.replied ? null : ROW_OPEN), ...(selected === r.id ? THUMB_ON : null) }}>
                <span style={{ display: 'flex', alignItems: 'baseline', gap: 8, minWidth: 0 }}>
                  <b style={{ fontSize: 15, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.petName ?? '(펫 없음)'}</b>
                  <span style={{ ...DIM, marginLeft: 'auto', whiteSpace: 'nowrap', fontSize: 12 }}>{when(r.createdAt)}</span>
                </span>
                <span style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                  <span style={{ fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0, flex: 1 }}>{r.body}</span>
                  <span style={{ ...BADGE, ...(r.replied ? BADGE_DONE : BADGE_OPEN) }}>{r.replied ? '답장함' : '미답'}</span>
                </span>
              </button>
            ))}
          </div>
        </div>

        <div style={{ flex: '1 1 480px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {detailError ? <p role="alert" style={{ ...NOTICE, ...NOTICE_BAD, margin: 0 }}>{detailError}</p> : null}
          {detail ? (
            <div data-part="inquiry-detail" style={{ ...CARD, padding: 14 }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, fontSize: 13, marginBottom: 10 }}>
                <b style={{ fontSize: 15 }}>{detail.inquiry.petName ?? '(펫 없음)'}</b>
                <span style={{ ...BADGE, ...(detail.inquiry.replied ? BADGE_DONE : BADGE_OPEN) }}>{detail.inquiry.replied ? '답장함' : '미답'}</span>
                <span style={DIM}>· 펫 #{detail.inquiry.petId ?? '-'} · 사용자 #{detail.inquiry.userId} · {LOCALE_NAME[detail.inquiry.locale] ?? detail.inquiry.locale}</span>
                <span style={{ ...DIM, marginLeft: 'auto' }}>{when(detail.inquiry.createdAt)}</span>
              </div>
              <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
                {detail.chain.map(c => (
                  <li key={c.id} data-part="chain-row" data-inquiry={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 6, opacity: c.id === selected ? 1 : 0.75 }}>
                    {detail.chain.length > 1 ? <span style={{ ...DIM, fontSize: 12 }}>#{c.id} · {when(c.createdAt)}{c.parentId ? ' · 더 물어보기' : ''}</span> : null}
                    <p style={MINE}>{c.body}</p>
                    {c.replyText ? (
                      <>
                        <span style={{ ...DIM, fontSize: 12 }}>로어 운영팀 · {when(c.repliedAt)}{c.replySeenAt ? ' · 사용자 확인함' : ' · 사용자 아직 안 봄'}</span>
                        <p style={REPLY}>{c.replyText}</p>
                      </>
                    ) : null}
                  </li>
                ))}
              </ol>
              {target && !target.replyText ? (
                <div style={{ marginTop: 14 }}>
                  <label htmlFor="admin-inquiry-reply" style={{ display: 'block', fontWeight: 700, fontSize: 13, marginBottom: 6 }}>답장 쓰기</label>
                  <textarea id="admin-inquiry-reply" data-part="inquiry-reply-input" value={draft} maxLength={MAX_REPLY + 200} onChange={e => setDraft(e.target.value)}
                    style={{ ...INPUT, width: '100%', minHeight: 96, boxSizing: 'border-box', resize: 'vertical' }} />
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
                    <span style={{ ...DIM, fontSize: 12, flex: 1, minWidth: 0 }}>보내면 사용자 메뉴에 점 · 보낸 뒤엔 이 칸 아래 기록으로 남음 · {text.length}/{MAX_REPLY}</span>
                    <button type="button" data-action="inquiry-reply-send" disabled={!text || text.length > MAX_REPLY || sending} onClick={() => void send()} style={BTN}>
                      {sending ? '보내는 중…' : '보내기'}
                    </button>
                  </div>
                </div>
              ) : null}
            </div>
          ) : rows && rows.length ? <p style={DIM}>왼쪽에서 문의를 고르세요.</p> : null}

          {detail ? (
            <div data-part="inquiry-user-feedback" style={{ ...CARD, padding: '10px 14px' }}>
              <div style={{ fontSize: 13 }}><b>같은 사용자의 후기</b> <span style={DIM}>· {detail.feedback.length}건</span></div>
              {detail.feedback.length === 0 ? <p style={{ ...DIM, margin: '6px 0 0' }}>남긴 후기가 없습니다.</p> : detail.feedback.map(f => <FeedbackLine key={f.id} f={f} />)}
            </div>
          ) : null}
        </div>
      </div>

      <details data-part="feedback-all" style={{ ...CARD, padding: '10px 14px', marginTop: 16 }}>
        <summary style={{ cursor: 'pointer', fontSize: 13, fontWeight: 700, minHeight: 32 }}>최근 후기 전체 · {feedback?.length ?? '…'}건</summary>
        {feedback?.map(f => <FeedbackLine key={f.id} f={f} showPet />)}
      </details>
    </section>
  );
}

/** 880 상자를 벗어나 1120 까지. 화면이 더 좁으면 양옆 12px 를 남긴다. */
const WIDE: React.CSSProperties = { width: 'min(1120px, calc(100vw - 24px))', position: 'relative', left: '50%', transform: 'translateX(-50%)', boxSizing: 'border-box' };
const BTN: React.CSSProperties = { minHeight: 40, padding: '6px 12px', fontSize: 13, fontWeight: 700, borderRadius: 6, cursor: 'pointer', border: '1px solid rgba(127,127,127,0.35)', color: 'inherit', background: 'transparent' };
const ROW: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, textAlign: 'left', padding: '10px 12px', borderRadius: 8, border: '1px solid rgba(127,127,127,0.30)', borderLeftWidth: 4, borderLeftColor: 'rgba(127,127,127,0.30)', background: 'transparent', color: 'inherit', cursor: 'pointer', minWidth: 0, width: '100%', boxSizing: 'border-box' };
const ROW_OPEN: React.CSSProperties = { borderLeftColor: 'rgba(200,70,55,0.85)', background: 'rgba(220,80,60,0.06)' };
const BADGE: React.CSSProperties = { display: 'inline-block', fontSize: 11, fontWeight: 700, lineHeight: 1.5, padding: '0 7px', borderRadius: 999, border: '1px solid', whiteSpace: 'nowrap' };
const BADGE_OPEN: React.CSSProperties = { color: 'rgb(190,60,45)', borderColor: 'rgba(200,70,55,0.7)', background: 'rgba(220,80,60,0.08)' };
const BADGE_DONE: React.CSSProperties = { color: 'rgb(45,130,75)', borderColor: 'rgba(60,150,90,0.6)', background: 'rgba(60,150,90,0.08)' };
const CHIP: React.CSSProperties = { fontSize: 11, padding: '0 7px', borderRadius: 999, border: '1px solid rgba(127,127,127,0.4)', lineHeight: 1.6 };
const MINE: React.CSSProperties = { margin: 0, padding: '10px 12px', borderRadius: 8, background: 'rgba(127,127,127,0.10)', fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' };
const REPLY: React.CSSProperties = { margin: 0, padding: '10px 12px', borderRadius: 8, border: '1px solid rgba(127,127,127,0.25)', borderLeft: '4px solid #9C4232', fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' };
