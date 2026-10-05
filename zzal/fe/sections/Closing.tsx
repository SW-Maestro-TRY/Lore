// 마감 CTA + 푸터 — 두 변경 단위로 가른다.
//   cta    : "데뷔시킬 준비?"·"무료로 만들기" → "같이 키우러 가기", 하드섀도우·기울임 제거(여울 톤)
//   footer : "자캐툰 ✎" 브랜딩 제거, © 최소 표기
"use client";

import { useDevFlag } from "../landingDev";
import { C, GAEGU, SANS, radius } from "../tamagotchi/yeoul/ui";

/** 기존 CTA 카드 — 하드섀도우·기울임·데뷔 카피. 원본 이식분 그대로. */
function CtaOld() {
  return (
    <div
      className="zt-closing-card"
      style={{
        maxWidth: 760,
        margin: "0 auto",
        position: "relative",
        background: "var(--accent)",
        color: "#fff7ec",
        border: "2.5px solid var(--ink)",
        boxShadow: "8px 8px 0 var(--ink)",
        transform: "rotate(-0.6deg)",
      }}
    >
      <div
        style={{
          position: "absolute",
          top: -16,
          left: "50%",
          transform: "translateX(-50%) rotate(-2deg)",
          width: 150,
          height: 30,
          background: "var(--tape)",
          opacity: 0.85,
        }}
      />
      <h2
        className="zt-closing-h2"
        style={{ fontFamily: "var(--disp), 'Gaegu'", fontWeight: 700, margin: "0 0 12px", lineHeight: 1.2 }}
      >
        내 자캐, 데뷔시킬 준비 됐어?
      </h2>
      <p style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 19, margin: "0 0 26px", opacity: 0.9 }}>
        가입 없이 지금 바로, 무료로 ✦
      </p>
      <a
        href="#make"
        style={{
          textDecoration: "none",
          display: "inline-block",
          fontFamily: "'Gaegu'",
          fontWeight: 700,
          fontSize: 24,
          color: "var(--accent)",
          background: "#fff7ec",
          border: "2.5px solid var(--ink)",
          boxShadow: "5px 5px 0 var(--ink)",
          padding: "15px 40px",
        }}
      >
        ✏️ 무료로 만들기
      </a>
    </div>
  );
}

/** 적용후 CTA 카드 — 여울 톤(잉크 그림자·라운드·회전 없음), "같이 키우러 가기". */
function CtaNew() {
  return (
    <div
      className="zt-closing-card"
      style={{
        maxWidth: 560,
        margin: "0 auto",
        position: "relative",
        background: C.accent,
        color: C.accentInk,
        borderRadius: radius.xl,
        boxShadow: "0 8px 24px rgba(156,66,50,.20)",
        textAlign: "center",
      }}
    >
      <h2
        className="zt-closing-h2"
        style={{ fontFamily: GAEGU, fontWeight: 700, margin: "0 0 10px", lineHeight: 1.2 }}
      >
        우리, 같이 키워볼까?
      </h2>
      <p style={{ fontFamily: SANS, fontSize: 15, margin: "0 0 24px", opacity: 0.92 }}>
        가입 없이 지금 바로 시작할 수 있어요.
      </p>
      <a
        href="#make"
        style={{
          textDecoration: "none",
          display: "inline-block",
          fontFamily: GAEGU,
          fontWeight: 700,
          fontSize: 22,
          color: C.accent,
          background: C.accentInk,
          borderRadius: 15,
          boxShadow: "0 4px 12px rgba(74,64,56,.18)",
          padding: "14px 38px",
        }}
      >
        같이 키우러 가기
      </a>
    </div>
  );
}

/** 기존 푸터 — 자캐툰 브랜딩. */
function FooterOld() {
  return (
    <div
      style={{
        maxWidth: 1080,
        margin: "46px auto 0",
        paddingTop: 22,
        borderTop: "2px dashed #c9bda6",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        flexWrap: "wrap",
        gap: 12,
      }}
    >
      <span style={{ fontFamily: "'Nanum Pen Script'", fontSize: 30, color: "var(--accent)" }}>
        자캐툰 ✎
      </span>
      <span style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 14, color: "#8a7f6a" }}>
        © 2026 자캐툰 · 내 최애는 나의 자캐
      </span>
    </div>
  );
}

/** 적용후 푸터 — 브랜딩 제거, © 최소. */
function FooterNew() {
  return (
    <div
      style={{
        maxWidth: 560,
        margin: "40px auto 0",
        paddingTop: 20,
        borderTop: `1px solid ${C.lineHard}`,
        textAlign: "center",
      }}
    >
      <span style={{ fontFamily: SANS, fontSize: 12.5, color: C.faint }}>© 2026</span>
    </div>
  );
}

export default function Closing() {
  const newCta = useDevFlag("cta");
  const newFooter = useDevFlag("footer");
  return (
    <section className="zt-closing-outer" style={{ position: "relative", textAlign: "center" }}>
      {newCta ? <CtaNew /> : <CtaOld />}
      {newFooter ? <FooterNew /> : <FooterOld />}
    </section>
  );
}
