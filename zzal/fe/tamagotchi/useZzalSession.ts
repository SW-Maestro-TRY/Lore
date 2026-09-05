// 이 화면이 "지금 누구의 어떤 아이를 보고 있는가" 를 정하는 자리.
//
// 화면과 서버 사이에 이 한 겹을 둔 이유 — 다마고치 화면이 답해야 하는 질문은 셋인데 셋의 출처가 전부 다르다.
//   1) 로그인했는가          → common/fe/auth (쿠키라 자바스크립트가 못 읽는다. 물어봐야 안다)
//   2) 내 아이가 있는가      → GET /me/pets
//   3) 그 아이는 지금 어떤가 → usePet 이 경계 폴링한다
//
// ★ 가르는 기준은 "로그인했는가" 가 아니라 **"내 아이가 있는가"** 다(2026-08-31 결정).
//   로그인했는데 펫이 없는 사람과 비로그인인 사람은 화면에서 같은 것을 본다 — 올리는 자리.
//   다른 것은 **올리기를 눌렀을 때**뿐이고, 그때 비로그인이면 로그인 모달이 뜬다.
//
// ★ v2: 출처가 `PetSource` 다. 주소창에 `?mock=` 이 있으면 목 서버(로그인·업로드 없이 바로 아이)로 돈다.
//   시연 모드(demo 옵션)는 없어졌다 — 그 역할을 목이 맡는다(결정기록 C3·C7).
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { track } from '@common/analytics';
import { useAuth } from '@common/auth/useAuth';
import { ApiError } from '../lib/api';
import { httpPetSource, isMockRequested, resolvePetSource, type PetSource } from '../lib/petSource';
import { uploadImage } from '../lib/upload';
import { usePet } from '../lib/usePet';
import type { CreateInput, TamagotchiServer } from './useTamagotchi';

/**
 * 알이 금 가는 시점을 정하는 데만 쓰는 값(초). ★ 진행률이 아니다 — 서버는 "언제 끝난다" 를 약속하지 않는다.
 */
const HATCH_VISUAL_SPAN_SEC = 600;

function errorCodeOf(e: unknown): string {
  if (e instanceof ApiError) return e.code ?? `http_${e.status}`;
  return 'network_error';
}

function messageOf(e: unknown): string {
  if (e instanceof ApiError && e.message) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return '요청을 처리하지 못했습니다';
}

/** 같은 문구가 연달아 떠도 매번 새 객체라 반드시 뜬다. */
export interface Notice { message: string }

export interface ZzalSession {
  /** 화면이 useTamagotchi 에 그대로 넘기는 손잡이. 출처를 아직 못 정했으면 null. */
  server: TamagotchiServer | null;
  authOpen: boolean;
  closeAuth: () => void;
}

/** 주소창(`?mock=`)을 보고 출처를 고른다. 서버 렌더에서는 실서버. */
function useSource(): PetSource | null {
  const [source, setSource] = useState<PetSource | null>(() =>
    typeof window !== 'undefined' && isMockRequested(window.location.search) ? null : httpPetSource,
  );
  useEffect(() => {
    if (source) return;
    let alive = true;
    void resolvePetSource(window.location.search).then((s) => { if (alive) setSource(s); });
    return () => { alive = false; };
  }, [source]);
  return source;
}

export function useZzalSession(): ZzalSession {
  const source = useSource();
  const mock = source?.kind === 'mock';

  const auth = useAuth();
  // 목이면 로그인 벽이 없다 — 서버가 없으니 물어볼 계정도 없다.
  const authenticated = mock || auth.isAuthenticated;

  const [petId, setPetId] = useState<number | null>(null);
  const [listed, setListed] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [estimateSec, setEstimateSec] = useState<number>(HATCH_VISUAL_SPAN_SEC);

  // 시계 오프셋은 여기서 들지 않는다 — usePet 은 응답의 serverNow 로 경계까지 남은 시간을 재고,
  // 화면의 "지금" 은 useClock 하나가 낸다(오프셋을 두 벌 들면 어긋난다).
  const pet = usePet(source, petId);

  // ── 내 아이 찾기 ────────────────────────────────────────────────────────
  // 실패한 알(FAILED)과 보낸 아이(DEAD)는 자리를 먹지 않으므로 목록에서 골라낼 때부터 뺀다.
  useEffect(() => {
    if (!source || !authenticated) {
      setPetId(null);
      setListed(false);
      return;
    }
    let alive = true;
    const controller = new AbortController();
    source.listPets(controller.signal)
      .then((list) => {
        if (!alive) return;
        const mine = list.find((p) => p.phase === 'HATCHING' || p.phase === 'ALIVE');
        setPetId(mine ? mine.petId : null);
      })
      .catch((e: unknown) => {
        if (!alive) return;
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setNotice({ message: messageOf(e) });
      })
      .finally(() => { if (alive) setListed(true); });
    return () => { alive = false; controller.abort(); };
  }, [source, authenticated]);

  // ── 부화 결과 기록(한 번만) ─────────────────────────────────────────────
  const hatchLogged = useRef(false);
  useEffect(() => { hatchLogged.current = false; }, [petId]);
  const phase = pet.pet?.phase ?? null;
  const deathReason = pet.pet?.deathReason ?? null;
  useEffect(() => {
    if (!phase || hatchLogged.current) return;
    if (phase === 'ALIVE') { hatchLogged.current = true; track('zzal_hatch_succeeded'); }
    else if (phase === 'FAILED') { hatchLogged.current = true; track('zzal_hatch_failed', { reason: deathReason ?? 'unknown' }); }
  }, [phase, deathReason]);

  useEffect(() => { if (phase === 'DEAD') setPetId(null); }, [phase]);

  // ── 이탈 기록 ───────────────────────────────────────────────────────────
  const uploadSeen = useRef(false);
  const uploadTouched = useRef(false);
  const createRequested = useRef(false);
  const hatching = useRef(false);
  hatching.current = phase === 'HATCHING';
  useEffect(() => {
    if (mock) return;
    let reported = false;
    const leave = () => {
      if (reported) return;
      reported = true;
      if (hatching.current) track('zzal_hatch_abandoned');
      if (uploadSeen.current && !uploadTouched.current && !createRequested.current) track('zzal_upload_abandoned');
    };
    window.addEventListener('pagehide', leave);
    return () => { window.removeEventListener('pagehide', leave); leave(); };
  }, [mock]);

  // ── 올리기 ──────────────────────────────────────────────────────────────
  const blockedByLogin = useRef(false);
  useEffect(() => {
    if (!authenticated || !blockedByLogin.current) return;
    blockedByLogin.current = false;
    // 자동으로 이어서 만들지 않는다. 입력한 것은 그대로 남아 있으니 한 번만 더 누르면 된다.
    setNotice({ message: '이제 데려올 수 있어요. 한 번만 더 눌러 주세요' });
  }, [authenticated]);

  const create = useCallback(
    async ({ name, note, file }: CreateInput): Promise<boolean> => {
      if (!source) return false;
      if (!authenticated) {
        track('zzal_upload_login_required');
        blockedByLogin.current = true;
        setAuthOpen(true);
        return false;
      }
      if (!file) { setNotice({ message: '그림을 한 장 올려 주세요' }); return false; }
      createRequested.current = true;
      setCreating(true);
      track('zzal_pet_create_requested', { has_note: note.trim().length > 0 });
      try {
        // 목이면 S3 를 안 거친다(presign 은 실서버 몫). 키만 흉내 낸다.
        const imageKey = source.kind === 'mock' ? `images/zzal/mock/${Date.now()}` : await uploadImage(file, 'zzal');
        const created = await source.createPet({ name: name.trim(), note: note.trim() || undefined, imageKey });
        setEstimateSec(created.estimatedSeconds > 0 ? created.estimatedSeconds : HATCH_VISUAL_SPAN_SEC);
        setPetId(created.petId);
        track('zzal_pet_create_succeeded');
        return true;
      } catch (e) {
        track('zzal_pet_create_failed', { code: errorCodeOf(e) });
        setNotice({ message: messageOf(e) });
        return false;
      } finally {
        setCreating(false);
      }
    },
    [source, authenticated],
  );

  const dismissFailed = useCallback(() => { track('zzal_hatch_retry'); setPetId(null); }, []);
  const markUploadOpened = useCallback(() => {
    if (uploadSeen.current) return;
    uploadSeen.current = true;
    track('zzal_upload_opened');
  }, []);
  const markImagePicked = useCallback(() => { uploadTouched.current = true; track('zzal_image_picked'); }, []);
  const nameLogged = useRef(false);
  const markNameEntered = useCallback(() => {
    uploadTouched.current = true;
    if (nameLogged.current) return;
    nameLogged.current = true;
    track('zzal_name_entered');
  }, []);

  const clearNotice = useCallback(() => setNotice(null), []);
  const closeAuth = useCallback(() => setAuthOpen(false), []);

  const server = useMemo<TamagotchiServer | null>(() => {
    if (!source) return null;
    return {
      source,
      pet: pet.pet,
      // "아직 모른다" 를 하나로 묶는다. 셋 중 하나라도 진행 중이면 "펫이 없다" 고 단정하면 안 된다.
      loading: (!mock && auth.isLoading) || (authenticated && !listed) || pet.loading,
      acting: pet.acting,
      creating,
      error: pet.error,
      notice,
      clearNotice,
      justUnlocked: pet.justUnlocked,
      clearJustUnlocked: pet.clearJustUnlocked,
      chat: pet.chat,
      chatReply: pet.chatReply,
      clearChatReply: pet.clearChatReply,
      hatchSpanSeconds: estimateSec,
      reload: pet.reload,
      care: pet.care, sleep: pet.sleep, wake: pet.wake,
      setPersonality: pet.setPersonality, setBackground: pet.setBackground, share: pet.share,
      answerChat: pet.answerChat, markSeen: pet.markSeen,
      create, dismissFailed, markUploadOpened, markImagePicked, markNameEntered,
    };
  }, [
    source, mock, pet.pet, pet.loading, pet.acting, pet.error, pet.reload, pet.justUnlocked, pet.clearJustUnlocked, pet.chat, pet.chatReply,
    pet.clearChatReply, pet.care, pet.sleep, pet.wake, pet.setPersonality, pet.setBackground, pet.share, pet.answerChat, pet.markSeen,
    auth.isLoading, authenticated, listed, creating, notice, clearNotice, estimateSec, create, dismissFailed,
    markUploadOpened, markImagePicked, markNameEntered,
  ]);

  return { server, authOpen, closeAuth };
}
