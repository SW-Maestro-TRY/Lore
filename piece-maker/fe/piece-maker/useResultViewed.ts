/* 정상 판정 열람(F12) — 결과를 받은 시각과 실제로 보인 시각을 구분한다.
 * 화면 안·보이는 탭·가림 없는 상태에서만 기록하고, 최초 계정 열람의 영속 중복 제거는 서버가 맡는다. */
import { useEffect, useRef, type RefObject } from "react";
import { ApiError } from "@common/api/client";
import { recordResultView } from "../lib/api";
import { trackResultViewed } from "../lib/track";
import type { AccountRequests } from "./useAccountRequests";
import { sendMetaPixelEvent } from "../lib/meta-pixel";

const RETRY_DELAYS = [1_000, 3_000];
type Delivery = { failures: number; nextAttemptAt: number; done: boolean };

function isRendered(element: Element): boolean {
  if (!element.getClientRects().length) return false;
  for (let current: Element | null = element; current; current = current.parentElement) {
    const style = getComputedStyle(current);
    if (style.display === "none" || style.visibility !== "visible" || Number(style.opacity) === 0) return false;
  }
  return true;
}

/** 포털로 열린 공용 로그인 창도 포함한다. 모달이 있는 동안 배경 결과는 열람으로 세지 않는다. */
function hasBlockingDialog(): boolean {
  return [...document.querySelectorAll('dialog[open], [aria-modal="true"]')].some(isRendered);
}

export function useResultViewed(
  resultRef: RefObject<HTMLDivElement | null>,
  hypothesisId: number | null,
  { begin, version }: AccountRequests,
  blocked: boolean,
): void {
  const deliveries = useRef(new Map<string, Delivery>());

  useEffect(() => {
    // 결과 상자의 여백만 보인 경우를 세지 않도록 실제 판정 문구를 관찰한다.
    const element = resultRef.current?.querySelector('[data-part="judge-grade"]');
    const owner = version.viewer;
    if (!element || hypothesisId === null || typeof owner !== "number" || blocked) return;
    const key = `${owner}:${hypothesisId}`;
    const delivery = deliveries.current.get(key) ?? { failures: 0, nextAttemptAt: 0, done: false };
    deliveries.current.set(key, delivery);
    if (delivery.done || delivery.failures > RETRY_DELAYS.length) return;

    let active = true;
    let intersection: IntersectionObserverEntry | null = null;
    let frame = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let request: ReturnType<typeof begin> | null = null;

    const visible = () => {
      if (!active || !version.active || document.visibilityState !== "visible" || !intersection?.isIntersecting || !isRendered(element) || hasBlockingDialog()) return false;
      // 완전히 보이는 요소가 움직이면 IO 임계값을 넘지 않을 수 있어 현재 좌표로 다시 자른다.
      const rect = element.getBoundingClientRect();
      let left = Math.max(rect.left, 0);
      let right = Math.min(rect.right, window.innerWidth);
      let top = Math.max(rect.top, 0);
      let bottom = Math.min(rect.bottom, window.innerHeight);
      for (let parent = element.parentElement; parent; parent = parent.parentElement) {
        const style = getComputedStyle(parent);
        const bounds = parent.getBoundingClientRect();
        if (style.overflowX !== "visible") { left = Math.max(left, bounds.left); right = Math.min(right, bounds.right); }
        if (style.overflowY !== "visible") { top = Math.max(top, bounds.top); bottom = Math.min(bottom, bounds.bottom); }
      }
      if (right <= left || bottom <= top) return false;
      // 고정 헤더나 모달 외 덮개가 실제 결과를 가리는 경우도 제외한다.
      return [0.25, 0.5, 0.75].some(ratio => {
        const hit = document.elementFromPoint((left + right) / 2, top + (bottom - top) * ratio);
        return hit !== null && element.contains(hit);
      });
    };

    const check = () => {
      if (timer !== null) { clearTimeout(timer); timer = null; }
      if (delivery.done || request || delivery.failures > RETRY_DELAYS.length || !visible()) return;
      const delay = delivery.nextAttemptAt - Date.now();
      if (delay > 0) { timer = setTimeout(check, delay); return; }
      const account = begin();
      if (account.scope !== version || !account.isCurrent()) { account.finish(); return; }
      trackResultViewed(owner, hypothesisId);
      request = account;
      void recordResultView(hypothesisId, account.signal).then(response => {
        if (!active || !account.isCurrent()) return;
        delivery.done = true;
        // 내부 저장 성공을 먼저 확정한다. Meta 실패 때문에 F12를 재시도하거나 판정을 막지 않는다.
        // 실제 열람은 요청 전에 확인했다. SDK를 기다리는 동안 스크롤한 사실로 열람을 취소하지 않는다.
        if (response.metaEvent) void sendMetaPixelEvent(response.metaEvent,
          () => active && account.isCurrent());
      }).catch(error => {
        if (!active || !account.isCurrent()) return;
        // 모달·계정 변경으로 취소한 요청은 실패 횟수에서 제외한다. 실제 오류 응답만 재시도 한도를 쓴다.
        delivery.failures += 1;
        const temporary = error instanceof TypeError || (error instanceof ApiError && (error.status >= 500 || error.status === 408 || error.status === 429));
        if (temporary && delivery.failures <= RETRY_DELAYS.length) {
          delivery.nextAttemptAt = Date.now() + RETRY_DELAYS[delivery.failures - 1];
        } else {
          delivery.failures = RETRY_DELAYS.length + 1;
        }
      }).finally(() => {
        account.finish();
        if (request === account) request = null;
        if (active && account.isCurrent()) check();
      });
    };
    const schedule = () => {
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => { frame = 0; check(); });
    };
    const observer = new IntersectionObserver(entries => {
      intersection = entries[0] ?? null;
      schedule();
    });
    observer.observe(element);
    // AuthModal은 PieceMaker 바깥의 body 포털이다. 열림·닫힘 및 CSS 가림 변화도 관찰한다.
    const mutations = new MutationObserver(schedule);
    mutations.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ["open", "aria-modal", "hidden", "style", "class", "data-view"] });
    document.addEventListener("visibilitychange", schedule);
    window.addEventListener("scroll", schedule, true);
    window.addEventListener("resize", schedule);
    return () => {
      active = false;
      observer.disconnect();
      mutations.disconnect();
      document.removeEventListener("visibilitychange", schedule);
      window.removeEventListener("scroll", schedule, true);
      window.removeEventListener("resize", schedule);
      cancelAnimationFrame(frame);
      if (timer !== null) clearTimeout(timer);
      request?.cancel();
    };
  }, [begin, blocked, hypothesisId, resultRef, version]);
}
