/* 세계관 체험의 세계 · 사건. GET /play/worlds 가 주는 모양 그대로다.
 * 그림이 없는 사건(image_url null)도 있다. tried 는 아직 집계 전이라 화면에 안 쓴다.
 * 사건마다 examples — 세계를 만든 사람이 그 사건에 정한 예시 캐릭터 id(최대 2). 체험의 「누구를 넣을까요?」 첫 칸에 나온다.
 * 세계의 examples 는 옛 모양(사건에 없을 때만 쓴다). */
import { uploadDataUrls } from "@common/api/uploads";
import { listPlayWorlds, makePlayWorld, readPlayWorld, savePlayWorld, WebtoonApiError } from "../../lib/api";

export interface PlayEvent { id: string; no: string; title: string; line: string; situation: string; image_url: string | null; tried: number; canon?: string[]; canon_end?: string; place?: string; spot?: string; cast?: string[]; examples?: string[]; example_people?: { id?: string; name: string; role?: string; age?: number; desc: string; relation?: string; face_url?: string; character_id?: string }[] }
export interface PlayPerson { id?: string; name: string; role?: string; desc?: string; face_url?: string | null }
export interface PlayWorld { id: string; name: string; genre: string; one: string; cover_url: string | null; spot: string; cast: PlayPerson[]; events: PlayEvent[]; examples?: string[]; mine?: boolean; rule?: string }

/* 세계관 만들기의 글 — 저장 API(POST /play/worlds)에 그대로 보낸다 */
export interface DraftPerson { id: string; name: string; role: string; desc: string; face?: string; character_id?: string }
export interface DraftEvent { id: string; title: string; line: string; situation: string; place: string; spot: string; cast: string[]; examples: string[]; example_people?: DraftPerson[]; image?: string }
export interface WorldDraft { id?: string; cover?: string; name: string; genre: string; one: string; rule: string; cast: DraftPerson[]; events: DraftEvent[]; examples: string[]; visibility: "public" | "private"; lorebook_id?: string }

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

/* 사진(data URL)은 공용 presign 으로 창고에 먼저 올리고 키만 보낸다 — 큰 본문 POST 는 dev · staging · 운영 WAF 가 막는다.
 * 올리기가 안 되는 곳(로컬에 창고가 없을 때)은 data URL 을 그대로 보낸다. 서버는 둘 다 받는다(lore-23). */
async function pic(u?: string): Promise<{ key?: string; url?: string; kept?: string }> {
  if (!u) return {};
  if (!u.startsWith("data:")) return { kept: u }; // 서버가 준 그림 주소 — 그대로 다시 보내야 그림이 남는다
  try { const [key] = await uploadDataUrls([u], "webtoon"); return key ? { key } : { url: u }; } catch { return { url: u }; }
}
async function withKeys(d: WorldDraft) {
  const c = await pic(d.cover);
  const face = async (p: DraftPerson) => { const f = await pic(p.face); return { ...p, face: f.url, face_key: f.key, face_url: f.kept }; };
  return {
    ...d, cover: c.url, cover_key: c.key, cover_url: c.kept,
    cast: await Promise.all(d.cast.map(face)),
    events: await Promise.all(d.events.map(async (e) => {
      const im = await pic(e.image);
      return { ...e, image: im.url, image_key: im.key, image_url: im.kept, example_people: await Promise.all((e.example_people || []).map(face)) };
    })),
  };
}

export async function saveWorld(d: WorldDraft): Promise<string> {
  try {
    const r = await savePlayWorld(await withKeys(d), d.id);
    return r.id;
  } catch (x) {
    if (d.id || !(x instanceof WebtoonApiError) || (x.status !== 404 && x.status !== 405)) throw x;
    const id = `my-${Math.random().toString(36).slice(2, 10)}`;
    try { localStorage.setItem(LOCAL, JSON.stringify([...readLocal(), toWorld(id, d)])); } catch { /* 저장소가 막힌 브라우저 */ }
    return id;
  }
}

/* AI 로 채우기 · 다시 만들기 — 서버가 다 지을 때까지 3초마다 본다. 돌려받은 세계를 만들기 글 모양으로 바꿔 준다. */
export type ServerWorld = Omit<PlayWorld, "cast" | "events"> & { making?: string; error?: string; visibility?: "public" | "private"; lorebook_id?: string;
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
export function fromServer(w: ServerWorld, d: WorldDraft): WorldDraft {
  const cast = (w.cast || []).map((p, i) => ({ id: p.id || `c${i + 1}`, name: p.name, role: p.role || "", desc: p.desc || "", face: p.face_url || undefined }));
  return {
    ...d, id: w.id, cover: w.cover_url || d.cover, name: w.name || d.name, genre: w.genre || d.genre, one: w.one || d.one, rule: w.rule || d.rule,
    visibility: w.visibility || d.visibility, lorebook_id: w.lorebook_id || d.lorebook_id, examples: w.examples || d.examples, cast,
    /* 운영 세계는 사건 인물을 이름으로 적어 둔다 — 고치는 칸은 인물 id 로 고르니 이름을 id 로 바꾼다 */
    events: (w.events || []).map((e) => ({ id: e.id, title: e.title, line: e.line || "", situation: e.situation || "", place: e.place || "", spot: e.spot || "", cast: (e.cast || []).map((c) => cast.find((p) => p.name === c)?.id || c), examples: e.examples || [], image: e.image_url || undefined,
      example_people: (e.example_people || []).map((x) => ({ id: x.id || x.name, name: x.name, role: x.role || "", desc: x.desc || "", face: x.face_url || undefined, character_id: x.character_id })) })),
  };
}
