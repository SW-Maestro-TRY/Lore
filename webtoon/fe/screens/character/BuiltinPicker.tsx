"use client";

/* 예시 캐릭터 고르기 — 내 캐릭터가 있으면 만들기 1걸음 줄에 예시 캐릭터가 안 나와서,
 * 따로 열어 고르는 창. 「새 캐릭터 만들기」 화면과 만들기 1걸음이 같이 쓴다.
 * 목록을 넘겨받으면 그대로 쓰고, 없으면 캐릭터 목록 API 에서 기본 제공만 추린다. */
import { useEffect, useState } from "react";
import { listCharacters, type Character } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import { Dialog } from "../../ui/Dialog";
import { IconCheck } from "../../ui/Icons";
import "./BuiltinPicker.css";

registerDict({
  "예시 캐릭터": { en: "Sample characters", ja: "サンプルキャラクター", zh: "示例角色" },
  "예시 캐릭터를 불러오지 못했습니다": { en: "Couldn't load sample characters", ja: "サンプルキャラクターを読み込めませんでした", zh: "无法加载示例角色" },
  "다시 시도": { en: "Try again", ja: "もう一度", zh: "重试" },
});

const usable = (c: Character) => c.builtin && c.status === "ready" && !!c.art_url;

export function BuiltinPicker({ characters, selectedId, onPick, onClose }: {
  /** 이미 불러온 캐릭터 목록. 없으면 창이 직접 불러온다. */
  characters?: Character[];
  selectedId?: string;
  onPick: (c: Character) => void;
  onClose: () => void;
}) {
  const t = useT();
  const [loaded, setLoaded] = useState<Character[] | null>(null);
  const [err, setErr] = useState("");
  const load = () => {
    setErr("");
    setLoaded(null);
    listCharacters()
      .then((got) => setLoaded(got.characters))
      .catch(() => { setLoaded([]); setErr(t("예시 캐릭터를 불러오지 못했습니다")); });
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { if (!characters) load(); }, [characters]);
  const list = (characters || loaded)?.filter(usable) ?? null;

  return (
    <Dialog wide title={t("예시 캐릭터")} onClose={onClose}>
      {err ? (
        <div className="wt-builtin-err" role="alert">{err}<button type="button" className="btn-ghost" onClick={load}>{t("다시 시도")}</button></div>
      ) : (
        <div className="wt-builtin-grid">
          {list === null
            ? [0, 1, 2, 3].map((i) => <div key={i} className="wt-builtin-card skeleton" />)
            : list.map((c) => (
              <button type="button" key={c.id} className={`wt-builtin-card${selectedId === c.id ? " on" : ""}`}
                      aria-pressed={selectedId === c.id} onClick={() => onPick(c)}>
                <img src={c.art_url || ""} alt="" />
                <b>{t(c.name)}</b>
                {selectedId === c.id && <span className="wt-builtin-selected"><IconCheck size={13} /></span>}
              </button>
            ))}
        </div>
      )}
    </Dialog>
  );
}
