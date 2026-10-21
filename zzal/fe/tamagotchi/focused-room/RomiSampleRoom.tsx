'use client';
import{useEffect,useMemo,useRef,useState}from'react';
import{FocusedRoomView}from'./FocusedRoom';
import{LiveProvider,type Live}from'../yeoul/useHatch';
import{createRoomChatBridge,type RoomChatSource}from'../../lib/roomChatBridge';
import type{PetDetail,ChatState,ChatHistoryTurn}from'../../lib/pet';
import type{GameState,GuessResult,Side}from'../../lib/game';
import{useZzalLocale}from'../../lib/locale';
import{romiSampleMotions}from'./romiSampleAssets';
const SAMPLE_COPY={
 ko:{greeting:'안녕, 나는 로미야. 같이 조금 놀아 볼래?',back:'내 알로 돌아가기',ready:'준비됐어',notice:'로미 미리보기 · 대화는 준비된 예시예요.',reply:'얘기해 줘서 고마워. 조금 더 같이 놀자.',startGame:'먼저 놀이를 시작해 주세요.'},
 en:{greeting:'Hi, I’m Romi. Want to play together for a bit?',back:'Back to my egg',ready:'I’m ready',notice:'Romi preview · Replies are prepared examples.',reply:'Thanks for telling me. Let’s play a little longer.',startGame:'Start a game first.'},
 ja:{greeting:'こんにちは、ロミだよ。少し一緒に遊ばない？',back:'自分の卵に戻る',ready:'準備できたよ',notice:'ロミのプレビュー・会話は用意された例です。',reply:'話してくれてありがとう。もう少し一緒に遊ぼう。',startGame:'先にゲームを始めてください。'},
}as const;
/** A local preview, isolated from the signed-in owner and all pet/chat endpoints. */
export default function RomiSampleRoom({onBack,onReady,previewBackgroundUrl}:{onBack:()=>void;onReady?:()=>void;previewBackgroundUrl?:string}){
 const locale=useZzalLocale(),words=SAMPLE_COPY[locale];
 const[pet,setPet]=useState(()=>({petId:-1,name:'로미',phase:'ALIVE',ready:true,hatchedAt:'2026-10-17T00:00:00Z',serverNow:new Date().toISOString(),daysTogether:1,background:'studio',clock:{sleeping:false,canSleep:true,canWake:false},gauges:{fullness:2,happiness:3,clean:3,trash:3},food:{count:3},today:{snacks:0,bathDone:false},sick:null,trip:null,firstIntro:{version:4,key:'COMPLETE',active:false,completed:true},attendanceRewards:{attendanceDay:0,ownedRewards:[],background:{selectedKey:'studio',owned:[{key:'studio',url:previewBackgroundUrl||'/zzal/romi-sample/studio.png'}]}},motions:romiSampleMotions.map((m,i)=>({seq:i+1,key:m.key,label:m.label,unlocked:true,layer:m.set==='basic8'?'BASIC_1':'BASIC_2',basicImageKey:m.url,advanced:{status:'NONE',imageKey:null}}))} as unknown as PetDetail));
 const[chat,setChat]=useState<ChatState>({openSlot:'CONTINUOUS',calls:[],memories:[],session:{id:-1,slot:'CONTINUOUS',kind:'CONTINUOUS',round:0,maxRounds:null,closed:false,closeReason:null},turns:[],stageSpeech:{turnIdx:0,line:words.greeting}}),[game,setGame]=useState<GameState>({playing:false,gameId:null,kind:null,round:null,hits:null,rounds:5,winAt:3,remainingToday:3,justUnlocked:[],runUnlocked:false});
 useEffect(()=>{if(previewBackgroundUrl)setPet(p=>({...p,background:'studio',attendanceRewards:{...p.attendanceRewards!,background:{...p.attendanceRewards!.background,selectedKey:'studio',owned:[{key:'studio',url:previewBackgroundUrl}]}}}))},[previewBackgroundUrl]);
 const current=useRef({pet,chat,game});current.current={pet,chat,game};const history=useRef<ChatHistoryTurn[]>([]),answer=useRef<Side>('LEFT');
 useEffect(()=>{if(history.current.length===0)setChat(c=>c.stageSpeech?{...c,stageSpeech:{...c.stageSpeech,line:words.greeting}}:c)},[words.greeting]);
 const live=useMemo(()=>({petId:-1,pet,chat,game,ready:true,petReady:true,chatting:false,chatPresenceRevision:0,chatDelivery:{status:'idle',text:'',message:null},img:(key:string)=>current.current.pet.motions?.find(m=>m.key===key)?.basicImageKey??null,refreshPet:async()=>{},putDetail:setPet,
 doCare:async(action:string)=>{setPet(p=>({...p,gauges:{...p.gauges!,trash:action==='CLEAN'?Math.max(0,(p.gauges?.trash??0)-1):action==='BATH'?0:p.gauges?.trash??0},today:{...p.today!,bathDone:action==='BATH'||!!p.today?.bathDone}}));return{ok:true,message:null}},
 doRest:async()=>{setPet(p=>({...p,clock:{...p.clock!,sleeping:!p.clock?.sleeping,canSleep:!!p.clock?.sleeping,canWake:!p.clock?.sleeping}}));return{ok:true,message:null}},
 startPlay:async()=>{if(!current.current.game.playing){answer.current=Math.random()<.5?'LEFT':'RIGHT';setGame(g=>({...g,playing:true,kind:'LEFT_RIGHT',gameId:Date.now(),round:0,hits:0}))}return null},
 pickSide:async(side:Side)=>{const g=current.current.game;if(!g.playing)return{error:words.startGame,result:null};const round=g.round??0,hit=side===answer.current,hits=(g.hits??0)+Number(hit),finished=hits===3||round+1-hits===3||round===4;const result:GuessResult={gameId:g.gameId!,round,pick:side,answer:answer.current,hit,hits,finished,win:finished?hits>=3:null,nextRound:finished?null:round+1,rounds:5,winAt:3,remainingToday:3,justUnlocked:[],runUnlocked:false};answer.current=Math.random()<.5?'LEFT':'RIGHT';setGame({...g,round:result.nextRound,hits,playing:!finished});return{error:null,result}},
 changeBackground:async(key:string)=>setPet(p=>({...p,background:key})),
 sendChat:async(text:string)=>{const line=words.reply,at=new Date().toISOString(),idx=history.current.length;history.current.push({id:idx+1,sessionId:-1,idx,speaker:'USER',line:text,at},{id:idx+2,sessionId:-1,idx:idx+1,speaker:'PET',line,at});setChat(c=>({...c,stageSpeech:null,turns:[...(c.turns??[]),{idx,speaker:'USER',type:null,line:text,motion:null,generator:null,filteredReason:null},{idx:idx+1,speaker:'PET',type:'CONTINUE',line,motion:'reply',generator:'LOCAL_SAMPLE',filteredReason:null}]}));return{error:null,reply:{line,reactionKey:'reply'}}},
 shareMotion:async()=>({error:null,url:null}),clearJustUnlocked:()=>{},justUnlocked:[],careing:null,resting:false,guessing:false
 } as unknown as Live),[pet,chat,game,words]);
 const factory=useMemo(()=> (source:RoomChatSource)=>createRoomChatBridge(source,{pageUrl:'http://local-sample/',apiBase:''},{persona:async()=>({authoredPersona:{version:1,fields:[]},callMe:'사용자님',addressStatus:'DEFERRED',personaSourceHash:'LOCAL_SAMPLE'}),meeting:async()=>({version:1,state:'COMPLETE',callMe:null,addressStatus:'DEFERRED',discoveryLine:'',greetingLine:'',greetingRetryAllowed:false}),history:async()=>({turns:[...history.current],nextBeforeId:null}),legacyHistory:async()=>({calls:[],nextBeforeId:null})}),[]);
 return <div className="fr-sample-room" data-local-sample><LiveProvider value={live}><FocusedRoomView live={live} bridgeFactory={factory} sampleMode/></LiveProvider><div className="fr-sample-controls"><button onClick={onBack}>{words.back}</button>{onReady&&<button onClick={onReady}>{words.ready}</button>}<p>{words.notice}</p></div></div>;
}
