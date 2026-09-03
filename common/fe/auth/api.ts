// 가입·로그인·로그아웃·내 정보. common/be 의 AuthController · UserController 와 짝이다.
//
// ★ 원래 zzal/fe/lib/auth.ts 였다. 로그인은 세 탭(zzal·trailer·webtoon)이 함께 쓰는 것이라
//   한 도메인 폴더 안에 있으면 다른 도메인이 zzal 을 import 하는 모양이 된다.
//   zzal/fe/lib/auth.ts 는 이 파일을 다시 내보내는 껍데기로 남는다.
//
// ★ 토큰을 다루는 코드가 여기 한 줄도 없는 것이 정상이다. 서버가 access·refresh 를
//   HttpOnly 쿠키로만 내보내므로 자바스크립트는 토큰을 읽지도 저장하지도 못한다.
//   "로그인했나?" 는 저장된 토큰이 아니라 getMe() 가 성공하는지로 판정한다.

import { ApiError, request } from '../api/client';

/** 동의 항목. common/be 의 AgreementType 과 같은 이름이어야 한다. */
export type AgreementType = 'TERMS' | 'PRIVACY' | 'MARKETING';

/**
 * 약관 동의. TERMS·PRIVACY 가 true 가 아니면 서버가 가입을 거부한다
 * (REQUIRED_AGREEMENT_MISSING). MARKETING 은 선택이지만 false 도 반드시 담아 보낸다 —
 * 안 물어본 것과 거부한 것은 기록상 다른 사실이다.
 */
export type Agreements = Partial<Record<AgreementType, boolean>>;

export interface SignUpInput {
  email: string;
  /** 8자 이상 72자 이하. 서버가 같은 기준으로 다시 검사한다. */
  password: string;
  agreements: Agreements;
}

export interface LoginInput {
  email: string;
  password: string;
}

/** 내 계정 정보. 토큰은 담기지 않는다. */
export interface Me {
  userId: number;
  email: string;
  /** USER 또는 ADMIN */
  role: string;
  /** ISO-8601 문자열(서버의 Instant). Date 로 바꿀지는 화면이 정한다. */
  createdAt: string;
}

/** 가입. 성공하면 그 자리에서 로그인 상태가 된다(쿠키 2종이 발급됨). */
export async function signUp(input: SignUpInput): Promise<void> {
  await request<void>('/api/v1/auth/signup', {
    method: 'POST',
    body: {
      email: input.email,
      password: input.password,
      // 서버는 Map<AgreementType, Boolean> 을 받는다 — 배열이 아니라 맵이다.
      agreements: input.agreements,
    },
  });
}

/**
 * 로그인.
 *
 * 실패는 401 LOGIN_FAILED 하나뿐이다 — 서버가 "없는 이메일" 과 "틀린 비밀번호" 를
 * 구분해 주지 않기 때문(가입 여부를 확인하는 수단이 되므로). 화면도 나눠 표시하면 안 된다.
 */
export async function login(input: LoginInput): Promise<void> {
  await request<void>('/api/v1/auth/login', { method: 'POST', body: input });
}

/**
 * 로그아웃. 이 기기의 refresh 만 폐기된다(다른 기기는 유지).
 *
 * 실패해도 삼킨다 — 이미 만료돼 401 이 나든 네트워크가 끊기든, 사용자 입장에서
 * "로그아웃 버튼을 눌렀는데 에러" 만큼 이상한 것이 없다. 화면은 그냥 로그아웃으로 넘어간다.
 */
export async function logout(): Promise<void> {
  try {
    await request<void>('/api/v1/auth/logout', { method: 'POST' });
  } catch {
    // 무시
  }
}

/** 내 정보. 로그인 여부를 확인하는 자리이기도 하다. */
export function getMe(signal?: AbortSignal): Promise<Me> {
  return request<Me>('/api/v1/users/me', { signal });
}

/**
 * 로그인 상태인지만 알고 싶을 때. 401 이면 false, 그 밖의 오류는 그대로 던진다.
 *
 * ★ 모든 실패를 false 로 뭉개지 않는 이유 — 서버가 죽었거나 네트워크가 끊긴 것을
 *   "로그아웃 상태" 로 그리면, 멀쩡히 로그인한 사람에게 가입 창을 들이밀게 된다.
 */
export async function isLoggedIn(signal?: AbortSignal): Promise<boolean> {
  try {
    await getMe(signal);
    return true;
  } catch (e) {
    if (e instanceof ApiError && e.isUnauthorized) return false;
    throw e;
  }
}
