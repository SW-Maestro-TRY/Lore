'use client';
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {createPortal} from 'react-dom';
import {stageUrl,type PropStage} from '../props/spec';

// Opaque inner bounds of the existing v1 cover frames, in native asset pixels.
// Cover these bounds so transparent edge particles never leave the UI exposed.
const COVER={bath:{width:782,height:959,bottom:0},dust:{width:558,height:1160,bottom:149}};

/** Cleaning/bath cover their own game room, including its dock, below the platform header. */
export default function FullscreenCareEffect({effect,stage,stages}:{effect:keyof typeof COVER;stage:PropStage;stages:readonly PropStage[]}){
 const anchor=useRef<HTMLSpanElement>(null);
 const [bounds,setBounds]=useState<{room:HTMLElement;width:number;height:number}|null>(null);
 useLayoutEffect(()=>{
  const room=anchor.current?.closest<HTMLElement>('[data-focused-room]');if(!room)return;
  const measure=()=>{const {width,height}=room.getBoundingClientRect();setBounds(previous=>previous?.room===room&&previous.width===width&&previous.height===height?previous:{room,width,height})};
  measure();const observer=new ResizeObserver(measure);observer.observe(room);
  return()=>observer.disconnect();
 },[]);
 useEffect(()=>{for(const value of stages){const image=new Image();image.src=stageUrl(value)}},[stages]);
 const marker=<span ref={anchor} hidden aria-hidden="true"/>;
 if(!bounds?.width||!bounds.height||!stage.srcW||!stage.srcH)return marker;
 const cover=COVER[effect],scale=Math.max(bounds.width/cover.width,bounds.height/cover.height);
 const width=stage.srcW*scale,height=stage.srcH*scale;
 return <>{marker}{createPortal(<div data-fr-care-effect={effect} data-stage={stage.n} aria-hidden="true" style={{position:'absolute',inset:0,zIndex:2147483646,overflow:'hidden',pointerEvents:'none'}}>
  <img data-fr-prop={effect} data-stage={stage.n} alt="" src={stageUrl(stage)} onError={event=>{event.currentTarget.hidden=true}} style={{position:'absolute',left:(bounds.width-width)/2,bottom:-cover.bottom*scale,width,height,maxWidth:'none',maxHeight:'none',pointerEvents:'none'}}/>
 </div>,bounds.room)}</>;
}
