'use client';

import {interactionMetrics} from '../focused-room/interactionMetrics';
import { useEffect, useState, type ReactNode } from 'react';
import { advanceFirstMeeting, getFirstMeeting, type FirstMeetingAction, type FirstMeetingStatus } from '../../lib/firstMeeting';
import FirstMeeting from './FirstMeeting';
import {useFirstMeetingDiscovery} from './useFirstMeetingDiscovery';
import { useZzalLocale } from '../../lib/locale';
import { FIRST_MEETING_EN, FIRST_MEETING_JA } from './firstMeetingCopy';
import { C, SANS } from './ui';

/** Only the server decides whether a legacy pet has already completed its first meeting. */
export default function FirstMeetingGate({ petId, characterName, imageSrc, children,focusedScene=false }: { petId: number; characterName: string; imageSrc?: string; children: ReactNode;focusedScene?:boolean }) {
  const metrics=interactionMetrics({pet:null},false,'first_meeting','tutorial');
  const locale = useZzalLocale();
  const copy = locale === 'en' ? FIRST_MEETING_EN : locale === 'ja' ? FIRST_MEETING_JA : undefined;
  const gateCopy = locale === 'en' ? { loading: 'Checking your first meeting…', error: 'Couldn’t load your first meeting.', retry: 'Try again' } : locale === 'ja' ? { loading: '初めての出会いを確認しています…', error: '初めての出会いを読み込めませんでした。', retry: 'もう一度確認' } : { loading: '첫 만남을 확인하고 있어요…', error: '첫 만남을 불러오지 못했어요.', retry: '다시 확인하기' };
  const [status, setStatus] = useState<FirstMeetingStatus | null>(null);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const discovery=useFirstMeetingDiscovery(petId,status,locale);
  useEffect(() => {
    const controller = new AbortController();
    setStatus(null); setError(false);
    void getFirstMeeting(petId, controller.signal).then(s => {
      if (!controller.signal.aborted) setStatus(s);
    }).catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, [petId, attempt]);
  useEffect(()=>{if(status)metrics.shown(`first_meeting_${status.state.toLowerCase()}`)},[status?.state]);
  const action = async (payload: FirstMeetingAction) => {
    metrics.attempt(payload.action.toLowerCase());
    try {
      const next = await advanceFirstMeeting(petId, payload);
      metrics.result(payload.action.toLowerCase(),'success');
      setStatus(next);
      return next;
    } catch (failure) {
      // Confirm a possibly committed action before offering a retry; never send a different action automatically.
      const confirmed = await getFirstMeeting(petId).catch(() => null);
      const progressed = confirmed && (
        (payload.action === 'NEXT' && confirmed.state === 'ADDRESS')
        || (payload.action === 'SET_ADDRESS' && confirmed.state === 'READY_TO_ENTER' && confirmed.addressStatus === 'EXPLICIT' && confirmed.callMe === payload.callMe)
        || (payload.action === 'DEFER_ADDRESS' && confirmed.state === 'READY_TO_ENTER' && (confirmed.addressStatus === 'DEFERRED' || (status?.callMe != null && confirmed.callMe === status.callMe)))
        || (payload.action === 'ENTER_ROOM' && confirmed.state === 'COMPLETE')
      );
      if (progressed) { metrics.result(payload.action.toLowerCase(),'success'); setStatus(confirmed); return confirmed; }
      metrics.result(payload.action.toLowerCase(),'uncertain');
      throw failure;
    }
  };
  if (!status) return <section aria-live="polite" style={{ padding: 24, fontFamily: SANS, color: C.ink }}>
    <p>{error ? gateCopy.error : gateCopy.loading}</p>
    {error && <button type="button" onClick={() => setAttempt(a => a + 1)} style={{ minHeight: 48, padding: '12px 20px', fontSize: 16 }}>{gateCopy.retry}</button>}
  </section>;
  if (status.state === 'COMPLETE') return children;
  return <FirstMeeting key={`${petId}:${status.state}`} petId={petId} characterName={characterName} imageSrc={imageSrc}
    focusedScene={focusedScene} copy={copy}
    greetingRetryAllowed={status.greetingRetryAllowed}
    discovery={discovery.line}
    initialStage={status.state === 'DISCOVERED' ? 'discovery' : status.state === 'ADDRESS' ? 'address' : 'greeting'}
    initialGreeting={status.state === 'READY_TO_ENTER' ? { line: status.greetingLine } : null}
    onNext={async () => { discovery.cancel(); await action({ action: 'NEXT' }); }}
    onAddress={async address => { const next = await action(address.status === 'EXPLICIT' ? { action: 'SET_ADDRESS', callMe: address.value } : { action: 'DEFER_ADDRESS' }); return { line: next.greetingLine }; }}
    onRetryGreeting={async () => { const next = await action(status.addressStatus === 'EXPLICIT' && status.callMe ? { action: 'SET_ADDRESS', callMe: status.callMe } : { action: 'DEFER_ADDRESS' }); return { line: next.greetingLine }; }}
    onEnter={async () => { await action({ action: 'ENTER_ROOM' }); }} />;
}
