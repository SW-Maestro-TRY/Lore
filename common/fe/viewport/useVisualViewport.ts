"use client";

// 보이는 영역(visualViewport) 하나를 화면 전체가 같이 쓰는 곳(2026-10-26).
//
// ★ 왜 한 곳인가 — 키보드 처리가 가입 창(AuthModal)·캐릭터 설정 창(usePersonaViewport)·채팅(FocusedRoom)
//   세 군데에 따로 있었고 셋이 서로 달랐다. 사파리·인스타 인앱(WKWebView)은 키보드가 오르면 화면(레이아웃)
//   높이는 그대로 두고 보이는 영역만 줄여 위로 민다(vv.offsetTop). 채팅은 높이만 따라가서, 줄어든 판이
//   밀린 영역 위로 사라졌다. 이제 숫자는 여기서만 재고, 모두 같은 CSS 변수를 읽는다.
//
// <html> 에 심는 것:
//   --vvh · --vvt · --vvl · --vvw   보이는 영역의 높이·위·왼쪽·폭(px). 핀치 줌 중에는 레이아웃 값.
//   data-vv-keyboard="1"            키보드가 떠 있다고 본다(숫자가 줄었거나, 숫자를 안 알리는 폰에서 글자 칸 포커스)
// 소비자: zzal 셸(.tama-fullscreen)·가입 창 오버레이·캐릭터 설정 창. 하나라도 켜져 있는 동안만 돈다.
//
// `?vvdebug=1` — 왼쪽 위에 숫자 한 줄(inner · vv.h · vv.top · scrollY). 실기기에서 읽어 주실 것.

import { useEffect } from "react";

const KEYBOARD_MIN_PX = 80;
/** 칸 포커스가 빠진 뒤(손가락을 뗀 뒤) 키보드 표시를 끄기 전 기다리는 시간 — 다음·닫기 누름이 빗나가지 않게. */
const HOLD_MS = 350;
const PHONE = "(pointer: coarse) and (max-width: 480px)";

type Listener = () => void;
const listeners = new Set<Listener>();
let users = 0;
let stop: (() => void) | null = null;

function isTextField(el: Element | null): el is HTMLInputElement | HTMLTextAreaElement {
  if (el instanceof HTMLTextAreaElement) return true;
  return el instanceof HTMLInputElement && !["checkbox", "radio", "button", "submit", "file", "range"].includes(el.type);
}

function start(): () => void {
  const root = document.documentElement;
  const vv = window.visualViewport;
  const phoneMq = window.matchMedia(PHONE);
  const debug = new URLSearchParams(window.location.search).get("vvdebug") === "1";
  let debugEl: HTMLDivElement | null = null;
  if (debug) {
    debugEl = document.createElement("div");
    debugEl.dataset.part = "vv-debug";
    debugEl.setAttribute("aria-hidden", "true");
    Object.assign(debugEl.style, {
      position: "fixed", top: "0", left: "0", zIndex: "2147483647", pointerEvents: "none",
      font: "600 10px/1.3 ui-monospace,Menlo,monospace", background: "rgba(255,255,255,.85)",
      color: "#222", padding: "1px 3px",
    });
    document.body.appendChild(debugEl);
  }
  let baseH = window.innerHeight;
  let baseW = window.innerWidth;
  let reports = false;
  let pointerDown = false;
  let holdUntil = 0;
  let gestureAt = 0;
  let typing = false;
  /** 사용자가 누른 직후에 포커스를 받은 칸. 창이 열릴 때 코드로 준 포커스는 iOS 가 키보드를 안 띄우므로 세지 않는다. */
  let userField: Element | null = null;
  let timer = 0;
  let raf = 0;

  const sync = () => {
    if (Math.abs(window.innerWidth - baseW) > 40) { baseW = window.innerWidth; baseH = window.innerHeight; }
    baseH = Math.max(baseH, window.innerHeight);
    const zoomed = vv ? vv.scale > 1.01 : false;
    const h = vv && !zoomed ? vv.height : window.innerHeight;
    const top = vv && !zoomed ? vv.offsetTop : 0;
    const left = vv && !zoomed ? vv.offsetLeft : 0;
    const w = vv && !zoomed ? vv.width : window.innerWidth;
    root.style.setProperty("--vvh", `${Math.round(h)}px`);
    root.style.setProperty("--vvt", `${Math.round(top)}px`);
    root.style.setProperty("--vvl", `${Math.round(left)}px`);
    root.style.setProperty("--vvw", `${Math.round(w)}px`);
    const visualShrunk = !zoomed && window.innerHeight - h > KEYBOARD_MIN_PX;
    const windowShrunk = baseH - window.innerHeight > KEYBOARD_MIN_PX;
    if (visualShrunk || windowShrunk) reports = true;
    const holding = pointerDown || Date.now() < holdUntil;
    const active = document.activeElement;
    const focused = isTextField(active) && active === userField;
    // 손가락이 닿아 있거나 막 뗀 동안은 지금 상태를 그대로 둔다.
    if (!holding) typing = focused;
    else if (focused) typing = true;
    // 숫자로 알리는 브라우저면 숫자를, 안 알리는 폰(일부 안드로이드 인앱)이면 포커스를 믿는다.
    const keyboard = visualShrunk || windowShrunk || (phoneMq.matches && typing && !reports);
    if (keyboard) root.dataset.vvKeyboard = "1";
    else delete root.dataset.vvKeyboard;
    if (debugEl) {
      debugEl.textContent = `inner=${window.innerHeight} vv.h=${Math.round(h)} vv.top=${Math.round(top)} ` +
        `scrollY=${Math.round(window.scrollY)} kb=${keyboard ? 1 : 0}`;
    }
    listeners.forEach((l) => l());
  };
  const onViewport = () => {
    if (raf) return;
    raf = requestAnimationFrame(() => { raf = 0; sync(); });
  };
  const later = () => { window.clearTimeout(timer); timer = window.setTimeout(sync, HOLD_MS + 20); };
  const onFocusIn = (e: FocusEvent) => {
    if (isTextField(e.target as Element) && Date.now() - gestureAt < 1000) userField = e.target as Element;
    onViewport();
    // 폰에서는 칸으로 문서를 밀어 올린 것을 되돌린다(셸이 보이는 영역을 따라가므로 문서는 늘 0).
    if (phoneMq.matches && isTextField(e.target as Element)) {
      requestAnimationFrame(() => window.scrollTo(0, 0));
      window.setTimeout(() => { window.scrollTo(0, 0); sync(); }, 320);
    }
  };
  const onFocusOut = () => { holdUntil = Date.now() + HOLD_MS; later(); };
  const onPointerDown = () => { pointerDown = true; gestureAt = Date.now(); };
  const onKeyDown = () => { gestureAt = Date.now(); };
  const onPointerUp = () => {
    if (!pointerDown) return;
    pointerDown = false;
    holdUntil = Date.now() + HOLD_MS;
    later();
  };
  sync();
  document.addEventListener("focusin", onFocusIn);
  document.addEventListener("focusout", onFocusOut);
  window.addEventListener("pointerdown", onPointerDown, true);
  window.addEventListener("keydown", onKeyDown, true);
  window.addEventListener("pointerup", onPointerUp, true);
  window.addEventListener("pointercancel", onPointerUp, true);
  vv?.addEventListener("resize", onViewport);
  vv?.addEventListener("scroll", onViewport);
  window.addEventListener("resize", onViewport);
  phoneMq.addEventListener("change", onViewport);
  return () => {
    window.clearTimeout(timer);
    cancelAnimationFrame(raf);
    document.removeEventListener("focusin", onFocusIn);
    document.removeEventListener("focusout", onFocusOut);
    window.removeEventListener("pointerdown", onPointerDown, true);
    window.removeEventListener("keydown", onKeyDown, true);
    window.removeEventListener("pointerup", onPointerUp, true);
    window.removeEventListener("pointercancel", onPointerUp, true);
    vv?.removeEventListener("resize", onViewport);
    vv?.removeEventListener("scroll", onViewport);
    window.removeEventListener("resize", onViewport);
    phoneMq.removeEventListener("change", onViewport);
    debugEl?.remove();
    for (const v of ["--vvh", "--vvt", "--vvl", "--vvw"]) root.style.removeProperty(v);
    delete root.dataset.vvKeyboard;
  };
}

/** 보이는 영역이 바뀔 때마다(한 프레임에 한 번) 부른다. 훅이 켜져 있는 동안만 온다. */
export function subscribeViewport(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** 켜 두는 동안 <html> 에 --vvh·--vvt 등을 심는다. 여러 곳이 같이 켜도 측정은 한 벌이다. */
export function useVisualViewport(enabled = true): void {
  useEffect(() => {
    if (!enabled) return;
    users += 1;
    if (users === 1) stop = start();
    return () => {
      users -= 1;
      if (users === 0) { stop?.(); stop = null; }
    };
  }, [enabled]);
}
