'use client';

import { useEffect, useRef, useState } from 'react';
import { C, SANS, GAEGU } from './ui';
import FocusedMeetingScene from '../focused-room/FocusedMeetingScene';
import {useZzalLocale}from '../../lib/locale';

/** Copy is separate from authored names and text; locale changes must never rewrite source data. */
export const FIRST_MEETING_KO = {
  next: '다음', addressLabel: '캐릭터가 나를 부를 이름을 알려 주세요.',
  addressHint: '캐릭터가 나를 부를 때 쓸 이름이에요. 나중에 바꿀 수 있어요.', addressPlaceholder: '불리고 싶은 이름',
  save: '이렇게 불러줘', later: '사용자님이라 불러줘!', saving: '저장하는 중…',
  enter: '방으로 들어가기', entering: '들어가는 중…',
  enterWithoutGreeting: '먼저 방으로 들어가기',
  preparing: '첫 인사를 준비하는 중…', retryGreeting: '첫 인사 다시 준비하기',
  greetingFailed: '호칭은 저장했어요. 첫 인사를 아직 준비하지 못했어요. 먼저 방으로 들어가도 괜찮아요.',
  failed: '저장 결과를 확인하지 못했어요. 이 화면의 입력은 남아 있어요. 다시 확인해 주세요.',
  enterFailed: '방에 들어가지 못했어요. 다시 눌러 주세요.',
  nextFailed: '다음 단계로 넘어가지 못했어요. 다시 눌러 주세요.',
};
export type MeetingCopy = { [K in keyof typeof FIRST_MEETING_KO]: string };
export type MeetingAddress = { status: 'EXPLICIT'; value: string } | { status: 'DEFERRED' };
export interface MeetingGreeting { line: string }

/** Server persistence is injected; a successful UI transition must follow a confirmed server result. */
export default function FirstMeeting({ petId, characterName, imageSrc, discovery, initialGreeting, initialStage, greetingRetryAllowed = true, copy = FIRST_MEETING_KO, onNext, onAddress, onRetryGreeting, onEnter,focusedScene=false }: {
  focusedScene?:boolean;
  petId: number;
  characterName: string;
  imageSrc?: string;
  discovery: string;
  initialGreeting?: MeetingGreeting | null;
  initialStage?: 'discovery' | 'address' | 'greeting';
  greetingRetryAllowed?: boolean;
  onNext: () => Promise<void>;
  copy?: MeetingCopy;
  onAddress: (address: MeetingAddress) => Promise<MeetingGreeting>;
  onRetryGreeting: () => Promise<MeetingGreeting>;
  onEnter: () => Promise<void>;
}) {
  const locale=useZzalLocale();
  const [stage, setStage] = useState<'discovery' | 'address' | 'greeting'>(initialStage ?? (initialGreeting ? 'greeting' : 'discovery'));
  const [greeting, setGreeting] = useState(initialGreeting?.line ?? '');
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const locked = useRef(false);
  const input = useRef<HTMLInputElement>(null);
  const primary = useRef<HTMLButtonElement>(null);
  const era = useRef(0);
  useEffect(() => { era.current += 1; return () => { era.current += 1; }; }, [petId]);

  const next = async () => {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const mine = era.current;
    try { await onNext(); if (mine === era.current) setStage('address'); }
    catch { if (mine === era.current) setError(copy.nextFailed); }
    finally { if (mine === era.current) { locked.current = false; setBusy(false); } }
  };
  const submit = async (deferred: boolean) => {
    if (locked.current || (!deferred && !address.trim())) return;
    locked.current = true; setBusy(true); setError('');input.current?.blur();
    const mine = era.current;
    try {
      const result = await onAddress(deferred ? { status: 'DEFERRED' } : { status: 'EXPLICIT', value: address.trim() });
      if (mine !== era.current) return;
      setGreeting(result.line); setStage('greeting');
    } catch { if (mine === era.current) setError(copy.failed); }
    finally { if (mine === era.current) { locked.current = false; setBusy(false); } }
  };
  const retryGreeting = async () => {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const mine = era.current;
    try { const result = await onRetryGreeting(); if (mine === era.current) setGreeting(result.line); }
    catch { if (mine === era.current) setError(copy.greetingFailed); }
    finally { if (mine === era.current) { locked.current = false; setBusy(false); } }
  };
  const enter = async () => {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const mine = era.current;
    try { await onEnter(); }
    catch { if (mine === era.current) setError(copy.enterFailed); }
    finally { if (mine === era.current) { locked.current = false; setBusy(false); } }
  };
  const button = { minHeight: 48, width: '100%', borderRadius: 16, padding: '12px 18px', fontSize: 16, fontFamily: 'inherit', cursor: busy ? 'wait' : 'pointer' } as const;
  const controls=<>
    {stage === 'discovery' && <button ref={primary} type="button" data-action="meeting-next" disabled={busy} onClick={() => void next()} style={{ ...button, border: 0, background: C.accent, color: C.paper }}>{copy.next}</button>}
    {stage === 'address' && <form onSubmit={e => { e.preventDefault(); void submit(false); }} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <label htmlFor="meeting-address" style={{ fontSize: 14,wordBreak:locale==='ko'?'keep-all':'normal',overflowWrap:'anywhere',lineHeight:1.5 }}>{copy.addressHint}</label>
      <input ref={input} id="meeting-address" value={address} maxLength={20} placeholder={copy.addressPlaceholder} autoComplete="off" disabled={busy} onChange={e => setAddress(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && (e.nativeEvent.isComposing || e.keyCode === 229)) e.preventDefault(); }} style={{ minHeight: 48, width: '100%', boxSizing: 'border-box', border: `1px solid ${C.line}`, borderRadius: 14, padding: '10px 14px', fontSize: 16, fontFamily: 'inherit', background: C.paper, color: C.ink }} />
      <button type="submit" data-action="meeting-save" disabled={busy || !address.trim()} style={{ ...button, border: 0, background: C.accent, color: C.paper, opacity: busy || !address.trim() ? .55 : 1 }}>{busy ? copy.preparing : copy.save}</button>
      <button type="button" data-action="meeting-later" disabled={busy} onClick={() => void submit(true)} style={{ ...button, border: `1px solid ${C.line}`, background: 'transparent', color: C.ink }}>{copy.later}</button>
    </form>}
    {stage === 'greeting' && !greeting && greetingRetryAllowed && <button type="button" data-action="meeting-retry-greeting" disabled={busy} onClick={() => void retryGreeting()} style={{ ...button, border: 0, background: C.accent, color: C.paper }}>{busy ? copy.preparing : copy.retryGreeting}</button>}
    {stage === 'greeting' && <button ref={primary} type="button" data-action="meeting-enter" disabled={busy} onClick={() => void enter()} style={{ ...button, border: 0, background: C.accent, color: C.paper }}>{busy ? copy.entering : greeting ? copy.enter : copy.enterWithoutGreeting}</button>}
    {error && <p role="alert" style={{ margin: 0, fontSize: 14, lineHeight: 1.5 }}>{error}</p>}
  </>;
  if(focusedScene)return <FocusedMeetingScene name={characterName} stage={stage} speech={stage==='greeting'?greeting:discovery}>{stage==='greeting'&&!greeting&&<p role="status">{copy.greetingFailed}</p>}{controls}</FocusedMeetingScene>;
  return <section aria-labelledby="first-meeting-name" style={{ flex: 1, minHeight: 0, overflowY: 'auto', background: C.shell, color: C.ink, fontFamily: SANS, padding: 'max(24px, env(safe-area-inset-top)) 24px max(24px, env(safe-area-inset-bottom))', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', justifyContent: 'safe center', gap: 24 }}>
    <h1 id="first-meeting-name" style={{ fontFamily: GAEGU, margin: 0, fontSize: 30, overflowWrap: 'anywhere' }}>{characterName}</h1>
    {imageSrc && <img src={imageSrc} alt={characterName} style={{ display: 'block', width: 'min(42vw, 180px)', maxHeight: '24dvh', objectFit: 'contain', alignSelf: 'center' }} />}
    <div aria-live="polite" style={{ fontSize: 19, lineHeight: 1.7, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{stage === 'discovery' ? discovery : stage === 'greeting' ? (greeting || copy.greetingFailed) : copy.addressLabel}</div>
    {controls}

  </section>;
}
