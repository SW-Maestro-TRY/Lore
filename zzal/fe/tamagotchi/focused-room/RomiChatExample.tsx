'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import type {ZzalLocale} from '../../lib/locale';
import {endingCopy} from './focusedCopy';
export const ROMI_CHAT_EXAMPLE='/zzal-entry/romi-sample/custom8/eat_snack.webp';
/** A labelled demonstration, never passed to the live chat or reward owners. */
export function RomiChatExample({phase,locale,onReady}:{phase:number;locale:ZzalLocale;onReady:(ready:boolean)=>void}){
 const words=endingCopy(locale),[failed,setFailed]=useState(false),timer=useRef<ReturnType<typeof setTimeout>|null>(null);
 useLayoutEffect(()=>{setFailed(false);onReady(phase!==1);return()=>{if(timer.current)clearTimeout(timer.current)}},[phase,onReady]);
 return <section className="fr-ending-example" data-fr-example-phase={phase} aria-label={words.example}>
  <h2>{words.example}</h2>
  {phase===0?<div className="fr-example-dialogue"><p><small>{words.user}</small>{words.userLine}</p><p><small>{words.romi}</small>{words.romiLine}</p></div>:<><img data-fr-romi-motion src={ROMI_CHAT_EXAMPLE} alt={words.motion} onLoad={()=>{if(timer.current)clearTimeout(timer.current);timer.current=setTimeout(()=>onReady(true),900)}} onError={()=>{setFailed(true);onReady(true)}}/>{failed&&<p role="status">{words.motionFailed}</p>}</>}
 </section>;
}
