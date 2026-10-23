'use client';

import { useEffect, useRef, useState } from 'react';
import { getChatHistory, getLegacyChatHistory, type ChatHistoryTurn, type LegacyChatHistoryCall } from '../../lib/pet';
import { C, radius, TAP_MIN } from './ui';
import {useUiText} from '../../lib/uiCopy';
import {useZzalLocale} from '../../lib/locale';

/** 원문은 펼칠 때 읽는다. 대화 전송/모델 생성과 분리된 읽기 전용 이력. */
export function ChatHistory({ petId }: { petId: number }) {
  const tr=useUiText(); const locale=useZzalLocale();
  const [open, setOpen] = useState(false);
  const [legacy, setLegacy] = useState(false);
  const [turns, setTurns] = useState<ChatHistoryTurn[]>([]);
  const [calls, setCalls] = useState<LegacyChatHistoryCall[]>([]);
  const [cursor, setCursor] = useState<number | null | undefined>(undefined);
  const [oldCursor, setOldCursor] = useState<number | null | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const list = useRef<HTMLDivElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { if (open && dialog.current && !dialog.current.open) dialog.current.showModal(); if (!open) dialog.current?.close(); }, [open]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; controller.current?.abort(); }; }, []);

  const load = async (old: boolean) => {
    const before = old ? oldCursor : cursor;
    if (inFlight.current || before === null) return;
    inFlight.current = true; setLoading(true); setError('');
    const abort = new AbortController(); controller.current = abort;
    const deadline = setTimeout(() => abort.abort(), 15_000);
    const scrollHeight = list.current?.scrollHeight ?? 0;
    const scrollTop = list.current?.scrollTop ?? 0;
    try {
      if (old) {
        const page = await getLegacyChatHistory(petId, before, abort.signal);
        if (!alive.current) return;
        setCalls((current) => Array.from(new Map([...page.calls, ...current].map((c) => [c.id, c])).values()).sort((a, b) => a.id - b.id));
        setOldCursor(page.nextBeforeId);
      } else {
        const page = await getChatHistory(petId, before, abort.signal);
        if (!alive.current) return;
        setTurns((current) => Array.from(new Map([...page.turns, ...current].map((t) => [t.id, t])).values()).sort((a, b) => a.id - b.id));
        setCursor(page.nextBeforeId);
      }
      requestAnimationFrame(() => { if (list.current) list.current.scrollTop = before === undefined ? list.current.scrollHeight : scrollTop + list.current.scrollHeight - scrollHeight; });
    } catch { if (alive.current) setError('지난 대화를 불러오지 못했어요. 다시 눌러 주세요.'); }
    finally { clearTimeout(deadline); inFlight.current = false; if (alive.current) setLoading(false); }
  };
  const button = { minHeight: TAP_MIN, padding: '8px 12px', border: `1px solid ${C.lineHard}`, borderRadius: radius.pill, color: C.ink, background: C.paper, fontSize: 14, cursor: 'pointer' };
  const line = (key: string, who: string, text: string, at: string | null) => <p key={key} style={{ margin: '10px 0', lineHeight: 1.5, overflowWrap: 'anywhere' }}><strong>{tr(who)}</strong>{at && <time dateTime={at} style={{ marginLeft: 8, fontSize: 12 }}>{new Date(at).toLocaleString(locale==='en'?'en-US':locale==='ja'?'ja-JP':'ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time>}<br />{text}</p>;
  const next = legacy ? oldCursor : cursor;
  return <div data-part="chat-history" style={{ width: '100%', color: C.ink, fontSize: 14 }}>
    <button type="button" aria-haspopup="dialog" aria-expanded={open} style={button} onClick={() => { setOpen(!open); if (!open && cursor === undefined && !legacy) void load(false); }}>{tr(open?'지난 대화 접기':'지난 대화 보기')}</button>
    <dialog ref={dialog} aria-label={tr('지난 대화')} onCancel={() => setOpen(false)} style={{ width: 'min(540px, calc(100% - 24px))', height: 'min(640px, calc(100dvh - 32px))', maxHeight: 'calc(100dvh - 32px)', boxSizing: 'border-box', margin: 'auto', padding: 12, borderRadius: 16, border: `1px solid ${C.lineHard}`, background: C.paper, color: C.ink }}><div style={{ height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0, gap: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}><strong>{tr('지난 대화')}</strong><button type="button" style={button} onClick={() => setOpen(false)}>{tr('닫기')}</button></div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        <button type="button" aria-pressed={!legacy} disabled={loading} style={button} onClick={() => { setLegacy(false); if (cursor === undefined) void load(false); }}>{tr('대화 기록')}</button>
        <button type="button" aria-pressed={legacy} disabled={loading} style={button} onClick={() => { setLegacy(true); if (oldCursor === undefined) void load(true); }}>{tr('예전 부름 기록')}</button>
      </div>
      <div ref={list} data-part="chat-history-lines" tabIndex={0} role="region" aria-label={tr(legacy ? '예전 부름 원문' : '대화 원문')} style={{ flex: '1 1 0', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain', padding: '0 4px' }}>
        {legacy ? calls.flatMap((c) => [line(`old-${c.id}-pet`, '캐릭터', c.petLine, c.calledAt), ...(c.userLine ? [line(`old-${c.id}-user`, '나', c.userLine, c.answeredAt)] : []), ...(c.replyLine ? [line(`old-${c.id}-reply`, '캐릭터', c.replyLine, c.answeredAt)] : [])]) : turns.map((t) => line(`turn-${t.id}`, t.speaker === 'PET' ? '캐릭터' : '나', t.line, t.at))}
        {!loading && !error && (legacy ? calls.length === 0 : turns.length === 0) && <p>{tr('아직 이곳에 남은 대화가 없어요.')}</p>}
      </div>
      {error && <p role="status">{tr(error)}</p>}
      <button type="button" data-action="chat-history-more" disabled={loading || next === null} style={button} onClick={() => { void load(legacy); }}>{tr(loading ? '대화를 불러오는 중…' : next === null ? '더 불러올 대화가 없어요.' : error ? '다시 불러오기' : '이전 대화 더 보기')}</button>
    </div></dialog>
  </div>;
}
