// 개발용 **알 화면 시안 전환**(dots / glow / off) — 리모컨에서 바꾸고 새로고침에도 남긴다(2026-10-13).
//
// ★ 상훈님 원문(10/13): "glow 같은 걸 내가 보고 싶어서 리모컨을 달라고 한거야. dev에서."
//   그전에는 주소 `?egg=` 로만 바꿀 수 있었다. 폰에서 주소창을 고치는 것은 번거롭고, 한 번 이동하면 쿼리가 사라진다.
//
// ★ 읽는 자리는 알 온보딩(feat/onboarding-egg-1013 `personaSteps.eggVariant`)이다. 그쪽이
//   `readDevEgg() ?? (주소 ?egg=) ?? 'dots'` 순으로 읽고 `onDevEggChange` 를 들으면 리모컨이 곧바로 먹는다.
//   이 브랜치에는 그 화면이 없어 **키를 쓰고 알리는 쪽**만 있다. 두 브랜치가 develop 에서 만나면 이어진다.
// ★ 리모컨 값이 주소보다 앞선다 — 리모컨은 일부러 누른 것이고, 주소의 ?egg= 는 지난 링크에 남은 것일 수 있다.
//   주소로 되돌리려면 리모컨에서 「주소대로」 를 누른다(키를 지운다).

export type EggVariant = 'dots' | 'glow' | 'off';
export const DEV_EGG_KEY = 'zzal.devEgg';
const EVENT = 'zzal:dev-egg';

/** 리모컨이 고른 시안. 고른 적 없거나 저장소를 못 읽으면 null(→ 주소·기본값으로). */
export function readDevEgg(): EggVariant | null {
  try {
    const v = window.localStorage.getItem(DEV_EGG_KEY);
    return v === 'dots' || v === 'glow' || v === 'off' ? v : null;
  } catch {
    return null;
  }
}

/** 시안을 바꾼다. null 이면 지운다(주소대로). 같은 탭의 화면에도 알린다(storage 이벤트는 다른 탭에만 온다). */
export function writeDevEgg(v: EggVariant | null): void {
  try {
    if (v) window.localStorage.setItem(DEV_EGG_KEY, v);
    else window.localStorage.removeItem(DEV_EGG_KEY);
  } catch {
    // 저장이 막힌 창(사생활 보호)이어도 이번 화면에는 알린다.
  }
  try { window.dispatchEvent(new CustomEvent(EVENT, { detail: v })); } catch { /* 무시 */ }
  // 알 온보딩 화면(feat/onboarding-egg-1013)이 듣는 이름 — 같은 탭에서 바로 반영되게 함께 보낸다.
  try { window.dispatchEvent(new Event('zzal:devEgg')); } catch { /* 무시 */ }
}

/** 바뀌면 부른다. 돌려준 함수로 끊는다. */
export function onDevEggChange(fn: (v: EggVariant | null) => void): () => void {
  const own = (e: Event) => fn(((e as CustomEvent).detail ?? null) as EggVariant | null);
  const other = (e: StorageEvent) => { if (e.key === DEV_EGG_KEY) fn(readDevEgg()); };
  window.addEventListener(EVENT, own);
  window.addEventListener('storage', other);
  return () => { window.removeEventListener(EVENT, own); window.removeEventListener('storage', other); };
}
