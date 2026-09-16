import { assetUrl } from "../lib/assets";

const stepCardBase = {
  width: 250,
  background: "#fff",
  padding: "11px 11px 22px",
  boxShadow: "4px 5px 14px rgba(0,0,0,.12)",
} as const;

const stepThumb = {
  position: "relative",
  height: 150,
  overflow: "hidden",
  background:
    "repeating-linear-gradient(-45deg,#eee7d8 0 9px,#f6f0e4 9px 18px)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 38,
} as const;

/**
 * 세 걸음 썸네일. 이모지 자리표시자 대신 Codex(무과금)로 그린 손그림을 CDN 에서 얹는다.
 *
 * ★ 이모지를 **뒤에** 깔고 그림을 그 위에 덮는다(그림은 불투명). CDN 이 죽어 그림이 안 뜨면
 *   뒤의 이모지가 그대로 보여, JS 없이도(이 섹션은 서버 컴포넌트) 안내가 무너지지 않는다.
 */
function StepThumb({ imgKey, emoji, alt }: { imgKey: string; emoji: string; alt: string }) {
  return (
    <div style={stepThumb}>
      <span aria-hidden style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 38 }}>{emoji}</span>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={assetUrl(imgKey)}
        alt={alt}
        style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", display: "block" }}
      />
    </div>
  );
}

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
            <StepThumb imgKey="zzal/landing/step_write.webp" emoji="✍️" alt="자캐 정보를 적는 모습" />
            <div style={stepTitle}>자캐 정보 입력</div>
            <p style={stepDesc}>이름·성격·외모·장르. 이미지도 올릴 수 있어요.</p>
          </div>
          <div style={arrow}>→</div>
          <div style={{ ...stepCardBase, transform: "rotate(1.5deg)" }}>
            <StepBadge label="STEP 2" color="#4a6fa5" rotate={2} />
            <StepThumb imgKey="zzal/landing/step_draw.webp" emoji="🎬" alt="AI가 만화 컷을 그리는 모습" />
            <div style={stepTitle}>AI가 컷 작화</div>
            <p style={stepDesc}>표정·앵글·말풍선까지 만화 문법대로.</p>
          </div>
          <div style={arrow}>→</div>
          <div style={{ ...stepCardBase, transform: "rotate(-1.5deg)" }}>
            <StepBadge label="STEP 3" color="#6b8e4e" rotate={-2} />
            <StepThumb imgKey="zzal/landing/step_share.webp" emoji="📖" alt="완성한 만화를 저장하고 공유하는 모습" />
            <div style={stepTitle}>저장 & 공유</div>
            <p style={stepDesc}>친구·SNS에 자랑! 내 자캐 데뷔 완료.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
