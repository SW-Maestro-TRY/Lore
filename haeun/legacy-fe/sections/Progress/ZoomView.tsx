"use client";

import { useEffect, useRef, useState } from "react";

/* 그림을 눌러 크게 보기.
 *
 * 원본 nh-review.js 의 openZoom 과 같은 동작이다 — 열면 화면을 꽉 채우고,
 * 한 번 더 누를 때마다 2·3·4배, 그 다음엔 다시 꽉 채움. 확대한 상태에서는
 * 끌어서 옮긴다.
 *
 * **내려받기는 여전히 막혀 있다.** 화면에 뜬 그림을 오른쪽 눌러 저장하면
 * LORE 표시 없이 나가므로, 가져가는 길은 「이미지로 뽑기」·「내려받기」로
 * 모은다. 크게 보는 것과 가져가는 것은 다른 일이다.
 */

const ZOOM_STEPS = [2, 3, 4];

export default function ZoomView({
  src,
  alt,
  onClose,
}: {
  src: string;
  alt: string;
  onClose: () => void;
}) {
  const boxRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const [step, setStep] = useState(-1);          // -1 = 꽉 채움
  const drag = useRef<{ x: number; y: number; sl: number; st: number } | null>(null);
  const movedPx = useRef(0);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    document.body.classList.add("nh-zoom-on");
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.classList.remove("nh-zoom-on");
    };
  }, [onClose]);

  /* 배율을 한 칸 올린다. 누른 자리가 화면 가운데로 오게 스크롤을 맞춘다 —
     안 맞추면 확대할 때마다 그림 왼쪽 위로 튀어서, 보고 있던 곳을 매번
     다시 찾아야 한다. */
  function zoomTo(next: number, atX: number, atY: number) {
    const box = boxRef.current, img = imgRef.current;
    if (!box || !img) return;
    const before = img.getBoundingClientRect();
    const fx = before.width ? (atX - before.left) / before.width : 0.5;
    const fy = before.height ? (atY - before.top) / before.height : 0.5;

    setStep(next);
    requestAnimationFrame(() => {
      if (next < 0) {
        box.scrollTo(0, 0);
        return;
      }
      const after = img.getBoundingClientRect();
      box.scrollTo(
        Math.max(0, fx * after.width + box.scrollLeft - box.clientWidth / 2),
        Math.max(0, fy * after.height + box.scrollTop - box.clientHeight / 2),
      );
    });
  }

  const big = step >= 0;
  const hint = !big
    ? "눌러서 더 크게 · 바깥을 누르면 닫힙니다"
    : step === ZOOM_STEPS.length - 1
      ? "끌어서 옮기기 · 누르면 처음 크기로"
      : "끌어서 옮기기 · 눌러서 더 크게";

  return (
    <div
      ref={boxRef}
      className={`nh-zoom${big ? " is-big" : ""}${drag.current ? " is-panning" : ""}`}
      style={big ? ({ ["--nh-zoom" as string]: String(ZOOM_STEPS[step]) } as React.CSSProperties) : undefined}
      onClick={(e) => {
        // 바깥이나 ✕ 를 누르면 닫는다. 그림 위 누르기는 아래에서 처리한다.
        if (e.target !== imgRef.current) onClose();
      }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        ref={imgRef}
        src={src}
        alt={alt}
        draggable={false}
        onPointerDown={(e) => {
          movedPx.current = 0;
          if (!big || !boxRef.current) return;
          drag.current = {
            x: e.clientX, y: e.clientY,
            sl: boxRef.current.scrollLeft, st: boxRef.current.scrollTop,
          };
          e.currentTarget.setPointerCapture(e.pointerId);
          e.preventDefault();
        }}
        onPointerMove={(e) => {
          const d = drag.current, box = boxRef.current;
          if (!d || !box) return;
          const dx = e.clientX - d.x, dy = e.clientY - d.y;
          movedPx.current = Math.max(movedPx.current, Math.abs(dx) + Math.abs(dy));
          box.scrollTo(d.sl - dx, d.st - dy);
        }}
        onPointerUp={() => { drag.current = null; }}
        onPointerCancel={() => { drag.current = null; }}
        onClick={(e) => {
          // 끌고 나서 손을 뗀 것은 누른 것이 아니다 — 안 그러면 손이 살짝
          // 흔들린 것만으로 배율이 바뀐다.
          if (movedPx.current > 6) { movedPx.current = 0; return; }
          zoomTo(step + 1 >= ZOOM_STEPS.length ? -1 : step + 1, e.clientX, e.clientY);
        }}
      />
      {big && <p className="nh-zoom-scale">{ZOOM_STEPS[step]}배</p>}
      <button type="button" className="nh-zoom-close" aria-label="닫기" onClick={onClose}>
        ✕
      </button>
      <p className="nh-zoom-hint">{hint}</p>
    </div>
  );
}
