'use client';

import { useEffect, useLayoutEffect, useRef, useState, useId, type CSSProperties, type FormEvent } from 'react';
import { createProductRoomChatBridge, type RoomChatBridge, type RoomChatSnapshot, type RoomChatSource } from '../../lib/roomChatBridge';
import { assetUrl } from '../../lib/assets';
import { useSpriteGeometry } from '../../lib/spriteGeometry';
import type { Live } from '../yeoul/useHatch';
import './focused-room.css';
import './korean-font-fix.css';
import './compact-menu.css';
import LanguageSelector from '../../LanguageSelector';
import { useOpenAnnouncements } from '../announcements/AnnouncementsGate';
import { useZzalLocale } from '../../lib/locale';
import { CHAT_MAX_CHARS } from '../rules';
import { FLOOR_DIRT_ASSETS } from '../floorDirtAssets';
import { useFocusedAnalytics } from './useFocusedAnalytics';
import { useFocusedExperience } from './useFocusedExperience';
import { FocusedPanels } from './FocusedPanels';
import { FocusedProps } from './FocusedProps';
import FocusedTiles from './FocusedTiles';
import { focusedCopy } from './focusedCopy';
import {RomiChatExample,isRomiMotionPhase} from './RomiChatExample';
import {ENDING_STORY} from './useTutorialEnding';
import { useAnchors } from '../props/anchors';
import FocusedInquiry from './FocusedInquiry';
import {interactionMetrics} from './interactionMetrics';
import SceneShare from '../yeoul/SceneShare';
import {useFocusedGeometry} from './useFocusedGeometry';
import ReturningGuide,{useReturningGuide} from '../announcements/ReturningGuide';
import {returningCopy} from '../announcements/returningGuideState';
import PersonaEditor from '../yeoul/PersonaEditor';

type FocusedLive = Live;
const blocks: Record<string, string> = {
  origin: '같은 서비스 주소에서 다시 열어 주세요.', loading: '대화 상태를 확인하고 있어요…',
  'not-alive': '아이가 방에 올 준비를 하고 있어요.', meeting: '먼저 첫 만남을 마쳐 주세요.',
  sleeping: '자는 중에는 대화할 수 없어요.', traveling: '여행 중에는 대화할 수 없어요.',
  closed: '지금은 대화를 이어갈 수 없어요.',
};
const dateTime = (value: string,locale:string) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Seoul' });
};

/** The service root owns useHatchState. This view never creates presence/polling/UUID owners. */
export default function FocusedRoom({ live }: { live: Live }) {
  return <FocusedRoomView live={live} />;
}

/** Factory injection is for an isolated test harness; the product route uses cookie-authenticated APIs. */
export function FocusedRoomView({ live, bridgeFactory = createProductRoomChatBridge, sampleMode=false }: {
  live: FocusedLive; sampleMode?:boolean; bridgeFactory?: (source: RoomChatSource) => RoomChatBridge;
}) {
  const locale=useZzalLocale(),t=focusedCopy(locale),x=useFocusedExperience(live,locale,sampleMode);
  const openAnnouncements=useOpenAnnouncements();
  const returning=useReturningGuide(live.pet,sampleMode),returningWords=returningCopy[locale];
  const [settingsOwner,setSettingsOwner]=useState<number|null>(null);
  const [focusAfterSettings,setFocusAfterSettings]=useState(false);
  const settingsOpen=settingsOwner!==null&&settingsOwner===live.petId;
  useEffect(()=>{setSettingsOwner(null);setFocusAfterSettings(false)},[live.petId]);
  const metrics=useFocusedAnalytics(live,sampleMode);
  const [returnError,setReturnError]=useState<string|null>(null);
  const returnLock=useRef(false),returnOwner=useRef(live.petId);returnOwner.current=live.petId;
  useEffect(()=>{setReturnError(null);},[live.petId]);
  const callBack=async()=>{
    if(returnLock.current||live.calling||!live.pet?.trip)return;
    const id=live.petId;returnLock.current=true;setReturnError(null);
    try{const result=await live.callBack();if(returnOwner.current===id&&!result.ok)setReturnError(result.message||t.error);}
    catch(error){if(returnOwner.current===id)setReturnError(error instanceof Error?error.message:t.error);}
    finally{returnLock.current=false;}
  };
  const owner = useRef(live); owner.current = live;
  const bridgeRef = useRef<RoomChatBridge | null>(null);
  const [storedSnapshot, setSnapshot] = useState<RoomChatSnapshot | null>(null);
  const interaction=interactionMetrics(live,sampleMode);
  useEffect(()=>interaction.shown('room'),[live.petId,sampleMode]);
  const [drawer, setDrawer] = useState<'history' | 'menu' | null>(null);
  const [inquiryOpen,setInquiryOpen]=useState(false);
  const [shareOpen,setShareOpen]=useState(false),[exampleReady,setExampleReady]=useState(false),[sleepNotice,setSleepNotice]=useState<string|null>(null);
  const sleepNoticeTimer=useRef<ReturnType<typeof setTimeout>|null>(null);
  const scene=useRef<HTMLDivElement>(null);
  const [legacy, setLegacy] = useState(false);
  const [reply, setReply] = useState<{ petId: number; sessionId: number | null; idx: number; line: string } | null>(null);
  const [size, setSize] = useState({ width: 390, height: 700 });
  const initialHeight=useRef(typeof window==='undefined'?844:window.innerHeight);
  const host = useRef<HTMLElement>(null), input = useRef<HTMLInputElement>(null),dock=useRef<HTMLElement>(null);
  const [dockBudget,setDockBudget]=useState({left:0,top:0,width:0,height:0});
  const sendLock=useRef(false);
  const inviteHandoff=useRef<{petId:number;bridge:RoomChatBridge;chat:Live['chat'];ready:boolean}|null>(null);
  const [inviteTransfer,setInviteTransfer]=useState<{petId:number;waiting:boolean;error:string|null}|null>(null);
  const historyButton = useRef<HTMLButtonElement>(null), menuButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const menuPanel = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const returnFocus=useRef<'history'|'menu'|null>(null);
  // An open tile group keeps the composer live; only real playback locks the existing controls.
  useEffect(()=>{
    if(!x.busy)return;
    const nodes=[...host.current?.querySelectorAll<HTMLElement>('.fr-dock,.fr-header,[data-fr-actor]')??[]];
    const prior=nodes.map(node=>node.inert);nodes.forEach(node=>{node.inert=true});
    return()=>nodes.forEach((node,i)=>{node.inert=prior[i]||(node.matches('.fr-dock')&&host.current?.dataset.gameOpen==='true')});
  },[x.panel,x.busy]);

  useEffect(() => {
    const bridge = bridgeFactory(owner.current); bridgeRef.current = bridge;
    const paint = () => setSnapshot(bridge.getSnapshot());
    const unsubscribe = bridge.subscribe(paint); paint(); void bridge.loadContext();
    setReply(null); setDrawer(null);
    return () => { unsubscribe(); bridge.dispose(); if (bridgeRef.current === bridge) bridgeRef.current = null; };
  }, [live.petId, bridgeFactory]);
  useEffect(() => { bridgeRef.current?.update(live); }, [live]);
  useLayoutEffect(() => {
    const node = host.current; if (!node) return;
    const measure = () => { const r = node.getBoundingClientRect(); setSize({ width: r.width, height: r.height }); };
    measure(); const observer = new ResizeObserver(measure); observer.observe(node);
    return () => observer.disconnect();
  }, []);
  // Keep the full ordinary dock measurable while its controls are hidden during a game.
  useLayoutEffect(()=>{
    const node=dock.current,room=host.current;if(!node||!room)return;
    const measure=()=>{const bounds=node.getBoundingClientRect(),parent=room.getBoundingClientRect();const next={left:bounds.left-parent.left,top:bounds.top-parent.top,width:bounds.width,height:bounds.height};setDockBudget(previous=>Object.keys(next).every(key=>previous[key as keyof typeof next]===next[key as keyof typeof next])?previous:next)};
    measure();const observer=new ResizeObserver(measure);observer.observe(node);observer.observe(room);return()=>observer.disconnect();
  },[x.panel,x.guided,locale]);
  useEffect(() => {
    const viewport = window.visualViewport;
    const fit = () => { host.current?.style.setProperty('--fr-viewport', `${viewport?.height ?? window.innerHeight}px`); };
    fit(); viewport?.addEventListener('resize', fit);
    return () => viewport?.removeEventListener('resize', fit);
  }, []);
  useEffect(() => {
    if (drawer === 'menu') menuPanel.current?.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true });
    else if (drawer === 'history') closeButton.current?.focus({ preventScroll: true });
  }, [drawer]);

  const snapshot = storedSnapshot?.pet?.petId === live.petId ? storedSnapshot : null;
  const pet = live.pet;
  const sleeping = !!pet?.clock?.sleeping, traveling = !!pet?.trip;
  const returnToChat=()=>{returning.close();setSettingsOwner(null);setFocusAfterSettings(!sleeping&&!traveling);};
  const editSettings=()=>{interactionMetrics(live,sampleMode,'settings').attempt('settings_open');returning.close();setDrawer(null);x.close();setSettingsOwner(live.petId);};
  const showSleepNotice=(wakeLocked=false)=>{if(sleepNoticeTimer.current)clearTimeout(sleepNoticeTimer.current);setSleepNotice(wakeLocked?(locale==='en'?'You can wake them after 7 a.m.':locale==='ja'?'朝7時を過ぎると起こせます。':'아침 7시가 지나면 깨울 수 있어요.'):(locale==='en'?'This is unavailable while they are asleep.':locale==='ja'?'眠っている間はこの機能を使えません。':'자는 동안에는 이 기능을 사용할 수 없어요.'));sleepNoticeTimer.current=setTimeout(()=>setSleepNotice(null),2200)};
  useEffect(()=>{setSleepNotice(null);if(sleepNoticeTimer.current)clearTimeout(sleepNoticeTimer.current);return()=>{if(sleepNoticeTimer.current)clearTimeout(sleepNoticeTimer.current)}},[live.petId,sleeping,locale]);
  useEffect(()=>{setReply(null);},[live.chatPresenceRevision,live.chat?.session?.id,sleeping,traveling]);
  const pose = x.sleepFarewell ? 'base' : sleeping ? 'sleep' : pet?.sick ? 'sick' : x.visual.pose;
  const activeCustom=!sleeping&&!traveling?x.visual.custom:null;
  const {src,reference,geometry,compact,scale,foot,width,height,alphaLeft,alphaRight,left,top,actorStyle,backgroundUrl,customReady,customFailed,customAnchorMode}=useFocusedGeometry(live,size,pose,initialHeight.current,activeCustom);
  useEffect(()=>{if(!activeCustom)return;if(customFailed)x.chatMotionReady(activeCustom.id,true);else if(customReady)x.chatMotionReady(activeCustom.id)},[activeCustom?.id,customFailed,customReady]);
  const turns = snapshot?.chat?.turns ?? [];
  const latestPet = [...turns].reverse().find(turn => turn.speaker === 'PET');
  const activeReply = reply?.petId === live.petId && reply.sessionId === (snapshot?.chat?.session?.id ?? null) && (!latestPet || latestPet.idx <= reply.idx) ? reply.line : null;
  const roomBlocked=snapshot ? snapshot.blocked : 'loading';
  const line=(x.guided||x.panel==='game')?x.speech:!roomBlocked?(snapshot?.stageSpeech?.line||activeReply||null):null;
  const speechLeft = left + alphaRight + 8;
  const speechWidth = size.width - speechLeft - 12 >= 100 ? Math.min(230, size.width - speechLeft - 12) : size.width - 24;
  const speechTop = Math.max(size.height < 460 ? 72 : 92, Math.min(top + geometry.top * geometry.height * scale + 8, size.height - 320));
  const speechBudget = Math.max(1, Math.floor((size.height - 170 - speechTop - 72) / 28)) * Math.max(4, Math.floor((speechWidth - 28) / 14));
  const blocked = snapshot ? snapshot.blocked : 'loading';
  const uncertain = snapshot?.delivery.status === 'uncertain';
  const sending = !!snapshot?.sending;
  const inviteInput=x.farewell&&x.storyPhase===ENDING_STORY.invite&&!x.panel;
  const transfer=inviteTransfer?.petId===live.petId?inviteTransfer:null;
  const inviteRetry=!!transfer?.error;
  const composerBlocked=!!blocked&&!(blocked==='closed'&&(inviteInput||inviteRetry));
  useEffect(()=>{
    if(!focusAfterSettings||settingsOpen||returning.open)return;
    if(sleeping||traveling){setFocusAfterSettings(false);return;}
    if(composerBlocked||sending||uncertain||transfer?.waiting||x.busy||x.graceSync||x.panel==='game'||x.guided&&!inviteInput)return;
    const frame=requestAnimationFrame(()=>{if(input.current&&!input.current.disabled){input.current.focus({preventScroll:true});setFocusAfterSettings(false);}});
    return()=>cancelAnimationFrame(frame);
  },[focusAfterSettings,settingsOpen,returning.open,sleeping,traveling,composerBlocked,sending,uncertain,transfer?.waiting,x.busy,x.graceSync,x.panel,x.guided,inviteInput]);
  const canSend = !sleeping&&!traveling&&!x.graceSync && (!x.guided||inviteInput) && x.panel!=='game' && !x.busy && !composerBlocked && !sending && !transfer?.waiting && !!(uncertain ? snapshot?.delivery.text : snapshot?.draft)?.trim();

  useEffect(()=>{const refresh=(event:Event)=>{const id=(event as CustomEvent<{petId?:number}>).detail?.petId;if(id!=null&&id!==owner.current.petId)return;void bridgeRef.current?.loadContext();};window.addEventListener('zzal-persona-updated',refresh);window.addEventListener('pageshow',refresh);return()=>{window.removeEventListener('zzal-persona-updated',refresh);window.removeEventListener('pageshow',refresh)};},[]);
  const personaIdentity=JSON.stringify([live.petId,pet?.name,pet?.personality,pet?.world]);
  const priorPersona=useRef(personaIdentity);useEffect(()=>{if(priorPersona.current!==personaIdentity){priorPersona.current=personaIdentity;void bridgeRef.current?.loadContext();}},[personaIdentity]);

  useEffect(()=>{host.current?.querySelectorAll('[data-fr-pressed]').forEach(node=>node.removeAttribute('data-fr-pressed'));},[x.currentTarget,x.key,x.panel,drawer]);
  useEffect(()=>{const visibility=()=>{host.current?.setAttribute('data-fr-hidden',String(document.hidden));};visibility();document.addEventListener('visibilitychange',visibility);return()=>document.removeEventListener('visibilitychange',visibility);},[]);
  useEffect(()=>{if(drawer||!returnFocus.current)return;const was=returnFocus.current;returnFocus.current=null;const frame=requestAnimationFrame(()=>(was==='history'?historyButton:menuButton).current?.focus({preventScroll:true}));return()=>cancelAnimationFrame(frame);},[drawer]);
  const closeDrawer = () => { returnFocus.current=drawer;setDrawer(null); };
  useEffect(() => {
    if (drawer !== 'menu') return;
    const dismiss = (event: PointerEvent | FocusEvent) => {
      const target = event.target;
      if (!(target instanceof Node) || menuPanel.current?.contains(target) || menuButton.current?.contains(target)) return;
      // Preserve the outside control's natural focus/action; only Escape explicitly restores focus.
      returnFocus.current = null;
      setDrawer(null);
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('focusin', dismiss);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('focusin', dismiss); };
  }, [drawer]);
  const deliver=async(bridge:RoomChatBridge,petId:number)=>{
    try{
    const result = await bridge?.send();
    interaction.result('chat_send',result?.sent&&!result.error?'success':bridge.getSnapshot().delivery?.status==='uncertain'?'uncertain':'failure');
    if (result?.reply && bridge === bridgeRef.current && owner.current.petId === petId && petId) {
      if(result.sent&&!result.error)metrics?.chatSaved(result.reply);
      const petTurn = [...(result.reply.turns ?? [])].reverse().find(turn => turn.speaker === 'PET');
      setReply({ petId, sessionId: result.reply.session?.id ?? owner.current.chat?.session?.id ?? null, idx: petTurn?.idx ?? ((owner.current.chat?.turns?.at(-1)?.idx ?? -1) + 2), line: result.reply.line });
      // Replay only this saved reply's exact owned ID. History/presence/PET-only greetings never enter here.
      const turnIdentity=petTurn?.idx;
      if(result.sent&&!result.error&&result.reply.session?.id!=null&&turnIdentity!=null)x.playChatMotion(result.reply.reactionKey,`${petId}:${result.reply.session?.id??'legacy'}:${turnIdentity}`);
    }
    }catch(error){interaction.result('chat_send','uncertain');throw error;}finally{if(bridgeRef.current===bridge&&owner.current.petId===petId)sendLock.current=false;}
  };
  const transferFailed=(pending:NonNullable<typeof inviteHandoff.current>)=>{
    if(inviteHandoff.current!==pending)return;
    inviteHandoff.current=null;sendLock.current=false;
    setInviteTransfer({petId:pending.petId,waiting:false,error:locale==='ko'?'대화를 열지 못했어요. 적은 말은 남아 있어요. 보내기를 눌러 다시 확인해 주세요.':locale==='en'?'Chat did not open. Your message is still here. Press Send to try again.':'会話を開けませんでした。入力した内容は残っています。送信を押してもう一度確認してください。'});
  };
  useEffect(()=>{const pending=inviteHandoff.current;if(pending&&(pending.petId!==live.petId||sleeping||traveling||pet?.phase!=='ALIVE'))transferFailed(pending);},[live.petId,sleeping,traveling,pet?.phase]);
  useEffect(()=>()=>{inviteHandoff.current=null;sendLock.current=false;},[live.petId]);
  useEffect(()=>{
    const pending=inviteHandoff.current;if(!pending?.ready)return;
    const timer=setTimeout(()=>transferFailed(pending),6000);return()=>clearTimeout(timer);
  },[inviteTransfer]);
  useEffect(()=>{
    const pending=inviteHandoff.current;if(!pending?.ready||pending.petId!==live.petId||bridgeRef.current!==pending.bridge||sleeping||traveling||pet?.phase!=='ALIVE'||pet.firstIntro?.key!=='COMPLETE'||x.busy)return;
    const current=pending.bridge.getSnapshot(),session=live.chat?.session;
    if(live.chat===pending.chat||current.blocked||current.sending||!session||session.closed||!live.chat?.openSlot||current.chat?.session?.id!==session.id)return;
    inviteHandoff.current=null;setInviteTransfer(null);void deliver(pending.bridge,pending.petId);
  },[live,snapshot,inviteTransfer,x.busy,sleeping,traveling]);
  const send = async (event: FormEvent) => {
    event.preventDefault();if(!canSend||sendLock.current)return;
    const bridge=bridgeRef.current,petId=live.petId;if(!bridge||!petId)return;
    interaction.attempt('chat_send');sendLock.current=true;
    if(inviteInput||inviteRetry){
      const pending={petId,bridge,chat:owner.current.chat,ready:false};inviteHandoff.current=pending;setInviteTransfer({petId,waiting:true,error:null});
      const confirmed=inviteInput?await x.nextStory():(await owner.current.refreshPet(),true);
      if(inviteHandoff.current!==pending||bridgeRef.current!==bridge||owner.current.petId!==petId)return;
      if(!confirmed||owner.current.pet?.clock?.sleeping||owner.current.pet?.trip||owner.current.pet?.phase!=='ALIVE'){transferFailed(pending);return;}
      pending.ready=true;setInviteTransfer({petId,waiting:true,error:null});return;
    }
    await deliver(bridge,petId);
  };
  const openHistory = () => { interaction.attempt('history_open'); input.current?.blur(); setDrawer('history'); void bridgeRef.current?.history(legacy); };

  return <section ref={host} data-dialogue-source={x.speechSource} data-stage-status={x.stageStatus} data-intro-key={x.key??''} onPointerDown={event=>{const target=(event.target as HTMLElement).closest<HTMLElement>('[data-fr-target=true]');if(target)target.dataset.frPressed='true';}} onPointerDownCapture={event=>{if(x.panel==='game'&&x.gamePhase==='done'&&!x.busy&&!drawer){event.preventDefault();event.stopPropagation();x.close();}}} onClick={event=>{if(x.panel!=='game'||drawer)return;const target=event.target as HTMLElement;if(target.closest('[data-fr-side],button,a,input,textarea,select')||target.closest('[role=button]')&&!target.closest('[data-fr-actor]'))return;x.close();}} onKeyDown={event=>{if(event.key==='Escape'&&x.panel==='game'&&!drawer){event.preventDefault();event.stopPropagation();x.close();}}} data-game-open={x.panel==='game'?'true':undefined} className={`focused-room ${sleeping ? 'fr-sleeping' : ''}`} style={{'--fr-game-left':`${dockBudget.left}px`,'--fr-game-top':`${dockBudget.top}px`,'--fr-game-width':`${dockBudget.width}px`,'--fr-game-height':`${dockBudget.height}px`} as CSSProperties} data-focused-room data-care-open={x.panel==='care'?'true':undefined} data-compact={compact?'true':undefined} data-pet-id={live.petId ?? ''}>
    <div className="fr-scene" ref={scene} data-fr-scene>
      <div className="fr-world" aria-hidden="true">{backgroundUrl&&<img src={backgroundUrl} alt=""/>}</div>
      {!traveling && src && reference.loaded && geometry.loaded && !geometry.failed && <img className="fr-actor" data-fr-actor data-fr-custom-id={activeCustom?.id} data-fr-custom-anchor={customAnchorMode??undefined} data-fr-base-reference-round={activeCustom?.baseReferenceRound??undefined} src={src} alt={pet?.name || '캐릭터'} role="button" aria-label={t.pet} aria-disabled={x.busy||x.guided||x.panel==='game'||sleeping||traveling} tabIndex={x.busy||x.guided||x.panel==='game'||sleeping||traveling?-1:0} onClick={()=>{if(!x.busy&&!x.guided&&x.panel!=='game'&&!sleeping&&!traveling)void x.care('PET')}} onKeyDown={e=>{if((e.key==='Enter'||e.key===' ')&&!x.busy&&!x.guided&&x.panel!=='game'&&!sleeping&&!traveling){e.preventDefault();void x.care('PET')}}} style={{...actorStyle,visibility:geometry.measured&&(!activeCustom||customReady)?'visible':'hidden',pointerEvents:'auto',cursor:x.busy||x.guided||x.panel==='game'||sleeping||traveling?'default':'pointer'}} />}
      {!traveling && !activeCustom?.suppressRoomProps && !activeCustom?.embeddedProps && <FocusedProps speechVisible={(!traveling&&!sleeping&&!!line)||(x.sleepFarewell&&!traveling&&sleeping)} renderTrash={false} live={live} visual={{...x.visual,pose}} width={size.width} height={size.height} footFloor={170} actor={{left,top,width,height}}/>}
      {Array.from({length:Math.max(0,Math.min(4,x.visualTrash))},(_,i)=><img key={i} data-fr-floor-trash src={FLOOR_DIRT_ASSETS[0]} alt={locale==='ko'?'바닥의 먼지와 부스러기':locale==='en'?'Dust and crumbs on the floor':'床のほこりと食べかす'} style={{position:'absolute',zIndex:2,width:37,height:43,left:Math.max(6,Math.min(size.width-43,size.width*[.12,.36,.61,.85][i]-18)),top:Math.max(0,Math.min(foot-27+(i%2?9:0),(dockBudget.height?dockBudget.top:size.height-145)-43-8)),objectFit:'contain',pointerEvents:'none'}}/>)}
    </div>
    <header className="fr-header">
      <div><h1>{pet?.name || '방'}</h1><p>{pet?.daysTogether != null ? locale==='ko'?`함께한 지 ${pet.daysTogether}일`:locale==='en'?`${pet.daysTogether} days together`:`一緒に${pet.daysTogether}日` : ''}</p></div>
      <div className="fr-header-tools"><button ref={menuButton} className="fr-icon-button" disabled={x.farewell} onClick={() => { input.current?.blur(); returnFocus.current=null; setDrawer(current=>current==='menu'?null:'menu'); }} data-fr-target={x.currentTarget==='attendance-menu'&&!drawer?'true':undefined} aria-label={t.menu} aria-haspopup="dialog" aria-controls={drawer === 'menu' ? menuId : undefined} aria-expanded={drawer === 'menu'}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14" /></svg></button></div>
    </header>
    {!traveling && !sleeping && line && <SpeechBubble key={`${live.petId}:${line}`} wideAbove={x.key==='FINISH_NOTICE'||x.farewell} line={line} bounds={{left:left+alphaLeft,right:left+alphaRight,top:top+geometry.top*geometry.height*scale,bottom:foot}} budget={speechBudget} previous={locale==='ko'?'이전':locale==='en'?'Previous':'前へ'} next={t.next} style={{ left: speechWidth === size.width - 24 ? 12 : speechLeft, top: speechTop, width: speechWidth }} />}
    {x.farewell&&isRomiMotionPhase(x.storyPhase)&&<RomiChatExample phase={x.storyPhase} locale={locale} onReady={setExampleReady}/>}
    {x.sleepFarewell&&!traveling&&sleeping&&<SpeechBubble line={locale==='ko'?'나는 이제 졸려서 자러 갈게. 아침에 또 이야기하자.':locale==='en'?'I am sleepy now, so I am going to bed. Let’s talk again in the morning.':'もう眠いから、そろそろ寝るね。また朝に話そう。'} bounds={{left:left+alphaLeft,right:left+alphaRight,top:top+geometry.top*geometry.height*scale,bottom:foot}} budget={speechBudget} previous={locale==='ko'?'이전':locale==='en'?'Previous':'前へ'} next={t.next} style={{left:speechWidth===size.width-24?12:speechLeft,top:speechTop,width:speechWidth}}/>}
    {(!src || geometry.failed) && !traveling && <p className="fr-asset-status" role="status">{locale==='en'?'Could not load the character image.':locale==='ja'?'キャラクターの画像を読み込めませんでした。':'캐릭터 그림을 불러오지 못했어요.'}</p>}
    <div className="fr-status" role="status" aria-live="polite">
      {blocked && !(inviteInput&&blocked==='closed') && blocked!=='sleeping' && (!x.guided||sleeping||traveling) && <p>{blocked==='traveling'?t.traveling:locale==='ko'?blocks[blocked]:t.loading}</p>}
      {traveling&&<button type="button" data-fr-call-back disabled={live.calling} onClick={()=>void callBack()} style={{minWidth:44,minHeight:44}}>{live.calling?t.calling:t.callBack}</button>}
      {traveling&&returnError&&<p role="alert">{returnError}</p>}
      {x.graceSync&&!sleeping&&!traveling&&<p>{t.loading} <button onClick={()=>void live.refreshPet()}>{t.retry}</button></p>}
      {x.graceNotice&&!sleeping&&!traveling&&<p data-fr-grace-notice>{locale==='ko'?'조금 있으면 잠들어요. 나머지 이야기는 아침에 이어가요.':locale==='en'?'Sleep is coming soon. Continue the conversation in the morning.':'もうすぐ眠ります。続きは朝に話しましょう。'}</p>}
      {x.error&&<p role="alert">{x.error}</p>}
      {x.careUncertain&&<button data-fr-care-retry onClick={()=>void x.retryCare()}>{t.retry}</button>}
      {activeCustom&&!customReady&&<p role="status">{t.loading}</p>}
      {sending && <p>{t.thinking}</p>}
      {snapshot?.error && <p>{snapshot.error}</p>}
      {uncertain && <p>{t.uncertain}</p>}
      {blocked === 'loading' && snapshot?.error && <button onClick={() => void bridgeRef.current?.loadContext()}>{t.retry}</button>}
    </div>
    {sleepNotice&&<div className="fr-status" data-fr-sleep-notice role="status" style={{bottom:dockBudget.height+8,zIndex:7}}><p>{sleepNotice}</p></div>}
    {transfer?.error&&!x.error&&<div className="fr-status" data-fr-invite-error role="status"><p>{transfer.error}</p></div>}
    <footer ref={dock} className="fr-dock" aria-hidden={x.panel==='game'} inert={x.panel==='game'}>
      {(sleeping||x.key!=='FINISH_NOTICE'&&!x.farewell)&&<FocusedTiles live={live} x={x} locale={locale} drawerOpen={!!drawer} onSleepingAction={showSleepNotice} beforeOpen={()=>{input.current?.blur();setDrawer(null)}}/>}
      {x.guided&&x.key==='FINISH_NOTICE'&&!x.panel&&<div data-fr-finish-beat={x.finishBeat}>
        {x.finishBeat===1&&(pet?.attendanceRewards?.background.preview?<figure className="fr-finish-preview"><img data-fr-preview src={assetUrl(pet.attendanceRewards.background.preview.url)} alt={t.preview}/><figcaption>{t.preview}</figcaption></figure>:null)}
        <button className="fr-finish-trigger" data-fr-action="finish-next" data-fr-target={x.currentTarget==='finish'&&!drawer&&x.stageStatus!=='PREPARING'?'true':undefined} disabled={x.busy||x.stageStatus==='PREPARING'||sleeping||traveling||pet?.phase!=='ALIVE'} onClick={x.nextFinish}>{x.busy?t.busy:x.error?t.retry:t.next}</button>
      </div>}
      {x.farewell&&!x.panel&&x.storyPhase!==ENDING_STORY.review&&x.storyPhase!==ENDING_STORY.invite&&<button className="fr-finish-trigger" data-fr-story-next={x.storyPhase} data-fr-target={!x.busy&&x.stageStatus!=='PREPARING'&&!sleeping&&!traveling&&pet?.phase==='ALIVE'&&(!isRomiMotionPhase(x.storyPhase)||exampleReady)?'true':undefined} disabled={x.busy||x.stageStatus==='PREPARING'||sleeping||traveling||pet?.phase!=='ALIVE'||isRomiMotionPhase(x.storyPhase)&&!exampleReady} onClick={()=>void x.nextStory()}>{x.busy?t.busy:x.error?t.retry:t.next}</button>}
      {x.guided&&x.key==='REVIEW_SEEN'&&!x.panel&&!x.farewell&&<button onClick={()=>x.open('review')}>{t.review}</button>}
      {(!x.guided||inviteInput||x.panel==='game'||sleeping)&&<form className="fr-composer" onSubmit={send}>
        <button type="button" ref={historyButton} className="fr-history-icon" onClick={()=>sleeping?showSleepNotice():openHistory()} aria-disabled={sleeping||undefined} disabled={x.busy} aria-label={t.history}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 11a9 9 0 1 1 2 7M3 4v7h7M12 7v5l3 2"/></svg></button>
        <input ref={input} maxLength={CHAT_MAX_CHARS} aria-label={sleeping?`${t.input} · ${t.sleeping}`:t.input} placeholder={sleeping ? (locale==='ko'?'자는 중이에요':locale==='en'?'Asleep':'眠っています') : traveling ? t.traveling : x.guided&&!inviteInput?t.guide:t.placeholder} value={sleeping?'':uncertain ? snapshot?.delivery.text ?? '' : snapshot?.draft ?? ''}
          disabled={sleeping || traveling || x.graceSync || x.guided&&!inviteInput || x.panel==='game' || x.busy || composerBlocked || sending || uncertain || transfer?.waiting} onChange={event => bridgeRef.current?.setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
        <button type="submit" aria-disabled={sleeping||undefined} disabled={x.busy||!sleeping&&!canSend} onClick={sleeping?event=>{event.preventDefault();showSleepNotice()}:undefined}>{uncertain ? t.recheck : sending||transfer?.waiting ? t.busy : t.send}</button>
      </form>}
    </footer>
    {drawer && <div ref={drawer==='menu'?menuPanel:undefined} id={drawer==='menu'?menuId:undefined} data-fr-menu={drawer==='menu'?'true':undefined} className={`fr-modal ${drawer === 'menu' ? 'fr-menu-panel' : ''}`} role="dialog" aria-modal={drawer==='history'?true:undefined} aria-label={drawer === 'history' ? t.history : t.menu} onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); }
      if (event.key === 'Tab' && drawer === 'history') {
        const buttons = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),a[href]')];
        const first = buttons[0], last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    }}>
      {drawer === 'history' && <header><h2>{t.history}</h2><button ref={closeButton} onClick={closeDrawer}>{t.back}</button></header>}
      {drawer === 'menu' ? <><nav className="fr-menu-links">
        <button data-fr-action="attendance-menu" data-fr-target={x.currentTarget==='attendance-menu'?'true':undefined} onClick={()=>{setDrawer(null);x.open('attendance')}}>{t.attendance}</button>
        <button data-fr-action="info-menu" onClick={()=>{setDrawer(null);x.open('info')}}>{locale==='ko'?'캐릭터 정보':t.personal}</button>
        {!sampleMode&&<button data-fr-action="settings-menu" onClick={editSettings}>{locale==='ko'?'캐릭터 설정 다시 작성':locale==='en'?'Rewrite character settings':'キャラクター設定を書き直す'}</button>}
        {returning.eligible&&<button data-fr-action="returning-guide-menu" onClick={()=>{setDrawer(null);x.close();returning.replay()}}>{returningWords.replay}</button>}
        {openAnnouncements&&<button data-fr-action="announcements-menu" onClick={()=>{setDrawer(null);openAnnouncements?.(menuButton.current)}}>{locale==='ko'?'공지사항':locale==='en'?'Announcements':'お知らせ'}</button>}
        <button data-fr-action="help-menu" onClick={()=>{setDrawer(null);x.open('help')}}>{locale==='ko'?'도움말':locale==='en'?'Help':'使い方'}</button>
        {!sampleMode&&<button data-fr-action="feedback-menu" onClick={()=>{setDrawer(null);x.open('review')}}>{locale==='ko'?'후기':locale==='en'?'Feedback':'感想'}</button>}
        <button data-fr-action="scene-menu" onClick={()=>{setDrawer(null);interaction.attempt('scene_open');setShareOpen(true)}}>{locale==='ko'?'공유':locale==='en'?'Share':'共有'}</button>
        {!sampleMode&&<button data-fr-action="inquiry-menu" onClick={()=>{setDrawer(null);x.close();interaction.attempt('inquiry_open');setInquiryOpen(true)}}>{locale==='ko'?'문의하기':locale==='en'?'Contact us':'お問い合わせ'}</button>}
      </nav><div className="fr-languages"><LanguageSelector/></div></> : <div className="fr-history">
        <div className="fr-history-tabs"><button aria-disabled={snapshot?.historyLoading} aria-pressed={!legacy} onClick={() => { if(snapshot?.historyLoading)return; setLegacy(false); void bridgeRef.current?.history(false); }}>{t.current}</button><button aria-disabled={snapshot?.historyLoading} aria-pressed={legacy} onClick={() => { if(snapshot?.historyLoading)return; setLegacy(true); void bridgeRef.current?.history(true); }}>{t.legacyHistory}</button></div>
        {snapshot?.historyLoading && <p role="status">{t.loading}</p>}
        {snapshot?.error && <p role="status">{snapshot.error}</p>}
        {!legacy && snapshot?.history?.turns.map(turn => <article key={turn.id} className={turn.speaker === 'USER' ? 'fr-user-turn' : ''}><p className="fr-turn-meta">{turn.speaker === 'USER' ? locale==='en'?'Me':locale==='ja'?'私':'나' : pet?.name} · <time dateTime={turn.at}>{dateTime(turn.at,locale)}</time></p><p>{turn.line}</p></article>)}
        {legacy && snapshot?.legacyHistory?.calls.map(call => <article key={call.id}><p className="fr-turn-meta"><time dateTime={call.calledAt}>{dateTime(call.calledAt,locale)}</time></p><p>{call.petLine}</p>{call.userLine && <p className="fr-user-turn">{call.userLine}</p>}{call.replyLine && <p>{call.replyLine}</p>}</article>)}
        {!snapshot?.historyLoading && (legacy ? snapshot?.legacyHistory?.calls.length === 0 : snapshot?.history?.turns.length === 0) && <p>{t.emptyHistory}</p>}
        {((legacy ? snapshot?.legacyHistory : snapshot?.history)?.nextBeforeId != null) && <button aria-disabled={snapshot?.historyLoading} onClick={() => {if(!snapshot?.historyLoading)void bridgeRef.current?.history(legacy, true)}}>{t.more}</button>}
      </div>}
    </div>}
    <FocusedPanels live={live} x={x.panel==='care'?{...x,panel:null}:x} locale={locale} sampleMode={sampleMode}/>
    <FocusedInquiry metrics={interactionMetrics(live,sampleMode,'inquiry')} open={inquiryOpen} onClose={()=>setInquiryOpen(false)} petId={live.petId} locale={locale}/>
    {returning.open&&<ReturningGuide guide={returning} locale={locale} locked={sleeping||traveling} onEdit={editSettings} onChat={returnToChat}/>}
    {settingsOpen&&<section className="fr-sheet fr-reading-sheet" role="dialog" aria-label={returningWords.settings} data-returning-settings><header><h2>{returningWords.settings}</h2><button type="button" onClick={()=>setSettingsOwner(null)}>{returningWords.close}</button></header><div className="returning-content"><PersonaEditor initialEditing onSaved={returnToChat} onClose={()=>setSettingsOwner(null)}/></div></section>}
    {shareOpen&&<SceneShare metrics={interactionMetrics(live,sampleMode,'scene_share')} stage={scene.current} logoUrl="/zzal-entry/logo-08-transparent.png" onClose={()=>setShareOpen(false)}/>}
  </section>;
}

type SpeechBounds={left:number;right:number;top:number;bottom:number};
export function SpeechBubble({line,budget,style,previous,next,bounds,wideAbove=false}:{line:string;budget:number;style:CSSProperties;previous:string;next:string;bounds:SpeechBounds;wideAbove?:boolean}){
 const root=useRef<HTMLDivElement>(null),[page,setPage]=useState(0),[capacity,setCapacity]=useState(Number.MAX_SAFE_INTEGER),[,remeasure]=useState(0);const chars=Array.from(line),count=Math.max(1,Math.ceil(chars.length/capacity)),current=Math.min(page,count-1);
 useLayoutEffect(()=>{
  const bubble=root.current,host=bubble?.closest<HTMLElement>('.focused-room'),body=bubble?.querySelector('p');if(!bubble||!host||!body)return;
  const width=host.clientWidth,height=host.clientHeight,edge=12,gap=wideAbove?4:12;let safeTop=12,safeBottom=height-12;
  for(const selector of ['.fr-header','.fr-sample-controls']){const e=(selector==='.fr-sample-controls'?host.closest('.fr-sample-room'):host)?.querySelector<HTMLElement>(selector);if(e&&e.checkVisibility())safeTop=Math.max(safeTop,e.getBoundingClientRect().bottom-host.getBoundingClientRect().top+(wideAbove?2:8))}
  for(const selector of ['.fr-dock','.fr-game-sheet','.fr-meeting-controls']){const e=host.querySelector<HTMLElement>(selector);if(e&&e.checkVisibility())safeBottom=Math.min(safeBottom,e.getBoundingClientRect().top-host.getBoundingClientRect().top-gap)}
  const old=body.textContent,pages=bubble.querySelector<HTMLElement>('.fr-speech-pages'),counter=pages?.querySelector('span');body.textContent=line;pages?.style.setProperty('display','none');
  const probe=document.createElement('span');probe.style.cssText='position:absolute;visibility:hidden;white-space:pre';probe.style.font=getComputedStyle(body).font;probe.textContent=line;host.append(probe);const natural=probe.getBoundingClientRect().width+30;probe.remove();const preferred=wideAbove?width-edge*2:Math.min(width-edge*2,Math.max(84,Math.min(260,natural)));
  const measure=(w:number)=>{bubble.style.width=Math.floor(w)+'px';return bubble.getBoundingClientRect().height};const candidates:Array<{mode:string;w:number;left:number;top:number;available:number;fits:boolean}>=[];
  const add=(mode:string,w:number,left:number,top:number,min:number,max:number)=>{if(w<84||max-min<28)return;const h=measure(w);candidates.push({mode,w,left,top:Math.max(min,Math.min(top,max-h)),available:max-min,fits:h<=max-min+.5})};
  const above=bounds.top-gap;add('above',preferred,width-edge-preferred,above-measure(preferred),safeTop,above);if(!candidates.some(c=>c.fits)&&preferred<width-edge*2)add('above',width-edge*2,edge,above-measure(width-edge*2),safeTop,above);
  const rightWidth=width-edge-bounds.right-gap;if(!wideAbove&&rightWidth>=84){const w=Math.min(preferred,rightWidth);add('right',w,bounds.right+gap,bounds.top+8,safeTop,safeBottom)}const leftWidth=bounds.left-gap-edge;if(!wideAbove&&leftWidth>=84){const w=Math.min(preferred,leftWidth);add('left',w,bounds.left-gap-w,bounds.top+8,safeTop,safeBottom)}
  const chosen=candidates.find(c=>c.fits)||candidates.sort((a,b)=>b.w*b.available-a.w*a.available)[0]||{mode:'above',w:width-edge*2,left:edge,top:safeTop,available:Math.max(28,safeBottom-safeTop),fits:false};
  bubble.style.width=chosen.w+'px';bubble.style.left=Math.max(edge,Math.min(width-edge-chosen.w,chosen.left+(wideAbove?0:10)))+'px';bubble.style.top=Math.max(safeTop,chosen.top-(wideAbove?0:10))+'px';bubble.dataset.speechPlacement=chosen.mode;bubble.dataset.speechFits=String(chosen.fits);bubble.style.setProperty('--fr-tail-x',Math.max(20,Math.min(chosen.w-24,(bounds.left+bounds.right)/2-chosen.left))+'px');
  // Measure the real font and controls, rather than assuming a fixed width per character.
  let cap=Number.MAX_SAFE_INTEGER;
  if(!chosen.fits){
   pages?.style.setProperty('display','flex');
   const fits=(n:number)=>{if(counter)counter.textContent=`${Math.ceil(chars.length/n)}/${Math.ceil(chars.length/n)}`;for(let start=0;start<chars.length;start+=n){body.textContent=chars.slice(start,start+n).join('');if(bubble.getBoundingClientRect().height>chosen.available+.5||body.scrollWidth>body.clientWidth+1)return false}return true};
   let low=1,high=Math.max(1,Math.min(chars.length,budget));while(low<high){const mid=Math.ceil((low+high)/2);if(fits(mid))low=mid;else high=mid-1}cap=low;while(cap>1&&!fits(cap))cap--;
  }
  body.textContent=old;if(counter)counter.textContent=`${current+1}/${count}`;pages?.style.setProperty('display',count>1?'flex':'none');setCapacity(cap);
  const actual=bubble.getBoundingClientRect().height;const regionBottom=chosen.mode==='above'?Math.min(safeBottom,bounds.top-gap):safeBottom;
  bubble.style.top=Math.max(safeTop,Math.min(parseFloat(bubble.style.top),regionBottom-actual))+'px';bubble.dataset.speechFits=String(actual<=chosen.available+.5);
 });
 useEffect(()=>{const bubble=root.current,host=bubble?.closest('.focused-room');if(!bubble||!host)return;let active=true;const update=()=>{if(active)remeasure(v=>v+1)};const observer=new ResizeObserver(update);observer.observe(host);observer.observe(bubble.querySelector('p')!);for(const e of host.querySelectorAll('.fr-dock,.fr-header,.fr-meeting-controls'))observer.observe(e);const sampleControls=host.closest('.fr-sample-room')?.querySelector('.fr-sample-controls');if(sampleControls)observer.observe(sampleControls);void document.fonts.ready.then(update);document.fonts.addEventListener('loadingdone',update);return()=>{active=false;observer.disconnect();document.fonts.removeEventListener('loadingdone',update)}},[]);
 useEffect(()=>setPage(0),[line]);
 return <div ref={root} className="fr-bubble" data-fr-speech data-fr-ending-speech={wideAbove?true:undefined} style={style}><p>{chars.slice(current*capacity,(current+1)*capacity).join('')}</p><div className="fr-speech-pages" style={{display:count>1?'flex':'none',flexWrap:'wrap'}}><button aria-label={previous} disabled={current===0} onClick={()=>setPage(current-1)}>{previous}</button><span>{current+1}/{count}</span><button aria-label={next} disabled={current+1===count} onClick={()=>setPage(current+1)}>{next}</button></div></div>;
}
