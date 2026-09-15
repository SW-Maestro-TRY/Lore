// 부화가 막혔을 때의 안내 — **사유 → 문구 표를 쥐고 있는 한 곳**이다.
//
// 명세 E절(2026-09-14): *"부화가 막힌 **모든 경우** '지금은 새 아이를 만들 수 없어요, 잠시 후
// 다시' — 고장으로 안 읽히게"*, 다만 상한별로 문구는 살짝 다르게.
//
// ★★ 왜 한 파일인가 — 막기는 다섯 개로 시작하지만 늘어난다(백엔드가 코드를 더 늘릴 수 있다고
//    했다). 문구를 부르는 쪽마다 흩뿌려 두면 사유가 하나 늘 때 **빠뜨린 자리만 조용히 옛 문구를
//    내보낸다.** 표가 한 곳이면 늘어난 사유는 표에 한 줄이고, 표에 없는 사유는 공통 문구로 떨어진다.
//
// ★★ **문구의 주인은 화면이다.** 서버도 `error.message` 에 사람이 읽을 문장을 담아 주지만
//    (예: "오늘은 여기까지예요. 내일 다시 만나요") 그걸 그대로 찍으면 말투의 주인이 백엔드로
//    넘어간다. 이 서비스는 말투가 곧 제품이라 그러면 안 된다. 서버 문장은 **콘솔에만** 남긴다 —
//    화면 문구보다 구체적일 수 있어서, 안 남기면 나중에 왜 막혔는지 아무도 모른다.
//
// ★ 쓰지 않는 말 — "이미 여러 번 시도하셨어요" 처럼 사용자를 탓하는 말, "아이가 기다려요" 처럼
//   죄책감을 주는 말, 아이가 사용자를 원망하는 말. 막은 것은 우리 쪽 사정이다.
// ★ 없는 숫자를 지어내지 않는다. 서버는 "몇 초 뒤에 풀린다" 를 안 보낸다(봉투에 그 칸이 없다).
//   대신 **언제 풀리는지 화면이 아는 것만** 말한다 — 하루 상한 둘은 한국 시각 자정에 풀리므로
//   "내일 다시 만나요" 라고 쓸 수 있고, 나머지는 모르므로 "잠시 후에" 로만 쓴다.

import { ApiError } from './api';

/**
 * 막힌 사유. 명세 F절이 정한 다섯에 `unknown` 하나를 더했다.
 *
 * ★ `unknown` 이 이 설계의 핵심이다 — 서버가 새 코드를 내보내도 화면은 공통 문구로 떨어질 뿐
 *   깨지지 않는다. 모르는 것을 아는 척하지 않는 자리이기도 하다.
 */
export type BlockedReason =
  | 'pet_limit'
  | 'daily_cap'
  | 'service_cap'
  | 'ip_rate'
  | 'quota'
  | 'unknown';

export interface HatchBlocked {
  reason: BlockedReason;
  /** 굵게 나가는 한 줄. */
  title: string;
  /** 그 아래 한 줄. 왜 막혔고 언제 다시 되는지만 담는다. */
  body: string;
  /** 서버가 보낸 코드 그대로. 화면에 안 쓰고 기록·검사에만 쓴다. */
  code: string;
  /** 서버가 보낸 문장. **화면에 쓰지 않는다** — 콘솔에만 남긴다. */
  serverMessage: string | null;
}

/**
 * 막힘 코드의 머리말. 서버가 사유마다 코드를 따로 두기로 했다(2026-09-14 확정).
 * 상태는 전부 409 지만 **상태로는 갈리지 않는다** — 409 는 다른 이유로도 온다(이미 부화 중 등).
 */
const BLOCKED_PREFIX = 'ZZAL_HATCH_BLOCKED';

/**
 * 코드 → 사유. 여기 없는 `ZZAL_HATCH_BLOCKED_*` 는 `unknown` 이 된다.
 *
 * ★ `ZZAL_PET_LIMIT_REACHED` 도 함께 받는다. 막기 5개가 서버에 들어가기 전부터 있던 코드이고
 *   뜻이 `_PET_LIMIT` 과 같다(자리가 없다). 안 받으면 그동안은 이 안내가 한 번도 안 뜬다.
 */
const REASON_OF: Record<string, BlockedReason> = {
  [`${BLOCKED_PREFIX}_PET_LIMIT`]: 'pet_limit',
  [`${BLOCKED_PREFIX}_DAILY_CAP`]: 'daily_cap',
  [`${BLOCKED_PREFIX}_SERVICE_CAP`]: 'service_cap',
  [`${BLOCKED_PREFIX}_IP_RATE`]: 'ip_rate',
  [`${BLOCKED_PREFIX}_QUOTA`]: 'quota',
  ZZAL_PET_LIMIT_REACHED: 'pet_limit',
};

interface Copy { title: string; body: string }

/**
 * 사유 → 문구.
 *
 * ★ `pet_limit` 만 **기다려도 안 풀린다.** 여기에 "잠시 후 다시" 를 쓰면 거짓말이 된다 —
 *   기다리면 될 줄 알고 새로고침을 반복하게 만드는 문구다.
 *   (이 코드는 "동시에 한 아이" 와 "누적 3마리" 를 함께 뜻한다. 서버가 하나로 묶어 보내므로
 *    화면은 둘을 가를 수 없어서, 어느 쪽이어도 거짓이 아닌 말로만 적었다.)
 * ★ 하루 상한 둘(`daily_cap`·`service_cap`)은 **한국 시각 자정에 풀린다**(백엔드 확인).
 *   그래서 "내일" 이라고 말할 수 있다.
 * ★ 나머지(`ip_rate`·`quota`)는 언제 풀리는지 화면이 모른다 → 공통 문구와 같은 결.
 */
const COPY: Record<BlockedReason, Copy> = {
  pet_limit: {
    title: '이미 아이가 있어요',
    body: '한 번에 한 아이와 지내는 서비스예요. 지금은 새 아이를 만들 수 없어요.',
  },
  daily_cap: {
    title: '오늘은 여기까지예요',
    body: '오늘 만들 수 있는 만큼을 다 썼어요. 내일 다시 만나요.',
  },
  service_cap: {
    title: '오늘은 여기까지예요',
    body: '오늘은 아이가 많이 태어났어요. 내일 다시 만나요.',
  },
  ip_rate: {
    title: '지금은 새 아이를 만들 수 없어요',
    body: '잠시 후에 다시 시도해 주세요.',
  },
  quota: {
    title: '지금은 새 아이를 만들 수 없어요',
    body: '잠시 후에 다시 시도해 주세요.',
  },
  // 모르는 사유의 공통 문구. 명세 E절의 문장 그대로다.
  unknown: {
    title: '지금은 새 아이를 만들 수 없어요',
    body: '잠시 후에 다시 시도해 주세요.',
  },
};

/**
 * ★★ **서버 응답의 모양을 아는 유일한 자리.**
 *
 * 서버가 모양을 바꾸면(사유를 본문 칸에 담는다든가) 고칠 곳은 이 함수 하나다. 바깥은
 * `HatchBlocked` 만 보고 그리므로 한 줄도 안 바뀐다.
 *
 * 지금의 약속(2026-09-14 확정) — 상태 409 · `error.code` 가 `ZZAL_HATCH_BLOCKED_*`.
 * 공통 클라이언트(`common/fe/api/client.ts`)가 봉투를 벗겨 `ApiError.code` 에 넣어 준다.
 *
 * @returns 막힘이 아니면 null — 그때는 부르는 쪽이 하던 대로 오류를 다룬다.
 */
function readCode(e: unknown): { code: string; serverMessage: string | null } | null {
  if (!(e instanceof ApiError)) return null;
  const code = e.code ?? '';
  if (!code.startsWith(BLOCKED_PREFIX) && !(code in REASON_OF)) return null;
  return { code, serverMessage: e.message || null };
}

/**
 * 이 실패가 "막혔다" 인가. 맞으면 화면에 그대로 얹을 안내를 돌려준다.
 *
 * ★ 콘솔에 서버 문장을 남기는 것도 여기서 한 번만 한다. 부르는 쪽마다 하면 빠지는 곳이 생긴다.
 */
export function readHatchBlocked(e: unknown): HatchBlocked | null {
  const wire = readCode(e);
  if (!wire) return null;
  const reason = REASON_OF[wire.code] ?? 'unknown';
  if (reason === 'unknown') {
    // 새 코드가 생겼다는 뜻이다. 표에 줄을 한 줄 더하면 전용 문구가 나간다.
    console.warn('[zzal] 모르는 부화 막힘 코드 — 공통 문구로 안내했다:', wire.code, wire.serverMessage);
  } else {
    console.info('[zzal] 부화 막힘:', wire.code, wire.serverMessage);
  }
  return { reason, ...COPY[reason], code: wire.code, serverMessage: wire.serverMessage };
}
