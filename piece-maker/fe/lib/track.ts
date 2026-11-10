/* 독자의 행동 기록 — 서버 표가 볼 수 없는 "제출 전 행동"과 "결과를 본 뒤 행동"만 남긴다.
 *
 * 무엇을 왜 남기는지는 설계 문서(트래커설정.md)가 정본이다. 공통 수집기(`@common/analytics`)로 보내고,
 * 속성은 수집기가 허용한 키만 쓴다. 독자가 쓴 글(검색어 · 해석 · 주장)은 보내지 않는다 — 있고 없음과 개수만 보낸다.
 * 이벤트 이름과 속성을 한곳에 모아 두려고 화면은 `track()` 을 바로 부르지 않고 여기 함수를 부른다. */
import { track } from "@common/analytics";

/** 카드를 어디서 눌렀나. 목록 · 카드 상세 창 · 작성 패널 · 판정 결과. */
export type CardFrom = "list" | "detail" | "compose" | "result";

let searchSeq = 0;
/** 한 방문에 한 번만 남기는 것. 가설 만들기의 첫 단계와, 이미 남긴 판정 결과. */
const once = new Set<string>();

/** 찾기 한 번의 결과. `seq` 는 이 방문에서 몇 번째 찾기인지다. 검색어는 보내지 않는다. */
export function trackSearch(hasKeywords: boolean, filtered: boolean, count: number): void {
  searchSeq += 1;
  track("piece_maker_search", { has_keywords: hasKeywords, type: filtered ? "kind" : "all", count, seq: searchSeq });
}

/** 카드를 담거나 빼거나 상세를 열었다. */
export function trackCard(action: "add" | "remove" | "detail", from: CardFrom): void {
  track("piece_maker_card", { action, from });
}

/** 가설 만들기의 단계. 첫 카드와 첫 입력은 한 방문에 한 번, 판정 버튼은 누를 때마다 남긴다. */
export function trackCompose(step: "first_card" | "first_input" | "submit"): void {
  if (step !== "submit") {
    if (once.has(step)) return;
    once.add(step);
  }
  track("piece_maker_compose", { step });
}

/** 판정을 맡기지 못했다. 로그인이 없거나, 크레딧이 모자라거나, 서버가 거절했다. */
export function trackSubmitBlocked(reason: "login" | "credit" | "error", code?: string | null): void {
  track("piece_maker_submit_blocked", code ? { reason, code } : { reason });
}

/** 맡기지 않은 초안을 두고 화면을 떠났다. */
export function trackDraftAbandoned(cards: number, hasNote: boolean): void {
  track("piece_maker_draft_abandoned", { count: cards, has_note: hasNote });
}

/** 정상 결과가 실제로 보였다. 계정별 같은 가설은 한 방문에 한 번만 남긴다. 영속 중복 제거는 result-view API가 맡는다. */
export function trackResultViewed(accountId: number, hypothesisId: number): void {
  const key = `result:${accountId}:${hypothesisId}`;
  if (once.has(key)) return;
  once.add(key);
  track("piece_maker_result_viewed", { type: "COMPLETE" });
}

/** 맡긴 뒤의 행동. `type` 은 공유에 쓴 수단(copy · native · SNS 이름)이거나, 새 가설을 시작한 때의 판정 상태다. */
export function trackResultAction(action: "share_open" | "share_done" | "new_draft", type?: string): void {
  track("piece_maker_result_action", type ? { action, type } : { action });
}

/** 피드백 창을 열었다. 판정 결과를 보던 중이면 `result`, 아니면 `home` 이다. 보낸 피드백은 서버 표가 안다. */
export function trackFeedbackOpened(from: "result" | "home"): void {
  track("piece_maker_feedback_opened", { from });
}
