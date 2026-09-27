"use client";

/**
 * 완성 직후 짧은 설문(#471). 끝까지 읽은 작품 주인에게 한 번, 질문 두세 개.
 *
 * 무엇을 물을지는 서버가 정한다 — 넣은 것(사진·이름·설명·줄거리)은 주인에게만 보이는
 * 값이라 화면이 모른다. 핵심 질문 하나는 고정이고 나머지는 무작위다(WebtoonFeedbackService).
 * 건너뛰면 이 브라우저에서 이 작품에는 다시 안 띄운다.
 */
import { useEffect, useState } from "react";
import { mySurveyStatus, sendShortSurvey, surveyQuestions, type SurveyAnswers, type SurveyKey } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { answeredAll, RewardBadge, SurveyQuestion } from "../../ui/Survey";

const SKIPPED_KEY = "lore_survey_skipped";

function skipped(runId: string): boolean {
  try {
    return (JSON.parse(localStorage.getItem(SKIPPED_KEY) || "[]") as string[]).includes(runId);
  } catch {
    return false;
  }
}

function rememberSkip(runId: string) {
  try {
    const list = (JSON.parse(localStorage.getItem(SKIPPED_KEY) || "[]") as string[]).filter((r) => r !== runId);
    localStorage.setItem(SKIPPED_KEY, JSON.stringify([runId, ...list].slice(0, 50)));
  } catch {
    /* 저장이 막힌 브라우저면 다음에 또 뜰 뿐이다 */
  }
}

export default function ResultSurvey({ runId, authenticated, go }: { runId: string; authenticated: boolean; go: Go }) {
  const t = useT();
  const [questions, setQuestions] = useState<SurveyKey[]>([]);
  const [answers, setAnswers] = useState<SurveyAnswers>({});
  const [state, setState] = useState<"ask" | "busy" | "done" | "gone">("gone");
  const [err, setErr] = useState("");
  /* 보낸 뒤 — 마이페이지 설문 보상을 아직 안 받았으면 그 크레딧을 보여 준다 */
  const [reward, setReward] = useState<number | null>(null);

  useEffect(() => {
    if (skipped(runId)) return;
    let alive = true;
    surveyQuestions(runId)
      .then((got) => {
        if (!alive || got.questions.length === 0) return;
        setQuestions(got.questions);
        setState("ask");
        track("feedback_view", { where: "result", run: runId, count: got.questions.length });
      })
      .catch(() => { /* 설문이 안 떠도 완성본은 멀쩡해야 한다 */ });
    return () => { alive = false; };
  }, [runId]);

  if (state === "gone") return null;

  if (state === "done") {
    return (
      <div className="card wt-survey-card wt-survey-done">
        <b>{t("답해 주셔서 고마워요!")}</b>
        {reward !== null && (
          <>
            <span>{t("설문을 조금만 더 하면")}</span>
            <RewardBadge amount={reward} />
            <button type="button" className="btn btn-p" onClick={() => go("mypage", { tab: "feedback" })}>
              {t("{n}크레딧 받으러 가기", { n: reward })}
            </button>
          </>
        )}
      </div>
    );
  }

  const send = async () => {
    setState("busy");
    setErr("");
    try {
      await sendShortSurvey(runId, answers);
      track("feedback_submit", { where: "result", run: runId, count: Object.keys(answers).length });
      if (authenticated) {
        mySurveyStatus().then((s) => { if (!s.done && s.questions.length > 0) setReward(s.reward); }).catch(() => {});
      }
      setState("done");
    } catch {
      setErr(t("보내지 못했어요. 잠시 뒤에 다시 눌러 주세요."));
      setState("ask");
    }
  };

  const skip = () => {
    rememberSkip(runId);
    track("feedback_skip", { where: "result", run: runId });
    setState("gone");
  };

  return (
    <div className="card wt-survey-card">
      <h3>{t("이 웹툰, 어땠나요?")}</h3>
      {questions.map((q) => (
        <SurveyQuestion key={q} q={q} value={answers[q]}
                        onChange={(v) => setAnswers((a) => ({ ...a, [q]: v }))} />
      ))}
      {err && <p className="wt-survey-err">{err}</p>}
      <div className="wt-survey-actions">
        <button type="button" className="btn btn-w" disabled={state === "busy"} onClick={skip}>{t("건너뛰기")}</button>
        <button type="button" className="btn btn-p" disabled={state === "busy" || !answeredAll(questions, answers)}
                onClick={() => void send()}>{t("보내기")}</button>
      </div>
    </div>
  );
}

registerDict({
  "이 웹툰, 어땠나요?": { en: "How was this webtoon?", ja: "このウェブトゥーン、どうでしたか？", zh: "这部漫画怎么样？" },
  "건너뛰기": { en: "Skip", ja: "スキップ", zh: "跳过" },
  "보내기": { en: "Send", ja: "送信", zh: "提交" },
  "답해 주셔서 고마워요!": { en: "Thanks for answering!", ja: "ご回答ありがとうございます！", zh: "感谢你的回答！" },
  "보내지 못했어요. 잠시 뒤에 다시 눌러 주세요.": { en: "Couldn't send. Please try again in a moment.", ja: "送信できませんでした。少ししてからもう一度押してください。", zh: "发送失败，请稍后再试。" },
  "피드백 보내기": { en: "Send feedback", ja: "フィードバックを送る", zh: "发送反馈" },
  "설문을 조금만 더 하면": { en: "A few more questions and you get", ja: "あと少し答えると", zh: "再多答几题就能获得" },
  "{n}크레딧 받으러 가기": { en: "Get {n} credits", ja: "{n}クレジットをもらう", zh: "去领 {n} 积分" },
});
