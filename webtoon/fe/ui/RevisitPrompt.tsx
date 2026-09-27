"use client";

/**
 * 다시 온 사람 안내(#471) — 한 편 이상 완성하고 그 뒤 다른 날 다시 온 사람에게, 설문과
 * 그 보상(웹툰 한 편 값의 크레딧)을 한 번 권한다. 대상인지는 서버가 정한다
 * (`GET /my/feedback` 의 prompt). 이 브라우저에서 한 번 띄웠으면 다시 띄우지 않는다.
 *
 * 만드는 중·편집실·만들기·마이페이지에서는 띄우지 않는다 — 일하는 도중에 가로막으면
 * 안 되고, 마이페이지에는 이미 「피드백 보내기」가 있다.
 */
import { useEffect, useState } from "react";
import { mySurveyStatus } from "../lib/api";
import { registerDict, useT } from "../lib/i18n";
import { louArt } from "../lib/louArt";
import type { Go, View } from "../lib/nav";
import { track } from "../lib/track";
import { RewardBadge } from "./Survey";
import "./RevisitPrompt.css";

const SEEN_KEY = "lore_feedback_prompt_seen";
const QUIET: View[] = ["running", "editor", "mypage", "create"];

function seen(): boolean {
  try { return localStorage.getItem(SEEN_KEY) === "1"; } catch { return true; }
}

export default function RevisitPrompt({ authenticated, view, go }: { authenticated: boolean; view: View; go: Go }) {
  const t = useT();
  const [reward, setReward] = useState<number | null>(null);
  const [checked, setChecked] = useState(false);
  const [art] = useState(() => louArt("notice"));

  useEffect(() => {
    if (!authenticated || checked || QUIET.includes(view) || seen()) return;
    setChecked(true);
    mySurveyStatus().then((s) => {
      if (!s.prompt || s.questions.length === 0) return;
      try { localStorage.setItem(SEEN_KEY, "1"); } catch { /* 다음에 또 뜰 뿐 */ }
      track("feedback_prompt_view", { where: view });
      setReward(s.reward);
    }).catch(() => { /* 안내가 안 떠도 된다 */ });
  }, [authenticated, view, checked]);

  useEffect(() => {
    if (reward === null) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setReward(null); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [reward]);

  if (reward === null) return null;
  const close = () => setReward(null);
  return (
    <div className="wt-dialog wt-revisit" onClick={close}>
      <div className="wt-revisit-box" role="dialog" aria-modal="true" aria-labelledby="wt-revisit-title"
           onClick={(e) => e.stopPropagation()}>
        <div className="wt-revisit-art">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {art && <img src={art} alt="" />}
        </div>
        <div className="wt-revisit-body">
          <p className="wt-revisit-eyebrow">{t("다시 와 주셨네요")}</p>
          <h2 id="wt-revisit-title">{t("설문에 답하고\n웹툰 1편 더 만들기")}</h2>
          <RewardBadge amount={reward} />
          <button type="button" className="btn btn-p wt-revisit-go" autoFocus onClick={() => {
            track("feedback_prompt_click", { where: view });
            close();
            go("mypage", { tab: "feedback" });
          }}>{t("{n}크레딧 받으러 가기", { n: reward })}</button>
          <button type="button" className="wt-revisit-later" onClick={close}>{t("다음에 할게요")}</button>
        </div>
      </div>
    </div>
  );
}

registerDict({
  "다시 와 주셨네요": { en: "Welcome back", ja: "おかえりなさい", zh: "欢迎回来" },
  "설문에 답하고\n웹툰 1편 더 만들기": { en: "Answer a survey,\nmake one more webtoon", ja: "アンケートに答えて\nもう1話作ろう", zh: "填写问卷\n再做一部漫画" },
  "{n}크레딧 받으러 가기": { en: "Get {n} credits", ja: "{n}クレジットをもらう", zh: "去领 {n} 积分" },
  "다음에 할게요": { en: "Maybe later", ja: "また今度", zh: "下次吧" },
});
