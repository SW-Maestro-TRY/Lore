"use client";

import { useEffect, useRef, useState } from "react";
import type { CSSProperties, MouseEvent } from "react";
import ImageSlot from "../components/ImageSlot";
import { track, trackConversion } from "../lib/analytics";

const FREE_LIMIT = 2;
const KEYWORDS = [
  "로맨스", "학원", "판타지", "일상", "액션", "코미디", "힐링", "스릴러", "무협", "드라마",
  "설렘", "코믹", "긴장", "잔잔", "몽환", "따뜻", "발랄", "시크", "서정", "오싹",
];
const FBTAGS = ["그림체", "대사", "컷 구성", "속도", "더 다양하게", "캐릭터 안 닮음"];

const fieldLabel: CSSProperties = {
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 18,
};

const textInput: CSSProperties = {
  width: "100%",
  marginTop: 6,
  padding: "8px 6px",
  border: "none",
  borderBottom: "2.5px solid var(--ink)",
  background: "transparent",
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 19,
  outline: "none",
  color: "var(--ink)",
};

const resultActionBtn: CSSProperties = {
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 17,
  border: "2.5px solid var(--ink)",
  padding: "10px 22px",
  cursor: "pointer",
  boxShadow: "3px 3px 0 var(--ink)",
};

// 스크랩북 감성의 "필수" 스탬프
function RequiredBadge() {
  return (
    <span
      style={{
        display: "inline-block",
        fontFamily: "'Gaegu'",
        fontWeight: 700,
        fontSize: 11,
        color: "#fff7ec",
        background: "var(--accent)",
        border: "1.5px solid var(--ink)",
        padding: "0 7px",
        marginLeft: 7,
        transform: "rotate(-3deg)",
        verticalAlign: "middle",
      }}
    >
      필수
    </span>
  );
}

const hint: CSSProperties = {
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: 13,
  color: "#9a8f7a",
  marginLeft: 8,
};

const chipStyle = (active: boolean, i: number, small = false): CSSProperties => ({
  fontFamily: "'Gaegu'",
  fontWeight: 700,
  fontSize: small ? 14 : 16,
  padding: small ? "6px 13px" : "7px 15px",
  border: "2px solid var(--ink)",
  cursor: "pointer",
  background: active ? "var(--accent)" : "#fff",
  color: active ? "#fff7ec" : "var(--ink)",
  boxShadow: active ? `${small ? "2px 2px" : "3px 3px"} 0 var(--ink)` : "none",
  transform: `rotate(${active ? -2 : i % 2 ? (small ? 1 : 1.5) : -1}deg)`,
  transition: "all .1s",
});

// 텍스트 입력 + 추천 버튼을 함께 쓰는 태그 선택기
function TagPicker({
  label,
  hintText,
  selected,
  setSelected,
  suggestions,
  placeholder,
}: {
  label: string;
  hintText: string;
  selected: string[];
  setSelected: React.Dispatch<React.SetStateAction<string[]>>;
  suggestions: string[];
  placeholder: string;
}) {
  const [draft, setDraft] = useState("");
  const add = (v: string) => {
    const t = v.trim();
    if (t && !selected.includes(t)) setSelected([...selected, t]);
  };
  const remove = (v: string) => setSelected(selected.filter((x) => x !== v));
  const custom = selected.filter((s) => !suggestions.includes(s));

  return (
    <div>
      <span style={fieldLabel}>
        {label} <span style={hint}>{hintText}</span>
      </span>
      <input
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            add(draft);
            setDraft("");
          }
        }}
        placeholder={placeholder}
        style={{ ...textInput, fontSize: 17, marginTop: 8 }}
      />
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 12 }}>
        {custom.map((c, i) => (
          <button key={`c-${c}`} onClick={() => remove(c)} style={chipStyle(true, i)}>
            {c} ✕
          </button>
        ))}
        {suggestions.map((s, i) => (
          <button
            key={s}
            onClick={() => (selected.includes(s) ? remove(s) : add(s))}
            style={chipStyle(selected.includes(s), i)}
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function CharacterCreator() {
  const [name, setName] = useState("");
  const [desc, setDesc] = useState("");
  const [appearance, setAppearance] = useState("");
  const [uploadImg, setUploadImg] = useState<string | null>(null);
  const [keywords, setKeywords] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false);
  const [result, setResult] = useState({ name: "우리 애", keywords: ["로맨스", "설렘"] });
  const [toastMsg, setToastMsg] = useState("");
  const [genCount, setGenCount] = useState(0);
  const [bonusGrants, setBonusGrants] = useState(0);

  // 필수 미입력 강조용
  const [descTouchedEmpty, setDescTouchedEmpty] = useState(false);
  const [apprTouchedEmpty, setApprTouchedEmpty] = useState(false);

  const [fbStars, setFbStars] = useState(5);
  const [fbText, setFbText] = useState("");
  const [fbEmail, setFbEmail] = useState("");
  const [fbTags, setFbTags] = useState<string[]>([]);
  const [submitted, setSubmitted] = useState(false);

  const genTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (genTimer.current) clearTimeout(genTimer.current);
      if (toastTimer.current) clearTimeout(toastTimer.current);
    };
  }, []);

  const allowed = FREE_LIMIT + bonusGrants;
  const remaining = Math.max(0, allowed - genCount);

  const showToast = (msg: string) => {
    setToastMsg(msg);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToastMsg(""), 2400);
  };

  const generate = () => {
    const descEmpty = !desc.trim();
    const apprEmpty = !appearance.trim();
    if (descEmpty || apprEmpty) {
      setDescTouchedEmpty(descEmpty);
      setApprTouchedEmpty(apprEmpty);
      showToast("한 줄 소개랑 외모 키워드는 꼭 적어줘! 🙏");
      track("generate_blocked", { reason: "required_empty" });
      return;
    }
    if (genCount >= allowed) {
      showToast("무료 2회를 다 썼어요! 아래 '결과물 피드백'을 남기면 1회 더 그려드려요 👇");
      track("generate_blocked", { reason: "limit" });
      return;
    }
    track("generate_click", {
      has_image: !!uploadImg,
      has_keywords: keywords.length > 0,
    });
    const liveName = name.trim() || "우리 애";
    // 입력을 즉시 결과에 반영 → "내 입력이 전달됐다"를 바로 보여준다
    setResult({ name: liveName, keywords: [...keywords] });
    setGenerating(true);
    setGenCount((c) => c + 1);
    // 버튼 자리에 접수 배너가 먼저 뜬 뒤(그 자리 피드백), 살짝 늦게 결과로 스크롤
    if (typeof document !== "undefined") {
      window.setTimeout(
        () =>
          document
            .getElementById("result")
            ?.scrollIntoView({ behavior: "smooth", block: "start" }),
        750,
      );
    }
    if (genTimer.current) clearTimeout(genTimer.current);
    genTimer.current = setTimeout(() => {
      setGenerating(false);
    }, 1600);
  };

  const onUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files && e.target.files[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      setUploadImg(reader.result as string);
      track("image_upload");
    };
    reader.readAsDataURL(f);
  };

  const submitFeedback = () => {
    const text = fbText.trim();
    if (!text) {
      showToast("한 줄 피드백을 적어주세요! 🙏");
      return;
    }
    const email = fbEmail.trim();
    if (email && !email.includes("@")) {
      showToast("이메일 형식을 확인해줘! 📮");
      return;
    }
    // 핵심 전환: 측정 + Supabase 원본 저장(이메일 있으면 type=email = 신청)
    trackConversion("feedback_submit", {
      type: email ? "email" : "feedback",
      stars: fbStars,
      fb_tags: fbTags,
      fb_text: text,
      email: email || null,
      has_email: !!email,
      char_name: name.trim() || null,
      char_desc: desc.trim() || null,
      char_appearance: appearance.trim() || null,
      keywords,
      has_image: !!uploadImg,
    });
    if (email) track("email_submit", { has_image: !!uploadImg });
    setBonusGrants((b) => b + 1);
    setSubmitted(true);
    setFbText("");
    setFbEmail("");
    setFbTags([]);
    setFbStars(5);
    showToast(
      email
        ? "고마워요! 오픈하면 가장 먼저 알려드릴게요 📮 (생성 1회 추가)"
        : "피드백 감사합니다! 생성 1회가 추가됐어요 ✨",
    );
  };

  const toggleFrom = (
    setter: React.Dispatch<React.SetStateAction<string[]>>,
    label: string,
  ) => setter((arr) => (arr.includes(label) ? arr.filter((x) => x !== label) : [...arr, label]));

  const demoToast = () => showToast("데모 버전이에요 — 실제 서비스에서 동작해요!");

  const liftOn = (e: MouseEvent<HTMLElement>) => {
    e.currentTarget.style.transform =
      e.currentTarget.style.transform.replace(/translate\([^)]*\)\s*/, "") +
      " translate(-2px,-2px)";
  };
  const liftOff = (e: MouseEvent<HTMLElement>) => {
    e.currentTarget.style.transform = e.currentTarget.style.transform.replace(
      /\s*translate\(-2px,-2px\)/,
      "",
    );
  };

  const genStatus =
    remaining > 0 ? `무료 생성 ${remaining}회 남음` : "피드백 남기면 1회 더 받아요 🎁";
  const resultTitle = generating ? "그리는 중..." : `${result.name}의 데뷔작`;
  const resultKeywords = result.keywords.length ? result.keywords.join(" · ") : "자유 구성";

  return (
    <>
      {/* ================= INPUT ================= */}
      <section
        id="make"
        className="zt-make-pad"
        style={{ position: "relative", maxWidth: 1000, margin: "0 auto" }}
      >
        <div style={{ textAlign: "center", marginBottom: 38 }}>
          <div style={{ fontFamily: "'Nanum Pen Script'", fontSize: 26, color: "var(--accent)" }}>
            Character Sheet ✎
          </div>
          <h2
            className="zt-h2"
            style={{ fontFamily: "var(--disp), 'Gaegu'", fontWeight: 700, margin: "2px 0 4px" }}
          >
            자캐, 소개해 줘!
          </h2>
          <p style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 17, color: "#6a5f4d", margin: 0 }}>
            성격이랑 외모만 알려주면 돼~ 세부 사항은 골라주면 더 좋아!
          </p>
        </div>

        <div
          className="zt-form-card"
          style={{
            position: "relative",
            backgroundColor: "var(--card)",
            border: "1px solid #e4dccb",
            boxShadow: "var(--card-sh)",
            backgroundImage: "repeating-linear-gradient(var(--card) 0 31px, #ece3d0 31px 32px)",
            display: "grid",
          }}
        >
          <div
            style={{
              position: "absolute",
              top: -16,
              left: 36,
              fontFamily: "'Gaegu'",
              fontWeight: 700,
              fontSize: 18,
              background: "var(--accent)",
              color: "#fff7ec",
              border: "2.5px solid var(--ink)",
              padding: "5px 16px",
              transform: "rotate(-2deg)",
              boxShadow: "3px 3px 0 var(--ink)",
            }}
          >
            📋 프로필 노트
          </div>

          {/* upload polaroid */}
          <div className="zt-form-upload">
            <span style={fieldLabel}>자캐 이미지</span>
            <label
              style={{
                display: "block",
                position: "relative",
                marginTop: 12,
                background: "#fff",
                padding: "11px 11px 40px",
                transform: "rotate(-2deg)",
                boxShadow: "4px 5px 14px rgba(0,0,0,.14)",
                cursor: "pointer",
              }}
            >
              <div
                style={{
                  position: "absolute",
                  top: -13,
                  left: "50%",
                  transform: "translateX(-50%) rotate(-3deg)",
                  width: 96,
                  height: 24,
                  background: "var(--tape)",
                  opacity: 0.82,
                }}
              />
              <input
                type="file"
                accept="image/*"
                onChange={onUpload}
                style={{ position: "absolute", inset: 0, opacity: 0, cursor: "pointer" }}
              />
              <div
                style={{
                  position: "relative",
                  height: 230,
                  overflow: "hidden",
                  background: "repeating-linear-gradient(-45deg,#eee7d8 0 9px,#f6f0e4 9px 18px)",
                }}
              >
                {uploadImg && (
                  <div
                    style={{
                      position: "absolute",
                      inset: 0,
                      backgroundImage: `url(${uploadImg})`,
                      backgroundSize: "cover",
                      backgroundPosition: "center",
                      filter: "grayscale(1) contrast(1.12)",
                    }}
                  />
                )}
                {!uploadImg && (
                  <div
                    style={{
                      position: "absolute",
                      inset: 0,
                      display: "flex",
                      flexDirection: "column",
                      alignItems: "center",
                      justifyContent: "center",
                      gap: 6,
                      textAlign: "center",
                      padding: 14,
                    }}
                  >
                    <div style={{ fontSize: 32 }}>🖼️</div>
                    <span style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 16 }}>
                      여기 콕! 이미지 붙이기
                    </span>
                    <span
                      style={{
                        fontFamily: "'Gothic A1'",
                        fontWeight: 600,
                        fontSize: 12,
                        color: "#9a8f7a",
                        lineHeight: 1.5,
                      }}
                    >
                      있으면 더 닮게 그려줘요
                      <br />
                      (없어도 완전 OK!)
                    </span>
                  </div>
                )}
              </div>
              <div
                style={{
                  position: "absolute",
                  bottom: 11,
                  left: 0,
                  right: 0,
                  textAlign: "center",
                  fontFamily: "'Nanum Pen Script'",
                  fontSize: 22,
                  color: "#5a5040",
                }}
              >
                우리 애 ♡
              </div>
            </label>
            {uploadImg && (
              <button
                onClick={() => setUploadImg(null)}
                style={{
                  marginTop: 14,
                  width: "100%",
                  fontFamily: "'Gaegu'",
                  fontWeight: 700,
                  fontSize: 14,
                  background: "#fff",
                  border: "2px solid var(--ink)",
                  padding: 8,
                  cursor: "pointer",
                }}
              >
                ✕ 다시 붙이기
              </button>
            )}
          </div>

          {/* fields */}
          <div style={{ display: "flex", flexDirection: "column", gap: 22 }}>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 22 }}>
              <label style={{ display: "block" }}>
                <span style={fieldLabel}>이름</span>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="하리, 레이, 루나..."
                  style={textInput}
                />
              </label>
              <label style={{ display: "block" }}>
                <span style={fieldLabel}>
                  한 줄 소개 / 성격 <RequiredBadge />
                </span>
                <input
                  value={desc}
                  onChange={(e) => {
                    setDesc(e.target.value);
                    if (e.target.value.trim()) setDescTouchedEmpty(false);
                  }}
                  placeholder="겉은 도도, 속은 여림"
                  style={{
                    ...textInput,
                    borderBottomColor: descTouchedEmpty ? "var(--accent)" : "var(--ink)",
                  }}
                />
              </label>
            </div>
            <label style={{ display: "block" }}>
              <span style={fieldLabel}>
                외모 키워드 <RequiredBadge />
              </span>
              <input
                value={appearance}
                onChange={(e) => {
                  setAppearance(e.target.value);
                  if (e.target.value.trim()) setApprTouchedEmpty(false);
                }}
                placeholder="은발 단발, 붉은 눈, 후드티"
                style={{
                  ...textInput,
                  borderBottomColor: apprTouchedEmpty ? "var(--accent)" : "var(--ink)",
                }}
              />
            </label>
            <TagPicker
              label="세부 사항"
              hintText="장르·분위기 — 직접 쓰거나 골라도 돼!"
              selected={keywords}
              setSelected={setKeywords}
              suggestions={KEYWORDS}
              placeholder="자유롭게 적어봐! 예: 비 오는 날 옥상에서 마주친 첫사랑"
            />
          </div>

          <div
            style={{
              gridColumn: "1 / -1",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 20,
              flexWrap: "wrap",
              marginTop: 4,
              paddingTop: 22,
              borderTop: "2px dashed #c9bda6",
            }}
          >
            <span
              style={{
                fontFamily: "'Gaegu'",
                fontWeight: 700,
                fontSize: 16,
                color: "var(--ink)",
                background: "var(--tape)",
                border: "2px solid var(--ink)",
                padding: "5px 14px",
                transform: "rotate(-2deg)",
              }}
            >
              {genStatus}
            </span>
            <button
              onClick={generate}
              onMouseEnter={liftOn}
              onMouseLeave={liftOff}
              disabled={generating}
              style={{
                fontFamily: "'Gaegu'",
                fontWeight: 700,
                fontSize: 24,
                color: "#fff7ec",
                background: "var(--accent)",
                border: "2.5px solid var(--ink)",
                boxShadow: "5px 5px 0 var(--ink)",
                padding: "14px 36px",
                cursor: generating ? "default" : "pointer",
                transform: "rotate(-1.5deg)",
                transition: "transform .12s",
              }}
            >
              {generating ? "✏️ 그리는 중..." : "✦ 만화 생성! ✦"}
            </button>
          </div>

          {generating && (
            <div
              onClick={() =>
                document
                  .getElementById("result")
                  ?.scrollIntoView({ behavior: "smooth", block: "start" })
              }
              style={{
                gridColumn: "1 / -1",
                marginTop: 18,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                gap: 10,
                fontFamily: "'Gaegu'",
                fontWeight: 700,
                fontSize: 18,
                color: "#fff7ec",
                background: "var(--accent)",
                border: "2.5px solid var(--ink)",
                boxShadow: "4px 4px 0 var(--ink)",
                padding: "13px 18px",
                cursor: "pointer",
                transform: "rotate(-0.8deg)",
                animation: "ztPop .35s ease-out",
              }}
            >
              <span>✨ ‘{result.name}’ 접수 완료! 아래에서 만화가 그려지고 있어</span>
              <span
                style={{
                  display: "inline-block",
                  fontSize: 22,
                  animation: "ztArrow .8s ease-in-out infinite",
                }}
              >
                ↓
              </span>
            </div>
          )}
        </div>
      </section>

      {/* ================= OUTPUT ================= */}
      <section id="result" className="zt-result-pad" style={{ position: "relative", scrollMarginTop: 80 }}>
        <div style={{ textAlign: "center", marginBottom: 34 }}>
          <div style={{ fontFamily: "'Nanum Pen Script'", fontSize: 26, color: "var(--accent)" }}>
            Your Manga ✦
          </div>
          <h2 className="zt-h2" style={{ fontFamily: "var(--disp), 'Gaegu'", fontWeight: 700, margin: "2px 0 0" }}>
            {resultTitle}
          </h2>
        </div>

        <div
          style={{
            maxWidth: 640,
            margin: "0 auto",
            position: "relative",
            background: "#fff",
            border: "1px solid #e4dccb",
            boxShadow: "var(--card-sh)",
            padding: "24px 24px 22px",
          }}
        >
          <div
            style={{
              position: "absolute",
              top: -16,
              left: "50%",
              transform: "translateX(-50%) rotate(-2deg)",
              width: 160,
              height: 32,
              background: "var(--tape)",
              opacity: 0.8,
            }}
          />
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "0 4px 14px",
              borderBottom: "2px dashed #d8cdb5",
              marginBottom: 18,
            }}
          >
            <span style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 22 }}>
              {`『${result.name}』 `}
              <span style={{ fontSize: 15, color: "#8a7f6a" }}>
                — {resultKeywords}
              </span>
            </span>
            <span style={{ fontFamily: "'Nanum Pen Script'", fontSize: 22, color: "var(--accent)" }}>
              EP.01
            </span>
          </div>

          {generating ? (
            <div
              style={{
                minHeight: 430,
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                gap: 16,
              }}
            >
              <div
                style={{
                  fontFamily: "'Black Han Sans'",
                  fontSize: 52,
                  color: "var(--accent)",
                  WebkitTextStroke: "3px var(--ink)",
                  paintOrder: "stroke fill",
                  animation: "ztShake 0.5s ease-in-out infinite",
                }}
              >
                두구두구두구...
              </div>
              <p style={{ fontFamily: "'Gaegu'", fontWeight: 700, fontSize: 18, color: "#6a5f4d" }}>
                ✏️ {result.name}의 만화 페이지를 그리는 중...
              </p>
            </div>
          ) : (
            <div style={{ display: "flex", justifyContent: "center" }}>
              <div
                style={{
                  position: "relative",
                  width: "min(460px,100%)",
                  aspectRatio: "108/150",
                  transform: "rotate(-0.6deg)",
                }}
              >
                <div
                  style={{
                    position: "absolute",
                    top: -11,
                    left: 30,
                    width: 88,
                    height: 22,
                    background: "var(--tape)",
                    opacity: 0.72,
                    transform: "rotate(-6deg)",
                    zIndex: 3,
                    pointerEvents: "none",
                  }}
                />
                <div
                  style={{
                    position: "absolute",
                    top: -11,
                    right: 30,
                    width: 88,
                    height: 22,
                    background: "var(--tape)",
                    opacity: 0.72,
                    transform: "rotate(5deg)",
                    zIndex: 3,
                    pointerEvents: "none",
                  }}
                />
                <div
                  style={{
                    position: "absolute",
                    inset: 0,
                    border: "4px solid var(--ink)",
                    background: "repeating-linear-gradient(-45deg,#ece4d5 0 9px,#f7f2e8 9px 18px)",
                    boxShadow: "6px 8px 0 rgba(61,53,42,.18)",
                    overflow: "hidden",
                  }}
                >
                  <ImageSlot
                    fit="contain"
                    placeholder="완성된 만화 페이지를 여기에 (권장 1080×1500)"
                    style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }}
                  />
                </div>
              </div>
            </div>
          )}

          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 12,
              marginTop: 20,
              paddingTop: 18,
              borderTop: "2px dashed #d8cdb5",
              flexWrap: "wrap",
            }}
          >
            <button
              onClick={() => {
                track("regenerate_click");
                generate();
              }}
              style={{ ...resultActionBtn, background: "var(--accent)", color: "#fff7ec" }}
            >
              🎲 다시 그리기
            </button>
            <button
              onClick={() => {
                track("result_action", { action: "save" });
                demoToast();
              }}
              style={{ ...resultActionBtn, background: "#fff", color: "var(--ink)" }}
            >
              💾 저장
            </button>
            <button
              onClick={() => {
                track("result_action", { action: "share" });
                demoToast();
              }}
              style={{ ...resultActionBtn, background: "#fff", color: "var(--ink)" }}
            >
              🔗 공유
            </button>
          </div>
        </div>
      </section>

      {/* ================= FEEDBACK ================= */}
      <section id="feedback" className="zt-feedback-pad" style={{ position: "relative" }}>
        <div style={{ maxWidth: 1080, margin: "0 auto" }}>
          <div style={{ textAlign: "center", marginBottom: 32 }}>
            <div style={{ fontFamily: "'Nanum Pen Script'", fontSize: 26, color: "var(--accent)" }}>
              Feedback ✍️
            </div>
            <h2 className="zt-h2" style={{ fontFamily: "var(--disp), 'Gaegu'", fontWeight: 700, margin: "2px 0 6px" }}>
              완성된 웹툰, 어땠어요?
            </h2>
            <p
              style={{
                fontFamily: "'Gaegu'",
                fontWeight: 700,
                fontSize: 17,
                color: "#6a5f4d",
                margin: "0 auto",
                maxWidth: 520,
                lineHeight: 1.5,
              }}
            >
              결과물을 확인했다면 별점과 한 줄 피드백을 남겨주세요. 개발팀이 퀄리티를 높이는 데 큰 힘이 돼요 🙏{" "}
              <b style={{ color: "var(--accent)" }}>남겨주시면 생성 1회 추가!</b>
            </p>
          </div>

          <div
            style={{
              position: "relative",
              maxWidth: 600,
              margin: "0 auto 50px",
              background: "var(--card)",
              border: "2.5px solid var(--ink)",
              boxShadow: "6px 7px 0 var(--ink)",
              padding: "30px 28px",
              transform: "rotate(-0.5deg)",
            }}
          >
            <div
              style={{
                position: "absolute",
                top: -15,
                left: "50%",
                transform: "translateX(-50%) rotate(-2deg)",
                width: 140,
                height: 28,
                background: "var(--tape)",
                opacity: 0.85,
              }}
            />

            {!submitted ? (
              <div>
                <span style={fieldLabel}>웹툰 퀄리티 별점</span>
                <div style={{ display: "flex", gap: 6, margin: "8px 0 20px" }}>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      key={n}
                      onClick={() => setFbStars(n)}
                      style={{
                        fontFamily: "'Gothic A1'",
                        fontSize: 32,
                        lineHeight: 1,
                        background: "none",
                        border: "none",
                        cursor: "pointer",
                        padding: "0 3px",
                        color: n <= fbStars ? "#f2b705" : "#d8cdb5",
                        WebkitTextStroke: "2px var(--ink)",
                        paintOrder: "stroke fill",
                        transition: "color .1s",
                      }}
                    >
                      ★
                    </button>
                  ))}
                </div>
                <span style={fieldLabel}>
                  이런 점은 어땠나요? <span style={{ fontSize: 13, color: "#9a8f7a" }}>(복수 선택)</span>
                </span>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 9, margin: "10px 0 20px" }}>
                  {FBTAGS.map((label, i) => (
                    <button
                      key={label}
                      onClick={() => toggleFrom(setFbTags, label)}
                      style={chipStyle(fbTags.includes(label), i, true)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <span style={fieldLabel}>자유 피드백</span>
                <textarea
                  value={fbText}
                  onChange={(e) => setFbText(e.target.value)}
                  placeholder="어떤 점이 좋았는지, 뭐가 아쉬웠는지 편하게 적어주세요!"
                  style={{
                    width: "100%",
                    height: 92,
                    margin: "8px 0 16px",
                    padding: "12px 14px",
                    border: "2.5px solid var(--ink)",
                    background: "#fff",
                    fontFamily: "'Gaegu'",
                    fontWeight: 700,
                    fontSize: 16,
                    resize: "none",
                    outline: "none",
                  }}
                />
                <span style={fieldLabel}>
                  📮 오픈 소식 받기 <span style={hint}>정식 오픈하면 가장 먼저 알려드려요!</span>
                </span>
                <input
                  type="email"
                  value={fbEmail}
                  onChange={(e) => setFbEmail(e.target.value)}
                  placeholder="이메일 주소 (선택)"
                  style={{
                    width: "100%",
                    margin: "8px 0 8px",
                    padding: "10px 14px",
                    border: "2.5px solid var(--ink)",
                    background: "#fff",
                    fontFamily: "'Gaegu'",
                    fontWeight: 700,
                    fontSize: 15,
                    outline: "none",
                  }}
                />
                <p
                  style={{
                    fontFamily: "'Gothic A1'",
                    fontWeight: 500,
                    fontSize: 11.5,
                    color: "#9a8f7a",
                    lineHeight: 1.5,
                    margin: "0 0 16px",
                  }}
                >
                  신청 시 오픈 알림 발송을 위해 이메일을 수집·이용하며, 수요조사 종료 후 파기합니다
                </p>
                <button
                  onClick={submitFeedback}
                  style={{
                    width: "100%",
                    fontFamily: "'Gaegu'",
                    fontWeight: 700,
                    fontSize: 20,
                    color: "#fff7ec",
                    background: "var(--accent)",
                    border: "2.5px solid var(--ink)",
                    boxShadow: "4px 4px 0 var(--ink)",
                    padding: 13,
                    cursor: "pointer",
                  }}
                >
                  피드백 보내기 → <span style={{ fontSize: 14 }}>(생성 1회 추가)</span>
                </button>
              </div>
            ) : (
              <div style={{ textAlign: "center", padding: "16px 0 6px" }}>
                <div style={{ fontSize: 40 }}>🙌</div>
                <h3
                  style={{
                    fontFamily: "var(--disp), 'Gaegu'",
                    fontWeight: 700,
                    fontSize: 26,
                    margin: "8px 0 6px",
                  }}
                >
                  피드백 고마워요!
                </h3>
                <p
                  style={{
                    fontFamily: "'Gaegu'",
                    fontWeight: 700,
                    fontSize: 16,
                    color: "#6a5f4d",
                    margin: "0 0 20px",
                    lineHeight: 1.5,
                  }}
                >
                  개발팀에 잘 전달됐어요.
                  <br />
                  생성 <b style={{ color: "var(--accent)" }}>1회</b>가 추가됐어요 ✨
                </p>
                <div style={{ display: "flex", gap: 10, justifyContent: "center", flexWrap: "wrap" }}>
                  <a
                    href="#make"
                    style={{
                      textDecoration: "none",
                      fontFamily: "'Gaegu'",
                      fontWeight: 700,
                      fontSize: 16,
                      color: "#fff7ec",
                      background: "var(--accent)",
                      border: "2.5px solid var(--ink)",
                      boxShadow: "3px 3px 0 var(--ink)",
                      padding: "10px 20px",
                    }}
                  >
                    ✏️ 한 번 더 생성하기
                  </a>
                  <button
                    onClick={() => setSubmitted(false)}
                    style={{
                      fontFamily: "'Gaegu'",
                      fontWeight: 700,
                      fontSize: 16,
                      background: "#fff",
                      color: "var(--ink)",
                      border: "2.5px solid var(--ink)",
                      boxShadow: "3px 3px 0 var(--ink)",
                      padding: "10px 20px",
                      cursor: "pointer",
                    }}
                  >
                    피드백 더 남기기
                  </button>
                </div>
              </div>
            )}
          </div>

        </div>
      </section>

      {/* toast */}
      {toastMsg && (
        <div
          style={{
            position: "fixed",
            left: "50%",
            bottom: 30,
            transform: "translateX(-50%)",
            zIndex: 100,
            background: "var(--ink)",
            color: "#fff7ec",
            fontFamily: "'Gaegu'",
            fontWeight: 700,
            fontSize: 17,
            padding: "13px 26px",
            border: "2.5px solid var(--ink)",
            boxShadow: "4px 4px 0 var(--accent)",
          }}
        >
          {toastMsg}
        </div>
      )}
    </>
  );
}
