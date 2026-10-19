'use client';
import {useEffect,useLayoutEffect,useRef,useState,type CSSProperties} from 'react';
import {LINE,ROOM_KEYS,type RoomKey} from '../yeoul/constants';
import type {Live} from '../yeoul/useHatch';
import type {CareAction} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import type {FocusedExperience} from './useFocusedExperience';
import {focusedCopy,restHelp} from './focusedCopy';
import './focused-tiles.css';
const actions={table:['FEED','SNACK'],bath:['CLEAN','BATH'],play:['GAME'],bed:['REST'],album:['collection','journal','background']} as const;
const tutorialAction:Record<string,string>={FEED:'FEED',SNACK:'SNACK',CLEAN:'CLEAN',BATH:'BATH',GAME:'GAME',NAP_SLEEP:'REST',NAP_WAKE:'REST'};
const layerStyle=(text:string)=>Object.fromEntries([['position','absolute'],...text.split(';').filter(Boolean).map(part=>{const i=part.indexOf(':');return[part.slice(0,i).replace(/-([a-z])/g,(_,c:string)=>c.toUpperCase()),part.slice(i+1)]})]) as CSSProperties;
/** Approved A: only the five-tile row changes; the real composer stays mounted. */
export default function FocusedTiles({live,x,locale,drawerOpen,beforeOpen}:{live:Live;x:FocusedExperience;locale:ZzalLocale;drawerOpen:boolean;beforeOpen:()=>void}){
 const t=focusedCopy(locale),[group,setGroup]=useState<RoomKey|null>(null),root=useRef<HTMLDivElement>(null),last=useRef<RoomKey|null>(null);
 const sleeping=!!live.pet?.clock?.sleeping,traveling=!!live.pet?.trip,target=x.guided&&x.currentTarget?tutorialAction[x.key??'']:null;
 const names:Record<RoomKey,string>=locale==='en'?{table:'Kitchen',bath:'Bathroom',play:'Yard',bed:'Bedroom',album:t.album}:locale==='ja'?{table:'キッチン',bath:'浴室',play:'庭',bed:'寝室',album:t.album}:{table:'주방',bath:'욕실',play:'마당',bed:'침실',album:t.album};
 const labels:Record<string,string>={FEED:t.feed,SNACK:t.snack,CLEAN:t.clean,BATH:t.bath,GAME:locale==='en'?'Left or right':locale==='ja'?'左右あて':'좌우 맞히기',PET:t.pet,REST:sleeping?t.wake:t.sleep,collection:t.album,journal:t.journal,background:t.background};
 const allowed=(action:string)=>!x.busy&&(!traveling||['collection','journal','background'].includes(action))&&(!x.guided||target===action)&&(action==='REST'?!!(sleeping?live.pet?.clock?.canWake:live.pet?.clock?.canSleep):['collection','journal','background'].includes(action)||!sleeping);
 const enabled=(key:RoomKey)=>!x.busy&&(!x.guided||!!target&&(actions[key] as readonly string[]).includes(target))&&(key==='album'||!traveling)&&(key==='bed'||key==='album'||!sleeping);
 const back=()=>{last.current=group;setGroup(null);if(x.panel==='care')x.close()};
 useEffect(()=>{if(x.busy||drawerOpen||traveling){setGroup(null)}},[x.busy,drawerOpen,traveling,live.petId]);
 useEffect(()=>{setGroup(null);last.current=null},[live.petId]);
 useLayoutEffect(()=>{if(x.busy)return;if(group)root.current?.querySelector<HTMLButtonElement>('[data-fr-care-choice]:not(:disabled)')?.focus({preventScroll:true});else if(last.current)root.current?.querySelector<HTMLButtonElement>(`[data-fr-tile="${last.current}"]`)?.focus({preventScroll:true});},[group,x.busy]);
 useEffect(()=>{if(drawerOpen&&x.panel==='care')x.close()},[drawerOpen]);
 const pick=(key:RoomKey)=>{if(!enabled(key))return;beforeOpen();last.current=key;setGroup(key);if(key!=='album')x.open('care');};
 const run=(action:string)=>{if(!allowed(action))return;if(action==='collection'||action==='journal'||action==='background'){setGroup(null);x.open(action)}else if(action==='GAME')void x.startGame();else if(action==='REST')void x.rest();else void x.care(action as CareAction)};
 const fact=group==='table'&&x.key==='SNACK'?t.snackHelp:group==='bath'&&x.key==='BATH'?t.bathHelp:group==='bed'?restHelp(locale,sleeping,x.key==='NAP_SLEEP'||x.key==='NAP_WAKE'):null;
 return <div ref={root} className="fr-tile-area" data-fr-care-level={group?'actions':'groups'} onKeyDown={event=>{if(event.key==='Escape'&&group&&!x.busy){event.preventDefault();back()}}}>
  {group?<div className="fr-tile-actions" aria-label={names[group]}>
   <button type="button" className="fr-tile-back" onClick={back} disabled={x.busy} aria-label={locale==='ko'?'분류로 돌아가기':locale==='en'?'Back to rooms':'分類に戻る'}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7"/></svg></button>
   {actions[group].map(action=><button key={action} type="button" data-fr-care-choice data-fr-action={action} data-fr-collection={group==='album'?action:undefined} data-fr-target={target===action&&allowed(action)?'true':undefined} disabled={!allowed(action)} onClick={()=>run(action)}>{labels[action]}</button>)}
   <button type="button" className="fr-tile-close" onClick={back} disabled={x.busy} aria-label={t.close}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
  </div>:<div className="fr-legacy-tiles">{ROOM_KEYS.map(key=><button key={key} type="button" data-fr-tile={key} data-fr-care-group={{table:'kitchen',bath:'bathroom',play:'play',bed:'bedroom',album:'album'}[key]} data-fr-action={key==='album'?'collection-hub':'care'} data-fr-target={target&&(actions[key] as readonly string[]).includes(target)&&enabled(key)?'true':undefined} aria-expanded={false} disabled={!enabled(key)} onClick={()=>pick(key)}><span className="fr-legacy-icon" aria-hidden="true">{LINE[key].map((line,i)=><span key={i} style={layerStyle(line)}/>)}</span><span>{names[key]}</span></button>)}</div>}
  {fact&&<p className="fr-tile-fact">{fact}</p>}
 </div>;
}
