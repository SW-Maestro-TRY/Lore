// 무대 — 배경 위에 캐릭터가 서고, 그 앞에 쓰레기·커튼이 얹히고, 말풍선이 뜬다.
//
// 데이터는 전부 useTamagotchi 에서 온다. 이 파일은 그리기만 한다.
// 앵커(발·손·머리)에 붙는 공통 에셋은 derived.idle.overlays 로 온다 — 실물이 없는 것(placeholder)은 CSS 글자로 대신한다.
'use client';

import type { CSSProperties } from 'react';
import { ANCHORS_DEFAULT, ASSET, bgUrl, type AssetDef } from '../constants';
import type { Tamagotchi } from '../useTamagotchi';
import { PEN, RED } from './theme';

export interface RoomStageProps {
  tama: Tamagotchi;
  /** 배경 키(BACKGROUNDS). */
  bg: string;
  /** 캐릭터 이름(alt 용). */
  name: string;
}

/** 실물이 아직 없는 에셋의 임시 대체(플랜 "에셋 임시 대체 v0"). E4 가 오면 이 표는 안 쓰인다. */
const PLACEHOLDER_TEXT: Record<string, string> = { growl: '꼬르륵', skull: '☠', fly: '~ ~', heart: '♥', sweat: '💧', sparkle: '✦' };

export default function RoomStage({ tama, bg, name }: RoomStageProps) {
  const { state: s, derived, can, actions } = tama;

  const L = {
    room: {
      position: 'relative', width: '100%', aspectRatio: '1 / 1', borderRadius: 20, overflow: 'hidden',
      background: '#F2E5D7', boxShadow: 'inset 0 1px 0 rgba(255,255,255,.6), 0 8px 20px rgba(120,100,80,.14)',
    } as CSSProperties,
    roomBg: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', display: 'block' } as CSSProperties,
    roomEmpty: { position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, background: 'rgba(247,243,235,.72)' } as CSSProperties,
    eggGhost: { width: 96, height: 126, borderRadius: '50% 50% 48% 48% / 62% 62% 38% 38%', border: '1.5px dashed #C9BD9C' } as CSSProperties,
    layer: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block' } as CSSProperties,
    /** 캐릭터는 무대보다 작아야 '방 안에 서 있다' 가 된다. 발이 바닥선(아래 13%)에 닿는다. */
    charBox: {
      position: 'absolute', left: '50%', bottom: '13%', width: '58%', aspectRatio: '313 / 350',
      marginLeft: '-29%', transformOrigin: '50% 100%', transition: 'transform .12s linear',
    } as CSSProperties,
    shadow: {
      position: 'absolute', left: '50%', bottom: '11.5%', width: '26%', height: '3%', marginLeft: '-13%',
      borderRadius: '50%', background: 'rgba(140,110,85,.26)', filter: 'blur(3px)', pointerEvents: 'none',
    } as CSSProperties,
    charImg: { width: '100%', height: '100%', objectFit: 'contain', display: 'block' } as CSSProperties,
    bubble: {
      position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)',
      padding: '8px 14px', borderRadius: '16px 16px 16px 5px', background: '#FFFDF9',
      color: '#5C5445', fontFamily: PEN, fontSize: 18, whiteSpace: 'nowrap',
      boxShadow: '0 6px 14px rgba(120,100,80,.16)', pointerEvents: 'none',
    } as CSSProperties,
    myBubble: {
      position: 'absolute', right: 10, bottom: 10, maxWidth: '72%',
      padding: '7px 13px', borderRadius: '16px 16px 5px 16px', background: '#4A4438',
      color: '#FFFDF6', fontFamily: "'Gaegu',cursive", fontSize: 14, lineHeight: 1.45,
      textAlign: 'right', boxShadow: '0 6px 14px rgba(74,68,56,.24)', pointerEvents: 'none',
      animation: 'tamaPopIn .24s cubic-bezier(.34,1.56,.64,1) both',
    } as CSSProperties,
    typing: {
      position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)',
      display: 'flex', gap: 4, padding: '11px 14px', borderRadius: 16, background: '#FFFDF9',
      boxShadow: '0 6px 14px rgba(120,100,80,.16)', pointerEvents: 'none',
    } as CSSProperties,
    dot: { width: 6, height: 6, borderRadius: '50%', background: '#C6BCA2' } as CSSProperties,
  };

  const fxStyle = (o: { x: number }): CSSProperties => ({
    position: 'absolute', left: o.x + '%', bottom: '30%', fontFamily: PEN, fontSize: 24, color: RED,
    animation: 'tamaFloatUp 1.5s ease-out both', pointerEvents: 'none',
  });

  /** 앵커에 붙는 공통 에셋 한 장. 캐릭터 상자(charBox) 안 좌표라 캐릭터와 같이 움직인다. */
  const overlay = (key: string, i: number) => {
    const def = (ASSET as unknown as Record<string, AssetDef | undefined>)[key];
    if (!def) return null;
    if (def.at === 'full') {
      return def.placeholder
        ? null
        : <img key={key} src={def.src} alt="" aria-hidden style={{ ...L.charImg, position: 'absolute', inset: 0, pointerEvents: 'none' }} />;
    }
    const a = ANCHORS_DEFAULT[def.at];
    const style: CSSProperties = {
      position: 'absolute', left: `${((a.x + (def.dx ?? 0)) * 100).toFixed(1)}%`, top: `${((a.y + (def.dy ?? 0)) * 100).toFixed(1)}%`,
      width: `${(def.size * 100).toFixed(1)}%`, transform: 'translate(-50%, -50%)', pointerEvents: 'none',
    };
    return def.placeholder
      ? <span key={key} data-overlay={key} style={{ ...style, fontFamily: PEN, fontSize: 18, color: '#5C5445', textAlign: 'center', animation: `tamaSoftPulse 1.2s ease-in-out ${i * 0.2}s infinite` }}>{PLACEHOLDER_TEXT[key] ?? '·'}</span>
      : <img key={key} data-overlay={key} src={def.src} alt="" aria-hidden style={style} />;
  };

  const trashImg = s.trash > 0 ? ASSET.trash[Math.min(s.trash, ASSET.trash.length) - 1].src : null;
  const petIfPossible = () => { if (can('pet')) actions.pet(); };

  return (
    <div style={L.room} data-part="room" data-light={derived.light}>
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
        <div style={{ position: 'absolute', inset: 0, animation: s.dropping ? 'tamaEggDrop 1.5s cubic-bezier(.3,.9,.3,1) both' : undefined }}>
          <img src={s.eggT > 0.6 ? ASSET.eggCrack.src : ASSET.eggIdle.src} alt="알" style={L.layer} />
        </div>
      )}

      {s.phase === 'hatching' && <img src={ASSET.eggHatch.src} alt="부화" style={L.layer} />}

      {s.phase === 'live' && (
        <>
          <div style={L.shadow} />
          <div
            onClick={petIfPossible}
            data-stage="char"
            data-motion={derived.motionKey}
            style={{ ...L.charBox, transform: `scale(${derived.idle.scale})`, cursor: can('pet') ? 'pointer' : 'default' }}
            role="button"
            aria-label="쓰다듬기"
          >
            <img src={derived.motionImg} alt={`${name} · ${derived.motionKey}`} style={L.charImg} />
            {derived.idle.overlays.map(overlay)}
          </div>
        </>
      )}

      {/* 쓰레기는 바닥에 쌓여 캐릭터 앞을 가린다 — 캐릭터와 같은 자리·같은 크기로 겹친다. */}
      {trashImg && (
        <img src={trashImg} alt={`쓰레기 ${s.trash}단계`} style={{ ...L.charBox, transform: 'none', pointerEvents: 'none', objectFit: 'contain' }} />
      )}

      {s.sleeping && (
        <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', animation: 'tamaVeilIn .6s ease-out both' }}>
          <img src={ASSET.curtainClosed.src} alt="자는 중" style={L.layer} />
          <img src={ASSET.moon.src} alt="" style={L.layer} />
        </div>
      )}

      {s.fx.map((f) => <span key={f.id} style={fxStyle(f)}>{f.text}</span>)}

      {/* 말풍선 — 아이가 방금 한 일을 스스로 말하거나, 말 건 것에 답한다. 말 건 답이 있으면 그쪽이 이긴다. */}
      {s.phase === 'live' && !s.sleeping && s.chatTyping && (
        <div style={L.typing}>
          <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out infinite' }} />
          <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out .15s infinite' }} />
          <span style={{ ...L.dot, animation: 'tamaDot 1s ease-in-out .3s infinite' }} />
        </div>
      )}
      {s.phase === 'live' && !s.sleeping && !s.chatTyping && (s.chatReply || s.standLine) && (
        <div data-bubble style={{ ...L.bubble, whiteSpace: s.chatReply ? 'normal' : 'nowrap', maxWidth: s.chatReply ? '78%' : undefined }}>
          {s.chatReply || s.standLine}
        </div>
      )}
      {!!s.chatUser && <div style={L.myBubble}>{s.chatUser}</div>}
    </div>
  );
}
