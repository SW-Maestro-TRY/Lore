// 이 화면이 "지금 누구의 어떤 아이를 보고 있는가" 를 정하는 자리.
//
// 화면과 서버 사이에 이 한 겹을 둔 이유 — 다마고치 화면이 답해야 하는 질문은 셋인데
// 셋의 출처가 전부 다르다. 한 곳에 모아 두지 않으면 스킨마다 같은 판단을 다시 짓게 된다.
//
//   1) 로그인했는가          → common/fe/auth (쿠키라 자바스크립트가 못 읽는다. 물어봐야 안다)
//   2) 내 아이가 있는가      → GET /me/pets
//   3) 그 아이는 지금 어떤가 → usePet 이 알아서 폴링한다
//
// ★ 가르는 기준은 "로그인했는가" 가 아니라 **"내 아이가 있는가"** 다(2026-08-31 결정).
//   로그인했는데 펫이 없는 사람과 비로그인인 사람은 화면에서 같은 것을 본다 — 올리는 자리.
//   다른 것은 **올리기를 눌렀을 때**뿐이고, 그때 비로그인이면 로그인 모달이 뜬다.
//
// ★ 여기서 낙관적 업데이트를 하지 않는다. 수치 판정은 전부 서버 규칙이라, 미리 그리면
//   응답이 왔을 때 값이 튄다(usePet 머리말의 규칙 셋과 같은 약속).
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { track } from '@common/analytics';
import { useAuth } from '@common/auth/useAuth';
import { ApiError } from '../lib/api';
import { createPet, listPets } from '../lib/pet';
import { uploadImage } from '../lib/upload';
import { usePet } from '../lib/usePet';
import type { CreateInput, TamagotchiServer } from './useTamagotchi';

/**
 * 알이 금 가는 시점을 정하는 데만 쓰는 값(초).
 *
 * ★ 진행률이 아니다. 서버는 "언제 끝난다" 를 약속하지 않고(대개 훨씬 빨리 끝난다),
 *   생성 응답의 estimatedSeconds 도 진행바의 분모로 쓰면 안 된다고 못 박혀 있다.
 *   여기서는 알 그림을 언제 갈아 끼울지만 정하므로 어긋나도 화면이 거짓말을 하지 않는다.
 *   새로고침해서 들어온 사람은 생성 응답을 못 봤으므로 이 기본값을 쓴다.
 */
const HATCH_VISUAL_SPAN_SEC = 600;

/** 기록에 남길 실패 코드. **코드만** 남긴다 — 문구는 서버가 바꾸면 통계가 끊긴다. */
function errorCodeOf(e: unknown): string {
  if (e instanceof ApiError) return e.code ?? `http_${e.status}`;
  return 'network_error';
}

function messageOf(e: unknown): string {
  if (e instanceof ApiError && e.message) return e.message;
  // 업로드(S3)는 우리 봉투를 안 거치므로 ApiError 가 아니다. 그쪽 문구는 이미 한국어다.
  if (e instanceof Error && e.message) return e.message;
  return '요청을 처리하지 못했습니다';
}

/**
 * 화면에 한 번 띄우고 사라지는 알림.
 *
 * 문자열이 아니라 객체인 이유 — 같은 문구가 연달아 뜰 때(밥을 두 번 잘못 누르면 같은 말이
 * 두 번 온다) 문자열이면 값이 안 바뀌어 두 번째가 안 뜬다. 매번 새 객체라 반드시 뜬다.
 */
export interface Notice {
  message: string;
}

export interface ZzalSession {
  /** 화면이 useTamagotchi 에 그대로 넘기는 손잡이. 시연 모드에서는 null. */
  server: TamagotchiServer | null;
  /** 로그인 모달을 열어 둘 것인가. */
  authOpen: boolean;
  closeAuth: () => void;
}

export interface ZzalSessionOptions {
  /**
   * 서버를 아예 안 붙인다(디자인 확인용). 켜면 예전처럼 브라우저 안에서만 도는 시연이 된다.
   * 스킨의 startWithChar 가 이 값을 넘긴다 — 그 모드는 애초에 없는 아이를 그리는 자리라
   * 서버에 물어볼 것이 없다.
   */
  demo?: boolean;
}

export function useZzalSession({ demo = false }: ZzalSessionOptions = {}): ZzalSession {
  const auth = useAuth();
  const authenticated = auth.isAuthenticated;

  const [petId, setPetId] = useState<number | null>(null);
  /** 내 아이가 있는지 아직 못 물어봤다. 이걸 안 보면 로그인한 사람에게도 올리는 자리가 스친다. */
  const [listed, setListed] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [estimateSec, setEstimateSec] = useState<number>(HATCH_VISUAL_SPAN_SEC);

  const pet = usePet(demo ? null : petId);

  // ── 내 아이 찾기 ────────────────────────────────────────────────────────
  //
  // 실패한 알(FAILED)과 보낸 아이(DEAD)는 **자리를 먹지 않는다**(PetController 주석).
  // 그래서 목록에서 골라낼 때부터 빼 둔다 — 그러면 "다시 시도" 가 특별한 길이 아니라
  // 그냥 올리는 자리로 돌아가는 일이 된다.
  useEffect(() => {
    if (demo || !authenticated) {
      setPetId(null);
      setListed(false);
      return;
    }

    let alive = true;
    const controller = new AbortController();

    listPets(controller.signal)
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
      .finally(() => {
        // 실패해도 세운다. 못 물어본 채로 영영 기다리면 화면이 빈 채로 멈춘다.
        if (alive) setListed(true);
      });

    return () => {
      alive = false;
      controller.abort();
    };
  }, [demo, authenticated]);

  // ── 부화 결과 기록 ──────────────────────────────────────────────────────
  //
  // 한 번만 남기려고 ref 로 막는다. 폴링이라 같은 상태가 계속 들어온다.
  const hatchLogged = useRef(false);
  useEffect(() => {
    hatchLogged.current = false;
  }, [petId]);

  const phase = pet.pet?.phase ?? null;
  const deathReason = pet.pet?.deathReason ?? null;
  useEffect(() => {
    if (!phase || hatchLogged.current) return;
    if (phase === 'ALIVE') {
      hatchLogged.current = true;
      track('zzal_hatch_succeeded');
    } else if (phase === 'FAILED') {
      hatchLogged.current = true;
      track('zzal_hatch_failed', { reason: deathReason ?? 'unknown' });
    }
  }, [phase, deathReason]);

  // 보낸 아이는 화면에서 내린다. 실패한 알(FAILED)은 "이 그림은 좀 어렵네요" 를 한 번
  // 보여줘야 하므로 여기서 내리지 않고, 사용자가 닫을 때(dismissFailed) 내린다.
  useEffect(() => {
    if (phase === 'DEAD') setPetId(null);
  }, [phase]);

  // ── 이탈 기록 ───────────────────────────────────────────────────────────
  //
  // ★ 여기가 이 서비스에서 가장 알고 싶은 두 지점이다. 성공한 사람은 어차피 뒤에서 다시
  //   보이지만, 올리는 자리에서 아무것도 안 하고 나간 사람과 부화를 기다리다 나간 사람은
  //   이 한 줄이 없으면 흔적조차 안 남는다.
  const uploadSeen = useRef(false);
  const uploadTouched = useRef(false);
  const createRequested = useRef(false);
  const hatching = useRef(false);
  hatching.current = phase === 'HATCHING';

  useEffect(() => {
    if (demo) return;
    let reported = false;
    const leave = () => {
      if (reported) return;
      reported = true;
      if (hatching.current) track('zzal_hatch_abandoned');
      if (uploadSeen.current && !uploadTouched.current && !createRequested.current) {
        track('zzal_upload_abandoned');
      }
    };
    // 폰에서는 탭을 닫아도 unload 가 안 오는 경우가 있어 pagehide 를 함께 듣는다.
    window.addEventListener('pagehide', leave);
    return () => {
      window.removeEventListener('pagehide', leave);
      leave();
    };
  }, [demo]);

  // ── 올리기 ──────────────────────────────────────────────────────────────

  /** 올리기를 누르다 로그인 벽을 만난 사람인가. 로그인한 뒤 한마디 건네려고 든다. */
  const blockedByLogin = useRef(false);

  useEffect(() => {
    if (!authenticated || !blockedByLogin.current) return;
    blockedByLogin.current = false;
    // ★ 자동으로 이어서 만들지 않는다. 로그인 창이 닫히자마자 아이가 만들어져 버리면
    //   "내가 언제 눌렀지" 가 된다. 입력한 것은 그대로 남아 있으니 한 번만 더 누르면 된다.
    setNotice({ message: '이제 데려올 수 있어요. 한 번만 더 눌러 주세요' });
  }, [authenticated]);

  const create = useCallback(
    async ({ name, note, file }: CreateInput): Promise<boolean> => {
      if (!authenticated) {
        // ★ 여기서 막는 것이 이 화면의 유일한 로그인 벽이다. 보고 만지는 것은 전부 열어 두고,
        //   "내 아이를 만든다" 는 순간에만 계정을 묻는다.
        track('zzal_upload_login_required');
        blockedByLogin.current = true;
        setAuthOpen(true);
        return false;
      }
      if (!file) {
        setNotice({ message: '그림을 한 장 올려 주세요' });
        return false;
      }

      createRequested.current = true;
      setCreating(true);
      track('zzal_pet_create_requested', { has_note: note.trim().length > 0 });

      try {
        // 파일 바이트는 우리 서버를 안 지나간다(S3 직행). key 만 받아 넘긴다.
        const imageKey = await uploadImage(file, 'zzal');
        const created = await createPet({
          name: name.trim(),
          note: note.trim() || undefined,
          imageKey,
        });
        setEstimateSec(created.estimatedSeconds > 0 ? created.estimatedSeconds : HATCH_VISUAL_SPAN_SEC);
        setPetId(created.petId);
        track('zzal_pet_create_succeeded');
        return true;
      } catch (e) {
        // ⚠️ 이미지 내용도 이름도 남기지 않는다. 코드만.
        track('zzal_pet_create_failed', { code: errorCodeOf(e) });
        setNotice({ message: messageOf(e) });
        return false;
      } finally {
        setCreating(false);
      }
    },
    [authenticated],
  );

  const dismissFailed = useCallback(() => {
    // 실패한 알은 자리를 안 먹으므로 그냥 내려놓으면 곧바로 다시 올릴 수 있다.
    track('zzal_hatch_retry');
    setPetId(null);
  }, []);

  const markUploadOpened = useCallback(() => {
    if (uploadSeen.current) return;
    uploadSeen.current = true;
    track('zzal_upload_opened');
  }, []);

  const markImagePicked = useCallback(() => {
    uploadTouched.current = true;
    track('zzal_image_picked');
  }, []);

  const nameLogged = useRef(false);
  const markNameEntered = useCallback(() => {
    uploadTouched.current = true;
    if (nameLogged.current) return;
    nameLogged.current = true;
    // ⚠️ 이름 자체는 절대 싣지 않는다. "쳤다" 는 사실만.
    track('zzal_name_entered');
  }, []);

  const clearNotice = useCallback(() => setNotice(null), []);
  const closeAuth = useCallback(() => setAuthOpen(false), []);

  const server = useMemo<TamagotchiServer | null>(() => {
    if (demo) return null;
    return {
      pet: pet.pet,
      // ★ "아직 모른다" 를 하나로 묶는다. 셋 중 하나라도 진행 중이면 화면은 아직
      //   "펫이 없다" 고 단정하면 안 된다 — 그러면 이미 키우는 사람에게 올리는 자리가 스친다.
      loading: auth.isLoading || (authenticated && !listed) || pet.loading,
      acting: pet.acting,
      creating,
      error: pet.error,
      notice,
      clearNotice,
      trainLeft: pet.trainLeft,
      sleepLeft: pet.sleepLeft,
      learned: pet.learned,
      clearLearned: pet.clearLearned,
      hatchSpanSeconds: estimateSec,
      care: pet.care,
      train: pet.train,
      sleep: pet.sleep,
      wake: pet.wake,
      tutorialDone: pet.tutorialDone,
      create,
      dismissFailed,
      markUploadOpened,
      markImagePicked,
      markNameEntered,
    };
  }, [
    demo, pet.pet, pet.loading, pet.acting, pet.error, pet.trainLeft, pet.sleepLeft,
    auth.isLoading, authenticated, listed,
    pet.learned, pet.clearLearned, pet.care, pet.train, pet.sleep, pet.wake, pet.tutorialDone,
    creating, notice, clearNotice, estimateSec, create, dismissFailed,
    markUploadOpened, markImagePicked, markNameEntered,
  ]);

  return { server, authOpen, closeAuth };
}
