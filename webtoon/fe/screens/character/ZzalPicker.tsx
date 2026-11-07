"use client";

/* 짤 캐릭터 가져오기 — 짤(키우기)에서 키운 캐릭터의 이름·설정과 그림 한 장으로 웹툰 내 캐릭터를 만든다.
 * 그림은 원본 그림과 캐릭터 시트 중에서 고른다. 가져오기는 원본 등록과 같아서 AI 호출·크레딧이 없다.
 * 「새 캐릭터 만들기」 화면이 연다. */
import { useEffect, useState } from "react";
import { importZzalCharacter, listZzalCharacters, type Character, type ZzalCharacter } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import { Dialog } from "../../ui/Dialog";
import { ErrLine, errText } from "../../ui/CreditShort";
import { IconCheck } from "../../ui/Icons";
import "./BuiltinPicker.css";
import "./ZzalPicker.css";

registerDict({
  "짤 캐릭터": { en: "Zzal characters", ja: "Zzalキャラクター", zh: "Zzal 角色" },
  "짤 캐릭터가 없어요": { en: "No Zzal characters yet", ja: "Zzalキャラクターがいません", zh: "还没有 Zzal 角色" },
  "짤 캐릭터를 불러오지 못했습니다": { en: "Couldn't load Zzal characters", ja: "Zzalキャラクターを読み込めませんでした", zh: "无法加载 Zzal 角色" },
  "원본 그림": { en: "Original art", ja: "元の絵", zh: "原图" },
  "캐릭터 시트": { en: "Character sheet", ja: "キャラクターシート", zh: "角色设定图" },
  "가져오기": { en: "Import", ja: "取り込む", zh: "导入" },
  "가져오지 못했습니다": { en: "Couldn't import", ja: "取り込めませんでした", zh: "导入失败" },
  "가져오는 중…": { en: "Importing…", ja: "取り込み中…", zh: "导入中…" },
});

export function ZzalPicker({ onImported, onClose }: {
  onImported: (c: Character) => void;
  onClose: () => void;
}) {
  const t = useT();
  const [list, setList] = useState<ZzalCharacter[] | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [picked, setPicked] = useState<ZzalCharacter | null>(null);
  const [image, setImage] = useState<"image" | "sheet">("image");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = () => {
    setLoadErr("");
    setList(null);
    listZzalCharacters()
      .then((got) => setList(got.characters))
      .catch(() => { setList([]); setLoadErr(t("짤 캐릭터를 불러오지 못했습니다")); });
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, []);

  const pick = (c: ZzalCharacter) => {
    setPicked(c);
    setImage(c.image_url ? "image" : "sheet");
    setError("");
  };

  const save = async () => {
    if (!picked) return;
    setBusy(true);
    setError("");
    try {
      onImported(await importZzalCharacter(picked.pet_id, image));
    } catch (e) {
      setError(errText(e, t("가져오지 못했습니다")));
      setBusy(false);
    }
  };

  if (picked) {
    const choices = ([["image", picked.image_url, "원본 그림"], ["sheet", picked.sheet_url, "캐릭터 시트"]] as const)
      .filter(([, url]) => !!url);
    return (
      <Dialog wide title={picked.name} onClose={onClose} busy={busy}
              onBack={busy ? undefined : () => setPicked(null)} backLabel={t("짤 캐릭터")}>
        <div className="wt-zzal-images">
          {choices.map(([key, url, label]) => (
            <button type="button" key={key} className={`wt-zzal-image${image === key ? " on" : ""}`}
                    aria-pressed={image === key} disabled={busy} onClick={() => setImage(key)}>
              <img src={url || ""} alt="" />
              <b>{t(label)}</b>
              {image === key && <span className="wt-builtin-selected"><IconCheck size={13} /></span>}
            </button>
          ))}
        </div>
        {picked.description && <p className="wt-zzal-description">{picked.description}</p>}
        <ErrLine text={error} />
        <div className="wt-zzal-actions">
          <button type="button" className="btn btn-w" disabled={busy} onClick={onClose}>{t("취소")}</button>
          <button type="button" className="btn btn-p" disabled={busy} onClick={() => void save()}>
            {busy ? t("가져오는 중…") : t("가져오기")}
          </button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog wide title={t("짤 캐릭터")} onClose={onClose}>
      {loadErr ? (
        <div className="wt-builtin-err" role="alert">{loadErr}<button type="button" className="btn-ghost" onClick={load}>{t("다시 시도")}</button></div>
      ) : list && list.length === 0 ? (
        <p className="wt-zzal-empty">{t("짤 캐릭터가 없어요")}</p>
      ) : (
        <div className="wt-builtin-grid">
          {list === null
            ? [0, 1, 2, 3].map((i) => <div key={i} className="wt-builtin-card skeleton" />)
            : list.map((c) => (
              <button type="button" key={c.pet_id} className="wt-builtin-card" onClick={() => pick(c)}>
                <img src={c.image_url || c.sheet_url || ""} alt="" />
                <b>{c.name}</b>
              </button>
            ))}
        </div>
      )}
    </Dialog>
  );
}
