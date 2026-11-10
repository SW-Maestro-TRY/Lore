import { API_BASE } from '@common/api/client';
import { getAuthoredPersona, getChatHistory, getLegacyChatHistory } from './pet';
import type { ChatHistoryPage, LegacyChatHistoryPage, PersonaSource } from './pet';
import { getFirstMeeting } from './firstMeeting';
import type { FirstMeetingStatus } from './firstMeeting';
import type { Live } from '../tamagotchi/yeoul/useHatch';

/** Pass the ONE service-root useHatchState() owner. Never mount another owner for
 * a drawer/iframe: that would duplicate lifecycle presence and polling. */
export type RoomChatSource = Pick<Live, 'petId' | 'pet' | 'chat' | 'chatting' |
  'chatDelivery' | 'chatPresenceRevision' | 'sendChat'>;
type Dependencies = {
  persona: typeof getAuthoredPersona; meeting: typeof getFirstMeeting;
  history: typeof getChatHistory; legacyHistory: typeof getLegacyChatHistory;
};
const productDependencies: Dependencies = {
  persona: getAuthoredPersona, meeting: getFirstMeeting,
  history: getChatHistory, legacyHistory: getLegacyChatHistory,
};
export type RoomChatBlock = 'origin' | 'loading' | 'not-alive' | 'meeting' | 'sleeping' | 'traveling' | 'closed' | null;
export interface RoomChatSnapshot {
  pet: Live['pet']; chat: Live['chat']; persona: PersonaSource | null;
  meeting: FirstMeetingStatus | null; blocked: RoomChatBlock;
  draft: string; sending: boolean; delivery: Live['chatDelivery'];
  error: string | null; history: ChatHistoryPage | null;
  legacyHistory: LegacyChatHistoryPage | null; historyLoading: boolean;
  /** Server-authoritative proactive speech. No punctuation/content guessing. */
  stageSpeech: { key: string; line: string } | null;
  presenceRevision: number;
}

export function roomChatSameOrigin(pageUrl: string, apiBase: string): boolean {
  try {
    const page = new URL(pageUrl);
    return /^https?:$/.test(page.protocol) && new URL(apiBase || '/', page).origin === page.origin;
  } catch { return false; }
}

/** A view bridge, not a second delivery/presence state machine.
 * sendChat delegates UUID/session/round/timeout/retry to the live owner unchanged.
 * Render text with textContent/React text nodes; history stays original language.
 */
export function createRoomChatBridge(
  source: RoomChatSource,
  context: { pageUrl: string; apiBase: string },
  deps: Dependencies = productDependencies,
) {
  let live = source;
  const originOK = roomChatSameOrigin(context.pageUrl, context.apiBase);
  let disposed = false, generation = 0, contextEpoch = 0, sending = false;
  let state: RoomChatSnapshot = {
    pet: live.pet, chat: live.chat, persona: null, meeting: null,
    blocked: originOK ? 'loading' : 'origin', draft: '', sending: false,
    delivery: live.chatDelivery, error: null, history: null, legacyHistory: null,
    historyLoading: false, stageSpeech: null, presenceRevision: 0,
  };
  const listeners = new Set<() => void>();
  const publish = (patch: Partial<RoomChatSnapshot> = {}) => {
    if (disposed) return;
    state = { ...state, ...patch };
    let blocked: RoomChatBlock = !originOK ? 'origin'
      : !live.petId || !live.pet || live.pet.petId !== live.petId ? 'loading'
      : live.pet.phase !== 'ALIVE' ? 'not-alive'
      : !state.meeting || !state.persona ? 'loading'
      : state.meeting.state !== 'COMPLETE' ? 'meeting'
      : live.pet.clock?.sleeping ? 'sleeping'
      : live.pet.trip ? 'traveling'
      : !live.chat?.openSlot || !live.chat.session || live.chat.session.closed ? 'closed' : null;
    const speech = live.chat?.stageSpeech;
    state = { ...state, pet: live.pet, chat: live.chat, blocked,
      sending: sending || live.chatting || live.chatDelivery.status === 'sending',
      delivery: live.chatDelivery, presenceRevision: live.chatPresenceRevision,
      stageSpeech: !blocked && speech && live.chat?.session
        ? { key: `${live.petId}:${live.chat.session.id}:${speech.turnIdx}`, line: speech.line } : null };
    for (const listener of listeners) listener();
  };
  const current = (version: number, petId: number) => !disposed && version === generation && live.petId === petId;
  const loadContext = async () => {
    if (!originOK || !live.petId || disposed) return;
    const petId = live.petId, version = generation, epoch = ++contextEpoch;
    publish({ persona: null, meeting: null, error: null });
    try {
      const [persona, meeting] = await Promise.all([deps.persona(petId), deps.meeting(petId)]);
      if (current(version, petId) && epoch === contextEpoch) publish({ persona, meeting });
    } catch (error) {
      if (current(version, petId) && epoch === contextEpoch) publish({ error: error instanceof Error ? error.message : '대화 상태를 확인하지 못했어요.' });
    }
  };
  const update = (next: RoomChatSource) => {
    const changed = live.petId !== next.petId;
    live = next;
    if (changed) {
      generation++; sending = false;
      publish({ draft: '', persona: null, meeting: null, history: null, legacyHistory: null, historyLoading: false, error: null });
      // Caller explicitly invokes loadContext after switching the owner/pet.
    } else publish();
  };
  const send = async () => {
    if (disposed || state.sending || state.blocked) return { sent: false, error: null, reply: null };
    const text = live.chatDelivery.status === 'uncertain' ? live.chatDelivery.text : state.draft;
    if (!text.trim()) return { sent: false, error: null, reply: null };
    const petId = live.petId!, version = generation;
    sending = true; publish({ error: null });
    try {
      const result = await live.sendChat(text);
      if (!current(version, petId)) return { sent: false, error: null, reply: null };
      // null/null means owner guard/no-op; never erase a draft or invent success.
      if (result.reply && !result.error) publish({ draft: '' });
      else if (result.error) publish({ draft: text, error: result.error });
      return { sent: !!result.reply && !result.error, ...result };
    } catch (error) {
      const message = error instanceof Error ? error.message : '답을 확인하지 못했어요.';
      if (current(version, petId)) publish({ draft: text, error: message });
      return { sent: false, error: message, reply: null };
    } finally { if (current(version, petId)) { sending = false; publish(); } }
  };
  const history = async (legacy = false, more = false) => {
    if (!originOK || disposed || !live.petId || state.historyLoading) return;
    const petId = live.petId, version = generation;
    const old = legacy ? state.legacyHistory : state.history;
    if (more && (!old || old.nextBeforeId === null)) return;
    const cursor = more ? old!.nextBeforeId! : undefined;
    publish({ historyLoading: true, error: null });
    try {
      if (legacy) {
        const page = await deps.legacyHistory(petId, cursor);
        if (current(version, petId)) publish({ legacyHistory: { ...page,
          calls: [...new Map([...(more ? state.legacyHistory?.calls ?? [] : []), ...page.calls].map(t => [t.id, t])).values()] } });
      } else {
        const page = await deps.history(petId, cursor);
        if (current(version, petId)) publish({ history: { ...page,
          turns: [...new Map([...(more ? state.history?.turns ?? [] : []), ...page.turns].map(t => [t.id, t])).values()] } });
      }
    } catch (error) {
      if (current(version, petId)) publish({ error: error instanceof Error ? error.message : '지난 대화를 불러오지 못했어요.' });
    } finally { if (current(version, petId)) publish({ historyLoading: false }); }
  };
  publish();
  return {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    update, loadContext, send, history,
    setDraft(text: string) {
      if (state.sending || live.chatDelivery.status === 'uncertain') return;
      publish({ draft: text });
    },
    dispose() { disposed = true; generation++; listeners.clear(); },
  };
}
export type RoomChatBridge = ReturnType<typeof createRoomChatBridge>;
/** Product mounting helper. The existing cookie-authenticated API origin is the only target. */
export function createProductRoomChatBridge(source: RoomChatSource): RoomChatBridge {
  if (typeof window === 'undefined') throw new Error('Room chat requires the product browser context');
  return createRoomChatBridge(source, { pageUrl: window.location.href, apiBase: API_BASE });
}
