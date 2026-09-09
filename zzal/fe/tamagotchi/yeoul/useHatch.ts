// 진짜 서버에 붙는 **한 줄기**만 담당한다 — 그림 올리기 → 초안 → 이름 → 다 될 때까지 지켜보기 →
// 방 화면에 내 아이 그림 끼우기.
//
// ★ 왜 `useYeoul` 안에 넣지 않았나
//   `useYeoul` 은 시안을 눌러 보기 위한 **프론트 전용 목**이다. 거기에 서버를 섞으면
//   "화면이 이상한 것" 과 "서버가 이상한 것" 이 한 덩어리가 되어 판정이 안 된다.
//   그래서 서버는 이 파일 하나에 가두고, 목에는 결과(그림 주소·부화 진행)만 건넨다.
//   그림을 안 올리고 넘어가면 이 훅은 통째로 잠자고 화면은 지금까지처럼 여울로 돈다.
//
// ★ 지금 잇는 것은 **부화까지**다. 돌보기 수치·채팅·앨범은 아직 목이다(2026-09-07 상훈님 결정).
//
// ★ 2026-09-09 새 계약(`프론트-연동-계약-0909.md` 4절) — 펫 만들기가 **두 번으로 갈렸다**.
//     1) 그림을 올린 **그 순간** `draft` → 서버가 캐릭터 시트를 미리 굽기 시작한다
//     2) 이름을 받은 순간 `character` → 격자 생성 시작(알이 흔들리는 자리)
//   사용자가 이름을 짓는 약 74초를 그대로 버는 것이 이 분리의 목적이다.
//   예전처럼 이름까지 다 받고 한 번에 만들면 그 시간이 통째로 버려진다.
'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { assetUrl } from '../../lib/assets';
import { MOTION_FALLBACK, YEOUL_MOTION } from '../constants';
import { BASIC_KEYS } from './constants';
import {
  care, draftPet, getHatchProgress, getPet, listPets, setCharacter,
  type CareAction, type CharacterInput, type HatchProgress, type PetDetail,
} from '../../lib/pet';
import { uploadImage } from '../../lib/upload';

export interface Live {
  /** 고른 그림(미리보기용). 서버에 올리기 전에도 화면에 보여 준다. */
  previewUrl: string | null;
  /** 올리기가 끝나 받은 키. */
  imageKey: string | null;
  /** 초안이 잡힌 순간부터 있다. 그림을 올리면 바로 생긴다(이름은 아직 없다). */
  petId: number | null;
  pet: PetDetail | null;
  busy: boolean;
  error: string | null;
  /**
   * 그림만 올려 둔 채 이름이 아직 없는 아이가 서버에 있다 — "이어서 이름을 지어 주세요".
   * 계약 4절: 이름을 안 짓고 나갔다 오면 `draft` 가 **같은 petId** 를 준다. 이미 구운
   * 시트를 다시 쓰므로 돈이 두 번 안 나간다. 화면은 처음부터 다시 올리게 하면 안 된다.
   */
  draftOnly: boolean;
  /**
   * 이번 방문에서 **두고 간 초안을 찾아 이어붙였다.** 방금 올린 사람과 구분하려고 따로 둔다 —
   * 화면이 "이어서 이름을 지어 주세요" 를 띄울 근거이고, 갓 올린 사람에겐 그 말이 어색하다.
   */
  resumedDraft: boolean;
  /** 부화가 끝났는가(`ALIVE`). */
  ready: boolean;
  failed: boolean;
  /** 부화 중 지금 하는 일 한 줄(서버 문구). */
  step: string | null;
  /**
   * 끝난 단계 수와 전체 단계 수 — **둘 다 서버가 준 숫자 그대로**다.
   * 예전에는 "라벨이 바뀐 횟수" 를 셌는데, 그건 전용 API 가 없던 시절의 임시방편이었다.
   */
  progress: number;
  total: number;
  /** 남은 시간(초). 서버가 모르면 0. */
  etaSeconds: number;
  /** 실패했을 때 서버가 보낸 말. */
  message: string | null;
  /**
   * 기본 8종 중 **서버가 그림을 안 준 것**. 있으면 안 되는 상태다(→ `BASIC_KEYS` 주석).
   * ⚠️ 지금 개발 중에는 가짜 생성이 6종만 만들어서 `sick`·`call` 이 늘 여기 담긴다 —
   *   **정상적인 경고**다. 진짜 생성으로 바꾸면 비어야 한다. 이 경고를 지우지 말 것.
   */
  missingBasics: string[];
  /** 서버가 준 내 아이 그림(카탈로그 key). 아직 없으면 null → 화면은 여울로 폴백한다. */
  img: (key: string) => string | null;
  /** 파일 하나를 올린다 — 성공하면 **그 자리에서 초안까지** 잡는다. */
  upload: (file: File) => Promise<void>;
  /** 이름·성격을 보낸다. 이 순간부터 격자 생성이 돈다. */
  setChar: (input: CharacterInput) => Promise<void>;
  /**
   * 지금 도는 돌보기. 있으면 **버튼을 전부 잠근다** — 계약 10절 "누르면 잠그고 기다린다".
   */
  careing: CareAction | null;
  /**
   * 돌보기 한 번. 응답으로 온 상태가 곧 새 화면이다.
   *
   * ★ 되감기를 만들지 않는다(계약 10절 · 백엔드도 같은 의견). 먼저 올려 두고 틀리면 되돌리는
   *   방식은 되돌리는 순간이 사람 눈에 '깎였다' 로 읽힌다. 그래서 **응답을 받고 나서** 그린다.
   * @returns 성공이면 null, 거절이면 화면에 띄울 한 줄.
   */
  doCare: (action: CareAction) => Promise<string | null>;
  /** 두고 간 아이가 있는지 서버에 물어본다. 로그인한 뒤에 한 번만 부른다. */
  resume: () => Promise<'draft' | 'hatching' | 'alive' | null>;
  reset: () => void;
}

const EMPTY: Live = {
  previewUrl: null, imageKey: null, petId: null, pet: null, busy: false, error: null,
  draftOnly: false, resumedDraft: false, careing: null, ready: false, failed: false, step: null,
  progress: 0, total: 0, etaSeconds: 0, message: null, missingBasics: [],
  img: () => null,
  upload: async () => {}, setChar: async () => {}, doCare: async () => null,
  resume: async () => null, reset: () => {},
};

export function useHatchState(): Live {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [imageKey, setImageKey] = useState<string | null>(null);
  const [petId, setPetId] = useState<number | null>(null);
  const [pet, setPet] = useState<PetDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const objectUrl = useRef<string | null>(null);
  /** 이름을 보냈는가. 이게 켜져야 굽기가 도는 것이므로 그때부터 진행을 묻는다. */
  const [charSet, setCharSet] = useState(false);
  const [hatch, setHatch] = useState<HatchProgress | null>(null);
  const [resumedDraft, setResumedDraft] = useState(false);
  const [careing, setCareing] = useState<CareAction | null>(null);

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
      const key = await uploadImage(file, 'zzal');
      setImageKey(key);
      // ★ 여기서 곧바로 초안을 잡는다. 이 한 줄이 이름 짓는 시간(약 74초)을 버는 자리다.
      const { petId: id } = await draftPet(key);
      setPetId(id);
    } catch (e) {
      // ★ 실패하면 미리보기도 함께 지운다(상훈님 판정 19). 그림만 크게 남아 있으면
      //   작은 오류 한 줄보다 그림이 먼저 읽혀 성공한 줄 안다.
      setImageKey(null);
      setPetId(null);
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = null;
      setPreviewUrl(null);
      setError(e instanceof Error ? e.message : '그림을 올리지 못했어요');
    } finally {
      setBusy(false);
    }
  }, []);

  /**
   * 이름·성격을 보낸다 = 격자 생성 시작.
   *
   * ★ 계약 4절 — **그림 생성에 들어가는 것은 `note` 뿐**이다. `personality`·`world` 는
   *   대사 톤에만 쓰이고, 격자 프롬프트의 정체성 문단은 올린 그림에서 뽑는다.
   *   그래서 말투·장르 칩은 여기 안 싣는다(보낼 자리가 없고, 실어도 그림엔 영향이 없다).
   */
  const setChar = useCallback(async (input: CharacterInput) => {
    if (!petId || charSet) return;
    setBusy(true);
    setError(null);
    try {
      const created = await setCharacter(petId, input);
      setCharSet(true);
      setHatch({
        phase: created.phase, label: null, progress: 0, total: 0,
        estimatedSeconds: created.estimatedSeconds, message: null,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '부화를 시작하지 못했어요');
    } finally {
      setBusy(false);
    }
  }, [petId, charSet]);

  /**
   * 돌보기 한 번.
   *
   * ★ 거절이 나도 **되감지 않는다.** 화면이 값을 올린 적이 없으니 되돌릴 것도 없다.
   *   대신 지금 진짜 상태를 다시 받아 그리고(폰·PC 를 같이 켜 둔 경우가 여기다) 문구만 띄운다.
   * ★ 401 은 여기서 다루지 않는다 — 공통 클라이언트가 갱신을 시도하고, 그래도 안 되면
   *   로그인 창을 여는 것은 바깥의 일이다.
   */
  const doCare = useCallback(async (action: CareAction): Promise<string | null> => {
    if (!petId || careing) return null;
    setCareing(action);
    try {
      setPet(await care(petId, action));
      return null;
    } catch (e) {
      // 거절당했으면 서버가 지금 무엇을 참인지 알고 있다. 그걸 받아 다시 그린다.
      try { setPet(await getPet(petId)); } catch { /* 이것마저 실패하면 화면은 그대로 둔다 */ }
      return e instanceof Error ? e.message : '지금은 할 수 없어요';
    } finally {
      setCareing(null);
    }
  }, [petId, careing]);

  /**
   * 두고 간 아이 찾기. 로그인 직후 한 번 부른다.
   *
   * `DRAFT` = 그림만 올리고 이름을 안 지은 아이 → 캐릭터 칸부터 이어서.
   * `HATCHING` = 이름까지 지어 굽는 중인 아이 → 알 화면으로. 이걸 안 받아 주면 다시 올리려다
   *   `ZZAL_PET_ALREADY_HATCHING` 에 막혀 갈 데가 없어진다.
   */
  const resume = useCallback(async (): Promise<'draft' | 'hatching' | 'alive' | null> => {
    try {
      const mine = await listPets();
      const draft = mine.find((p) => p.phase === 'DRAFT');
      if (draft) { setPetId(draft.petId); setResumedDraft(true); return 'draft'; }
      const baking = mine.find((p) => p.phase === 'HATCHING');
      if (baking) { setPetId(baking.petId); setCharSet(true); return 'hatching'; }
      // 이미 함께 살고 있는 아이. 온보딩을 다시 태우지 않고 방으로 보낸다.
      // ★ 이걸 안 하면 다시 들어올 때마다 머리줄이 목 값(12일째·친밀도 40%)으로 돌아간다.
      const living = mine.find((p) => p.phase === 'ALIVE');
      if (living) {
        setPetId(living.petId); setCharSet(true); setPet(living);
        setHatch({ phase: 'ALIVE', label: null, progress: 0, total: 0, estimatedSeconds: 0, message: null });
        return 'alive';
      }
    } catch {
      // 못 물어본 것으로 화면을 막지 않는다. 처음부터 시작하면 된다.
    }
    return null;
  }, []);

  // ── 부화 지켜보기 ──────────────────────────────────────────────
  // 무거운 `getPet` 대신 **전용 API** 를 3초마다. 끝나면 스스로 멈춘다.
  const phase = hatch?.phase ?? null;
  const watching = !!petId && charSet && phase !== 'ALIVE' && phase !== 'FAILED' && phase !== 'DEAD';
  useEffect(() => {
    if (!watching || !petId) return;
    let alive = true;
    const look = async () => {
      try {
        const next = await getHatchProgress(petId);
        if (alive) setHatch(next);
      } catch {
        // 한 번 못 읽은 것으로 화면을 깨뜨리지 않는다. 다음 차례에 다시 묻는다.
      }
    };
    look();
    const t = setInterval(look, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [watching, petId]);

  // 다 됐을 때 **한 번만** 무거운 쪽을 부른다 — 그림 주소(`motions[].basicImageKey`)가 거기 있다.
  useEffect(() => {
    if (phase !== 'ALIVE' || !petId || pet) return;
    let alive = true;
    void getPet(petId).then((d) => { if (alive) setPet(d); }).catch(() => {});
    return () => { alive = false; };
  }, [phase, petId, pet]);

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
    setCharSet(false); setHatch(null); setResumedDraft(false);
  }, []);

  return {
    previewUrl, imageKey, petId, pet, busy, error,
    // 초안은 아직 부화가 아니다 — 이름을 받아야 굽기가 시작된다.
    draftOnly: !!petId && !charSet,
    resumedDraft: resumedDraft && !charSet,
    ready: phase === 'ALIVE',
    failed: phase === 'FAILED' || phase === 'DEAD',
    step: hatch?.label ?? null,
    progress: hatch?.progress ?? 0,
    total: hatch?.total ?? 0,
    etaSeconds: hatch?.estimatedSeconds ?? 0,
    message: hatch?.message ?? null,
    missingBasics: pet?.phase === 'ALIVE'
      ? BASIC_KEYS.filter((k) => !pet.motions?.some((m) => m.key === k && m.basicImageKey))
      : [],
    careing,
    img, upload, setChar, doCare, resume, reset,
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
