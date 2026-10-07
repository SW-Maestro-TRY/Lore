// 앨범 벽과 액자 하나.
//
// 벽 = 방 위로 통째로 올라오는 판(18칸). 열린 칸만 또렷하고 잠긴 칸은 흐리다 —
//      "아직 못 배운 모습" 이 보여야 다음에 뭘 할지 알 수 있다.
// 액자 = 벽에서 한 칸을 누르면 크게. 열린 칸은 저장·공유, 잠긴 칸은 조건만 알려 준다.
'use client';

import { useEffect, useState } from 'react';
import { assetUrl } from '../../lib/assets';
import { downloadImage, imageFileName, prepareImageFile, shareImageFile } from '../../lib/download';
import { detectInApp } from '../../lib/inapp';
import { C, C2, GAEGU, TAP_MIN, gap, radius, fz, ink, paperA } from './ui';
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
  return (
    <>
      {v.wall.show && <Wall y={y} />}
      {v.frame.show && <FrameView y={y} />}
    </>
  );
}

function Wall({ y }: { y: Yeoul }) {
  const w = y.v.wall;
  const live = useLive();
  return (
    <div data-part="wall" style={{ position: 'absolute', inset: 0, zIndex: 9, background: C.wallBg, display: 'flex', flexDirection: 'column', animation: w.anim }}>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'baseline', gap: gap.md, padding: '14px 18px 10px' }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, color: C.ink }}>함께한 순간</span>
        <span style={{ fontSize: fz.sm, color: C.faint }}>{w.count}</span>
        <span style={{ flex: 1 }} />
        {/* ★ 누르는 자리 44px(판정 5) — 동그라미는 28 그대로, 단추만 키운다. */}
        <button
          onClick={w.close} aria-label="닫기"
          style={{ width: TAP_MIN, height: TAP_MIN, margin: '-8px -8px -8px 0', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', border: 'none', background: 'none', padding: 0 }}
        >
          <span style={{ width: 28, height: 28, borderRadius: radius.pill, border: `1px solid ${ink(.16)}`, background: paperA(.9), fontSize: fz.sm, color: C.sub, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>✕</span>
        </button>
      </div>

      <div style={{
        flex: '1 1 auto', overflow: 'auto', padding: '6px 18px 18px',
        display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 14px',
        alignContent: 'start', alignItems: 'start',
        backgroundImage: `repeating-linear-gradient(90deg,${ink(.05)} 0 2px,transparent 2px 22px)`,
      }}>
        {w.frames.map((f, i) => (
          <span key={i} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, alignSelf: 'start', margin: '0 0 22px' }}>
            {/* ★ 이 버튼 안에는 그림 한 장뿐이라 **화면 낭독기에는 "버튼" 으로만 들린다**
                (2026-09-21 실측 — 전 화면에서 이름 없는 버튼은 이 하나였다).
                밑에 붙은 이름표는 버튼 **밖**이라 이름이 되어 주지 못한다. 열림/잠김까지 말해 준다 —
                흐린 액자와 또렷한 액자의 차이는 눈으로만 갈리기 때문이다. */}
            <button
              onClick={f.tap}
              aria-label={f.open ? f.name : `${f.name} · 아직 못 배운 모습 · ${f.cond}`}
              style={{ position: 'relative', width: '100%', height: 0, padding: '0 0 133%', boxSizing: 'content-box', border: `5px solid ${f.bd}`, borderRadius: radius.frame, background: f.bg, boxShadow: f.shadow, overflow: 'hidden' }}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={(!y.s.sampleMode && albumImageUrl(live, f.key)) || spriteUrl(live, f.key, y.s.sampleMode)} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity, filter: f.filter }} />
            </button>
            <span style={{ fontSize: fz.xs, lineHeight: 1.35, textAlign: 'center', color: f.labelFg }}>{f.label}</span>
          </span>
        ))}
      </div>

      {/* ★ 손잡이 줄 — 앨범 시트에 있던 것을 여기로 옮겼다(2026-09-21 A-14).
          예전엔 시트로 가는 입구가 팝오버 버튼 하나뿐이라, 그 버튼을 지우면 도감·엽서·저장·벽지가
          통째로 갈 곳이 없어졌다. 앨범은 이제 이 화면 하나다.
          ★★ **개수가 늘 것을 전제로 그린다** — 엽서·여행처럼 앨범에 들어올 것이 더 있다.
          `auto-fit` 이라 칸을 더해도 이 코드는 안 고친다(넉 줄을 딱 맞춰 그리면 다음에 다시 뜯는다). */}
      <div
        data-part="wall-actions"
        style={{
          flex: 'none', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(72px, 1fr))',
          gap: gap.sm, padding: '0 18px 10px',
        }}
      >
        {w.actions.map((a) => (
          <button
            key={a.label} onClick={a.tap} data-action={`album-${a.label}`}
            // ★ 누르는 자리 `TAP_MIN`(2026-09-23 · 바닥값이 40 이라 실측도 40px 이었다). 칸이 보이는 단추라
            //   알약처럼 **바닥값만 올린다** — 아이 정보·온보딩 칩과 같은 규칙(`Panels`·`Onboarding`).
            style={{ minHeight: TAP_MIN, padding: '10px 4px', borderRadius: radius.sm, border: `1px solid ${a.bd}`, background: a.bg, fontSize: fz.sm, color: a.fg }}
          >{a.label}</button>
        ))}
      </div>

      {w.deco && (
        <div data-part="wall-deco" style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: gap.sm, padding: '0 18px 10px' }}>
          <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm, flexWrap: 'wrap' }}>
            <span style={{ fontSize: fz.sm, color: C.faint }}>벽지</span>
            {w.decoNote && <span style={{ fontSize: fz.xs, color: C.faint }}>{w.decoNote}</span>}
          </span>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(64px, 1fr))', gap: gap.sm }}>
            {w.walls.map((x) => (
              <button
                key={x.name} onClick={x.pick} data-action={`wall-${x.name}`}
                style={{ display: 'flex', flexDirection: 'column', gap: gap.xs, alignItems: 'center', padding: '7px 4px', borderRadius: radius.sm, border: `${x.bw} solid ${x.bd}`, background: C.paper }}
              >
                <span style={{ width: '100%', height: 30, borderRadius: radius.xs, background: x.color }} />
                <span style={{ fontSize: fz.xs, color: C.sub2 }}>{x.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      <span style={{ flex: 'none', padding: '0 18px 16px', fontSize: fz.xs, color: C.faint }}>
        액자를 누르면 크게 볼 수 있어요 · 흐린 액자는 아직 못 배운 모습이에요
      </span>
    </div>
  );
}

function FrameView({ y }: { y: Yeoul }) {
  const f = y.v.frame;
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
  const record = (kind: 'SHARE' | 'DOWNLOAD') => {
    if (live.petId && !y.s.sampleMode) void live.shareMotion(f.key, kind);
  };
  const save = () => {
    if (!fileSrc || busy) return;
    setBusy(true);
    void downloadImage(fileSrc, fileName).then((result) => {
      if (result.outcome === 'saved') {
        setNotice('파일 다운로드를 시작했어요. 다운로드한 파일을 확인해 주세요.');
        record('DOWNLOAD');
      } else setNotice('파일을 받지 못했어요. Safari나 Chrome에서 다시 눌러 주세요.');
    }).finally(() => setBusy(false));
  };
  const share = () => {
    if (!file || busy) return;
    // 미리 읽은 File을 클릭 안에서 곧바로 넘겨 iOS의 사용자 활성화를 보존한다.
    setBusy(true);
    void shareImageFile(file).then((result) => {
      if (result === 'shared') { setNotice('공유창에 파일을 전달했어요.'); record('SHARE'); }
      else if (result === 'unsupported') setNotice('이 브라우저에서는 파일 공유를 지원하지 않아요. 저장한 뒤 앱에서 파일을 첨부해 주세요.');
      else if (result === 'failed') setNotice('공유창을 열지 못했어요. 저장한 뒤 앱에서 파일을 첨부해 주세요.');
    }).finally(() => setBusy(false));
  };
  return (
    <div onClick={f.close} data-part="frame" style={{ position: 'absolute', inset: 0, zIndex: 10, background: C.faint, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 22, animation: 'yFadeIn .18s ease' }}>
      <div onClick={(e) => e.stopPropagation()} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.lg, width: '100%', animation: f.anim }}>
        <span style={{ position: 'relative', width: 'min(190px,70%)', height: 0, padding: '0 0 93%', boxSizing: 'content-box', border: `7px solid ${C.frameWood}`, borderRadius: radius.frame, background: C.paper, overflow: 'hidden', boxShadow: '0 12px 28px rgba(46,42,38,.32)' }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={src} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity }} />
        </span>
        <span style={{ fontFamily: GAEGU, fontSize: fz.h2, color: C2.onDark }}>{f.name}</span>
        {notice && <span role="status" data-part="album-file-notice" style={{ fontSize: fz.sm, color: C2.onDark, textAlign: 'center' }}>{notice}</span>}
        {f.locked && <span style={{ padding: '7px 14px', borderRadius: radius.pill, background: paperA(.16), fontSize: fz.sm, color: '#F3E9DC' }}>{f.cond}</span>}
        {f.open && inApp && (
          <span data-part="album-inapp-note" style={{ maxWidth: 300, fontSize: fz.sm, lineHeight: 1.5, color: C2.onDark, textAlign: 'center' }}>
            인앱에서는 저장이 안 될 수 있어요 — 그림을 길게 눌러 저장하거나 외부 브라우저로 열어 주세요(외부 브라우저에선 다시 로그인이 필요해요)
          </span>
        )}
        {f.open && (
          <span style={{ display: 'flex', gap: gap.sm }}>
            {/* ★ 누르는 자리 `TAP_MIN`(2026-09-23 · 실측 39px). */}
            <button onClick={save} disabled={!fileSrc || busy} data-action="frame-save" style={{ minHeight: TAP_MIN, padding: '10px 18px', borderRadius: radius.sm, border: 'none', background: C.paper, fontSize: fz.md, color: C.ink }}>저장</button>
            {/* 미리 읽은 애니메이션 파일을 OS 공유창에 전달한다. */}
            <button onClick={share} disabled={!file || busy} data-action="frame-share" style={{ minHeight: TAP_MIN, padding: '10px 18px', borderRadius: radius.sm, border: 'none', background: C.paper, fontSize: fz.md, color: C.ink }}>{file || notice ? '공유' : '공유 준비 중…'}</button>
          </span>
        )}
      </div>
    </div>
  );
}
