"use client";
import { useEffect, useRef, useState } from "react";
import { uploadDataUrls } from "@common/api/uploads";
import {
  saveOriginalCharacter,
  renameCharacter,
  editCharacterCard,
  listWorlds,
  type World,
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
import { BuiltinPicker } from "./BuiltinPicker";

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
  "카드 내용": { en: "Card details", ja: "カードの内容", zh: "卡片内容" },
  "웹툰을 만들 때 이 내용이 그대로 들어가요. 비운 칸은 들어가지 않아요.": {
    en: "These details go into your webtoon exactly as written. Empty fields are left out.",
    ja: "ウェブトゥーンを作るとき、この内容がそのまま使われます。空欄は使われません。",
    zh: "制作漫画时会原样使用这些内容。空着的项不会使用。",
  },
  "세계관": { en: "World", ja: "世界観", zh: "世界观" },
  "세계관 이름": { en: "World name", ja: "世界観の名前", zh: "世界观名称" },
  "정하지 않음": { en: "None", ja: "指定しない", zh: "不指定" },
  "장르": { en: "Genre", ja: "ジャンル", zh: "类型" },
  "종": { en: "Species", ja: "種族", zh: "种族" },
  "역할": { en: "Role", ja: "役割", zh: "角色" },
  "비중": { en: "Part", ja: "役の大きさ", zh: "戏份" },
  "주연": { en: "Lead", ja: "主役", zh: "主角" },
  "조연": { en: "Supporting", ja: "脇役", zh: "配角" },
  "단역": { en: "Bit part", ja: "端役", zh: "龙套" },
  "엑스트라": { en: "Extra", ja: "エキストラ", zh: "群演" },
  "한 줄 소개": { en: "One-line intro", ja: "ひとこと紹介", zh: "一句话介绍" },
  "운명": { en: "Fate", ja: "運命", zh: "命运" },
  "한 줄에 하나씩 적어요. 지우면 웹툰에 안 들어가요.": {
    en: "One per line. Delete a line to leave it out of the webtoon.",
    ja: "1行に1つずつ。消した行はウェブトゥーンに入りません。",
    zh: "每行一条。删掉的行不会进入漫画。",
  },
  "대사": { en: "Lines", ja: "セリフ", zh: "台词" },
  "한 줄에 하나씩 「누구: 말」로 적어요.": {
    en: "One per line, as \"Who: line\".",
    ja: "1行に1つずつ「誰: セリフ」の形で。",
    zh: "每行一条，格式为「谁: 台词」。",
  },
});

/** 자리의 무게 — 하네스 값과 화면 딱지(PhotoResult 의 TIER_LABEL 과 같다). */
const TIERS: [string, string][] = [["중심", "주연"], ["곁", "조연"], ["스쳐감", "단역"], ["뜬금", "엑스트라"]];

/** 「누구: 말」 한 줄을 나눈다. 앞이 짧은 이름처럼 보일 때만 나누고, 아니면 통째로 말이다. */
function splitLine(line: string): { who: string; text: string } {
  const m = line.match(/^([^:：]{1,30})[:：]\s*(.+)$/);
  return m ? { who: m[1].trim(), text: m[2].trim() } : { who: "", text: line.trim() };
}

function lines(text: string): string[] {
  return text.split("\n").map((l) => l.trim()).filter(Boolean);
}

/** 카드 캐릭터인가 — AI 로 만든 카드가 있고, 사진으로 바꾸지 않은 것. */
function hasEditableCard(c?: Character): boolean {
  return !!c?.card && !c.builtin && c.source !== "upload" && c.source !== "replaced_upload";
}

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
  const cardMode = !localOnly && hasEditableCard(character);
  const card = character?.card;
  const [name, setName] = useState(character?.name || initialDraft?.name || ""),
    [description, setDescription] = useState(
      // 카드 캐릭터는 사람이 적은 설정 글만 — description 은 비어 있으면 카드 칸을 이어 붙인 글이라 아래 칸과 겹친다
      (cardMode ? character?.own_description : character?.description) || initialDraft?.description || "",
    );
  const [worldKey, setWorldKey] = useState(card?.world || ""),
    [worldLabel, setWorldLabel] = useState(card?.world_label || ""),
    [genre, setGenre] = useState(card?.genre || ""),
    [species, setSpecies] = useState(card?.species || ""),
    [role, setRole] = useState(card?.role || ""),
    [roleTier, setRoleTier] = useState(card?.role_tier || ""),
    [twist, setTwist] = useState(card?.twist || ""),
    [fate, setFate] = useState((card?.fate || []).join("\n")),
    [dialogue, setDialogue] = useState(
      card?.dialogue?.length
        ? card.dialogue.map((l) => (l.who ? `${l.who}: ${l.text}` : l.text)).join("\n")
        : card?.quote || "",
    ),
    [worlds, setWorlds] = useState<World[]>([]);
  useEffect(() => {
    if (!cardMode) return;
    let alive = true;
    listWorlds().then((r) => { if (alive) setWorlds(r.worlds || []); }).catch(() => { /* 목록이 없으면 지금 값만 */ });
    return () => { alive = false; };
  }, [cardMode]);
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
      if (cardMode && character) {
        const edited = await editCharacterCard(character.id, {
          name: name.trim(), description, world: worldKey, world_label: worldLabel, genre, species, role,
          role_tier: roleTier, twist, fate: lines(fate), dialogue: lines(dialogue).map(splitLine),
        });
        if (!photo) { onSaved(edited); return; }
      }
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
          {cardMode && (
            <div className="wt-original-card">
              <div className="wt-original-card-head">
                <h3>{t("카드 내용")}</h3>
                <p>{t("웹툰을 만들 때 이 내용이 그대로 들어가요. 비운 칸은 들어가지 않아요.")}</p>
              </div>
              <div className="wt-original-card-grid">
                <label>
                  {t("세계관")}
                  <select
                    className="field"
                    value={worldKey}
                    disabled={busy}
                    onChange={(e) => {
                      const next = e.target.value;
                      const label = (k: string) => worlds.find((w) => w.key === k)?.label || "";
                      // 세계관 이름이 비었거나 고르기 전 세계관의 이름 그대로일 때만 새 이름으로 바꾼다 — 사람이 적은 이름은 두고
                      if (!worldLabel.trim() || worldLabel === label(worldKey)) setWorldLabel(label(next));
                      setWorldKey(next);
                    }}
                  >
                    <option value="">{t("정하지 않음")}</option>
                    {worldKey && !worlds.some((w) => w.key === worldKey) && <option value={worldKey}>{card?.world_label || worldKey}</option>}
                    {worlds.map((w) => <option key={w.key} value={w.key}>{t(w.label)}</option>)}
                  </select>
                </label>
                <label>
                  {t("세계관 이름")}
                  <input className="field" value={worldLabel} maxLength={20} disabled={busy} onChange={(e) => setWorldLabel(e.target.value)} />
                </label>
                <label>
                  {t("장르")}
                  <input className="field" value={genre} maxLength={40} disabled={busy} onChange={(e) => setGenre(e.target.value)} />
                </label>
                <label>
                  {t("종")}
                  <input className="field" value={species} maxLength={40} disabled={busy} onChange={(e) => setSpecies(e.target.value)} />
                </label>
                <label>
                  {t("역할")}
                  <input className="field" value={role} maxLength={40} disabled={busy} onChange={(e) => setRole(e.target.value)} />
                </label>
                <label>
                  {t("비중")}
                  <select className="field" value={roleTier} disabled={busy} onChange={(e) => setRoleTier(e.target.value)}>
                    <option value="">{t("정하지 않음")}</option>
                    {TIERS.map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
                  </select>
                </label>
              </div>
              <label>
                {t("한 줄 소개")}
                <textarea className="field" rows={2} maxLength={300} value={twist} disabled={busy} onChange={(e) => setTwist(e.target.value)} />
              </label>
              <label>
                {t("운명")}
                <small>{t("한 줄에 하나씩 적어요. 지우면 웹툰에 안 들어가요.")}</small>
                <textarea className="field" rows={3} value={fate} disabled={busy} onChange={(e) => setFate(e.target.value)} />
              </label>
              <label>
                {t("대사")}
                <small>{t("한 줄에 하나씩 「누구: 말」로 적어요.")}</small>
                <textarea className="field" rows={3} value={dialogue} disabled={busy} onChange={(e) => setDialogue(e.target.value)} />
              </label>
            </div>
          )}
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
  /* 예시 캐릭터 — 내 캐릭터가 있으면 만들기 1걸음 줄에 안 나와서 여기서도 고를 수 있게 둔다.
     고르면 만들기 1걸음으로 돌아가 그 캐릭터가 골라져 있다(저장하지 않는다). */
  const [pickingBuiltin, setPickingBuiltin] = useState(false);
  return (
    <section className="wt-wrap wt-page wt-original-page">
      {pickingBuiltin && <BuiltinPicker onClose={() => setPickingBuiltin(false)}
        onPick={(c) => go("create", { step: 1, character: c.id })} />}
      <div className="wt-original-heading">
        <h1>{t("새 캐릭터 만들기")}</h1>
        <div className="wt-original-heading-actions">
          <button className="btn btn-w btn-sm" onClick={() => setPickingBuiltin(true)}>
            {t("예시 캐릭터")}
          </button>
          <button className="btn btn-w btn-sm" onClick={() => go("try")}>
            {t("AI 캐릭터 뽑기")}
          </button>
        </div>
      </div>
      <CharacterForm
        authenticated={authenticated}
        onClose={() => go("create", { step: 1 })}
        onSaved={(c) => go("create", { step: 1, character: c.id })}
      />
    </section>
  );
}
