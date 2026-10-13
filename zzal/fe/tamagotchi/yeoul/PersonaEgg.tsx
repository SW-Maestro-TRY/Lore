// 알 온보딩(2026-10-13) — 이름을 넣는 순간 알이 깨어나고, 나머지 칸은 굽는 동안 알 화면에서 하나씩.
//
// 상훈님 원문(10/13): "글이나 칩을 눌러서 제출하면 알에 그런 내용들이 쇽 하고 들어가는 느낌이 좋을 것 같아."
// 결정(10/13): 알을 화면 위 40% 에 두고 아래에서 한 칸씩 · 핵심은 "사용자가 부화에 개입하고 있다는 느낌" ·
//   입력 중에 다 구워지면 끊지 않고 알만 크게 몸부림 + "다 됐어요 · 남은 칸은 나중에 해도 돼요" ·
//   두 시안(dots = 칸마다 색 점이 껍데기에 쌓임 / glow = 빛만)을 `?egg=` 로 바꿔 본다.
//
// ★ 칸·칩·순서는 여기 적지 않는다 — `personaSteps.ts` 한 파일의 데이터다.
// ★ 서버는 안 건드린다. 서버 저장은 **마지막에 한 번**(`live.setChar`)이고, 그 전까지는 브라우저 저장이다.
//   이름 직후에는 진행만 묻는다(`live.watchEarly`) — 굽기는 서버가 그림을 받은 순간부터 이미 돌고 있다.
// ★ 연출은 `transform`·`opacity` 만 쓴다(인앱 브라우저 저사양 폰). 날아가는 사본은 body 위 고정 한 겹이고,
//   끝나면 지운다(검사: `[data-part="pe-fly"]` 개수가 0 으로 돌아오는가).
// ★ 건너뛰면 알이 한 번 갸웃할 뿐이다. 서운해하거나 원망하는 연출·대사는 없다(자캐 커뮤니티 규범).
// ★ 단추(10/13 상훈님 원문): "이전, 다음, 건너뛰기 이런 형태였으면 좋겠어. 그리고 이전으로 가면 그려졌던 점
//   하나는 이전으로 돌아가야지" — 「다음」= 넣기(빈 칸이면 흐림), 「건너뛰기」= 갸웃·점 없음,
//   「이전」= 앞 칸으로 가며 그 칸의 점이 알에서 칸으로 돌아온다(0.3초). 글·칩은 칸에 그대로 남아 다시 고칠 수 있다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { EGG_IMG } from './constants';
import { C, GAEGU, MONO, TAP_MIN, gap, monoSize, radius, fz, ink, acc } from './ui';
import { useLive } from './useHatch';
import { EGG_CRACK_MS, type Yeoul } from './useYeoul';
import { once, ztrack } from './funnel';
import {
  NAME_STEP, PERSONA_STEPS, answerOf, clearSaved, extraRoom, hasAnswer, loadSaved, pickChips, pieceOf,
  toCharacterInput, writeSaved, type Answers, type FieldAnswer, type PersonaField, type PersonaStep,
} from './personaSteps';

/** 날아감 0.42초 · 알 반응 0.25초(날아감 끝 0.1초와 겹침) · 칩 여러 개면 60ms 간격, 최대 3개. */
const FLY_MS = 420;
const REACT_MS = 250;
const OVERLAP_MS = 100;
const STAGGER_MS = 60;
const MAX_PIECES = 3;
/** 건너뛰면 갸웃 한 번. */
const TILT_MS = 300;
/** 「이전」— 점이 알에서 칸으로 돌아오는 시간. */
const BACK_MS = 300;
/** 동작 줄이기 설정이면 날아감 없이 빛만. */
const REDUCED_GLOW_MS = 150;
/** 깨지는 순간(껍질 그림이 바뀌는 때). Egg.tsx 와 같은 값. */
const EGG_BREAK_MS = 950;

/** 점이 앉는 자리(알 상자 안 %). 껍데기 몸통 쪽으로만 — 알 그림 밖으로 안 나가게. 이름이 0번. */
const DOT_AT: readonly [number, number][] = [
  [50, 60], [38, 51], [62, 49], [43, 71], [58, 72], [33, 63], [67, 62], [50, 44], [50, 82], [40, 40], [60, 39],
];

/** 아래 줄 단추 — 셋 다 같은 높이(52)로 한 줄에. 글자는 접히지 않는다(320 폭에서 잰다). */
const NAV_SIDE = {
  flex: 'none', minHeight: 52, minWidth: TAP_MIN, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4,
  borderRadius: radius.pill, fontSize: fz.lg, whiteSpace: 'nowrap',
} as const;
const NAV_MAIN = {
  flex: '1 1 auto', minWidth: 0, minHeight: 52, borderRadius: radius.pill, border: 'none',
  fontSize: fz.xl, fontFamily: GAEGU, fontWeight: 700, whiteSpace: 'nowrap',
} as const;

const STYLE = `
.pe-root{ --pe-stage: 40%; }
.pe-stage{ flex:0 0 var(--pe-stage); min-height:0; }
.pe-root[data-kb="1"]{ --pe-stage: 92px; }
.pe-root[data-kb="1"] .pe-sub, .pe-root[data-kb="1"] .pe-status{ display:none; }
.pe-chip{ position:relative; min-width:44px; transition:opacity .15s; }
.pe-chip[data-flying="1"]{ opacity:.35; }
.pe-scroll{ -webkit-overflow-scrolling:touch; overscroll-behavior:contain; }
.pe-text::placeholder{ font-size:14px; line-height:1.4; color:rgba(74,64,56,.42); }
@media (prefers-reduced-motion: reduce){
  .pe-eggwrap{ animation:none!important; }
}
`;

/** 칩을 `rows` 대로 줄을 나눈다. 수가 모자라면 남은 칩은 마지막 줄에 붙는다. */
function rowsOf(f: PersonaField): { name: string; chips: readonly string[] }[] {
  if (!f.chips.length) return [];
  if (!f.rows || !f.rows.length) return [{ name: '', chips: f.chips }];
  const out: { name: string; chips: readonly string[] }[] = [];
  let at = 0;
  f.rows.forEach((n, i) => {
    const end = i === f.rows!.length - 1 ? f.chips.length : at + n;
    if (at < end) out.push({ name: f.rowNames?.[i] ?? '', chips: f.chips.slice(at, end) });
    at = end;
  });
  return out;
}

/**
 * 글 칸 높이를 **글(없으면 자리표시)이 다 보이게** 맞춘다. textarea 는 자리표시 길이로 저절로 안 늘어나서
 * 320 폭에서 세세한 예 3개가 셋째 줄부터 잘렸다. 같은 폭·글꼴의 보이지 않는 상자에 적어 보고 높이를 잰다.
 */
function fitTextarea(el: HTMLTextAreaElement | null) {
  if (!el || !el.isConnected) return;
  const cs = getComputedStyle(el);
  const m = document.createElement('div');
  m.textContent = el.value || el.placeholder || ' ';
  const padX = parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight);
  const padY = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
  const bd = parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
  m.style.cssText = `position:absolute;visibility:hidden;left:-9999px;top:0;white-space:pre-wrap;overflow-wrap:break-word;width:${el.clientWidth - padX}px;font:${cs.font};letter-spacing:${cs.letterSpacing};line-height:${cs.lineHeight};`;
  if (!el.value) m.style.fontSize = '14px';
  document.body.appendChild(m);
  const h = Math.ceil(m.scrollHeight + padY + bd);
  m.remove();
  el.style.height = `${Math.max(TAP_MIN, h)}px`;
}

const reducedMotion = () => {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
};

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** 위로 휘는 곡선 위의 한 점(2차 베지에). 조절점은 출발·도착의 위쪽. */
function curve(dx: number, dy: number, t: number): [number, number] {
  const cx = dx * 0.35;
  const cy = Math.min(0, dy) - 70;
  const u = 1 - t;
  return [2 * u * t * cx + t * t * dx, 2 * u * t * cy + t * t * dy];
}

/**
 * 칩·말 조각 사본을 알까지 날린다. 끝나면 사본을 지운다.
 * @returns 마지막 사본이 알에 닿기 0.1초 전(= 알 반응을 시작할 때)에 풀리는 약속
 */
function flyPieces(pieces: { rect: DOMRect; label: string }[], to: DOMRect): Promise<void> {
  const layer = document.createElement('div');
  layer.setAttribute('data-part', 'pe-fly');
  layer.setAttribute('aria-hidden', 'true');
  layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:60;overflow:hidden;';
  document.body.appendChild(layer);
  const tx = to.left + to.width / 2;
  const ty = to.top + to.height * 0.55;
  pieces.forEach((p, i) => {
    const el = document.createElement('span');
    el.textContent = p.label;
    el.style.cssText = [
      'position:absolute', `left:${p.rect.left}px`, `top:${p.rect.top}px`,
      `height:${Math.min(p.rect.height, 44)}px`, 'display:flex', 'align-items:center', 'padding:0 14px',
      `border-radius:999px`, `border:2px solid ${C.accent}`, `background:${C.accentSoft}`, `color:${C.accent}`,
      `font-size:${fz.md}px`, 'white-space:nowrap', 'will-change:transform,opacity', 'transform-origin:50% 50%',
      `box-shadow:0 4px 12px ${ink(.14)}`,
    ].join(';');
    layer.appendChild(el);
    const w = el.getBoundingClientRect().width;
    const h = el.getBoundingClientRect().height;
    const dx = tx - (p.rect.left + w / 2);
    const dy = ty - (p.rect.top + h / 2);
    const frames: Keyframe[] = [];
    const N = 10;
    for (let k = 0; k <= N; k += 1) {
      const t = k / N;
      const [x, y] = curve(dx, dy, t);
      const sc = 1 - 0.65 * t;
      // 끝 0.1초(= 0.42 의 약 24%)에 투명해진다.
      const op = t < 0.76 ? 1 : Math.max(0, 1 - (t - 0.76) / 0.24);
      frames.push({ offset: t, transform: `translate(${x}px,${y}px) scale(${sc})`, opacity: op });
    }
    el.animate(frames, { duration: FLY_MS, delay: i * STAGGER_MS, easing: 'cubic-bezier(.5,0,.75,0)', fill: 'both' });
  });
  const lastStart = (pieces.length - 1) * STAGGER_MS;
  setTimeout(() => layer.remove(), lastStart + FLY_MS + 40);
  return wait(Math.max(0, lastStart + FLY_MS - OVERLAP_MS));
}

/**
 * 「이전」의 역연출 — 알 위의 점 하나가 칸(질문 제목 자리)으로 돌아오며 흐려진다. 끝나면 사본을 지운다.
 * 넣기와 같은 겹(`pe-fly`)을 쓰므로 검사 「끝나면 사본 0」이 그대로 적용된다.
 */
function flyBack(from: DOMRect, to: DOMRect, color: string): Promise<void> {
  const layer = document.createElement('div');
  layer.setAttribute('data-part', 'pe-fly');
  layer.setAttribute('aria-hidden', 'true');
  layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:60;overflow:hidden;';
  document.body.appendChild(layer);
  const el = document.createElement('span');
  el.style.cssText = [
    'position:absolute', `left:${from.left}px`, `top:${from.top}px`, `width:${from.width}px`, `height:${from.height}px`,
    'border-radius:50%', `background:${color}`, 'border:2px solid #FFFDF8', 'box-sizing:border-box',
    `box-shadow:0 0 0 1px ${ink(.22)}, 0 2px 6px ${ink(.25)}`, 'will-change:transform,opacity',
  ].join(';');
  layer.appendChild(el);
  const dx = to.left + 28 - (from.left + from.width / 2);
  const dy = to.top + 26 - (from.top + from.height / 2);
  // 아래로 떨어지는 길 — 넣을 때(위로 휘어 들어감)의 거울.
  el.animate([
    { transform: 'translate(0,0) scale(1)', opacity: 1 },
    { transform: `translate(${dx * 0.45}px,${dy * 0.25 - 18}px) scale(1.25)`, opacity: 1, offset: 0.35 },
    { transform: `translate(${dx}px,${dy}px) scale(1.6)`, opacity: 0 },
  ], { duration: BACK_MS, easing: 'cubic-bezier(.3,.7,.4,1)', fill: 'both' });
  setTimeout(() => layer.remove(), BACK_MS + 40);
  return wait(BACK_MS);
}

/** `upto` 앞 칸들의 답만 남긴다 — 「이전」으로 되돌린 칸(점이 빠진 칸)의 글·칩은 화면에만 있고 알에는 안 들었다. */
function settledOf(ans: Answers, upto: number): Answers {
  const out: Answers = {};
  PERSONA_STEPS.slice(0, Math.max(0, upto)).forEach((st) => st.fields.forEach((f) => {
    if (ans[f.key]) out[f.key] = ans[f.key];
  }));
  return out;
}

/** 알 껍데기 그림이 없을 때(로컬·CDN 장애) 그리는 알. 점·빛이 앉을 자리를 같은 모양으로 둔다. */
function CssEgg() {
  return (
    <span
      aria-hidden
      style={{
        position: 'absolute', left: '22%', right: '22%', top: '12%', bottom: '6%',
        borderRadius: '50% 50% 48% 48% / 60% 60% 42% 42%',
        background: `radial-gradient(70% 60% at 38% 32%, #FFFDF8 0%, #F6E9D6 55%, #E9D3B7 100%)`,
        boxShadow: `inset -6px -10px 18px ${ink(.08)}, 0 10px 18px ${ink(.12)}`,
        border: `1px solid ${ink(.08)}`,
      }}
    />
  );
}

export default function PersonaEgg({ y, variant }: { y: Yeoul; variant: 'dots' | 'glow' }) {
  const { s, v, actions } = y;
  const live = useLive();
  const N = PERSONA_STEPS.length;

  /** -1 = 이름, 0..N-1 = 질문, N = 다 넣음(기다림). */
  const [idx, setIdx] = useState(-1);
  const [named, setNamed] = useState(false);
  const [answers, setAnswers] = useState<Answers>({});
  const [skipped, setSkipped] = useState<string[]>([]);
  /** 알 위에 실제로 **앉은** 점(반응이 끝난 칸). 넣는 순간이 아니라 닿는 순간에 붙는다. */
  const [placed, setPlaced] = useState<string[]>([]);
  const [busyFx, setBusyFx] = useState(false);
  const [nameErr, setNameErr] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendErr, setSendErr] = useState<string | null>(null);
  const [meeting, setMeeting] = useState(false);
  const [imgFailed, setImgFailed] = useState(false);
  const [kb, setKb] = useState(false);
  const [lastColor, setLastColor] = useState<string>(NAME_STEP.color);

  const eggBox = useRef<HTMLDivElement>(null);
  const eggReact = useRef<HTMLDivElement>(null);
  const halo = useRef<HTMLSpanElement>(null);
  const chipRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const textRefs = useRef<Record<string, HTMLTextAreaElement | null>>({});
  const nameRef = useRef<HTMLInputElement>(null);
  const sheetRef = useRef<HTMLDivElement>(null);

  const petId = live.petId;
  const submitted = !live.draftOnly && !!petId;
  const baked = live.baked;
  const step: PersonaStep | null = idx >= 0 && idx < N ? PERSONA_STEPS[idx] : null;

  // ── 브라우저 저장에서 되살리기(인앱 브라우저에서 탭이 죽었다 온 경우) ──
  const restoredFor = useRef<number | null>(null);
  useEffect(() => {
    if (!petId || restoredFor.current === petId) return;
    restoredFor.current = petId;
    const saved = loadSaved(petId);
    if (!saved) return;
    setAnswers(saved.answers);
    setSkipped(saved.skipped ?? []);
    if (saved.name && !s.petName) actions.onName(saved.name);
    if (saved.named) {
      setNamed(true);
      setIdx(Math.min(N, Math.max(0, saved.idx)));
      setPlaced(saved.placed ?? [NAME_STEP.key, ...PERSONA_STEPS.filter((st) => st.fields.some((f) => hasAnswer(saved.answers[f.key]))).map((st) => st.key)]);
      live.watchEarly();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 아이 번호가 정해질 때 한 번만 되살린다
  }, [petId]);

  // 넣을 때마다 브라우저에 적는다. 서버에 이미 보냈으면 지운다(아래 submit).
  useEffect(() => {
    if (!petId || submitted) return;
    if (!named && !s.petName && Object.keys(answers).length === 0) return;
    writeSaved({ v: 1, petId, name: s.petName, named, idx, answers, skipped, placed, at: Date.now() });
  }, [petId, submitted, named, idx, answers, skipped, placed, s.petName]);
  // 서버가 받았으면 브라우저 사본은 지운다(같은 틱의 마지막 쓰기보다 **뒤에** 돌도록 효과로 둔다).
  useEffect(() => { if (submitted) clearSaved(petId); }, [submitted, petId]);

  // ── 키보드(iOS 사파리·인앱은 레이아웃 높이가 안 줄고 visualViewport 만 준다) ──
  useEffect(() => {
    const vv = typeof window !== 'undefined' ? window.visualViewport : null;
    if (!vv) return;
    const on = () => setKb(vv.height < window.innerHeight * 0.78);
    on();
    vv.addEventListener('resize', on);
    return () => vv.removeEventListener('resize', on);
  }, []);

  // 글 칸 높이 맞추기 — 칸이 바뀔 때·글이 바뀔 때·폭이 바뀔 때.
  useEffect(() => {
    const fit = () => Object.values(textRefs.current).forEach(fitTextarea);
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, [idx, answers]);

  // 칸이 바뀌면 질문 제목부터 보이게 — 앞 칸에서 내려 둔 스크롤이 남으면 320 폭에서 제목이 가려진다.
  useEffect(() => { if (sheetRef.current) sheetRef.current.scrollTop = 0; }, [idx]);

  // ── 계측: 질문이 보였다 ──
  useEffect(() => {
    if (!named || !step || !petId) return;
    once(`pq_shown:${petId}:${step.key}`, () => ztrack('zzal_persona_q_shown', { step: idx + 2, type: step.key }));
  }, [named, step, idx, petId]);

  // ── 알 반응 ──
  const react = useCallback(async (color: string, key: string) => {
    const reduced = reducedMotion();
    setLastColor(color);
    const base = variant === 'glow' ? 0.5 : 0.18;
    if (reduced) {
      halo.current?.animate([{ opacity: base }, { opacity: 0.85 }, { opacity: base }], { duration: REDUCED_GLOW_MS });
      setPlaced((p) => (p.includes(key) ? p : [...p, key]));
      await wait(REDUCED_GLOW_MS);
      return;
    }
    eggReact.current?.animate([
      { transform: 'scale(1,1)' }, { transform: 'scale(1.05,.92)', offset: 0.35 },
      { transform: 'scale(.98,1.05)', offset: 0.7 }, { transform: 'scale(1,1)' },
    ], { duration: REACT_MS, easing: 'ease-out' });
    halo.current?.animate([
      { opacity: base, transform: 'scale(1)' }, { opacity: 0.95, transform: 'scale(1.16)', offset: 0.4 }, { opacity: base, transform: 'scale(1)' },
    ], { duration: REACT_MS, easing: 'ease-out' });
    setPlaced((p) => (p.includes(key) ? p : [...p, key]));
    await wait(REACT_MS);
  }, [variant]);

  const tilt = useCallback(async () => {
    if (reducedMotion()) return;
    eggReact.current?.animate([
      { transform: 'rotate(0)' }, { transform: 'rotate(-4deg)', offset: 0.45 }, { transform: 'rotate(0)' },
    ], { duration: TILT_MS, easing: 'ease-in-out' });
    await wait(TILT_MS);
  }, []);

  /** 넣는 연출 한 번. 고른 칩(최대 3, 나머지는 +N)과 글 조각을 알로 날리고 알이 받는다. */
  const putIn = useCallback(async (pieces: { rect: DOMRect; label: string }[], color: string, key: string) => {
    const to = eggBox.current?.getBoundingClientRect();
    if (!reducedMotion() && to && pieces.length) await flyPieces(pieces, to);
    await react(color, key);
  }, [react]);

  // ── 서버 저장(마지막 한 번) ──
  const submit = useCallback(async (ans: Answers): Promise<boolean> => {
    if (submitted) return true;
    if (!s.petName.trim()) return false;
    setSending(true);
    setSendErr(null);
    const filled = 1 + PERSONA_STEPS.filter((st) => st.fields.some((f) => hasAnswer(ans[f.key]))).length;
    once('persona_filled', () => ztrack('zzal_persona_filled', { step: filled }));
    const ok = await live.setChar(toCharacterInput(s.petName, ans));
    setSending(false);
    if (ok) clearSaved(petId);
    else setSendErr('못 담았어요. 다시 눌러 주세요.');
    return ok;
  }, [submitted, s.petName, live, petId]);

  const finish = useCallback((ans: Answers) => {
    setIdx(N);
    void submit(ans);
  }, [N, submit]);

  // ── 이름 넣기 ──
  const putName = useCallback(async () => {
    if (busyFx) return;
    const name = s.petName.trim();
    if (!name) { setNameErr(true); nameRef.current?.focus(); return; }
    setBusyFx(true);
    nameRef.current?.blur();
    const rect = nameRef.current?.getBoundingClientRect();
    live.watchEarly();
    await putIn(rect ? [{ rect, label: pieceOf(name) }] : [], NAME_STEP.color, NAME_STEP.key);
    setNamed(true);
    setIdx((i) => (i < 0 ? 0 : i));
    setBusyFx(false);
  }, [busyFx, s.petName, live, putIn]);

  // ── 칸 넣기 ──
  const putStep = useCallback(async () => {
    if (!step || busyFx) return;
    const got = step.fields.map((f) => answerOf(answers, f.key));
    if (!got.some(hasAnswer)) return;
    setBusyFx(true);
    (document.activeElement as HTMLElement | null)?.blur?.();
    const pieces: { rect: DOMRect; label: string }[] = [];
    const extra: string[] = [];
    step.fields.forEach((f, fi) => {
      const x = got[fi];
      for (const c of x.chips) {
        const el = chipRefs.current[`${f.key}:${c}`];
        if (el && pieces.length < MAX_PIECES) pieces.push({ rect: el.getBoundingClientRect(), label: c });
        else extra.push(c);
      }
      const t = x.text.trim();
      if (t) {
        const el = textRefs.current[f.key];
        if (el && pieces.length < MAX_PIECES) pieces.push({ rect: el.getBoundingClientRect(), label: pieceOf(t) });
        else extra.push(t);
      }
    });
    // 넘친 것은 세 번째 조각에 "+N" 으로 합친다.
    if (extra.length && pieces.length) {
      const last = pieces[pieces.length - 1];
      pieces[pieces.length - 1] = { ...last, label: `${last.label} +${extra.length}` };
    }
    const chips = got.reduce((n, x) => n + x.chips.length, 0);
    const texts = got.some((x) => !!x.text.trim());
    ztrack('zzal_persona_q_answered', { step: idx + 2, type: step.key, count: chips, action: chips && texts ? 'both' : chips ? 'chip' : 'text' });
    setSkipped((sk) => sk.filter((k) => k !== step.key));
    await putIn(pieces, step.color, step.key);
    setBusyFx(false);
    if (idx + 1 >= N) finish(settledOf(answers, N));
    else setIdx(idx + 1);
  }, [step, busyFx, answers, idx, N, putIn, finish]);

  // ── 건너뛰기 ──
  const skipOne = useCallback(async () => {
    if (!step || busyFx) return;
    setBusyFx(true);
    ztrack('zzal_persona_q_skipped', { step: idx + 2, type: step.key, action: 'one' });
    // 넣지 않은 칸은 알에 안 들어간다 — 화면에 남아 있던 고른 것도 비운다(보낸 것 = 알에 들어간 것).
    const next = { ...answers };
    for (const f of step.fields) delete next[f.key];
    setAnswers(next);
    setPlaced((p) => p.filter((k) => k !== step.key));
    setSkipped((sk) => (sk.includes(step.key) ? sk : [...sk, step.key]));
    await tilt();
    setBusyFx(false);
    if (idx + 1 >= N) finish(next);
    else setIdx(idx + 1);
  }, [step, busyFx, idx, answers, N, tilt, finish]);

  const skipRest = useCallback(() => {
    if (!step || busyFx) return;
    ztrack('zzal_persona_q_skipped', { step: idx + 2, type: step.key, action: 'rest' });
    // 지금 칸과 그 뒤(「이전」으로 되돌려 점이 빠진 칸)에 남은 것은 알에 안 들었으므로 버린다.
    const next = settledOf(answers, idx);
    setAnswers(next);
    setSkipped((sk) => [...sk, ...PERSONA_STEPS.slice(idx).map((st) => st.key).filter((k) => !sk.includes(k))]);
    finish(next);
  }, [step, busyFx, idx, answers, finish]);

  // ── 만나러 가기 ──
  const meet = useCallback(async () => {
    if (meeting) return;
    // 넣던 칸이 남아 있어도 끊지 않고 지금까지 넣은 것(점이 앉은 칸)만 보낸다.
    const ok = await submit(idx >= N ? answers : settledOf(answers, Math.max(0, idx)));
    if (!ok) return;
    setMeeting(true);
  }, [meeting, submit, answers, idx, N]);

  // 서버가 살렸다는 답(ALIVE)이 오면 그때 깬다. `tapEgg` 가 깨짐 → 태어남 칸으로 옮긴다.
  const cracked = useRef(false);
  useEffect(() => {
    if (!meeting || !live.ready || cracked.current) return;
    cracked.current = true;
    actions.tapEgg();
  }, [meeting, live.ready, actions]);

  // ── 깨지는 그림(Egg.tsx 와 같은 순서: 멀쩡 → 금 → 부서짐) ──
  const isCrack = v.egg.isCrack;
  const [broke, setBroke] = useState(false);
  useEffect(() => {
    if (!isCrack) { setBroke(false); return; }
    const t = setTimeout(() => setBroke(true), Math.min(EGG_BREAK_MS, EGG_CRACK_MS - 200));
    return () => clearTimeout(t);
  }, [isCrack]);
  const eggImg = isCrack ? (broke ? EGG_IMG.hatch : EGG_IMG.crack) : EGG_IMG.idle;
  const eggAnim = isCrack ? 'yCrack 1.45s cubic-bezier(.36,.07,.19,.97) 1 both'
    : !named ? 'none'
      : baked ? 'yShakeBig 1.35s ease-in-out infinite' : 'yWiggle 2.2s ease-in-out infinite';

  // ── 알 아래 한 줄 ──
  const left = live.etaSeconds > 0 && !baked && !live.failed
    ? (live.etaSeconds >= 60 ? `약 ${Math.ceil(live.etaSeconds / 60)}분` : '곧')
    : '';
  const status = live.failed
    ? (live.message ?? '이 그림은 좀 어렵네요')
    : !named ? '이름을 기다리는 알'
      : baked ? '다 됐어요'
        : (live.step ?? '깨어나는 중');
  const count = named && live.total > 0 ? `${Math.min(live.progress, live.total)} / ${live.total}` : '';

  // 글로우 세기 — 넣을수록 조금씩 밝아진다(glow 판의 보상). dots 판은 은은하게 고정.
  const glowBase = variant === 'glow' ? Math.min(0.95, 0.35 + placed.length * 0.09) : 0.18;

  const room = useMemo(() => extraRoom(answers), [answers]);

  const setChip = (f: PersonaField, chip: string) => {
    setAnswers((a) => {
      const cur = answerOf(a, f.key);
      return { ...a, [f.key]: { ...cur, chips: pickChips(f, cur.chips, chip) } };
    });
  };
  const setText = (fkey: string, t: string, max: number) => {
    setAnswers((a) => ({ ...a, [fkey]: { ...answerOf(a, fkey), text: t.slice(0, max) } as FieldAnswer }));
  };

  /**
   * 「이전」(머리줄 ‹ 도 같다). 앞 칸으로 가며 **그 칸의 점이 알에서 빠진다** — 점은 칸으로 돌아오는 0.3초
   * 역연출(동작 줄이기면 즉시). 그 칸의 글·칩은 지우지 않아 그대로 보이고 다시 고칠 수 있다.
   * 이름 칸에서는 올리기 화면으로 돌아간다(머리줄 ‹ 만, 아래 「이전」은 숨김).
   */
  const back = useCallback(async () => {
    if (busyFx || submitted) return;
    if (idx < 0) { actions.onBack(); return; }
    const target = Math.min(idx, N) - 1;
    const st = target < 0 ? null : PERSONA_STEPS[target];
    const key = st ? st.key : NAME_STEP.key;
    const color = st ? st.color : NAME_STEP.color;
    const dot = eggBox.current?.querySelector<HTMLElement>(`[data-part="pe-dot"][data-key="${key}"]`);
    const from = dot?.getBoundingClientRect();
    const to = sheetRef.current?.getBoundingClientRect();
    (document.activeElement as HTMLElement | null)?.blur?.();
    setPlaced((p) => p.filter((k) => k !== key));
    setIdx(target);
    if (from && to && variant === 'dots' && !reducedMotion()) {
      setBusyFx(true);
      await flyBack(from, to, color);
      setBusyFx(false);
    }
  }, [busyFx, submitted, idx, N, actions, variant]);

  const stepAnswered = !!step && step.fields.some((f) => hasAnswer(answerOf(answers, f.key)));
  const showReadyNote = baked && named && !isCrack && idx < N;
  const waitingDone = idx >= N;

  // ── 화면 ──
  return (
    <div
      className="pe-root"
      data-part="persona-egg" data-variant={variant} data-q={idx < 0 ? 'name' : step?.key ?? 'done'}
      data-kb={kb ? '1' : undefined} data-baked={baked ? '1' : undefined}
      style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', background: C.eggBg }}
    >
      <style>{STYLE}</style>
      <span aria-live="polite" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap' }}>
        {idx < 0 ? NAME_STEP.q : step ? `${idx + 2}번째 칸 · ${step.q}` : baked ? '다 됐어요. 만나러 갈 수 있어요.' : '다 넣었어요. 곧 나와요.'}
      </span>

      {/* 머리줄 — 뒤로 · 묶음 이름 · 나머지는 나중에 */}
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: gap.sm, padding: '6px 14px 0', minHeight: TAP_MIN }}>
        {!submitted ? (
          <button
            type="button" onClick={() => void back()} aria-label="뒤로" data-action="pe-back" className="onb-back"
            style={{ width: TAP_MIN, height: TAP_MIN, display: 'flex', alignItems: 'center', justifyContent: 'center', border: 'none', background: 'none', padding: 0 }}
          >
            <span style={{ border: `1px solid ${C.lineHard}`, background: C.paper, borderRadius: radius.pill, width: 28, height: 28, fontSize: fz.md, color: C.sub2, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>‹</span>
          </button>
        ) : <span style={{ width: TAP_MIN }} />}
        <span style={{ flex: 1, textAlign: 'center', font: `${monoSize.sm}px ${MONO}`, color: ink(.45) }}>
          {idx < 0 ? '' : step ? `${step.section} · ${idx + 2}/${N + 1}` : ''}
        </span>
        {step && !submitted ? (
          <button
            type="button" onClick={skipRest} data-action="pe-skip-rest" disabled={busyFx}
            style={{ minHeight: TAP_MIN, padding: '0 6px', border: 'none', background: 'none', fontSize: fz.sm, color: C.sub2, textDecoration: 'underline', textUnderlineOffset: 3 }}
          >나머지는 나중에</button>
        ) : <span style={{ width: TAP_MIN }} />}
      </div>

      {/* 알 무대 — 화면 위 40%. 누르는 자리는 없다(보기만). */}
      <div className="pe-stage" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: gap.sm, padding: '0 16px' }}>
        <div
          ref={eggBox} data-part="pe-egg"
          style={{ position: 'relative', flex: '1 1 auto', minHeight: 56, maxHeight: 200, aspectRatio: '1 / 1', height: '100%' }}
        >
          <span
            ref={halo} aria-hidden
            style={{
              position: 'absolute', inset: variant === 'glow' ? '-22%' : '-8%', borderRadius: '50%', pointerEvents: 'none',
              background: `radial-gradient(closest-side, ${lastColor}cc 0%, ${lastColor}55 45%, ${lastColor}00 100%)`,
              opacity: named ? glowBase : 0.12, transition: 'opacity .3s',
            }}
          />
          <div className="pe-eggwrap" style={{ position: 'absolute', inset: 0, animation: eggAnim, transformOrigin: '50% 80%' }}>
            <div ref={eggReact} style={{ position: 'absolute', inset: 0, transformOrigin: '50% 85%', opacity: named || isCrack ? 1 : 0.88 }}>
              {imgFailed ? <CssEgg /> : (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={eggImg} alt="알" onError={() => setImgFailed(true)}
                  style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block' }}
                />
              )}
              {variant === 'dots' && !isCrack && placed.map((k, i) => {
                const st = k === NAME_STEP.key ? null : PERSONA_STEPS.find((x) => x.key === k);
                const color = st ? st.color : NAME_STEP.color;
                const [lx, ty] = DOT_AT[i % DOT_AT.length];
                return (
                  <span
                    key={k} data-part="pe-dot" data-key={k}
                    style={{
                      position: 'absolute', left: `${lx}%`, top: `${ty}%`, width: 13, height: 13, marginLeft: -6.5, marginTop: -6.5,
                      borderRadius: '50%', background: color, border: '2px solid #FFFDF8',
                      boxShadow: `0 0 0 1px ${ink(.22)}, 0 1px 3px ${ink(.25)}`, animation: reducedMotion() ? undefined : 'yPop .3s ease both',
                    }}
                  />
                );
              })}
            </div>
          </div>
        </div>
        <span className="pe-status" data-part="pe-status" style={{ display: 'flex', alignItems: 'center', gap: gap.sm, flex: 'none', fontSize: fz.sm, color: live.failed ? C.accent : ink(.58) }}>
          <span>{status}</span>
          {count && <span style={{ font: `${monoSize.xs}px ${MONO}`, color: ink(.4) }}>{count}</span>}
          {left && <span style={{ color: ink(.45) }}>· {left}</span>}
        </span>
      </div>

      {/* 다 구워졌는데 아직 넣는 중 — 끊지 않고 한 줄만. */}
      {showReadyNote && (
        <div data-part="pe-ready-note" style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: gap.sm, margin: '0 16px', padding: '6px 6px 6px 14px', borderRadius: radius.pill, background: C.paper, border: `1px solid ${C.accentDim}` }}>
          <span style={{ flex: 1, fontSize: fz.sm, lineHeight: 1.4, color: C.ink }}>다 됐어요 · 남은 칸은 나중에 해도 돼요</span>
          <button
            type="button" onClick={meet} data-action="pe-meet" disabled={sending || meeting}
            style={{ flex: 'none', minHeight: TAP_MIN, padding: '0 16px', borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: fz.md, fontFamily: GAEGU, fontWeight: 700 }}
          >{sending || meeting ? '깨우는 중…' : '지금 만나러 가기'}</button>
        </div>
      )}

      {/* 아래 — 질문 한 칸(스크롤) + 단추(고정) */}
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', background: C.paper, borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, boxShadow: `0 -6px 20px ${ink(.06)}`, marginTop: gap.sm }}>
        <div ref={sheetRef} className="pe-scroll" style={{ flex: '1 1 auto', minHeight: 0, overflow: 'auto', padding: '16px 20px 8px', display: 'flex', flexDirection: 'column', gap: gap.md }}>
          {idx < 0 && (
            <>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, lineHeight: 1.25, color: C.ink }}>{NAME_STEP.q}</span>
              <span className="pe-sub" style={{ fontSize: fz.sm, lineHeight: 1.5, color: C.sub2 }}>
                {live.resumedDraft ? '올려 두신 그림이 있어요. 이름만 지어 주면 이어서 시작해요.' : NAME_STEP.sub}
              </span>
              <div style={{ display: 'flex', gap: gap.sm }}>
                <input
                  ref={nameRef} value={s.petName} maxLength={NAME_STEP.max} placeholder={NAME_STEP.ph}
                  onChange={(e) => { actions.onName(e.target.value); setNameErr(false); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') void putName(); }}
                  data-part="pet-name" aria-label="이름" enterKeyHint="done"
                  style={{ flex: 1, minWidth: 0, boxSizing: 'border-box', minHeight: TAP_MIN, padding: '10px 14px', borderRadius: radius.md, border: `1px solid ${nameErr ? C.accent : C.lineHard}`, background: C.paper, fontSize: 16, color: C.ink, outline: 'none' }}
                />
                <button type="button" onClick={actions.randomName} style={{ flex: 'none', minHeight: TAP_MIN, padding: '0 16px', borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: fz.md, color: C.sub2 }}>랜덤</button>
              </div>
              {nameErr && <span style={{ fontSize: fz.sm, color: C.accent }}>이름을 지어 주면 알이 깨어나요.</span>}
            </>
          )}

          {step && (
            <>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, lineHeight: 1.25, color: C.ink }}>{step.q}</span>
              {step.sub && <span className="pe-sub" style={{ fontSize: fz.sm, lineHeight: 1.5, color: C.sub2 }}>{step.sub}</span>}
              {step.fields.map((f) => {
                const x = answerOf(answers, f.key);
                const max = f.target === 'note' && !f.noteLabel ? Math.min(f.text?.max ?? 200, room) : (f.text?.max ?? 0);
                return (
                  <div key={f.key} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
                    {f.label && <span style={{ fontSize: fz.sm, color: C.sub2 }}>{f.label}</span>}
                    {rowsOf(f).map((g, gi) => (
                      <div key={gi} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                      {g.name && <span style={{ fontSize: fz.xs, color: ink(.5) }}>{g.name}</span>}
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
                        {g.chips.map((c) => {
                          const on = x.chips.includes(c);
                          return (
                            <button
                              key={c} type="button" className="pe-chip" data-part="pe-chip" data-chip={c} aria-pressed={on}
                              data-flying={busyFx && on ? '1' : undefined}
                              ref={(el) => { chipRefs.current[`${f.key}:${c}`] = el; }}
                              onClick={() => setChip(f, c)} disabled={busyFx}
                              style={{ minHeight: TAP_MIN, padding: '0 15px', borderRadius: radius.pill, border: `${on ? 2 : 1}px solid ${on ? C.accent : C.lineHard}`, background: on ? C.accentSoft : C.paper, fontSize: fz.md, color: on ? C.accent : C.ink }}
                            >{c}</button>
                          );
                        })}
                      </div>
                      </div>
                    ))}
                    {f.text && (
                      // ★ 글 칸은 textarea 다 — 자리표시가 실제 사람이 쓴 세세한 예 2~3개라 길다(칩 어휘 v2).
                      //   input 이면 320 폭에서 자리표시가 **잘린다**. 두 줄로 접히게 둔다. Enter 는 넣기(줄바꿈 아님).
                      <textarea
                        ref={(el) => { textRefs.current[f.key] = el; }}
                        value={x.text} maxLength={max} placeholder={f.text.ph} rows={1} className="pe-text"
                        onChange={(e) => setText(f.key, e.target.value.replace(/\n/g, ' '), max)}
                        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (stepAnswered) void putStep(); } }}
                        data-part="pe-text" data-field={f.key} aria-label={f.label ?? step.q} enterKeyHint="done"
                        style={{ boxSizing: 'border-box', minHeight: TAP_MIN, padding: '10px 14px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 16, lineHeight: 1.4, color: C.ink, outline: 'none', resize: 'none', fontFamily: 'inherit', opacity: busyFx && x.text.trim() ? 0.35 : 1 }}
                      />
                    )}
                    {f.target === 'note' && !f.noteLabel && room < 60 && (
                      <span data-part="pe-room" style={{ fontSize: fz.xs, color: C.sub2 }}>앞에서 넣은 것까지 합쳐 {room - x.text.length}자 남았어요</span>
                    )}
                  </div>
                );
              })}
            </>
          )}

          {waitingDone && (
            <>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, lineHeight: 1.25, color: C.ink }}>
                {isCrack ? '지금 나오고 있어요' : baked ? '다 됐어요' : '다 넣었어요'}
              </span>
              <span className="pe-sub" style={{ fontSize: fz.sm, lineHeight: 1.6, color: C.sub2 }}>
                {isCrack ? '잠시만요.' : baked ? '이제 만나러 가도 돼요.' : `곧 나와요.${skipped.length ? ' 비워 둔 칸은 비워 둬도 괜찮아요.' : ''}`}
              </span>
            </>
          )}
        </div>

        {/* 단추 — 한 줄: 「이전」 · 「다음」 · 「건너뛰기」(상훈님 10/13 순서 그대로). 높이 52, 320 폭에서도 한 줄.
            빈 칸이면 「다음」은 흐리고 「건너뛰기」에 테두리를 둘러 지금 누를 단추가 하나로 보이게 한다. */}
        <div style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: 2, padding: '8px 16px', paddingBottom: 'max(14px, env(safe-area-inset-bottom))' }}>
          {(sendErr || live.error || live.blocked) && (
            <span data-part="pe-error" style={{ fontSize: fz.sm, lineHeight: 1.5, color: live.blocked ? C.sub2 : C.accent, textAlign: 'center', paddingBottom: 4 }}>
              {live.blocked ? `${live.blocked.title} ${live.blocked.body}` : (live.error ?? sendErr)}
            </span>
          )}
          {(idx < 0 || step) && (
            <div data-part="pe-nav" style={{ display: 'flex', alignItems: 'stretch', gap: gap.sm }}>
              {step && (
                <button
                  type="button" onClick={() => void back()} data-action="pe-prev" disabled={busyFx} aria-label="이전 칸"
                  style={{ ...NAV_SIDE, border: `1px solid ${C.lineHard}`, background: C.paper, color: C.sub2, padding: '0 14px 0 10px' }}
                ><span aria-hidden style={{ fontSize: fz.xl, lineHeight: 1, marginTop: -2 }}>‹</span>이전</button>
              )}
              {idx < 0 ? (
                <button
                  type="button" onClick={() => void putName()} data-action="pe-put" disabled={busyFx}
                  style={{ ...NAV_MAIN, background: s.petName.trim() ? C.accent : C.off, color: s.petName.trim() ? C.accentInk : C.sub2, boxShadow: s.petName.trim() ? `0 6px 16px ${acc(.24)}` : 'none' }}
                >다음</button>
              ) : (
                <button
                  type="button" onClick={() => void putStep()} data-action="pe-put" disabled={busyFx || !stepAnswered}
                  style={{ ...NAV_MAIN, background: stepAnswered ? C.accent : C.off, color: stepAnswered ? C.accentInk : C.sub2, boxShadow: stepAnswered ? `0 6px 16px ${acc(.24)}` : 'none' }}
                >다음</button>
              )}
              {step && (
                <button
                  type="button" onClick={() => void skipOne()} data-action="pe-skip" disabled={busyFx}
                  style={{ ...NAV_SIDE, padding: '0 14px', border: `1px solid ${stepAnswered ? 'transparent' : C.lineHard}`, background: stepAnswered ? 'none' : C.paper, color: stepAnswered ? C.sub2 : C.ink }}
                >건너뛰기</button>
              )}
            </div>
          )}
          {waitingDone && !isCrack && (
            baked || meeting ? (
              <button
                type="button" onClick={() => void meet()} data-action="pe-meet" disabled={sending || meeting}
                style={{ minHeight: 52, borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: fz.xl, fontFamily: GAEGU, fontWeight: 700, boxShadow: `0 6px 16px ${acc(.24)}` }}
              >{sending || meeting ? '깨우는 중…' : '지금 만나러 가기'}</button>
            ) : (
              <>
                {sendErr && (
                  <button
                    type="button" onClick={() => void submit(answers)} data-action="pe-retry" disabled={sending}
                    style={{ minHeight: 52, borderRadius: radius.pill, border: `1px solid ${C.accent}`, background: C.paper, color: C.accent, fontSize: fz.lg }}
                  >다시 담기</button>
                )}
                <button
                  type="button" onClick={actions.onNext} data-action="pe-play-sample" disabled={sending || !submitted}
                  style={{ minHeight: TAP_MIN, border: 'none', background: 'none', fontSize: fz.md, color: C.sub2, textDecoration: 'underline', textUnderlineOffset: 3 }}
                >기다리는 동안 여울이랑 놀기</button>
              </>
            )
          )}
        </div>
      </div>
    </div>
  );
}
