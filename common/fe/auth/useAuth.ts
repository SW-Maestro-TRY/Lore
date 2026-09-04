"use client";

// 로그인 상태를 앱 전체가 함께 보는 자리. 헤더도, 각 도메인 화면도 여기만 본다.
//
// ★ Context.Provider 가 아니라 모듈 스토어(useSyncExternalStore)인 이유
//   헤더가 두 군데에서 각자 렌더된다 — 랜딩은 LandingPage 안에서, 도메인 탭은
//   app/(domains)/layout.tsx 에서. 둘의 공통 조상은 앱 루트 하나뿐이라 Provider 를
//   쓰려면 세 도메인이 공유하는 루트 레이아웃을 고쳐야 하고, 하은·병연 화면도 각자
//   Provider 안에 들어와 있는지 신경 써야 한다. 로그인한 사람은 브라우저당 하나뿐이라
//   애초에 트리마다 다른 값을 가질 일이 없으므로, 트리 밖 스토어 하나가 더 정직하다.
//   덕분에 이 훅은 어디서 불러도 켜지고, 공통 영역 밖 파일을 한 줄도 안 건드린다.
//
// ★ 토큰을 안 들고 있는 것도 정상이다. 인증은 HttpOnly 쿠키라 자바스크립트가 볼 수 없고,
//   "로그인했나" 는 저장된 값이 아니라 GET /api/v1/users/me 가 성공하는지로 판정한다.

import { useSyncExternalStore } from "react";
import { ApiError } from "../api/client";
import {
  getMe,
  login as loginApi,
  logout as logoutApi,
  signUp as signUpApi,
  type LoginInput,
  type Me,
  type SignUpInput,
} from "./api";

/**
 * 지금 로그인 상태를 아는가.
 *
 * anonymous 와 unknown 을 굳이 나눈 이유 — 401(정말 비로그인)과 서버가 죽거나 네트워크가
 * 끊긴 것은 다른 사실이다. 둘을 뭉개면 멀쩡히 로그인한 사람에게 "로그인하세요" 를
 * 들이밀게 되고, 그건 사용자 입장에서 로그아웃당한 것처럼 보인다.
 * 화면이 둘을 같게 취급하기로 정할 수는 있지만, 그 판단은 화면이 하는 것이지
 * 여기서 미리 지워 버릴 정보가 아니다.
 */
export type AuthStatus = "loading" | "authenticated" | "anonymous" | "unknown";

export interface AuthState {
  status: AuthStatus;
  /** authenticated 일 때만 채워진다. */
  user: Me | null;
}

/**
 * 서버 렌더 · 하이드레이션 시점의 스냅샷.
 *
 * useSyncExternalStore 는 매번 같은 참조가 돌아와야 무한 렌더에 빠지지 않으므로
 * 새 객체를 만들지 말고 이 상수를 그대로 돌려준다.
 */
const INITIAL: AuthState = { status: "loading", user: null };

let state: AuthState = INITIAL;
const listeners = new Set<() => void>();

function setState(next: AuthState): void {
  state = next;
  listeners.forEach((notify) => notify());
}

/** 진행 중인 /users/me 조회. 화면 여러 개가 동시에 붙어도 요청은 한 번만 나간다. */
let inFlight: Promise<void> | null = null;
/** 한 번이라도 확인했는가. 비로그인으로 확정된 뒤 매번 다시 묻지 않기 위한 표시. */
let settled = false;

/**
 * 서버에 지금 누구인지 묻는다. 밖으로 예외를 내보내지 않는다 —
 * 비로그인은 오류가 아니라 정상 상태라, 화면에 에러로 뜨면 안 된다.
 */
function load(force = false): Promise<void> {
  if (!force) {
    if (inFlight) return inFlight;
    if (settled) return Promise.resolve();
  }

  const p = getMe()
    .then((me) => {
      setState({ status: "authenticated", user: me });
    })
    .catch((e: unknown) => {
      const unauthorized = e instanceof ApiError && e.isUnauthorized;
      setState({ status: unauthorized ? "anonymous" : "unknown", user: null });
    })
    .finally(() => {
      settled = true;
      // 자기 자신일 때만 비운다. 늦게 끝난 옛 요청이 새 요청을 지우는 걸 막는다.
      if (inFlight === p) inFlight = null;
    });

  inFlight = p;
  return p;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  // 첫 구독이 붙는 순간에만 실제 호출이 나간다(load 가 스스로 막는다).
  // 훅을 안 쓰는 화면에서는 요청 자체가 없다.
  void load();
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): AuthState {
  return state;
}

function getServerSnapshot(): AuthState {
  return INITIAL;
}

/** 서버에 다시 물어 상태를 맞춘다. 보통은 로그인·가입·로그아웃이 알아서 부른다. */
export function reloadMe(): Promise<void> {
  return load(true);
}

/**
 * 로그인. 성공하면 곧바로 내 정보까지 채워 넣는다 —
 * 쿠키만 받고 상태를 안 갱신하면 헤더가 여전히 "로그인" 을 그린다.
 * 실패는 ApiError 로 그대로 던진다. 문구를 고르는 건 화면 몫이다.
 */
export async function signIn(input: LoginInput): Promise<void> {
  await loginApi(input);
  await load(true);
}

/** 가입. 서버가 가입과 동시에 쿠키를 내주므로 그 자리에서 로그인 상태가 된다. */
export async function signUpAndSignIn(input: SignUpInput): Promise<void> {
  await signUpApi(input);
  await load(true);
}

/**
 * 로그아웃. 서버 호출이 실패해도(만료·네트워크) 화면은 로그아웃으로 넘어간다 —
 * "로그아웃 눌렀는데 에러" 만큼 이상한 것이 없다(auth/api.ts 의 logout 주석과 같은 약속).
 */
export async function signOut(): Promise<void> {
  await logoutApi();
  settled = true;
  setState({ status: "anonymous", user: null });
}

export interface UseAuthResult extends AuthState {
  /** status === "authenticated" 의 줄임. 화면에서 가장 많이 쓰는 판정이다. */
  isAuthenticated: boolean;
  /** 아직 첫 확인이 안 끝났다. 이때는 로그인/비로그인 어느 쪽도 단정하면 안 된다. */
  isLoading: boolean;
  signIn: typeof signIn;
  signUp: typeof signUpAndSignIn;
  signOut: typeof signOut;
  reload: typeof reloadMe;
}

/** 어디서 불러도 되는 로그인 상태 훅. Provider 로 감쌀 필요가 없다. */
export function useAuth(): UseAuthResult {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  return {
    ...snapshot,
    isAuthenticated: snapshot.status === "authenticated",
    isLoading: snapshot.status === "loading",
    signIn,
    signUp: signUpAndSignIn,
    signOut,
    reload: reloadMe,
  };
}
