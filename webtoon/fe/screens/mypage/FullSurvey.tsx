"use client";

/**
 * 마이페이지 「피드백 보내기」(#471) — 설문 전부(S1~S10)와 자유 의견.
 *
 * 끝까지 답하면 웹툰 한 편 값의 크레딧을 계정당 한 번 준다(서버 `WebtoonFeedbackService`).
 * 답의 내용과는 상관없다. 자유 의견·연락처는 행동 기록이 아니라 설문 표에만 남는다.
 */
import { useState } from "react";
import { sendFullSurvey, type SurveyAnswers, type SurveyStatus } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { Dialog } from "../../ui/Dialog";
import { answeredAll, SURVEY_KEYS, SurveyQuestion } from "../../ui/Survey";

export default function FullSurvey({ authenticated, status, onClose, onRewarded }: {
  authenticated: boolean;
  status: SurveyStatus | null;
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

  const sub = !authenticated
    ? t("로그인하면 피드백을 보낼 수 있어요.")
    : status?.done
      ? t("이미 보상을 받으셨어요. 다시 보내 주셔도 고맙게 읽을게요.")
      : t("모든 문항에 답해 주시면 웹툰 한 편을 더 만들 수 있는 {n}크레딧을 드려요. 답의 내용과 상관없이 드려요.", { n: reward });

  const send = async () => {
    setBusy(true);
    setErr("");
    try {
      const got = await sendFullSurvey({ answers, comment, wantsInterview: interview, contact: interview ? contact : "" });
      track("feedback_submit", { where: "mypage", count: SURVEY_KEYS.length, ok: got.rewarded > 0 });
      if (got.rewarded > 0) onRewarded(got.balance);
      setSent(got.rewarded);
    } catch {
      setErr(t("보내지 못했어요. 잠시 뒤에 다시 눌러 주세요."));
    } finally {
      setBusy(false);
    }
  };

  if (sent !== null) {
    return (
      <Dialog title={t("보내 주셔서 고마워요!")} onClose={onClose}
              sub={sent > 0 ? t("{n}크레딧을 드렸어요. 웹툰 한 편을 더 만들어 보세요.", { n: sent }) : t("잘 받았어요. 하나하나 읽고 반영할게요.")}>
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-p" onClick={onClose}>{t("닫기")}</button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title={t("피드백 보내기")} sub={sub} wide onClose={onClose} busy={busy}>
      {authenticated && (
        <div className="wt-survey-full">
          {SURVEY_KEYS.map((q) => (
            <SurveyQuestion key={q} q={q} value={answers[q]} allowNa
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
      )}
      {err && <p className="wt-dialog-err">{err}</p>}
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={onClose}>{t("닫기")}</button>
        {authenticated && (
          <button type="button" className="btn btn-p" disabled={busy || !answeredAll(SURVEY_KEYS, answers)}
                  onClick={() => void send()}>{t("보내기")}</button>
        )}
      </div>
    </Dialog>
  );
}

registerDict({
  "로그인하면 피드백을 보낼 수 있어요.": { en: "Sign in to send feedback.", ja: "ログインするとフィードバックを送れます。", zh: "登录后可以发送反馈。" },
  "이미 보상을 받으셨어요. 다시 보내 주셔도 고맙게 읽을게요.": { en: "You've already received the reward. We'll still gladly read more feedback.", ja: "特典はすでに受け取り済みです。また送っていただいてもありがたく読みます。", zh: "你已经领过奖励了。再次发送我们也会认真阅读。" },
  "모든 문항에 답해 주시면 웹툰 한 편을 더 만들 수 있는 {n}크레딧을 드려요. 답의 내용과 상관없이 드려요.": {
    en: "Answer every question and we'll give you {n} credits — enough for one more webtoon, whatever your answers.",
    ja: "すべての質問に答えていただくと、ウェブトゥーンをもう1話作れる{n}クレジットを差し上げます。回答内容は関係ありません。",
    zh: "回答全部问题，就送你可以再做一部漫画的 {n} 积分，与回答内容无关。",
  },
  "더 하고 싶은 말 (선택)": { en: "Anything else? (optional)", ja: "ほかに伝えたいこと（任意）", zh: "还想说的（可选）" },
  "좋았던 장면, 아쉬웠던 곳, 바라는 기능 무엇이든 적어 주세요.": { en: "Scenes you liked, what fell short, features you want — anything.", ja: "良かった場面、物足りなかった点、欲しい機能など何でも。", zh: "喜欢的场景、不足之处、希望的功能，什么都可以。" },
  "15분 인터뷰에 참여할 수 있어요": { en: "I can join a 15-minute interview", ja: "15分のインタビューに参加できます", zh: "我可以参加 15 分钟的访谈" },
  "연락받을 곳 (이메일·카카오톡 아이디 등)": { en: "How to reach you (email, KakaoTalk ID, etc.)", ja: "連絡先（メール・カカオトークIDなど）", zh: "联系方式（邮箱、KakaoTalk ID 等）" },
  "보내 주셔서 고마워요!": { en: "Thank you!", ja: "ありがとうございます！", zh: "谢谢！" },
  "{n}크레딧을 드렸어요. 웹툰 한 편을 더 만들어 보세요.": { en: "We've added {n} credits. Go make one more webtoon!", ja: "{n}クレジットを差し上げました。もう1話作ってみてください。", zh: "已送你 {n} 积分，再做一部漫画吧。" },
  "잘 받았어요. 하나하나 읽고 반영할게요.": { en: "Got it. We'll read every word.", ja: "受け取りました。一つずつ読んで反映します。", zh: "收到了，我们会逐条阅读。" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "피드백 보내기": { en: "Send feedback", ja: "フィードバックを送る", zh: "发送反馈" },
});
