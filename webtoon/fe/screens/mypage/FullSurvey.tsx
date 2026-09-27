"use client";

/**
 * 마이페이지 「피드백 보내기」(#471) — 가장 최근에 완성한 작품에 맞춘 설문과 자유 의견.
 *
 * 무엇을 물을지는 서버가 정한다(`GET /my/feedback` 의 questions). 설명을 안 적은 사람에게
 * 「성격대로 행동했나」를 묻지 않으려는 것이다. 모두 답하면 웹툰 한 편 값의 크레딧을
 * 계정당 한 번 준다(서버 `WebtoonFeedbackService`). 이미 받은 사람도 보낼 수 있고, 보낸
 * 뒤에 왜 크레딧이 없는지와 인터뷰 보상을 알려 준다. 자유 의견·연락처는 행동 기록이
 * 아니라 설문 표에만 남는다.
 */
import { useState } from "react";
import { CONTACT_CHANNEL } from "@common/links";
import { sendFullSurvey, type SurveyAnswers, type SurveyStatus } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { Dialog } from "../../ui/Dialog";
import { answeredAll, OpenNotes, RewardBadge, SurveyQuestion, withAnswer } from "../../ui/Survey";

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
  const [interview, setInterview] = useState(false);
  const [contact, setContact] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [sent, setSent] = useState<number | null>(null);
  const reward = status?.reward ?? 12;
  const questions = status?.questions ?? [];
  const intro = (
    <>
      {t("LORE가 더 좋은 서비스가 될 수 있도록 설문을 받고 있어요.")}
      {!status?.done && <><br />{t("피드백에 응답해 주시면 크레딧을 드려요!")}</>}
    </>
  );

  const send = async () => {
    setBusy(true);
    setErr("");
    try {
      const got = await sendFullSurvey({ answers, wantsInterview: interview, contact: interview ? contact : "" });
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

  /* 인터뷰를 신청한 사람에게만 — 신청 안 한 사람에게 「이메일로 보내 드릴게요」는 맞지 않는다 */
  const interviewLine = interview && (
    <p>{t("적어 주신 이메일로 인터뷰 일정과 사전 질문을 보내 드릴게요.")}</p>
  );

  if (sent !== null && sent > 0) {
    return (
      <Dialog title={t("설문 제출 완료")} onClose={onClose}>
        <div className="wt-survey-sent">
          <p>{t("소중한 의견을 보내 주셔서 감사해요!")}</p>
          <RewardBadge amount={sent} />
          <p>{t("크레딧이 지급되었어요. 바로 웹툰을 만들어 보세요!")}</p>
          {interviewLine}
        </div>
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={onClose}>{t("닫기")}</button>
          <button type="button" className="btn btn-p" autoFocus onClick={toCreate}>{t("웹툰 만들러 가기")}</button>
        </div>
      </Dialog>
    );
  }

  if (sent !== null) {
    return (
      <Dialog title={t("설문 제출 완료")} onClose={onClose}>
        <div className="wt-survey-sent">
          <p>{t("소중한 의견을 보내 주셔서 감사해요!")}</p>
          <p>{t("이번 설문 크레딧은 이미 받으셔서 추가로 드리기 어려워요.")}</p>
          <p>{t("대신 15분 인터뷰에 참여해 주시면 따로 보상을 드려요.")}</p>
          {interviewLine}
          <p className="wt-survey-sent-help">
            {t("처음 참여했는데 크레딧이 들어오지 않았다면")}{" "}
            <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">{t("1:1 문의하기")}</a>{t("로 알려 주세요.")}
          </p>
        </div>
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-p" autoFocus onClick={onClose}>{t("확인")}</button>
        </div>
      </Dialog>
    );
  }

  if (!authenticated || questions.length === 0) {
    return (
      <Dialog title={t("피드백 보내기")} onClose={onClose}
              sub={!authenticated ? t("로그인하면 피드백을 보낼 수 있어요.") : intro}>
        {authenticated && !status?.done && <RewardBadge amount={reward} />}
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={onClose}>{t("닫기")}</button>
          {authenticated && <button type="button" className="btn btn-p" onClick={toCreate}>{t("웹툰 만들러 가기")}</button>}
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title={t("피드백 보내기")} wide onClose={onClose} busy={busy} sub={intro}>
      {!status?.done && <RewardBadge amount={reward} />}
      <div className="wt-survey-full">
        {questions.map((q) => (
          <SurveyQuestion key={q} q={q} answers={answers}
                          set={(k, v) => setAnswers((a) => withAnswer(a, k, v))} />
        ))}
        <OpenNotes answers={answers} set={(k, v) => setAnswers((a) => withAnswer(a, k, v))} />
        <div className="wt-survey-interview">
          <label className="wt-survey-check">
            <input type="checkbox" checked={interview} onChange={(e) => setInterview(e.target.checked)} />
            {t("15분 인터뷰에 참여할게요")}
          </label>
          <span className="muted">{t("체크해 주시면 인터뷰 가능한 날짜와 질문을 이메일로 미리 보내 드려요. 참여해 주시면 따로 보상도 드려요.")}</span>
          {interview && (
            <input type="text" inputMode="email" value={contact} maxLength={200} onChange={(e) => setContact(e.target.value)}
                   placeholder={t("안내를 받을 이메일")} />
          )}
        </div>
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
  "LORE가 더 좋은 서비스가 될 수 있도록 설문을 받고 있어요.": { en: "We're collecting feedback to make LORE even better.", ja: "LOREをもっと良いサービスにするため、アンケートを実施しています。", zh: "为了让 LORE 变得更好，我们正在收集问卷。" },
  "피드백에 응답해 주시면 크레딧을 드려요!": { en: "Answer and we'll give you credits!", ja: "回答していただくとクレジットを差し上げます！", zh: "回答即可获得积分！" },
  "로그인하면 피드백을 보낼 수 있어요.": { en: "Sign in to send feedback.", ja: "ログインするとフィードバックを送れます。", zh: "登录后可以发送反馈。" },
  "설문 제출 완료": { en: "Survey submitted", ja: "アンケート送信完了", zh: "问卷已提交" },
  "소중한 의견을 보내 주셔서 감사해요!": { en: "Thank you for your valuable feedback!", ja: "貴重なご意見をありがとうございます！", zh: "感谢你宝贵的意见！" },
  "이번 설문 크레딧은 이미 받으셔서 추가로 드리기 어려워요.": { en: "You've already received the credits for this survey, so we can't add more.", ja: "このアンケートのクレジットはすでにお受け取り済みのため、追加でお渡しできません。", zh: "这份问卷的积分你已经领过了，无法再次发放。" },
  "대신 15분 인터뷰에 참여해 주시면 따로 보상을 드려요.": { en: "Instead, join a 15-minute interview for a separate reward.", ja: "代わりに15分のインタビューにご参加いただくと、別途特典を差し上げます。", zh: "作为替代，参加 15 分钟访谈可获得额外奖励。" },
  "적어 주신 이메일로 인터뷰 일정과 사전 질문을 보내 드릴게요.": { en: "We'll email you the interview schedule and questions in advance.", ja: "ご記入のメールにインタビューの日程と事前質問をお送りします。", zh: "我们会把访谈时间和问题提前发到你填写的邮箱。" },
  "크레딧이 지급되었어요. 바로 웹툰을 만들어 보세요!": { en: "Your credits are in. Go make a webtoon!", ja: "クレジットが付与されました。さっそくウェブトゥーンを作ってみてください！", zh: "积分已发放，马上去做漫画吧！" },
  "처음 참여했는데 크레딧이 들어오지 않았다면": { en: "If this was your first time and you didn't get credits, let us know via", ja: "初めての参加でクレジットが入っていない場合は、", zh: "如果是第一次参加却没有收到积分，请通过" },
  "로 알려 주세요.": { en: ".", ja: "からお知らせください。", zh: "告诉我们。" },
  "1:1 문의하기": { en: "Contact us", ja: "1:1お問い合わせ", zh: "1:1 咨询" },
  "확인": { en: "OK", ja: "OK", zh: "确定" },
  "보내고 {n}크레딧 받기": { en: "Send & get {n} credits", ja: "送信して{n}クレジットを受け取る", zh: "提交并领取 {n} 积分" },
  "웹툰 만들러 가기": { en: "Make a webtoon", ja: "ウェブトゥーンを作る", zh: "去做漫画" },
  "15분 인터뷰에 참여할게요": { en: "I'll join a 15-minute interview", ja: "15分のインタビューに参加します", zh: "我愿意参加 15 分钟访谈" },
  "체크해 주시면 인터뷰 가능한 날짜와 질문을 이메일로 미리 보내 드려요. 참여해 주시면 따로 보상도 드려요.": {
    en: "Check this and we'll email you available dates and the questions in advance. You'll get a separate reward for joining.",
    ja: "チェックしていただくと、候補日と質問を事前にメールでお送りします。ご参加いただくと別途特典もございます。",
    zh: "勾选后，我们会提前通过邮件发送可选日期和问题。参加还会获得额外奖励。",
  },
  "안내를 받을 이메일": { en: "Email for the invitation", ja: "ご案内を受け取るメール", zh: "接收通知的邮箱" },
  "보내 주셔서 고마워요!": { en: "Thank you!", ja: "ありがとうございます！", zh: "谢谢！" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "피드백 보내기": { en: "Send feedback", ja: "フィードバックを送る", zh: "发送反馈" },
  "보내지 못했어요. 잠시 뒤에 다시 눌러 주세요.": { en: "Couldn't send. Please try again in a moment.", ja: "送信できませんでした。少ししてからもう一度押してください。", zh: "发送失败，请稍后再试。" },
});
