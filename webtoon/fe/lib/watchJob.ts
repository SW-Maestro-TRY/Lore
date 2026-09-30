/* 지금 지켜보는 작업 — 진행 화면을 떠나도 떠 있는 동그라미(ui/RunningBubble)가 이걸 본다(#507).
 *
 * 진행 화면이 열릴 때 적고, 사람이 결과를 확인했거나(완성본으로 감 · 실패 화면을 봄 · 중단)
 * 동그라미를 닫으면 지운다. 서버의 「만들던 작업」 목록(`/nh/jobs/mine`)은 끝난 작업을
 * 안 돌려주므로, 둘러보는 사이에 완성된 것을 「완성됐어요」로 알려 주려면 여기에 남아
 * 있어야 한다. */
const KEY = "lore_wt_watch_job";

export function watchedJob(): string | null {
  try { return localStorage.getItem(KEY); } catch { return null; }
}

export function watchJob(id: string): void {
  try { localStorage.setItem(KEY, id); } catch { /* 못 적으면 서버 목록으로 찾는다 */ }
}

/** 이 작업을 지켜보던 중이면 지운다. 다른 작업을 지켜보는 중이면 그대로 둔다. */
export function unwatchJob(id: string): void {
  try { if (localStorage.getItem(KEY) === id) localStorage.removeItem(KEY); } catch { /* 없던 셈 */ }
}
