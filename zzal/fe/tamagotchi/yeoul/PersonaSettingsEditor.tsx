'use client';
import {PERSONA_LAYOUT_STYLE,PersonaQuestionSheet,PersonaQuestionPane,PersonaQuestionFooter} from './PersonaQuestionSheet';
import {flyPieces,reactPersona,reducedMotion} from './personaAbsorption';
import {usePersonaViewport} from './usePersonaViewport';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import {useEffect,useLayoutEffect,useMemo,useRef,useState,type CSSProperties} from 'react';
import {createPortal} from 'react-dom';
import {getAuthoredPersona,type AuthoredPersona,type PersonaSource} from '../../lib/pet';
import {useSpriteGeometry} from '../../lib/spriteGeometry';
import {useZzalLocale} from '../../lib/locale';
import {useLive} from './useHatch';
import {PERSONA_STEPS,answerOf,pickChips,pieceOf,type Answers,type PersonaField} from './personaSteps';
import {personaStepCopy} from './personaStepCopy';
import {PersonaQuestionFields} from './PersonaQuestionFields';
import {PERSONA_EDITOR_EN,PERSONA_EDITOR_JA,PERSONA_EDITOR_KO} from './personaEditorCopy';
import {PERSONA_LABEL} from './constants';
import './persona-settings-editor.css';

export function editablePersona(source:PersonaSource,legacy?:{world?:string|null;note?:string|null}|null):AuthoredPersona {
 const old=source.legacySource;
 // Preserve legacy prompt input verbatim as editable text, never guessed onboarding chips.
 const preserved:Record<string,string>={world:old?.world??legacy?.world??'',persona:old?.personalities.map(value=>PERSONA_LABEL[value]??value).join(' · ')??'',extra:[old?.note??legacy?.note??'',old?.tone?`말투: ${old.tone}`:'',old?.genre?`장르: ${old.genre}`:''].filter(Boolean).join('\n')};
 return {version:1,fields:PERSONA_STEPS.flatMap(step=>step.fields).map(field=>source.authoredPersona?.fields.find(f=>f.fieldId===field.key)??{fieldId:field.key as AuthoredPersona['fields'][number]['fieldId'],chips:[],rawText:!source.authoredPersona?preserved[field.key]??'':'',skipped:!(!source.authoredPersona&&preserved[field.key])})};
}
/** Editing only uses the existing source patch; no onboarding lifecycle operation is reachable here. */
export default function PersonaSettingsEditor({source,onClose,onSaved,onReload,allowConfirm=false}:{source:PersonaSource;allowConfirm?:boolean;onClose:()=>void;onSaved:(source:PersonaSource)=>void|Promise<void>;onReload:()=>void}){
 const live=useLive(),locale=useZzalLocale(),copy=locale==='en'?PERSONA_EDITOR_EN:locale==='ja'?PERSONA_EDITOR_JA:PERSONA_EDITOR_KO;
 const labels=locale==='en'?{title:'Edit settings',cancel:'Cancel',previous:'Previous',next:'Next',skip:'Skip',name:'Character name stays the same.',reload:'Load latest settings'}:locale==='ja'?{title:'設定を変更',cancel:'キャンセル',previous:'前へ',next:'次へ',skip:'スキップ',name:'キャラクターの名前はそのままです。',reload:'最新の設定を読み込む'}:{title:'설정 수정',cancel:'취소',previous:'이전',next:'다음',skip:'건너뛰기',name:'캐릭터 이름은 그대로예요.',reload:'최신 설정 불러오기'};
 const metrics=interactionMetrics(live,false,'settings');
 useEffect(()=>metrics.shown('settings'),[]);
 const original=useMemo(()=>editablePersona(source,live.pet),[source]);
 const [draft,setDraft]=useState(original),[callMe,setCallMe]=useState(source.callMe??''),[idx,setIdx]=useState(0),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[conflict,setConflict]=useState(false);
 const root=useRef<HTMLDivElement>(null),stage=useRef<HTMLDivElement>(null),pane=useRef<HTMLDivElement>(null),texts=useRef<Record<string,HTMLTextAreaElement|null>>({}),lock=useRef(false),mounted=useRef(true),identity=useRef(live.petId);
 const activePet=useRef(live.petId);activePet.current=live.petId;
 const [kb,setKb]=useState(false),[busyFx,setBusyFx]=useState(false),[color,setColor]=useState('#4F8FCB');
 const chips=useRef<Record<string,HTMLButtonElement|null>>({}),actor=useRef<HTMLDivElement>(null),halo=useRef<HTMLSpanElement>(null),fxLock=useRef(false);
 usePersonaViewport(root,pane,setKb,'dialog');
 const [size,setSize]=useState({width:280,height:160});
 const src=live.img('base')??'',geometry=useSpriteGeometry(src,.05);
 const step=idx<PERSONA_STEPS.length?personaStepCopy(PERSONA_STEPS[idx],locale):null;
 const answers:Answers=Object.fromEntries(draft.fields.map(f=>[f.fieldId,{chips:[...f.chips],text:f.rawText}]));
 const dirtyFields=JSON.stringify(draft)!==JSON.stringify(original),dirty=dirtyFields||callMe.trim()!==(source.callMe??'');
 const legacyMissing=!source.authoredPersona&&!source.legacySource;
 const validation=dirtyFields&&legacyMissing?(locale==='en'?'Load the original settings again before saving.':locale==='ja'?'保存する前に元の設定をもう一度読み込んでください。':'기존 설정을 다시 불러온 뒤 저장해 주세요.'):source.callMe&&!callMe.trim()?copy.emptyAddress:draft.fields.some(f=>f.rawText.length>10000)?copy.tooLong:'';
 const invalidChips=draft.fields.some(f=>f.fieldId==='persona'&&f.chips.length>3);
 const update=(key:string,patch:Partial<AuthoredPersona['fields'][number]>)=>{setMessage('');setDraft(d=>({...d,fields:d.fields.map(f=>{if(f.fieldId!==key)return f;const next={...f,...patch,skipped:false};const before=original.fields.find(x=>x.fieldId===key)!;if(patch.skipped===true)next.skipped=true;else if(next.rawText===before.rawText&&JSON.stringify(next.chips)===JSON.stringify(before.chips))next.skipped=before.skipped;else next.skipped=!next.rawText.trim()&&!next.chips.length;return next})}))};
 const close=()=>{if(busy)return;if(!dirty||window.confirm(locale==='en'?'Discard your changes?':locale==='ja'?'変更を破棄しますか？':'수정한 내용을 버리고 나갈까요?'))onClose()};
 useEffect(()=>{mounted.current=true;const previous=document.activeElement as HTMLElement|null,overflow=document.body.style.overflow;document.body.style.overflow='hidden';root.current?.querySelector<HTMLButtonElement>('button')?.focus();return()=>{mounted.current=false;document.body.style.overflow=overflow;if(previous?.isConnected)previous.focus({preventScroll:true})}},[]);
 useEffect(()=>{if(live.petId!==identity.current)onClose()},[live.petId,onClose]);
 useLayoutEffect(()=>{const node=stage.current;if(!node)return;const measure=()=>setSize({width:node.clientWidth,height:node.clientHeight});measure();const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect()},[]);

 useLayoutEffect(()=>{pane.current?.scrollTo({top:0})},[idx]);
 useLayoutEffect(()=>{for(const el of Object.values(texts.current)){if(!el)continue;el.style.height='auto';el.style.height=`${Math.max(88,el.scrollHeight)}px`}},[idx,draft,locale]);
 const save=async()=>{if(lock.current||(!dirty&&!allowConfirm)||validation||invalidChips||!live.petId)return;metrics.attempt('settings_save');lock.current=true;setBusy(true);setMessage('');setConflict(false);try{
  if(!dirty&&allowConfirm){
    const fresh=await getAuthoredPersona(live.petId);
    if(!mounted.current||activePet.current!==identity.current)return;
    if(fresh.personaSourceHash!==source.personaSourceHash){setConflict(true);setMessage(copy.conflict);metrics.result('settings_save','failure',{code:'PERSONA_HASH_MISMATCH',reason:'client'});return}
    await onSaved(fresh);metrics.result('settings_save','success');return;
  }
  const result=await live.updatePersona({...(dirtyFields?{authoredPersona:draft}:{}),...(callMe.trim()&&callMe.trim()!==source.callMe?{callMe:callMe.trim()}:{}),expectedPersonaHash:source.personaSourceHash});metrics.result('settings_save',result.ok?'success':result.status==null?'uncertain':'failure',result.ok?undefined:{code:result.code,reason:result.status==null?'network':`http_${result.status}`});if(!mounted.current||activePet.current!==identity.current)return;if(!result.ok){setConflict(result.code==='ZZAL_PERSONA_SOURCE_STALE');setMessage(result.code==='ZZAL_PERSONA_SOURCE_STALE'?copy.conflict:result.status==null?copy.uncertain:result.message??copy.failed);return}const fresh=await getAuthoredPersona(live.petId);if(!mounted.current||activePet.current!==identity.current)return;await onSaved(fresh);}catch{metrics.result('settings_save','uncertain');if(mounted.current&&activePet.current===identity.current)setMessage(copy.uncertain)}finally{lock.current=false;if(mounted.current&&activePet.current===identity.current)setBusy(false)}};
 const next=async()=>{
  if(!step||fxLock.current||busy)return;fxLock.current=true;setBusyFx(true);
  (document.activeElement as HTMLElement|null)?.blur();
  await new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve())));
  const pieces:{rect:DOMRect;label:string}[]=[];
  for(const field of step.fields){const answer=answerOf(answers,field.key);for(const chip of answer.chips){const el=chips.current[`${field.key}:${chip}`];if(el)pieces.push({rect:el.getBoundingClientRect(),label:el.textContent??chip})}const el=texts.current[field.key];if(el&&answer.text.trim())pieces.push({rect:el.getBoundingClientRect(),label:pieceOf(answer.text.trim())})}
  if(pieces.length>3){pieces[2].label+=` +${pieces.length-3}`;pieces.length=3}
  setColor(step.color);const target=stage.current?.getBoundingClientRect();
  try{if(!reducedMotion()&&target&&pieces.length)await flyPieces(pieces,target);if(mounted.current)await reactPersona(actor.current,halo.current);if(mounted.current)setIdx(n=>n+1)}finally{fxLock.current=false;if(mounted.current)setBusyFx(false)}
 };
 const scale=Math.min(Math.max(1,size.height-8)/Math.max(1,geometry.height*(1-geometry.top-geometry.bottom)),Math.max(1,size.width-24)/Math.max(1,geometry.width*(geometry.right-geometry.left)));
 const actorStyle:CSSProperties={position:'absolute',width:geometry.width*scale,height:geometry.height*scale,maxWidth:'none',maxHeight:'none',left:size.width/2-geometry.width*scale*(geometry.left+geometry.right)/2,top:size.height-geometry.height*scale*(1-geometry.bottom),visibility:geometry.loaded?'visible':'hidden'};
 return createPortal(<div ref={root} className="pse-root pe-root zzal-local-fonts" data-q="settings" data-kb={kb?'1':'0'} data-variant="glow" role="dialog" aria-modal="true" aria-labelledby="pse-title" data-part="persona-settings-editor" data-question={step?.key??'address'} aria-busy={busy} onKeyDown={event=>{event.stopPropagation();if(event.key==='Escape'){event.preventDefault();close()}if(event.key==='Tab'){const nodes=[...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),textarea:not(:disabled),input:not(:disabled)')];if(event.shiftKey&&document.activeElement===nodes[0]){event.preventDefault();nodes.at(-1)?.focus()}else if(!event.shiftKey&&document.activeElement===nodes.at(-1)){event.preventDefault();nodes[0]?.focus()}}}}>
  <style>{PERSONA_LAYOUT_STYLE}</style>
  <header><h2 id="pse-title">{labels.title}</h2><button type="button" disabled={busy} onClick={close}>{labels.cancel}</button></header>
  <div className="pse-actor-stage pe-stage" ref={stage}><span ref={halo} data-part="persona-settings-glow" style={{position:'absolute',inset:'-10%',borderRadius:'50%',pointerEvents:'none',background:`radial-gradient(closest-side, ${color}cc 0%, ${color}55 45%, ${color}00 100%)`,opacity:.5}}/><div ref={actor} style={{position:'absolute',inset:0,transformOrigin:'50% 85%'}}>{src?<img data-part="persona-settings-actor" src={src} alt={live.pet?.name??''} style={actorStyle}/>:<span>{live.pet?.name}</span>}</div></div>
  <PersonaQuestionSheet>
  {kb&&<button type="button" className="pe-keyboard-done" onClick={()=>(document.activeElement as HTMLElement)?.blur()}>{locale==='en'?'Done':locale==='ja'?'完了':'완료'}</button>}
  <PersonaQuestionPane paneRef={pane}><div className="pse-question" key={step?.key??'address'}><h3>{step?.q??copy.address}</h3>{step?.sub&&<p>{step.sub}</p>}
   {step?<PersonaQuestionFields step={step} answers={answers} busyFx={busy||busyFx} editing chipRefs={chips} textRefs={texts} setChip={(field:PersonaField,chip:string)=>update(field.key,{chips:pickChips(field,answerOf(answers,field.key).chips,chip)})} setText={(key,text)=>update(key,{rawText:text})}/>:<><label>{copy.address}<input autoComplete="off" data-part="persona-settings-address" aria-label={copy.address} maxLength={20} disabled={busy} value={callMe} onChange={event=>{setCallMe(event.target.value);setMessage('')}} placeholder={copy.unknown}/></label><p>{labels.name} {live.pet?.name}</p>{!source.authoredPersona&&live.pet?.note&&<blockquote>{live.pet.note}</blockquote>}</>}
  </div></PersonaQuestionPane>
  <PersonaQuestionFooter><p role={message||validation?'alert':'status'} data-part="persona-settings-status">{message||(!step?validation:'')}</p>{(conflict||(dirtyFields&&legacyMissing))&&<button type="button" onClick={()=>{if(window.confirm(copy.replace))onReload()}}>{labels.reload}</button>}<div className="pse-nav"><button type="button" disabled={busy||busyFx||idx===0} onClick={()=>setIdx(n=>n-1)}>{labels.previous}</button><button type="button" className="pse-primary" data-action="persona-settings-next" disabled={busy||busyFx||(!step&&((!dirty&&!allowConfirm)||!!validation||invalidChips))} onClick={()=>step?void next():void save()}>{busy?copy.saving:step?labels.next:!dirty&&allowConfirm?(locale==='en'?'Confirm settings':locale==='ja'?'この設定で続ける':'이 설정으로 시작하기'):copy.save}</button>{step&&<button type="button" data-action="persona-settings-skip" disabled={busy||busyFx} onClick={()=>setIdx(n=>n+1)}>{labels.skip}</button>}</div></PersonaQuestionFooter>
  </PersonaQuestionSheet>
 </div>,document.body);
}
