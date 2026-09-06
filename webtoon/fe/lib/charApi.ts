"use client";

/* 캐릭터 탭이 서버에 말 거는 자리.
 *
 * 웹툰 쪽(nhApi)과 같은 규칙이다 — 봉투를 안 씌운 응답을 그대로 읽고, 실패는
 * `{error: "사람이 읽을 한 줄"}` 로 온다. */

const BASE = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon";

export interface Character {
  id: string;
  name: string;
  description: string;
  /** 잠깐 열리는 주소. 아직 안 그렸으면 null. */
  art_url: string | null;
  /** photo · prompt · builtin — 어떻게 만들었나 */
  source: "photo" | "prompt" | "builtin";
  /** 우리가 올려 둔 것인가 */
  builtin: boolean;
  /** 내가 만든 것인가 */
  mine: boolean;
  created_at: string;
}

export interface CharacterList {
  characters: Character[];
  logged_in: boolean;
  /** 오늘 공짜로 몇 개 더 만들 수 있나 */
  free_left: number;
  free_per_day: number;
  credit_cost: number;
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, init);
  let body: unknown = null;
  try { body = await res.json(); } catch { /* JSON 이 아닐 수 있다 */ }
  if (!res.ok) {
    const said = (body as { error?: unknown } | null)?.error;
    throw new Error(typeof said === "string" && said.trim()
      ? said : `요청이 실패했습니다 (${res.status})`);
  }
  return body as T;
}

export function listCharacters(): Promise<CharacterList> {
  return call<CharacterList>("/characters");
}

/** 만든다. 사진은 없어도 된다 — 그게 이 기능의 핵심이다. */
export function createCharacter(body: {
  name: string;
  description: string;
  photo_data?: string;
  style?: string;
}): Promise<Character> {
  return call<Character>("/characters", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function renameCharacter(id: string, name: string, description: string) {
  return call<Character>(`/characters/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, description }),
  });
}

export function removeCharacter(id: string) {
  return call<{ ok: boolean }>(`/characters/${encodeURIComponent(id)}`, { method: "DELETE" });
}
