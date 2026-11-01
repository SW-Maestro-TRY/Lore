'use client';
import {useEffect,useLayoutEffect,useRef,useState,type CSSProperties,type KeyboardEvent} from 'react';
import {getAuthoredPersona,type PersonaSource} from '../../lib/pet';
import {useSpriteGeometry} from '../../lib/spriteGeometry';
import {useZzalLocale} from '../../lib/locale';
import {useLive} from './useHatch';
import {personaChipLabel} from './personaChipCopy';
import {PERSONA_LABEL} from './constants';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import PersonaSettingsEditor,{editablePersona} from './PersonaSettingsEditor';
import {CALL_ME_MAX,CROP_FALLBACK_Y,CROP_HEAD_ROOM,CROP_VISIBLE,NAME_MAX,pickRepresentativeChips,validateCallMe,validateName} from './characterCardRules';
import {cardCopy,type CardCopy} from './characterCardCopy';
import '../focused-room/character-card.css';

type Field='name'|'callMe';
const FIELD_ORDER=['world','job','persona','likes','dislikes','extra'] as const;

function Pen(){return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4Z"/><path d="m13.5 6.5 4 4"/></svg>}
function Alert(){return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/></svg>}

/**
 * 그림 칸(1단계 자르기). `live.img('base')`만 쓴다 — 없으면 실루엣.
 * ★ `spriteUrl()`은 그림이 없을 때 여울 그림으로 대신하므로 쓰지 않는다(남의 캐릭터를 내 카드에 보이게 됨).
 */
function CardArt({src,label,copy}:{src:string|null;label:string;copy:CardCopy}){
 const box=useRef<HTMLDivElement>(null),[size,setSize]=useState({width:0,height:0});
 const geometry=useSpriteGeometry(src??'',0);
 useLayoutEffect(()=>{const node=box.current;if(!node)return;const measure=()=>setSize({width:node.clientWidth,height:node.clientHeight});measure();const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect()},[]);
 const missing=!src||geometry.failed;
 let style:CSSProperties|null=null;
 if(!missing&&geometry.measured&&size.height>0){
  // 캐릭터 키(투명 아닌 영역)의 위쪽 CROP_VISIBLE 만큼이 칸 높이(머리 위 여백 뺀)를 채운다.
  const body=Math.max(1,geometry.height*(1-geometry.top-geometry.bottom));
  const scale=size.height*(1-CROP_HEAD_ROOM)/(body*CROP_VISIBLE);
  const centerX=geometry.width*(geometry.left+geometry.right)/2;
  style={position:'absolute',maxWidth:'none',maxHeight:'none',width:geometry.width*scale,height:geometry.height*scale,left:size.width/2-centerX*scale,top:size.height*CROP_HEAD_ROOM-geometry.top*geometry.height*scale};
 }else if(!missing){
  // 측정 실패(픽셀 읽기 막힘 등): 고정 자르기로 버틴다.
  style={position:'absolute',inset:0,width:'100%',height:'100%',objectFit:'cover',objectPosition:`50% ${CROP_FALLBACK_Y}`,visibility:geometry.loaded?'visible':'hidden'};
 }
 return <div ref={box} className="zcc-art" role="img" aria-label={missing?copy.silhouette:label} data-card-art={missing?'silhouette':geometry.measured?'cropped':'fallback'}>
  {missing?<svg className="zcc-silhouette" data-card-silhouette viewBox="0 0 120 120" aria-hidden="true"><circle cx="60" cy="44" r="22"/><path d="M22 116c2-28 18-44 38-44s36 16 38 44Z"/></svg>:<img src={src!} alt="" style={style!} draggable={false}/>}
 </div>;
}

/**
 * 캐릭터 카드(명세 2026-11-01). 앞면: 번호·함께한 날·그림·이름·호칭·대표 칩. 뒷면: 여섯 칸 표 + "상세 설정 고치기".
 * 이름·호칭은 연필로 그 자리에서 고친다(한 번에 한 줄). 저장 뒤 문구 없이 바뀐 값으로만 보여 준다.
 */
export default function CharacterCard({sample=false,allowConfirm=false,onSaved,onClose}:{sample?:boolean;allowConfirm?:boolean;onSaved?:(source:PersonaSource)=>void|Promise<void>;onClose?:()=>void}){
 const locale=useZzalLocale(),copy=cardCopy(locale);
 const live=useLive(),petId=live.petId,pet=live.pet;
 const metrics=interactionMetrics(live,sample,'character_card');
 const [source,setSource]=useState<PersonaSource|null>(null),[failed,setFailed]=useState(false),[attempt,setAttempt]=useState(0),[settings,setSettings]=useState(false);
 const [editing,setEditing]=useState<Field|null>(null),[draft,setDraft]=useState(''),[serverError,setServerError]=useState(''),[busy,setBusy]=useState(false),[travelNote,setTravelNote]=useState<Field|null>(null);
 const era=useRef(0),lock=useRef(false),mounted=useRef(true),petRef=useRef(petId);petRef.current=petId;
 const input=useRef<HTMLInputElement>(null),pens=useRef<Record<Field,HTMLButtonElement|null>>({name:null,callMe:null}),focusBack=useRef<Field|null>(null);
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
 useEffect(()=>{const token=++era.current;setSource(null);setFailed(false);if(!petId||sample)return;void getAuthoredPersona(petId).then(value=>{if(token===era.current)setSource(value)}).catch(()=>{if(token===era.current)setFailed(true)});return()=>{era.current++}},[petId,attempt,sample]);
 useEffect(()=>{setEditing(null);setServerError('');setTravelNote(null);setSettings(false)},[petId]);
 useEffect(()=>{if(!editing)return;const node=input.current;if(!node)return;node.focus({preventScroll:true});const frame=requestAnimationFrame(()=>node.scrollIntoView?.({block:'center'}));return()=>cancelAnimationFrame(frame)},[editing]);
 useEffect(()=>{if(editing||!focusBack.current)return;const field=focusBack.current;focusBack.current=null;pens.current[field]?.focus({preventScroll:true})},[editing]);

 const name=pet?.name??'';
 const deferred=!source?.callMe||source.addressStatus==='DEFERRED';
 const callMe=deferred?'':source!.callMe!;
 const legacy=!!source&&!source.authoredPersona;
 const chips=pickRepresentativeChips(source).map(value=>personaChipLabel(legacy?PERSONA_LABEL[value]??value:value,locale));
 const initial=(field:Field)=>field==='name'?name:callMe;
 const validation=editing==='name'?validateName(draft,name):editing==='callMe'?validateCallMe(draft,callMe):null;
 const validationText=editing&&validation&&validation!=='unchanged'?copy.errors[editing][validation]??'':'';
 const errorText=serverError||validationText;
 const dirty=!!editing&&draft!==initial(editing);
 const editable=!sample&&!!petId;
 const traveling=!!pet?.trip;

 const open=(field:Field)=>{
  if(busy||editing===field)return;
  if(editing&&dirty)return;
  if(traveling){setEditing(null);setTravelNote(field);return}
  if(field==='callMe'&&!source)return;
  setTravelNote(null);setServerError('');setDraft(initial(field));setEditing(field);
 };
 const close=(field:Field)=>{focusBack.current=field;setEditing(null);setServerError('')};
 const cancel=()=>{if(busy||!editing)return;close(editing)};
 const save=async()=>{
  const field=editing;if(!field||lock.current||busy||validation||!petId)return;
  if(field==='callMe'&&!source)return;
  const value=draft.trim(),identity=petId,action=field==='name'?'card_rename':'card_call_me';
  lock.current=true;setBusy(true);setServerError('');metrics.attempt(action);
  try{
   const result=field==='name'?await live.rename(value):await live.updatePersona({callMe:value,expectedPersonaHash:source!.personaSourceHash});
   // Name values never enter telemetry; only fixed codes.
   metrics.result(action,result.ok?'success':result.status==null&&!result.code?'uncertain':'failure',result.ok?undefined:{code:result.code,reason:result.status==null?'network':`http_${result.status}`});
   if(!mounted.current||petRef.current!==identity)return;
   if(result.ok){
    if(field==='callMe'){
     // 호칭은 설정 해시에 들어간다. 새 해시를 받아 두어야 재방문 "이 설정으로 시작하기"가 맞는 해시로 끝난다.
     try{const fresh=await getAuthoredPersona(identity);if(mounted.current&&petRef.current===identity)setSource(fresh)}catch{if(mounted.current)setAttempt(n=>n+1)}
    }
    if(mounted.current)close(field);return;
   }
   if(result.code==='ZZAL_TRAVELING'){setServerError(copy.traveling);return}
   if(result.code==='ZZAL_PERSONA_SOURCE_STALE'){
    try{const fresh=await getAuthoredPersona(identity);if(mounted.current&&petRef.current===identity)setSource(fresh)}catch{/* keep the draft; the message still asks to save again */}
    if(mounted.current)setServerError(copy.stale);return;
   }
   // message null without status = a late response for a replaced pet/session; nothing to show.
   if(result.status==null&&!result.code){setServerError(result.message===null?'':copy.uncertain);return}
   setServerError(result.message||copy.failed);
  }catch{metrics.result(action,'uncertain',{reason:'network'});if(mounted.current)setServerError(copy.uncertain)}
  finally{lock.current=false;if(mounted.current)setBusy(false)}
 };
 const keys=(event:KeyboardEvent<HTMLInputElement>)=>{
  if(event.nativeEvent.isComposing)return;
  if(event.key==='Enter'){event.preventDefault();void save()}
  else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();cancel()}
 };

 const pen=(field:Field)=>editable&&<button type="button" ref={node=>{pens.current[field]=node}} className="zcc-pen" data-card-edit={field} aria-label={field==='name'?copy.editName:copy.editCallMe} disabled={busy||(!!editing&&editing!==field&&dirty)||(field==='callMe'&&!source)} onClick={()=>open(field)}><i><Pen/></i></button>;
 const editor=(field:Field)=>{
  const id=`zcc-${field}`,max=field==='name'?NAME_MAX:CALL_ME_MAX,bad=!!errorText;
  return <div className="zcc-edit" data-card-editor={field} data-bad={bad?'true':undefined}>
   <label className="zcc-label" htmlFor={id}>{field==='name'?copy.nameLabel:copy.callMeLabel}</label>
   <div className="zcc-field"><input ref={input} id={id} data-card-input={field} autoComplete="off" enterKeyHint="done" maxLength={max} value={draft} disabled={busy} aria-invalid={bad} aria-describedby={`${id}-note`} onChange={event=>{setDraft(event.target.value);setServerError('')}} onKeyDown={keys}/><span className="zcc-count" data-card-count>{draft.length}/{max}</span></div>
   {bad?<p className="zcc-p zcc-err" id={`${id}-note`} role="alert" data-card-error><Alert/><span>{errorText}</span></p>:<p className="zcc-p zcc-hint" id={`${id}-note`}>{field==='name'?copy.nameHint:copy.callMeHint}</p>}
   <div className="zcc-actions"><button type="button" className="zcc-btn" data-card-cancel disabled={busy} onClick={cancel}>{copy.cancel}</button><button type="button" className="zcc-btn zcc-save" data-card-save disabled={busy||!!validation} onClick={()=>void save()}>{busy?copy.saving:copy.save}</button></div>
  </div>;
 };
 const rows=source?editablePersona(source,pet).fields:[];

 return <section className="zcc" data-card data-sample={sample?'true':undefined} lang={locale}>
  <div className="zcc-card"><div className="zcc-inner">
   <div className="zcc-top"><span>{copy.no}</span>{pet?.daysTogether!=null&&<span data-card-days>{copy.days(pet.daysTogether)}</span>}</div>
   <CardArt src={live.img('base')} label={name||copy.art} copy={copy}/>
   <div className="zcc-plate">{editing==='name'?editor('name'):<><strong className="zcc-name" data-card-name>{name}</strong>{pen('name')}</>}</div>
   {travelNote==='name'&&<p className="zcc-p zcc-note" role="status" data-card-travel>{copy.traveling}</p>}
   {!sample&&<div className="zcc-callrow">{editing==='callMe'?editor('callMe'):<><span className="zcc-callme"><small>{copy.callMe}</small><span data-card-callme>{source?(deferred?copy.undecided:callMe):failed?'':copy.loading}</span></span>{pen('callMe')}</>}</div>}
   {travelNote==='callMe'&&<p className="zcc-p zcc-note" role="status" data-card-travel>{copy.traveling}</p>}
   {chips.length>0&&<ul className="zcc-tags" data-card-chips>{chips.map(chip=><li key={chip} className="zcc-chip">{chip}</li>)}</ul>}
  </div></div>
  {sample?<p className="zcc-p zcc-sample">{copy.sample}</p>:<>
   <p className="zcc-p zcc-backhead"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9h13l-3-3M20 15H7l3 3"/></svg>{copy.back}<span>{copy.backSub}</span></p>
   {!source?<div className="zcc-back zcc-loading" role="status">{failed?copy.loadFailed:copy.loading}{failed&&<button type="button" className="zcc-btn" onClick={()=>setAttempt(n=>n+1)}>{copy.retry}</button>}</div>:<>
    {legacy&&<p className="zcc-p zcc-legacy">{copy.legacy}</p>}
    <dl className="zcc-back" data-card-back>{FIELD_ORDER.map(id=>{const field=rows.find(f=>f.fieldId===id);const text=field?[...field.chips.map(chip=>personaChipLabel(chip,locale)),field.rawText].filter(Boolean).join(' · '):'';return <div className="zcc-row" key={id}><dt>{copy.labels[id]}</dt><dd data-empty={text?undefined:'true'}>{text||copy.empty}</dd></div>})}</dl>
    <button type="button" className="zcc-btn zcc-primary" data-action="persona-edit" disabled={busy||!!editing&&dirty} onClick={()=>{setEditing(null);setSettings(true)}}>{copy.editDetail}</button>
   </>}
  </>}
  {settings&&source&&<PersonaSettingsEditor key={petId} source={source} allowConfirm={allowConfirm} onClose={()=>{setSettings(false);onClose?.()}} onReload={()=>{setSettings(false);setAttempt(n=>n+1)}} onSaved={async value=>{await onSaved?.(value);setSettings(false);setSource(value);setAttempt(n=>n+1)}}/>}
 </section>;
}
