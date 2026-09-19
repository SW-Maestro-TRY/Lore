"use client";

/* 루와 놀기 — 기다리는 동안의 자리.
 *
 * 누르기·연달아 누르기·꾹 누르기·끌어당기기·잡고 흔들기에 루가 다르게
 * 반응한다. 반응 프레임과 대사는 /static/lou/react/manifest.json, 아래에서
 * 도는 팁은 /static/lou/tips.json 이 정한다 — 둘 다 그림 원본
 * (webtoon/ai/assets/lou)에 있어서, 반응이나 팁을 늘리는 데 이 파일을
 * 고칠 일은 없다.
 *
 * 움직임은 mascotPlay 가 DOM 을 직접 만져서 그린다. 그래서 여기서 하는 일은
 * 그쪽이 찾는 id(mascotStage · mascot · mascotImg · playSay · playHint ·
 * shakeAllow · tips · tipKind · tipText)를 그대로 놓아 주는 것뿐이다. */

import { useEffect } from "react";
import { LOU_IDLE } from "../../lib/louArt";
import { setupLou, setupTips } from "../../lib/mascotPlay";
import { useT } from "../../lib/i18n";

export default function LouPlay() {
  const t = useT();

  useEffect(() => {
    const stopLou = setupLou();
    const stopTips = setupTips();
    return () => { stopLou(); stopTips(); };
  }, []);

  return (
    <div className="wt-prog-play">
      <div className="stage" id="mascotStage">
        <button type="button" className="mascot" id="mascot" aria-label={t("루를 눌러 보기")}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img id="mascotImg" src={LOU_IDLE} alt="" draggable={false} />
        </button>
      </div>
      <p className="say" id="playSay">{t("루를 눌러 보세요")}</p>
      <p className="hint" id="playHint">{t("누르기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기")}</p>
      <button type="button" className="btn btn-w btn-sm" id="shakeAllow" hidden>
        {t("흔들기 켜기")}
      </button>
      <div className="wt-prog-tips" id="tips" hidden aria-live="polite">
        <span className="kind" id="tipKind">{t("팁")}</span>
        <p className="text" id="tipText" />
      </div>
    </div>
  );
}
