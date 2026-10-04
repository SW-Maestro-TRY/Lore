"use client";

/* 첫 화면의 「편집실」 목업 — 니즈 카드와 「약속 셋」이 같이 쓴다.
 * 보드의 f0/f1/f2 층: 0 = 페이지 + 캐릭터 시트, 1 = 장면 확인(장면 칸 + 그 장), 2 = 장면 하나 다시 그리기.
 * `s` 는 보드의 배율(PC 약속 1 · PC 니즈 0.9 · 폰 약속 0.8 · 폰 니즈 0.7). */
import "./i18n";
import { useT } from "../../lib/i18n";
import { IconDownload, IconRetry, IconShare } from "../../ui/Icons";

/* 첫 화면(온보딩)에 뜨는 그림은 **디자인 캔버스에 올라간 바로 그 파일**이다.
 * 아무 예시 그림이나 끌어다 쓰면 화면이 캔버스와 달라진다 — 예전에 시트는
 * 다른 캐릭터, 페이지는 목업 장면, 다시 그리기는 몽이 컷이 들어가 있었다. */
export const PAGE_IMG = "/static/samples/onboarding-page.jpg";
export const SHEET_IMG = "/static/samples/onboarding-sheet.png";
export const REGEN_IMG = "/static/samples/onboarding-regen.jpg";
/* 약속 셋 목업은 장면 확인과 같은 공개 작품 「대기실 밖에서는」: 01 = 마지막 장 + 시트, 03 = 5장의 한 컷. */
const MOCK_PAGE_IMG = "/static/samples/onboarding-idol-last.jpg";
const MOCK_REDO_IMG = "/static/samples/onboarding-idol-redo.jpg";
const MOCK_PREV_IMG = "/static/samples/onboarding-idol-prev.jpg";
const MOCK_NEXT_IMG = "/static/samples/onboarding-idol-next.jpg";
export const CUT_IMG = "/static/samples/ex-mongi-1.jpg";
/* 장면 확인 목업 — 공개 작품 「대기실 밖에서는」(세이엘)의 표지와 5장. 고른 페이지는 02(장면 2). */
export const SCENE_THUMBS = [1, 2, 3, 4, 5, 6].map((n) => `/static/samples/onboarding-scene-p${n}.jpg`);
export const SCENE_PICK = 2;
export const SCENE_IMG = "/static/samples/onboarding-scene-big.jpg";

export default function EditorMock({ feat, s, height, who }: { feat: 0 | 1 | 2; s: number; height: number; who: string }) {
  const t = useT();
  const px = (n: number) => n * s;
  const layer = (show: boolean): React.CSSProperties => ({
    position: "absolute", inset: 0, background: "#f0f6f8", opacity: show ? 1 : 0,
    transition: "opacity .3s ease", pointerEvents: "none",
  });
  const pageImg: React.CSSProperties = {
    position: "absolute", left: "50%", top: 0, transform: "translateX(-50%)", width: px(430), display: "block",
  };
  return (
    <div className="wt-landing-mock" style={{ height, borderRadius: px(18) }}>
      <div className="wt-landing-mock-bar" style={{ height: px(44), gap: px(14), padding: `0 ${px(16)}px`, fontSize: px(12.5) }}>
        <b>LORE</b><span>{t("편집실")}</span><span>{who} · EP.01</span>
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: px(8) }}>
          <IconRetry size={px(16)} /><IconShare size={px(16)} /><IconDownload size={px(16)} />
        </span>
      </div>
      <div className="wt-landing-mock-body">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={MOCK_PAGE_IMG} alt={t("컷과 말풍선이 들어간 페이지")} style={pageImg} />
        <div className="wt-landing-mock-sheet" style={{ left: px(16), bottom: px(16), width: px(210), borderRadius: px(12) }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={SHEET_IMG} alt={t("캐릭터 시트")} style={{ width: "100%", display: "block" }} />
          <div style={{ padding: `${px(6)}px ${px(10)}px`, fontSize: px(11), color: "#3f6472" }}>{t("캐릭터 시트 · {who}", { who })}</div>
        </div>

        <div style={{ ...layer(feat === 2), overflow: "hidden" }}>
          {/* 편집실 — 장(장면)이 세로로 이어지고, 하나를 골라 「다시 그리기」를 누르면 팝업이 뜬다. 앞뒤 장도 같이 보인다. */}
          <div style={{
            position: "absolute", left: "4%", width: "42%", top: "50%", transform: "translateY(-50%)",
            display: "flex", flexDirection: "column", gap: px(12),
          }}>
            {[[MOCK_PREV_IMG, false], [MOCK_REDO_IMG, true], [MOCK_NEXT_IMG, false]].map(([src, on]) => (
              <div key={String(src)} style={{
                position: "relative", lineHeight: 0, borderRadius: px(6), overflow: "hidden", background: "#fff",
                outline: on ? `${px(3)}px solid #0e8fb5` : "none", outlineOffset: on ? px(1) : 0, opacity: on ? 1 : .6,
              }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={String(src)} alt="" style={{ width: "100%", display: "block" }} />
                {on && <span style={{
                  position: "absolute", right: px(8), bottom: px(8), padding: `${px(4)}px ${px(10)}px`, borderRadius: 999,
                  background: "#0e8fb5", color: "#fff", fontSize: px(11), fontWeight: 700, lineHeight: 1.4,
                }}>{t("다시 그리기")}</span>}
              </div>
            ))}
          </div>
          <div style={{ position: "absolute", inset: 0, background: "rgba(15,51,63,.28)" }} />
          <div style={{
            position: "absolute", right: "4%", width: "48%", top: "50%", transform: "translateY(-50%) scale(.9)", transformOrigin: "right center",
            background: "#fff", borderRadius: px(12), padding: px(14), boxShadow: "0 14px 34px rgba(15,51,63,.28)", fontSize: px(11.5), color: "var(--ink)",
          }}>
            <b style={{ display: "block", fontSize: px(14), marginBottom: px(10) }}>{t("{n}번째 장 다시 그리기", { n: 5 })}</b>
            <div style={{ fontSize: px(11), color: "#3f6472", marginBottom: px(6) }}>{t("무엇이 마음에 안 드나요?")}</div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: px(5), marginBottom: px(10) }}>
              {[["캐릭터가 이상해요", false], ["표정이 안 맞아요", true], ["포즈가 어색해요", false], ["배경이 이상해요", false]].map(([k, on]) => (
                <span key={String(k)} style={{
                  padding: `${px(3)}px ${px(8)}px`, borderRadius: 999, fontSize: px(10.5), lineHeight: 1.4,
                  border: `1px solid ${on ? "#0e8fb5" : "rgba(15,51,63,.2)"}`, background: on ? "#0e8fb5" : "#fff", color: on ? "#fff" : "var(--ink)",
                }}>{t(String(k))}</span>
              ))}
            </div>
            <div style={{ fontSize: px(11), color: "#3f6472", marginBottom: px(4) }}>{t("더 하고 싶은 말")}</div>
            <div style={{ border: "1px solid rgba(15,51,63,.2)", borderRadius: px(8), padding: px(8), minHeight: px(48), lineHeight: 1.45, marginBottom: px(8) }}>
              {t("무대 문 앞이라 조금 더 긴장한 표정으로")}
            </div>
            <div style={{ fontSize: px(11), marginBottom: px(10) }}>☐ {t("말풍선 없이 그림만")}</div>
            <div style={{ display: "flex", justifyContent: "flex-end", gap: px(6) }}>
              <span style={{ padding: `${px(6)}px ${px(12)}px`, borderRadius: 999, border: "1px solid rgba(15,51,63,.2)", fontSize: px(11) }}>{t("취소")}</span>
              <span style={{ padding: `${px(6)}px ${px(12)}px`, borderRadius: 999, background: "#0e8fb5", color: "#fff", fontWeight: 700, fontSize: px(11) }}>{t("다시 그리기")}</span>
            </div>
          </div>
        </div>

        <div style={{ ...layer(feat === 1), display: "flex", alignItems: "stretch", gap: "4%", padding: `${px(16)}px 4%` }}>
          {/* 장면 확인 화면처럼 — 왼쪽 위에 페이지(표지 · 01~05) 중 고른 것, 그 아래 장면 칸. 오른쪽에 그 페이지. */}
          <div style={{ flex: "0 0 38%", display: "flex", flexDirection: "column", justifyContent: "flex-start", gap: px(14) }}>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: px(6), width: "60%", alignSelf: "center" }}>
              {SCENE_THUMBS.map((src, i) => (
                <div key={src} style={{ position: "relative", borderRadius: px(5), overflow: "hidden", lineHeight: 0,
                  outline: i === SCENE_PICK ? `${px(2)}px solid #0e8fb5` : "1px solid rgba(15,51,63,.15)", outlineOffset: i === SCENE_PICK ? px(1) : 0,
                  opacity: i === SCENE_PICK ? 1 : .7 }}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={src} alt="" style={{ width: "100%", aspectRatio: "2 / 3", objectFit: "cover", display: "block" }} />
                  <span style={{ position: "absolute", left: px(4), bottom: px(4), padding: `${px(1)}px ${px(5)}px`, borderRadius: 999,
                    background: i === SCENE_PICK ? "#0e8fb5" : "rgba(15,51,63,.7)", color: "#fff", fontSize: px(9.5), fontWeight: 700, lineHeight: 1.4, whiteSpace: "nowrap" }}>
                    {i === 0 ? t("표지") : `0${i}`}
                  </span>
                </div>
              ))}
            </div>
            <div className="wt-landing-scenebox" style={{ padding: px(16), borderRadius: px(12), fontSize: px(14) }}>
              <b style={{ display: "block", marginBottom: px(8), fontSize: px(15) }}>{t("장면 {n} / {total}", { n: SCENE_PICK, total: 5 })}</b>
              {[
                ["장소와 상황", "대기실 거울 앞, 무대 직전"],
                ["벌어지는 일", "마지막 무대임을 떠올리며 임시 센터 계약서를 확인한다"],
                ["행동과 표정", "휴대폰 속 D-day를 읽고, 입꼬리가 처진다"],
              ].map(([k, v]) => (
                <div key={k} style={{ marginTop: px(9) }}>
                  <span style={{ display: "block", color: "#3f6472", fontSize: px(12) }}>{t(k)}</span>
                  {t(v)}
                </div>
              ))}
            </div>
          </div>
          <div style={{ flex: "1 1 0", minWidth: 0, borderRadius: px(10), overflow: "hidden", boxShadow: "0 10px 24px rgba(15,51,63,.16)", background: "#fff" }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={SCENE_IMG} alt={t("웹툰 한 컷")} style={{ width: "100%", height: "100%", objectFit: "cover", objectPosition: "top", display: "block" }} />
          </div>
        </div>
      </div>
    </div>
  );
}
