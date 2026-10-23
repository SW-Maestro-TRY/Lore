"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useState } from "react";
import { report, errorText } from "../../lib/community";
export default function ReportForm({
  kind,
  target,
  onClose,
}: {
  kind: string;
  target: string;
  onClose: () => void;
}) {
  const t = useT();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [sent, setSent] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await report(kind, target, reason);
      setSent(true);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  if (sent)
    return (
      <p role="status">
        {t("신고를 접수했어요. 운영자가 확인하겠습니다.")}{" "}
        <button className="btn-ghost" type="button" onClick={onClose}>
          {t("닫기")}
        </button>
      </p>
    );
  return (
    <form className="wt-report" onSubmit={submit}>
      {busy && <p role="status">{t("처리 중이에요…")}</p>}
      <label>
        {t("신고 사유")}
        <textarea
          className="field"
          required
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder={t("문제가 되는 내용을 알려 주세요.")}
        />
      </label>
      <div className="wt-community-actions">
        <button className="btn btn-w btn-sm" disabled={busy || !reason.trim()}>
          {busy ? t("처리 중…") : t("신고 접수")}
        </button>
        <button
          className="btn-ghost"
          type="button"
          disabled={busy}
          onClick={onClose}
        >
          {t("취소")}
        </button>
      </div>
      {error && (
        <p role="alert" className="wt-community-error">
          {t(error)}
        </p>
      )}
    </form>
  );
}
