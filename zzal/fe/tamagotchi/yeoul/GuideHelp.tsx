'use client';

import { useEffect, useRef, useState } from 'react';
import type { PetDetail } from '../../lib/pet';
import { guideCopy, type GuideTopic } from './guideTopics';
import { C, C2, radius, TAP_MIN } from './ui';
import {useZzalLocale} from '../../lib/locale';
import {localizedGuide} from './guideLocale';

/** Read-only and deliberately independent of care, clock, tutorial and reward mutations. */
export default function GuideHelp({ pet, sample = false, topics: providedTopics, copy: providedCopy }: {
  pet: PetDetail | null; sample?: boolean; topics?: readonly GuideTopic[];
  copy?: { [K in keyof typeof guideCopy]: string };
}) {
  const locale=useZzalLocale();
  const localized=localizedGuide(locale);
  const topics=providedTopics??localized.topics;
  const copy=providedCopy??localized.copy;
  const [open, setOpen] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    if (open && !node.open) node.showModal();
    if (!open && node.open) node.close();
  }, [open]);
  const button = { minHeight: TAP_MIN, minWidth: TAP_MIN, padding: '8px 12px', border: `1px solid ${C2.lineWarm}`, borderRadius: radius.pill, background: C.paper, color: C.ink, fontSize: 13, cursor: 'pointer' };
  const facts = sample ? [copy.sample] : !pet ? [copy.unavailable] : [
    ...(pet.trip ? [copy.traveling] : []),
    ...(pet.clock ? [pet.clock.sleeping ? copy.sleeping : copy.awake] : []),
    ...(pet.food ? [`${copy.food.replace('{count}', String(pet.food.count))}${pet.food.nextInSeconds != null ? ` · ${copy.refill.replace('{minutes}', String(Math.ceil(pet.food.nextInSeconds / 60)))}` : ''}`] : []),
    ...(pet.gauges ? [copy.trash.replace('{count}', String(pet.gauges.trash))] : []),
    ...(pet.today?.bathDone ? [copy.bathed] : []),
    ...(pet.sick ? [copy.sick] : []),
  ];
  const relatedTopic = sample || !pet ? null : pet.trip ? 'travel' : pet.sick ? 'medicine' : pet.clock?.sleeping ? 'sleep' : pet.food?.count === 0 ? 'food' : (pet.gauges?.trash ?? 0) > 0 ? 'clean' : null;
  const orderedTopics=relatedTopic?[...topics.filter(t=>t.id===relatedTopic),...topics.filter(t=>t.id!==relatedTopic)]:topics;
  const showRelated = () => {
    const topic = dialog.current?.querySelector<HTMLDetailsElement>(`[data-guide-topic="${relatedTopic}"]`);
    if (topic) { topic.open = true; topic.scrollIntoView({ block: 'start' }); topic.querySelector('summary')?.focus(); }
  };
  return <>
    <button type="button" data-action="guide-open" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)} style={{ ...button, flex: 'none', position: 'relative', zIndex: 3 }}>{copy.open}</button>
    <dialog ref={dialog} aria-label={copy.title} onCancel={() => setOpen(false)} onClose={() => setOpen(false)} style={{ width: 'min(540px, calc(100% - 24px))', height: 'min(700px, calc(100dvh - 24px))', maxHeight: 'calc(100dvh - 24px)', boxSizing: 'border-box', padding: 16, border: `1px solid ${C2.lineWarm}`, borderRadius: 20, background: C.paper, color: C.ink, margin: 'auto' }}>
      <div style={{ height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0, gap: 12 }}>
        <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}><strong>{copy.title}</strong><button type="button" data-action="guide-close" style={button} onClick={() => setOpen(false)}>{copy.close}</button></header>
        <div data-part="guide-content" style={{ overflowY: 'auto', minHeight: 0, flex: 1, overscrollBehavior: 'contain', paddingBottom: 'max(12px, env(safe-area-inset-bottom))', overflowWrap: 'anywhere',wordBreak:locale==='ko'?'keep-all':'normal' }}>
          <p style={{ lineHeight: 1.6, fontSize: 14, marginTop: 0 }}>{copy.intro}</p>
          <details data-part="guide-status" open={!!relatedTopic || sample} aria-label={copy.current} style={{ padding: 12, borderRadius: 12, background: C2.paperWarm, marginBottom: 16 }}><summary style={{minHeight:TAP_MIN,cursor:'pointer',lineHeight:1.5}}><strong>{copy.current}</strong>{!relatedTopic&&pet?.clock&&!sample?' · '+(pet.clock.sleeping?copy.sleeping:copy.awake):''}</summary>{facts.map(fact => <p key={fact} style={{ margin: '8px 0 0', lineHeight: 1.6, fontSize: 14 }}>{fact}</p>)}{relatedTopic && <button type="button" data-action="guide-related" onClick={showRelated} style={{ ...button, marginTop: 8 }}>{copy.related}</button>}</details>
          {orderedTopics.map(topic => <details key={topic.id} data-guide-topic={topic.id} style={{ borderTop: `1px solid ${C2.lineWarm}` }}><summary style={{ minHeight: TAP_MIN, padding: '12px 4px', boxSizing: 'border-box', cursor: 'pointer', lineHeight: 1.5, fontSize: 15 }}>{topic.title}</summary><div style={{ padding: '0 4px 12px', lineHeight: 1.7, fontSize: 14 }}><p style={{ margin: '0 0 8px', color: C2.muted }}>{copy.where}: {topic.where}</p><p style={{ margin: 0 }}>{topic.body}</p></div></details>)}
        </div>
      </div>
    </dialog>
  </>;
}
