'use client';
import {useEffect,useLayoutEffect,useRef,useState,type CSSProperties} from 'react';
import {LINE,ROOM_KEYS,type RoomKey} from '../yeoul/constants';
import type {Live} from '../yeoul/useHatch';
import type {CareAction} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import type {FocusedExperience} from './useFocusedExperience';
import {focusedCopy,restHelp} from './focusedCopy';
import './focused-tiles.css';
import {NewDot,newBadgeCounts} from './newBadges';
const actions={table:['FEED','SNACK'],bath:['CLEAN','BATH'],play:['GAME'],bed:['REST'],album:['collection','journal','background']} as const;
const tutorialAction:Record<string,string>={FEED:'FEED',SNACK:'SNACK',CLEAN:'CLEAN',BATH:'BATH',GAME:'GAME',NAP_SLEEP:'REST',NAP_WAKE:'REST'};
const layerStyle=(text:string)=>Object.fromEntries([['position','absolute'],...text.split(';').filter(Boolean).map(part=>{const i=part.indexOf(':');return[part.slice(0,i).replace(/-([a-z])/g,(_,c:string)=>c.toUpperCase()),part.slice(i+1)]})]) as CSSProperties;
/** Approved A: only the five-tile row changes; the real composer stays mounted. */
export default function FocusedTiles({live,x,locale,drawerOpen,beforeOpen,onSleepingAction}:{live:Live;x:FocusedExperience;locale:ZzalLocale;drawerOpen:boolean;beforeOpen:()=>void;onSleepingAction:(wakeLocked?:boolean)=>void}){
 const t=focusedCopy(locale),[group,setGroup]=useState<RoomKey|null>(null),root=useRef<HTMLDivElement>(null),last=useRef<RoomKey|null>(null);
 const sleeping=!!live.pet?.clock?.sleeping,traveling=!!live.pet?.trip;
 const diaryDot=newBadgeCounts(live.pet,x.guided).diary;
 const sleepNoticeEnabled=sleeping&&!x.busy&&!traveling;
 const roomActions:Record<RoomKey,readonly string[]>={...actions,bed:live.pet?.sick?['REST','MEDICINE']:actions.bed};
 // The server blocks medicine throughout the first intro and while its review is pending.
 const introMedicineBlocked=!!live.pet?.firstIntro&&(!live.pet.clock?.clockStartedAt||live.pet.firstIntro.version===4&&live.pet.firstIntro.key==='REVIEW_SEEN');
 const treatmentContext=!!live.pet?.sick&&live.pet.phase==='ALIVE'&&!x.guided&&!introMedicineBlocked&&!traveling&&!x.busy&&!live.careDelivery&&!drawerOpen&&(x.panel===null||x.panel==='care');
 const medicineReady=treatmentContext&&!sleeping;
 const medicalTarget=medicineReady?'MEDICINE':treatmentContext&&sleeping&&live.pet?.clock?.canWake?'REST':null;
 const target=medicalTarget??(x.guided&&x.currentTarget?tutorialAction[x.key??'']:null);
 const names:Record<RoomKey,string>=locale==='en'?{table:'Kitchen',bath:'Bathroom',play:'Yard',bed:'Bedroom',album:t.album}:locale==='ja'?{table:'キッチン',bath:'浴室',play:'庭',bed:'寝室',album:t.album}:{table:'주방',bath:'욕실',play:'마당',bed:'침실',album:t.album};
 const labels:Record<string,string>={FEED:t.feed,SNACK:t.snack,CLEAN:t.clean,BATH:t.bath,GAME:locale==='en'?'Left or right':locale==='ja'?'左右あて':'좌우 맞히기',PET:t.pet,MEDICINE:t.medicine,REST:sleeping?t.wake:t.sleep,collection:t.album,journal:t.journal,background:t.background};
 const allowed=(action:string)=>action==='MEDICINE'?medicineReady:!x.busy&&(!traveling||['collection','journal','background'].includes(action))&&(!x.guided||target===action)&&(action==='REST'?!!(sleeping?live.pet?.clock?.canWake:live.pet?.clock?.canSleep):!sleeping);
 const enabled=(key:RoomKey)=>!x.busy&&(sleeping?key==='bed'&&!traveling:(!x.guided||!!target&&roomActions[key].includes(target))&&(key==='album'||!traveling));
 const back=()=>{last.current=group;setGroup(null);if(x.panel==='care')x.close()};
 useEffect(()=>{if(drawerOpen||traveling||x.panel==='game'||x.busy&&!(group==='bed'&&live.pet?.sick)){setGroup(null)}},[x.busy,drawerOpen,traveling,live.petId,x.panel,group,!!live.pet?.sick]);
 useEffect(()=>{setGroup(null);last.current=null},[live.petId]);
 useLayoutEffect(()=>{if(x.busy)return;if(group)root.current?.querySelector<HTMLButtonElement>('[data-fr-care-choice]:not(:disabled)')?.focus({preventScroll:true});else if(last.current)root.current?.querySelector<HTMLButtonElement>(`[data-fr-tile="${last.current}"]`)?.focus({preventScroll:true});},[group,x.busy]);
 useEffect(()=>{if(drawerOpen&&x.panel==='care')x.close()},[drawerOpen]);
 const pick=(key:RoomKey)=>{if(sleepNoticeEnabled&&key!=='bed'){onSleepingAction();return;}if(!enabled(key))return;beforeOpen();last.current=key;setGroup(key);if(key!=='album')x.open('care');};
 const run=(action:string)=>{if(sleepNoticeEnabled&&(action!=='REST'||!live.pet?.clock?.canWake)){onSleepingAction(action==='REST');return;}if(!allowed(action))return;if(action==='collection'||action==='journal'||action==='background'){setGroup(null);x.open(action)}else if(action==='GAME')void x.startGame();else if(action==='REST')void x.rest();else void x.care(action as CareAction)};
 const fact=group==='table'&&x.key==='SNACK'?t.snackHelp:group==='bath'&&x.key==='BATH'?t.bathHelp:group==='bed'?restHelp(locale,sleeping,x.key==='NAP_SLEEP'||x.key==='NAP_WAKE'):null;
 return <div ref={root} className="fr-tile-area" data-fr-care-level={group&&x.panel!=='game'?'actions':'groups'} onKeyDown={event=>{if(event.key==='Escape'&&group&&!x.busy){event.preventDefault();back()}}}>
  {group&&x.panel!=='game'?<div className="fr-tile-actions" aria-label={names[group]}>
   <button type="button" className="fr-tile-back" data-fr-target={medicalTarget&&group!=='bed'?'true':undefined} onClick={back} disabled={x.busy} aria-label={locale==='ko'?'분류로 돌아가기':locale==='en'?'Back to rooms':'分類に戻る'}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7"/></svg></button>
   {roomActions[group].map(action=><button key={action} type="button" data-fr-care-choice data-fr-action={action} data-fr-collection={group==='album'?action:undefined} data-fr-new={action==='journal'&&diaryDot>0?'diary':undefined} data-fr-target={target===action&&allowed(action)?'true':undefined} aria-disabled={!allowed(action)||undefined} disabled={!allowed(action)&&!sleepNoticeEnabled} onClick={()=>run(action)}>{labels[action]}{action==='journal'&&<NewDot place="tile" count={diaryDot} locale={locale}/>}</button>)}
   <button type="button" className="fr-tile-close" onClick={back} disabled={x.busy} aria-label={t.close}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
  </div>:<div className="fr-legacy-tiles">{ROOM_KEYS.map(key=><button key={key} type="button" data-fr-tile={key} data-fr-care-group={{table:'kitchen',bath:'bathroom',play:'play',bed:'bedroom',album:'album'}[key]} data-fr-action={key==='album'?'collection-hub':'care'} data-fr-target={target&&roomActions[key].includes(target)&&enabled(key)?'true':undefined} aria-expanded={false} data-fr-new={key==='album'&&diaryDot>0?'diary':undefined} aria-disabled={!enabled(key)||undefined} disabled={!enabled(key)&&!sleepNoticeEnabled} onClick={()=>pick(key)}><span className="fr-legacy-icon" aria-hidden="true">{LINE[key].map((line,i)=><span key={i} style={layerStyle(line)}/>)}</span><span>{names[key]}</span>{key==='album'&&<NewDot place="tile" count={diaryDot} locale={locale}/>}</button>)}</div>}
  {fact&&<p className="fr-tile-fact">{fact}</p>}
 </div>;
}
