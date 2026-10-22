'use client';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import {useEffect,useLayoutEffect,useMemo,useRef,useState,type CSSProperties} from 'react';
import {createPortal} from 'react-dom';
import type {AuthoredPersona,PersonaSource} from '../../lib/pet';
import {useSpriteGeometry} from '../../lib/spriteGeometry';
import {useZzalLocale} from '../../lib/locale';
import {useLive} from './useHatch';
import {PERSONA_STEPS,answerOf,pickChips,type Answers,type PersonaField} from './personaSteps';
import {personaStepCopy} from './personaStepCopy';
import {PersonaQuestionFields} from './PersonaQuestionFields';
import {PERSONA_EDITOR_EN,PERSONA_EDITOR_JA,PERSONA_EDITOR_KO} from './personaEditorCopy';
import './persona-settings-editor.css';

export function editablePersona(source:PersonaSource,legacy?:{world?:string|null;note?:string|null}|null):AuthoredPersona {
 return {version:1,fields:PERSONA_STEPS.flatMap(step=>step.fields).map(field=>source.authoredPersona?.fields.find(f=>f.fieldId===field.key)??{fieldId:field.key as AuthoredPersona['fields'][number]['fieldId'],chips:[],rawText:!source.authoredPersona?(field.key==='world'?legacy?.world??'':field.key==='extra'?legacy?.note??'':''):'',skipped:!(!source.authoredPersona&&(field.key==='world'?legacy?.world:field.key==='extra'?legacy?.note:false))})};
}
/** Editing only uses the existing source patch; no onboarding lifecycle operation is reachable here. */
export default function PersonaSettingsEditor({source,onClose,onSaved,onReload}:{source:PersonaSource;onClose:()=>void;onSaved:(source:PersonaSource)=>void;onReload:()=>void}){
 const live=useLive(),locale=useZzalLocale(),copy=locale==='en'?PERSONA_EDITOR_EN:locale==='ja'?PERSONA_EDITOR_JA:PERSONA_EDITOR_KO;
 const labels=locale==='en'?{title:'Edit settings',cancel:'Cancel',previous:'Previous',next:'Next',skip:'Clear and next',skipHelp:'Skipping clears this field.',name:'Character name stays the same.',reload:'Load latest settings'}:locale==='ja'?{title:'設定を変更',cancel:'キャンセル',previous:'前へ',next:'次へ',skip:'空欄にして次へ',skipHelp:'スキップすると、この項目を空欄にします。',name:'キャラクターの名前はそのままです。',reload:'最新の設定を読み込む'}:{title:'설정 수정',cancel:'취소',previous:'이전',next:'다음',skip:'비우고 다음',skipHelp:'건너뛰면 이 항목을 비워요.',name:'캐릭터 이름은 그대로예요.',reload:'최신 설정 불러오기'};
 const metrics=interactionMetrics(live,false,'settings');
 useEffect(()=>metrics.shown('settings'),[]);
 const original=useMemo(()=>editablePersona(source,live.pet),[source]);
 const [draft,setDraft]=useState(original),[callMe,setCallMe]=useState(source.callMe??''),[idx,setIdx]=useState(0),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[conflict,setConflict]=useState(false);
 const root=useRef<HTMLDivElement>(null),stage=useRef<HTMLDivElement>(null),pane=useRef<HTMLDivElement>(null),texts=useRef<Record<string,HTMLTextAreaElement|null>>({}),lock=useRef(false),mounted=useRef(true),identity=useRef(live.petId);
 const activePet=useRef(live.petId);activePet.current=live.petId;
 const [size,setSize]=useState({width:280,height:160});
 const src=live.img('base')??'',geometry=useSpriteGeometry(src,.05);
 const step=idx<PERSONA_STEPS.length?personaStepCopy(PERSONA_STEPS[idx],locale):null;
 const answers:Answers=Object.fromEntries(draft.fields.map(f=>[f.fieldId,{chips:[...f.chips],text:f.rawText}]));
 const dirtyFields=JSON.stringify(draft)!==JSON.stringify(original),dirty=dirtyFields||callMe.trim()!==(source.callMe??'');
 const validation=source.callMe&&!callMe.trim()?copy.emptyAddress:draft.fields.some(f=>f.rawText.length>10000)?copy.tooLong:'';
 const invalidChips=draft.fields.some(f=>f.fieldId==='persona'&&f.chips.length>3);
 const update=(key:string,patch:Partial<AuthoredPersona['fields'][number]>)=>{setMessage('');setDraft(d=>({...d,fields:d.fields.map(f=>{if(f.fieldId!==key)return f;const next={...f,...patch,skipped:false};const before=original.fields.find(x=>x.fieldId===key)!;if(patch.skipped===true)next.skipped=true;else if(next.rawText===before.rawText&&JSON.stringify(next.chips)===JSON.stringify(before.chips))next.skipped=before.skipped;else next.skipped=!next.rawText.trim()&&!next.chips.length;return next})}))};
 const close=()=>{if(busy)return;if(!dirty||window.confirm(locale==='en'?'Discard your changes?':locale==='ja'?'変更を破棄しますか？':'수정한 내용을 버리고 나갈까요?'))onClose()};
 useEffect(()=>{mounted.current=true;const previous=document.activeElement as HTMLElement|null;root.current?.querySelector<HTMLButtonElement>('button')?.focus();return()=>{mounted.current=false;previous?.focus({preventScroll:true})}},[]);
 useEffect(()=>{if(live.petId!==identity.current)onClose()},[live.petId,onClose]);
 useLayoutEffect(()=>{const node=stage.current;if(!node)return;const measure=()=>setSize({width:node.clientWidth,height:node.clientHeight});measure();const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect()},[]);
 useEffect(()=>{const view=window.visualViewport;const fit=()=>{if(root.current){root.current.style.height=`${view?.height??window.innerHeight}px`;root.current.style.top=`${view?.offsetTop??0}px`}};fit();view?.addEventListener('resize',fit);window.addEventListener('resize',fit);return()=>{view?.removeEventListener('resize',fit);window.removeEventListener('resize',fit)}},[]);
 useLayoutEffect(()=>{pane.current?.scrollTo({top:0})},[idx]);
 useLayoutEffect(()=>{for(const el of Object.values(texts.current)){if(!el)continue;el.style.height='auto';el.style.height=`${Math.max(88,el.scrollHeight)}px`}},[idx,draft,locale]);
 const save=async()=>{if(lock.current||!dirty||validation||invalidChips||!live.petId)return;metrics.attempt('settings_save');lock.current=true;setBusy(true);setMessage('');setConflict(false);try{const result=await live.updatePersona({...(dirtyFields?{authoredPersona:draft}:{}),...(callMe.trim()&&callMe.trim()!==source.callMe?{callMe:callMe.trim()}:{}),expectedPersonaHash:source.personaSourceHash});metrics.result('settings_save',result.ok?'success':result.status==null?'uncertain':'failure');if(!mounted.current||activePet.current!==identity.current)return;if(!result.ok){setConflict(result.code==='ZZAL_PERSONA_SOURCE_STALE');setMessage(result.code==='ZZAL_PERSONA_SOURCE_STALE'?copy.conflict:result.status==null?copy.uncertain:result.message??copy.failed);return}onSaved({...source,authoredPersona:dirtyFields?draft:source.authoredPersona,callMe:callMe.trim()||source.callMe});}catch{metrics.result('settings_save','uncertain');if(mounted.current&&activePet.current===identity.current)setMessage(copy.uncertain)}finally{lock.current=false;if(mounted.current&&activePet.current===identity.current)setBusy(false)}};
 const scale=Math.min(Math.max(1,size.height-8)/Math.max(1,geometry.height*(1-geometry.top-geometry.bottom)),Math.max(1,size.width-24)/Math.max(1,geometry.width*(geometry.right-geometry.left)));
 const actorStyle:CSSProperties={position:'absolute',width:geometry.width*scale,height:geometry.height*scale,maxWidth:'none',maxHeight:'none',left:size.width/2-geometry.width*scale*(geometry.left+geometry.right)/2,top:size.height-geometry.height*scale*(1-geometry.bottom),visibility:geometry.loaded?'visible':'hidden'};
 return createPortal(<div ref={root} className="pse-root" role="dialog" aria-modal="true" aria-labelledby="pse-title" data-part="persona-settings-editor" data-question={step?.key??'address'} aria-busy={busy} onKeyDown={event=>{event.stopPropagation();if(event.key==='Escape'){event.preventDefault();close()}if(event.key==='Tab'){const nodes=[...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),textarea:not(:disabled),input:not(:disabled)')];if(event.shiftKey&&document.activeElement===nodes[0]){event.preventDefault();nodes.at(-1)?.focus()}else if(!event.shiftKey&&document.activeElement===nodes.at(-1)){event.preventDefault();nodes[0]?.focus()}}}}>
  <header><h2 id="pse-title">{labels.title}</h2><button type="button" disabled={busy} onClick={close}>{labels.cancel}</button></header>
  <div className="pse-actor-stage" ref={stage}>{src?<img data-part="persona-settings-actor" src={src} alt={live.pet?.name??''} style={actorStyle}/>:<span>{live.pet?.name}</span>}</div>
  <div ref={pane} className="pse-question" key={step?.key??'address'}><h3>{step?.q??copy.address}</h3>{step?.sub&&<p>{step.sub}</p>}
   {step?<PersonaQuestionFields step={step} answers={answers} busyFx={busy} editing textRefs={texts} setChip={(field:PersonaField,chip:string)=>update(field.key,{chips:pickChips(field,answerOf(answers,field.key).chips,chip)})} setText={(key,text)=>update(key,{rawText:text})}/>:<><label>{copy.address}<input autoComplete="off" data-part="persona-settings-address" aria-label={copy.address} maxLength={20} disabled={busy} value={callMe} onChange={event=>{setCallMe(event.target.value);setMessage('')}} placeholder={copy.unknown}/></label><p>{labels.name} {live.pet?.name}</p>{!source.authoredPersona&&live.pet?.note&&<blockquote>{live.pet.note}</blockquote>}</>}
  </div>
  <footer><p role={message||validation?'alert':'status'} data-part="persona-settings-status">{message||(!step?validation:'')}</p>{conflict&&<button type="button" onClick={()=>{if(window.confirm(copy.replace))onReload()}}>{labels.reload}</button>}<div className="pse-nav"><button type="button" disabled={busy||idx===0} onClick={()=>setIdx(n=>n-1)}>{labels.previous}</button><button type="button" className="pse-primary" data-action="persona-settings-next" disabled={busy||(!step&&(!dirty||!!validation||invalidChips))} onClick={()=>step?setIdx(n=>n+1):void save()}>{busy?copy.saving:step?labels.next:copy.save}</button>{step&&<button type="button" disabled={busy} onClick={()=>{for(const f of step.fields)update(f.key,{chips:[],rawText:'',skipped:true});setIdx(n=>n+1)}}>{labels.skip}</button>}</div>{step&&<p className="pse-skip-help">{labels.skipHelp}</p>}</footer>
 </div>,document.body);
}
