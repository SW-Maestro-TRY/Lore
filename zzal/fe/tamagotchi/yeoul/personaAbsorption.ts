import {C,fz,ink} from './ui';
const FLY_MS=420,STAGGER_MS=60,OVERLAP_MS=100;
export const reducedMotion = () => {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
};

export const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** 위로 휘는 곡선 위의 한 점(2차 베지에). 조절점은 출발·도착의 위쪽. */
function curve(dx: number, dy: number, t: number): [number, number] {
  const cx = dx * 0.35;
  const cy = Math.min(0, dy) - 70;
  const u = 1 - t;
  return [2 * u * t * cx + t * t * dx, 2 * u * t * cy + t * t * dy];
}

/**
 * 칩·말 조각 사본을 알까지 날린다. 끝나면 사본을 지운다.
 * @returns 마지막 사본이 알에 닿기 0.1초 전(= 알 반응을 시작할 때)에 풀리는 약속
 */
export function flyPieces(pieces: { rect: DOMRect; label: string }[], to: DOMRect): Promise<void> {
  const layer = document.createElement('div');
  layer.setAttribute('data-part', 'pe-fly');
  layer.setAttribute('aria-hidden', 'true');
  layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2100;overflow:hidden;';
  document.body.appendChild(layer);
  const tx = to.left + to.width / 2;
  const ty = to.top + to.height * 0.55;
  pieces.forEach((p, i) => {
    const el = document.createElement('span');
    el.textContent = p.label;
    el.style.cssText = [
      'position:absolute', `left:${p.rect.left}px`, `top:${p.rect.top}px`,
      `height:${Math.min(p.rect.height, 44)}px`, 'display:flex', 'align-items:center', 'padding:0 14px',
      `border-radius:999px`, `border:2px solid ${C.accent}`, `background:${C.accentSoft}`, `color:${C.accent}`,
      `font-size:${fz.md}px`, 'white-space:nowrap', 'will-change:transform,opacity', 'transform-origin:50% 50%',
      `box-shadow:0 4px 12px ${ink(.14)}`,
    ].join(';');
    layer.appendChild(el);
    const w = el.getBoundingClientRect().width;
    const h = el.getBoundingClientRect().height;
    const dx = tx - (p.rect.left + w / 2);
    const dy = ty - (p.rect.top + h / 2);
    const frames: Keyframe[] = [];
    const N = 10;
    for (let k = 0; k <= N; k += 1) {
      const t = k / N;
      const [x, y] = curve(dx, dy, t);
      const sc = 1 - 0.65 * t;
      // 끝 0.1초(= 0.42 의 약 24%)에 투명해진다.
      const op = t < 0.76 ? 1 : Math.max(0, 1 - (t - 0.76) / 0.24);
      frames.push({ offset: t, transform: `translate(${x}px,${y}px) scale(${sc})`, opacity: op });
    }
    el.animate(frames, { duration: FLY_MS, delay: i * STAGGER_MS, easing: 'cubic-bezier(.5,0,.75,0)', fill: 'both' });
  });
  const lastStart = (pieces.length - 1) * STAGGER_MS;
  setTimeout(() => layer.remove(), lastStart + FLY_MS + 40);
  return wait(Math.max(0, lastStart + FLY_MS - OVERLAP_MS));
}

export async function reactPersona(actor:HTMLElement|null,halo:HTMLElement|null,base=.5){
 if(reducedMotion()){halo?.animate([{opacity:base},{opacity:.85},{opacity:base}],{duration:150});await wait(150);return;}
 actor?.animate([{transform:'scale(1,1)'},{transform:'scale(1.05,.92)',offset:.35},{transform:'scale(.98,1.05)',offset:.7},{transform:'scale(1,1)'}],{duration:250,easing:'ease-out'});
 halo?.animate([{opacity:base,transform:'scale(1)'},{opacity:.95,transform:'scale(1.16)',offset:.4},{opacity:base,transform:'scale(1)'}],{duration:250,easing:'ease-out'});await wait(250);
}
