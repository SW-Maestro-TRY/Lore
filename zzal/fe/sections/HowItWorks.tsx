// 어떻게 노나 — 기존은 "딱 세 컷!" 3열 카드(만화 프레임·안티슬롭 3열). 적용후(how-3step ON)는
// 방향서 C-1 대로 "올리기 → 태어남 → 같이 놀기" 세로 스택으로, 여울 톤(저채도·라운드·잉크 그림자).
"use client";

import { assetUrl } from "../lib/assets";
import { useDevFlag } from "../landingDev";
import { C, GAEGU, SANS, radius } from "../tamagotchi/yeoul/ui";

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
  background: "repeating-linear-gradient(-45deg,#eee7d8 0 9px,#f6f0e4 9px 18px)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontSize: 38,
} as const;

/**
 * 세 걸음 썸네일(기존). 이모지 자리표시자 위에 Codex 손그림을 덮는다. CDN 이 죽으면 이모지가 보인다.
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

function StepBadge({ label, color, rotate }: { label: string; color: string; rotate: number }) {
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

/** 기존 — "딱 세 컷!" 3열 카드. 원본 이식분 그대로. */
function HowOld() {
  return (
    <section id="service" className="zt-section-pad" style={{ position: "relative" }}>
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
        <div style={{ position: "absolute", top: -15, left: 44, width: 150, height: 30, background: "var(--tape)", opacity: 0.78, transform: "rotate(-3deg)" }} />
        <div style={{ position: "absolute", top: -15, right: 60, width: 120, height: 30, background: "#bcd8f0", opacity: 0.7, transform: "rotate(4deg)" }} />
        <div style={{ textAlign: "center", marginBottom: 44 }}>
          <div style={{ fontFamily: "'Nanum Pen Script'", fontSize: 26, color: "var(--accent)" }}>
            How it works ✦
          </div>
          <h2 className="zt-h2" style={{ fontFamily: "var(--disp), 'Gaegu'", fontWeight: 700, margin: "2px 0 0" }}>
            딱 세 컷이면 끝!
          </h2>
        </div>
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "center", gap: 14, flexWrap: "wrap" }}>
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

/** 적용후 — 세로 스택 3컷(올리기 → 태어남 → 같이 놀기), 여울 톤. */
const NEW_STEPS = [
  { emoji: "🖼️", title: "올리기", desc: "내가 그린 그림 한 장을 올려요." },
  { emoji: "🐣", title: "태어남", desc: "그 아이가 여기서 태어나요." },
  { emoji: "🌱", title: "같이 놀기", desc: "밥 주고, 쓰다듬고, 이야기해요." },
] as const;

function HowNew() {
  return (
    <section id="service" className="zt-section-pad" style={{ position: "relative" }}>
      <div style={{ maxWidth: 560, margin: "0 auto" }}>
        <div style={{ textAlign: "center", marginBottom: 22 }}>
          <h2
            className="zt-h2"
            style={{ fontFamily: GAEGU, fontWeight: 700, margin: 0, color: C.ink }}
          >
            이렇게 놀아요
          </h2>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {NEW_STEPS.map((s, i) => (
            <div
              key={s.title}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 16,
                padding: "16px 18px",
                background: C.paper,
                border: `1px solid ${C.lineHard}`,
                borderRadius: radius.lg,
                boxShadow: "0 4px 14px rgba(74,64,56,.08)",
              }}
            >
              <div
                aria-hidden
                style={{
                  flex: "none",
                  width: 56,
                  height: 56,
                  borderRadius: radius.pill,
                  background: C.slot,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 28,
                }}
              >
                {s.emoji}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                  <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 19, color: C.ink }}>
                    {s.title}
                  </span>
                  <span style={{ fontSize: 11, color: C.faint }}>{i + 1}/3</span>
                </div>
                <p style={{ fontFamily: SANS, fontSize: 14, color: C.sub, lineHeight: 1.6, margin: "3px 0 0" }}>
                  {s.desc}
                </p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export default function HowItWorks() {
  const isNew = useDevFlag("how-3step");
  return isNew ? <HowNew /> : <HowOld />;
}
