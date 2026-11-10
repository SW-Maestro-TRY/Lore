// 시간대 산수 — 이름은 옛 "KST" 그대로 두고, 계산은 **그 펫의 시간대**로 한다(2026-10-26).
//
// ★ 시간대가 둘이다.
//   - 규칙 시간대(rules): 서버가 창(재우기 19·자동 취침 23·깨우기 07·자동 기상 10·부름 10/14/19시)을 재는 벽시계.
//     PetDetail.rulesTimeZone. 이 파일의 기존 함수(dayStart·timeOfDay·at·dayIndex·kstHourMinute·lightPhaseAt)는
//     전부 이 시간대로 계산한다 — 목 서버가 서버 규칙을 흉내 내고, 빛(아침·노을·밤)이 아이의 잠과 맞아야 하므로.
//   - 표시 시간대(display): 사람에게 시각·날짜를 보여 줄 때. PetDetail.timeZone.
//   1단계(서버 규칙이 아직 KST)에는 둘이 다를 수 있다. 그때 창 시각은 **표시 시간대로 바꿔서** 보여 준다
//   (ruleHourLabel). 2단계 뒤에는 서버가 둘을 같은 값으로 보내므로 이 파일은 바뀔 것이 없다.
//
// ★ 서머타임 — 고정 오프셋 덧셈(옛 "UTC+9")을 쓰지 않는다. Intl.DateTimeFormat 로 그 순간의 벽시계를 읽고,
//   벽시계 → 순간은 두 번 맞춰 본다(zonedToUtc). 그래서 23시간·25시간 날에도 "그날 19:00" 이 정확하다.
//
// ★ 이 파일 밖에서 `new Date().getHours()`·시간대 없는 `toLocale*` 를 쓰지 않는다. 기기 시간대는 펫의 시간대와
//   다를 수 있다(여행·기기 설정). 날짜·시각을 글로 보일 때는 formatInZone 을 쓴다.
//
// 목 서버(lib/mock)와 훅(useClock)이 같은 함수를 쓴다 — 경계 계산이 두 벌이면 반드시 어긋난다.

import { LIGHT_PHASES, type LightPhase } from '../tamagotchi/rules';

export const SEOUL = 'Asia/Seoul';
/** @deprecated 고정 오프셋은 서머타임에서 틀린다. 남은 호출처가 없을 때 지운다. Asia/Seoul 의 오프셋일 뿐이다. */
export const KST_OFFSET_MS = 9 * 3600_000;
export const DAY_MS = 86_400_000;
export const HOUR_MS = 3600_000;
export const MINUTE_MS = 60_000;

// ── 세션에서 한 번 정하는 시간대 ─────────────────────────────────────────

let rulesZone = SEOUL;
let displayZone = SEOUL;

function validZone(zone: string | null | undefined): string | null {
  if (!zone) return null;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return zone;
  } catch {
    return null;
  }
}

/**
 * PetDetail 을 받으면 한 번 부른다. 펫이 오기 전에는 둘 다 Asia/Seoul(옛 동작 그대로).
 * 못 읽는 값은 Asia/Seoul 로 둔다.
 */
export function setPetZones(zones: { timeZone?: string | null; rulesTimeZone?: string | null } | null | undefined): void {
  displayZone = validZone(zones?.timeZone) ?? SEOUL;
  rulesZone = validZone(zones?.rulesTimeZone) ?? SEOUL;
}

export function getRulesZone(): string { return rulesZone; }
export function getDisplayZone(): string { return displayZone; }

/** 둘 다 서울이면 참 — 한국 사용자. 이때 화면 문구는 옛 문구 그대로다. */
export function zonesAreSeoul(): boolean { return rulesZone === SEOUL && displayZone === SEOUL; }

/** 규칙 시간대와 표시 시간대가 같으면 참 — 창 시각을 바꿀 것이 없다. */
export function rulesAreLocal(): boolean { return rulesZone === displayZone; }

/** 기기의 IANA 시간대. 서버에 X-Zzal-Tz 로 보낸다. 못 읽으면 null. */
export function deviceTimeZone(): string | null {
  try {
    return validZone(Intl.DateTimeFormat().resolvedOptions().timeZone);
  } catch {
    return null;
  }
}

// ── 벽시계 읽기 / 맞추기 ─────────────────────────────────────────────────

interface Wall { y: number; mo: number; d: number; h: number; mi: number; s: number }

const fmtCache = new Map<string, Intl.DateTimeFormat>();
function partsFormatter(zone: string): Intl.DateTimeFormat {
  let f = fmtCache.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone, hourCycle: 'h23',
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
    fmtCache.set(zone, f);
  }
  return f;
}

/** 그 순간 그 시간대의 벽시계. */
export function wallAt(msValue: number, zone: string = rulesZone): Wall {
  const out: Record<string, number> = {};
  for (const p of partsFormatter(zone).formatToParts(new Date(msValue))) {
    if (p.type !== 'literal') out[p.type] = Number(p.value);
  }
  // 일부 엔진은 자정을 24 로 낸다(hourCycle 무시). 같은 날 0시로 읽는다.
  return { y: out.year, mo: out.month, d: out.day, h: out.hour === 24 ? 0 : out.hour, mi: out.minute, s: out.second };
}

/** 그 순간의 UTC 오프셋(ms). 서울은 늘 +9h. */
export function offsetAt(msValue: number, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  const floorSec = Math.floor(msValue / 1000) * 1000;
  return Date.UTC(w.y, w.mo - 1, w.d, w.h, w.mi, w.s) - floorSec;
}

/**
 * 벽시계 → 순간. 월·일·시가 넘치면 Date.UTC 처럼 다음 칸으로 넘긴다(d+1 = 다음 날).
 * 서머타임으로 없는 시각(예 02:30)은 뒤로 밀린 순간, 두 번 있는 시각은 앞의 것 — java.time 과 같은 규칙.
 */
export function zonedToUtc(y: number, mo: number, d: number, h = 0, mi = 0, s = 0, zone: string = rulesZone): number {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  // 전환은 하루에 많아야 한 번이다 — 반나절 앞뒤의 오프셋 두 개가 후보의 전부다.
  const before = offsetAt(guess - 12 * HOUR_MS, zone);
  const after = offsetAt(guess + 12 * HOUR_MS, zone);
  const valid = [guess - before, guess - after].filter((t) => offsetAt(t, zone) === guess - t);
  if (valid.length) return Math.min(...valid);
  // 없는 시각(봄 전환의 틈) — 틈 앞의 오프셋으로 읽으면 틈 길이만큼 뒤로 밀린다(java.time 과 같다).
  return guess - before;
}

// ── 규칙 시간대 산수(옛 API 이름 그대로) ─────────────────────────────────

/** 그 시각이 속한 날의 자정(ms). */
export function dayStart(msValue: number, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  return zonedToUtc(w.y, w.mo, w.d, 0, 0, 0, zone);
}

/** 그날 자정부터의 **벽시계** 경과(ms). 서머타임 날에도 19시면 19h 다. */
export function timeOfDay(msValue: number, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  return w.h * HOUR_MS + w.mi * MINUTE_MS + w.s * 1000 + (((msValue % 1000) + 1000) % 1000);
}

/** 같은 날의 hh:mm(ms). */
export function at(msValue: number, hour: number, minute = 0, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  return zonedToUtc(w.y, w.mo, w.d, hour, minute, 0, zone);
}

/** n 달력일 뒤 같은 벽시계 시각. "+ DAY_MS" 는 서머타임 날에 한 시간 어긋나므로 이것을 쓴다. */
export function addDays(msValue: number, n: number, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  return zonedToUtc(w.y, w.mo, w.d + n, w.h, w.mi, w.s, zone) + (((msValue % 1000) + 1000) % 1000);
}

/** "달력일" 비교에 쓰는 정수(1970-01-01 = 0). */
export function dayIndex(msValue: number, zone: string = rulesZone): number {
  const w = wallAt(msValue, zone);
  return Math.floor(Date.UTC(w.y, w.mo - 1, w.d) / DAY_MS);
}

/** 규칙 시간대의 시·분(0~23, 0~59). 빛 단계용. */
export function kstHourMinute(msValue: number, zone: string = rulesZone): { hour: number; minute: number } {
  const w = wallAt(msValue, zone);
  return { hour: w.h, minute: w.mi };
}

/** "19:00" 꼴(규칙 시간대). */
export function kstClockLabel(msValue: number, zone: string = rulesZone): string {
  const { hour, minute } = kstHourMinute(msValue, zone);
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/** 시각 → 빛(정본 §11). 07~11 아침 / 11~17 낮 / 17~19 노을 / 19~07 밤. 규칙 시간대 — 아이의 잠과 같은 시계. */
export function lightPhaseAt(msValue: number, zone: string = rulesZone): LightPhase {
  const { hour } = kstHourMinute(msValue, zone);
  for (const p of LIGHT_PHASES) {
    if (p.from < p.to ? hour >= p.from && hour < p.to : hour >= p.from || hour < p.to) return p.key;
  }
  return 'DAY';
}

// ── 표시 ────────────────────────────────────────────────────────────────

/** 표시 시간대의 시·분. 사람에게 "몇 시" 를 말할 때. */
export function localHourMinute(msValue: number, zone: string = displayZone): { hour: number; minute: number } {
  const w = wallAt(msValue, zone);
  return { hour: w.h, minute: w.mi };
}

/** 날짜·시각을 표시 시간대로 쓴다. toLocale* 를 직접 부르지 않는다. */
export function formatInZone(msValue: number | string | Date, locale: string, opts: Intl.DateTimeFormatOptions = {}, zone: string = displayZone): string {
  const d = msValue instanceof Date ? msValue : new Date(msValue);
  if (Number.isNaN(d.getTime())) return String(msValue);
  return new Intl.DateTimeFormat(locale, { ...opts, timeZone: zone }).format(d);
}

/**
 * 규칙 시간대의 "그날 hour:minute" 가 표시 시간대로는 몇 시인가. 1단계에서 서버 창(KST 19시 등)을
 * 현지 시각으로 말할 때 쓴다. 기준 날은 nowMs(서버 시각 권장, 없으면 기기 시각) — 오프셋만 쓰므로 하루 안의 오차는 없다.
 */
export function ruleHourInDisplay(hour: number, minute = 0, nowMs: number = Date.now()): { hour: number; minute: number } {
  return localHourMinute(at(nowMs, hour, minute, rulesZone), displayZone);
}

/** 시각 글: ko "오후 3시"·"오전 7시 30분", en "3 p.m."·"7:30 a.m.", ja "15時"·"7時30分". */
export function hourLabel(hour: number, minute: number, locale: 'ko' | 'en' | 'ja'): string {
  const h12 = hour % 12 === 0 ? 12 : hour % 12;
  if (locale === 'en') return `${h12}${minute ? `:${String(minute).padStart(2, '0')}` : ''} ${hour < 12 ? 'a.m.' : 'p.m.'}`;
  if (locale === 'ja') return `${hour}時${minute ? `${minute}分` : ''}`;
  return `${hour < 12 ? '오전' : '오후'} ${h12}시${minute ? ` ${minute}분` : ''}`;
}

/** 규칙 시각(예 19) → 표시 시간대 글. */
export function ruleHourLabel(hour: number, locale: 'ko' | 'en' | 'ja', nowMs?: number): string {
  const t = ruleHourInDisplay(hour, 0, nowMs);
  return hourLabel(t.hour, t.minute, locale);
}

/** ISO → ms. 못 읽으면 null(서버가 비운 칸을 그대로 null 로 흘린다). */
export function ms(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const v = Date.parse(iso);
  return Number.isFinite(v) ? v : null;
}
