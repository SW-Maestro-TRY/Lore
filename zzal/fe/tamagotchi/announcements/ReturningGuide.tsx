'use client';
import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {useAuth} from '@common/auth/useAuth';
import type {PetDetail} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import {RomiChatExample} from '../focused-room/RomiChatExample';
import {ENDING_STORY} from '../focused-room/useTutorialEnding';
import {endingFallbackLine} from '../focused-room/focusedCopy';
import {returningCopy,returningGuideEligible,returningGuideAutomatic,returningGuideKey} from './returningGuideState';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import './returning-guide.css';

type Position={key:string;step:number;open:boolean};
/** Independent reading state only: never calls tutorial, attendance or chat mutation APIs. */
export function useReturningGuide(pet:PetDetail|null|undefined,sample:boolean){
 const auth=useAuth(),key=!sample&&auth.user&&pet?returningGuideKey(auth.user.userId,pet.petId):'';
 const [position,setPosition]=useState<Position>({key:'',step:0,open:false});
 const [storageFailed,setStorageFailed]=useState(false),checked=useRef(new Map<string,Position>());
 const metrics=useRef(interactionMetrics({pet:pet??null},sample,'returning_guide'));
 metrics.current=interactionMetrics({pet:pet??null},sample,'returning_guide');
 useLayoutEffect(()=>{
  if(!key||!pet||auth.isLoading)return;
  const prior=checked.current.get(key);if(prior){setPosition(prior);return;}
  let saved:unknown=null;try{saved=JSON.parse(localStorage.getItem(key)??'null')}catch{setStorageFailed(true)}
  const p={key,step:typeof saved==='number'&&saved>=0&&saved<6?saved:0,open:returningGuideAutomatic(pet)&&saved!=='done'&&saved!=='new'};
  // A real incomplete response is not a returning user. Loading/unknown never writes this marker.
  if(pet.phase==='ALIVE'&&pet.firstIntro&&pet.firstIntro.key!=='COMPLETE')try{localStorage.setItem(key,JSON.stringify('new'))}catch{setStorageFailed(true)}
  checked.current.set(key,p);setPosition(p);
 },[key,auth.isLoading,pet?.phase,pet?.firstIntro?.key,pet?.hatchedAt]);
 const current=position.key===key?position:{key,step:0,open:false};
 useEffect(()=>{if(current.open)metrics.current.shown(`returning_step_${current.step}`)},[current.key,current.open,current.step]);
 const update=(step:number,open:boolean)=>{const next={key,step,open};checked.current.set(key,next);setPosition(next);try{if(key)localStorage.setItem(key,JSON.stringify(open?step:'done'))}catch{setStorageFailed(true)}};
 return {...current,eligible:!!key&&returningGuideEligible(pet),storageFailed,next:()=>{metrics.current.attempt('returning_next');update(Math.min(5,current.step+1),true)},back:()=>{metrics.current.attempt('returning_previous');update(Math.max(0,current.step-1),true)},close:()=>update(current.step,false),replay:()=>{metrics.current.attempt('returning_replay');update(0,true)}};
}

/** Native modal keeps focus and prevents accidental care actions beneath the guide. */
export function ReturningDialog({title,onClose,children,kind='guide'}:{title:string;onClose:()=>void;children:ReactNode;kind?:string}){
 const ref=useRef<HTMLDialogElement>(null);
 useEffect(()=>{const previous=document.activeElement,node=ref.current,overflow=document.body.style.overflow;node?.showModal();document.body.style.overflow='hidden';return()=>{node?.close();document.body.style.overflow=overflow;if(previous instanceof HTMLElement&&previous.isConnected)previous.focus({preventScroll:true})}},[]);
 return <dialog ref={ref} className="zzal-returning" data-returning-dialog={kind} aria-label={title} onCancel={e=>{e.preventDefault();onClose()}} onKeyDown={e=>e.stopPropagation()} onClick={e=>e.stopPropagation()}>{children}</dialog>;
}

export default function ReturningGuide({guide,locale,locked,onEdit,onChat}:{guide:ReturnType<typeof useReturningGuide>;locale:ZzalLocale;locked:boolean;onEdit:()=>void;onChat:()=>void}){
 const w=returningCopy[locale],[ready,setReady]=useState(false),example=guide.step===2||guide.step===3;
 const title=guide.step===0?w.title:guide.step===1?w.intro:guide.step===4?w.memory:guide.step===5?w.settings:w.intro;
 return <ReturningDialog title={w.title} onClose={guide.close}>
  <header><h2>{title}</h2><button type="button" data-returning-close onClick={guide.close}>{w.close}</button></header>
  <div className="returning-content" data-returning-step={guide.step}>
   {guide.step===0&&<p>{w.update}</p>}
   {guide.step===1&&<p>{endingFallbackLine('CHAT_INTRO',locale,false)}</p>}
   {example&&<RomiChatExample phase={guide.step===2?ENDING_STORY.firstExample:ENDING_STORY.secondExample} locale={locale} onReady={setReady}/>}
   {guide.step===4&&<><p>{endingFallbackLine('CHAT_MEMORY',locale,false)}</p><p>{w.reward}</p></>}
   {guide.step===5&&<><p>{w.settingsBody}</p>{locked&&<p role="status">{w.locked}</p>}</>}
   {guide.storageFailed&&<p role="status">{w.storage}</p>}
  </div>
  <footer>
   {guide.step>0&&<button type="button" data-returning-back onClick={guide.back}>{w.back}</button>}
   {guide.step<5?<button type="button" className="returning-primary" data-returning-next disabled={example&&!ready} onClick={guide.next}>{w.next}</button>:<button type="button" className="returning-primary" data-returning-edit onClick={onEdit}>{w.edit}</button>}
   <button type="button" data-returning-exit onClick={guide.step===5?onChat:guide.close}>{guide.step===5?(locked?w.room:w.chat):w.skip}</button>
  </footer>
 </ReturningDialog>;
}
