import { randomUuid } from './randomUuid';
import { readZzalLocale } from './locale';
import { API_BASE } from './api';
import type { ChatState } from './pet';

export type PresenceEntry = 'OPEN' | 'RETURN' | 'RELOAD';
export interface PresenceEvent {
  visitId: string; eventId: string; sequence: number;
  event: 'ENTER' | 'LEAVE'; entryKind: PresenceEntry;
}
export interface PresenceResult {
  outcome: 'GENERATED' | 'RESTORED' | 'IGNORED_RELOAD' | 'LEFT' | 'UNAVAILABLE' | 'STALE';
  chat: ChatState | null;
}

/** keepalive keeps the same cookie-authenticated event alive during page departure.
 * No refresh/retry on departure: a lost event must not become a second speech. */
export async function postChatPresence(petId: number, event: PresenceEvent): Promise<PresenceResult> {
  const response = await fetch(`${API_BASE}/api/zzal/v1/me/pets/${petId}/chat/presence`, {
    method: 'POST', credentials: 'include', keepalive: true,
    headers: { 'Content-Type': 'application/json', 'X-Zzal-Language': readZzalLocale() }, body: JSON.stringify(event),
  });
  if (!response.ok) throw new Error('Presence unavailable');
  const envelope = await response.json();
  if (!envelope.success || !envelope.data) throw new Error('Presence unavailable');
  return envelope.data;
}

/** Preserve later PET-only turns too: a speech does not increment round. */
export function mergeChatSnapshot(previous: ChatState | null, incoming: ChatState): ChatState {
  if (!previous || previous.session?.id !== incoming.session?.id) return incoming;
  const latest = (chat: ChatState) => Math.max(-1, ...(chat.turns ?? []).map(turn => turn.idx));
  if (latest(incoming) < latest(previous)) return previous;
  const turns = Array.from(new Map([...(previous.turns ?? []), ...(incoming.turns ?? [])]
    .map(turn => [turn.idx, turn])).values()).sort((a, b) => a.idx - b.idx);
  return { ...incoming, turns };
}

let navigationConsumed = false;
const visits = new Map<string, { visitId: string; sequence: number; active: boolean; initial: PresenceEntry }>();

/** A fresh navigation gets its own visit, even if a duplicated tab copied sessionStorage. */
export function presenceVisit(key: string) {
  const existing = visits.get(key);
  if (existing) return existing;
  const navigation = !navigationConsumed
    ? (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)?.type
    : undefined;
  navigationConsumed = true;
  const initial: PresenceEntry = navigation === 'reload' ? 'RELOAD' : 'OPEN';
  let saved: { visitId?: string; sequence?: number } = {};
  if (initial === 'RELOAD') {
    try { saved = JSON.parse(sessionStorage.getItem(key) ?? '{}'); } catch { /* memory fallback */ }
  }
  const visit = { visitId: typeof saved.visitId === 'string' ? saved.visitId : randomUuid(),
    sequence: Number.isSafeInteger(saved.sequence) && (saved.sequence ?? 0) >= 0 ? saved.sequence! : 0,
    active: false, initial };
  visits.set(key, visit);
  return visit;
}

export function nextPresenceEvent(key: string, event: PresenceEvent['event'], entryKind: PresenceEntry): PresenceEvent | null {
  const visit = presenceVisit(key);
  if ((event === 'ENTER') === visit.active) return null;
  visit.active = event === 'ENTER';
  visit.sequence += 1;
  try { sessionStorage.setItem(key, JSON.stringify({ visitId: visit.visitId, sequence: visit.sequence })); } catch { /* memory fallback */ }
  return { visitId: visit.visitId, eventId: randomUuid(), sequence: visit.sequence, event, entryKind };
}
