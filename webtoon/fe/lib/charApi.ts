"use client";

/* 캐릭터 탭이 서버에 말 거는 자리.
 *
 * 웹툰 쪽(nhApi)과 같은 규칙이다 — 봉투를 안 씌운 응답을 그대로 읽고, 실패는
 * `{error: "사람이 읽을 한 줄"}` 로 온다. */

import { getUid } from "./nhApi";

const BASE = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon/v1";

export interface Character {
  id: string;
  name: string;
  description: string;
  /** 잠깐 열리는 주소. 아직 안 그렸으면 null. */
  art_url: string | null;
  /** photo · prompt · builtin — 어떻게 만들었나 */
  source: "photo" | "prompt" | "builtin";
  /** drawing · ready · error — 그리는 데 1분쯤 걸린다 */
  status: "drawing" | "ready" | "error";
  /** 못 그렸을 때 사람이 읽을 한 줄 */
  error: string | null;
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
  /* **이 브라우저가 누구인지 늘 같이 보낸다.**
   *
   * 캐릭터는 로그인 없이도 만들 수 있어서, 게스트가 만든 것은 계정이 아니라
   * 이 값으로만 자기 것임을 말할 수 있다. 안 보내면 방금 만든 캐릭터가 다음
   * 목록에서 사라진다 — 서버가 남의 것으로 보기 때문이다.
   *
   * 웹툰 쪽과 **같은 값**을 쓴다(`nhApi.getUid` — localStorage 의 lore_uid).
   * 따로 만들면 같은 브라우저인데 캐릭터와 작품의 주인이 갈린다.
   *
   * 네 가지 부름(목록·만들기·고치기·지우기)이 다 여기를 지나므로 한 곳에서
   * 붙인다. 본문에 실으면 본문이 없는 GET·DELETE 가 빠진다. */
  const res = await fetch(BASE + path, {
    ...init,
    headers: { ...(init?.headers || {}), "X-Lore-Uid": getUid() },
  });
  let body: unknown = null;
  try { body = await res.json(); } catch { /* JSON 이 아닐 수 있다 */ }
  if (!res.ok) {
    /* 사유가 오는 모양이 둘이다 — 이 컨트롤러는 `{error: "한 줄"}` 로 주는데,
       프록시나 공용 봉투를 지나면 `{error: {code, message}}` 로 온다. 앞의 것만
       읽으면 객체가 그대로 글자가 돼 "[object Object]" 가 뜬다. */
    const b = body as { error?: unknown; message?: unknown } | null;
    const err = b?.error;
    const said = typeof err === "string" ? err
      : typeof (err as { message?: unknown } | null)?.message === "string"
        ? String((err as { message: string }).message)
        : typeof b?.message === "string" ? b.message : "";
    throw new Error(said.trim() || `요청이 실패했습니다 (${res.status})`);
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
