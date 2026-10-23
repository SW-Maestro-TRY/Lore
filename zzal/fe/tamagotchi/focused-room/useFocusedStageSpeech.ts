'use client';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {isStageSpeech,type TutorialScene,type StageSpeechView} from '../../lib/tutorialStageSpeech';
import type {Live} from '../yeoul/useHatch';
import {StageOpeningWindow} from './stageOpeningWindow';
import {savedStageFor,stagePresentation,stageRequestFor,StageReadLedger} from './focusedStageSpeech';
/** Stored dialogue is presentation, never a completion receipt or a fabricated USER turn. */
export function useFocusedStageSpeech(live:Live,locale:string,displayStep:string|undefined,serverStep:string|undefined,enabled:boolean,fallback:string|null,canPresent:boolean,scene?:TutorialScene){
 const latest=useRef(live);latest.current=live;
 const latestScene=useRef(scene);latestScene.current=scene;
 const saved=(rows:readonly StageSpeechView[]|undefined,step:string,expected:Parameters<typeof savedStageFor>[2])=>scene?(rows??[]).filter(isStageSpeech).filter(row=>row.step===scene&&row.phase==='ENTRY').sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt)||b.id-a.id)[0]:savedStageFor(rows,step,expected);
 const [,redraw]=useState(0);const opening=useRef<StageOpeningWindow|null>(null);opening.current??=new StageOpeningWindow(()=>redraw(v=>v+1));
 const ledger=useRef(new StageReadLedger()),attempts=useRef(new Set<string>()),generation=useRef(0);
 const previous=useRef<{pet:number|null;step:string|undefined}>({pet:live.petId,step:undefined});
 const request=useRef(stageRequestFor(serverStep,undefined));
 if(previous.current.pet!==live.petId){previous.current={pet:live.petId,step:undefined};ledger.current.clear();attempts.current.clear();opening.current.cancelAll();generation.current++;}
 if(previous.current.step!==serverStep){request.current=stageRequestFor(serverStep,previous.current.step);previous.current={pet:live.petId,step:serverStep};}
 const desired=scene?{step:scene,phase:'ENTRY' as const}:request.current;
 const [revision,setRevision]=useState(0),[visible,setVisible]=useState(true);
 useEffect(()=>{const hide=()=>{generation.current++;if(document.hidden)opening.current?.cancelAll();setVisible(!document.hidden)};const edited=(event:Event)=>{const id=(event as CustomEvent<{petId?:number}>).detail?.petId;if(id==null||id===latest.current.petId){generation.current++;setRevision(v=>v+1)}};document.addEventListener('visibilitychange',hide);window.addEventListener('zzal-persona-updated',edited);return()=>{document.removeEventListener('visibilitychange',hide);window.removeEventListener('zzal-persona-updated',edited);opening.current?.cancelAll()}},[]);
 useEffect(()=>()=>opening.current?.cancel(`${live.petId}:${scene??displayStep}`),[live.petId,displayStep,scene]);
 const awake=!live.pet?.clock?.sleeping&&!live.pet?.trip;
 // Only records owned by this exact visible step/phase can provide its line.
 const stored=displayStep===serverStep?saved(live.chat?.stageSpeeches,displayStep??'',desired):undefined;
 const proposed=scene&&stored?.source==='FALLBACK'&&stored.line?.trim()?{line:stored.line,source:'SERVER_FALLBACK' as const}:stagePresentation(stored,fallback);
 const readKey=`${live.petId}:${scene??displayStep}`;
 const presentation=ledger.current.peek(readKey,proposed);
 const supported=live.chat==null||Array.isArray(live.chat.stageSpeeches);
 const waiting=!stored||stored.status==='PENDING'||stored.status==='READY'&&!stored.turnRef;
 const preparing=enabled&&awake&&visible&&supported&&!ledger.current.has(readKey)&&!opening.current.isExpired(readKey)&&waiting;
 useLayoutEffect(()=>{
  if(!enabled||!awake||!visible||!canPresent||displayStep!==serverStep)return;
  if(preparing)opening.current!.begin(readKey);
  else{opening.current!.cancel(readKey);
   // The timeout only shows temporary guidance. A late saved line for this exact scene must still be seen.
   if(presentation.source!=='SERVICE_FALLBACK'||!waiting)ledger.current.commit(readKey,presentation);
  }
 },[enabled,awake,visible,canPresent,displayStep,serverStep,readKey,preparing,waiting,presentation.line,presentation.source]);
 useEffect(()=>{if(!awake||!enabled)opening.current?.cancelAll()},[awake,enabled]);
 useEffect(()=>{
  if(!enabled||!awake||!visible||document.hidden||!Array.isArray(live.chat?.stageSpeeches)||!live.prepareStageSpeech)return;
  if(!desired)return;
  const stored=saved(live.chat.stageSpeeches,serverStep??'',desired);
  const attempt=`${live.petId}:${desired.step}:${desired.phase}`;
  const stamp=++generation.current,id=live.petId,current=serverStep;
  const controller=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined,reads=0;
  const valid=()=>!controller.signal.aborted&&generation.current===stamp&&latest.current.petId===id&&latestScene.current===scene&&latest.current.pet?.firstIntro?.key===current&&!latest.current.pet?.clock?.sleeping&&!latest.current.pet?.trip&&!document.hidden;
  const activateCached=(row:NonNullable<typeof stored>)=>{
   const activation=`${id}:${row.step}:${row.phase}:activate`;
   if(!valid()||row.status!=='READY'||row.turnRef||attempts.current.has(activation))return;
   attempts.current.add(activation);
   // READY without a turn is already generated; this only activates its existing cached line.
   void latest.current.prepareStageSpeech?.(row.step,row.phase,controller.signal).catch(()=>{});
  };
  const poll=()=>{if(!valid()||reads++>=24||!latest.current.readStageSpeeches)return;timer=setTimeout(async()=>{if(!valid())return;try{const rows=await latest.current.readStageSpeeches?.(controller.signal);if(!valid())return;const row=saved(rows??undefined,current??'',desired);if(!row||row.status==='PENDING')poll();else activateCached(row)}catch{/* Stored-only reads never retry a paid prepare. */}},500)};
  if(stored?.status==='READY'&&!stored.turnRef)activateCached(stored);
  else if(stored){if(stored.status==='PENDING')poll();}
  else if(!attempts.current.has(attempt)){
   attempts.current.add(attempt);
   void live.prepareStageSpeech(desired.step,desired.phase,controller.signal).then(row=>{if(!valid()||!row)return;if(row.status==='PENDING')poll();else activateCached(row)}).catch(()=>{if(valid()){opening.current!.cancel(readKey);redraw(v=>v+1)}/* No automatic paid retry. */});
  }else poll(); // Recover an interrupted prepare through stored-only reads; never submit it again.
  return()=>{controller.abort();if(timer)clearTimeout(timer)};
 // Metadata updates must not abort a prepare merely because its PENDING row was committed.
 },[live.petId,serverStep,enabled,awake,visible,locale,revision,Array.isArray(live.chat?.stageSpeeches),scene]);
 return {line:enabled&&awake?(preparing?'…':presentation.line):null,source:preparing?'PREPARING':presentation.source,status:preparing?'PREPARING':stored?.status??'UNPREPARED'};
}
