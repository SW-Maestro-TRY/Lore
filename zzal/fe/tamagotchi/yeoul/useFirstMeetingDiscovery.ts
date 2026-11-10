 'use client';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {getFirstMeeting,prepareFirstMeetingDiscovery,type FirstMeetingStatus} from '../../lib/firstMeeting';
import {StageOpeningWindow} from '../focused-room/stageOpeningWindow';
/** Presentation is committed once. Stored GET is authority; a preparation response never moves the gate. */
export function useFirstMeetingDiscovery(petId:number,status:FirstMeetingStatus|null,locale:string){
 const key=`${petId}:${locale}:discovery`,latest=useRef({petId,status,locale});latest.current={petId,status,locale};
 const [,redraw]=useState(0),window=useRef<StageOpeningWindow|null>(null);window.current??=new StageOpeningWindow(()=>redraw(v=>v+1));
 const read=useRef<{key:string;line:string;source:string}|null>(null),attempts=useRef(new Set<string>()),epoch=useRef(0);
 const [candidate,setCandidate]=useState<{key:string;value:FirstMeetingStatus}|null>(null);
 const discovered=status?.state==='DISCOVERED',supported=!!status&&Object.prototype.hasOwnProperty.call(status,'discoveryStatus');
 const row=candidate?.key===key?candidate.value:status;
 const ready=row?.state==='DISCOVERED'&&row.discoveryStatus==='READY'&&!!row.discoveryLine;
 const preparing=discovered&&supported&&read.current?.key!==key&&!window.current.isExpired(key)&&(!row?.discoveryStatus||row.discoveryStatus==='PENDING');
 const proposed={key,line:ready?row.discoveryLine:status?.discoveryLine??'',source:ready?(row.discoverySource==='LLM'?'SERVER_LLM':'SERVER_FALLBACK'):'SERVICE_FALLBACK'};
 const shown=read.current?.key===key?read.current:proposed;
 const cancel=()=>{epoch.current++;window.current!.cancel(key);if(read.current?.key!==key)read.current={key,line:status?.discoveryLine??'',source:'SERVICE_FALLBACK'}};
 useLayoutEffect(()=>{if(!discovered)return;if(preparing)window.current!.begin(key);else if(read.current?.key!==key){window.current!.cancel(key);read.current=proposed;}},[key,discovered,preparing,proposed.line,proposed.source]);
 useEffect(()=>{
  if(!discovered||!supported)return;
  const controller=new AbortController(),mine=++epoch.current;let timer:ReturnType<typeof setTimeout>|undefined,reads=0;
  const valid=()=>!controller.signal.aborted&&mine===epoch.current&&latest.current.petId===petId&&latest.current.locale===locale&&latest.current.status?.state==='DISCOVERED'&&!document.hidden;
  const accept=(value:FirstMeetingStatus)=>{if(!valid()||value.state!=='DISCOVERED')return;setCandidate({key,value});if(value.discoveryStatus==='PENDING'&&reads++<8)timer=setTimeout(()=>void getFirstMeeting(petId,controller.signal).then(accept).catch(()=>{}),500)};
  if(status.discoveryStatus==='PENDING')timer=setTimeout(()=>void getFirstMeeting(petId,controller.signal).then(accept).catch(()=>{}),500);
  else if(!status.discoveryStatus&&!attempts.current.has(key)){attempts.current.add(key);void prepareFirstMeetingDiscovery(petId,controller.signal).then(accept).catch(()=>{if(valid()){window.current!.cancel(key);redraw(v=>v+1)}})}
  const invalid=()=>{if(document.hidden){epoch.current++;controller.abort();window.current!.cancel(key);redraw(v=>v+1)}};
  const edited=(event:Event)=>{const id=(event as CustomEvent<{petId?:number}>).detail?.petId;if(id==null||id===petId){epoch.current++;controller.abort();window.current!.cancel(key);redraw(v=>v+1)}};
  document.addEventListener('visibilitychange',invalid);globalThis.addEventListener('zzal-persona-updated',edited);
  return()=>{controller.abort();if(timer)clearTimeout(timer);window.current!.cancel(key);document.removeEventListener('visibilitychange',invalid);globalThis.removeEventListener('zzal-persona-updated',edited)};
 },[key,discovered,supported]);
 return{line:preparing?'…':shown.line,source:preparing?'PREPARING':shown.source,cancel};
}
