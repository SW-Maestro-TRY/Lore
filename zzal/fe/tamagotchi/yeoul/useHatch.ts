// 진짜 서버에 붙는 **한 줄기**만 담당한다 — 그림 올리기 → 부화 시작 → 다 될 때까지 지켜보기 →
// 방 화면에 내 아이 그림 끼우기.
//
// ★ 왜 `useYeoul` 안에 넣지 않았나
//   `useYeoul` 은 시안을 눌러 보기 위한 **프론트 전용 목**이다. 거기에 서버를 섞으면
//   "화면이 이상한 것" 과 "서버가 이상한 것" 이 한 덩어리가 되어 판정이 안 된다.
//   그래서 서버는 이 파일 하나에 가두고, 목에는 결과(그림 주소·부화 완료)만 건넨다.
//   그림을 안 올리고 넘어가면 이 훅은 통째로 잠자고 화면은 지금까지처럼 여울로 돈다.
//
// ★ 지금 잇는 것은 **부화까지**다. 돌보기 수치·채팅·앨범은 아직 목이다(2026-09-07 상훈님 결정).
'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { assetUrl } from '../../lib/assets';
import { MOTION_FALLBACK, YEOUL_MOTION } from '../constants';
import { BASIC_KEYS } from './constants';
import { createPet, getPet, type PetDetail } from '../../lib/pet';
import { uploadImage } from '../../lib/upload';

export interface Live {
  /** 고른 그림(미리보기용). 서버에 올리기 전에도 화면에 보여 준다. */
  previewUrl: string | null;
  /** 올리기가 끝나 받은 키. 이게 있어야 부화를 시작할 수 있다. */
  imageKey: string | null;
  petId: number | null;
  pet: PetDetail | null;
  busy: boolean;
  error: string | null;
  /** 부화가 끝났는가. */
  ready: boolean;
  failed: boolean;
  /** 부화 중 지금 하는 일 한 줄(서버 문구). */
  step: string | null;
  /**
   * 알 화면의 네 칸(0~4). **아이가 있으면 이것이 진행의 유일한 근거**다.
   *
   * ★ 시간으로 재지 않는다 — 그림 굽는 데 몇 분이 걸릴지 우리가 모르므로 시계로 칸을 채우면
   *   거짓말이 된다. 대신 서버가 알려 주는 **지금 하는 일(step)이 바뀐 횟수**를 센다.
   *   단계가 실제로 넘어가야 칸이 찬다.
   * ★ 4 는 서버가 `ALIVE` 라고 답했을 때에만 나온다. 그 전에는 3 에서 멈춘다 —
   *   아직 안 끝났는데 '다 됐어요' 가 뜨면 눌러도 안 열리는 문이 된다.
   */
  progress: number;
  /**
   * 기본 8종 중 **서버가 그림을 안 준 것**. 있으면 안 되는 상태다(→ `BASIC_KEYS` 주석).
   * ⚠️ 지금 개발 중에는 가짜 생성이 6종만 만들어서 `sick`·`call` 이 늘 여기 담긴다 —
   *   **정상적인 경고**다. 진짜 생성으로 바꾸면 비어야 한다. 이 경고를 지우지 말 것.
   */
  missingBasics: string[];
  /** 서버가 준 내 아이 그림(카탈로그 key). 아직 없으면 null → 화면은 여울로 폴백한다. */
  img: (key: string) => string | null;
  /** 파일 하나를 올린다. 실패하면 error 에 한국어 한 줄이 남는다. */
  upload: (file: File) => Promise<void>;
  /** 부화 시작. 그림을 안 올렸으면 아무 일도 안 한다(목으로 계속 간다). */
  start: (name: string, note: string) => Promise<void>;
  reset: () => void;
}

const EMPTY: Live = {
  previewUrl: null, imageKey: null, petId: null, pet: null, busy: false, error: null,
  ready: false, failed: false, step: null, progress: 0, missingBasics: [],
  img: () => null, upload: async () => {}, start: async () => {}, reset: () => {},
};

export function useHatchState(): Live {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [imageKey, setImageKey] = useState<string | null>(null);
  const [petId, setPetId] = useState<number | null>(null);
  const [pet, setPet] = useState<PetDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const objectUrl = useRef<string | null>(null);
  // 지금까지 본 단계 이름들. 같은 이름이 다시 와도 한 번만 센다.
  const [seenSteps, setSeenSteps] = useState<string[]>([]);

  // 미리보기 주소는 브라우저 메모리를 잡으므로 바뀌거나 떠날 때 놓아 준다.
  useEffect(() => () => { if (objectUrl.current) URL.revokeObjectURL(objectUrl.current); }, []);

  const upload = useCallback(async (file: File) => {
    setBusy(true);
    setError(null);
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = URL.createObjectURL(file);
    setPreviewUrl(objectUrl.current);
    try {
      // ★ 한 key 는 한 번만 쓸 수 있다. 실패하면 presign 부터 다시 — 같은 key 로 재시도하지 않는다.
      setImageKey(await uploadImage(file, 'zzal'));
    } catch (e) {
      // ★ 실패하면 미리보기도 함께 지운다(상훈님 판정 19). 그림만 크게 남아 있으면
      //   작은 오류 한 줄보다 그림이 먼저 읽혀 성공한 줄 안다.
      setImageKey(null);
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = null;
      setPreviewUrl(null);
      setError(e instanceof Error ? e.message : '그림을 올리지 못했어요');
    } finally {
      setBusy(false);
    }
  }, []);

  const start = useCallback(async (name: string, note: string) => {
    if (!imageKey || petId) return;
    setBusy(true);
    setError(null);
    try {
      const created = await createPet({ name, note: note || undefined, imageKey });
      setPetId(created.petId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '부화를 시작하지 못했어요');
    } finally {
      setBusy(false);
    }
  }, [imageKey, petId]);

  // 부화가 끝날 때까지 3초마다 들여다본다. 끝나면 스스로 멈춘다.
  const done = pet?.phase === 'ALIVE' || pet?.phase === 'FAILED' || pet?.phase === 'DEAD';
  useEffect(() => {
    if (!petId || done) return;
    let alive = true;
    const look = async () => {
      try {
        const next = await getPet(petId);
        if (alive) setPet(next);
      } catch {
        // 한 번 못 읽은 것으로 화면을 깨뜨리지 않는다. 다음 차례에 다시 묻는다.
      }
    };
    look();
    const t = setInterval(look, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [petId, done]);

  // 단계가 넘어갈 때마다 한 칸. 서버가 말해 준 것만 센다.
  const stepName = pet?.step ?? null;
  useEffect(() => {
    if (!stepName) return;
    setSeenSteps((v) => (v.includes(stepName) ? v : [...v, stepName]));
  }, [stepName]);

  /**
   * 카탈로그 key 하나를 **내 아이 그림 주소**로. 아직 못 받았으면 null.
   * ★ 18 동작 전부를 받는다 — 서버 `Motion.key` 와 우리 key 는 같은 이름이라 표가 필요 없다.
   */
  const img = useCallback((key: string) => {
    const m = pet?.motions?.find((x) => x.key === key);
    return m?.basicImageKey ? assetUrl(m.basicImageKey) : null;
  }, [pet]);

  const reset = useCallback(() => {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null;
    setPreviewUrl(null); setImageKey(null); setPetId(null); setPet(null); setError(null);
    setSeenSteps([]);
  }, []);

  return {
    previewUrl, imageKey, petId, pet, busy, error,
    ready: pet?.phase === 'ALIVE',
    failed: pet?.phase === 'FAILED' || pet?.phase === 'DEAD',
    step: pet?.step ?? null,
    progress: pet?.phase === 'ALIVE' ? 4 : Math.min(3, seenSteps.length),
    missingBasics: pet?.phase === 'ALIVE'
      ? BASIC_KEYS.filter((k) => !pet.motions?.some((m) => m.key === k && m.basicImageKey))
      : [],
    img, upload, start, reset,
  };
}

const LiveContext = createContext<Live>(EMPTY);
export const LiveProvider = LiveContext.Provider;

/** 화면 어디서나 쓴다. 서버에 안 붙은 상태(그림 없이 계속)면 전부 null 이라 폴백이 걸린다. */
export function useLive(): Live {
  return useContext(LiveContext);
}

// ── 발밑 여백 재기 ──────────────────────────────────────────────────────
//
// ★ 왜 상수로 두면 안 되는가
//   배경을 지운 그림은 발 아래가 비어 있고, 그 여백이 **그림마다 다르다**.
//   여울 시연본은 350 중 54px(15.4%)인데, 서버가 만든 아이는 30px(8.6%)이었다(2026-09-07 실측).
//   상수 하나로 내리면 어떤 아이는 뜨고 어떤 아이는 바닥에 잠긴다 — 그리고 **아무 소리도 안 난다.**
//   그래서 그림에서 직접 잰다. 못 재면 넘겨받은 기본값으로 되돌아간다.
//
// ★ 지금은 **같은 출처의 그림만** 잰다. 생성된 그림은 CloudFront 에서 오는데 그쪽이 CORS 헤더를
//   안 줘서 캔버스로 읽는 순간 막히고, 콘솔에 오류만 쌓인다(2026-09-07 실측). 시도조차 안 하는
//   편이 조용하다. `/images/*` 를 같은 출처로 넘기는 프록시가 생기면 그때 저절로 켜진다.
//   그때까지 생성된 아이는 여울 기준값으로 앉는다 — 상훈님 결정(2026-09-07): 지금은 이대로 간다.
const padCache = new Map<string, number>();

export function useFootPad(src: string, fallback: number): number {
  const [pad, setPad] = useState(() => padCache.get(src) ?? fallback);

  useEffect(() => {
    const cached = padCache.get(src);
    if (cached !== undefined) { setPad(cached); return; }
    if (!src) return;
    // 다른 출처면 어차피 못 읽는다. 조용히 기본값으로 간다.
    try {
      if (new URL(src, window.location.href).origin !== window.location.origin) return;
    } catch { return; }
    let alive = true;
    const img = new Image();
    // 다른 출처의 그림을 캔버스로 읽으려면 이 표시가 있어야 한다(없으면 읽는 순간 막힌다).
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = img.naturalWidth; c.height = img.naturalHeight;
        const ctx = c.getContext('2d');
        if (!ctx) return;
        ctx.drawImage(img, 0, 0);
        const d = ctx.getImageData(0, 0, c.width, c.height).data;
        let bottom = -1;
        // 아래에서 위로 훑다가 처음 만나는 불투명한 줄이 발끝이다.
        for (let y = c.height - 1; y >= 0 && bottom < 0; y--) {
          for (let x = 0; x < c.width; x++) {
            if (d[(y * c.width + x) * 4 + 3] > 10) { bottom = y; break; }
          }
        }
        if (bottom < 0) return;
        const p = (c.height - 1 - bottom) / c.height;
        padCache.set(src, p);
        if (alive) setPad(p);
      } catch {
        // 캔버스를 못 읽는 경우(CORS)엔 기본값 그대로 간다. 화면은 멀쩡히 돈다.
      }
    };
    img.src = src;
    return () => { alive = false; };
  }, [src, fallback]);

  return pad;
}

/**
 * **어떤 그림을 그릴지 정하는 단 한 곳.** 축이 둘이고, 각각 순서가 있다.
 *
 *   누구를  :  내 아이 그림  →  (여울 샘플 방에서만) 여울
 *   무엇을  :  지금 하는 동작  →  없으면 상태(아픔·잠·배고픔…)  →  기본
 *
 * '무엇을' 은 화면이 `key` 로 정해 넘기고(→ `useYeoul` 의 `spriteKey`), 여기서는 '누구를' 만 푼다.
 *
 * ★ **진짜 방에서는 여울로 내려가지 않는다**(상훈님 2026-09-08).
 *   방에 들어왔다는 것은 기본 8종이 다 만들어졌다는 뜻이라(→ `BASIC_KEYS`), 거기서 여울이 보이면
 *   그건 폴백이 아니라 **고장을 덮은 것**이다. 그래서 진짜 방의 폴백은 **기본 8종 안에서** 끝난다:
 *     2층 동작(`wash`·`sleep`·`nod` …) → `MOTION_FALLBACK` → 기본 8종. 사슬은 전부 기본 8종에서 끝난다(실측).
 *   그래도 없으면 `base` 로 버티되 **콘솔에 경고**를 남긴다 — 조용히 넘어가면 생성이 8종을 못 채워도 아무도 모른다.
 *
 * ★ 여울은 **샘플 방 전용**이다. 거기서는 여울이 주인공이라 그게 맞다.
 *   펫이 아예 없는데 진짜 방에 있는 경우는 **개발용 '이동' 으로 건너뛴 때뿐**이라, 그때만 여울로 버틴다.
 */
const warned = new Set<string>();

export function spriteUrl(live: Live, key: string, sample = false): string {
  const alt = MOTION_FALLBACK[key];
  const yeoul = YEOUL_MOTION[key] ?? (alt ? YEOUL_MOTION[alt] : undefined) ?? YEOUL_MOTION.base;

  // 여울 샘플 방 · 펫이 없는 개발용 경로 — 여울로 그린다.
  if (sample || !live.petId) return yeoul;

  const mine = live.img(key) ?? (alt ? live.img(alt) : null);
  if (mine) return mine;

  const base = live.img('base');
  if (!warned.has(key)) {
    warned.add(key);
    // eslint-disable-next-line no-console
    console.warn(`[여울] 내 아이 그림이 없습니다 — key=${key}${alt ? ` (폴백 ${alt} 도 없음)` : ''}. `
      + `${base ? 'base 로 버팁니다.' : 'base 마저 없어 여울로 버팁니다.'} 기본 8종은 방에 들어온 시점에 다 있어야 합니다.`);
  }
  return base ?? yeoul;
}
