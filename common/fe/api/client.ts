// 백엔드 호출의 밑바닥. 봉투 벗기기 · 쿠키 인증 · 401 자동 갱신 세 가지만 한다.
// 도메인 지식(펫·업로드)은 여기 없다 — 그건 pet.ts / upload.ts 가 안다.
//
// ★ 원래 zzal/fe/lib/api.ts 였고 2026-09-03 에 여기로 올라왔다. 봉투 모양도 refresh 회전
//   규칙도 common/be 의 약속이지 zzal 것이 아니고, 로그인 모달이 common 으로 올라오면서
//   common → zzal 역참조가 생길 판이었다. zzal/fe/lib/api.ts 는 이 파일을 다시 내보내는
//   껍데기로 남겨 두어 pet.ts·upload.ts·usePet.ts 는 한 줄도 안 바뀐다.
//
// 서버 응답은 전부 같은 봉투다(common/be ApiResponse):
//   성공 { success: true,  data: {...}, message: null, error: null }
//   실패 { success: false, data: null,  message: "...", error: { code, message } }
// 화면은 문구가 아니라 error.code 로 분기해야 한다. 문구는 바뀌어도 코드는 안 바뀐다.

/** 봉투. 서버가 실제로 내려주는 그대로. */
interface Envelope<T> {
  success: boolean;
  data: T | null;
  message: string | null;
  error: { code: string | null; message: string | null } | null;
}

/**
 * API 주소 앞단.
 *
 * 기본값이 빈 문자열인 이유 — 운영에서는 CloudFront 가 같은 도메인 아래 `/api/*` 만
 * 백엔드로 보내므로 상대경로여야 CORS 가 아예 안 생긴다(next.config.mjs 주석과 같은 약속).
 * 로컬에서 백엔드를 8080 에 따로 띄울 때만 NEXT_PUBLIC_API_BASE 를 채운다.
 */
export const API_BASE: string = process.env.NEXT_PUBLIC_API_BASE ?? '';

/** 인증 API 경로. 401 을 만나도 갱신을 시도하면 안 되는 구역이다(아래 shouldTryRefresh 참고). */
const AUTH_PREFIX = '/api/v1/auth';
const REFRESH_PATH = `${AUTH_PREFIX}/refresh`;

/**
 * 서버가 쓰는 에러 코드(common/be ErrorCode). 오타를 잡으려고 적어 두지만
 * `(string & {})` 를 섞어 두어 서버에 새 코드가 생겨도 타입이 막지 않는다 —
 * 프론트가 못 따라갔다고 화면이 죽는 쪽이 더 나쁘다.
 */
export type ApiErrorCode =
  | 'INVALID_INPUT'
  | 'INTERNAL_ERROR'
  | 'EMAIL_ALREADY_EXISTS'
  | 'REQUIRED_AGREEMENT_MISSING'
  | 'LOGIN_FAILED'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'INVALID_REFRESH_TOKEN'
  | 'USER_NOT_FOUND'
  | 'INVALID_UPLOAD_KEY'
  | 'UPLOAD_KEY_ALREADY_USED'
  | 'ZZAL_PET_NOT_FOUND'
  | 'ZZAL_PET_ALREADY_HATCHING'
  | 'ZZAL_PET_LIMIT_REACHED'
  | 'ZZAL_PET_NOT_ALIVE'
  | 'ZZAL_PET_SLEEPING'
  | 'ZZAL_PET_NOT_SLEEPING'
  | 'ZZAL_PET_STILL_SLEEPING'
  | 'ZZAL_CARE_NOT_NEEDED'
  | 'ZZAL_NO_FOOD'
  | 'ZZAL_TRAIN_IN_PROGRESS'
  | 'ZZAL_TRAIN_ENOUGH'
  | 'ZZAL_TRAIN_NOT_ENOUGH'
  | 'ZZAL_ALL_UNLOCKED'
  | 'ZZAL_MOTION_NOT_READY'
  | (string & {});

/**
 * 호출 실패. status 는 참고용이고 분기는 code 로 한다.
 *
 * code 가 null 일 수 있다 — 스프링 시큐리티가 인증 없이 들어온 요청을 막을 때는
 * 우리 봉투를 거치지 않고 컨테이너 기본 401 을 내보내기 때문이다
 * (WebSecurityConfig 의 authenticationEntryPoint 가 res.sendError 를 쓴다).
 * 그래서 "코드가 없는 401" 이 실제로 존재한다. isUnauthorized 로 판정할 것.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode | null;

  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }

  /** 로그인이 필요한 상태인가. 코드 없는 401 까지 함께 잡는다. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }
}

/**
 * 진행 중인 갱신 요청. 동시에 여러 호출이 401 을 받아도 refresh 는 한 번만 나가야 한다.
 *
 * ★ 여러 번 나가면 안 되는 이유가 단순한 낭비가 아니다 — 서버는 refresh 를 회전시키고
 *   이미 쓴 refresh 가 다시 들어오면 탈취로 보고 그 사용자의 토큰을 전부 폐기한다
 *   (AuthController 주석). 즉 동시 갱신 2발이 곧 전 기기 강제 로그아웃이 된다.
 */
let refreshInFlight: Promise<boolean> | null = null;

/**
 * access 를 다시 받는다. 성공 여부만 돌려주고 예외를 밖으로 내보내지 않는다 —
 * 갱신 실패는 "원래 요청의 401" 로 사용자에게 전해져야 맥락이 맞기 때문이다.
 */
function refreshOnce(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  const p = fetch(`${API_BASE}${REFRESH_PATH}`, {
    method: 'POST',
    credentials: 'include',
  })
    .then((res) => res.ok)
    .catch(() => false)
    .finally(() => {
      // 자기 자신일 때만 비운다. 늦게 끝난 옛 요청이 새 요청을 지우는 걸 막는다.
      if (refreshInFlight === p) refreshInFlight = null;
    });

  refreshInFlight = p;
  return p;
}

/**
 * 이 401 에 갱신을 시도해도 되는가.
 *
 * ★ 인증 API 는 제외한다. 로그인 실패도 401(LOGIN_FAILED)이라, 거르지 않으면
 *   비밀번호를 틀릴 때마다 refresh 가 날아가고 로그인 요청이 한 번 더 나간다.
 *   refresh 자신이 401 인 경우도 여기서 함께 막혀 무한 재시도가 생기지 않는다.
 */
function shouldTryRefresh(path: string): boolean {
  return !path.startsWith(AUTH_PREFIX);
}

/** 본문을 한 번만 읽어 봉투로 해석한다. 비었거나 JSON 이 아니면 null. */
async function readEnvelope<T>(res: Response): Promise<Envelope<T> | null> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as Envelope<T>;
  } catch {
    // 시큐리티 기본 401·502 처럼 우리 봉투를 안 거친 응답이 여기로 온다.
    return null;
  }
}

function toError(res: Response, envelope: Envelope<unknown> | null): ApiError {
  const code = envelope?.error?.code ?? null;
  const message =
    envelope?.error?.message ??
    envelope?.message ??
    (res.status === 401 ? '로그인이 필요합니다' : '요청을 처리하지 못했습니다');
  return new ApiError(res.status, code, message);
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  /** 그대로 JSON 으로 직렬화해 보낸다. 없으면 본문 없이 보낸다. */
  body?: unknown;
  /** 화면이 떠날 때 호출을 끊기 위한 것. 폴링·언마운트에서 쓴다. */
  signal?: AbortSignal;
}

/**
 * 공통 호출. 성공하면 봉투를 벗긴 `data` 를, 실패하면 ApiError 를 던진다.
 *
 * 인증은 HttpOnly 쿠키라 자바스크립트가 토큰을 읽을 수도 헤더에 실을 수도 없다.
 * 그래서 `credentials: 'include'` 가 빠지면 전 API 가 조용히 401 이 된다 — 기본값이
 * same-origin 이라 로컬에서 8080 을 따로 띄우는 순간 쿠키가 안 붙는다.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal } = options;

  const send = (): Promise<Response> =>
    fetch(`${API_BASE}${path}`, {
      method,
      credentials: 'include',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });

  let res = await send();

  if (res.status === 401 && shouldTryRefresh(path)) {
    const renewed = await refreshOnce();
    // 갱신에 실패하면 재시도하지 않는다. 원래 401 을 그대로 사용자에게 전한다.
    if (renewed) res = await send();
  }

  const envelope = await readEnvelope<T>(res);

  if (!res.ok) throw toError(res, envelope);

  // HTTP 는 200 인데 봉투가 실패인 경우. 지금 서버에는 없지만, 생겼을 때
  // 성공으로 흘러 들어가는 쪽이 훨씬 찾기 어렵다.
  if (envelope && !envelope.success) throw toError(res, envelope);

  // 본문 없는 성공(ApiResponse.ok())은 data 가 null 이다. 그 API 들은 T = void 로 부른다.
  return (envelope?.data ?? null) as T;
}
