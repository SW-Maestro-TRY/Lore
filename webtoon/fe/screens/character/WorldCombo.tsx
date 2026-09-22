"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useT } from "../../lib/i18n";
import { IconChevronDown, IconClose } from "../../ui/Icons";

/* 세계관 고르개 — 적으면서 고른다.
 *
 * 전에는 **버튼 열 개와 「직접 쓰기」 칸이 따로** 있었다. 고른 것과 적은 것이
 * 서로를 지우는 관계인데 자리가 둘이라, 무엇이 지금 값인지 화면만 봐서는
 * 알기 어려웠다(버튼이 켜져 있는데 아래 칸에도 글자가 남아 있는 식).
 *
 * 지금은 칸 하나다. 적으면 아래에 맞는 것만 남고, 그중 하나를 고르면 그것이
 * 값이 된다. 목록에 없는 것은 적은 그대로 값이 된다 — 그래서 「직접 쓰기」가
 * 따로 필요 없다.
 *
 * **PC 와 폰이 같은 부품이다.** 폰에서 `<select>` 로 바꾸면 직접 적을 수가
 * 없어진다(고르기만 된다). 목록을 흐름 안에 그려서 손가락으로도 눌리게 한다.
 */

export interface World {
  key: string;
  label: string;
}

export default function WorldCombo({
  worlds, worldKey, worldText, onPick, onType,
}: {
  worlds: World[];
  /** 고른 프리셋의 키. 직접 적은 값이 있으면 빈 문자열. */
  worldKey: string;
  /** 직접 적은 값. 프리셋을 골랐으면 빈 문자열. */
  worldText: string;
  onPick: (key: string) => void;
  onType: (text: string) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [at, setAt] = useState(-1);          // 키보드로 짚고 있는 줄
  const box = useRef<HTMLDivElement>(null);

  const picked = worlds.find((w) => w.key === worldKey);
  /* 칸에 보이는 글자. 프리셋을 골랐으면 그 이름, 아니면 적은 그대로. */
  const shown = picked ? t(picked.label) : worldText;

  /* 적은 글자로 목록을 좁힌다. 프리셋을 고른 상태에서 다시 열면 전부 보인다 —
     그 이름으로 좁히면 고른 것 하나만 남아 바꾸기가 더 어렵다. */
  const list = useMemo(() => {
    const q = (picked ? "" : worldText).trim().toLowerCase();
    if (!q) return worlds;
    return worlds.filter((w) => t(w.label).toLowerCase().includes(q) || w.key.includes(q));
  }, [worlds, worldText, picked, t]);

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  useEffect(() => { setAt(-1); }, [worldText, open]);

  const choose = (w: World) => {
    onPick(w.key);
    setOpen(false);
  };

  const clear = () => {
    onPick("");
    onType("");
    setOpen(false);
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") { setOpen(false); return; }
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) { setOpen(true); return; }
      if (!list.length) return;
      const step = e.key === "ArrowDown" ? 1 : -1;
      setAt((i) => (i + step + list.length) % list.length);
      return;
    }
    if (e.key === "Enter" && open && at >= 0 && list[at]) {
      e.preventDefault();
      choose(list[at]);
    }
  };

  return (
    <div className="wt-ch-combo" ref={box}>
      <div className="wt-ch-combo-box">
        <input
          id="wt-ch-wd"
          className="field"
          role="combobox"
          aria-expanded={open}
          aria-controls="wt-ch-combo-list"
          aria-autocomplete="list"
          aria-activedescendant={open && at >= 0 && list[at] ? `wt-ch-w-${list[at].key}` : undefined}
          value={shown}
          placeholder={t("고르거나 직접 적기 · 예: 무협 / 좀비 아포칼립스")}
          onChange={(e) => {
            /* 프리셋을 고른 상태에서 글자를 고치면 그 순간부터 직접 적는 것이다. */
            if (picked) onPick("");
            onType(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onClick={() => setOpen(true)}
          onKeyDown={onKey}
        />
        {shown ? (
          <button type="button" className="icon-btn wt-ch-combo-x" aria-label={t("지우기")} onClick={clear}>
            <IconClose size={15} />
          </button>
        ) : (
          <button type="button" className="icon-btn wt-ch-combo-x" aria-label={t("목록 열기")}
                  onClick={() => setOpen((v) => !v)}>
            <IconChevronDown size={16} />
          </button>
        )}
      </div>

      {open && (
        <ul className="wt-ch-combo-list" id="wt-ch-combo-list" role="listbox" aria-label={t("세계관")}>
          {list.length === 0 ? (
            <li className="wt-ch-combo-none">{t("목록에 없어요 — 적은 그대로 씁니다")}</li>
          ) : list.map((w, i) => (
            <li key={w.key}>
              <button type="button" id={`wt-ch-w-${w.key}`} role="option"
                      aria-selected={w.key === worldKey}
                      className={`wt-ch-combo-one${i === at ? " at" : ""}${w.key === worldKey ? " on" : ""}`}
                      onMouseEnter={() => setAt(i)}
                      onClick={() => choose(w)}>
                {t(w.label)}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
