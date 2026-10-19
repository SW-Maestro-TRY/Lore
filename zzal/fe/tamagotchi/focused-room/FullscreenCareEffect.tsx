'use client';
import {useEffect,useLayoutEffect,useState} from 'react';
import {createPortal} from 'react-dom';
import {stageUrl,type PropStage} from '../props/spec';

// Opaque inner bounds of the existing v1 cover frames, in native asset pixels.
// Cover these bounds so transparent edge particles never leave the UI exposed.
const COVER={bath:{width:782,height:959,bottom:0},dust:{width:558,height:1160,bottom:149}};

/** Only the cleaning/bath screen effects escape the room's clipping and stacking context. */
export default function FullscreenCareEffect({effect,stage,stages}:{effect:keyof typeof COVER;stage:PropStage;stages:readonly PropStage[]}){
 const [viewport,setViewport]=useState({left:0,top:0,width:0,height:0});
 useLayoutEffect(()=>{
  const view=window.visualViewport;
  const measure=()=>setViewport({left:view?.offsetLeft??0,top:view?.offsetTop??0,width:view?.width??window.innerWidth,height:view?.height??window.innerHeight});
  measure();window.addEventListener('resize',measure);view?.addEventListener('resize',measure);view?.addEventListener('scroll',measure);
  return()=>{window.removeEventListener('resize',measure);view?.removeEventListener('resize',measure);view?.removeEventListener('scroll',measure)};
 },[]);
 useEffect(()=>{for(const value of stages){const image=new Image();image.src=stageUrl(value)}},[stages]);
 if(!viewport.width||!viewport.height||!stage.srcW||!stage.srcH)return null;
 const cover=COVER[effect],scale=Math.max(viewport.width/cover.width,viewport.height/cover.height);
 const width=stage.srcW*scale,height=stage.srcH*scale;
 return createPortal(<div data-fr-care-effect={effect} data-stage={stage.n} aria-hidden="true" style={{position:'fixed',...viewport,zIndex:2147483646,overflow:'hidden',pointerEvents:'none'}}>
  <img data-fr-prop={effect} data-stage={stage.n} alt="" src={stageUrl(stage)} onError={event=>{event.currentTarget.hidden=true}} style={{position:'absolute',left:(viewport.width-width)/2,bottom:-cover.bottom*scale,width,height,maxWidth:'none',maxHeight:'none',pointerEvents:'none'}}/>
 </div>,document.body);
}
