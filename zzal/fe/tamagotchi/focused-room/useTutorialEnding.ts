'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import {useAuth} from '@common/auth/useAuth';
export const ENDING_STORY={intro:0,firstExample:1,secondExample:2,memory:3,review:4,invite:5} as const;
type Position={scope:string;finish:number;story:number};
/** Reading position only. No chat, memory, reward or tutorial receipt is stored here. */
export function useTutorialEnding(petId:number|null,serverKey:string|undefined,sample:boolean){
 const auth=useAuth(),scope=!sample&&auth.user&&petId?`${auth.user.userId}:${petId}`:'';
 const storageKey=`zzal-tutorial-ending-v5:${scope}`;
 const [position,setPosition]=useState<Position>({scope:'',finish:0,story:-1});
 const current=useRef(position);current.current=position;
 useLayoutEffect(()=>{
  if(!scope||!serverKey)return;
  let next:Position={scope,finish:0,story:-1};
  try{
   if(serverKey==='COMPLETE'){sessionStorage.removeItem(storageKey);sessionStorage.removeItem(`zzal-tutorial-ending-v4:${scope}`);sessionStorage.removeItem(`zzal-tutorial-ending-v3:${scope}`);sessionStorage.removeItem(`zzal-tutorial-ending-v2:${scope}`);sessionStorage.removeItem(`zzal-focused-review-dismissed-v4:${petId}`)}
   else{const saved=JSON.parse(sessionStorage.getItem(storageKey)??'null');if(saved?.scope===scope)next={scope,finish:saved.finish===1?1:0,story:Number.isInteger(saved.story)&&saved.story>=0&&saved.story<=ENDING_STORY.invite?saved.story:-1};
    else{const v4=JSON.parse(sessionStorage.getItem(`zzal-tutorial-ending-v4:${scope}`)??'null'),v3=JSON.parse(sessionStorage.getItem(`zzal-tutorial-ending-v3:${scope}`)??'null'),v2=JSON.parse(sessionStorage.getItem(`zzal-tutorial-ending-v2:${scope}`)??'null');const old=[v4,v3,v2].find(v=>v?.scope===scope);if(old){const story=Number.isInteger(old.story)&&old.story>=0?(old===v2?ENDING_STORY.firstExample:Math.min([1,1,2,2,3,4,5][Math.min(old.story,6)],old===v3?ENDING_STORY.review:ENDING_STORY.invite)):-1;next={scope,finish:old.finish===1?1:0,story};sessionStorage.setItem(storageKey,JSON.stringify(next));for(const version of [4,3,2])sessionStorage.removeItem(`zzal-tutorial-ending-v${version}:${scope}`)}}
   }
  }catch{/* Storage denial leaves a usable in-memory guide. */}
  if(serverKey==='REVIEW_SEEN'&&next.story<0)next.story=ENDING_STORY.intro;
  current.current=next;setPosition(next);
 },[scope,serverKey,storageKey,petId]);
 const positionForScope=position.scope===scope?position:{scope,finish:0,story:-1};
 const update=(patch:Partial<Pick<Position,'finish'|'story'>>)=>{const previous=current.current.scope===scope?current.current:positionForScope;const next={...previous,...patch,scope};current.current=next;setPosition(next);try{if(scope)sessionStorage.setItem(storageKey,JSON.stringify(next))}catch{}};
 return{ready:!!scope&&position.scope===scope,finishBeat:positionForScope.finish,storyPhase:positionForScope.story,nextFinish:()=>update({finish:1}),finishReview:()=>update({story:ENDING_STORY.invite}),nextStory:()=>update({story:Math.min(ENDING_STORY.invite,positionForScope.story+1)})};
}
