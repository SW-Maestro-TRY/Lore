'use client';
import {useEffect,useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import {useAuth} from '@common/auth/useAuth';
import type {PetDetail} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import {RomiChatExample} from '../focused-room/RomiChatExample';
import {ENDING_STORY} from '../focused-room/useTutorialEnding';
import {endingFallbackLine} from '../focused-room/focusedCopy';
import {returningCopy,returningGuideAutomatic,returningGuideKey} from './returningGuideState';
import {AnnouncementDialog,useAnnouncementsOpen} from './AnnouncementsGate';
import {RETURNING_UPDATE} from './announcements';
import {API_BASE,request} from '@common/api/client';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import './returning-guide.css';

type GuideStatus={version:string;eligible:boolean;step:number;completed:boolean;boundPetId:number|null};
type Position={key:string;step:number;open:boolean;busy:boolean;failed:boolean;loaded:boolean};
/** Server-owned account/version progress. Never mutates the original tutorial or attendance. */
export function useReturningGuide(pet:PetDetail|null|undefined,sample:boolean){
 const auth=useAuth(),key=!sample&&auth.user&&pet?returningGuideKey(auth.user.userId,pet.petId):'';
 const automatic=!!key&&returningGuideAutomatic(pet),identity=useRef(key);identity.current=key;
 const [position,setPosition]=useState<Position>({key:'',step:0,open:false,busy:false,failed:false,loaded:false});
 const metrics=useRef(interactionMetrics({pet:pet??null},sample,'returning_guide'));
 metrics.current=interactionMetrics({pet:pet??null},sample,'returning_guide');
 const path=`${API_BASE}/api/zzal/v1/me/pets/${pet?.petId}/returning-guide`;
 const valid=(value:GuideStatus)=>{if(!value||typeof value.eligible!=='boolean'||typeof value.completed!=='boolean'||!Number.isInteger(value.step)||value.step<0||value.step>5)throw new Error('Invalid returning guide response');return value};
 const apply=(value:GuideStatus)=>setPosition({key,step:value.step,open:value.eligible&&!value.completed,busy:false,failed:false,loaded:true});
 const reload=async()=>{if(!automatic||auth.isLoading)return;setPosition(previous=>({key,step:previous.key===key?previous.step:0,open:true,busy:true,failed:false,loaded:previous.key===key&&previous.loaded}));try{const value=valid(await request<GuideStatus>(path));if(identity.current===key)apply(value)}catch{if(identity.current===key)setPosition(previous=>({...previous,busy:false,failed:true}))}};
 useLayoutEffect(()=>{if(!automatic){setPosition({key,step:0,open:false,busy:false,failed:false,loaded:false});return}void reload()},[key,automatic,auth.isLoading]);
 const current=position.key===key?position:{key,step:0,open:automatic,busy:true,failed:false,loaded:false};
 useEffect(()=>{if(current.open&&!current.busy&&!current.failed)metrics.current.shown(`returning_step_${current.step}`)},[current.key,current.open,current.step,current.busy,current.failed]);
 const lock=useRef(false);
 const update=async(step:number,complete=false,expectedPersonaHash?:string)=>{
  if(lock.current||!automatic)return false;lock.current=true;setPosition(previous=>({...previous,busy:true,failed:false}));
  try{const value=valid(await request<GuideStatus>(path,{method:'POST',body:{step,complete,expectedPersonaHash}}));if(identity.current!==key)return false;apply(value);return true}
  catch(error){if(identity.current===key)setPosition(previous=>({...previous,busy:false,failed:true}));if(complete)throw error;return false}
  finally{lock.current=false}
 };
 return {...current,eligible:automatic,pending:current.open,retry:reload,next:()=>{metrics.current.attempt('returning_next');void update(Math.min(5,current.step+1))},back:()=>{metrics.current.attempt('returning_previous');setPosition(previous=>({...previous,step:Math.max(0,previous.step-1)}))},complete:(hash:string)=>update(5,true,hash)};
}

/** Native modal keeps focus and prevents accidental care actions beneath the guide. */
export function ReturningDialog({title,onClose,children,kind='guide'}:{title:string;onClose:()=>void;children:ReactNode;kind?:string}){
 const ref=useRef<HTMLDialogElement>(null);
 useEffect(()=>{const previous=document.activeElement,node=ref.current,overflow=document.body.style.overflow;node?.showModal();document.body.style.overflow='hidden';return()=>{node?.close();document.body.style.overflow=overflow;if(previous instanceof HTMLElement&&previous.isConnected)previous.focus({preventScroll:true})}},[]);
 return <dialog ref={ref} className="zzal-returning" data-returning-dialog={kind} aria-label={title} onCancel={e=>{e.preventDefault();onClose()}} onKeyDown={e=>e.stopPropagation()} onClick={e=>e.stopPropagation()}>{children}</dialog>;
}

export default function ReturningGuide({guide,locale,locked,onEdit}:{guide:ReturnType<typeof useReturningGuide>;locale:ZzalLocale;locked:boolean;onEdit:()=>void}){
 const announcementOpen=useAnnouncementsOpen();
 const w=returningCopy[locale],[ready,setReady]=useState(false),example=guide.step===2||guide.step===3;
 const title=guide.step===1?w.intro:guide.step===4?w.memory:guide.step===5?w.settings:w.intro;
 if(announcementOpen)return null;
 if(!guide.loaded)return <ReturningDialog title={w.loading} onClose={()=>{}} kind="loading"><div className="returning-content" data-returning-loading><p role={guide.failed?'alert':'status'}>{guide.failed?w.failed:w.loading}</p>{guide.failed&&<button type="button" data-returning-retry onClick={()=>void guide.retry()}>{w.retry}</button>}</div></ReturningDialog>;
 if(guide.step===0&&!guide.failed)return <AnnouncementDialog announcements={[RETURNING_UPDATE]} selected={RETURNING_UPDATE} onSelect={()=>{}} onClose={()=>{if(!guide.busy)guide.next()}} startup scope="returning-guide" returnTo={null} hideControl={false} actionLabel={w.showExamples} actionDisabled={guide.busy}/>;
 return <ReturningDialog title={w.title} onClose={()=>{}}>
  <header><h2>{title}</h2></header>
  <div className="returning-content" data-returning-step={guide.step}>
   {guide.step===1&&<p>{endingFallbackLine('CHAT_INTRO',locale,false)}</p>}
   {example&&<RomiChatExample phase={guide.step===2?ENDING_STORY.firstExample:ENDING_STORY.secondExample} locale={locale} onReady={setReady}/>}
   {guide.step===4&&<><p>{endingFallbackLine('CHAT_MEMORY',locale,false)}</p><p>{w.reward}</p></>}
   {guide.step===5&&<><p>{w.settingsBody}</p>{locked&&<p role="status">{w.locked}</p>}</>}
   {guide.busy&&<p role="status">{w.loading}</p>}
   {guide.failed&&<p role="alert">{w.failed}</p>}
  </div>
  <footer>
   {guide.step>1&&!guide.failed&&<button type="button" data-returning-back onClick={guide.back}>{w.back}</button>}
   {guide.failed?<button type="button" data-returning-retry onClick={()=>void guide.retry()}>{w.retry}</button>:guide.step<5?<button type="button" className="returning-primary" data-returning-next disabled={guide.busy||example&&!ready} onClick={guide.next}>{w.next}</button>:<button type="button" className="returning-primary" data-returning-edit disabled={guide.busy} onClick={onEdit}>{w.edit}</button>}
  </footer>
 </ReturningDialog>;
}
