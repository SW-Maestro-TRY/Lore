/* 세계관 체험의 세계 · 사건. GET /play/worlds 가 주는 모양 그대로다.
 * 그림이 없는 사건(image_url null)도 있다. tried 는 아직 집계 전이라 화면에 안 쓴다. */
import { BASE, getUid } from "../../lib/api";
import type { Lang } from "../../lib/i18n";

export interface PlayEvent { id: string; no: string; title: string; line: string; situation: string; image_url: string | null; tried: number }
export interface PlayPerson { name: string; role?: string }
export interface PlayWorld { id: string; name: string; genre: string; one: string; cover_url: string | null; spot: string; cast: PlayPerson[]; events: PlayEvent[] }

export async function loadWorlds(): Promise<PlayWorld[]> {
  const r = await fetch(`${BASE}/play/worlds`);
  if (!r.ok) throw new Error(String(r.status));
  return ((await r.json()) as { worlds: PlayWorld[] }).worlds;
}

export async function startPlay(body: { world_id: string; event_id: string; character_id: string; agree_ip: boolean; language: Lang }): Promise<{ id: string; queue_position?: number }> {
  const r = await fetch(`${BASE}/play/runs`, { method: "POST", credentials: "include", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ...body, uid: getUid() }) });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}
