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
import { registerDict, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconPlus, IconEdit, IconTrash, IconClose } from "../../ui/Icons";
import { Dialog } from "../../ui/Dialog";
import { ErrLine, errText } from "../../ui/CreditShort";
import "./OriginalCharacter.css";

registerDict({
  "AI 캐릭터 뽑기": { en: "Generate an AI character", ja: "AIキャラクターを作る", zh: "AI 生成角色" },
  "삭제 중…": { en: "Deleting…", ja: "削除中…", zh: "删除中…" },
  "삭제하기": { en: "Delete", ja: "削除する", zh: "删除" },
  "삭제하지 못했습니다": { en: "Couldn't delete", ja: "削除できませんでした", zh: "删除失败" },
  "이미 만든 웹툰은 유지됩니다.": { en: "Webtoons you've already made will be kept.", ja: "すでに作ったウェブトゥーンは残ります。", zh: "已制作的漫画会保留。" },
  "이미지 변경": { en: "Change image", ja: "画像を変更", zh: "更换图片" },
  "이미지 올리기": { en: "Upload image", ja: "画像をアップロード", zh: "上传图片" },
  "이미지 읽는 중…": { en: "Reading image…", ja: "画像を読み込み中…", zh: "正在读取图片…" },
  "이미지를 읽지 못했습니다": { en: "Couldn't read the image", ja: "画像を読み込めませんでした", zh: "无法读取图片" },
  "저장 중…": { en: "Saving…", ja: "保存中…", zh: "保存中…" },
  "캐릭터 설정": { en: "Character details", ja: "キャラクター設定", zh: "角色设定" },
  "캐릭터 수정": { en: "Edit character", ja: "キャラクターを編集", zh: "编辑角色" },
  "캐릭터 이미지 선택": { en: "Choose character image", ja: "キャラクター画像を選択", zh: "选择角色图片" },
  "캐릭터를 삭제할까요?": { en: "Delete this character?", ja: "キャラクターを削除しますか？", zh: "要删除这个角色吗？" },
});

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
          <ErrLine text={error} />
          {/* 수정 창에는 삭제를 두지 않는다 — 지우기는 마이페이지 「내 캐릭터」의 「삭제」에서 골라서(휴지통 15일) */}
          <div className="wt-original-actions">
            {localOnly && <p className="wt-original-localnote">{t("예시 캐릭터는 저장되지 않아요. 고친 이름·설정은 이번 웹툰에만 쓰여요.")}</p>}
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
