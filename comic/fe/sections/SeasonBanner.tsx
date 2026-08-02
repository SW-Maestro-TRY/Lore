import seasonPanorama from "../assets/season-panorama.webp";

const tapeBase = {
  position: "absolute" as const,
  width: 96,
  height: 26,
  background: "var(--tape)",
  opacity: 0.82,
  zIndex: 2,
  pointerEvents: "none" as const,
};

export default function SeasonBanner() {
  return (
    <div className="zt-banner-wrap" style={{ maxWidth: 1180, margin: "0 auto" }}>
      <div
        className="zt-banner-frame"
        style={{
          position: "relative",
          background: "#fff",
          boxShadow: "6px 9px 24px rgba(0,0,0,.18)",
          transform: "rotate(-0.4deg)",
        }}
      >
        {/* 모서리 마스킹테이프 */}
        <div style={{ ...tapeBase, top: -11, left: 26, transform: "rotate(-6deg)" }} />
        <div style={{ ...tapeBase, top: -11, right: 26, transform: "rotate(5deg)" }} />
        <div style={{ ...tapeBase, bottom: 2, left: 40, transform: "rotate(4deg)", background: "#bcd8f0", opacity: 0.7 }} />
        <div style={{ ...tapeBase, bottom: 2, right: 40, transform: "rotate(-5deg)", background: "#bfe6a0", opacity: 0.72 }} />

        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={seasonPanorama.src}
          alt="봄·여름·가을·겨울을 걸어가는 내 자캐"
          style={{ display: "block", width: "100%", height: "auto" }}
        />

        <div
          style={{
            position: "absolute",
            right: 26,
            bottom: 16,
            fontFamily: "'Nanum Pen Script'",
            fontSize: 26,
            color: "#5a5040",
            transform: "rotate(-2deg)",
            zIndex: 3,
            textShadow: "0 1px 3px rgba(255,255,255,.8)",
          }}
        >
          우리 애의 사계절 ♡
        </div>
      </div>
    </div>
  );
}
