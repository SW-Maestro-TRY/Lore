/* 세계관 체험의 세계 · 사건. GET /play/worlds 가 주는 모양 그대로다(그림 칸은 없다).
 * 시작은 전용 API 없이 웹툰 만들기(/nh/create)를 그대로 쓴다 — 세계 설정은 lorebook, 사건은 settings 로 넘긴다. */
import { BASE, createJob, type Character, type LoreBook } from "../../lib/api";
import type { Lang } from "../../lib/i18n";
import { QUALITY_DEFAULT } from "../../lib/wizardData";

export interface PlayEvent { id: string; title: string; text: string }
export interface PlayWorld { id: string; name: string; genre: string; one: string; lorebook: LoreBook; events: PlayEvent[] }

export async function loadWorlds(): Promise<PlayWorld[]> {
  const r = await fetch(`${BASE}/play/worlds`);
  if (!r.ok) throw new Error(String(r.status));
  return ((await r.json()) as { worlds: PlayWorld[] }).worlds;
}

export function startPlay(w: PlayWorld, e: PlayEvent, c: Character, lang: Lang) {
  return createJob({
    lorebook: w.lorebook, name: c.name, character: c.description, photo_note: "", fields: {},
    genre: w.genre, story: "", style: PLAY_STYLE, quality: QUALITY_DEFAULT, language: lang,
    photos_data: [], agree_ip: true, checkpoints: false, character_id: c.id, settings: e.text, title: "",
  });
}
const PLAY_STYLE = "webtoon";

export async function saveWorld(body: { name: string; one: string; rule: string; cast: { name: string; role: string }[]; events: { title: string; situation: string }[] }): Promise<{ id: string }> {
  const r = await fetch(`${BASE}/play/worlds`, { method: "POST", credentials: "include", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}
