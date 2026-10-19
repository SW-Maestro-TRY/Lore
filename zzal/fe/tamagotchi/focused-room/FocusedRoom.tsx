'use client';

import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type FormEvent } from 'react';
import { createProductRoomChatBridge, type RoomChatBridge, type RoomChatSnapshot, type RoomChatSource } from '../../lib/roomChatBridge';
import { assetUrl } from '../../lib/assets';
import { useSpriteGeometry } from '../../lib/spriteGeometry';
import type { Live } from '../yeoul/useHatch';
import './focused-room.css';
import './korean-font-fix.css';
import { useZzalLocale } from '../../lib/locale';
import { CHAT_MAX_CHARS } from '../rules';
import { useFocusedAnalytics } from './useFocusedAnalytics';
import { useFocusedExperience } from './useFocusedExperience';
import { FocusedPanels } from './FocusedPanels';
import { FocusedProps } from './FocusedProps';
import FocusedTiles from './FocusedTiles';
import { focusedCopy } from './focusedCopy';
import { useAnchors } from '../props/anchors';
import {useAuth} from '@common/auth/useAuth';
import SceneShare from '../yeoul/SceneShare';
import {useFocusedGeometry} from './useFocusedGeometry';

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
  const locale=useZzalLocale(),t=focusedCopy(locale),x=useFocusedExperience(live,locale);
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
  const [drawer, setDrawer] = useState<'history' | 'menu' | null>(null);
  const [shareOpen,setShareOpen]=useState(false);
  const scene=useRef<HTMLDivElement>(null);
  const [legacy, setLegacy] = useState(false);
  const [reply, setReply] = useState<{ petId: number; sessionId: number | null; idx: number; line: string } | null>(null);
  const [size, setSize] = useState({ width: 390, height: 700 });
  const initialHeight=useRef(typeof window==='undefined'?844:window.innerHeight);
  const host = useRef<HTMLElement>(null), input = useRef<HTMLInputElement>(null);
  const historyButton = useRef<HTMLButtonElement>(null), menuButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const returnFocus=useRef<'history'|'menu'|null>(null);
  // An open tile group keeps the composer live; only real playback locks the existing controls.
  useEffect(()=>{
    if(!x.busy)return;
    const nodes=[...host.current?.querySelectorAll<HTMLElement>('.fr-dock,.fr-header,[data-fr-actor]')??[]];
    const prior=nodes.map(node=>node.inert);nodes.forEach(node=>{node.inert=true});
    return()=>nodes.forEach((node,i)=>{node.inert=prior[i]});
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
  useEffect(() => {
    const viewport = window.visualViewport;
    const fit = () => { host.current?.style.setProperty('--fr-viewport', `${viewport?.height ?? window.innerHeight}px`); };
    fit(); viewport?.addEventListener('resize', fit);
    return () => viewport?.removeEventListener('resize', fit);
  }, []);
  useEffect(() => {
    if (drawer) closeButton.current?.focus({ preventScroll: true });
  }, [drawer]);

  const snapshot = storedSnapshot?.pet?.petId === live.petId ? storedSnapshot : null;
  const pet = live.pet;
  const sleeping = !!pet?.clock?.sleeping, traveling = !!pet?.trip;
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
  const canSend = !x.graceSync && !x.guided && x.panel!=='game' && !x.busy && !blocked && !sending && !!(uncertain ? snapshot?.delivery.text : snapshot?.draft)?.trim();

  useEffect(()=>{const refresh=(event:Event)=>{const id=(event as CustomEvent<{petId?:number}>).detail?.petId;if(id!=null&&id!==owner.current.petId)return;void bridgeRef.current?.loadContext();};window.addEventListener('zzal-persona-updated',refresh);window.addEventListener('pageshow',refresh);return()=>{window.removeEventListener('zzal-persona-updated',refresh);window.removeEventListener('pageshow',refresh)};},[]);
  const personaIdentity=JSON.stringify([live.petId,pet?.name,pet?.personality,pet?.world]);
  const priorPersona=useRef(personaIdentity);useEffect(()=>{if(priorPersona.current!==personaIdentity){priorPersona.current=personaIdentity;void bridgeRef.current?.loadContext();}},[personaIdentity]);

  useEffect(()=>{host.current?.querySelectorAll('[data-fr-pressed]').forEach(node=>node.removeAttribute('data-fr-pressed'));},[x.currentTarget,x.key,x.panel,drawer]);
  useEffect(()=>{const visibility=()=>{host.current?.setAttribute('data-fr-hidden',String(document.hidden));};visibility();document.addEventListener('visibilitychange',visibility);return()=>document.removeEventListener('visibilitychange',visibility);},[]);
  useEffect(()=>{if(drawer||!returnFocus.current)return;const was=returnFocus.current;returnFocus.current=null;const frame=requestAnimationFrame(()=>(was==='history'?historyButton:menuButton).current?.focus({preventScroll:true}));return()=>cancelAnimationFrame(frame);},[drawer]);
  const closeDrawer = () => { returnFocus.current=drawer;setDrawer(null); };
  const send = async (event: FormEvent) => {
    event.preventDefault(); if (!canSend) return;
    const bridge = bridgeRef.current, petId = live.petId;
    const result = await bridge?.send();
    if (result?.reply && bridge === bridgeRef.current && owner.current.petId === petId && petId) {
      if(result.sent&&!result.error)metrics?.chatSaved(result.reply);
      const petTurn = [...(result.reply.turns ?? [])].reverse().find(turn => turn.speaker === 'PET');
      setReply({ petId, sessionId: result.reply.session?.id ?? owner.current.chat?.session?.id ?? null, idx: petTurn?.idx ?? ((owner.current.chat?.turns?.at(-1)?.idx ?? -1) + 2), line: result.reply.line });
      // Replay only this saved reply's exact owned ID. History/presence/PET-only greetings never enter here.
      const turnIdentity=petTurn?.idx;
      if(result.sent&&!result.error&&result.reply.session?.id!=null&&turnIdentity!=null)x.playChatMotion(result.reply.reactionKey,`${petId}:${result.reply.session?.id??'legacy'}:${turnIdentity}`);
    }
  };
  const openHistory = () => { input.current?.blur(); setDrawer('history'); void bridgeRef.current?.history(legacy); };

  return <section ref={host} data-dialogue-source={x.speechSource} data-stage-status={x.stageStatus} data-intro-key={x.key??''} onPointerDown={event=>{const target=(event.target as HTMLElement).closest<HTMLElement>('[data-fr-target=true]');if(target)target.dataset.frPressed='true';}} data-game-open={x.panel==='game'?'true':undefined} className={`focused-room ${sleeping ? 'fr-sleeping' : ''}`} data-focused-room data-care-open={x.panel==='care'?'true':undefined} data-compact={compact?'true':undefined} data-pet-id={live.petId ?? ''}>
    <div className="fr-scene" ref={scene} data-fr-scene>
      <div className="fr-world" aria-hidden="true">{backgroundUrl&&<img src={backgroundUrl} alt=""/>}</div>
      {!traveling && src && reference.loaded && geometry.loaded && !geometry.failed && <img className="fr-actor" data-fr-actor data-fr-custom-id={activeCustom?.id} data-fr-custom-anchor={customAnchorMode??undefined} data-fr-base-reference-round={activeCustom?.baseReferenceRound??undefined} src={src} alt={pet?.name || '캐릭터'} role="button" aria-label={t.pet} aria-disabled={x.busy||x.guided||x.panel==='game'||sleeping||traveling} tabIndex={x.busy||x.guided||x.panel==='game'||sleeping||traveling?-1:0} onClick={()=>{if(!x.busy&&!x.guided&&x.panel!=='game'&&!sleeping&&!traveling)void x.care('PET')}} onKeyDown={e=>{if((e.key==='Enter'||e.key===' ')&&!x.busy&&!x.guided&&x.panel!=='game'&&!sleeping&&!traveling){e.preventDefault();void x.care('PET')}}} style={{...actorStyle,visibility:geometry.measured&&(!activeCustom||customReady)?'visible':'hidden',pointerEvents:'auto',cursor:x.busy||x.guided||x.panel==='game'||sleeping||traveling?'default':'pointer'}} />}
      {!traveling && !activeCustom?.suppressRoomProps && !activeCustom?.embeddedProps && <FocusedProps speechVisible={(!traveling&&!sleeping&&!!line)||(x.sleepFarewell&&!traveling&&sleeping)} renderTrash={false} live={live} visual={{...x.visual,pose}} width={size.width} height={size.height} footFloor={170} actor={{left,top,width,height}}/>}
      {Array.from({length:Math.max(0,Math.min(4,x.visualTrash))},(_,i)=><img key={i} data-fr-floor-trash src="/images/zzal/assets/trash_1.v1.webp" alt={locale==='ko'?'바닥의 똥':locale==='en'?'Droppings':'床の汚れ'} style={{position:'absolute',width:37,height:43,left:Math.max(6,Math.min(size.width-43,size.width*[.12,.36,.61,.85][i]-18)),top:Math.min(size.height-145,foot-27+(i%2?9:0)),objectFit:'contain',pointerEvents:'none'}}/>)}
    </div>
    <header className="fr-header">
      <div><h1>{pet?.name || '방'}</h1><p>{pet?.daysTogether != null ? locale==='ko'?`함께한 지 ${pet.daysTogether}일`:locale==='en'?`${pet.daysTogether} days together`:`一緒に${pet.daysTogether}日` : ''}</p></div>
      <div className="fr-header-tools">{pet?.sick&&<button type="button" className="fr-medicine-trigger" data-fr-action="MEDICINE" disabled={x.busy||x.guided||x.panel==='game'||sleeping||traveling} onClick={()=>void x.care('MEDICINE')}>{t.medicine}</button>}<button ref={menuButton} className="fr-icon-button" onClick={() => { input.current?.blur(); setDrawer('menu'); }} data-fr-target={x.currentTarget==='attendance-menu'&&!drawer?'true':undefined} aria-label={t.menu} aria-expanded={drawer === 'menu'}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14" /></svg></button></div>
    </header>
    {!traveling && !sleeping && line && <SpeechBubble key={`${live.petId}:${line}`} line={line} bounds={{left:left+alphaLeft,right:left+alphaRight,top:top+geometry.top*geometry.height*scale,bottom:foot}} budget={speechBudget} previous={locale==='ko'?'이전':locale==='en'?'Previous':'前へ'} next={t.next} style={{ left: speechWidth === size.width - 24 ? 12 : speechLeft, top: speechTop, width: speechWidth }} />}
    {x.sleepFarewell&&!traveling&&sleeping&&<SpeechBubble line={locale==='ko'?'나는 이제 졸려서 자러 갈게. 아침에 또 이야기하자.':locale==='en'?'I am sleepy now, so I am going to bed. Let’s talk again in the morning.':'もう眠いから、そろそろ寝るね。また朝に話そう。'} bounds={{left:left+alphaLeft,right:left+alphaRight,top:top+geometry.top*geometry.height*scale,bottom:foot}} budget={speechBudget} previous={locale==='ko'?'이전':locale==='en'?'Previous':'前へ'} next={t.next} style={{left:speechWidth===size.width-24?12:speechLeft,top:speechTop,width:speechWidth}}/>}
    {(!src || geometry.failed) && !traveling && <p className="fr-asset-status" role="status">{locale==='en'?'Could not load the character image.':locale==='ja'?'キャラクターの画像を読み込めませんでした。':'캐릭터 그림을 불러오지 못했어요.'}</p>}
    <div className="fr-status" role="status" aria-live="polite">
      {blocked && (!x.guided||sleeping||traveling) && <p>{blocked==='sleeping'?t.sleeping:blocked==='traveling'?t.traveling:locale==='ko'?blocks[blocked]:t.loading}</p>}
      {traveling&&<button type="button" data-fr-call-back disabled={live.calling} onClick={()=>void callBack()} style={{minWidth:44,minHeight:44}}>{live.calling?t.calling:t.callBack}</button>}
      {traveling&&returnError&&<p role="alert">{returnError}</p>}
      {x.graceSync&&!sleeping&&!traveling&&<p>{t.loading} <button onClick={()=>void live.refreshPet()}>{t.retry}</button></p>}
      {x.graceNotice&&!sleeping&&!traveling&&<p data-fr-grace-notice>{locale==='ko'?'조금 있으면 잠들어요. 나머지 이야기는 아침에 이어가요.':locale==='en'?'Sleep is coming soon. Continue the conversation in the morning.':'もうすぐ眠ります。続きは朝に話しましょう。'}</p>}
      {x.error&&<p role="alert">{x.error}</p>}
      {x.careUncertain&&<button data-fr-care-retry onClick={()=>void x.retryCare()}>{t.retry}</button>}
      {x.farewell&&x.error&&<button disabled={x.closingReview} onClick={()=>void x.retryFarewell()}>{t.retry}</button>}
      {activeCustom&&!customReady&&<p role="status">{t.loading}</p>}
      {sending && <p>{t.thinking}</p>}
      {snapshot?.error && <p>{snapshot.error}</p>}
      {uncertain && <p>{t.uncertain}</p>}
      {blocked === 'loading' && snapshot?.error && <button onClick={() => void bridgeRef.current?.loadContext()}>{t.retry}</button>}
    </div>
    <footer className="fr-dock" hidden={x.panel==='game'}>
      <FocusedTiles live={live} x={x} locale={locale} drawerOpen={!!drawer} beforeOpen={()=>{input.current?.blur();setDrawer(null)}}/>
      {x.guided&&x.key==='FINISH_NOTICE'&&!x.panel&&<button className="fr-finish-trigger" data-fr-target="true" onClick={()=>x.open('finish')}>{t.next}</button>}
      {x.guided&&x.key==='REVIEW_SEEN'&&!x.panel&&!x.farewell&&<button onClick={()=>x.open('review')}>{t.review}</button>}
      {!x.guided&&<form className="fr-composer" onSubmit={send}>
        <button type="button" ref={historyButton} className="fr-history-icon" onClick={openHistory} disabled={x.busy} aria-label={t.history}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 11a9 9 0 1 1 2 7M3 4v7h7M12 7v5l3 2"/></svg></button>
        <input ref={input} maxLength={CHAT_MAX_CHARS} aria-label={t.input} placeholder={sleeping ? t.sleeping : traveling ? t.traveling : x.guided?t.guide:t.placeholder} value={uncertain ? snapshot?.delivery.text ?? '' : snapshot?.draft ?? ''}
          disabled={x.graceSync || x.guided || x.busy || !!blocked || sending || uncertain} onChange={event => bridgeRef.current?.setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
        <button type="submit" disabled={!canSend}>{uncertain ? t.recheck : sending ? t.busy : t.send}</button>
      </form>}
    </footer>
    {drawer && <div className={`fr-modal ${drawer === 'menu' ? 'fr-menu-panel' : ''}`} role="dialog" aria-modal="true" aria-label={drawer === 'history' ? t.history : t.menu} onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); }
      if (event.key === 'Tab') {
        const buttons = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),a[href]')];
        const first = buttons[0], last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    }}>
      <header><h2>{drawer === 'history' ? t.history : t.menu}</h2><button ref={closeButton} onClick={closeDrawer}>{t.back}</button></header>
      {drawer === 'menu' ? <nav><button data-fr-action="attendance-menu" data-fr-target={x.currentTarget==='attendance-menu'?'true':undefined} onClick={()=>{setDrawer(null);x.open('attendance')}}>{t.attendance}</button><div className="fr-languages" aria-label={locale==='ko'?'화면 언어':locale==='en'?'Interface language':'表示言語'}>{(['ko','en','ja'] as const).map(code=><button key={code} aria-pressed={locale===code} onClick={()=>{document.cookie=`lore_locale=${code}; Path=/; Max-Age=31536000; SameSite=Lax`;dispatchEvent(new Event('zzal-locale-change'));}}>{code==='ko'?'한국어':code==='en'?'English':'日本語'}</button>)}</div>{!sampleMode&&<RoomAccountActions locale={locale}/>}<button data-fr-action="info-menu" onClick={()=>{setDrawer(null);x.open('info')}}>{t.personal}</button><button data-fr-action="help-menu" onClick={()=>{setDrawer(null);x.open('help')}}>{locale==='ko'?'도움말':locale==='en'?'Help':'使い方'}</button>{!sampleMode&&<button data-fr-action="feedback-menu" onClick={()=>{setDrawer(null);x.open('review')}}>{t.review}</button>}<button data-fr-action="scene-menu" onClick={()=>{setDrawer(null);setShareOpen(true)}}>{locale==='ko'?'현재 장면 공유':locale==='en'?'Share this scene':'今のシーンを共有'}</button>{pet?.firstIntro&&pet.firstIntro.version!==4&&!pet.firstIntro.completed&&<><p>{t.legacy}</p><a href="/zzal?room=legacy">{t.oldRoom}</a></>}</nav> : <div className="fr-history">
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
    {shareOpen&&<SceneShare stage={scene.current} logoUrl="/zzal-entry/logo-08-transparent.png" onClose={()=>setShareOpen(false)}/>}
  </section>;
}

type SpeechBounds={left:number;right:number;top:number;bottom:number};
export function SpeechBubble({line,budget,style,previous,next,bounds}:{line:string;budget:number;style:CSSProperties;previous:string;next:string;bounds:SpeechBounds}){
 const root=useRef<HTMLDivElement>(null),[page,setPage]=useState(0),[capacity,setCapacity]=useState(Number.MAX_SAFE_INTEGER);const chars=Array.from(line),count=Math.max(1,Math.ceil(chars.length/capacity)),current=Math.min(page,count-1);
 useLayoutEffect(()=>{
  const bubble=root.current,host=bubble?.closest<HTMLElement>('.focused-room'),body=bubble?.querySelector('p');if(!bubble||!host||!body)return;
  const width=host.clientWidth,height=host.clientHeight,edge=12,gap=12;let safeTop=12,safeBottom=height-12;
  for(const selector of ['.fr-header','.fr-sample-controls']){const e=host.querySelector<HTMLElement>(selector);if(e&&e.checkVisibility())safeTop=Math.max(safeTop,e.getBoundingClientRect().bottom-host.getBoundingClientRect().top+8)}
  for(const selector of ['.fr-dock','.fr-game-sheet','.fr-meeting-controls']){const e=host.querySelector<HTMLElement>(selector);if(e&&e.checkVisibility())safeBottom=Math.min(safeBottom,e.getBoundingClientRect().top-host.getBoundingClientRect().top-gap)}
  const old=body.textContent;body.textContent=line;bubble.querySelector<HTMLElement>('.fr-speech-pages')?.style.setProperty('display','none');
  const probe=document.createElement('span');probe.style.cssText='position:absolute;visibility:hidden;white-space:pre';probe.style.font=getComputedStyle(body).font;probe.textContent=line;host.append(probe);const natural=probe.getBoundingClientRect().width+30;probe.remove();const preferred=Math.min(width-edge*2,Math.max(84,Math.min(260,natural)));
  const measure=(w:number)=>{bubble.style.width=Math.floor(w)+'px';return bubble.getBoundingClientRect().height};const candidates:Array<{mode:string;w:number;left:number;top:number;available:number;fits:boolean}>=[];
  const add=(mode:string,w:number,left:number,top:number,min:number,max:number)=>{if(w<84||max-min<28)return;const h=measure(w);candidates.push({mode,w,left,top:Math.max(min,Math.min(top,max-h)),available:max-min,fits:h<=max-min+.5})};
  const above=bounds.top-gap;add('above',preferred,width-edge-preferred,above-measure(preferred),safeTop,above);if(!candidates.some(c=>c.fits)&&preferred<width-edge*2)add('above',width-edge*2,edge,above-measure(width-edge*2),safeTop,above);
  const rightWidth=width-edge-bounds.right-gap;if(rightWidth>=84){const w=Math.min(preferred,rightWidth);add('right',w,bounds.right+gap,bounds.top+8,safeTop,safeBottom)}const leftWidth=bounds.left-gap-edge;if(leftWidth>=84){const w=Math.min(preferred,leftWidth);add('left',w,bounds.left-gap-w,bounds.top+8,safeTop,safeBottom)}
  const chosen=candidates.find(c=>c.fits)||candidates.sort((a,b)=>b.w*b.available-a.w*a.available)[0]||{mode:'above',w:width-edge*2,left:edge,top:safeTop,available:Math.max(28,safeBottom-safeTop),fits:false};
  bubble.style.width=chosen.w+'px';bubble.style.left=Math.max(edge,Math.min(width-edge-chosen.w,chosen.left+10))+'px';bubble.style.top=Math.max(safeTop,chosen.top-10)+'px';bubble.dataset.speechPlacement=chosen.mode;bubble.dataset.speechFits=String(chosen.fits);bubble.style.setProperty('--fr-tail-x',Math.max(20,Math.min(chosen.w-24,(bounds.left+bounds.right)/2-chosen.left))+'px');body.textContent=old;
  const cap=chosen.fits?Number.MAX_SAFE_INTEGER:Math.max(12,Math.min(budget,Math.floor(chosen.w/14)*Math.max(1,Math.floor((chosen.available-72)/28))));setCapacity(cap);bubble.querySelector<HTMLElement>('.fr-speech-pages')?.style.removeProperty('display');
 });
 return <div ref={root} className="fr-bubble" data-fr-speech style={style}><p>{chars.slice(current*capacity,(current+1)*capacity).join('')}</p>{count>1&&<div className="fr-speech-pages"><button aria-label={previous} disabled={current===0} onClick={()=>setPage(current-1)}>{previous}</button><span>{current+1}/{count}</span><button aria-label={next} disabled={current+1===count} onClick={()=>setPage(current+1)}>{next}</button></div>}</div>;
}

function RoomAccountActions({locale}:{locale:'ko'|'en'|'ja'}){const{signOut}=useAuth(),[confirm,setConfirm]=useState(false),[busy,setBusy]=useState(false);return confirm?<div className="fr-logout"><p>{locale==='ko'?'로그아웃할까요?':locale==='en'?'Sign out?':'ログアウトしますか？'}</p><button disabled={busy} onClick={()=>{setBusy(true);void signOut().then(()=>{location.assign('/zzal')}).finally(()=>setBusy(false))}}>{locale==='ko'?'로그아웃':locale==='en'?'Sign out':'ログアウト'}</button><button disabled={busy} onClick={()=>setConfirm(false)}>{locale==='ko'?'취소':locale==='en'?'Cancel':'キャンセル'}</button></div>:<button onClick={()=>setConfirm(true)}>{locale==='ko'?'로그아웃':locale==='en'?'Sign out':'ログアウト'}</button>;}
