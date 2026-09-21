// 히어로 — 기존(만화 데뷔 콜라주) ↔ 적용후(여울 톤) 를 두 변경 단위로 가른다.
//   hero-copy   : 왼쪽 카피 교체("안녕! 같이 키우자!" · 가짜 수치·생성기 배지 삭제)
//   hero-mascot : 오른쪽 콜라주(oc-*.webp) → 여울 마스코트 중앙 + 숨쉬기(±4px)
// 둘 다 ON 이면 방향서 C-1 대로 **중앙정렬 한 줄 히어로**로 합친다.
"use client";

import ImageSlot from "../components/ImageSlot";
import ocSummer from "../assets/oc-summer.webp";
import ocSurprised from "../assets/oc-surprised.webp";
import { useDevFlag } from "../landingDev";
import { C, GAEGU, SANS } from "../tamagotchi/yeoul/ui";
import { YEOUL_MOTION } from "../tamagotchi/constants";

const GREETING = "안녕! 같이 키우자!";
const SUBCOPY = "그림 한 장이면, 내가 그린 아이랑 같이 지낼 수 있어요.";
const CTA_LABEL = "같이 키우러 가기";

/** 여울 마스코트(원본 확정본 v6 hello 포즈). 숨쉬기(zt-ybob)·reduced-motion 정지. */
function YeoulMascot({ size = 240 }: { size?: number }) {
  return (
    <div
      style={{
        width: "min(" + size + "px, 72vw)",
        aspectRatio: "1 / 1",
        margin: "0 auto",
        borderRadius: 28,
        background: C.slot,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
      }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        className="zt-ybob"
        src={YEOUL_MOTION.hello}
        alt="여울이 손 흔드는 모습"
        style={{ width: "86%", height: "86%", objectFit: "contain", display: "block" }}
      />
    </div>
  );
}

function CtaButton({ big = false }: { big?: boolean }) {
  return (
    <a
      href="#make"
      style={{
        textDecoration: "none",
        display: "inline-block",
        fontFamily: GAEGU,
        fontWeight: 700,
        fontSize: big ? 22 : 20,
        background: C.accent,
        color: C.accentInk,
        padding: big ? "15px 38px" : "13px 32px",
        borderRadius: 15,
        boxShadow: "0 4px 12px rgba(156,66,50,.22)",
      }}
    >
      {CTA_LABEL}
    </a>
  );
}

/** 카피 — 적용후(여울 톤). 왼쪽 셀 또는 중앙에 쓴다. */
function CopyNew({ center = false }: { center?: boolean }) {
  return (
    <div style={{ position: "relative", textAlign: center ? "center" : "left" }}>
      <h1
        className="zt-h1"
        style={{
          fontFamily: GAEGU,
          fontWeight: 700,
          lineHeight: 1.2,
          margin: "0 0 14px",
          color: C.ink,
          letterSpacing: "-0.5px",
        }}
      >
        {GREETING}
      </h1>
      <p
        style={{
          fontFamily: SANS,
          fontSize: 16,
          lineHeight: 1.7,
          color: C.sub,
          maxWidth: 400,
          margin: center ? "0 auto 26px" : "0 0 26px",
        }}
      >
        {SUBCOPY}
      </p>
      <CtaButton big={center} />
    </div>
  );
}

/** 카피 — 기존(만화 데뷔). 원본 이식분 그대로. */
function CopyOld() {
  return (
    <div style={{ position: "relative" }}>
      <div
        style={{
          position: "absolute",
          top: -18,
          left: -6,
          width: 150,
          height: 30,
          background: "var(--tape)",
          opacity: 0.78,
          transform: "rotate(-4deg)",
          borderLeft: "1px dashed rgba(0,0,0,.15)",
          borderRight: "1px dashed rgba(0,0,0,.15)",
        }}
      />
      <div
        style={{
          fontFamily: "'Nanum Pen Script'",
          fontSize: 26,
          color: "#8a7a5c",
          transform: "rotate(-1deg)",
        }}
      >
        ✦ 100% 무료 · AI 자캐 만화 생성기 ✦
      </div>
      <h1
        className="zt-h1"
        style={{
          fontFamily: "var(--disp), 'Gaegu'",
          fontWeight: 700,
          lineHeight: 1.18,
          margin: "8px 0 16px",
          letterSpacing: "-1px",
        }}
      >
        내 자캐,
        <br />
        만화로{" "}
        <span style={{ position: "relative", color: "var(--accent)" }}>
          데뷔
          <span
            style={{
              position: "absolute",
              left: -4,
              right: -4,
              bottom: 2,
              height: 12,
              background: "var(--tape)",
              opacity: 0.7,
              zIndex: -1,
              transform: "rotate(-1deg)",
            }}
          />
        </span>
        하다!
      </h1>
      <p
        style={{
          fontFamily: "'Gaegu'",
          fontWeight: 700,
          fontSize: 20,
          lineHeight: 1.65,
          color: "#6a5f4d",
          maxWidth: 400,
          margin: "0 0 28px",
        }}
      >
        이름이랑 성격만 적어줘~
        <br />
        컷 나누기, 작화, 말풍선까지{" "}
        <b style={{ color: "var(--ink)" }}>나머지 컷은 우리가 그려줄게 ✏️</b>
      </p>
      <div style={{ display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" }}>
        <a
          href="#make"
          style={{
            textDecoration: "none",
            fontFamily: "'Gaegu'",
            fontWeight: 700,
            fontSize: 22,
            background: "var(--accent)",
            color: "#fff7ec",
            padding: "14px 30px",
            border: "2.5px solid var(--ink)",
            boxShadow: "5px 5px 0 var(--ink)",
            transform: "rotate(-1.5deg)",
          }}
        >
          ✦ 무료로 만들기 ✦
        </a>
        <a
          href="#result"
          style={{
            textDecoration: "none",
            fontFamily: "'Gaegu'",
            fontWeight: 700,
            fontSize: 17,
            color: "var(--ink)",
            borderBottom: "2.5px solid var(--ink)",
            paddingBottom: 2,
          }}
        >
          완성작 구경 →
        </a>
      </div>
      <div
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 10,
          marginTop: 30,
          background: "#fff",
          border: "2px dashed #c9bda6",
          padding: "9px 16px",
          transform: "rotate(-1deg)",
          boxShadow: "2px 3px 8px rgba(0,0,0,.08)",
        }}
      >
        <span style={{ fontSize: 20 }}>🎟️</span>
        <span style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 15 }}>
          이미 <b style={{ color: "var(--accent)" }}>24,318명</b>의 자캐가 데뷔했어요!
        </span>
      </div>
    </div>
  );
}

/** 오른쪽 비주얼 — 기존(콜라주). 원본 이식분 그대로(oc-*.webp 는 기존 비교용에만 남는다). */
function CollageOld() {
  return (
    <div className="zt-collage" style={{ position: "relative" }}>
      <div
        style={{
          position: "absolute",
          top: 6,
          right: 24,
          width: 250,
          background: "#fff",
          padding: "12px 12px 44px",
          transform: "rotate(4deg)",
          boxShadow: "6px 8px 20px rgba(0,0,0,.2)",
          zIndex: 2,
        }}
      >
        <div
          style={{
            position: "absolute",
            top: -14,
            left: "50%",
            transform: "translateX(-50%) rotate(-4deg)",
            width: 100,
            height: 26,
            background: "var(--tape)",
            opacity: 0.8,
          }}
        />
        <ImageSlot
          src={ocSummer.src}
          placeholder="자캐 일러스트 · 452×540"
          style={{ display: "block", width: "100%", height: 270 }}
        />
        <div
          style={{
            position: "absolute",
            bottom: 10,
            left: 0,
            right: 0,
            textAlign: "center",
            fontFamily: "'Nanum Pen Script'",
            fontSize: 24,
            color: "#5a5040",
          }}
        >
          데뷔하러 왔어 ♡
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          bottom: 24,
          left: 0,
          width: 172,
          background: "#fff",
          padding: 8,
          transform: "rotate(-6deg)",
          boxShadow: "5px 6px 16px rgba(0,0,0,.18)",
          zIndex: 1,
        }}
      >
        <ImageSlot
          src={ocSurprised.src}
          placeholder="클로즈업 컷 · 312×260"
          style={{ display: "block", width: "100%", height: 130 }}
        />
      </div>
      <div
        style={{
          position: "absolute",
          top: 130,
          left: 6,
          fontFamily: "'Black Han Sans'",
          fontSize: 58,
          color: "var(--accent)",
          WebkitTextStroke: "3px var(--ink)",
          paintOrder: "stroke fill",
          transform: "rotate(-10deg)",
          animation: "ztPulse 1.9s ease-in-out infinite",
          zIndex: 3,
        }}
      >
        두근!
      </div>
      <div
        style={{
          position: "absolute",
          top: -6,
          left: 20,
          width: 92,
          height: 92,
          background: "#bfe6a0",
          transform: "rotate(-8deg)",
          boxShadow: "3px 4px 8px rgba(0,0,0,.12)",
          padding: 12,
          fontFamily: "'Nanum Pen Script'",
          fontSize: 19,
          color: "var(--ink)",
          lineHeight: 1.15,
          zIndex: 2,
        }}
      >
        30초면
        <br />
        완성!
        <br />
        진짜임ㅇㅇ
      </div>
      <div
        style={{
          position: "absolute",
          bottom: 0,
          right: 10,
          fontSize: 22,
          transform: "rotate(12deg)",
        }}
      >
        ✦
      </div>
    </div>
  );
}

export default function Hero() {
  const newCopy = useDevFlag("hero-copy");
  const newMascot = useDevFlag("hero-mascot");

  // 둘 다 적용후 → 방향서 C-1 중앙정렬 한 줄 히어로로 합친다.
  if (newCopy && newMascot) {
    return (
      <section
        className="zt-hero-wrap zt-popin"
        style={{ position: "relative", maxWidth: 560, margin: "0 auto" }}
      >
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 24 }}>
          <YeoulMascot size={260} />
          <CopyNew center />
        </div>
      </section>
    );
  }

  // 그 외(부분 적용/기존) → 2단 그리드에서 셀마다 기존/적용후.
  return (
    <section className="zt-hero-wrap" style={{ position: "relative", maxWidth: 1140, margin: "0 auto" }}>
      <div className="zt-hero-grid" style={{ position: "relative", display: "grid", alignItems: "center" }}>
        {newCopy ? <CopyNew /> : <CopyOld />}
        {newMascot ? (
          <div style={{ display: "flex", justifyContent: "center" }}>
            <YeoulMascot />
          </div>
        ) : (
          <CollageOld />
        )}
      </div>
    </section>
  );
}
