/* 세계관 체험의 세계 · 사건. GET /play/worlds 가 주는 모양 그대로다.
 * 그림이 없는 사건(image_url null)도 있다. tried 는 아직 집계 전이라 화면에 안 쓴다. */
import { BASE } from "../../lib/api";

export interface PlayEvent { id: string; no: string; title: string; line: string; situation: string; image_url: string | null; tried: number }
export interface PlayPerson { name: string; role?: string }
export interface PlayWorld { id: string; name: string; genre: string; one: string; cover_url: string | null; spot: string; cast: PlayPerson[]; events: PlayEvent[] }

export async function loadWorlds(): Promise<PlayWorld[]> {
  const r = await fetch(`${BASE}/play/worlds`);
  if (!r.ok) throw new Error(String(r.status));
  return ((await r.json()) as { worlds: PlayWorld[] }).worlds;
}

