"use client";
import { useEffect, useRef, useState } from "react";
import { uploadDataUrls } from "@common/api/uploads";
import {
  saveOriginalCharacter,
  renameCharacter,
  removeCharacter,
  uploadDataUrlsAsGuest,
  type Character,
} from "../../lib/api";
import { PHOTO_ACCEPT, readPhoto } from "../../lib/photoFile";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconPlus, IconEdit, IconTrash, IconClose } from "../../ui/Icons";
import { Dialog } from "../../ui/Dialog";
import { ErrLine, errText } from "../../ui/CreditShort";
import "./OriginalCharacter.css";

export function CharacterForm({
  character,
  initialDraft,
  authenticated,
  onSaved,
  onDeleted,
  onClose,
  onBusyChange,
  localOnly = false,
}: {
  character?: Character;
  initialDraft?: { name: string; description: string; image: string };
  authenticated: boolean;
  onSaved: (c: Character) => void;
  onDeleted?: () => void;
  onClose: () => void;
  onBusyChange?: (value: boolean) => void;
  /** 예시 캐릭터 — 고친 것을 저장하지 않고 이번 웹툰 요청에만 쓴다(2026-10-25). 그림은 못 바꾼다. */
  localOnly?: boolean;
}) {
  const t = useT(),
    file = useRef<HTMLInputElement>(null);
  const [name, setName] = useState(character?.name || initialDraft?.name || ""),
    [description, setDescription] = useState(
      character?.description || initialDraft?.description || "",
    );
  const [photo, setPhoto] = useState(initialDraft?.image || ""),
    [error, setError] = useState(""),
    [working, setBusy] = useState(false),
    [reading, setReading] = useState(false),
    [confirmDelete, setConfirmDelete] = useState(false);
  const photoRevision = useRef(0);
  const busy = working || reading;
  useEffect(() => () => { photoRevision.current++; }, []);
  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);
  const save = async () => {
    if (busy || !name.trim() || (!photo && !character)) return;
    if (localOnly && character) { onSaved({ ...character, name: name.trim(), description }); return; }
    setBusy(true);
    setError("");
    try {
      const result = photo
        ? await saveOriginalCharacter(
            {
              name: name.trim(),
              description,
              photo_keys: authenticated
                ? await uploadDataUrls([photo], "webtoon")
                : await uploadDataUrlsAsGuest([photo]),
            },
            character?.id,
          )
        : await renameCharacter(character!.id, name.trim(), description);
      onSaved(result);
    } catch (e) {
      setError(errText(e, t("저장하지 못했습니다")));
    } finally {
      setBusy(false);
    }
  };
  const drop = async () => {
    if (!character || busy) return;
    setBusy(true);
    setError("");
    try {
      await removeCharacter(character.id);
      onDeleted?.();
    } catch (e) {
      setError(errText(e, t("삭제하지 못했습니다")));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="wt-original-form">
      {confirmDelete ? (
        <div className="wt-original-delete-confirm">
          <IconTrash size={28} />
          <h3>{t("캐릭터를 삭제할까요?")}</h3>
          <b>{character?.name}</b>
          <p>{t("이미 만든 웹툰은 유지됩니다.")}</p>
          <ErrLine text={error} />
          <div className="wt-original-actions">
            <button
              className="btn btn-w"
              disabled={busy}
              onClick={() => setConfirmDelete(false)}
            >
              {t("취소")}
            </button>
            <button
              className="btn wt-danger-fill"
              disabled={busy}
              onClick={() => void drop()}
            >
              {busy ? t("삭제 중…") : t("삭제하기")}
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="wt-original-fields">
            <button
              type="button"
              className={`wt-original-image${photo || character?.art_url ? " has" : ""}`}
              disabled={busy || localOnly}
              onClick={() => file.current?.click()}
              aria-label={t("캐릭터 이미지 선택")}
            >
              {photo || character?.art_url ? (
                <>
                  <img src={photo || character?.art_url || ""} alt={name} />
                  {!localOnly && <span>
                    <IconEdit size={15} />
                    <b>{t("이미지 변경")}</b>
                  </span>}
                </>
              ) : (
                <>
                  <IconPlus size={28} />
                  <b>{t("이미지 올리기")}</b>
                </>
              )}
            </button>
            <input
              ref={file}
              type="file"
              accept={PHOTO_ACCEPT}
              hidden
              onChange={async (e) => {
                const f = e.target.files?.[0];
                e.target.value = "";
                if (!f) return;
                const revision = ++photoRevision.current;
                setReading(true);
                setError("");
                try {
                  const image = await readPhoto(f);
                  if (photoRevision.current === revision) setPhoto(image);
                } catch (e) {
                  if (photoRevision.current === revision) setError(errText(e, t("이미지를 읽지 못했습니다")));
                } finally {
                  if (photoRevision.current === revision) setReading(false);
                }
              }}
            />
            <div className="wt-original-text">
              <label>
                {t("이름")}
                <input
                  className="field"
                  value={name}
                  maxLength={60}
                  disabled={busy}
                  onChange={(e) => setName(e.target.value)}
                />
              </label>
              <label>
                {t("캐릭터 설정")}
                <textarea
                  className="field"
                  rows={4}
                  maxLength={8000}
                  value={description}
                  disabled={busy}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </label>
            </div>
          </div>
          {localOnly && <p className="wt-original-localnote">{t("예시 캐릭터는 저장되지 않아요. 고친 이름·설정은 이번 웹툰에만 쓰여요.")}</p>}
          <ErrLine text={error} />
          {character && onDeleted && !localOnly && (
            <button
              type="button"
              className="wt-original-delete"
              disabled={busy}
              onClick={() => setConfirmDelete(true)}
            >
              <IconTrash size={17} />
              {t("캐릭터 삭제")}
            </button>
          )}
          <div className="wt-original-actions">
            <button
              type="button"
              className="btn btn-w"
              disabled={busy}
              onClick={onClose}
            >
              {t("취소")}
            </button>
            <button
              type="button"
              className="btn btn-p"
              disabled={busy || !name.trim() || (!photo && !character)}
              onClick={() => void save()}
            >
              {reading ? t("이미지 읽는 중…") : busy
                ? t("저장 중…")
                : localOnly
                  ? t("이번 웹툰에만 적용")
                  : character
                  ? t("저장")
                  : t("캐릭터 만들기")}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
export function CharacterEditDialog(props: {
  character: Character;
  authenticated: boolean;
  localOnly?: boolean;
  onSaved: (c: Character) => void;
  onDeleted: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  return (
    <Dialog wide title={t("캐릭터 수정")} onClose={props.onClose} busy={busy}>
      <CharacterForm {...props} onBusyChange={setBusy} />
    </Dialog>
  );
}
export default function OriginalCharacter({
  go,
  authenticated,
}: {
  go: Go;
  authenticated: boolean;
}) {
  const t = useT();
  return (
    <section className="wt-wrap wt-page wt-original-page">
      <div className="wt-original-heading">
        <h1>{t("새 캐릭터 만들기")}</h1>
        <button className="btn btn-w btn-sm" onClick={() => go("try")}>
          {t("AI 캐릭터 뽑기")}
        </button>
      </div>
      <CharacterForm
        authenticated={authenticated}
        onClose={() => go("create", { step: 1 })}
        onSaved={(c) => go("create", { step: 1, character: c.id })}
      />
    </section>
  );
}
