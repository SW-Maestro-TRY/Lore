"use client";
/* 만들기 — 웹툰 만들기 / 세계관 만들기 둘 중 하나(시안 B: haeun/브랜드-UX-2026-11-02/시안-만들기).
 * 칸마다 그림 위에 짧은 이름표만 얹어, 무엇이 나오는지 그림과 같이 보이게 한다. 설명 문장은 쓰지 않는다. */
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import "./i18n";
import "./Play.css";

export default function MakeChoice({ go }: { go: Go }) {
  const t = useT();
  return (
    <div className="pl"><div className="mk-wrap">
      <h1 className="pl-h1">{t("무엇을 만들까요?")}</h1>
      <div className="mk-grid">
        <button type="button" className="mk-card" onClick={() => go("create", { step: 1 })}>
          <span className="stage webtoon">
            <span className="strip">
              <i style={{ backgroundImage: "url(/static/gallery/seiel/ball1.jpg)" }} />
              <i style={{ backgroundImage: "url(/static/gallery/seiel/ball2.jpg)" }} />
              <i style={{ backgroundImage: "url(/static/gallery/seiel/ball3.jpg)" }} />
            </span>
            <span className="tags"><em>{t("내 캐릭터")}</em><em>{t("이야기")}</em><em>{t("그림체")}</em><b>{t("웹툰 한 편")}</b></span>
          </span>
          <span className="ft"><b>{t("웹툰 만들기")}</b><span className="meta"><em>20C</em><em>{t("약 6~15분")}</em></span><span className="pl-btn sm">{t("시작")}</span></span>
        </button>
        <button type="button" className="mk-card" onClick={() => go("world-new")}>
          <span className="stage world" style={{ backgroundImage: "url(/static/play/zombie-world.jpg)" }}>
            <span className="tags"><em>{t("세계")}</em><em>{t("사건")}</em><em>{t("예시 캐릭터")}</em><b>{t("세계관")}</b></span>
          </span>
          <span className="ft"><b>{t("세계관 만들기")}</b><span className="meta"><em>{t("무료")}</em></span><span className="pl-btn sm">{t("시작")}</span></span>
        </button>
      </div>
    </div></div>
  );
}
