"use client";

/**
 * 다시 온 사람 안내(#471) — 한 편 이상 완성하고 그 뒤 다른 날 다시 온 사람에게, 전체 설문을
 * 한 번 권한다. 대상인지는 서버가 정한다(`GET /my/feedback` 의 prompt). 이 브라우저에서
 * 한 번 띄웠으면 다시 띄우지 않는다.
 *
 * 만드는 중·편집실·마이페이지에서는 띄우지 않는다 — 일하는 도중에 가로막으면 안 되고,
 * 마이페이지에는 이미 「피드백 보내기」가 있다.
 */
import { useEffect, useState } from "react";
import { mySurveyStatus } from "../lib/api";
import { registerDict, useT } from "../lib/i18n";
import type { Go, View } from "../lib/nav";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";

const SEEN_KEY = "lore_feedback_prompt_seen";
const QUIET: View[] = ["running", "editor", "mypage", "create"];

function seen(): boolean {
  try { return localStorage.getItem(SEEN_KEY) === "1"; } catch { return true; }
}

export default function RevisitPrompt({ authenticated, view, go }: { authenticated: boolean; view: View; go: Go }) {
  const t = useT();
  const [reward, setReward] = useState<number | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!authenticated || checked || QUIET.includes(view) || seen()) return;
    setChecked(true);
    mySurveyStatus().then((s) => {
      if (!s.prompt) return;
      try { localStorage.setItem(SEEN_KEY, "1"); } catch { /* 다음에 또 뜰 뿐 */ }
      track("feedback_prompt_view", { where: view });
      setReward(s.reward);
    }).catch(() => { /* 안내가 안 떠도 된다 */ });
  }, [authenticated, view, checked]);

  if (reward === null) return null;
  const close = () => setReward(null);
  return (
    <Dialog title={t("다시 찾아 주셔서 고마워요!")} onClose={close}
            sub={t("써 보신 소감을 설문으로 들려주시면, 웹툰 한 편을 더 만들 수 있는 {n}크레딧을 드려요. 한 번만 드리고, 답의 내용과는 상관없어요.", { n: reward })}>
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" onClick={close}>{t("다음에")}</button>
        <button type="button" className="btn btn-p" autoFocus onClick={() => {
          track("feedback_prompt_click", { where: view });
          close();
          go("mypage", { tab: "feedback" });
        }}>{t("설문하러 가기")}</button>
      </div>
    </Dialog>
  );
}

registerDict({
  "다시 찾아 주셔서 고마워요!": { en: "Thanks for coming back!", ja: "また来てくださってありがとうございます！", zh: "感谢你再次光临！" },
  "써 보신 소감을 설문으로 들려주시면, 웹툰 한 편을 더 만들 수 있는 {n}크레딧을 드려요. 한 번만 드리고, 답의 내용과는 상관없어요.": {
    en: "Tell us how it went in a short survey and we'll give you {n} credits — enough for one more webtoon. One time only, whatever your answers.",
    ja: "使ってみた感想をアンケートで教えていただくと、ウェブトゥーンをもう1話作れる{n}クレジットを差し上げます。1回限りで、回答内容は関係ありません。",
    zh: "通过问卷告诉我们使用感受，就送你可以再做一部漫画的 {n} 积分。仅限一次，与回答内容无关。",
  },
  "다음에": { en: "Later", ja: "また今度", zh: "下次吧" },
  "설문하러 가기": { en: "Take the survey", ja: "アンケートへ", zh: "去填问卷" },
});
