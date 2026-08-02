const stepCardBase = {
  width: 250,
  background: "#fff",
  padding: "11px 11px 22px",
  boxShadow: "4px 5px 14px rgba(0,0,0,.12)",
} as const;

const stepThumb = {
  height: 150,
  background:
    "repeating-linear-gradient(-45deg,#eee7d8 0 9px,#f6f0e4 9px 18px)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 38,
} as const;

const stepTitle = {
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 20,
  textAlign: "center",
  marginTop: 12,
} as const;

const stepDesc = {
  fontFamily: "'Gothic A1'",
  fontWeight: 600,
  fontSize: 13,
  color: "#7a6f5c",
  textAlign: "center",
  lineHeight: 1.5,
  margin: "6px 10px 0",
} as const;

const arrow = {
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 34,
  color: "var(--accent)",
  alignSelf: "center",
} as const;

function StepBadge({
  label,
  color,
  rotate,
}: {
  label: string;
  color: string;
  rotate: number;
}) {
  return (
    <div
      style={{
        position: "absolute",
        margin: "-24px 0 0 60px",
        fontFamily: "'Gaegu'",
        fontWeight: 700,
        fontSize: 15,
        background: color,
        color: "#fff7ec",
        border: "2px solid var(--ink)",
        padding: "2px 12px",
        transform: `rotate(${rotate}deg)`,
      }}
    >
      {label}
    </div>
  );
}

export default function HowItWorks() {
  return (
    <section
      id="service"
      className="zt-section-pad"
      style={{ position: "relative" }}
    >
      <div
        className="zt-card-pad"
        style={{
          maxWidth: 1080,
          margin: "0 auto",
          position: "relative",
          background: "var(--card)",
          border: "1px solid #e4dccb",
          boxShadow: "var(--card-sh)",
        }}
      >
        <div
          style={{
            position: "absolute",
            top: -15,
            left: 44,
            width: 150,
            height: 30,
            background: "var(--tape)",
            opacity: 0.78,
            transform: "rotate(-3deg)",
          }}
        />
        <div
          style={{
            position: "absolute",
            top: -15,
            right: 60,
            width: 120,
            height: 30,
            background: "#bcd8f0",
            opacity: 0.7,
            transform: "rotate(4deg)",
          }}
        />
        <div style={{ textAlign: "center", marginBottom: 44 }}>
          <div
            style={{
              fontFamily: "'Nanum Pen Script'",
              fontSize: 26,
              color: "var(--accent)",
            }}
          >
            How it works ✦
          </div>
          <h2
            className="zt-h2"
            style={{
              fontFamily: "var(--disp), 'Gaegu'",
              fontWeight: 700,
              margin: "2px 0 0",
            }}
          >
            딱 세 컷이면 끝!
          </h2>
        </div>
        <div
          style={{
            display: "flex",
            alignItems: "flex-start",
            justifyContent: "center",
            gap: 14,
            flexWrap: "wrap",
          }}
        >
          <div style={{ ...stepCardBase, transform: "rotate(-2deg)" }}>
            <StepBadge label="STEP 1" color="var(--accent)" rotate={-3} />
            <div style={stepThumb}>✍️</div>
            <div style={stepTitle}>자캐 정보 입력</div>
            <p style={stepDesc}>이름·성격·외모·장르. 이미지도 올릴 수 있어요.</p>
          </div>
          <div style={arrow}>→</div>
          <div style={{ ...stepCardBase, transform: "rotate(1.5deg)" }}>
            <StepBadge label="STEP 2" color="#4a6fa5" rotate={2} />
            <div style={stepThumb}>🎬</div>
            <div style={stepTitle}>AI가 컷 작화</div>
            <p style={stepDesc}>표정·앵글·말풍선까지 만화 문법대로.</p>
          </div>
          <div style={arrow}>→</div>
          <div style={{ ...stepCardBase, transform: "rotate(-1.5deg)" }}>
            <StepBadge label="STEP 3" color="#6b8e4e" rotate={-2} />
            <div style={stepThumb}>📖</div>
            <div style={stepTitle}>저장 & 공유</div>
            <p style={stepDesc}>친구·SNS에 자랑! 내 자캐 데뷔 완료.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
