'use client';
import { useEffect, useRef, useState } from 'react';
import {claimCarePlayback} from '../../lib/careDelivery';
import type { Live } from '../yeoul/useHatch';
import type { CareAction } from '../../lib/pet';
import type { Side, GuessResult } from '../../lib/game';
import type { ZzalLocale } from '../../lib/locale';
import { CYCLE_MS, cyclesOfAction, situationOfAction, poseOfSituation, stagePlanOf, stageAt, type ActionKey } from '../props/situations';
import { SITUATION_TABLE } from '../props/table';
import { focusedCopy, fallbackIntroLine, focusedGameLine, endingFallbackLine } from './focusedCopy';
import {useTutorialEnding,ENDING_STORY} from './useTutorialEnding';
import type {TutorialScene} from '../../lib/tutorialStageSpeech';
import {useFocusedStageSpeech} from './useFocusedStageSpeech';
import {selectOwnedChatResource,sameChatResource,type FocusedChatResource} from './focusedChatMotion';
import{selectCarePlayback}from'./focusedCareMotion';
import type{PetDetail}from'../../lib/pet';
export type FocusedPanel = 'care'|'game'|'attendance'|'review'|'collection-hub'|'collection'|'journal'|'background'|'info'|'help'|null;
const careAction:Record<string,ActionKey>={FEED:'feed_rice',SNACK:'feed_snack',CLEAN:'clean',BATH:'bath',PET:'pet',MEDICINE:'medicine'};
const actionCare:Partial<Record<ActionKey,CareAction>>={feed_rice:'FEED',feed_snack:'SNACK',clean:'CLEAN',bath:'BATH',pet:'PET',medicine:'MEDICINE'};
const custom:Partial<Record<ActionKey,string>>={feed_rice:'eat_rice',feed_snack:'eat_snack',clean:'sweep',bath:'wash',pet:'petted',wake:'wake_up',reply:'reply',game_choose:'startle'};
export type FocusedVisual={custom?:FocusedChatResource;pose:string;situation:string|null;stage:number;prop:string|null;signal:string|null};
const idle:FocusedVisual={pose:'base',situation:null,stage:0,prop:null,signal:null};
/** Only server responses advance an intro; visual timers are never completion receipts. */
export function useFocusedExperience(live:Live,locale:ZzalLocale,sampleMode=false){
 const t=focusedCopy(locale),owner=useRef(live);owner.current=live;
 const [panel,setPanel]=useState<FocusedPanel>(null),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null),[visual,setVisual]=useState(idle);
 const [result,setResult]=useState<GuessResult|null>(null),[marks,setMarks]=useState<Record<number,boolean>>({});
 const [gameScore,setGameScore]=useState({tries:0,hits:0});
 const [gamePhase,setGamePhase]=useState<'ready'|'choosing'|'reveal'|'done'>('ready'),[selected,setSelected]=useState<Side|null>(null);
 const [trashOverride,setTrashOverride]=useState<number|null>(null);
 const [graceSync,setGraceSync]=useState(false);
 const graceSyncRef=useRef(false);graceSyncRef.current=graceSync;
 const [graceNotice,setGraceNotice]=useState(false),[sleepFarewell,setSleepFarewell]=useState(false);
 const observedDeadline=useRef<{petId:number|null;deadline:string}|null>(null);
 const [closingReview,setClosingReview]=useState(false);
 const lock=useRef(false),gameEpoch=useRef(0),epoch=useRef(0),cancelVisual=useRef<(()=>void)|null>(null),timers=useRef(new Set<ReturnType<typeof setTimeout>>()),mounted=useRef(true);
 const chatPlayback=useRef<{id:string;start:()=>void;fail:()=>void}|null>(null),playedReplies=useRef(new Set<string>()),playedCareResponses=useRef(new Set<string>()),careAuthority=useRef<{before:PetDetail|null;response:PetDetail}|null>(null);
 const petId=live.petId,serverIntro=live.pet?.firstIntro;
 useEffect(()=>{setMarks({});setResult(null);setGameScore({tries:live.game?.round??0,hits:live.game?.hits??0});},[petId,live.game?.gameId]);
 const guided=serverIntro?.version===4&&serverIntro.key!=='COMPLETE';
 // Follow the authoritative step immediately, including while its action animation finishes.
 const key=guided?serverIntro?.key:undefined;
 const ending=useTutorialEnding(petId,serverIntro?.key,sampleMode);
 const farewell=!sampleMode&&key==='REVIEW_SEEN'&&ending.storyPhase>=0;
 const sleeping=!!live.pet?.clock?.sleeping,traveling=!!live.pet?.trip;
 const valid=(stamp:number,id:number|null)=>mounted.current&&epoch.current===stamp&&owner.current.petId===id;
 useEffect(()=>{if(sleeping||traveling){gameEpoch.current++;cancelVisual.current?.();setPanel(null);}},[sleeping,traveling]);

 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;epoch.current++;cancelVisual.current?.();for(const timer of timers.current)clearTimeout(timer);};},[]);
 useEffect(()=>{setTrashOverride(null);epoch.current++;lock.current=false;cancelVisual.current?.();for(const timer of timers.current)clearTimeout(timer);timers.current.clear();setBusy(false);setPanel(null);setVisual(idle);setError(null);setClosingReview(false);setSleepFarewell(false);setGraceSync(false);observedDeadline.current=null;setResult(null);setMarks({});setGamePhase('ready');},[petId]);
 const animate=(action:ActionKey,cycleOverride?:number,forceBasic=false)=>{
  // Compatibility for existing semantic-key catalog assets only; never infer generated multi-set metadata here.
  cancelVisual.current?.();if(document.hidden)return Promise.resolve();const l=owner.current;const owned=!forceBasic&&custom[action]&&l.pet?.motions?.some(m=>m.key===custom[action]&&m.unlocked&&m.basicImageKey);
  const sit=situationOfAction(action,!!owned),pose=(sit&&poseOfSituation(SITUATION_TABLE,sit))||((action==='reply'||action==='wake'||action==='game_choose')?'base':action==='game_win'?'joy':action==='game_lose'?'sad':'base');
  const plan=sit?stagePlanOf(SITUATION_TABLE,sit):null;
  const cycles=Math.max(1,Math.round(cycleOverride??cyclesOfAction(SITUATION_TABLE,action,!!owned)));const duration=cycles*CYCLE_MS;
  return new Promise<void>(resolve=>{let ended=false;const local=new Set<ReturnType<typeof setTimeout>>();const end=()=>{if(ended)return;ended=true;for(const id of local){clearTimeout(id);timers.current.delete(id);}setVisual(idle);if(cancelVisual.current===end)cancelVisual.current=null;resolve();};cancelVisual.current=end;
   const show=(cycle:number)=>{const beat=plan?stageAt(plan,cycle):null;setVisual({pose:l.img(pose)?pose:'base',situation:beat?.sweeping?null:sit,stage:beat?.stage??0,prop:plan?.prop??null,signal:beat?.sweeping?plan?.signal??null:null});};show(0);
   for(let cycle=1;cycle<cycles;cycle++){const id=setTimeout(()=>{local.delete(id);timers.current.delete(id);if(!ended)show(cycle)},cycle*CYCLE_MS);local.add(id);timers.current.add(id)}
   const id=setTimeout(end,duration);local.add(id);timers.current.add(id);
  });
 };
 useEffect(()=>{const hidden=()=>{if(document.hidden){cancelVisual.current?.();}};document.addEventListener('visibilitychange',hidden);return()=>document.removeEventListener('visibilitychange',hidden);},[]);
 const beginResourcePlayback=(resource:FocusedChatResource)=>new Promise<void>(resolve=>{
  const stamp=epoch.current,id=owner.current.petId;cancelVisual.current?.();lock.current=true;setBusy(true);setError(null);
  let ended=false,started=false,timer:ReturnType<typeof setTimeout>|undefined;
  const end=(failed=false)=>{if(ended)return;ended=true;if(timer){clearTimeout(timer);timers.current.delete(timer)}if(chatPlayback.current===job)chatPlayback.current=null;if(cancelVisual.current===cancel)cancelVisual.current=null;careAuthority.current=null;if(valid(stamp,id)){setVisual(idle);lock.current=false;setBusy(false);if(failed)setError(locale==='ko'?'선택된 동작을 불러오지 못했어요.':locale==='en'?'Could not load the selected motion.':'選ばれた動きを読み込めませんでした。')}resolve();};
  const cancel=()=>end();
  const job={id:resource.id,start:()=>{if(ended||started||!valid(stamp,id))return;started=true;timer=setTimeout(()=>end(),CYCLE_MS);timers.current.add(timer)},fail:()=>end(true)};
  chatPlayback.current=job;cancelVisual.current=cancel;setVisual({pose:'base',situation:null,stage:0,prop:null,signal:null,custom:resource});
 });
 const playChatMotion=(reactionKey:unknown,receiptId:string)=>{
  const l=owner.current;
  if(!receiptId||playedReplies.current.has(receiptId)||lock.current||(l.pet?.firstIntro?.version===4&&l.pet.firstIntro.key!=='COMPLETE')||graceSyncRef.current||l.pet?.clock?.sleeping||l.pet?.trip||document.hidden)return false;
  const resource=selectOwnedChatResource(l.pet,reactionKey);if(!resource)return false;
  playedReplies.current.add(receiptId);if(playedReplies.current.size>64)playedReplies.current.delete(playedReplies.current.values().next().value!);
  void beginResourcePlayback(resource);return true;
 };
 const chatMotionReady=(id:string,failed=false)=>{const job=chatPlayback.current;if(job?.id!==id)return;if(failed)job.fail();else job.start()};
 useEffect(()=>{playedReplies.current.clear();playedCareResponses.current.clear();careAuthority.current=null},[petId]);
 useEffect(()=>{const resource=visual.custom,authority=careAuthority.current;
  // The accepted response is authoritative before its setPet commit; later snapshots can revoke it.
  if(authority&&live.pet===authority.before&&live.pet!==authority.response)return;
  if(resource&&!sameChatResource(resource,selectOwnedChatResource(live.pet,resource.id)))cancelVisual.current?.();
 },[live.pet,visual.custom]);
 const open=(next:FocusedPanel)=>{if(lock.current)return;setError(null);setPanel(next);};
 const close=()=>{if(lock.current)return;if(panel==='game')gameEpoch.current++;cancelVisual.current?.();setPanel(null);setError(null);};
 // Start dismissal after the final reaction, never while a guess/animation still owns the lock.
 useEffect(()=>{
  if(panel!=='game'||gamePhase!=='done'||busy||sleeping||traveling)return;
  const stamp=epoch.current,id=petId,gameStamp=gameEpoch.current;
  const timer=setTimeout(()=>{if(valid(stamp,id)&&gameEpoch.current===gameStamp&&!lock.current)close()},2500);
  return()=>clearTimeout(timer);
 },[panel,gamePhase,busy,petId,live.game?.gameId,sleeping,traveling]);
 const retryingCare=useRef(false);
 const run=async(call:()=>ReturnType<Live['doCare']>,action?:ActionKey)=>{
  if(lock.current||traveling)return;const stamp=epoch.current,id=petId;lock.current=true;setBusy(true);setError(null);setPanel(null);let unresolved=false;
  try{const receipt=await call();if(!valid(stamp,id))return;if(!receipt.ok){unresolved=receipt.uncertain===true;setError(receipt.message||t.error);return;}if(receipt.careReceipt?.status==='NO_OP')return;if(receipt.presentationAccepted===false||document.hidden||owner.current.pet?.clock?.sleeping||owner.current.pet?.trip||receipt.responsePet?.clock?.sleeping||receipt.responsePet?.trip)return;if(receipt.careReceipt&&(!id||!claimCarePlayback(id,receipt.careReceipt.requestId)))return;
   const careKind=action? actionCare[action]:undefined;
   if(action&&careKind&&receipt.careReceipt){
    const selected=selectCarePlayback(receipt.responsePet,receipt.motionPlayback,id,careKind);
    if(!selected||!receipt.playbackReceiptId||playedCareResponses.current.has(receipt.playbackReceiptId))return;
    playedCareResponses.current.add(receipt.playbackReceiptId);if(playedCareResponses.current.size>64)playedCareResponses.current.delete(playedCareResponses.current.values().next().value!);
    if(selected.kind==='basic')await animate(action,undefined,true);
    else{const response=receipt.responsePet!;const before=owner.current.pet;const playing=beginResourcePlayback(selected.resource);careAuthority.current={before,response};await playing;}
   }else if(action)await animate(action);}
  catch(e){if(valid(stamp,id))setError(e instanceof Error?e.message:t.error)}finally{if(valid(stamp,id)&&!unresolved){lock.current=false;setBusy(false);}}
 };
 const careUncertain=live.careDelivery?.status==='uncertain';
 useEffect(()=>{if(careUncertain){lock.current=true;setBusy(true);setError(live.careDelivery?.message??t.error)}},[petId,careUncertain,live.careDelivery?.requestId]);
 const retryCare=async()=>{const pending=owner.current.careDelivery;if(pending?.status!=='uncertain'||retryingCare.current)return;retryingCare.current=true;lock.current=false;try{await run(()=>owner.current.doCare(pending.action),careAction[pending.action]);}finally{retryingCare.current=false}};
 const care=(action:CareAction)=>{
  if(lock.current||traveling)return Promise.resolve();
  return run(()=>owner.current.doCare(action),careAction[action]);
 };
 const rest=()=>run(()=>owner.current.doRest(),sleeping?'wake':undefined);
 const startGame=async()=>{if(lock.current||owner.current.pet?.clock?.sleeping||owner.current.pet?.trip)return;gameEpoch.current++;const stamp=epoch.current,id=petId;lock.current=true;setBusy(true);setError(null);try{const message=await owner.current.startPlay();if(!valid(stamp,id))return;if(message)setError(message);else{setPanel('game');setResult(null);setSelected(null);setGamePhase('ready');setGameScore({tries:owner.current.game?.round??0,hits:owner.current.game?.hits??0});if(!owner.current.game?.playing)setMarks({});}}catch(e){if(valid(stamp,id))setError(e instanceof Error?e.message:t.error)}finally{if(valid(stamp,id)){lock.current=false;setBusy(false)}}};
 const choose=async(side:Side)=>{if(lock.current||!owner.current.game?.playing||owner.current.pet?.clock?.sleeping||owner.current.pet?.trip)return;const stamp=epoch.current,id=petId,gameStamp=gameEpoch.current;lock.current=true;setBusy(true);setError(null);setSelected(side);setGamePhase('choosing');
  try{const reply=await owner.current.pickSide(side);if(!valid(stamp,id)||gameEpoch.current!==gameStamp)return;if(!reply.result){setError(reply.error||t.error);setGamePhase('ready');setSelected(null);return;}
   const r=reply.result;setResult(r);setGameScore({tries:r.round+1,hits:r.hits});setMarks(old=>({...old,[r.round]:r.hit}));setGamePhase('reveal');const userWon=r.finished?(r.win??r.hit):r.hit;await animate(userWon?'game_lose':'game_win',1,true);if(!valid(stamp,id)||gameEpoch.current!==gameStamp)return;setGamePhase(r.finished?'done':'ready');if(!r.finished){setResult(null);setSelected(null);}
  }catch(e){if(valid(stamp,id)&&gameEpoch.current===gameStamp){setError(e instanceof Error?e.message:t.error);setGamePhase('ready');setSelected(null)}}finally{if(valid(stamp,id)){lock.current=false;setBusy(false);}}
 };
 const acknowledge=async(topic:'ATTENDANCE_INTRO'|'REVIEW_SEEN')=>{const stamp=epoch.current,id=petId;if(lock.current)return false;lock.current=true;setBusy(true);setError(null);try{const r=await owner.current.tutorialSeen(topic);if(!valid(stamp,id))return false;if(!r.ok){setError(r.message||t.error);return false;}setPanel(null);return true;}catch(e){if(valid(stamp,id))setError(e instanceof Error?e.message:t.error);return false;}finally{if(valid(stamp,id)){lock.current=false;setBusy(false)}}};
 const finish=async()=>{if(lock.current)return;const stamp=epoch.current,id=petId;lock.current=true;setBusy(true);setError(null);try{const r=await owner.current.finishTutorial();if(!valid(stamp,id))return;if(!r.ok){setError(r.message||t.error);return;}setPanel(null);}catch(e){if(valid(stamp,id))setError(e instanceof Error?e.message:t.error)}finally{if(valid(stamp,id)){lock.current=false;setBusy(false)}}};
 const nextFinish=()=>{if(lock.current||key!=='FINISH_NOTICE')return;if(ending.finishBeat===0)ending.nextFinish();else void finish()};
 const dismissReview=()=>{setPanel(null);if(serverIntro?.key==='REVIEW_SEEN'&&!sampleMode&&ending.storyPhase===ENDING_STORY.review)ending.finishReview()};
 useEffect(()=>{if(sampleMode||serverIntro?.version!==4||serverIntro.key!=='REVIEW_SEEN'||!ending.ready)return;setPanel(ending.storyPhase===ENDING_STORY.review?'review':null)},[petId,serverIntro?.version,serverIntro?.key,ending.ready,ending.storyPhase,sampleMode]);
 const nextStory=async()=>{if(lock.current||!farewell||ending.storyPhase===ENDING_STORY.review||sleeping||traveling||owner.current.pet?.phase!=='ALIVE')return false;if(ending.storyPhase<ENDING_STORY.invite){ending.nextStory();return true;}const stamp=epoch.current,id=petId;setClosingReview(true);const confirmed=await acknowledge('REVIEW_SEEN');if(valid(stamp,id))setClosingReview(false);return confirmed&&valid(stamp,id)};
 // The clock only schedules a read/notice. The server alone grants chat or sleep.
 const grace=live.pet?.firstNightChatGrace;
 useEffect(()=>{
  if(!grace?.active||!grace.deadlineAt||sleeping||traveling){setGraceNotice(false);setGraceSync(false);return;}
  const deadline=Date.parse(grace.deadlineAt),server=Date.parse(live.pet?.serverNow??'');
  if(!Number.isFinite(deadline)||!Number.isFinite(server))return;
  const id=petId,started=performance.now(),noticeKey=`zzal-focused-grace-notice:${id}:${grace.deadlineAt}`;let read=false,armed=deadline>server&&!document.hidden;
  const tick=()=>{if(document.hidden||owner.current.petId!==id)return;const remaining=deadline-server-(performance.now()-started);
   if(remaining<=0){setGraceSync(true);if(!read){read=true;if(armed&&grace.willSleepAtEnd)observedDeadline.current={petId:id,deadline:grace.deadlineAt!};void owner.current.refreshPet();}return;}
   if(remaining<=60_000&&grace.willSleepAtEnd){let shown=false;try{shown=sessionStorage.getItem(noticeKey)==='1';if(!shown)sessionStorage.setItem(noticeKey,'1');}catch{}if(!shown)setGraceNotice(true);}
  };
  const visibility=()=>{if(document.hidden){armed=false;observedDeadline.current=null;}else if(deadline-server-(performance.now()-started)>0)armed=true;tick();};
  const offline=()=>{armed=false;observedDeadline.current=null;};
  tick();const timer=setInterval(tick,1000);document.addEventListener('visibilitychange',visibility);window.addEventListener('offline',offline);
  return()=>{clearInterval(timer);document.removeEventListener('visibilitychange',visibility);window.removeEventListener('offline',offline)};
 },[petId,grace?.active,grace?.deadlineAt,grace?.willSleepAtEnd,live.pet?.serverNow,sleeping,traveling]);
 useEffect(()=>{
  const due=observedDeadline.current;
  if(!due||due.petId!==petId||grace?.active||!grace?.endedAt)return;
  observedDeadline.current=null;
  if(!sleeping||document.hidden||traveling||Date.parse(grace.endedAt)!==Date.parse(due.deadline))return;
  // Presentation after authoritative sleep, never an awake extension.
  setSleepFarewell(true);const timer=setTimeout(()=>setSleepFarewell(false),2400);
  const hide=()=>{if(document.hidden)setSleepFarewell(false)};document.addEventListener('visibilitychange',hide);
  return()=>{clearTimeout(timer);document.removeEventListener('visibilitychange',hide)};
 },[petId,sleeping,traveling,grace?.active,grace?.endedAt]);
 const currentTarget=busy?null:key==='ATTENDANCE_INTRO'?(panel==='attendance'?'attendance-ack':'attendance-menu'):key==='FINISH_NOTICE'?'finish':key==='REVIEW_SEEN'?null:panel==='care'?(key==='FEED'?'FEED':key==='SNACK'?'SNACK':key==='CLEAN'?'CLEAN':key==='BATH'?'BATH':key==='GAME'?'GAME':key==='NAP_SLEEP'||key==='NAP_WAKE'?'REST':null):panel==='game'?null:key?'care':null;
 const gameLine=panel==='game'?(result&&(gamePhase==='reveal'||gamePhase==='done')?focusedGameLine(locale,result):locale==='en'?'Which hand did I hide it in?':locale==='ja'?'どっちの手に隠したかな？':'어느 손에 숨겼을까?'):null;
 const stage=useFocusedStageSpeech(live,locale,key,serverIntro?.key,!!guided&&panel!=='game'&&key!=='FINISH_NOTICE'&&key!=='REVIEW_SEEN',fallbackIntroLine(key,locale),!busy&&!gameLine&&!farewell);
 const endingScene:TutorialScene|undefined=key==='FINISH_NOTICE'?(ending.finishBeat===0?'FINISH_THANKS':'FINISH_TOMORROW'):farewell?(ending.storyPhase===ENDING_STORY.intro?'CHAT_INTRO':ending.storyPhase===ENDING_STORY.memory?'CHAT_MEMORY':ending.storyPhase===ENDING_STORY.invite?'CHAT_INVITE':undefined):undefined;
 const endingSpeech=useFocusedStageSpeech(live,locale,key,serverIntro?.key,!!endingScene&&!panel,endingFallbackLine(endingScene,locale,!!live.pet?.attendanceRewards?.background.preview),!busy&&!gameLine&&!panel,endingScene);
 const speech=gameLine??(endingScene?endingSpeech.line:farewell?null:!sleeping&&guided?stage.line:null);
 const speechSource=gameLine?'SERVICE_FALLBACK':endingScene?endingSpeech.source:stage.source;
 return{careUncertain,retryCare,visualTrash:trashOverride??live.pet?.gauges?.trash??0,graceSync,sleepFarewell,graceNotice,gameScore,panel,open,close,busy,error,visual,key,guided,currentTarget,care,rest,startGame,choose,result,marks,gamePhase,selected,acknowledge,finishBeat:ending.finishBeat,nextFinish,dismissReview,farewell,storyPhase:ending.storyPhase,nextStory,closingReview,speech,speechSource,stageStatus:endingScene?endingSpeech.status:stage.status,animate,playChatMotion,chatMotionReady};
}
export type FocusedExperience=ReturnType<typeof useFocusedExperience>;
