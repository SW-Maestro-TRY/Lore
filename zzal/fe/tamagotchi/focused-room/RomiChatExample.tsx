'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import type {ZzalLocale} from '../../lib/locale';
import {endingCopy} from './focusedCopy';
import {ENDING_STORY} from './useTutorialEnding';
const ROMI_CHAT_EXAMPLES=['/zzal-entry/romi-sample/custom8/warm_sleeves.webp','/zzal-entry/romi-sample/custom8/tap_rhythm.webp'] as const;
export const isRomiMotionPhase=(phase:number)=>phase===ENDING_STORY.firstExample||phase===ENDING_STORY.secondExample;
/** A labelled demonstration, never passed to the live chat or reward owners. */
export function RomiChatExample({phase,locale,onReady}:{phase:number;locale:ZzalLocale;onReady:(ready:boolean)=>void}){
 const words=endingCopy(locale),example=phase===ENDING_STORY.firstExample?0:1,copy=words.examples[example],[failed,setFailed]=useState(false),timer=useRef<ReturnType<typeof setTimeout>|null>(null),currentPhase=useRef(phase);currentPhase.current=phase;
 useLayoutEffect(()=>{setFailed(false);onReady(false);return()=>{if(timer.current)clearTimeout(timer.current)}},[phase,onReady]);
 return <section className="fr-ending-example" data-fr-example-phase={phase} aria-label={words.example}>
  <h2>{words.example}</h2>
  <><img data-fr-romi-motion key={phase} src={ROMI_CHAT_EXAMPLES[example]} alt={copy.motion} onLoad={()=>{if(currentPhase.current!==phase)return;if(timer.current)clearTimeout(timer.current);timer.current=setTimeout(()=>{if(currentPhase.current===phase)onReady(true)},900)}} onError={()=>{if(currentPhase.current!==phase)return;setFailed(true);onReady(true)}}/>{failed&&<p role="status">{words.motionFailed}</p>}</>
  <div className="fr-example-dialogue"><p><small>{words.user}</small>{copy.userLine}</p><p><small>{words.romi}</small>{copy.romiLine}</p></div>
 </section>;
}
