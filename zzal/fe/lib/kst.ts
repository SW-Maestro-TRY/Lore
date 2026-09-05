// KST(Asia/Seoul) 산수. 한국은 서머타임이 없어 "UTC + 9시간" 덧셈으로 충분하다.
//
// ★ 이 파일 밖에서 `new Date().getHours()`·`toLocale*` 을 쓰지 않는다. 기기 시간대가 KST 가 아니면
//   창(19·23·07·10시)이 어긋나고, 그 어긋남은 한국 밖에서만 드러난다. 시각은 항상 ms(number)로 다루고
//   사람 눈에 보일 때만 여기 함수로 시·분을 뽑는다.
//
// 목 서버(lib/mock)와 훅(useClock)이 같은 함수를 쓴다 — 경계 계산이 두 벌이면 반드시 어긋난다.

import { LIGHT_PHASES, type LightPhase } from '../tamagotchi/rules';

export const KST_OFFSET_MS = 9 * 3600_000;
export const DAY_MS = 86_400_000;
export const HOUR_MS = 3600_000;
export const MINUTE_MS = 60_000;

/** 그 시각이 속한 KST 날의 자정(ms, UTC 기준 값). */
export function dayStart(ms: number): number {
  return Math.floor((ms + KST_OFFSET_MS) / DAY_MS) * DAY_MS - KST_OFFSET_MS;
}

/** KST 자정부터 지난 ms. */
export function timeOfDay(ms: number): number {
  return ms - dayStart(ms);
}

/** 같은 KST 날의 hh:mm(ms). */
export function at(ms: number, hour: number, minute = 0): number {
  return dayStart(ms) + hour * HOUR_MS + minute * MINUTE_MS;
}

/** 부화 이후 며칠째인지 같은 "달력일" 비교에 쓰는 정수. */
export function dayIndex(ms: number): number {
  return Math.floor((ms + KST_OFFSET_MS) / DAY_MS);
}

/** KST 시·분(0~23, 0~59). 화면 표시·빛 단계용. */
export function kstHourMinute(ms: number): { hour: number; minute: number } {
  const t = timeOfDay(ms);
  return { hour: Math.floor(t / HOUR_MS), minute: Math.floor((t % HOUR_MS) / MINUTE_MS) };
}

/** "19:00" 꼴. */
export function kstClockLabel(ms: number): string {
  const { hour, minute } = kstHourMinute(ms);
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/** 시각 → 빛(정본 §11). 07~11 아침 / 11~17 낮 / 17~19 노을 / 19~07 밤. 자는 동안은 커튼이라 여기 없다. */
export function lightPhaseAt(ms: number): LightPhase {
  const { hour } = kstHourMinute(ms);
  for (const p of LIGHT_PHASES) {
    if (p.from < p.to ? hour >= p.from && hour < p.to : hour >= p.from || hour < p.to) return p.key;
  }
  return 'DAY';
}

/** ISO → ms. 못 읽으면 null(서버가 비운 칸을 그대로 null 로 흘린다). */
export function ms(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const v = Date.parse(iso);
  return Number.isFinite(v) ? v : null;
}
