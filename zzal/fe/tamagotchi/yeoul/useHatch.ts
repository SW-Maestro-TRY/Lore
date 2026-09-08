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
  ready: false, failed: false, step: null,
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
      setImageKey(null);
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
  }, []);

  return {
    previewUrl, imageKey, petId, pet, busy, error,
    ready: pet?.phase === 'ALIVE',
    failed: pet?.phase === 'FAILED' || pet?.phase === 'DEAD',
    step: pet?.step ?? null,
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
 *   누구를  :  서버가 준 내 아이 그림  →  없으면 여울 폴백
 *   무엇을  :  지금 하는 동작  →  없으면 상태(아픔·잠·배고픔…)  →  기본
 *
 * '무엇을' 은 화면이 `key` 로 정해 넘기고(→ `useYeoul` 의 `spriteKey`), 여기서는 '누구를' 만 푼다.
 *
 * ★ **잠긴 동작은 대신 그린다.** 2층 동작(`wash`·`sleep`·`nod` …)은 아직 안 열렸을 수 있어,
 *   그 자리를 비우면 아무 그림도 안 나온다. `MOTION_FALLBACK` 이 그때 무엇을 대신 그릴지 적은 표다.
 *   순서는 **열린 진짜 동작 → 폴백 동작 → 여울**. 이 판정을 여기 한 곳에서만 한다 —
 *   화면마다 따로 하면 어느 한 곳이 빠지고, 빠진 자리는 빈 그림이라 조용히 티가 안 난다.
 */
export function spriteUrl(live: Live, key: string): string {
  const alt = MOTION_FALLBACK[key];
  return live.img(key)
    ?? (alt ? live.img(alt) : null)
    ?? YEOUL_MOTION[key]
    ?? (alt ? YEOUL_MOTION[alt] : undefined)
    ?? YEOUL_MOTION.base;
}
