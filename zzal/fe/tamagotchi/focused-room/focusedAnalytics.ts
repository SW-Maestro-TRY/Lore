import type { ChatReply } from '../../lib/pet';
import { FIRST_INTRO_V4_KEYS } from '../yeoul/firstIntro';
import { ztrack } from '../yeoul/funnel';

type Props = Record<string, string | number | boolean>;
type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;
type Intro = {version:number;key:string;completed?:boolean} | null | undefined;
type Options = {petId:number; emit?:(name:string,props:Props)=>void; now?:()=>number; storage?:StorageLike|null};
const memory = new Map<number, Set<string>>();
const LIMIT = 256;
const ORDER:readonly string[]=[...FIRST_INTRO_V4_KEYS,'COMPLETE'];
const transient=(value:string)=>value.startsWith('chat:')&&value!=='chat:first';

/** IDs stay in a bounded browser-only dedup ledger; event payloads contain enums/counts only. */
export function createFocusedMetrics({petId,emit=ztrack,now=Date.now,storage}:Options){
  const key=`zzal-focused-metrics-v1:${petId}`;
  const seen=memory.get(petId)??new Set<string>();memory.set(petId,seen);if(memory.size>32)memory.delete(memory.keys().next().value!);
  let store=storage;
  if(store===undefined)try{store=window.localStorage}catch{store=null}
  try{const values=JSON.parse(store?.getItem(key)??'[]');if(Array.isArray(values))for(const value of values)if(typeof value==='string')seen.add(value)}catch{/* Memory fallback also coalesces StrictMode/retries. */}
  const claim=(token:string)=>{if(seen.has(token))return false;seen.add(token);while(seen.size>LIMIT){const oldest=[...seen].find(transient);if(!oldest)break;seen.delete(oldest)}try{store?.setItem(key,JSON.stringify([...seen]))}catch{}return true};
  const send=(name:string,props:Props)=>{try{emit(name,props)}catch{/* Metrics never break an action. */}};
  let intro:Intro=null,stepAt=now(),roomAt=now(),active=false,entered=false;
  let navigation='navigate';
  const elapsed=(at:number)=>Math.max(0,now()-at);
  const step=(action:string)=>{if(intro?.version!==4||intro.key==='COMPLETE')return;const index=ORDER.indexOf(intro.key);if(index<0)return;send('zzal_tutorial_step',{action,type:intro.key,step:index+1,ms:elapsed(stepAt),from:'focused_v4'})};
  return {
    enter(type:string){if(entered)return;entered=true;active=true;navigation=['navigate','reload','back_forward'].includes(type)?type:'navigate';roomAt=now();stepAt=now();send('zzal_room_viewed',{action:'enter',type:navigation,from:'focused',ms:0})},
    leave(){if(!active)return;active=false;send('zzal_room_viewed',{action:'leave',type:navigation,from:'focused',ms:elapsed(roomAt)});step('leave')},
    resume(){if(!entered||active)return;active=true;roomAt=now();stepAt=now();send('zzal_room_viewed',{action:'resume',type:'visible',from:'focused',ms:0})},
    observeIntro(next:Intro){
      if(!next)return;
      if(intro?.version===4&&next.version===4&&intro.key!==next.key){
        const before=ORDER.indexOf(intro.key),after=ORDER.indexOf(next.key);
        if(before>=0&&after>before&&claim(`step:${intro.key}`))step('completed');
        if(intro.key==='REVIEW_SEEN'&&next.key==='COMPLETE'&&next.completed===true&&claim('intro:completed'))send('zzal_tutorial_completed',{action:'completed',type:'v4',from:'focused',count:1});
      }
      if(intro?.key!==next.key||intro?.version!==next.version)stepAt=now();
      intro={...next};
    },
    chatSaved(reply:ChatReply|null|undefined){
      const session=reply?.session,turns=reply?.turns;
      if(!session||!Array.isArray(turns))return;
      const users=turns.filter(t=>t.speaker==='USER'&&Number.isInteger(t.idx));
      if(!users.length)return;
      let fresh=false;
      for(const turn of users){if(!claim(`chat:${session.id}:${turn.idx}`))continue;fresh=true;send('zzal_chat_turn',{action:'user',step:session.round,code:session.kind.toLowerCase(),from:'focused'})}
      if(!fresh)return;
      for(const turn of turns.filter(t=>t.speaker==='PET'))send('zzal_chat_turn',{action:(turn.type??'continue').toLowerCase(),step:session.round,code:session.kind.toLowerCase(),type:turn.generator??'template',reason:turn.filteredReason??'ok',from:'focused'});
      if(session.kind==='CONTINUOUS'&&session.round===1&&claim('chat:first'))send('zzal_first_chat_succeeded',{action:'user',code:'continuous',step:1,from:'focused'});
    },
  };
}
