'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import type {Live} from '../yeoul/useHatch';
import type {CareAction} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import type {FocusedExperience} from './useFocusedExperience';
import {focusedCopy,restHelp} from './focusedCopy';
import './care-grouping.css';

type Group='kitchen'|'bathroom'|'play'|'bedroom';
const groups:Group[]=['kitchen','bathroom','play','bedroom'];
const actions:Record<Group,string[]>={kitchen:['FEED','SNACK'],bathroom:['CLEAN','BATH'],play:['GAME','PET'],bedroom:['REST']};
const stageAction:Record<string,string>={FEED:'FEED',SNACK:'SNACK',CLEAN:'CLEAN',BATH:'BATH',GAME:'GAME',NAP_SLEEP:'REST',NAP_WAKE:'REST'};

/** The same care surface is used by an owned pet and the isolated sample provider. */
export default function FocusedCare({live,x,locale}:{live:Live;x:FocusedExperience;locale:ZzalLocale}){
 const t=focusedCopy(locale),[group,setGroup]=useState<Group|null>(null),root=useRef<HTMLDivElement>(null),lastGroup=useRef<Group|null>(null);
 const sleeping=!!live.pet?.clock?.sleeping,traveling=!!live.pet?.trip,target=x.guided?stageAction[x.key??'']:null;
 const names:Record<Group,string>=locale==='en'?{kitchen:'Kitchen',bathroom:'Bathroom',play:'Play',bedroom:'Bedroom'}:locale==='ja'?{kitchen:'キッチン',bathroom:'浴室',play:'遊び',bedroom:'寝室'}:{kitchen:'주방',bathroom:'욕실',play:'놀이',bedroom:'침실'};
 const labels:Record<string,string>={FEED:t.feed,SNACK:t.snack,CLEAN:t.clean,BATH:t.bath,GAME:live.game?.playing?t.restart:t.game,PET:t.pet,REST:sleeping?t.wake:t.sleep,MEDICINE:t.medicine};
 const allowed=(action:string)=>!x.busy&&!traveling&&(!x.guided||target===action)&&(action==='REST'?!!(sleeping?live.pet?.clock?.canWake:live.pet?.clock?.canSleep):!sleeping);
 const enabledGroup=(id:Group)=>!x.busy&&!traveling&&(!x.guided||!!target&&actions[id].includes(target));
 useLayoutEffect(()=>{if(group)root.current?.querySelector<HTMLButtonElement>('[data-fr-care-choice]:not(:disabled)')?.focus({preventScroll:true});else if(lastGroup.current)root.current?.querySelector<HTMLButtonElement>(`[data-fr-care-group="${lastGroup.current}"]`)?.focus({preventScroll:true});},[group]);
 const back=()=>{lastGroup.current=group;setGroup(null)};
 const run=(action:string)=>{if(!allowed(action))return;if(action==='GAME')void x.startGame();else if(action==='REST')void x.rest();else void x.care(action as CareAction)};
 const fact=group==='kitchen'&&x.key==='SNACK'?t.snackHelp:group==='bathroom'&&x.key==='BATH'?t.bathHelp:group==='bedroom'?restHelp(locale,sleeping,x.key==='NAP_SLEEP'||x.key==='NAP_WAKE'):null;
 const backLabel=locale==='ko'?'돌보기 분류로 돌아가기':locale==='en'?'Back to care groups':'お世話の分類に戻る';
 return <div ref={root} className="fr-care-content" data-fr-care-level={group?'actions':'groups'}>
  <header className="fr-care-head">
   {group?<button className="fr-care-back" type="button" aria-label={backLabel} onClick={back} disabled={x.busy}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7"/></svg></button>:live.pet?.sick?<button className="fr-care-medicine" type="button" data-fr-action="MEDICINE" disabled={!allowed('MEDICINE')} onClick={()=>run('MEDICINE')}>{t.medicine}</button>:<span/>}
   <h2>{group?names[group]:t.care}</h2>
   <button type="button" className="fr-care-close" aria-label={t.close} onClick={x.close} disabled={x.busy}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
  </header>
  <div className="fr-care-choices">
   {group?actions[group].map(action=><button type="button" key={action} data-fr-care-choice data-fr-action={action} data-fr-target={target===action&&!x.busy&&allowed(action)?'true':undefined} disabled={!allowed(action)} onClick={()=>run(action)}>{labels[action]}</button>):groups.map(id=><button type="button" key={id} data-fr-care-group={id} data-fr-target={target&&actions[id].includes(target)&&enabledGroup(id)?'true':undefined} disabled={!enabledGroup(id)} onClick={()=>setGroup(id)}>{names[id]}</button>)}
  </div>
  {fact&&<p className="fr-care-fact">{fact}</p>}
 </div>;
}
