/* 세계관 체험의 세계 · 사건. GET /play/worlds 가 주는 모양 그대로다.
 * 그림이 없는 사건(image_url null)도 있다. tried 는 아직 집계 전이라 화면에 안 쓴다.
 * 사건마다 examples — 세계를 만든 사람이 그 사건에 정한 예시 캐릭터 id(최대 2). 체험의 「누구를 넣을까요?」 첫 칸에 나온다.
 * 세계의 examples 는 옛 모양(사건에 없을 때만 쓴다). */
import { listPlayWorlds, makePlayWorld, readPlayWorld, savePlayWorld, WebtoonApiError } from "../../lib/api";

export interface PlayEvent { id: string; no: string; title: string; line: string; situation: string; image_url: string | null; tried: number; place?: string; spot?: string; examples?: string[]; example_people?: { name: string; age?: number; desc: string; relation?: string; face_url?: string }[] }
export interface PlayPerson { name: string; role?: string; desc?: string; face_url?: string | null }
export interface PlayWorld { id: string; name: string; genre: string; one: string; cover_url: string | null; spot: string; cast: PlayPerson[]; events: PlayEvent[]; examples?: string[]; mine?: boolean; rule?: string }

/* 세계관 만들기의 글 — 저장 API(POST /play/worlds)에 그대로 보낸다 */
export interface DraftPerson { id: string; name: string; role: string; desc: string; face?: string }
export interface DraftEvent { id: string; title: string; line: string; situation: string; place: string; spot: string; cast: string[]; examples: string[]; image?: string }
export interface WorldDraft { id?: string; name: string; genre: string; one: string; rule: string; cast: DraftPerson[]; events: DraftEvent[]; examples: string[]; visibility: "public" | "private"; lorebook_id?: string }

/* 서버에 저장 길이 아직 없을 때(404 · 405) 만든 세계를 이 브라우저에 둔다. 서버가 생기면 그쪽이 먼저다. */
const LOCAL = "lore-my-worlds";
function readLocal(): PlayWorld[] {
  try { return JSON.parse(localStorage.getItem(LOCAL) || "[]"); } catch { return []; }
}
function toWorld(id: string, d: WorldDraft): PlayWorld {
  return {
    id, name: d.name, genre: d.genre, one: d.one, rule: d.rule, cover_url: null, mine: true, examples: d.examples,
    spot: d.events[0]?.spot || "",
    cast: d.cast.map((p) => ({ name: p.name, role: p.role, desc: p.desc })),
    events: d.events.map((e, i) => ({ id: `${id}-${i + 1}`, no: `EP ${String(i + 1).padStart(2, "0")}`, title: e.title, line: e.line, situation: e.situation, place: e.place, spot: e.spot, examples: e.examples, image_url: null, tried: 0 })),
  };
}

export const loadWorlds = () => listPlayWorlds<PlayWorld>().then((r) => {
  const have = new Set(r.worlds.map((w) => w.id));
  return [...r.worlds, ...readLocal().filter((w) => !have.has(w.id))];
});

export async function saveWorld(d: WorldDraft): Promise<string> {
  try {
    const r = await savePlayWorld(d, d.id);
    return r.id;
  } catch (x) {
    if (!(x instanceof WebtoonApiError) || (x.status !== 404 && x.status !== 405)) throw x;
    const id = `my-${Math.random().toString(36).slice(2, 10)}`;
    try { localStorage.setItem(LOCAL, JSON.stringify([...readLocal(), toWorld(id, d)])); } catch { /* 저장소가 막힌 브라우저 */ }
    return id;
  }
}

/* AI 로 채우기 · 다시 만들기 — 서버가 다 지을 때까지 3초마다 본다. 돌려받은 세계를 만들기 글 모양으로 바꿔 준다. */
type ServerWorld = Omit<PlayWorld, "cast" | "events"> & { making?: string; error?: string; visibility?: "public" | "private"; lorebook_id?: string;
  cast: (PlayPerson & { id?: string })[]; events: (PlayEvent & { cast?: string[] })[] };
export async function makeWorld(d: WorldDraft, onTick?: () => void): Promise<WorldDraft> {
  const r = await makePlayWorld({ id: d.id, draft: d, lorebook_id: d.lorebook_id });
  for (;;) {
    await new Promise((ok) => setTimeout(ok, 3000));
    onTick?.();
    const w = await readPlayWorld<ServerWorld>(r.id).catch(() => null);
    if (w?.making === "error") throw new Error(w.error || "");
    if (w && w.making !== "running") return fromServer(w, d);
  }
}
function fromServer(w: ServerWorld, d: WorldDraft): WorldDraft {
  const cast = (w.cast || []).map((p, i) => ({ id: p.id || `c${i + 1}`, name: p.name, role: p.role || "", desc: p.desc || "", face: p.face_url || undefined }));
  return {
    ...d, id: w.id, name: w.name || d.name, genre: w.genre || d.genre, one: w.one || d.one, rule: w.rule || d.rule,
    visibility: w.visibility || d.visibility, lorebook_id: w.lorebook_id || d.lorebook_id, examples: w.examples || d.examples, cast,
    events: (w.events || []).map((e) => ({ id: e.id, title: e.title, line: e.line || "", situation: e.situation || "", place: e.place || "", spot: e.spot || "", cast: e.cast || [], examples: e.examples || [], image: e.image_url || undefined })),
  };
}
