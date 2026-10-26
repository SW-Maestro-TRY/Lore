"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useState } from "react";
import { publishCharacter, errorText } from "../../lib/community";
import "./Community.css";
export default function CharacterPublication({
  id,
  original = false,
  published,
  blocked,
  ready,
  onChange,
}: {
  id: string;
  original?: boolean;
  published: boolean;
  blocked: boolean;
  ready: boolean;
  onChange: (published: boolean) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function save(value: boolean) {
    setBusy(true);
    setError("");
    try {
      const r = await publishCharacter(id, value, agreed);
      onChange(r.published);
      setOpen(false);
      setAgreed(false);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  if (!ready) return null;
  return (
    <div className="wt-publication">
      {busy && <p role="status">{t("처리 중이에요…")}</p>}
      {blocked ? (
        <p>{t("신고 검토로 공개가 제한됐어요. 문의하기로 연락해 주세요.")}</p>
      ) : published ? (
        <>
          <p>{t("다른 사람이 이 캐릭터로 새 이야기를 만들 수 있어요.")}</p>
          <button
            type="button"
            className="btn btn-w btn-sm"
            disabled={busy}
            onClick={() => void save(false)}
          >
            {busy ? t("처리 중…") : t("캐릭터 공개 취소")}
          </button>
        </>
      ) : open ? (
        <>
          <p>
            {t(
              original ? "공개하면 올린 원본 이미지와 이름·설정이 보이고, 누구나 LORE 안에서 웹툰 생성에 사용할 수 있어요. 공개를 취소해도 이미 생성한 작품은 남아요." : "공개하면 이름·설명·캐릭터 그림이 보이고, 누구나 LORE 안에서 웹툰 생성에 사용할 수 있어요. 공개를 취소해도 이미 생성한 작품은 남아요. 원본 사진은 공개하지 않으며, 그림 파일의 별도 이용 권한을 주는 것은 아니에요.",
            )}
          </p>
          <label>
            <input
              type="checkbox"
              checked={agreed}
              onChange={(e) => setAgreed(e.target.checked)}
            />
            {t("이 캐릭터를 공유할 권리가 있으며, 위 이용 범위에 동의해요.")}
          </label>
          <div className="wt-community-actions">
            <button
              type="button"
              className="btn btn-p btn-sm"
              disabled={busy || !agreed}
              onClick={() => void save(true)}
            >
              {busy ? t("처리 중…") : t("동의하고 공개")}
            </button>
            <button
              type="button"
              className="btn-ghost"
              disabled={busy}
              onClick={() => setOpen(false)}
            >
              {t("취소")}
            </button>
          </div>
        </>
      ) : (
        <button
          type="button"
          className="btn btn-w btn-sm"
          onClick={() => setOpen(true)}
        >
          {t("다른 사람도 쓸 수 있게 공개")}
        </button>
      )}
      {error && (
        <p role="alert" className="wt-community-error">
          {t(error)}
        </p>
      )}
    </div>
  );
}
