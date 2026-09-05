import ImageSlot from "../components/ImageSlot";
import ocSummer from "../assets/oc-summer.webp";
import ocSurprised from "../assets/oc-surprised.webp";

export default function Hero() {
  return (
    <section
      className="zt-hero-wrap"
      style={{
        position: "relative",
        maxWidth: 1140,
        margin: "0 auto",
      }}
    >
      <div
        className="zt-hero-grid"
        style={{
          position: "relative",
          display: "grid",
          alignItems: "center",
        }}
      >
        {/* ---- 왼쪽: 카피 ---- */}
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
          <div
            style={{
              display: "flex",
              gap: 16,
              alignItems: "center",
              flexWrap: "wrap",
            }}
          >
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
              이미 <b style={{ color: "var(--accent)" }}>24,318명</b>의 자캐가
              데뷔했어요!
            </span>
          </div>
        </div>

        {/* ---- 오른쪽: 콜라주 클러스터 ---- */}
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
      </div>
    </section>
  );
}
