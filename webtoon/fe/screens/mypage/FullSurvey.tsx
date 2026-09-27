"use client";

/**
 * 마이페이지 「피드백 보내기」(#471) — 가장 최근에 완성한 작품에 맞춘 설문과 자유 의견.
 *
 * 무엇을 물을지는 서버가 정한다(`GET /my/feedback` 의 questions). 설명을 안 적은 사람에게
 * 「성격대로 행동했나」를 묻지 않으려는 것이다. 모두 답하면 웹툰 한 편 값의 크레딧을
 * 계정당 한 번 준다(서버 `WebtoonFeedbackService`). 자유 의견·연락처는 행동 기록이
 * 아니라 설문 표에만 남는다.
 */
import { useState } from "react";
import { sendFullSurvey, type SurveyAnswers, type SurveyStatus } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { Dialog } from "../../ui/Dialog";
import { answeredAll, RewardBadge, SurveyQuestion } from "../../ui/Survey";

export default function FullSurvey({ authenticated, status, go, onClose, onRewarded }: {
  authenticated: boolean;
  status: SurveyStatus | null;
  go: Go;
  onClose: () => void;
  /** 크레딧을 받았으면 새 잔액을 알린다 */
  onRewarded: (balance: number) => void;
}) {
  const t = useT();
  const [answers, setAnswers] = useState<SurveyAnswers>({});
  const [comment, setComment] = useState("");
  const [interview, setInterview] = useState(false);
  const [contact, setContact] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [sent, setSent] = useState<number | null>(null);
  const reward = status?.reward ?? 12;
  const questions = status?.questions ?? [];

  const send = async () => {
    setBusy(true);
    setErr("");
    try {
      const got = await sendFullSurvey({ answers, comment, wantsInterview: interview, contact: interview ? contact : "" });
      track("feedback_submit", { where: "mypage", count: questions.length, ok: got.rewarded > 0 });
      if (got.rewarded > 0) onRewarded(got.balance);
      setSent(got.rewarded);
    } catch {
      setErr(t("보내지 못했어요. 잠시 뒤에 다시 눌러 주세요."));
    } finally {
      setBusy(false);
    }
  };

  const toCreate = () => { onClose(); go("entry"); };

  if (sent !== null) {
    return (
      <Dialog title={sent > 0 ? t("{n}크레딧을 받았어요!", { n: sent }) : t("보내 주셔서 고마워요!")} onClose={onClose}>
        {sent > 0 && <RewardBadge amount={sent} />}
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={onClose}>{t("닫기")}</button>
          {sent > 0 && <button type="button" className="btn btn-p" autoFocus onClick={toCreate}>{t("웹툰 만들러 가기")}</button>}
        </div>
      </Dialog>
    );
  }

  if (!authenticated || questions.length === 0) {
    return (
      <Dialog title={t("피드백 보내기")} onClose={onClose}
              sub={!authenticated ? t("로그인하면 피드백을 보낼 수 있어요.") : t("웹툰을 한 편 완성하면 설문에 답하고 크레딧을 받을 수 있어요.")}>
        {authenticated && !status?.done && <RewardBadge amount={reward} />}
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={onClose}>{t("닫기")}</button>
          {authenticated && <button type="button" className="btn btn-p" onClick={toCreate}>{t("웹툰 만들러 가기")}</button>}
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title={t("피드백 보내기")} wide onClose={onClose} busy={busy}
            sub={status?.done ? t("보상은 이미 받으셨어요.") : t("모든 문항에 답하면 받을 수 있어요.")}>
      {!status?.done && <RewardBadge amount={reward} />}
      <div className="wt-survey-full">
        {questions.map((q) => (
          <SurveyQuestion key={q} q={q} value={answers[q]}
                          onChange={(v) => setAnswers((a) => ({ ...a, [q]: v }))} />
        ))}
        <label className="wt-survey-q">
          <b>{t("더 하고 싶은 말 (선택)")}</b>
          <textarea value={comment} maxLength={2000} onChange={(e) => setComment(e.target.value)}
                    placeholder={t("좋았던 장면, 아쉬웠던 곳, 바라는 기능 무엇이든 적어 주세요.")} />
        </label>
        <label className="wt-survey-check">
          <input type="checkbox" checked={interview} onChange={(e) => setInterview(e.target.checked)} />
          {t("15분 인터뷰에 참여할 수 있어요")}
        </label>
        {interview && (
          <input type="text" value={contact} maxLength={200} onChange={(e) => setContact(e.target.value)}
                 placeholder={t("연락받을 곳 (이메일·카카오톡 아이디 등)")} />
        )}
      </div>
      {err && <p className="wt-dialog-err">{err}</p>}
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={onClose}>{t("닫기")}</button>
        <button type="button" className="btn btn-p" disabled={busy || !answeredAll(questions, answers)}
                onClick={() => void send()}>{status?.done ? t("보내기") : t("보내고 {n}크레딧 받기", { n: reward })}</button>
      </div>
    </Dialog>
  );
}

registerDict({
  "로그인하면 피드백을 보낼 수 있어요.": { en: "Sign in to send feedback.", ja: "ログインするとフィードバックを送れます。", zh: "登录后可以发送反馈。" },
  "웹툰을 한 편 완성하면 설문에 답하고 크레딧을 받을 수 있어요.": { en: "Finish one webtoon to answer the survey and get credits.", ja: "ウェブトゥーンを1話完成させると、アンケートに答えてクレジットをもらえます。", zh: "完成一部漫画后即可填写问卷并领取积分。" },
  "보상은 이미 받으셨어요.": { en: "You've already received the reward.", ja: "特典はすでに受け取り済みです。", zh: "你已经领过奖励了。" },
  "모든 문항에 답하면 받을 수 있어요.": { en: "Answer every question to get it.", ja: "すべての質問に答えると受け取れます。", zh: "回答全部问题即可领取。" },
  "보내고 {n}크레딧 받기": { en: "Send & get {n} credits", ja: "送信して{n}クレジットを受け取る", zh: "提交并领取 {n} 积分" },
  "{n}크레딧을 받았어요!": { en: "You got {n} credits!", ja: "{n}クレジットを受け取りました！", zh: "已领取 {n} 积分！" },
  "웹툰 만들러 가기": { en: "Make a webtoon", ja: "ウェブトゥーンを作る", zh: "去做漫画" },
  "더 하고 싶은 말 (선택)": { en: "Anything else? (optional)", ja: "ほかに伝えたいこと（任意）", zh: "还想说的（可选）" },
  "좋았던 장면, 아쉬웠던 곳, 바라는 기능 무엇이든 적어 주세요.": { en: "Scenes you liked, what fell short, features you want — anything.", ja: "良かった場面、物足りなかった点、欲しい機能など何でも。", zh: "喜欢的场景、不足之处、希望的功能，什么都可以。" },
  "15분 인터뷰에 참여할 수 있어요": { en: "I can join a 15-minute interview", ja: "15分のインタビューに参加できます", zh: "我可以参加 15 分钟的访谈" },
  "연락받을 곳 (이메일·카카오톡 아이디 등)": { en: "How to reach you (email, KakaoTalk ID, etc.)", ja: "連絡先（メール・カカオトークIDなど）", zh: "联系方式（邮箱、KakaoTalk ID 等）" },
  "보내 주셔서 고마워요!": { en: "Thank you!", ja: "ありがとうございます！", zh: "谢谢！" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "피드백 보내기": { en: "Send feedback", ja: "フィードバックを送る", zh: "发送反馈" },
  "보내지 못했어요. 잠시 뒤에 다시 눌러 주세요.": { en: "Couldn't send. Please try again in a moment.", ja: "送信できませんでした。少ししてからもう一度押してください。", zh: "发送失败，请稍后再试。" },
});
