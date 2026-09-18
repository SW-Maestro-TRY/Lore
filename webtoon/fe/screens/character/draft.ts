/* 「캐릭터 만들어보기」의 입력값을 잠깐 들고 있는 자리 — 결과 화면의 「다시 뽑기」가
 * 같은 입력으로 다시 만들 수 있게 sessionStorage 에 남긴다. 탭을 닫으면 사라진다. */
import { tryCharacter, type Character } from "../../lib/api";

export const DRAFT_KEY = "lore_try_draft";
/** 마지막으로 만든 카드의 id — 한도 소진 화면이 「이 카드로 1화」를 보일지 정한다. */
export const LAST_KEY = "lore_try_last";

export interface TryDraft {
  name: string;
  description: string;
  /** 프리셋 키 또는 직접 쓴 한 줄. 비우면 무작위. */
  world: string;
  photo?: string; // data URL
}

export function saveDraft(d: TryDraft): void {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify(d));
  } catch {
    /* 사진이 커서 못 담으면 사진만 빼고 남긴다 */
    try {
      sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ ...d, photo: undefined }));
    } catch {
      /* 그래도 안 되면 만들기는 계속한다 */
    }
  }
}

export function loadDraft(): TryDraft | null {
  if (typeof window === "undefined") return null;
  try {
    const v = JSON.parse(sessionStorage.getItem(DRAFT_KEY) || "null");
    if (!v || typeof v !== "object") return null;
    return {
      name: String(v.name || ""),
      description: String(v.description || ""),
      world: String(v.world || ""),
      photo: typeof v.photo === "string" && v.photo.startsWith("data:") ? v.photo : undefined,
    };
  } catch {
    return null;
  }
}

export function rememberLast(id: string): void {
  try {
    sessionStorage.setItem(LAST_KEY, id);
  } catch {
    /* 못 남겨도 된다 */
  }
}

export function lastCardId(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return sessionStorage.getItem(LAST_KEY);
  } catch {
    return null;
  }
}

/** 입력값으로 캐릭터를 만들고 마지막 카드로 기억한다. */
export async function runTry(d: TryDraft): Promise<Character> {
  const body: Parameters<typeof tryCharacter>[0] = {};
  if (d.name.trim()) body.name = d.name.trim();
  if (d.description.trim()) body.description = d.description.trim();
  if (d.world.trim()) body.world = d.world.trim();
  if (d.photo) body.photos_data = [d.photo];
  const c = await tryCharacter(body);
  rememberLast(c.id);
  return c;
}

/** 서버가 오늘 한도를 다 썼다고 했나. */
export function isLimitError(e: unknown): boolean {
  const status = (e as { status?: number } | null)?.status;
  const msg = (e as { message?: string } | null)?.message || "";
  return status === 402 || status === 403 || msg.includes("다 쓰셨어요");
}
