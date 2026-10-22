'use client';
import type {MutableRefObject} from 'react';
import {useZzalLocale} from '../../lib/locale';
import {useUiText} from '../../lib/uiCopy';
import {personaChipLabel} from './personaChipCopy';
import {C,TAP_MIN,gap,radius,fz,ink} from './ui';
import {answerOf,type Answers,type PersonaStep,type PersonaField} from './personaSteps';
function rowsOf(f: PersonaField): { name: string; chips: readonly string[] }[] {
  if (!f.chips.length) return [];
  if (!f.rows || !f.rows.length) return [{ name: '', chips: f.chips }];
  const out: { name: string; chips: readonly string[] }[] = [];
  let at = 0;
  f.rows.forEach((n, i) => {
    const end = i === f.rows!.length - 1 ? f.chips.length : at + n;
    if (at < end) out.push({ name: f.rowNames?.[i] ?? '', chips: f.chips.slice(at, end) });
    at = end;
  });
  return out;
}

/** Shared question fields: onboarding and settings use the same chips, caps, labels and original text. */
export function PersonaQuestionFields({step,answers,busyFx=false,setChip,setText,chipRefs,textRefs,editing=false}:{step:PersonaStep;answers:Answers;busyFx?:boolean;setChip:(field:PersonaField,chip:string)=>void;setText:(key:string,text:string,max:number)=>void;chipRefs?:MutableRefObject<Record<string,HTMLButtonElement|null>>;textRefs?:MutableRefObject<Record<string,HTMLTextAreaElement|null>>;editing?:boolean}){
 const locale=useZzalLocale(),tr=useUiText();
 return <>{step.fields.map((f) => {
                const x = answerOf(answers, f.key);
                const max = editing ? 10000 : f.text?.max ?? 0;
                return (
                  <div key={f.key} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
                    {f.label && <span style={{ fontSize: fz.sm, color: C.sub2 }}>{f.label}</span>}
                    {rowsOf(f).map((g, gi) => (
                      <div key={gi} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                      {g.name && <span style={{ fontSize: fz.xs, color: ink(.5) }}>{g.name}</span>}
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
                        {g.chips.map((c) => {
                          const on = x.chips.includes(c);
                          return (
                            <button
                              key={c} type="button" className="pe-chip" data-part="pe-chip" data-chip={c} aria-pressed={on}
                              data-flying={busyFx && on ? '1' : undefined}
                              ref={(el) => { chipRefs && (chipRefs.current[`${f.key}:${c}`] = el); }}
                              onClick={() => setChip(f, c)} disabled={busyFx || (!on && f.maxChips != null && x.chips.length >= f.maxChips)}
                              style={{ minHeight: TAP_MIN, padding: '0 15px', borderRadius: radius.pill, border: `${on ? 2 : 1}px solid ${on ? C.accent : C.lineHard}`, background: on ? C.accentSoft : C.paper, fontSize: fz.md, color: on ? C.accent : C.ink }}
                            >{personaChipLabel(c,locale)}</button>
                          );
                        })}
                      </div>
                      </div>
                    ))}
                    {x.chips.some(c => !f.chips.includes(c)) && <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
                      <span style={{ width: '100%', fontSize: fz.sm, color: C.sub2 }}>{tr('이전에 선택한 항목이에요. 누르면 선택을 해제해요.')}</span>
                      {x.chips.filter(c => !f.chips.includes(c)).map(c => <button key={c} type="button" data-part="pe-legacy-chip" aria-pressed="true" onClick={() => setChip(f, c)} disabled={busyFx} style={{ minHeight: TAP_MIN, padding: '0 15px', borderRadius: radius.pill, border: `2px solid ${C.accent}`, background: C.accentSoft, fontSize: fz.md, color: C.accent }}>{c} ×</button>)}
                    </div>}
                    {f.maxChips != null && x.chips.length > f.maxChips && <p role="alert" style={{ fontSize: fz.sm, color: C.accent }}>{locale==='en'?`Choose up to ${f.maxChips}. Please review your earlier choices.`:locale==='ja'?`${f.maxChips}つまで選べます。以前の選択を確認してください。`:`성격은 최대 ${f.maxChips}개까지 선택할 수 있어요. 이전 선택을 확인해 주세요.`}</p>}
                    {f.text && (
                      // ★ 글 칸은 textarea 다 — 자리표시가 실제 사람이 쓴 세세한 예 2~3개라 길다(칩 어휘 v2).
                      //   input 이면 320 폭에서 자리표시가 잘린다. Enter/키보드 완료는 입력을 유지하고 키보드만 닫는다.
                      <textarea
                        ref={(el) => { if(textRefs) textRefs.current[f.key] = el; }}
                        value={x.text} maxLength={max} placeholder={f.text.ph} rows={1} className="pe-text"
                        onChange={(e) => setText(f.key, editing ? e.target.value : e.target.value.replace(/\n/g, ' '), max)}
                        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (!e.nativeEvent.isComposing) e.currentTarget.blur(); } }}
                        data-part="pe-text" data-field={f.key} aria-label={f.label ?? step.q} enterKeyHint="done"
                        style={{ boxSizing: 'border-box', minHeight: TAP_MIN, padding: '10px 14px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 16, lineHeight: 1.4, color: C.ink, outline: 'none', resize: 'none', fontFamily: 'inherit', opacity: busyFx && x.text.trim() ? 0.35 : 1 }}
                      />
                    )}

                  </div>
                );
              })}</>;
}
