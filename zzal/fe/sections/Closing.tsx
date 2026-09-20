export default function Closing() {
  return (
    <section
      className="zt-closing-outer"
      style={{ position: "relative", textAlign: "center" }}
    >
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
          style={{
            fontFamily: "var(--disp), 'Gaegu'",
            fontWeight: 700,
            margin: "0 0 12px",
            lineHeight: 1.2,
          }}
        >
          내 자캐, 데뷔시킬 준비 됐어?
        </h2>
        <p
          style={{
            fontFamily: "'Gaegu'",
            fontWeight: 700,
            fontSize: 19,
            margin: "0 0 26px",
            opacity: 0.9,
          }}
        >
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
        <span
          style={{
            fontFamily: "'Nanum Pen Script'",
            fontSize: 30,
            color: "var(--accent)",
          }}
        >
          자캐툰 ✎
        </span>
        <span
          style={{
            fontFamily: "'Gaegu'",
            fontWeight: 700,
            fontSize: 14,
            color: "#8a7f6a",
          }}
        >
          © 2026 자캐툰 · 내 최애는 나의 자캐
        </span>
      </div>
    </section>
  );
}
