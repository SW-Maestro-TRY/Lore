'use client';
import { useEffect, type RefObject } from 'react';
import { subscribeViewport, useVisualViewport } from '@common/viewport/useVisualViewport';

/** Keep the egg stage fixed; only the question pane yields to the visual keyboard. */
export function usePersonaViewport(root: RefObject<HTMLDivElement | null>, sheet: RefObject<HTMLDivElement | null>, setKeyboard: (value: boolean) => void, mode: 'onboarding'|'dialog' = 'onboarding') {
  // 보이는 영역 숫자는 공용 훅 한 곳에서 잰다(--vvh·--vvt). 여기서는 그 변화에 맞춰 칸만 굴린다.
  useVisualViewport();
  useEffect(() => {
    const host = root.current;
    const pane = sheet.current;
    if (!host || !pane) return;
    let baseline = window.innerHeight;
    let hostHeight = host.getBoundingClientRect().height;
    let frame = 0;
    const vv = window.visualViewport;
    const update = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const el = document.activeElement;
        const editing = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
        const keyboard = editing && (vv?.height ?? window.innerHeight) < baseline - 100;
        if (!editing && (vv?.height ?? window.innerHeight) >= baseline - 80) {
          baseline = window.innerHeight;
          host.style.height = '';
          host.style.flex = '1 1 auto';
          hostHeight = host.getBoundingClientRect().height;
        }
        host.dataset.small = hostHeight < 600 ? '1' : '0';
        if (keyboard) { host.style.height = `${hostHeight}px`; host.style.flex = 'none'; }
        if(mode==='dialog'){host.style.height='var(--vvh,100dvh)';host.style.top='var(--vvt,0px)';host.style.left='var(--vvl,0px)';host.style.width='var(--vvw,100vw)';}
        setKeyboard(keyboard);
        pane.style.flex = keyboard && mode==='onboarding' ? 'none' : '1 1 auto';
        pane.style.height = keyboard && mode==='onboarding' ? `${Math.max(24, (vv?.offsetTop ?? 0) + (vv?.height ?? window.innerHeight) - pane.getBoundingClientRect().top - 4)}px` : '';
        if (!editing || !host.contains(el)) return;
        let caret = el.getBoundingClientRect().top + el.clientHeight / 2;
        if (el instanceof HTMLTextAreaElement) {
          const mirror = document.createElement('div');
          const css = getComputedStyle(el);
          for (const key of ['font','lineHeight','letterSpacing','padding','boxSizing','wordBreak','overflowWrap'] as const) mirror.style[key] = css[key];
          Object.assign(mirror.style, {position:'fixed',visibility:'hidden',width:`${el.clientWidth}px`,whiteSpace:'pre-wrap'});
          mirror.textContent = el.value.slice(0, el.selectionStart);
          const marker = document.createElement('span'); marker.textContent = '\u200b'; mirror.append(marker); document.body.append(mirror);
          caret = el.getBoundingClientRect().top + marker.offsetTop - el.scrollTop;
          mirror.remove();
        }
        const rect = pane.getBoundingClientRect();
        const bottom = Math.min(rect.bottom, (vv?.offsetTop ?? 0) + (vv?.height ?? window.innerHeight)) - 5;
        if (caret + 22 > bottom) pane.scrollTop += caret + 22 - bottom;
        else if (caret < rect.top + 4) pane.scrollTop -= rect.top + 4 - caret;
        host.dataset.inputSpace = String(Math.round(bottom - rect.top));
      });
    };
    update();
    const observer = new ResizeObserver(update); observer.observe(pane);
    const events = ['focusin','focusout','input','keyup','selectionchange'];
    events.forEach(event => document.addEventListener(event, update));
    const unsubscribe = subscribeViewport(update);
    return () => { observer.disconnect(); cancelAnimationFrame(frame); events.forEach(event => document.removeEventListener(event,update)); unsubscribe(); };
  }, [root, sheet, setKeyboard,mode]);
}
