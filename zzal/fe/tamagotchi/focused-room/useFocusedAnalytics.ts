import {useEffect,useRef} from 'react';
import type {Live} from '../yeoul/useHatch';
import {onLeave,ztrack} from '../yeoul/funnel';
import {createFocusedMetrics} from './focusedAnalytics';

export function useFocusedAnalytics(live:Live,sampleMode:boolean){
  const currentLive=useRef(live);currentLive.current=live;
  const petId=!sampleMode&&live.petId&&live.pet?.petId===live.petId&&live.pet.phase==='ALIVE'?live.petId:null;
  const slot=useRef<{petId:number;tracker:ReturnType<typeof createFocusedMetrics>;pending:ReturnType<typeof setTimeout>|null}|null>(null);
  if(petId&&slot.current?.petId!==petId)slot.current={petId,tracker:createFocusedMetrics({petId,emit:(name,props)=>ztrack(name,props,{source_screen:'focused_room',context:currentLive.current.pet?.firstIntro?.version===4&&!currentLive.current.pet.firstIntro.completed?'tutorial':'real'})}),pending:null};
  const tracker=petId?slot.current?.tracker:null;
  useEffect(()=>{
    const current=slot.current;
    if(!tracker||!current)return;
    if(current.pending){clearTimeout(current.pending);current.pending=null}
    const nav=performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming|undefined;
    tracker.enter(nav?.type??'navigate');
    const off=onLeave(()=>tracker.leave());
    const show=()=>{if(document.visibilityState==='visible')tracker.resume()};
    window.addEventListener('pageshow',show);document.addEventListener('visibilitychange',show);
    return()=>{off();window.removeEventListener('pageshow',show);document.removeEventListener('visibilitychange',show);current.pending=setTimeout(()=>tracker.leave(),0)};
  },[tracker]);
  useEffect(()=>{tracker?.observeIntro(live.pet?.firstIntro)},[tracker,live.pet?.firstIntro?.version,live.pet?.firstIntro?.key,live.pet?.firstIntro?.completed]);
  return tracker;
}
