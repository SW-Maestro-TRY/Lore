// 가입·로그인 창을 **여울의 결로 감싸는 겉옷**.
//
// ★★ 왜 창 자체를 안 고치나 — `common/fe/auth/AuthModal.tsx` 는 trailer·webtoon 과 같이 쓰는
//   공통 부품이다. 거기를 손대면 팀원 화면이 같이 바뀐다. 그래서 zzal 이 열었을 때만 켜지는
//   **덮어쓰기 한 겹**으로 해결한다(2026-09-21 G-4 1안).
//
// 하는 일 셋.
//   1) 창이 열려 있는 동안 `<body data-zzal-auth="1">` 를 켠다. 아래 스타일은 그 표시 안에서만 산다.
//   2) 그 범위에서 **CSS 변수만** 여울 값으로 바꾼다. 공통 창은 색을 `var(--card)` 같은 변수로
//      쓰고 있어서, 변수 한 벌만 덮으면 규칙을 하나도 안 건드리고 결이 맞는다.
//      (클래스 이름은 CSS 모듈이라 해시가 붙는다 — 이름으로 집으면 빌드마다 깨진다.)
//   3) 창 **머리에 한 줄과 고른 그림**을 얹는다(A-01).
//
// ★ 왜 한 줄이 필요한가 — 그림을 고르는 순간 가입 창이 화면을 덮는다. 정작 필요한 말
//   ("가입하면 이 그림으로 시작해요")은 이미 있었는데 **창 뒤에 가려** 닫아야만 보였다(실측).
//   전환이 갑작스러운 것은 창의 문제가 아니라 **말이 없는 것**의 문제였다.
'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { C, C2, GAEGU, SANS, fz, gap, radius, shadow } from './ui';

/** 창 머리에 붙는 말. 지킬 수 있는 것만 적는다 — 고른 그림은 실제로 `holdUpload` 가 들고 있다. */
export const AUTH_INTRO = {
  line: '그림 받았어요. 이어서 하려면 로그인이 필요해요',
  note: '고른 그림은 그대로 두었어요',
  alt: '고르신 그림',
} as const;

/**
 * 공통 창의 껍데기(오버레이)를 찾아 **그 안 첫 자리**에 우리 자리를 끼운다.
 *
 * ★ 왜 첫 자리인가 — 뒤에 붙이면 화면 낭독기가 폼을 다 읽은 **뒤에** 이 말을 읽는다. 이 말은
 *   맨 먼저 들려야 하는 말이라 DOM 순서가 곧 뜻이다. (보이는 순서만 CSS 로 바꾸지 않는 이유다.)
 * ★ 남의 컴포넌트가 만든 DOM 에 넣는 것이라, 우리가 만든 노드만 우리가 치운다. 공통 창은
 *   제 자식만 다루므로 앞에 낀 형제를 건드리지 않는다.
 */
function useOverlayHost(open: boolean): HTMLElement | null {
  const [host, setHost] = useState<HTMLElement | null>(null);
  useEffect(() => {
    if (!open) { setHost(null); return; }
    let node: HTMLDivElement | null = null;
    const attach = () => {
      const dialog = document.querySelector('[role="dialog"][aria-modal="true"]');
      const overlay = dialog?.parentElement;
      if (!overlay || node) return true;
      node = document.createElement('div');
      node.dataset.part = 'zzal-auth-intro-host';
      overlay.insertBefore(node, overlay.firstChild);
      /**
       * ★★ 손글씨 변수를 **여기로 옮겨 심는다.**
       *
       * `--font-gaegu`·`--font-gowun` 은 zzal 페이지가 제 `<div>` 에 걸어 둔다
       * (`app/(domains)/zzal/page.tsx`). 그런데 이 창은 `document.body` 로 포털을 쏘므로
       * **그 div 밖**이다 — 변수가 없어 `var(--font-gaegu)` 가 무효가 되고(IACVT),
       * `font-family` 선언이 통째로 죽어 상위 폰트(Noto Sans KR)로 떨어진다.
       * 실측으로 그랬다(2026-09-21: 색·모서리는 바뀌는데 서체만 안 바뀜).
       * 같은 함정이 `/zzal/landing` 머리말에도 적혀 있다 — 두 번째 같은 자리다.
       */
      const src = document.querySelector('.yeoul');
      if (src) {
        const cs = getComputedStyle(src);
        for (const v of ['--font-gaegu', '--font-gowun']) {
          const val = cs.getPropertyValue(v).trim();
          if (val) overlay.style.setProperty(v, val);
        }
      }
      setHost(node);
      return true;
    };
    if (!attach()) { /* 아직 없다 — 아래 관찰자가 붙는 순간을 잡는다 */ }
    const mo = new MutationObserver(() => { if (!node) attach(); });
    mo.observe(document.body, { childList: true, subtree: true });
    return () => {
      mo.disconnect();
      node?.remove();
      node = null;
      setHost(null);
    };
  }, [open]);
  return host;
}

/** 창이 열린 동안에만 `<body data-zzal-auth="1">`. 스타일이 여울 화면 밖으로 새지 않게 하는 표시다. */
function useBodyFlag(open: boolean) {
  useEffect(() => {
    if (!open) return;
    document.body.dataset.zzalAuth = '1';
    return () => { delete document.body.dataset.zzalAuth; };
  }, [open]);
}

/**
 * 여울 겉옷 — 변수만 덮는다.
 *
 * ★ `:has()` 로 오버레이를 집는다(자식이 대화상자인 div). 못 알아듣는 브라우저에서는 바깥
 *   어둠만 기본값으로 남고 **나머지는 그대로 동작한다** — 겉모습뿐이라 기능이 안 깨진다.
 * ★ 높이를 셸 안으로 들인다(A-03). 390x844 에서 창이 759.6px 이라 셸 위로 9.8px 나가 있었다.
 *   줄이지 않고 **넘치면 창 안에서 구르게** 한다 — 글자나 여백을 깎으면 읽기가 나빠진다.
 */
const STYLE = `
body[data-zzal-auth="1"] div:has(> [role="dialog"][aria-modal="true"]){
  flex-direction: column; gap: 0;
}
body[data-zzal-auth="1"] [role="dialog"][aria-modal="true"]{
  --card:${C.paper}; --card-border:${C.line}; --border:${C.lineHard};
  --text:${C.ink}; --text-subtle:${C.sub2}; --muted:${C.faint};
  --font-ko:${SANS}; --font-display:${GAEGU};
  border-radius:0 0 ${radius.xl}px ${radius.xl}px;
  /* ★ margin:auto 를 끈다 — 공통 창은 가운데 정렬을 그 여백으로 하는데, 우리가 세로로
     쌓으면 그 여백이 머리 띠와 창 사이를 벌려 **두 장으로 읽힌다**(실측 121px 틈). */
  margin:0 auto;
  /* 넘치면 창 안에서 구른다. 주 버튼은 공통 창이 이미 바닥에 붙여 두어 늘 보인다(공통 창의 sticky). */
  max-height:min(72dvh, 620px); overflow-y:auto;
}
body[data-zzal-auth="1"] [role="dialog"][aria-modal="true"] h2{ letter-spacing:0; }
.zzal-auth-intro{
  width:100%; max-width:420px; margin:0 auto; box-sizing:border-box;
  display:flex; align-items:center; gap:${gap.lg}px;
  padding:14px 16px; background:${C.accentSoft};
  border:1px solid ${C.accentDim}; border-bottom:none;
  border-radius:${radius.xl}px ${radius.xl}px 0 0;
  box-shadow:${shadow.pop};
}
.zzal-auth-intro img{
  width:52px; height:52px; flex:none; object-fit:contain; display:block;
  background:${C.paper}; border:1px solid ${C.lineSoft}; border-radius:${radius.sm}px;
}
.zzal-auth-intro .zzal-auth-line{ font-family:${GAEGU}; font-size:${fz.lg}px; line-height:1.35; color:${C.ink}; }
.zzal-auth-intro .zzal-auth-note{ font-size:${fz.sm}px; line-height:1.5; color:${C2.accentInk2}; }
`;

export interface AuthSkinProps {
  /** 창이 열려 있는가. `s.authOpen` 그대로. */
  open: boolean;
  /** 고른 그림 미리보기(`live.previewUrl`). 없으면 글만 나온다. */
  thumb: string | null;
  /**
   * 그림을 손에 든 채 가입을 기다리는 중인가(`live.pendingUpload`).
   * ★ 머리줄의 '로그인' 으로 연 창에는 이 말이 안 붙는다 — 그때는 고른 그림이 없고,
   *   "그림 받았어요" 가 거짓이 된다.
   */
  pending: boolean;
}

export default function AuthSkin({ open, thumb, pending }: AuthSkinProps) {
  useBodyFlag(open);
  const host = useOverlayHost(open && pending);
  if (!open) return null;
  return (
    <>
      <style>{STYLE}</style>
      {host && pending && createPortal(
        <div className="zzal-auth-intro" data-part="auth-intro">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {thumb && <img src={thumb} alt={AUTH_INTRO.alt} />}
          <span style={{ display: 'flex', flexDirection: 'column', gap: gap.xs, minWidth: 0 }}>
            <span className="zzal-auth-line">{AUTH_INTRO.line}</span>
            <span className="zzal-auth-note">{AUTH_INTRO.note}</span>
          </span>
        </div>,
        host,
      )}
    </>
  );
}
