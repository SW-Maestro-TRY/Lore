/* 친구 초대(2026-11-03).
 *
 * 링크는 `/webtoon?ref=<코드>&utm_source=invite&utm_medium=friend`. 첫 방문의 utm·ref 는
 * track.ts 가 그대로 기록하고(utm 이 있으면 `invite/friend` 로 남는다), 여기서는 코드만
 * 브라우저에 기억해 뒀다가 **이 브라우저에서 로그인이 확인된 뒤 한 번** 서버로 보낸다.
 * 새 계정인지(옛 계정 로그인인지)는 서버가 가린다 — 결과가 무엇이든 코드는 지운다.
 */

import { request as appRequest } from "@common/api/client";
import { getUid } from "./api";

const KEY = "wt-invite-ref";

/** 주소의 ref 가 초대 코드 모양이면 기억해 둔다. 이미 든 코드가 있으면 처음 것을 지킨다. */
export function rememberInviteRef(): void {
  try {
    const code = new URLSearchParams(location.search).get("ref")?.trim().toUpperCase() ?? "";
    if (!/^[A-Z0-9]{4,16}$/.test(code)) return;
    if (!localStorage.getItem(KEY)) localStorage.setItem(KEY, code);
  } catch { /* 저장소가 없으면 초대만 못 셀 뿐이다 */ }
}

export function pendingInviteRef(): string | null {
  try { return localStorage.getItem(KEY); } catch { return null; }
}

/** 로그인이 확인된 뒤 — 들고 있던 코드를 서버에 보내고 지운다. */
export async function claimInvite(): Promise<string | null> {
  const code = pendingInviteRef();
  if (!code) return null;
  try {
    const out = await appRequest<{ result: string }>("/api/webtoon/v1/my/invite/claim",
      { method: "POST", body: { code, uid: getUid() } });
    return out.result;
  } catch {
    return null;
  } finally {
    try { localStorage.removeItem(KEY); } catch { /* 위와 같다 */ }
  }
}

export interface InviteSummary {
  code: string;
  joined: number;
  completed: number;
  earned: number;
  signupCredits: number;
  completeCredits: number;
  monthlyCap: number;
  monthlyLeft: number;
}

export function inviteSummary(): Promise<InviteSummary> {
  return appRequest<InviteSummary>("/api/webtoon/v1/my/invite");
}

/** 내 초대 링크. 지금 보고 있는 곳 기준이다. */
export function inviteLink(code: string): string {
  const base = typeof window === "undefined" ? "" : window.location.origin;
  return `${base}/webtoon?ref=${encodeURIComponent(code)}&utm_source=invite&utm_medium=friend`;
}
