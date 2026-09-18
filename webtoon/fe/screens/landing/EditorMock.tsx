"use client";

/* 첫 화면의 「편집실」 목업 — 니즈 카드와 「약속 셋」이 같이 쓴다.
 * 보드의 f0/f1/f2 층: 0 = 페이지 + 캐릭터 시트, 1 = 한 컷만 다시 그리기, 2 = 세계관 한 컷.
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
export const CUT_IMG = "/static/samples/ex-mongi-1.jpg";

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
        <img src={PAGE_IMG} alt={t("컷과 말풍선이 들어간 페이지")} style={pageImg} />
        <div className="wt-landing-mock-sheet" style={{ left: px(16), bottom: px(16), width: px(210), borderRadius: px(12) }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={SHEET_IMG} alt={t("캐릭터 시트")} style={{ width: "100%", display: "block" }} />
          <div style={{ padding: `${px(6)}px ${px(10)}px`, fontSize: px(11), color: "#3f6472" }}>{t("캐릭터 시트 · {who}", { who })}</div>
        </div>

        <div style={layer(feat === 1)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={REGEN_IMG} alt={t("한 컷만 다시 그리기")} style={pageImg} />
          <div style={{
            position: "absolute", left: px(40), top: px(60), width: px(300), height: px(180),
            border: "3px solid #0e8fb5", borderRadius: px(8), boxShadow: "0 0 0 9999px rgba(15,51,63,.35)",
          }} />
          <span style={{
            position: "absolute", left: px(40), top: px(250), padding: `${px(8)}px ${px(14)}px`, borderRadius: 999,
            background: "#0e8fb5", color: "#fff", fontSize: px(13), fontWeight: 700,
          }}>{t("이 컷만 다시 그리기")}</span>
        </div>

        <div style={layer(feat === 2)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={CUT_IMG} alt={t("웹툰 한 컷")} style={pageImg} />
          <span className="wt-landing-genre" style={{ left: px(16), top: px(16), fontSize: px(11), padding: `${px(3)}px ${px(9)}px` }}>{t("로판")}</span>
          <div className="wt-landing-bubble" style={{ right: px(18), top: px(24), maxWidth: px(250), borderRadius: px(14), padding: `${px(8)}px ${px(12)}px`, fontSize: px(12.5) }}>
          </div>
          <div className="wt-landing-cap" style={{ padding: `${px(14)}px ${px(16)}px`, fontSize: px(13.5), fontWeight: 700 }}>
            {t("몽이는 이 로맨스 웹툰에서, 강아지인 채로 악역 영애예요")}
          </div>
        </div>
      </div>
    </div>
  );
}
