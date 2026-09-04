// 스킨 A — 스크랩북. Claude Design 시안 '앱 스크랩북'을 옮긴 것.
//
// 종이 위에 붙여 모으는 앨범이라는 결. 캐릭터 자리는 폴라로이드가 되고,
// 게이지는 네 칸 도장, 쓰레기는 종이 부스러기, 자는 중에는 커튼이 덮인다. 하단 바는 인덱스 탭 모양이다.
//
// 규칙은 전부 useTamagotchi 에 있다. 이 파일은 그리기만 한다.
// 시안의 인라인 스타일을 그대로 옮겼다 — 픽셀이 어긋나면 비교가 무의미해지므로 임의로 정리하지 않았다.
//
// 2026-09-05 v2(PR2): 훈련·친구·시연 갈래를 걷어내고 v2 훅에 맞췄다. 부품 분리(parts/)는 PR3.
'use client';

import { useCallback, useEffect, useRef, type CSSProperties } from 'react';
import { track } from '@common/analytics';
import AuthModal from '@common/auth/AuthModal';
import FeedbackSheet from '../FeedbackSheet';
import GameSection from '../GameSection';
import { PERSONALITY_GROUPS } from '../chat';
import { ASSET, BACKGROUNDS, YEOUL, YEOUL_LOOP, bgUrl, josa } from '../constants';
import { MAX_GAUGE } from '../rules';
import { useDex } from '../useDex';
import { useTamagotchi, type ActionKey } from '../useTamagotchi';
import { useZzalSession } from '../useZzalSession';
import '../tamagotchi.css';

/** 남은 시간 한마디. 밤잠은 몇 시간이라 초로만 적으면 "10800초" 가 된다. */
function leftLabel(sec: number): string {
  const s = Math.max(0, Math.ceil(sec));
  if (s < 60) return `${s}초`;
  if (s < 3600) return `${Math.ceil(s / 60)}분`;
  return `${Math.floor(s / 3600)}시간 ${Math.round((s % 3600) / 60)}분`;
}

const PEN = "'Nanum Pen Script',cursive";
const GAEGU = "'Gaegu',cursive";
const MONO = "'Nanum Gothic Coding',monospace";
const INK = '#3A352B';
const SUB = '#7E7561';
const RED = '#B4614C';
const GRN = '#7C9463';
const PAPER = '#FFFDF6';
const EDGE = '#E0D7C0';

export interface SkinProps {
  /** phone = 세로 한 폭, pc = 가로로 펼친 3단. 캐릭터 자리 크기가 달라진다. */
  mode?: 'phone' | 'pc';
}

export default function Scrapbook({ mode = 'phone' }: SkinProps) {
  // ★ 화면이 서버를 직접 부르지 않는다. 세션이 "누구의 어떤 아이인가" 를 정해 손잡이를
  //   만들어 주고, useTamagotchi 가 그 손잡이로 돈다. 스킨은 그리기만 한다.
  //   디자인 확인은 주소창 `?mock=1`(목 서버)로 한다 — 시연 갈래는 없다.
  const session = useZzalSession();
  const { state: s, can, derived, actions, sample, form, ui, chat } =
    useTamagotchi({ server: session.server });
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => { track('zzal_landing_viewed'); }, []);

  // 무대 배경 — 서버가 준 값(기본 'room'). 바꾸기는 2층 4종 뒤(features.background).
  const bg = BACKGROUNDS.some((b) => b.key === s.background) ? s.background : BACKGROUNDS[0].key;

  /** 무대를 탭하면 쓰다듬는다(시안에서 가져온 것). 조건이 안 되면 아무 일도 없다. */
  const petIfPossible = () => { if (can('pet')) actions.pet(); };

  const go = useCallback((key: string) => {
    const el = scroller.current?.querySelector<HTMLElement>(`[data-sec="${key}"]`);
    if (el) scroller.current?.scrollTo({ top: el.offsetTop, behavior: 'smooth' });
  }, []);
  const goUpload = () => { ui.openUpload(); go('upload'); };
  const submit = () => { void form.submit(() => go('dama')); };

  const pc = mode === 'pc';
  // 캐릭터 그림은 원본이 가로 313px 이다. 그보다 키우면 흐려지므로
  // 폰에서는 210, PC 에서도 380 에서 멈춘다. PC 의 남는 가로는 옆 단이 채운다.
  const w = pc ? 380 : 210;

  const tape = (extra: CSSProperties): CSSProperties => ({
    position: 'absolute', width: 68, height: 21,
    background: 'linear-gradient(180deg, rgba(226,208,160,.72), rgba(214,193,142,.62))',
    borderLeft: '1px solid rgba(196,175,124,.5)', borderRight: '1px solid rgba(196,175,124,.5)',
    boxShadow: '0 1px 2px rgba(120,102,64,.16)',
    ...extra,
  });

  const corner = (extra: CSSProperties): CSSProperties => ({
    position: 'absolute', width: 15, height: 15, borderColor: '#CDBF9E', borderStyle: 'solid', pointerEvents: 'none',
    ...extra,
  });

  const paperBase: CSSProperties = {
    position: 'relative', background: PAPER, border: '1px solid ' + EDGE,
    borderRadius: 4, padding: pc ? '22px 20px' : '19px 15px',
    boxShadow: '3px 4px 0 rgba(58,53,43,.08), 0 1px 0 #fff inset',
  };

  const L = {
    wrap: { width: '100%', maxWidth: pc ? 1120 : '100%', margin: '0 auto', flex: '1 1 auto', minWidth: 0 } as CSSProperties,
    sec: { scrollSnapAlign: 'start', padding: pc ? '38px 40px 46px' : '30px 18px 38px' } as CSSProperties,
    damaSec: { scrollSnapAlign: 'start', minHeight: '100%', display: 'flex', alignItems: 'center', padding: pc ? '32px 40px' : '8px 18px 0' } as CSSProperties,
    damaGrid: { display: 'grid', gridTemplateColumns: pc ? '1fr auto 1fr' : '1fr', gap: pc ? 30 : 9, alignItems: 'center', width: '100%' } as CSSProperties,
    tryCol: { display: 'flex', flexDirection: 'column', gap: 16, width: '100%', maxWidth: pc ? 520 : '100%', marginLeft: pc ? 'auto' : 0, marginRight: pc ? 'auto' : 0 } as CSSProperties,
    formGrid: { display: 'grid', gridTemplateColumns: pc ? '300px 1fr' : '1fr', gap: pc ? 26 : 17 } as CSSProperties,
    dexGrid: { display: 'grid', gridTemplateColumns: pc ? 'repeat(4, 1fr)' : 'repeat(2, 1fr)', gap: pc ? 18 : 13 } as CSSProperties,
    friendGrid: { display: 'grid', gridTemplateColumns: pc ? 'repeat(2, 1fr)' : '1fr', gap: 12 } as CSSProperties,
    btnGrid: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: pc ? 10 : 5 } as CSSProperties,

    paper: paperBase,
    controlPaper: { ...paperBase, display: 'flex', flexDirection: 'column', gap: pc ? 14 : 10, padding: pc ? '18px 17px' : '11px 12px 12px' } as CSSProperties,
    notePaper: { ...paperBase, display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start', background: '#FFF9E4' } as CSSProperties,
    albumSheet: { position: 'relative', background: 'rgba(255,253,246,.72)', border: '1px solid ' + EDGE, borderRadius: 4, padding: pc ? 20 : 13, boxShadow: '3px 4px 0 rgba(58,53,43,.07)' } as CSSProperties,

    h1: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: pc ? 31 : 26, lineHeight: 1.36, textWrap: 'pretty', color: INK } as CSSProperties,
    h2: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: pc ? 25 : 22, color: INK } as CSSProperties,
    h3: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: 18, lineHeight: 1.55, color: INK } as CSSProperties,
    body: { margin: 0, fontSize: 14, lineHeight: 1.78, color: SUB, textWrap: 'pretty' } as CSSProperties,
    hand: { margin: 0, minHeight: pc ? 26 : 22, fontFamily: PEN, fontSize: 21, lineHeight: 1.1, color: '#5C5445', textAlign: 'center' } as CSSProperties,
    smallHand: { fontFamily: PEN, fontSize: 17, color: SUB } as CSSProperties,
    fieldLabel: { fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: '#5C5445' } as CSSProperties,
    gLabel: { fontFamily: GAEGU, fontWeight: 700, fontSize: 14, color: '#5C5445', width: 44, flex: '0 0 auto' } as CSSProperties,
    infoRow: { display: 'flex', gap: 12, flexWrap: 'wrap', fontFamily: MONO, fontSize: 11, color: '#A79C82', paddingTop: pc ? 9 : 7, borderTop: '1px dashed ' + EDGE } as CSSProperties,
    memo: { margin: 0, display: pc ? 'block' : 'none', padding: '13px 14px 14px', background: '#FFF9E4', border: '1px solid #EADFC0', borderRadius: 3, fontFamily: PEN, fontSize: 19, lineHeight: 1.5, color: '#5C5445', boxShadow: '2px 3px 0 rgba(58,53,43,.07)', transform: 'rotate(-.5deg)' } as CSSProperties,

    tape1: tape({ top: -10, left: 20, transform: 'rotate(-6deg)' }),
    tape2: tape({ bottom: -10, right: 24, width: 56, transform: 'rotate(4deg)', background: 'linear-gradient(180deg, rgba(196,123,98,.32), rgba(180,110,90,.26))' }),
    tapeTop: tape({ top: -11, left: '50%', marginLeft: -34, transform: 'rotate(-2deg)', zIndex: 3 }),

    polaroid: { position: 'relative', background: '#FFFEFA', padding: pc ? '13px 13px 0' : '8px 8px 0', border: '1px solid #EDE6D4', boxShadow: '4px 6px 0 rgba(58,53,43,.1)', transform: 'rotate(-1deg)', flex: '0 0 auto' } as CSSProperties,
    photo: { position: 'relative', width: w, maxWidth: '100%', aspectRatio: '313 / 350', background: '#F2EDDD', overflow: 'hidden' } as CSSProperties,
    polaroidCaption: { display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'center', padding: pc ? '13px 6px 15px' : '6px 6px 8px', minHeight: pc ? 56 : 34, justifyContent: 'center', color: '#5C5445' } as CSSProperties,
    floorLine: { position: 'absolute', left: '12%', right: '12%', bottom: '13%', height: 1, background: 'rgba(120,105,72,.22)' } as CSSProperties,

    charImg: { width: '100%', height: '100%', objectFit: 'contain', display: 'block' } as CSSProperties,

    egg: { width: 106, height: 138, borderRadius: '50% 50% 48% 48% / 62% 62% 38% 38%', background: 'linear-gradient(160deg,#FFFDF4,#EFE5CE 60%,#DCCFAF)', boxShadow: 'inset -8px -12px 16px rgba(140,120,84,.2)' } as CSSProperties,
    eggShaking: { width: 106, height: 138, borderRadius: '50% 50% 48% 48% / 62% 62% 38% 38%', background: 'linear-gradient(160deg,#FFFDF4,#EFE5CE 60%,#DCCFAF)', animation: 'tamaEggShake .18s ease-in-out infinite' } as CSSProperties,
    eggGhost: { width: 96, height: 126, borderRadius: '50% 50% 48% 48% / 62% 62% 38% 38%', border: '1.5px dashed #C9BD9C' } as CSSProperties,
    flash: { position: 'absolute', inset: 0, background: 'radial-gradient(circle at 50% 52%, #FFFBEA 0%, rgba(255,251,234,0) 60%)', animation: 'tamaCrackFlash .9s ease-in-out infinite' } as CSSProperties,
    trashVeil: { position: 'absolute', inset: 'auto 0 0', height: '54%', background: 'linear-gradient(rgba(150,132,96,0), rgba(132,114,80,.4))', pointerEvents: 'none' } as CSSProperties,
    tracing: { position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(232,226,206,.9)', animation: 'tamaVeilIn .6s ease-out both', pointerEvents: 'none' } as CSSProperties,
    dexTracing: { position: 'absolute', inset: 0, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', paddingTop: 10, background: 'rgba(236,231,214,.72)', pointerEvents: 'none' } as CSSProperties,

    cnrTL: corner({ top: 6, left: 6, borderWidth: '2px 0 0 2px' }),
    cnrTR: corner({ top: 6, right: 6, borderWidth: '2px 2px 0 0' }),
    cnrBL: corner({ bottom: 6, left: 6, borderWidth: '0 0 2px 2px' }),
    cnrBR: corner({ bottom: 6, right: 6, borderWidth: '0 2px 2px 0' }),
    corners: { position: 'relative', width: 62, height: 62 } as CSSProperties,

    nameTag: { fontFamily: GAEGU, fontWeight: 700, fontSize: pc ? 22 : 20, color: INK, padding: '5px 13px', background: PAPER, border: '1px solid ' + EDGE, borderRadius: 3, boxShadow: '2px 3px 0 rgba(58,53,43,.09)' } as CSSProperties,
    stickyNote: { fontFamily: PEN, fontSize: 18, color: '#5C5445', padding: '6px 12px 7px', background: '#FBEFA8', boxShadow: '2px 3px 0 rgba(58,53,43,.11)', transform: 'rotate(2deg)', whiteSpace: 'nowrap' } as CSSProperties,
    tabImg: { width: 34, height: 34, objectFit: 'contain', objectPosition: 'top', display: 'block' } as CSSProperties,
    addTag: { flex: '0 0 auto', minHeight: 52, padding: '0 14px', border: '1px dashed #C4B99B', borderRadius: 3, background: 'transparent', fontFamily: GAEGU, fontWeight: 700, fontSize: 14, color: SUB, cursor: 'pointer' } as CSSProperties,
    countTag: { fontFamily: MONO, fontSize: 12, color: '#5C5445', padding: '6px 11px', background: PAPER, border: '1px solid ' + EDGE, boxShadow: '2px 2px 0 rgba(58,53,43,.08)' } as CSSProperties,

    stitchTrack: { height: 9, borderRadius: 5, background: '#EDE6D2', border: '1px dashed #D6CBAE', overflow: 'hidden' } as CSSProperties,

    tagBtnA: { flex: 1, minHeight: 54, padding: '0 16px', border: '1px solid #A2543F', borderRadius: 3, background: RED, color: '#FFF8EC', fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.18)' } as CSSProperties,
    tagBtnB: { flex: 1, minHeight: 54, padding: '0 16px', border: '1px solid ' + EDGE, borderRadius: 3, background: PAPER, color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.1)' } as CSSProperties,
    cta: { minHeight: 58, border: '1px solid #2F2A22', borderRadius: 3, background: INK, color: '#FFF8EC', fontFamily: GAEGU, fontWeight: 700, fontSize: 17, cursor: 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.2)' } as CSSProperties,
    smallTag: { minHeight: 52, padding: '0 15px', border: '1px solid ' + EDGE, borderRadius: 3, background: '#FFF9E4', color: '#5C5445', fontFamily: GAEGU, fontWeight: 700, fontSize: 14, cursor: 'pointer', flex: '0 0 auto', boxShadow: '2px 3px 0 rgba(58,53,43,.09)' } as CSSProperties,
    miniGhost: { flex: 1, minHeight: 40, border: '1px solid ' + EDGE, borderRadius: 3, background: PAPER, color: '#5C5445', fontFamily: GAEGU, fontWeight: 700, fontSize: 13, cursor: 'pointer' } as CSSProperties,
    miniSolid: { flex: 1, minHeight: 40, border: '1px solid #A2543F', borderRadius: 3, background: RED, color: '#FFF8EC', fontFamily: GAEGU, fontWeight: 700, fontSize: 13, cursor: 'pointer' } as CSSProperties,

    input: { flex: 1, minWidth: 0, minHeight: 52, padding: '0 14px', border: 'none', borderBottom: '2px solid #D6CBAE', borderRadius: 0, background: 'rgba(255,255,255,.5)', color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 17 } as CSSProperties,
    textarea: { padding: '12px 14px', border: 'none', borderBottom: '2px solid #D6CBAE', background: 'rgba(255,255,255,.5)', color: INK, fontFamily: GAEGU, fontSize: 16, lineHeight: 1.7, resize: 'none' } as CSSProperties,
    checkRow: { display: 'flex', alignItems: 'center', gap: 11, minHeight: 56, padding: '0 14px', border: '1px dashed #D6CBAE', borderRadius: 3, background: 'rgba(255,255,255,.45)', cursor: 'pointer' } as CSSProperties,
    drop: { display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: pc ? 300 : 196, padding: 20, border: '2px dashed ' + (s.form.img ? RED : '#CDBF9E'), borderRadius: 3, background: 'rgba(255,255,255,.5)' } as CSSProperties,
    dropSm: { display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 94, padding: 14, border: '2px dashed #CDBF9E', borderRadius: 3, background: 'rgba(255,255,255,.5)' } as CSSProperties,

    friendPhoto: { width: 54, height: 54, background: '#FFFEFA', border: '1px solid #EDE6D4', padding: 3, overflow: 'hidden', flex: '0 0 auto', display: 'flex', alignItems: 'flex-start', justifyContent: 'center', boxShadow: '2px 2px 0 rgba(58,53,43,.1)' } as CSSProperties,
    friendImg: { width: 48, height: 62, objectFit: 'contain', objectPosition: 'top', filter: 'saturate(.72)' } as CSSProperties,

    nav: { position: 'absolute', left: 0, right: 0, bottom: 0, padding: '0 10px 12px', background: 'linear-gradient(rgba(243,238,224,0), rgba(243,238,224,.96) 26%)', display: 'flex', gap: 5, alignItems: 'flex-end', zIndex: 30 } as CSSProperties,
    sheet: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '88%', overflowY: 'auto', background: '#F6F1E1', borderTop: '1px solid ' + EDGE, borderRadius: '6px 6px 0 0', padding: '14px 18px 26px', animation: 'tamaSheetUp .34s cubic-bezier(.2,.8,.2,1) both' } as CSSProperties,
    // ── 다마고치 섹션(2026-08-30 Cream Minimal v2 구조) ────────────────
    stageCol: { display: 'flex', flexDirection: 'column', gap: pc ? 12 : 9, width: '100%', maxWidth: pc ? 420 : 340, margin: '0 auto' } as CSSProperties,
    topRow: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '0 2px' } as CSSProperties,
    charName: { fontFamily: GAEGU, fontWeight: 700, fontSize: pc ? 24 : 21, color: INK, lineHeight: 1.2 } as CSSProperties,
    charSub: { fontSize: 12, color: SUB, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } as CSSProperties,
    addBtn: { width: 40, height: 40, borderRadius: 12, border: '1px dashed #C4B99B', background: 'transparent', color: SUB, fontSize: 17, cursor: 'pointer', flex: '0 0 auto' } as CSSProperties,

    /**
     * 방 — 배경 그림이 이 안을 채우고 캐릭터가 그 위에 선다.
     *
     * ★비율을 배경과 같은 1:1 로 둔다. 무대를 세로로 길게 잡으면 cover 가 위아래를 잘라
     *   벚꽃 가지처럼 위쪽에 있는 것이 통째로 사라진다(실제로 잘렸다).
     * ★모서리는 살짝만 둥글린다 — 아치로 크게 깎으면 배경 윗부분이 또 잘려 나간다.
     */
    room: {
      position: 'relative', width: '100%', aspectRatio: '1 / 1',
      borderRadius: 20, overflow: 'hidden',
      background: '#F2E5D7', boxShadow: 'inset 0 1px 0 rgba(255,255,255,.6), 0 8px 20px rgba(120,100,80,.14)',
    } as CSSProperties,
    roomBg: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', display: 'block' } as CSSProperties,
    roomEmpty: { position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, background: 'rgba(247,243,235,.72)' } as CSSProperties,
    /** 알·쓰레기·커튼처럼 무대를 통째로 덮는 한 장. */
    layer: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block' } as CSSProperties,

    /**
     * 캐릭터는 무대 안에서 가로로만 움직인다. 세로는 바닥에 고정.
     * ★무대보다 작아야 '방 안에 서 있다'가 된다 — 같은 크기로 채우면 방이 사라지고
     *   배경도 안 보인다(시안 비율은 방 높이의 약 57%다).
     *   발이 바닥선(무대 아래에서 약 1/4 지점)에 닿게 bottom 을 맞춘다.
     */
    charBox: {
      position: 'absolute', left: '50%', bottom: '13%', width: '58%', aspectRatio: '313 / 350',
      marginLeft: '-29%', transformOrigin: '50% 100%', transition: 'transform .12s linear',
    } as CSSProperties,
    shadow: {
      position: 'absolute', left: '50%', bottom: '11.5%', width: '26%', height: '3%', marginLeft: '-13%',
      borderRadius: '50%', background: 'rgba(140,110,85,.26)', filter: 'blur(3px)',
      transition: 'transform .12s linear', pointerEvents: 'none',
    } as CSSProperties,
    bubble: {
      position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)',
      padding: '8px 14px', borderRadius: '16px 16px 16px 5px', background: '#FFFDF9',
      color: '#5C5445', fontFamily: PEN, fontSize: 18, whiteSpace: 'nowrap',
      boxShadow: '0 6px 14px rgba(120,100,80,.16)', pointerEvents: 'none',
    } as CSSProperties,

    guide: {
      display: 'flex', alignItems: 'center', gap: 9, alignSelf: 'center',
      padding: '8px 14px 9px', maxWidth: '100%',
      background: '#FBEFA8', boxShadow: '2px 3px 0 rgba(58,53,43,.16)', transform: 'rotate(-1deg)',
    } as CSSProperties,
    panel: { ...paperBase, display: 'flex', flexDirection: 'column', gap: pc ? 14 : 11, padding: pc ? '16px 16px 15px' : '13px 13px 12px' } as CSSProperties,

    /* ── 말 걸기 (시안 Cream Minimal v2) ─────────────────────
       내 말은 오른쪽 아래에서 올라오고, 아이의 답은 무대 안 말풍선으로 나온다.
       ★두 말풍선이 겹치면 누가 한 말인지 헷갈리므로 자리를 위아래로 갈라 둔다. */
    myBubble: {
      position: 'absolute', right: 10, bottom: 10, maxWidth: '72%',
      padding: '7px 13px', borderRadius: '16px 16px 5px 16px', background: '#4A4438',
      color: '#FFFDF6', fontFamily: GAEGU, fontSize: 14, lineHeight: 1.45,
      textAlign: 'right', boxShadow: '0 6px 14px rgba(74,68,56,.24)', pointerEvents: 'none',
      animation: 'tamaPopIn .24s cubic-bezier(.34,1.56,.64,1) both',
    } as CSSProperties,
    typing: {
      position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)',
      display: 'flex', gap: 4, padding: '11px 14px', borderRadius: 16, background: '#FFFDF9',
      boxShadow: '0 6px 14px rgba(120,100,80,.16)', pointerEvents: 'none',
    } as CSSProperties,
    dot: { width: 6, height: 6, borderRadius: '50%', background: '#C6BCA2' } as CSSProperties,
    chatBar: {
      display: 'flex', alignItems: 'center', gap: 8, padding: '5px 5px 5px 14px',
      borderRadius: 999, background: 'rgba(255,255,255,.55)',
      boxShadow: 'inset 0 0 0 1.5px #E0D7C0',
    } as CSSProperties,
    chatInput: {
      flex: 1, minWidth: 0, height: 38, border: 'none', outline: 'none',
      background: 'transparent', color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 15,
    } as CSSProperties,
    sendBtn: {
      width: 34, height: 34, flex: '0 0 auto', border: 'none', borderRadius: '50%',
      background: RED, color: PAPER, cursor: 'pointer', fontSize: 15, lineHeight: 1,
      display: 'grid', placeItems: 'center',
    } as CSSProperties,
    badge: {
      position: 'absolute', top: -7, right: -5, padding: '2px 6px', borderRadius: 9,
      background: RED, color: '#FFF8EC', fontFamily: GAEGU, fontWeight: 700, fontSize: 11,
      boxShadow: '1px 2px 0 rgba(58,53,43,.2)',
    } as CSSProperties,

    toast: { pointerEvents: 'none', position: 'absolute', left: '50%', bottom: 96, transform: 'translateX(-50%) rotate(-1.5deg)', zIndex: 60, padding: '10px 18px 11px', background: '#FBEFA8', color: '#4A4438', fontFamily: PEN, fontSize: 20, whiteSpace: 'nowrap', boxShadow: '2px 3px 0 rgba(58,53,43,.18)', animation: 'tamaRiseIn .24s ease-out both' } as CSSProperties,
  };

  // 게이지 한 칸. 채운 칸은 색이 아니라 도장 모양으로도 구분된다(색만으로 가르지 않는다).
  const cell = (on: boolean, c: string, mark: string) => ({
    mark: on ? mark : '',
    style: {
      height: 26, flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
      borderRadius: 3, border: on ? '1px solid ' + c : '1px dashed #D2C6A8',
      background: on ? c : 'rgba(255,255,255,.4)', color: '#FFF8EC', fontSize: 13,
      fontFamily: GAEGU, fontWeight: 700, lineHeight: 1,
      transform: on ? `rotate(${(mark === '♥' ? -1 : 1) * 2}deg)` : 'none',
      transition: 'background .3s ease, border-color .3s ease',
    } as CSSProperties,
  });
  const cells = (v: number, c: string, mark: string) =>
    Array.from({ length: MAX_GAUGE }, (_, i) => cell(i < v, c, mark));

  // 게이지를 보기 전에 캐릭터가 먼저 말한다. 게이지는 확인용이다.
  const status = !s.hasChar ? '기다리는 중'
    : derived.failed ? '태어나지 못했어요'
    : s.sleeping ? (s.canWake ? '깨워도 돼요' : (s.sleepKind === 'NAP' ? '낮잠 중' : '자는 중'))
    : s.phase === 'egg' ? '품는 중' : s.phase === 'hatching' ? '지금 나오려 해요'
    : s.sick ? '아파요' : s.fullness <= 0 ? '배고파해요' : s.happiness <= 0 ? '시무룩해요'
    : s.trash >= 3 ? '바닥이 어질러졌어요' : s.canSleep ? '재울 수 있어요' : '평상시';

  // 캐릭터는 CSS 로 흔들지 않는다 — 그림 자체가 이미 2프레임으로 움직인다(v1 주석 그대로).
  // 배부름 0 이면 0.7배(정본 §4)만 idleBehavior 가 scale 로 준다.

  // 쓰레기는 캐릭터 **앞**에 겹치는 한 장이다(자캐 자체는 더러워지지 않는다).
  // 단계가 오를수록 더 많이 가리고, 5단계면 거의 안 보인다.
  const trashImg = s.trash > 0 ? ASSET.trash[Math.min(s.trash, ASSET.trash.length) - 1].src : null;

  const fxStyle = (o: { x: number }): CSSProperties => ({
    position: 'absolute', left: o.x + '%', bottom: '30%', fontFamily: PEN, fontSize: 24, color: RED,
    animation: 'tamaFloatUp 1.5s ease-out both', pointerEvents: 'none',
  });

  const ROT = [-1.5, 1, -.7, 1.6, -1.2, .8, -1];
  // 스크린리더가 "밥 3개" 대신 "밥 주기" 로 읽게 한다.
  // 못 누를 때만 aria-disabled 를 붙인다 — aria-disabled="false" 를 비활성으로
  // 읽어 버리는 도구가 있어서(자동 검증이 실제로 그렇게 읽었다) 눌릴 때는 속성을 뺀다.
  const ARIA: Record<ActionKey, string> = {
    feed: '밥 주기', snack: '간식 주기', pet: '쓰다듬기', clean: '청소하기', bath: '목욕시키기', medicine: '약 주기',
    sleep: s.sleeping ? '깨우기' : '불 끄고 재우기',
  };
  const btn = (key: ActionKey, label: string, sub: string, i: number, hint?: string) => {
    const on = can(key);
    // 부름이 지금 기다리는 버튼은 눈에 띄게 둔다(잠그지 않는다) — 헤매지 않는 게 첫날의 전부다.
    const want = derived.call?.want === key;
    return {
      key, label, sub, hint, act: actions[key],
      style: {
        minHeight: pc ? 70 : 64, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 3,
        border: '1px solid ' + (want ? RED : on ? EDGE : '#E8E1CD'), borderRadius: 3,
        background: want ? '#FFF4E2' : on ? PAPER : 'rgba(240,235,220,.7)',
        color: on ? INK : '#B0A68C',
        opacity: on ? 1 : .62, cursor: on ? 'pointer' : 'default', padding: '6px 2px',
        boxShadow: want ? '0 0 0 2px rgba(180,97,76,.18), 2px 3px 0 rgba(58,53,43,.11)'
          : on ? '2px 3px 0 rgba(58,53,43,.11)' : 'none',
        transform: on ? `rotate(${ROT[i]}deg)` : 'none',
        transition: 'all .22s ease',
      } as CSSProperties,
      pinStyle: { width: 9, height: 9, borderRadius: 5, background: on ? RED : '#D2C6A8', boxShadow: on ? '0 1px 0 rgba(0,0,0,.15)' : 'none' } as CSSProperties,
    };
  };

  // 밥은 시간이 지나면 찬다. 남은 시간은 재고가 덜 찼을 때만 말한다 —
  // 가득인데 카운트다운이 보이면 "못 논다"는 신호가 된다.
  const foodSub = s.food > 0
    ? `${s.food}개`
    : s.foodLeft > 0 ? `${leftLabel(s.foodLeft)} 뒤` : '없음';

  // 자는 동안에는 같은 자리가 깨우기가 된다(useTamagotchi 의 sleep 주석 참고).
  const sleepLabel = s.sleeping ? '깨우기' : '재우기';
  const sleepSub = s.sleeping
    ? (s.canWake ? '깨워도 돼요' : leftLabel(s.sleepLeft))
    : s.canSleep ? (derived.tutorial ? '낮잠' : '준비됐어요') : '저녁 7시부터';

  const buttons = [
    btn('feed', '밥', foodSub, 0),
    btn('snack', '간식', s.sick ? '아파요' : '', 1),
    btn('pet', '쓰다듬', '', 2),
    btn('clean', '청소', s.trash > 0 ? `${s.trash}개` : '깨끗', 3),
    btn('bath', '목욕', can('bath') || s.sleeping ? '' : '오늘 했어요', 4),
    btn('medicine', '약', s.sick ? '필요해요' : '', 5),
    btn('sleep', sleepLabel, sleepSub, 6),
  ];

  const chars = s.chars.length ? s.chars : [{ name: '빈 자리', note: '' }];
  const cur = chars[Math.min(s.active, chars.length - 1)];
  const charTabs = s.chars.map((c, i) => ({
    name: c.name, img: s.imgUrl || YEOUL, pick: () => ui.pickChar(i),
    style: {
      flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8, minHeight: 52, padding: '0 12px',
      border: '1px solid ' + (i === s.active ? RED : EDGE), borderRadius: 3,
      background: i === s.active ? '#FFF4E2' : PAPER, color: INK, cursor: 'pointer',
      boxShadow: '2px 3px 0 rgba(58,53,43,.09)',
    } as CSSProperties,
  }));

  // 도감은 서버가 정본이다 — 18칸 전부 이름·조건이 온다(정본 §6). 잠긴 칸에는 여울이가 흐리게 대신 선다.
  const dex = useDex({
    motions: s.motions, pc, say: ui.say,
    petName: session.server?.pet?.name,
    onShared: (key, kind) => { void actions.share(key, kind); },
  });

  // 하단 바는 탭이 아니라 섹션 점프다. 앱으로 낼 때 그대로 탭이 된다.
  const tabDefs: [string, string][] = (s.hasChar || derived.booting)
    ? [['dama', '다마고치'], ['game', '놀이'], ['dex', '도감']]
    : [['try', '여울'], ['upload', '올리기'], ['dama', '다마고치'], ['dex', '도감']];
  const TABC = ['#FBEFA8', '#E6EFC9', '#F6DFC9', '#DFE7F1'];
  const tabs = tabDefs.map((t, i) => ({
    label: t[1], go: () => go(t[0]),
    style: {
      flex: 1, minHeight: 54, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 4,
      border: '1px solid ' + EDGE, borderBottom: 'none', borderRadius: '8px 8px 0 0',
      background: TABC[i % 4], color: '#4A4438', cursor: 'pointer',
      paddingTop: i % 2 ? 4 : 0, boxShadow: '0 -2px 0 rgba(58,53,43,.05)',
    } as CSSProperties,
    dot: { width: 7, height: 7, borderRadius: 4, background: t[0] === 'dama' && s.hasChar ? RED : '#BFB49A' } as CSSProperties,
  }));

  // 서버 모드에서는 그림도 필수다(이름·그림 필수, 세부사항은 선택).
  const canSubmit = derived.canSubmit && !derived.creating;
  const submitStyle: CSSProperties = {
    minHeight: 58, border: '1px solid ' + (canSubmit ? '#2F2A22' : '#DCD2B8'), borderRadius: 3,
    background: canSubmit ? INK : 'rgba(230,224,206,.8)', color: canSubmit ? '#FFF8EC' : '#A79C82',
    fontFamily: GAEGU, fontWeight: 700, fontSize: 17,
    cursor: canSubmit ? 'pointer' : 'default',
    boxShadow: canSubmit ? '2px 3px 0 rgba(58,53,43,.2)' : 'none', transition: 'all .22s ease',
  };
  const submitLabel = derived.creating ? '데려오는 중…' : '알로 데려오기';
  const imgUrl = s.imgUrl || YEOUL;
  // ★ 상태에 따라 그림이 바뀐다. 게이지를 읽기 전에 이걸 먼저 본다.
  //   서버가 준 동작 그림(basicImageKey)이 정본이고, 아직 없으면 여울 폴백(useTamagotchi.derived.imageFor).
  const moodImg = derived.motionImg;

  /** 가로 막대 게이지. 시안대로 아래에 모아 둔다. 오른쪽에 상태를 한마디로 덧댈 수 있다. */
  const bar = (label: string, v: number, max: number, color: string, note?: string) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <span style={L.gLabel}>{label}</span>
        {note && <span style={{ fontFamily: GAEGU, fontSize: 12.5, color: GRN }}>{note}</span>}
      </div>
      <div style={{ height: 6, borderRadius: 4, background: '#ECE3D6', overflow: 'hidden' }}>
        <div style={{ width: `${(v / max) * 100}%`, height: '100%', borderRadius: 4, background: color, transition: 'width .35s ease' }} />
      </div>
    </div>
  );

  const gaugeRow = (label: string, list: ReturnType<typeof cells>) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <span style={L.gLabel}>{label}</span>
      <div style={{ display: 'flex', gap: 5, flex: 1 }}>
        {list.map((c, i) => <div key={i} style={c.style}>{c.mark}</div>)}
      </div>
    </div>
  );

  const uploadFields = (compact: boolean) => (
    <>
      <div style={{ display: 'flex', gap: 8 }}>
        <input value={s.form.name} onChange={e => form.patchForm({ name: e.target.value })} placeholder={compact ? '이름' : '아이의 이름'} style={L.input} />
        <button onClick={form.randomName} style={L.smallTag}>랜덤</button>
      </div>
      <textarea
        value={s.form.note}
        onChange={e => form.patchForm({ note: e.target.value })}
        rows={compact ? 2 : 3}
        placeholder={compact ? '하고 싶은 말 (선택)' : '조용하지만 고집이 세요'}
        style={L.textarea}
      />
      <label style={L.checkRow}>
        <input type="checkbox" checked={s.form.agree} onChange={e => form.patchForm({ agree: e.target.checked })} style={{ width: 20, height: 20, accentColor: RED, flex: '0 0 auto' }} />
        <span style={{ fontSize: 14, color: '#4A4438' }}>제가 그린 그림이 맞습니다</span>
      </label>
      <button onClick={submit} disabled={!canSubmit} style={submitStyle}>{submitLabel}</button>
    </>
  );

  return (
    <div
      className="tama"
      style={{
        position: 'absolute', inset: 0, overflow: 'hidden', background: '#F3EEE0',
        backgroundImage: 'linear-gradient(#E5DDC6 1px,transparent 1px),linear-gradient(90deg,#E5DDC6 1px,transparent 1px)',
        backgroundSize: '22px 22px', color: INK,
        fontFamily: "'Gowun Dodum',system-ui,sans-serif", WebkitFontSmoothing: 'antialiased',
      }}
    >
      <div
        ref={scroller}
        style={{ position: 'absolute', inset: 0, overflowY: 'auto', overflowX: 'hidden', overscrollBehavior: 'contain', scrollSnapType: 'y proximity', paddingBottom: 98 }}
      >
        {/* 섹션 1 — 여울 체험. 다 자란 여울을 직접 만져보는 자리.
            ★ booting 동안에는 안 그린다 — 이미 키우는 사람에게 이 장이 스치면
              "내 아이가 사라졌나" 로 읽힌다. */}
        {!s.hasChar && !derived.booting && (
          <section data-sec="try" style={L.sec}>
            <div style={L.wrap}>
              <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontFamily: PEN, fontSize: 22, color: RED, lineHeight: 1 }}>첫 장</span>
                  <h1 style={L.h1}>눌러보세요.<br />여울이가 대답합니다</h1>
                </div>
                <span style={{ fontFamily: PEN, fontSize: 19, color: '#A79C82', whiteSpace: 'nowrap', transform: 'rotate(-3deg)' }}>p. 1</span>
              </div>

              <div style={L.tryCol}>
                <div style={L.paper}>
                  <span style={L.tape1} />
                  <span style={L.tape2} />
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 15 }}>
                    <div style={L.polaroid}>
                      <div style={L.photo}>
                        <div style={L.floorLine} />
                        <img src={YEOUL_LOOP} alt="여울이 노는 모습" style={L.charImg} />
                        {s.sampleFx.map(fx => <span key={fx.id} style={fxStyle(fx)}>{fx.text}</span>)}
                      </div>
                      <div style={L.polaroidCaption}>
                        <span style={{ fontFamily: PEN, fontSize: 21, lineHeight: 1 }}>여울이 · 다 자란 모습</span>
                      </div>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, width: '100%', maxWidth: 272 }}>
                      {gaugeRow('포만감', cells(s.sFull, RED, '●'))}
                      {gaugeRow('행복', cells(s.sHappy, GRN, '♥'))}
                    </div>

                    <p style={L.hand}>{s.sampleLine || '눌러보면 반응해요'}</p>

                    <div style={{ display: 'flex', gap: 11, width: '100%', maxWidth: 292 }}>
                      <button onClick={sample.feed} style={L.tagBtnA}>밥 주기</button>
                      <button onClick={sample.pet} style={L.tagBtnB}>쓰다듬기</button>
                    </div>
                  </div>
                </div>

                <button onClick={goUpload} style={L.cta}>내 아이 데려오기</button>
              </div>
            </div>
          </section>
        )}

        {/* 섹션 2 — 올리기. 여기서 누르면 아래 섹션으로 알이 떨어진다. */}
        {!s.hasChar && !derived.booting && (
          <section data-sec="upload" style={L.sec}>
            <div style={L.wrap}>
              <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontFamily: PEN, fontSize: 22, color: RED, lineHeight: 1 }}>붙이는 자리</span>
                  <h2 style={L.h2}>아이를 소개해 주세요</h2>
                </div>
                <span style={{ fontFamily: PEN, fontSize: 19, color: '#A79C82', transform: 'rotate(2deg)' }}>p. 2</span>
              </div>

              <div style={L.paper}>
                <span style={L.tape1} />
                <div style={L.formGrid}>
                  <label style={{ display: 'block', cursor: 'pointer' }}>
                    <div style={L.drop}>
                      {s.form.img ? (
                        <img src={s.form.img} alt="올린 그림" style={{ maxWidth: 158, maxHeight: 196, objectFit: 'contain', display: 'block' }} />
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 11 }}>
                          <div style={L.corners}>
                            <span style={L.cnrTL} /><span style={L.cnrTR} /><span style={L.cnrBL} /><span style={L.cnrBR} />
                          </div>
                          <span style={{ fontFamily: PEN, fontSize: 23, color: SUB }}>여기에 그림 한 장</span>
                          <span style={{ fontFamily: MONO, fontSize: 11, color: '#A79C82' }}>313 × 350 권장 · PNG</span>
                        </div>
                      )}
                    </div>
                    <input type="file" accept="image/*" onChange={form.onPickImg} style={{ display: 'none' }} />
                  </label>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 17 }}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                      <span style={L.fieldLabel}>이름</span>
                      <div style={{ display: 'flex', gap: 8 }}>
                        <input value={s.form.name} onChange={e => form.patchForm({ name: e.target.value })} placeholder="아이의 이름" style={L.input} />
                        <button onClick={form.randomName} style={L.smallTag}>랜덤</button>
                      </div>
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                      <span style={L.fieldLabel}>이 아이에 대해 하고 싶은 말 (선택)</span>
                      <textarea value={s.form.note} onChange={e => form.patchForm({ note: e.target.value })} rows={3} placeholder="조용하지만 고집이 세요" style={L.textarea} />
                    </div>
                    <label style={L.checkRow}>
                      <input type="checkbox" checked={s.form.agree} onChange={e => form.patchForm({ agree: e.target.checked })} style={{ width: 20, height: 20, accentColor: RED, flex: '0 0 auto' }} />
                      <span style={{ fontSize: 14, color: '#4A4438' }}>제가 그린 그림이 맞습니다</span>
                    </label>
                    <button onClick={submit} disabled={!canSubmit} style={submitStyle}>{submitLabel}</button>
                    <p style={L.smallHand}>올린 그림은 그대로 씁니다. 손대지 않아요.</p>
                  </div>
                </div>
              </div>
            </div>
          </section>
        )}

        {/* 섹션 3 — 다마고치. 알부터 잠들 때까지 전부 이 한 자리에서 일어난다.
            2026-08-30 'Cream Minimal v2' 시안 구조로 재작성 — 무대가 방이 되고,
            캐릭터가 그 안을 돌아다니며, 게이지는 아래에 가로 막대로 모였다.
            시안에 있던 코인·젬·레벨·상점은 우리 설계에 없어서 넣지 않았다. */}
        <section data-sec="dama" style={L.damaSec}>
          <div style={L.wrap}>
            <div style={L.stageCol}>

              {/* 이름 · 상태 · 캐릭터 전환 */}
              <div style={L.topRow}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
                  <span style={L.charName}>{s.hasChar ? cur.name : '빈 자리'}</span>
                  <span style={L.charSub} data-status={status}>
                    {status}{s.hasChar && s.phase === 'live' ? ` · ${s.daysTogether}일째 함께` : ''}{derived.mock ? ' · 목 서버' : ''}
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 7, alignItems: 'center', flex: '0 0 auto' }}>
                  {charTabs.map((c, i) => (
                    <button key={i} onClick={c.pick} style={c.style} aria-label={`${c.name} 보기`}>
                      <img src={c.img} alt="" style={L.tabImg} />
                    </button>
                  ))}
                  <button onClick={ui.openNew} style={L.addBtn} aria-label="새 캐릭터 추가">＋</button>
                </div>
              </div>

              {/* 무대 — 배경 위에 캐릭터가 서고, 그 앞에 쓰레기·커튼이 얹힌다 */}
              <div style={L.room}>
                <img src={bgUrl(bg)} alt="" style={L.roomBg} />

                {(!s.hasChar || derived.failed) && (
                  <div style={L.roomEmpty}>
                    <div style={L.eggGhost} />
                    <span style={{ fontFamily: PEN, fontSize: 20, color: '#8E8375' }}>
                      {derived.failed ? '아직 비어 있어요' : '알이 놓일 자리'}
                    </span>
                  </div>
                )}

                {s.phase === 'egg' && (
                  <div style={{
                    position: 'absolute', inset: 0,
                    animation: s.dropping ? 'tamaEggDrop 1.5s cubic-bezier(.3,.9,.3,1) both' : undefined,
                  }}>
                    <img src={s.eggT > 0.6 ? ASSET.eggCrack.src : ASSET.eggIdle.src} alt="알" style={L.layer} />
                  </div>
                )}

                {s.phase === 'hatching' && <img src={ASSET.eggHatch.src} alt="부화" style={L.layer} />}

                {s.phase === 'live' && (
                  <>
                    {/* 발밑 그림자 — 이게 있어야 바닥을 딛고 선 것처럼 보인다.
                        캐릭터를 따라 같이 움직인다. */}
                    <div style={L.shadow} />
                    <div
                      onClick={petIfPossible}
                      data-stage="char"
                      style={{
                        ...L.charBox,
                        transform: `scale(${derived.idle.scale})`,
                        cursor: can('pet') ? 'pointer' : 'default',
                      }}
                      role="button"
                      aria-label="쓰다듬기"
                    >
                      <img src={moodImg} alt={`${cur.name} · ${derived.motionKey}`} style={L.charImg} />
                    </div>
                  </>
                )}

                {/* 쓰레기는 바닥에 쌓여 캐릭터 앞을 가린다 — 캐릭터와 같은 자리·같은 크기로 겹친다. */}
                {trashImg && (
                  <img src={trashImg} alt={`쓰레기 ${s.trash}단계`}
                    style={{ ...L.charBox, transform: 'none', pointerEvents: 'none', objectFit: 'contain' }} />
                )}

                {s.sleeping && (
                  <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', animation: 'tamaVeilIn .6s ease-out both' }}>
                    <img src={ASSET.curtainClosed.src} alt="자는 중" style={L.layer} />
                    <img src={ASSET.moon.src} alt="" style={L.layer} />
                  </div>
                )}

                {s.fx.map(f => <span key={f.id} style={fxStyle(f)}>{f.text}</span>)}

                {/* 말풍선 — 아이가 방금 한 일을 스스로 말하거나, 말 건 것에 답한다.
                    말 건 답이 있으면 그쪽이 이긴다(사람이 방금 물어본 것이 더 급하다). */}
                {s.phase === 'live' && !s.sleeping && s.chatTyping && (
                  <div style={L.typing}>
                    <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out infinite' }} />
                    <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out .15s infinite' }} />
                    <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out .3s infinite' }} />
                  </div>
                )}
                {s.phase === 'live' && !s.sleeping && !s.chatTyping && (s.chatReply || s.standLine) && (
                  <div style={{ ...L.bubble, whiteSpace: s.chatReply ? 'normal' : 'nowrap', maxWidth: s.chatReply ? '78%' : undefined }}>
                    {s.chatReply || s.standLine}
                  </div>
                )}

                {/* 내가 방금 한 말 */}
                {!!s.chatUser && <div style={L.myBubble}>{s.chatUser}</div>}
              </div>

              {/* 부름 — 무대 바로 아래 한 줄. 아기 시간표·하루 3회 부름이 한 줄로 온다(useCalls). */}
              {derived.call && s.phase === 'live' && (
                <div style={L.guide} data-call={derived.call.key}>
                  {derived.calls.length > 1 && (
                    <span style={{ fontFamily: MONO, fontSize: 10.5, color: '#8E8375', flex: '0 0 auto' }}>
                      +{derived.calls.length - 1}
                    </span>
                  )}
                  <span style={{ fontFamily: PEN, fontSize: 19, color: '#4A4438', lineHeight: 1.15 }}>
                    {derived.call.line}
                  </span>
                </div>
              )}

              {/* 성격 고르기 — 12분 부름 "어떤 아이인가요". 언제든 다시 고를 수 있다(정본 §0 원칙 6). */}
              {derived.call?.want === 'personality' && s.phase === 'live' && (
                <div style={L.notePaper} data-sec="personality">
                  <span style={L.h3}>어떤 아이인가요</span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                    {PERSONALITY_GROUPS.map((g) => (
                      <button key={g.key} data-personality={g.key} onClick={() => void actions.choosePersonality(g.key)}
                        style={{ ...L.smallTag, border: '1px solid ' + (s.personality === g.key ? RED : EDGE) }} title={g.hint}>
                        {g.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* 품는 중 안내. 문구는 서버가 지금 하는 일을 사람 말로 준 것 그대로다. */}
              {s.phase === 'egg' && (
                <div style={L.notePaper}>
                  <span style={L.h3}>품는 중</span>
                  <p style={{ ...L.body, color: INK }}>{derived.eggLine}</p>
                  <span style={{ fontFamily: MONO, fontSize: 11, color: '#A79C82' }}>
                    {Math.floor(s.t / 60)}분 {s.t % 60}초 지남
                  </span>
                </div>
              )}

              {/* 태어나지 못한 알. 자리를 먹지 않으므로 내려놓으면 곧바로 다시 올릴 수 있다.
                  ★ 사용자 잘못이 아니라는 것이 먼저 읽혀야 한다 — 그림을 탓하면 다시 안 온다. */}
              {derived.failed && (
                <div style={L.notePaper}>
                  <span style={L.h3}>이 그림은 좀 어렵네요</span>
                  <p style={L.body}>
                    이번엔 아이를 깨우지 못했어요. 다른 그림으로 다시 해 보면 잘 되는 경우가 많아요.
                  </p>
                  {/* 알을 내려놓아야 올리는 자리가 다시 그려지므로, 한 박자 뒤에 옮긴다. */}
                  <button
                    onClick={() => { ui.retryHatch(); window.setTimeout(goUpload, 80); }}
                    style={L.tagBtnA}
                  >
                    다시 해보기
                  </button>
                </div>
              )}

              {!s.hasChar && !derived.booting && (
                <div style={L.notePaper}>
                  <span style={L.h3}>아직 비어 있어요</span>
                  <p style={L.body}>위에서 아이를 소개해 주면, 여기로 알이 떨어져 부화가 시작됩니다.</p>
                  <button onClick={goUpload} style={L.tagBtnA}>올리는 자리로</button>
                </div>
              )}

              {/* 게이지 — 시안대로 가로 막대로 아래에 모았다. */}
              {s.phase === 'live' && (
                <div style={L.panel}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }} data-gauges={`${s.fullness}/${s.happiness}/${s.clean}`}>
                    {gaugeRow('배부름', cells(s.fullness, RED, '●'))}
                    {gaugeRow('행복', cells(s.happiness, GRN, '♥'))}
                    {gaugeRow('청결', cells(s.clean, '#6F8FB0', '✦'))}
                    <div style={L.infoRow}>
                      <span>{s.daysTogether}일째 함께</span>
                      <span>친밀도 {s.intimacyPercent}%</span>
                      {s.untilAutoSleep !== null && !s.sleeping && <span>{leftLabel(s.untilAutoSleep)} 뒤 자동 취침</span>}
                    </div>
                  </div>

                  {/* 말 걸기 — 캐릭터가 먼저 부를 때(하루 3회 + 아기 8분)만 열린다(정본 §10). */}
                  {derived.chatOpen && !s.sleeping && (
                    <div style={L.chatBar}>
                      <input
                        value={s.chatDraft}
                        onChange={e => chat.setDraft(e.target.value)}
                        onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void chat.send(); } }}
                        placeholder={`${cur.name}에게 답하기 (40자)`}
                        aria-label={`${cur.name}에게 답하기`}
                        maxLength={40}
                        data-action="chat-input"
                        style={L.chatInput}
                      />
                      <button onClick={() => void chat.send()} data-action="chat-send" style={L.sendBtn} aria-label="보내기">▸</button>
                    </div>
                  )}

                  <div style={L.btnGrid}>
                    {buttons.map(b => (
                      <button
                        key={b.key}
                        onClick={b.act}
                        data-action={b.key}
                        aria-label={ARIA[b.key]}
                        aria-disabled={can(b.key) ? undefined : true}
                        style={{ ...b.style, position: 'relative' }}
                      >
                        {b.hint && <span style={L.badge}>{b.hint}</span>}
                        <span style={b.pinStyle} />
                        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 14 }}>{b.label}</span>
                        <span style={{ fontSize: 10, color: '#A79C82', minHeight: 12, lineHeight: 1.2 }}>{b.sub}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>
        </section>

        {session.server && (
          <GameSection
            petId={session.server.pet?.phase === 'ALIVE' ? session.server.pet.petId : null}
            source={session.server.source}
            onFinished={() => { void session.server?.reload(); }}
          />
        )}

        {/* 섹션 4 — 도감. 사진 코너로 고정된 앨범 시트. */}
        <section data-sec="dex" style={L.sec}>
          <div style={L.wrap}>
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <span style={{ fontFamily: PEN, fontSize: 22, color: RED, lineHeight: 1 }}>모은 것들</span>
                <h2 style={L.h2}>도감</h2>
              </div>
              {/* ★ 후기는 이 한 줄이 전부다 — 띄울지 말지(이미 냈는가·첫 동작을 얻었는가)는
                  FeedbackSheet 이 서버에 직접 물어 정한다. 여기서 판정하면 스킨이 그 규칙을
                  알아야 하고, 규칙이 바뀔 때마다 스킨이 같이 바뀐다.
                  hold 는 해금 축하 판 위에 겹쳐 뜨는 것을 막는다(축하가 닫힌 뒤에 올라온다). */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: '0 0 auto' }}>
                {/* 후기는 실서버 전용(v1 경로) — 목에서는 안 그린다. */}
                <FeedbackSheet petId={!derived.mock ? (session.server?.pet?.petId ?? null) : null} unlocked={s.unlocked} hold={!!derived.celebration} />
                <span style={L.countTag}>{s.unlocked} / {dex.length}</span>
              </div>
            </div>

            <div style={L.albumSheet}>
              {/* 한 칸도 없는 것은 고장이 아니다 — 아직 아무것도 안 열린 상태다.
                  빈 판만 두면 안 그려진 것처럼 보이므로, 어떻게 하면 늘어나는지 말해 준다. */}
              {dex.length === 0 && (
                <p style={{ fontFamily: PEN, fontSize: 20, color: SUB, textAlign: 'center', margin: '26px 0 22px' }}>
                  아이가 태어나면 열여덟 칸이 생겨요
                </p>
              )}
              <div style={L.dexGrid}>
                {dex.map((d, i) => (
                  <div key={d.seq} data-dex={d.key} data-open={d.open ? '1' : '0'} style={d.cardStyle}>
                    <span style={L.cnrTL} /><span style={L.cnrTR} /><span style={L.cnrBL} /><span style={L.cnrBR} />
                    <div style={d.mediaStyle}>
                      <img src={d.img} alt={d.name} style={d.imgStyle} />
                      {d.locked && (
                        <div style={L.dexTracing}>
                          <span style={{ fontFamily: PEN, fontSize: 17, color: '#6E6653' }}>{d.hint}</span>
                        </div>
                      )}
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '9px 10px 11px' }}>
                      <span style={d.nameStyle}>{d.name}{d.advanced ? ' ✦' : ''}</span>
                      {/* 잠긴 칸에는 저장·공유가 없다. 버튼의 유무가 소유를 말한다. */}
                      {d.open && (
                        <div style={{ display: 'flex', gap: 7 }}>
                          <button onClick={d.save} style={L.miniGhost}>저장</button>
                          <button onClick={d.share} style={L.miniSolid}>공유</button>
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

      </div>

      {/* 인덱스 탭 */}
      <nav style={L.nav}>
        {tabs.map((t, i) => (
          <button key={i} onClick={t.go} style={t.style}>
            <span style={t.dot} />
            <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 14 }}>{t.label}</span>
          </button>
        ))}
      </nav>

      {/* 새 캐릭터 추가 — 페이지를 넘기지 않고 아래에서 올라온다. */}
      {s.sheet && (
        <div style={{ position: 'absolute', inset: 0, zIndex: 40 }}>
          <div onClick={ui.closeSheet} style={{ position: 'absolute', inset: 0, background: 'rgba(58,53,43,.42)' }} />
          <div style={L.sheet}>
            <div style={{ width: 44, height: 5, borderRadius: 3, background: '#DCD2B8', margin: '0 auto 16px' }} />
            <h3 style={L.h2}>새로 데려오기</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginTop: 15 }}>
              <label style={{ display: 'block', cursor: 'pointer' }}>
                <div style={L.dropSm}>
                  <span style={{ fontFamily: PEN, fontSize: 21, color: SUB }}>여기에 그림 한 장</span>
                </div>
                <input type="file" accept="image/*" onChange={form.onPickImg} style={{ display: 'none' }} />
              </label>
              {uploadFields(true)}
            </div>
          </div>
        </div>
      )}

      {/* ★ 축하 — 즉시 해금(2층, 조건 충족 순간 폭죽)과 아침 도착(심화 행동 "배워왔어요"). 이 서비스의 두 번째 심장이다.
          뽑기가 아니라 '내가 키워서 받았다'가 되게 결과보다 원인을 먼저 띄운다. 닫으면 다음 축하가 뜬다(useCelebrations). */}
      {derived.celebration && (
        <div data-celebration={derived.celebration.kind} style={{ position: 'fixed', inset: 0, zIndex: 80, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}>
          <div onClick={ui.closeUnlock} style={{ position: 'absolute', inset: 0, background: 'rgba(58,53,43,.5)' }} />
          <div style={{
            position: 'relative', width: '100%', maxWidth: 340,
            background: PAPER, border: '1px solid ' + EDGE, borderRadius: 4,
            padding: '20px 18px 18px', boxShadow: '4px 6px 0 rgba(58,53,43,.22)',
            animation: 'tamaRiseIn .3s ease-out both', textAlign: 'center',
          }}>
            <span style={L.tapeTop} />
            <p style={{ margin: '0 0 12px', fontFamily: PEN, fontSize: 19, color: SUB, lineHeight: 1.5 }}>
              {derived.celebration.kind === 'arrival' ? `어젯밤 ${cur.name}이(가) 연습해서` : `${cur.name}와 함께해서`}
            </p>
            <div style={{ ...L.polaroid, transform: 'rotate(-1.5deg)', display: 'inline-block' }}>
              <div style={{ ...L.photo, width: pc ? 240 : 190, position: 'relative' }}>
                <div style={L.floorLine} />
                <img src={derived.celebration.imageUrl} alt={derived.celebration.label}
                  style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
                {/* 폭죽은 캐릭터 앞에 겹치는 공통 에셋이다. */}
                <img src={ASSET.firework.src} alt="" aria-hidden
                  style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', pointerEvents: 'none' }} />
              </div>
              <div style={L.polaroidCaption}>
                <span style={{ fontFamily: PEN, fontSize: 20, lineHeight: 1 }}>{derived.celebration.label}</span>
              </div>
            </div>
            <p style={{ margin: '12px 0 4px', fontFamily: GAEGU, fontWeight: 700, fontSize: 19, color: INK }}>
              {derived.celebration.kind === 'arrival'
                ? `오늘 ${derived.celebration.label}${josa(derived.celebration.label, '을', '를')} 배워왔어요`
                : `${derived.celebration.label}${josa(derived.celebration.label, '을', '를')} 배웠어요`}
            </p>
            <p style={{ margin: '0 0 15px', fontSize: 13, color: SUB }}>
              도감 {s.unlocked} / {derived.total}
            </p>
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                onClick={() => { dex.find((d) => d.seq === derived.celebration?.seq)?.save(); ui.closeUnlock(); }}
                style={L.tagBtnB}
              >
                저장
              </button>
              <button onClick={ui.closeUnlock} data-action="celebration-close" style={L.tagBtnA}>보러 가기</button>
            </div>
          </div>
        </div>
      )}

      {!!s.toast && <div style={L.toast}>{s.toast}</div>}

      {/* 이 화면의 유일한 로그인 벽. 보고 만지는 것은 전부 열려 있고,
          "내 아이를 만든다" 는 순간에만 계정을 묻는다(useZzalSession.create). */}
      <AuthModal open={session.authOpen} onClose={session.closeAuth} />
    </div>
  );
}
