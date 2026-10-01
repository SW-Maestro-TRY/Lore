import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "@common/api/client";
import { FEEDBACK_MAX_LENGTH, stripFeedbackBody, submitFeedback, type FeedbackKind } from "../lib/api";

type Props = {
  onClose: () => void;
  onSent: () => void;
  /** 로그인한 독자인가. 그러면 보내기 전에 로그인 상태를 되살려 누가 보냈는지 남게 한다(lib/api.ts submitFeedback). */
  loggedIn: boolean;
};

export default function FeedbackPanel({ onClose, onSent, loggedIn }: Props) {
  const [kind, setKind] = useState<FeedbackKind>("ERROR_REPORT");
  const [body, setBody] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);
  const pending = useRef<AbortController | null>(null);
  const text = stripFeedbackBody(body);
  const valid = text.length > 0 && text.length <= FEEDBACK_MAX_LENGTH;

  useEffect(() => {
    // 부모가 showModal()로 창을 연 다음 프레임에 입력창으로 포커스를 옮긴다.
    const frame = requestAnimationFrame(() => {
      const el = field.current;
      if (el?.isConnected && el.closest("dialog")?.open) el.focus({ preventScroll: true });
    });
    return () => {
      cancelAnimationFrame(frame);
      // 응답 대기 취소는 이미 서버에 접수된 피드백의 취소를 뜻하지 않는다.
      pending.current?.abort();
      pending.current = null;
    };
  }, []);

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || !valid) return;
    const attempt = new AbortController();
    pending.current = attempt;
    setSending(true);
    setError("");
    try {
      await submitFeedback({ kind, body: text }, attempt.signal, loggedIn);
      if (pending.current === attempt && !attempt.signal.aborted) onSent();
    } catch (cause) {
      if (pending.current !== attempt || attempt.signal.aborted) return;
      const reason = cause instanceof ApiError ? ` ${cause.message}` : " 잠시 뒤 다시 시도해 주세요.";
      setError(`피드백을 보내지 못했어요.${reason} 입력한 내용은 그대로 있어요.`);
    } finally {
      if (pending.current === attempt && !attempt.signal.aborted) {
        pending.current = null;
        setSending(false);
      }
    }
  }

  return (
    <form className="feedback-panel" data-part="feedback-panel" onSubmit={(event) => void send(event)} aria-busy={sending}>
      <p className="feedback-intro">불편했던 점이나 판정에 대한 의견을 알려주세요. 로그인 없이 보낼 수 있어요.</p>
      <fieldset className="feedback-kinds" disabled={sending}>
        <legend className="label">피드백 종류</legend>
        <div className="feedback-kind-options">
          {([
            ["ERROR_REPORT", "오류 신고"],
            ["JUDGEMENT_REVIEW", "판정 후기"],
          ] as const).map(([value, label]) => (
            <label className="feedback-kind-option" key={value}>
              <input type="radio" name="piece-maker-feedback-kind" value={value} checked={kind === value}
                onChange={() => { setKind(value); setError(""); }} />
              <span>{label}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <div>
        <label className="label" htmlFor="piece-maker-feedback-body">피드백 내용</label>
        <textarea ref={field} id="piece-maker-feedback-body" className="field feedback-body" data-part="feedback-body" value={body}
          maxLength={FEEDBACK_MAX_LENGTH} required disabled={sending}
          aria-describedby={`piece-maker-feedback-count${error ? " piece-maker-feedback-error" : ""}`}
          placeholder="어떤 점이 불편했나요? 판정에서 좋았거나 아쉬웠던 점도 남겨주세요."
          onChange={(event) => { setBody(event.target.value); setError(""); }} />
        <p className="feedback-count" id="piece-maker-feedback-count">{body.length.toLocaleString()} / {FEEDBACK_MAX_LENGTH.toLocaleString()}자</p>
      </div>
      {error && <p className="feedback-error" id="piece-maker-feedback-error" role="alert" data-part="feedback-error">{error}</p>}
      <div className="feedback-actions">
        <button type="button" className="btn" data-action="close" onClick={onClose}>닫기</button>
        <button type="submit" className="btn primary" data-action="send-feedback" disabled={sending || !valid}>
          {sending ? "보내는 중…" : "피드백 보내기"}
        </button>
      </div>
    </form>
  );
}
