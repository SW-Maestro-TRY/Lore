// 앨범 벽과 액자 하나.
//
// 벽 = 방 위로 통째로 올라오는 판(18칸). 열린 칸만 또렷하고 잠긴 칸은 흐리다 —
//      "아직 못 배운 모습" 이 보여야 다음에 뭘 할지 알 수 있다.
// 액자 = 벽에서 한 칸을 누르면 크게. 열린 칸은 저장·공유, 잠긴 칸은 조건만 알려 준다.
'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import RewardsJournal, { BackgroundRoom, useRewardsCopy } from './RewardsJournal';
import { assetUrl } from '../../lib/assets';
import {useUiText} from '../../lib/uiCopy';
import { dexLayer, downloadImage, imageFileName, prepareImageFile, shareImageFile } from '../../lib/download';
import { detectInApp } from '../../lib/inapp';
import { motionByKey } from '../constants';
import { ACCENT, C, C2, GAEGU, TAP_MIN, gap, radius, fz, ink, paperA } from './ui';
import { spriteUrl, useLive } from './useHatch';
import type { Yeoul } from './useYeoul';

/** 앨범에서는 서버가 준 심화 원본이 먼저다. 다른 자세/샘플로 대체해서 저장하지 않는다. */
function albumImageUrl(live: ReturnType<typeof useLive>, key: string): string | null {
  const motion = live.pet?.motions?.find((m) => m.key === key);
  const imageKey = motion?.advanced.status === 'OPEN' && motion.advanced.imageKey
    ? motion.advanced.imageKey : motion?.basicImageKey;
  return imageKey ? assetUrl(imageKey) : null;
}

export default function Album({ y }: { y: Yeoul }) {
  const { v } = y;
  const live=useLive();
  const introAlbum=!y.s.sampleMode&&live.pet?.firstIntro?.key==='ALBUM';
  const [frameViewed,setFrameViewed]=useState(false);
  useEffect(()=>setFrameViewed(false),[live.petId,introAlbum]);
  const markViewed=useCallback(()=>setFrameViewed(true),[]);
  return (
    <>
      {v.wall.show && <Wall y={y} frameViewed={frameViewed} />}
      {v.frame.show && <FrameView y={y} viewed={frameViewed} onViewed={introAlbum?markViewed:undefined} />}
    </>
  );
}

/** 서버 배경 key → 이름. 모르는 key 는 '어딘가' — 빈칸보다 낫다. */
const PLACE: Record<string, string> = { sea: '바다', forest: '숲', city: '도시', mountain: '산', island: '섬' };

/** "10월 13일" — 엽서는 날짜만 말한다(몇 시인지는 이야기가 아니다). */
function postcardDay(at: string): string {
  const t = new Date(at);
  if (Number.isNaN(t.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', timeZone: 'Asia/Seoul' }).format(t);
}

/**
 * 여행 엽서 칸. 여행에서 돌아올 때 서버가 한꺼번에 전달한 것만 온다(여행 중인 엽서는 안 보인다).
 * 한 장도 없으면 칸 자체를 안 그린다 — 빈 상자는 "뭔가 빠졌다" 로 읽힌다.
 */
function Postcards({ cards }: { cards: ReadonlyArray<{ seq: number; place: string; at: string; line: string }> }) {
  if (cards.length === 0) return null;
  return (
    <section data-part="postcards" aria-label={`여행 엽서 ${cards.length}장`} style={{ gridColumn: '1 / -1', display: 'flex', flexDirection: 'column', gap: gap.sm, margin: '0 0 18px', minWidth: 0 }}>
      <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.lg, color: C.ink }}>여행 엽서</span>
        <span style={{ fontSize: fz.sm, color: C.faint }}>{cards.length}장</span>
      </span>
      {cards.map((c) => (
        <article
          key={c.seq} data-part="postcard" data-seq={c.seq}
          style={{
            display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto', gap: '4px 10px', alignItems: 'start',
            padding: '12px 14px', borderRadius: radius.sm, background: C.paper,
            border: `1px solid ${ink(.12)}`, boxShadow: `0 2px 6px ${ink(.08)}`, minWidth: 0,
          }}
        >
          <span style={{ fontFamily: GAEGU, fontSize: fz.lg, lineHeight: 1.35, color: C.ink, overflowWrap: 'anywhere' }}>{c.line}</span>
          {/* 우표 자리 — 어디서 보냈는가. 글자만으로 말한다(아이콘·이모지 안 씀). */}
          <span style={{ gridRow: 'span 2', padding: '4px 8px', borderRadius: 4, border: `1.5px dashed ${ink(.25)}`, fontSize: fz.sm, color: C.sub, whiteSpace: 'nowrap' }}>
            {PLACE[c.place] ?? '어딘가'}
          </span>
          <span style={{ fontSize: fz.xs, color: C.faint }}>{postcardDay(c.at)}</span>
        </article>
      ))}
    </section>
  );
}

function Wall({ y,frameViewed }: { y: Yeoul;frameViewed:boolean }) {
  const tr=useUiText();
  const w = y.v.wall;
  const live = useLive();
  const t = useRewardsCopy();
  // Only the retired SHARE tutorial skips the menu; firstIntro v2/v3 always starts at the hub.
  const tutorialAlbum=live.pet?.firstIntro==null&&(!!w.tutGuide||y.v.frame.tutSave);
  const introKey=!y.s.sampleMode?live.pet?.firstIntro?.key:null;
  const [view,setView] = useState<'hub'|'album'|'diary'|'room'>(()=>tutorialAlbum?'album':'hub');
  const [leaving,setLeaving]=useState(false);
  const [leaveError,setLeaveError]=useState('');
  const leaveLocked=useRef(false);
  // A completed list visit is acknowledged before either exit; opening only the hub is not a visit.
  const leaveAlbum=async(destination:'hub'|'room')=>{
    if(leaveLocked.current)return;
    leaveLocked.current=true;setLeaving(true);setLeaveError('');
    try{
      if(introKey==='ALBUM'&&frameViewed){
        const result=await live.tutorialSeen('ALBUM');
        if(!result.ok){setLeaveError(t.error);return;}
      }
      if(destination==='hub')setView('hub');else w.close();
    }catch{setLeaveError(t.error);}
    finally{leaveLocked.current=false;setLeaving(false);}
  };
  const firstOpen=w.frames.findIndex(frame=>frame.open);
  const localCurrent=w.walls.find(x=>x.bw==='2px');
  const [localChoice,setLocalChoice]=useState(localCurrent?.name ?? w.walls[0]?.name);
  const backStyle={minHeight:TAP_MIN,padding:'8px 12px',border:`1px solid ${C.line}`,borderRadius:radius.md,background:C.paper,color:C.ink,fontSize:fz.sm};
  useEffect(()=>{if(tutorialAlbum)setView('album');},[tutorialAlbum]);
  if(view==='diary')return <RewardsJournal onClose={()=>setView('hub')}/>;
  if(view==='room'&&!y.s.sampleMode&&live.petId!=null)return <BackgroundRoom onClose={()=>setView('hub')}/>;
  if(view==='hub'||view==='room')return <section data-part={view==='hub'?'album-hub':'local-background-room'} style={{position:'absolute',inset:0,zIndex:9,background:C.wallBg,color:C.ink,display:'flex',flexDirection:'column',animation:w.anim}}>
    <header style={{display:'flex',alignItems:'center',gap:10,padding:'14px 18px'}}><h2 style={{fontFamily:GAEGU,fontSize:fz.h2,margin:0,flex:1}}>{view==='hub'?t.title:t.backgrounds}</h2><button aria-label={tr('닫기')} onClick={w.close} style={{...backStyle,width:TAP_MIN}}>✕</button></header>
    <div style={{flex:1,minHeight:0,overflowY:'auto',padding:'4px 18px 18px'}}>
    {view==='hub'?<div style={{display:'grid',gap:12}}>{([
      ['album',t.album,t.albumDescription],['diary',t.diaryMenu,t.diaryDescription],['room',t.roomMenu,t.roomDescription],
    ] as const).map(([target,label,description])=><button key={target} data-action={`album-menu-${target}`} data-tut-hl={(introKey==='ALBUM'&&target==='album')||(introKey==='DIARY_INTRO'&&target==='diary')?'1':undefined} onClick={()=>setView(target)} style={{...backStyle,animation:(introKey==='ALBUM'&&target==='album')||(introKey==='DIARY_INTRO'&&target==='diary')?'yBlink 1.2s ease-in-out infinite':undefined,minHeight:76,width:'100%',padding:'14px 16px',display:'flex',gap:12,alignItems:'center',textAlign:'left'}}><span style={{flex:1,minWidth:0}}><strong style={{display:'block',fontSize:16}}>{label}</strong><span style={{display:'block',marginTop:5,lineHeight:1.5,fontSize:13,color:C.sub}}>{description}</span></span><span aria-hidden="true">›</span></button>)}</div>:<>
      <button style={backStyle} onClick={()=>setView('hub')}>{t.back}</button><p style={{fontSize:13,lineHeight:1.6}}>{t.sample}</p>
      <div style={{display:'grid',gridTemplateColumns:'repeat(2,minmax(0,1fr))',gap:12}}>{w.walls.map(x=><button key={x.name} data-local-background={x.name} aria-pressed={localChoice===x.name} onClick={()=>setLocalChoice(x.name)} style={{...backStyle,minWidth:0,borderColor:localChoice===x.name?C.ink:C.line}}><span style={{display:'block',height:60,background:x.color,borderRadius:6,marginBottom:8}}/>{x.name}<span style={{display:'block',minHeight:20}}>{localCurrent?.name===x.name?t.selected:localChoice===x.name?t.preview:''}</span></button>)}</div>
      <button data-action="apply-local-background" style={{...backStyle,marginTop:16}} disabled={localChoice===localCurrent?.name} onClick={()=>{w.walls.find(x=>x.name===localChoice)?.pick();}}>{t.apply}</button>
    </>}
    </div>
  </section>;
  return (
    <div data-part="wall" style={{ position: 'absolute', inset: 0, zIndex: 9, background: C.wallBg, display: 'flex', flexDirection: 'column', animation: w.anim }}>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'baseline', gap: gap.md, padding: '14px 18px 10px' }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, color: C.ink }}>{tr('함께한 순간')}</span>
        <span style={{ fontSize: fz.sm, color: C.faint }}>{w.count}</span>
        <span style={{ flex: 1 }} />
        {/* ★ 누르는 자리 44px(판정 5) — 동그라미는 28 그대로, 단추만 키운다. */}
        <button
          onClick={()=>void leaveAlbum('room')} disabled={leaving} aria-label={tr('닫기')}
          style={{ width: TAP_MIN, height: TAP_MIN, margin: '-8px -8px -8px 0', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', border: 'none', background: 'none', padding: 0 }}
        >
          <span style={{ width: 28, height: 28, borderRadius: radius.pill, border: `1px solid ${ink(.16)}`, background: paperA(.9), fontSize: fz.sm, color: C.sub, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>✕</span>
        </button>
      </div>

      <button data-action="album-list-back" data-tut-hl={introKey==='ALBUM'&&frameViewed?'1':undefined} disabled={leaving} style={{...backStyle,alignSelf:'flex-start',margin:'0 18px 12px',borderColor:introKey==='ALBUM'&&frameViewed?ACCENT:C.line,animation:introKey==='ALBUM'&&frameViewed?'yBlink 1.2s ease-in-out infinite':undefined}} onClick={()=>void leaveAlbum('hub')}>{leaving?t.loading:t.back}</button>
      {leaveError&&<p role="alert" style={{margin:'0 18px 12px',fontSize:13,lineHeight:1.6,color:C.ink}}>{leaveError}</p>}
      {introKey==='ALBUM'&&<p style={{margin:'0 18px 12px',fontSize:13,lineHeight:1.6,color:C.ink}}>{frameViewed?t.albumViewedGuide:t.albumGuide}</p>}
      {/* ★ 튜토리얼 7칸 1단계 안내(2026-10-10) — 빛나는 액자 하나를 누르라고 말한다. 판 위라 방의 안내 카드가
          가려지므로 여기서 한 줄로 잇는다. 튜토리얼 밖이면 안 그린다. */}
      {w.tutGuide && (
        <span data-part="album-tut-guide" style={{ flex: 'none', margin: '0 18px 8px', padding: '8px 12px', borderRadius: radius.sm, border: `1.5px solid ${ACCENT}`, background: C.paper, fontSize: fz.sm, color: C.ink }}>
          {w.tutGuide}
        </span>
      )}

      <div style={{
        flex: '1 1 auto', overflow: 'auto', padding: '6px 18px 18px',
        display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 14px',
        alignContent: 'start', alignItems: 'start',
        backgroundImage: `repeating-linear-gradient(90deg,${ink(.05)} 0 2px,transparent 2px 22px)`,
      }}>
        {/* ★ 여행 엽서(정본 16장 "앨범 = 도감 + 여행 엽서 + …") — **도감보다 위**다. 재회 판의 「앨범에서 보기」가
            여기로 오므로, 18칸 아래에 묻히면 폰 한 화면에서 안 보인다. 그리드 전 칸을 가로질러 한 장씩 쌓는다. */}
        <Postcards cards={live.album?.postcards ?? []} />
        {w.frames.map((f, i) => (
          <span key={i} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, alignSelf: 'start', margin: '0 0 22px' }}>
            {/* ★ 이 버튼 안에는 그림 한 장뿐이라 **화면 낭독기에는 "버튼" 으로만 들린다**
                (2026-09-21 실측 — 전 화면에서 이름 없는 버튼은 이 하나였다).
                밑에 붙은 이름표는 버튼 **밖**이라 이름이 되어 주지 못한다. 열림/잠김까지 말해 준다 —
                흐린 액자와 또렷한 액자의 차이는 눈으로만 갈리기 때문이다. */}
            <button
              onClick={f.tap}
              data-tut-hl={f.hl||(introKey==='ALBUM'&&!frameViewed&&i===firstOpen)?'1':undefined} data-action={i===firstOpen?'album-first-motion':undefined}
              aria-label={f.open ? f.name : `${f.name} · 아직 못 배운 모습 · ${f.cond}`}
              style={{ position: 'relative', width: '100%', height: 0, padding: '0 0 133%', boxSizing: 'content-box', border: `5px solid ${introKey==='ALBUM'&&!frameViewed&&i===firstOpen?ACCENT:f.bd}`, borderRadius: radius.frame, background: f.bg, boxShadow: f.shadow, overflow: 'hidden', animation: introKey==='ALBUM'&&!frameViewed&&i===firstOpen?'yBlink 1.2s ease-in-out infinite':f.anim }}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={(!y.s.sampleMode && albumImageUrl(live, f.key)) || spriteUrl(live, f.key, y.s.sampleMode)} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity, filter: f.filter }} />
            </button>
            <span style={{ fontSize: fz.xs, lineHeight: 1.35, textAlign: 'center', color: f.labelFg }}>{f.label}</span>
          </span>
        ))}
      </div>

      {!!live.pet?.attendanceRewards?.eligibleUnfulfilledCount&&<p style={{padding:'0 18px',fontSize:13}}>{t.preparing}</p>}
      {!!live.pet?.attendanceRewards?.ownedRewards.some(g=>g.kind==='MOTION')&&<div data-part="owned-moments" style={{flex:'0 1 auto',maxHeight:'36%',overflowY:'auto',padding:'8px 18px'}}><h3 style={{fontSize:16,margin:'0 0 8px'}}>{t.receivedMotions}</h3><div style={{display:'grid',gridTemplateColumns:'repeat(3,minmax(0,1fr))',gap:10}}>{live.pet.attendanceRewards.ownedRewards.filter(g=>g.kind==='MOTION').map(g=><figure key={g.slotKey} style={{margin:0}}><img src={g.url} alt={t.gifts} style={{width:'100%',height:120,objectFit:'contain'}}/></figure>)}</div></div>}

      <span style={{ flex: 'none', padding: '0 18px 16px', fontSize: fz.xs, color: C.faint }}>
        {tr('액자를 누르면 크게 볼 수 있어요')}{w.frames.some(f => !f.open) ? tr(' · 흐린 액자는 아직 열리지 않은 모습이에요') : ''}
      </span>
    </div>
  );
}

function FrameView({ y,onViewed,viewed }: { y: Yeoul;onViewed?:()=>void;viewed:boolean }) {
  const tr=useUiText();
  const f = y.v.frame;
  const t=useRewardsCopy();
  const contentRef=useRef<HTMLDivElement>(null);
  const fileActionsRef=useRef<HTMLSpanElement>(null);
  useEffect(()=>{
    const controls=fileActionsRef.current,root=contentRef.current;
    if(!f.open||!onViewed||!controls||!root)return;
    const visible=()=>{
      const box=controls.getBoundingClientRect(),viewport=root.getBoundingClientRect();
      if(document.visibilityState==='visible'&&box.width>0&&box.top>=viewport.top&&box.bottom<=viewport.bottom&&box.left>=viewport.left&&box.right<=viewport.right)onViewed();
    };
    const observer=new IntersectionObserver(visible,{root,threshold:1});observer.observe(controls);
    const frame=requestAnimationFrame(visible);document.addEventListener('visibilitychange',visible);
    return()=>{observer.disconnect();cancelAnimationFrame(frame);document.removeEventListener('visibilitychange',visible);};
  },[f.open,f.key,onViewed]);
  const live = useLive();
  // 내 아이의 해당 동작이 없으면 다른 동작/여울의 그림을 내려받지 않는다.
  const ownSrc = live.petId && !y.s.sampleMode ? albumImageUrl(live, f.key) : null;
  const src = ownSrc || spriteUrl(live, f.key, y.s.sampleMode);
  const fileSrc = live.petId && !y.s.sampleMode ? ownSrc : src;
  const fileName = imageFileName(live.pet?.name || y.s.petName, f.name);
  const [file, setFile] = useState<File | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    setFile(null); setNotice(f.open && !fileSrc ? '아직 그림이 준비되지 않았어요. 잠시 뒤 다시 열어 주세요.' : '');
    if (f.open && fileSrc) {
      void prepareImageFile(fileSrc, fileName).then((value) => {
        if (active) setFile(value);
      }).catch(() => {
        if (active) setNotice('아직 그림이 준비되지 않았어요. 잠시 뒤 다시 열어 주세요.');
      });
    }
    return () => { active = false; };
  }, [fileSrc, fileName, f.open]);
  // 인앱 브라우저는 <a download> 를 무시하기 쉽다(2026-10-08 #690). UA 는 클라이언트에서만 본다.
  const [inApp, setInApp] = useState(false);
  useEffect(() => { setInApp(detectInApp() != null); }, []);
  // 기록에 실을 동작 키·층(2026-10-08 — 어떤 자세를 저장하려 했는지가 지표에 안 남았다).
  const meta = { motion: f.key, layer: dexLayer(motionByKey(f.key)?.layer) };
  const record = (kind: 'SHARE' | 'DOWNLOAD') => {
    if (live.petId && !y.s.sampleMode) void live.shareMotion(f.key, kind);
  };
  const save = () => {
    if (!fileSrc || busy) return;
    setBusy(true);
    void downloadImage(fileSrc, fileName, meta).then((result) => {
      if (result.outcome === 'saved') {
        setNotice('파일 다운로드를 시작했어요. 다운로드한 파일을 확인해 주세요.');
        record('DOWNLOAD');
        f.onTaken();
      } else setNotice('파일을 받지 못했어요. Safari나 Chrome에서 다시 눌러 주세요.');
    }).finally(() => setBusy(false));
  };
  const share = () => {
    if (!file || busy) return;
    // 미리 읽은 File을 클릭 안에서 곧바로 넘겨 iOS의 사용자 활성화를 보존한다.
    setBusy(true);
    setNotice('');
    void shareImageFile(file, meta).then((result) => {
      if (result === 'shared') { setNotice('공유창에 파일을 전달했어요.'); record('SHARE'); f.onTaken(); }
      else if (result === 'cancelled') setNotice('공유를 취소했어요.');
      else if (result === 'unsupported') setNotice('이 브라우저에서는 파일 공유를 지원하지 않아요. 저장한 뒤 앱에서 파일을 첨부해 주세요.');
      else if (result === 'failed') setNotice('공유창을 열지 못했어요. 저장한 뒤 앱에서 파일을 첨부해 주세요.');
    }).finally(() => setBusy(false));
  };
  return (
    <div onClick={f.close} data-part="frame" style={{ position: 'absolute', inset: 0, zIndex: 10, background: 'rgba(0,0,0,.78)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 22, animation: 'yFadeIn .18s ease' }}>
      <div ref={contentRef} onClick={(e) => e.stopPropagation()} data-part="frame-content" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: onViewed?gap.md:gap.lg, width: 'min(300px,100%)', maxHeight:'100%', overflowY:'auto', boxSizing:'border-box', padding:'4px 0 max(4px, env(safe-area-inset-bottom))', animation: f.anim }}>
        <button onClick={f.close} data-action="frame-back" data-tut-hl={onViewed&&viewed?'1':undefined} aria-label={tr('앨범 목록으로 돌아가기')} style={{ alignSelf: 'flex-start', minHeight: TAP_MIN, padding: '10px 16px', borderRadius: radius.sm, border: `1px solid ${paperA(.6)}`, background: C.paper, color: C.ink, fontSize: fz.md, cursor: 'pointer',animation:onViewed&&viewed?'yBlink 1.2s ease-in-out infinite':undefined }}>{tr('← 뒤로')}</button>
        <span style={{ position: 'relative', width: 'min(190px,70%)', height: onViewed?'min(30dvh,260px)':0, flexShrink:0, padding: onViewed?0:'0 0 93%', boxSizing: 'content-box', border: `7px solid ${C.frameWood}`, borderRadius: radius.frame, background: C.paper, overflow: 'hidden', boxShadow: '0 12px 28px rgba(46,42,38,.32)' }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={src} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity }} />
        </span>
        <span style={{ fontFamily: GAEGU, fontSize: fz.h2, color: C2.onDark }}>{f.name}</span>
        {onViewed&&<p data-part="frame-intro-guide" style={{margin:0,fontSize:fz.sm,color:C2.onDark,lineHeight:1.6}}>{t.frameGuide}</p>}
        {notice && <span role="status" data-part="album-file-notice" style={{ fontSize: fz.sm, color: C2.onDark, textAlign: 'center' }}>{tr(notice)}</span>}
        {f.locked && <span style={{ padding: '7px 14px', borderRadius: radius.pill, background: paperA(.16), fontSize: fz.sm, color: '#F3E9DC' }}>{f.cond}</span>}
        {f.open && inApp && (
          <span data-part="album-inapp-note" style={{ maxWidth: 300, fontSize: fz.sm, lineHeight: 1.5, color: C2.onDark, textAlign: 'center' }}>
            {tr('인앱에서는 저장이 안 될 수 있어요 — 그림을 길게 눌러 저장하거나 외부 브라우저로 열어 주세요(외부 브라우저에선 다시 로그인이 필요해요)')}
          </span>
        )}
        {/* ★ 튜토리얼 7칸 2단계 안내(2026-10-10) — 「저장」을 빛내고 한 줄로 말한다. */}
        {f.open && f.tutGuide && (
          <span data-part="frame-tut-guide" style={{ fontSize: fz.sm, color: C2.onDark, textAlign: 'center' }}>{f.tutGuide}</span>
        )}
        {f.open && (
          <span ref={fileActionsRef} data-part="frame-file-actions" style={{ display: 'flex', gap: gap.sm }}>
            {/* ★ 누르는 자리 `TAP_MIN`(2026-09-23 · 실측 39px). */}
            <button onClick={save} disabled={!fileSrc || busy} data-action="frame-save" data-tut-hl={f.tutSave ? '1' : undefined} style={{ minHeight: TAP_MIN, padding: '10px 18px', borderRadius: radius.sm, border: f.tutSave ? `2px solid ${ACCENT}` : 'none', background: C.paper, fontSize: fz.md, color: C.ink, animation: f.tutSave ? 'yBlink 1.2s ease-in-out infinite' : 'none' }}>{tr('저장')}</button>
            {/* 미리 읽은 애니메이션 파일을 OS 공유창에 전달한다. */}
            <button onClick={share} disabled={!file || busy} data-action="frame-share" style={{ minHeight: TAP_MIN, padding: '10px 18px', borderRadius: radius.sm, border: 'none', background: C.paper, fontSize: fz.md, color: C.ink }}>{tr(file || notice ? '공유' : '공유 준비 중…')}</button>
          </span>
        )}
      </div>
    </div>
  );
}
