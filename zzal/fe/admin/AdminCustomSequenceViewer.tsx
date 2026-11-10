'use client';

import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@common/api/client';
import { assetUrl } from '@zzal/lib/assets';
import { BTN, STAGE } from './AdminReviewScreen';
import { fetchCustomReviewSequence, type CustomReviewSequence, type CustomSequenceAnchors, type CustomSequenceFrame, type CustomSequencePose } from './customBundleApi';

const BASIC = ['base', 'eat', 'joy', 'sad', 'sick', 'pet', 'hello', 'sleep'];
// Versioned physical slot order; these names are not custom action semantics.
const CUSTOM = ['eat_rice', 'eat_snack', 'sweep', 'wash', 'reply', 'petted', 'startle', 'wake_up'];
const BASIC_LABELS = ['기본', '먹기', '기쁨', '슬픔', '아픔', '쓰다듬', '인사', '잠'];
const control = { ...BTN, minHeight: 44, minWidth: 44 };
type Ready = CustomReviewSequence & { K: number; baseRound: number; baseAnchors: CustomSequenceAnchors; customAnchors: CustomSequenceAnchors };
type Loaded = { sequence: Ready; images: HTMLImageElement[] };
function canvasOf(a: CustomSequenceAnchors) { return Array.isArray(a.canvas) ? a.canvas : [a.canvas?.w, a.canvas?.h]; }
function poseOf(s: Ready, f: CustomSequenceFrame): CustomSequencePose { return (f.group === 'BASIC' ? s.baseAnchors : s.customAnchors).poses[f.slot]; }
function validSequence(s: CustomReviewSequence): s is Ready {
  const positive = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x) && x > 0;
  if (s?.version !== 'CUSTOM_SEQUENCE_V1' || s.status !== 'READY' || !positive(s.K) || !Number.isInteger(s.baseRound) || !positive(s.baseRound) || !s.baseAnchors || !s.customAnchors || !Array.isArray(s.frames) || s.frames.length !== 32) return false;
  for (let i = 0; i < 32; i++) {
    const f = s.frames[i], group = i < 16 ? 'BASIC' : 'CUSTOM', order = Math.floor(i / 2) % 8 + 1;
    if (!f || f.index !== i || f.group !== group || f.motionOrder !== order || f.frameIndex !== i % 2 || f.slot !== (group === 'BASIC' ? BASIC : CUSTOM)[order - 1]) return false;
    if (!Number.isInteger(f.width) || !Number.isInteger(f.height) || f.width < 16 || f.height < 16 || f.width > 4096 || f.height > 4096 || !positive(f.durationMs) || f.durationMs > 60000) return false;
    if (typeof f.imageKey !== 'string' || !/^images\/[A-Za-z0-9/_\-.]+\.png$/.test(f.imageKey) || f.imageKey.includes('..')) return false;
    if (![f.sha256, f.sourceSha256, f.rgbaSHA256].every(h => typeof h === 'string' && /^[a-f0-9]{64}$/.test(h))) return false;
    const bounds = f.alphaBounds;
    if (!Array.isArray(bounds) || bounds.length !== 4 || !bounds.every(Number.isFinite) || bounds[0] < 0 || bounds[1] < 0 || bounds[2] > f.width || bounds[3] > f.height || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) return false;
    const anchors = group === 'BASIC' ? s.baseAnchors : s.customAnchors, [w, h] = canvasOf(anchors), p = anchors.poses?.[f.slot];
    if (anchors.K !== s.K || w !== f.width || h !== f.height || !p) return false;
    const within = (n: unknown, max: number) => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= max;
    if (!within(p.head_top?.x, w) || !within(p.head_top?.y, h) || !within(p.feet?.center_x, w) || !within(p.feet?.y, h)) return false;
  }
  return true;
}
function loadFrame(f: CustomSequenceFrame, signal: AbortSignal): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    const finish = (error?: Error) => {
      clearTimeout(timer); signal.removeEventListener('abort', abort); image.onload = null; image.onerror = null;
      if (error) { image.src = ''; reject(error); } else resolve(image);
    };
    const abort = () => finish(new DOMException('Aborted', 'AbortError'));
    const timer = setTimeout(() => finish(new Error('FRAME_TIMEOUT')), 10000);
    image.onload = () => finish(image.naturalWidth === f.width && image.naturalHeight === f.height ? undefined : new Error('FRAME_SIZE'));
    image.onerror = () => finish(new Error('FRAME_LOAD'));
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) { abort(); return; }
    image.src = assetUrl(f.imageKey);
  });
}
function message(error: unknown) {
  if (error instanceof ApiError && error.status === 403) return '관리자 권한이 필요해요. 기존 미리보기는 계속 볼 수 있어요.';
  if (error instanceof ApiError && error.status === 404) return '이어보기 자료를 찾을 수 없어요. 기존 미리보기로 확인해 주세요.';
  return '이어보기 자료를 불러오지 못했어요. 기존 미리보기는 계속 볼 수 있어요.';
}

export default function AdminCustomSequenceViewer({ jobId, titles, onClose }: { jobId: number; titles: string[]; onClose: () => void }) {
  const [loaded, setLoaded] = useState<Loaded | null>(null), [state, setState] = useState<'loading' | 'ready' | 'unavailable' | 'error'>('loading');
  const [notice, setNotice] = useState(''), [reason, setReason] = useState<string | null>(null), [attempt, setAttempt] = useState(0);
  const [index, setIndex] = useState(0), [playing, setPlaying] = useState(false), [speed, setSpeed] = useState(1), [overlay, setOverlay] = useState(true);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const canvas = useRef<HTMLCanvasElement>(null), epoch = useRef(0);
  useEffect(() => {
    const stamp = ++epoch.current, ac = new AbortController();
    setLoaded(null); setState('loading'); setPlaying(false); setIndex(0); setReason(null);
    void (async () => {
      try {
        const sequence = await fetchCustomReviewSequence(jobId, ac.signal);
        if (ac.signal.aborted || stamp !== epoch.current) return;
        if (sequence.status !== 'READY') { setNotice('보존된 기본·맞춤 프레임 자료가 없어 이어볼 수 없어요. 기존 미리보기로 확인해 주세요.'); setReason(sequence.reasonCode); setState('unavailable'); return; }
        if (!validSequence(sequence)) throw new Error('INVALID_SEQUENCE');
        const images = await Promise.all(sequence.frames.map(f => loadFrame(f, ac.signal)));
        if (ac.signal.aborted || stamp !== epoch.current) return;
        setLoaded({ sequence, images }); setState('ready'); setPlaying(true);
      } catch (e) { if (!ac.signal.aborted && stamp === epoch.current) { setNotice(message(e)); setState('error'); } }
    })();
    return () => { ++epoch.current; ac.abort(); };
  }, [jobId, attempt]);
  useEffect(() => {
    const node = canvas.current; if (!node) return;
    const observer = new ResizeObserver(() => { const r = node.getBoundingClientRect(); setSize(old => old.width === r.width && old.height === r.height ? old : { width: r.width, height: r.height }); });
    observer.observe(node); return () => observer.disconnect();
  }, [state]);
  useEffect(() => {
    if (!playing || !loaded || state !== 'ready') return;
    const timer = setTimeout(() => setIndex(i => (i + 1) % 32), loaded.sequence.frames[index].durationMs / speed);
    return () => clearTimeout(timer);
  }, [playing, loaded, state, index, speed]);
  useEffect(() => { const pause = () => { if (document.hidden) setPlaying(false); }; document.addEventListener('visibilitychange', pause); return () => document.removeEventListener('visibilitychange', pause); }, []);
  useEffect(() => {
    const node = canvas.current, ctx = node?.getContext('2d'); if (!node || !ctx || !loaded || !size.width || !size.height) return;
    const { sequence, images } = loaded, f = sequence.frames[index], a = poseOf(sequence, f), w = size.width, h = size.height;
    const scale = Math.min(w * .70, h * .52) * .95 / sequence.K, baseFoot = h * .77 - 24;
    const unionTop = Math.min(...sequence.frames.map(frame => baseFoot - poseOf(sequence, frame).feet.y * scale + frame.alphaBounds[1] * scale));
    const verticalShift = Math.max(0, 6 - unionTop), foot = baseFoot + verticalShift;
    // One pose anchor and A/B union bounds: A and B never reanchor or fit independently.
    const pair = sequence.frames.slice(Math.floor(index / 2) * 2, Math.floor(index / 2) * 2 + 2);
    const alphaLeft = Math.min(...pair.map(frame => frame.alphaBounds[0])) * scale, alphaRight = Math.max(...pair.map(frame => frame.alphaBounds[2])) * scale;
    const left = Math.max(6 - alphaLeft, Math.min(w - 6 - alphaRight, w * .315 - a.feet.center_x * scale)), top = foot - a.feet.y * scale;
    const dpr = window.devicePixelRatio || 1; node.width = Math.round(w * dpr); node.height = Math.round(h * dpr); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h);
    ctx.drawImage(images[index], left, top, f.width * scale, f.height * scale);
    if (overlay) {
      ctx.lineWidth = 1; ctx.font = '11px system-ui'; ctx.strokeStyle = '#426d47'; ctx.beginPath(); ctx.moveTo(0, foot); ctx.lineTo(w, foot); ctx.stroke(); ctx.fillStyle = '#426d47'; ctx.fillText('FEET', 8, Math.min(h - 5, foot + 13));
      const head = top + a.head_top.y * scale; ctx.strokeStyle = '#a23b28'; ctx.beginPath(); ctx.moveTo(0, head); ctx.lineTo(w, head); ctx.stroke(); ctx.fillStyle = '#a23b28'; ctx.fillText('HEAD', 8, Math.max(12, head - 5));
      const b = f.alphaBounds; ctx.setLineDash([3, 3]); ctx.strokeStyle = '#4b5794'; ctx.strokeRect(left + b[0] * scale, top + b[1] * scale, (b[2] - b[0]) * scale, (b[3] - b[1]) * scale); ctx.setLineDash([]);
    }
    node.dataset.frame = String(index); node.dataset.scale = String(scale); node.dataset.foot = String(foot); node.dataset.left = String(left); node.dataset.top = String(top); node.dataset.verticalShift = String(verticalShift); node.dataset.slot = f.slot;
  }, [loaded, index, overlay, size]);
  const step = (next: number) => { setPlaying(false); setIndex((next + 32) % 32); };
  const f = loaded?.sequence.frames[index], label = f ? `${f.group === 'BASIC' ? '기본' : '맞춤'} · ${f.group === 'BASIC' ? BASIC_LABELS[f.motionOrder - 1] : titles[f.motionOrder - 1] || f.slot} · ${f.frameIndex ? 'B' : 'A'} (${index + 1}/32)` : '';
  return <section data-part="custom-sequence" data-job={jobId} style={{ borderTop: '1px solid rgba(127,127,127,.28)', marginTop: 14, paddingTop: 12 }}>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}><h4 style={{ fontSize: 15, margin: 0 }}>기본→맞춤 이어보기</h4><button type="button" data-action="sequence-close" onClick={onClose} style={control}>닫기</button></div>
    {state === 'loading' && <p role="status">실제 프레임을 불러오는 중이에요.</p>}
    {(state === 'error' || state === 'unavailable') && <><p role={state === 'error' ? 'alert' : 'status'} style={{ lineHeight: 1.6 }}>{notice}</p>{reason && <p style={{ overflowWrap: 'anywhere', fontSize: 12 }}>{reason}</p>}{state === 'error' && <button type="button" data-action="sequence-retry" onClick={() => setAttempt(x => x + 1)} style={control}>다시 불러오기</button>}</>}
    {state === 'ready' && <>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', marginTop: 8 }}>
        <label style={{ minHeight: 44, display: 'flex', alignItems: 'center', gap: 5 }}>속도 <select data-part="sequence-speed" value={speed} onChange={e => setSpeed(Number(e.target.value))} style={control}><option value={.5}>0.5×</option><option value={1}>1×</option><option value={2}>2×</option></select></label>
        <label style={{ minHeight: 44, display: 'flex', alignItems: 'center', gap: 5 }}><input type="checkbox" checked={overlay} onChange={e => setOverlay(e.target.checked)} style={{ width: 20, height: 20, accentColor: '#426d47' }} />앵커선</label>
      </div>
      <p data-part="sequence-current" style={{ height: 48, margin: '5px 0', alignContent: 'center', overflowWrap: 'anywhere', fontSize: 13 }}>{label}</p>
      <div style={{ ...STAGE, marginTop: 0 }}><canvas ref={canvas} data-part="sequence-canvas" aria-label="실제 기본8과 맞춤8의 A·B 프레임 이어보기" style={{ display: 'block', width: '100%', height: 'clamp(300px,58svh,480px)' }} /></div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: 7, maxWidth: 420, margin: '0 auto' }}>
        <button type="button" data-action="sequence-play" onClick={() => setPlaying(p => !p)} style={control}>{playing ? '일시정지' : '재생'}</button>
        <button type="button" data-action="sequence-step" onClick={() => step(index + 1)} style={control}>한 프레임</button>
        <button type="button" data-action="sequence-prev" onClick={() => step((Math.floor(index / 2) - 1) * 2)} style={control}>이전 동작</button>
        <button type="button" data-action="sequence-next" onClick={() => step((Math.floor(index / 2) + 1) * 2)} style={control}>다음 동작</button>
      </div>
      <p style={{ fontSize: 12, lineHeight: 1.6 }}>기본 {loaded?.sequence.baseRound}판 8동작 뒤 맞춤8동작, 총32프레임을 원본 시간으로 반복해요. A/B 앵커는 고정하고 검수 여백으로 전체 발선만 함께 조정해요. HEAD/FEET·alpha 범위는 진단 기준이며 실제 해부학 측정은 아니에요. 원본 잔여 픽셀도 보존해요.</p>
    </>}
  </section>;
}
